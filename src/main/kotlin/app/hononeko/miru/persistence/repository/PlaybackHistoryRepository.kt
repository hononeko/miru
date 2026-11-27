package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.escapeLikePattern
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.exposedReadQuery
import app.hononeko.miru.persistence.model.ClientStatsRow
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistoryPage
import app.hononeko.miru.persistence.model.HistorySortDirection
import app.hononeko.miru.persistence.model.HistorySortField
import app.hononeko.miru.persistence.model.MediaPlayRow
import app.hononeko.miru.persistence.model.MediaPlaySummary
import app.hononeko.miru.persistence.model.OverviewStatsRow
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryStatsRow
import app.hononeko.miru.persistence.model.UserPlaybackSummary
import app.hononeko.miru.persistence.table.MediaItemsCacheTable
import app.hononeko.miru.persistence.table.PlaybackHistoryTable
import app.hononeko.miru.persistence.table.UsersTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.roundToLong

private const val IMPORT_KEY_QUERY_CHUNK_SIZE = 500
private const val STATS_STREAM_FETCH_SIZE = 500
private const val COMPLETION_ROUNDING_SCALE = 100.0

interface PlaybackHistoryRepository {
    suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord>

    suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean>

    suspend fun insertBatchIfAbsentByImportKey(records: List<PlaybackHistoryRecord>): Either<MiruError.PersistenceError, Int>

    suspend fun findExistingImportKeys(keys: Set<String>): Either<MiruError.PersistenceError, Set<String>>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, PlaybackHistoryRecord?>

    suspend fun findLatestMergeCandidate(
        serverId: String,
        userId: String,
        ratingKey: String,
        minStoppedAtMs: Long,
        playerMachineId: String? = null,
    ): Either<MiruError.PersistenceError, PlaybackHistoryRecord?>

    suspend fun updateMergedSession(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean>

    suspend fun query(filter: HistoryFilter): Either<MiruError.PersistenceError, HistoryPage>

    suspend fun summarizeByUser(
        userIds: Set<String>? = null,
    ): Either<MiruError.PersistenceError, Map<String, UserPlaybackSummary>>

    suspend fun summarizeByRatingKey(
        ratingKey: String,
        allowedUserIds: Set<String>? = null,
        serverId: String? = null,
    ): Either<MiruError.PersistenceError, MediaPlaySummary>

    suspend fun summarizeSeriesByTitle(
        seriesTitle: String,
        allowedUserIds: Set<String>? = null,
        serverId: String? = null,
    ): Either<MiruError.PersistenceError, MediaPlaySummary>

    suspend fun listRecentByUser(
        userId: String,
        limit: Int = 10,
    ): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>>

    suspend fun listByUser(userId: String): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>>

    suspend fun listForStats(
        serverId: String? = null,
        userIds: Set<String>? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Either<MiruError.PersistenceError, List<PlaybackHistoryStatsRow>>

    suspend fun forEachStatsRow(
        serverId: String? = null,
        userIds: Set<String>? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        onRow: (PlaybackHistoryStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> =
        listForStats(serverId = serverId, userIds = userIds, fromMs = fromMs, toMs = toMs).map { rows ->
            rows.forEach(onRow)
        }

    suspend fun forEachOverviewStatsRow(
        serverId: String? = null,
        userIds: Set<String>? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        onRow: (OverviewStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> =
        listForStats(serverId = serverId, userIds = userIds, fromMs = fromMs, toMs = toMs).map { rows ->
            for (row in rows) {
                onRow(
                    OverviewStatsRow(
                        userId = row.userId,
                        mediaType = row.mediaType,
                        ratingKey = row.ratingKey,
                        title = row.title,
                        grandparentTitle = row.grandparentTitle,
                        thumbPath = row.thumbPath,
                        watchedDurationSec = row.watchedDurationSec,
                        streamDecision = row.streamDecision,
                        hwTranscode = row.hwTranscode,
                        stoppedAtMs = row.stoppedAtMs,
                    ),
                )
            }
        }

    /** Plays of [mediaTypes] on a server that stopped at or after [fromMs], oldest first. */
    suspend fun forEachMediaPlay(
        serverId: String,
        mediaTypes: Set<String>,
        fromMs: Long,
        userIds: Set<String>? = null,
        onRow: (MediaPlayRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> = Either.Right(Unit)

    suspend fun forEachClientStatsRow(
        serverId: String? = null,
        userIds: Set<String>? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        onRow: (ClientStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> =
        listForStats(serverId = serverId, userIds = userIds, fromMs = fromMs, toMs = toMs).map { rows ->
            for (row in rows) {
                onRow(
                    ClientStatsRow(
                        watchedDurationSec = row.watchedDurationSec,
                        streamDecision = row.streamDecision,
                        videoDecision = row.videoDecision,
                        audioDecision = row.audioDecision,
                        subtitleDecision = row.subtitleDecision,
                        videoCodec = row.videoCodec,
                        audioCodec = row.audioCodec,
                        transcodeVideoCodec = row.transcodeVideoCodec,
                        transcodeAudioCodec = row.transcodeAudioCodec,
                        container = row.container,
                        transcodeContainer = row.transcodeContainer,
                        playerProduct = row.playerProduct,
                        playerName = row.playerName,
                        playerPlatform = row.playerPlatform,
                    ),
                )
            }
        }

    suspend fun forEachHeatmapSample(
        serverId: String? = null,
        userIds: Set<String>? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        onRow: (stoppedAtMs: Long, watchedDurationSec: Int) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> =
        listForStats(serverId = serverId, userIds = userIds, fromMs = fromMs, toMs = toMs).map { rows ->
            for (row in rows) {
                onRow(row.stoppedAtMs, row.watchedDurationSec)
            }
        }
}

class ExposedPlaybackHistoryRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : PlaybackHistoryRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

    override suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord> =
        database.exposedQuery("playback_history_insert") {
            PlaybackHistoryTable.insert { populateInsert(it, record) }
            record
        }

    override suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("playback_history_insert_if_absent") {
            PlaybackHistoryTable.insertIgnore { populateInsert(it, record) }.insertedCount > 0
        }

    override suspend fun insertBatchIfAbsentByImportKey(
        records: List<PlaybackHistoryRecord>,
    ): Either<MiruError.PersistenceError, Int> {
        if (records.isEmpty()) {
            return Either.Right(0)
        }
        return database.exposedQuery("playback_history_insert_batch_if_absent") {
            var inserted = 0
            for (record in records) {
                inserted += PlaybackHistoryTable.insertIgnore { populateInsert(it, record) }.insertedCount
            }
            inserted
        }
    }

    override suspend fun findExistingImportKeys(keys: Set<String>): Either<MiruError.PersistenceError, Set<String>> {
        if (keys.isEmpty()) {
            return Either.Right(emptySet())
        }
        return readDatabase.exposedReadQuery("playback_history_find_existing_import_keys") {
            val existing = linkedSetOf<String>()
            for (chunk in keys.chunked(IMPORT_KEY_QUERY_CHUNK_SIZE)) {
                PlaybackHistoryTable
                    .select(PlaybackHistoryTable.externalImportKey)
                    .where { PlaybackHistoryTable.externalImportKey inList chunk }
                    .forEach { row ->
                        row[PlaybackHistoryTable.externalImportKey]?.let { existing.add(it) }
                    }
            }
            existing
        }
    }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, PlaybackHistoryRecord?> =
        readDatabase.exposedReadQuery("playback_history_find_by_id") {
            PlaybackHistoryTable
                .selectAll()
                .where { PlaybackHistoryTable.id eq id }
                .singleOrNull()
                ?.toPlaybackHistoryRecord()
        }

    override suspend fun findLatestMergeCandidate(
        serverId: String,
        userId: String,
        ratingKey: String,
        minStoppedAtMs: Long,
        playerMachineId: String?,
    ): Either<MiruError.PersistenceError, PlaybackHistoryRecord?> =
        readDatabase.exposedReadQuery("playback_history_find_merge_candidate") {
            val baseCondition =
                (PlaybackHistoryTable.serverId eq serverId) and
                    (PlaybackHistoryTable.userId eq userId) and
                    (PlaybackHistoryTable.ratingKey eq ratingKey) and
                    (PlaybackHistoryTable.stoppedAtMs greaterEq minStoppedAtMs)
            val condition =
                if (playerMachineId != null) {
                    baseCondition and (PlaybackHistoryTable.playerMachineId eq playerMachineId)
                } else {
                    baseCondition
                }
            PlaybackHistoryTable
                .selectAll()
                .where { condition }
                .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC, PlaybackHistoryTable.id to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.toPlaybackHistoryRecord()
        }

    override suspend fun updateMergedSession(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("playback_history_update_merged") {
            PlaybackHistoryTable.update({ PlaybackHistoryTable.id eq record.id }) {
                it[viewOffsetMs] = record.viewOffsetMs
                it[watchedDurationSec] = record.watchedDurationSec
                it[pausedDurationSec] = record.pausedDurationSec
                it[completionPercent] = record.completionPercent
                it[isWatched] = record.isWatched
                it[streamDecision] = record.streamTech.streamDecision
                it[videoDecision] = record.streamTech.videoDecision
                it[audioDecision] = record.streamTech.audioDecision
                it[hwTranscode] = record.streamTech.hwTranscode
                it[videoCodec] = record.streamTech.videoCodec
                it[audioCodec] = record.streamTech.audioCodec
                it[videoResolution] = record.streamTech.videoResolution
                it[bandwidthKbps] = record.streamTech.bandwidthKbps
                it[transcodeVideoCodec] = record.streamTech.transcodeVideoCodec
                it[transcodeAudioCodec] = record.streamTech.transcodeAudioCodec
                it[container] = record.streamTech.container
                it[transcodeContainer] = record.streamTech.transcodeContainer
                it[subtitleDecision] = record.streamTech.subtitleDecision
                it[playerName] = record.player.playerName
                it[playerPlatform] = record.player.playerPlatform
                it[playerProduct] = record.player.playerProduct
                it[playerMachineId] = record.player.playerMachineId
                it[ipAddress] = record.player.ipAddress
                it[location] = record.player.location
                it[startedAtMs] = record.startedAtMs
                it[stoppedAtMs] = record.stoppedAtMs
            } > 0
        }

    override suspend fun query(filter: HistoryFilter): Either<MiruError.PersistenceError, HistoryPage> {
        val safeLimit = filter.limit.coerceAtLeast(0)
        val safeOffset = filter.offset.coerceAtLeast(0)
        val hasEmptySet =
            listOfNotNull(filter.userIds, filter.ratingKeys, filter.librarySectionKeys, filter.mediaTypes)
                .any { it.isEmpty() }
        if (hasEmptySet) {
            return Either.Right(HistoryPage(emptyList(), 0L, safeLimit, safeOffset))
        }

        return readDatabase.exposedReadQuery("playback_history_query") {
            val condition = buildFilterCondition(filter)
            val sortOrder =
                when (filter.sortDir) {
                    HistorySortDirection.ASC -> SortOrder.ASC
                    HistorySortDirection.DESC -> SortOrder.DESC
                }
            val sortExpressions: List<Expression<*>> =
                when (filter.sortBy) {
                    HistorySortField.STOPPED_AT -> listOf(PlaybackHistoryTable.stoppedAtMs)
                    HistorySortField.STARTED_AT -> listOf(PlaybackHistoryTable.startedAtMs)
                    HistorySortField.WATCHED_DURATION -> listOf(PlaybackHistoryTable.watchedDurationSec)
                    HistorySortField.COMPLETION_PERCENT -> listOf(PlaybackHistoryTable.completionPercent)
                    HistorySortField.TITLE -> displayTitleSortExpressions()
                }

            val totalCount =
                PlaybackHistoryTable
                    .selectAll()
                    .where { condition }
                    .count()

            val items =
                if (safeLimit == 0) {
                    emptyList()
                } else {
                    val matching = PlaybackHistoryTable.selectAll().where { condition }
                    sortExpressions
                        .fold(matching) { query, expression -> query.orderBy(expression to sortOrder) }
                        // Plays that tie, e.g. rewatches or 100% completions, are listed newest first
                        .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC)
                        .orderBy(PlaybackHistoryTable.id to SortOrder.ASC)
                        .limit(safeLimit)
                        .offset(safeOffset.toLong())
                        .map { it.toPlaybackHistoryRecord() }
                }

            HistoryPage(
                items = items,
                totalCount = totalCount,
                limit = safeLimit,
                offset = safeOffset,
            )
        }
    }

    override suspend fun listForStats(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
    ): Either<MiruError.PersistenceError, List<PlaybackHistoryStatsRow>> {
        val rows = mutableListOf<PlaybackHistoryStatsRow>()
        return forEachStatsRow(
            serverId = serverId,
            userIds = userIds,
            fromMs = fromMs,
            toMs = toMs,
        ) { row ->
            rows += row
        }.map { rows }
    }

    override suspend fun forEachStatsRow(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
        onRow: (PlaybackHistoryStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> {
        if (userIds != null && userIds.isEmpty()) {
            return Either.Right(Unit)
        }

        return readDatabase.exposedReadQuery("playback_history_stream_stats") {
            val condition = buildStatsCondition(serverId, userIds, fromMs, toMs)
            PlaybackHistoryTable
                .select(
                    PlaybackHistoryTable.userId,
                    PlaybackHistoryTable.mediaType,
                    PlaybackHistoryTable.ratingKey,
                    PlaybackHistoryTable.title,
                    PlaybackHistoryTable.parentTitle,
                    PlaybackHistoryTable.grandparentTitle,
                    PlaybackHistoryTable.thumbPath,
                    PlaybackHistoryTable.librarySectionKey,
                    PlaybackHistoryTable.watchedDurationSec,
                    PlaybackHistoryTable.streamDecision,
                    PlaybackHistoryTable.hwTranscode,
                    PlaybackHistoryTable.playerPlatform,
                    PlaybackHistoryTable.playerProduct,
                    PlaybackHistoryTable.stoppedAtMs,
                    PlaybackHistoryTable.completionPercent,
                    PlaybackHistoryTable.isWatched,
                    PlaybackHistoryTable.videoDecision,
                    PlaybackHistoryTable.audioDecision,
                    PlaybackHistoryTable.videoCodec,
                    PlaybackHistoryTable.audioCodec,
                    PlaybackHistoryTable.videoResolution,
                    PlaybackHistoryTable.playerName,
                    PlaybackHistoryTable.startedAtMs,
                    PlaybackHistoryTable.transcodeVideoCodec,
                    PlaybackHistoryTable.transcodeAudioCodec,
                    PlaybackHistoryTable.container,
                    PlaybackHistoryTable.transcodeContainer,
                    PlaybackHistoryTable.subtitleDecision,
                ).where { condition }
                .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.ASC, PlaybackHistoryTable.id to SortOrder.ASC)
                .fetchSize(STATS_STREAM_FETCH_SIZE)
                .forEach { row -> onRow(row.toPlaybackHistoryStatsRow()) }
        }
    }

    override suspend fun forEachOverviewStatsRow(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
        onRow: (OverviewStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> {
        if (userIds != null && userIds.isEmpty()) {
            return Either.Right(Unit)
        }

        return readDatabase.exposedReadQuery("playback_history_stream_overview_stats") {
            val condition = buildStatsCondition(serverId, userIds, fromMs, toMs)
            PlaybackHistoryTable
                .select(
                    PlaybackHistoryTable.userId,
                    PlaybackHistoryTable.mediaType,
                    PlaybackHistoryTable.ratingKey,
                    PlaybackHistoryTable.title,
                    PlaybackHistoryTable.grandparentTitle,
                    PlaybackHistoryTable.thumbPath,
                    PlaybackHistoryTable.watchedDurationSec,
                    PlaybackHistoryTable.streamDecision,
                    PlaybackHistoryTable.hwTranscode,
                    PlaybackHistoryTable.stoppedAtMs,
                ).where { condition }
                .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.ASC, PlaybackHistoryTable.id to SortOrder.ASC)
                .fetchSize(STATS_STREAM_FETCH_SIZE)
                .forEach { row -> onRow(row.toOverviewStatsRow()) }
        }
    }

    override suspend fun forEachMediaPlay(
        serverId: String,
        mediaTypes: Set<String>,
        fromMs: Long,
        userIds: Set<String>?,
        onRow: (MediaPlayRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> {
        if (mediaTypes.isEmpty() || (userIds != null && userIds.isEmpty())) {
            return Either.Right(Unit)
        }

        return readDatabase.exposedReadQuery("playback_history_stream_media_plays") {
            val condition =
                buildStatsCondition(serverId, userIds, fromMs, null) and (PlaybackHistoryTable.mediaType inList mediaTypes)
            PlaybackHistoryTable
                .join(
                    otherTable = MediaItemsCacheTable,
                    joinType = JoinType.LEFT,
                    additionalConstraint = {
                        (MediaItemsCacheTable.serverId eq PlaybackHistoryTable.serverId) and
                            (MediaItemsCacheTable.ratingKey eq PlaybackHistoryTable.ratingKey)
                    },
                ).select(
                    PlaybackHistoryTable.userId,
                    PlaybackHistoryTable.mediaType,
                    PlaybackHistoryTable.ratingKey,
                    PlaybackHistoryTable.title,
                    PlaybackHistoryTable.grandparentTitle,
                    PlaybackHistoryTable.year,
                    PlaybackHistoryTable.thumbPath,
                    PlaybackHistoryTable.librarySectionKey,
                    PlaybackHistoryTable.watchedDurationSec,
                    PlaybackHistoryTable.startedAtMs,
                    MediaItemsCacheTable.sectionKey,
                    MediaItemsCacheTable.grandparentRatingKey,
                ).where { condition }
                .orderBy(PlaybackHistoryTable.startedAtMs to SortOrder.ASC, PlaybackHistoryTable.id to SortOrder.ASC)
                .fetchSize(STATS_STREAM_FETCH_SIZE)
                .forEach { row -> onRow(row.toMediaPlayRow()) }
        }
    }

    override suspend fun forEachClientStatsRow(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
        onRow: (ClientStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> {
        if (userIds != null && userIds.isEmpty()) {
            return Either.Right(Unit)
        }

        return readDatabase.exposedReadQuery("playback_history_stream_client_stats") {
            val condition = buildStatsCondition(serverId, userIds, fromMs, toMs)
            PlaybackHistoryTable
                .select(
                    PlaybackHistoryTable.watchedDurationSec,
                    PlaybackHistoryTable.streamDecision,
                    PlaybackHistoryTable.videoDecision,
                    PlaybackHistoryTable.audioDecision,
                    PlaybackHistoryTable.subtitleDecision,
                    PlaybackHistoryTable.videoCodec,
                    PlaybackHistoryTable.audioCodec,
                    PlaybackHistoryTable.transcodeVideoCodec,
                    PlaybackHistoryTable.transcodeAudioCodec,
                    PlaybackHistoryTable.container,
                    PlaybackHistoryTable.transcodeContainer,
                    PlaybackHistoryTable.playerProduct,
                    PlaybackHistoryTable.playerName,
                    PlaybackHistoryTable.playerPlatform,
                ).where { condition }
                .fetchSize(STATS_STREAM_FETCH_SIZE)
                .forEach { row -> onRow(row.toClientStatsRow()) }
        }
    }

    override suspend fun forEachHeatmapSample(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
        onRow: (stoppedAtMs: Long, watchedDurationSec: Int) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> {
        if (userIds != null && userIds.isEmpty()) {
            return Either.Right(Unit)
        }

        return readDatabase.exposedReadQuery("playback_history_stream_heatmap_stats") {
            val condition = buildStatsCondition(serverId, userIds, fromMs, toMs)
            PlaybackHistoryTable
                .select(
                    PlaybackHistoryTable.stoppedAtMs,
                    PlaybackHistoryTable.watchedDurationSec,
                ).where { condition }
                .fetchSize(STATS_STREAM_FETCH_SIZE)
                .forEach { row ->
                    onRow(row[PlaybackHistoryTable.stoppedAtMs], row[PlaybackHistoryTable.watchedDurationSec])
                }
        }
    }

    /** Titles as the UI lists them: episodes under their show, in season and episode order. */
    private fun displayTitleSortExpressions(): List<Expression<*>> {
        val isEpisodeOfShow = (PlaybackHistoryTable.mediaType eq "episode") and PlaybackHistoryTable.grandparentTitle.isNotNull()
        return listOf(
            Case()
                .When(isEpisodeOfShow, PlaybackHistoryTable.grandparentTitle.lowerCase())
                .Else(PlaybackHistoryTable.title.lowerCase()),
            // Coalesced because SQLite and PostgreSQL order NULLs differently
            Coalesce(PlaybackHistoryTable.parentIndex, intLiteral(0)),
            Coalesce(PlaybackHistoryTable.mediaIndex, intLiteral(0)),
            PlaybackHistoryTable.title.lowerCase(),
        )
    }

    private fun buildStatsCondition(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
    ): Op<Boolean> {
        val predicates = mutableListOf<Op<Boolean>>()
        serverId?.let { predicates += PlaybackHistoryTable.serverId eq it }
        userIds?.let { predicates += PlaybackHistoryTable.userId inList it }
        fromMs?.let { predicates += PlaybackHistoryTable.stoppedAtMs greaterEq it }
        toMs?.let { predicates += PlaybackHistoryTable.stoppedAtMs lessEq it }
        return if (predicates.isEmpty()) {
            Op.TRUE
        } else {
            predicates.reduce { acc, next -> acc and next }
        }
    }

    override suspend fun summarizeByUser(
        userIds: Set<String>?,
    ): Either<MiruError.PersistenceError, Map<String, UserPlaybackSummary>> {
        if (userIds != null && userIds.isEmpty()) {
            return Either.Right(emptyMap())
        }
        return readDatabase.exposedReadQuery("playback_history_summarize_by_user") {
            val playCountExpr = PlaybackHistoryTable.id.count()
            val watchDurationExpr = PlaybackHistoryTable.watchedDurationSec.castTo(LongColumnType()).sum()
            val query =
                PlaybackHistoryTable
                    .select(PlaybackHistoryTable.userId, playCountExpr, watchDurationExpr)
                    .let { base ->
                        if (userIds != null) {
                            base.where { PlaybackHistoryTable.userId inList userIds }
                        } else {
                            base
                        }
                    }.groupBy(PlaybackHistoryTable.userId)

            val summaries = linkedMapOf<String, UserPlaybackSummary>()
            for (row in query) {
                val uid = row[PlaybackHistoryTable.userId]
                summaries[uid] =
                    UserPlaybackSummary(
                        userId = uid,
                        totalPlays = row[playCountExpr],
                        totalWatchDurationSec = row[watchDurationExpr] ?: 0L,
                    )
            }
            summaries
        }
    }

    override suspend fun summarizeByRatingKey(
        ratingKey: String,
        allowedUserIds: Set<String>?,
        serverId: String?,
    ): Either<MiruError.PersistenceError, MediaPlaySummary> {
        if (ratingKey.isBlank()) {
            return Either.Right(MediaPlaySummary.EMPTY)
        }
        return summarizeMatching(
            operation = "playback_history_summarize_by_rating_key",
            allowedUserIds = allowedUserIds,
            serverId = serverId,
        ) {
            PlaybackHistoryTable.ratingKey eq ratingKey
        }
    }

    override suspend fun summarizeSeriesByTitle(
        seriesTitle: String,
        allowedUserIds: Set<String>?,
        serverId: String?,
    ): Either<MiruError.PersistenceError, MediaPlaySummary> {
        val trimmed = seriesTitle.trim()
        if (trimmed.isEmpty()) {
            return Either.Right(MediaPlaySummary.EMPTY)
        }
        return summarizeMatching(
            operation = "playback_history_summarize_series_by_title",
            allowedUserIds = allowedUserIds,
            serverId = serverId,
        ) {
            (PlaybackHistoryTable.mediaType eq "episode") and
                (PlaybackHistoryTable.grandparentTitle.lowerCase() eq trimmed.lowercase())
        }
    }

    private suspend fun summarizeMatching(
        operation: String,
        allowedUserIds: Set<String>?,
        serverId: String? = null,
        predicate: () -> Op<Boolean>,
    ): Either<MiruError.PersistenceError, MediaPlaySummary> {
        if (allowedUserIds != null && allowedUserIds.isEmpty()) {
            return Either.Right(MediaPlaySummary.EMPTY)
        }
        return readDatabase.exposedReadQuery(operation) {
            var condition = predicate()
            if (serverId != null) {
                condition = condition and (PlaybackHistoryTable.serverId eq serverId)
            }
            if (allowedUserIds != null) {
                condition = condition and (PlaybackHistoryTable.userId inList allowedUserIds)
            }

            val uniqueUsers = hashSetOf<String>()
            var totalPlays = 0L
            var totalWatchDurationSec = 0L
            var completionSum = 0.0
            var directPlayCount = 0L
            var directStreamCount = 0L
            var transcodeCount = 0L
            var lastUserId: String? = null
            var lastWatchedAtMs: Long? = null

            PlaybackHistoryTable
                .select(
                    PlaybackHistoryTable.userId,
                    PlaybackHistoryTable.watchedDurationSec,
                    PlaybackHistoryTable.completionPercent,
                    PlaybackHistoryTable.streamDecision,
                    PlaybackHistoryTable.stoppedAtMs,
                ).where { condition }
                .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC, PlaybackHistoryTable.id to SortOrder.ASC)
                .fetchSize(STATS_STREAM_FETCH_SIZE)
                .forEach { row ->
                    val uid = row[PlaybackHistoryTable.userId]
                    if (totalPlays == 0L) {
                        lastUserId = uid
                        lastWatchedAtMs = row[PlaybackHistoryTable.stoppedAtMs]
                    }
                    totalPlays++
                    uniqueUsers += uid
                    totalWatchDurationSec += row[PlaybackHistoryTable.watchedDurationSec].toLong()
                    completionSum += row[PlaybackHistoryTable.completionPercent]
                    when (row[PlaybackHistoryTable.streamDecision].lowercase()) {
                        "direct_play" -> directPlayCount++
                        "direct_stream" -> directStreamCount++
                        "transcode" -> transcodeCount++
                    }
                }

            if (totalPlays == 0L) {
                return@exposedReadQuery MediaPlaySummary.EMPTY
            }

            val resolvedLastUserId = lastUserId
            val lastWatchedUsername =
                resolvedLastUserId?.let { uid ->
                    UsersTable
                        .select(UsersTable.displayName, UsersTable.username)
                        .where { UsersTable.id eq uid }
                        .singleOrNull()
                        ?.let { userRow ->
                            userRow[UsersTable.displayName]?.takeIf { it.isNotBlank() } ?: userRow[UsersTable.username]
                        }
                }

            val avgCompletionPercent =
                ((completionSum / totalPlays) * COMPLETION_ROUNDING_SCALE).roundToLong() / COMPLETION_ROUNDING_SCALE

            MediaPlaySummary(
                totalPlays = totalPlays,
                uniqueUsers = uniqueUsers.size,
                lastWatchedAtMs = lastWatchedAtMs,
                lastWatchedUserId = resolvedLastUserId,
                lastWatchedUsername = lastWatchedUsername,
                avgCompletionPercent = avgCompletionPercent,
                directPlayCount = directPlayCount,
                directStreamCount = directStreamCount,
                transcodeCount = transcodeCount,
                totalWatchDurationSec = totalWatchDurationSec,
            )
        }
    }

    override suspend fun listRecentByUser(
        userId: String,
        limit: Int,
    ): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>> =
        readDatabase.exposedReadQuery("playback_history_list_recent_by_user") {
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) {
                emptyList()
            } else {
                PlaybackHistoryTable
                    .selectAll()
                    .where { PlaybackHistoryTable.userId eq userId }
                    .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC, PlaybackHistoryTable.id to SortOrder.ASC)
                    .limit(safeLimit)
                    .map { it.toPlaybackHistoryRecord() }
            }
        }

    override suspend fun listByUser(userId: String): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>> =
        readDatabase.exposedReadQuery("playback_history_list_by_user") {
            PlaybackHistoryTable
                .selectAll()
                .where { PlaybackHistoryTable.userId eq userId }
                .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC, PlaybackHistoryTable.id to SortOrder.ASC)
                .map { it.toPlaybackHistoryRecord() }
        }

    private fun buildFilterCondition(filter: HistoryFilter): Op<Boolean> {
        val predicates = mutableListOf<Op<Boolean>>()
        filter.serverId?.let { predicates += PlaybackHistoryTable.serverId eq it }
        filter.userId?.let { predicates += PlaybackHistoryTable.userId eq it }
        filter.userIds?.takeIf { it.isNotEmpty() }?.let { predicates += PlaybackHistoryTable.userId inList it }
        filter.mediaType?.let { predicates += PlaybackHistoryTable.mediaType eq it }
        filter.librarySectionKey?.let { predicates += PlaybackHistoryTable.librarySectionKey eq it }
        filter.streamDecision?.let { predicates += PlaybackHistoryTable.streamDecision eq it }
        filter.fromMs?.let { predicates += PlaybackHistoryTable.stoppedAtMs greaterEq it }
        filter.toMs?.let { predicates += PlaybackHistoryTable.stoppedAtMs lessEq it }
        filter.ratingKeys?.let { predicates += PlaybackHistoryTable.ratingKey inList it }
        filter.librarySectionKeys?.let { predicates += PlaybackHistoryTable.librarySectionKey inList it }
        filter.mediaTypes?.let { predicates += PlaybackHistoryTable.mediaType inList it }
        filter.startedFromMs?.let { predicates += PlaybackHistoryTable.startedAtMs greaterEq it }
        filter.startedBeforeMs?.let { predicates += PlaybackHistoryTable.startedAtMs less it }
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val pattern = LikePattern("%${escapeLikePattern(raw.lowercase())}%", escapeChar = '\\')
            predicates +=
                (PlaybackHistoryTable.title.lowerCase() like pattern) or
                (PlaybackHistoryTable.parentTitle.lowerCase() like pattern) or
                (PlaybackHistoryTable.grandparentTitle.lowerCase() like pattern)
        }

        return if (predicates.isEmpty()) {
            Op.TRUE
        } else {
            predicates.reduce { acc, next -> acc and next }
        }
    }

    private fun populateInsert(
        stmt: UpdateBuilder<*>,
        record: PlaybackHistoryRecord,
    ) {
        stmt[PlaybackHistoryTable.id] = record.id
        stmt[PlaybackHistoryTable.serverId] = record.serverId
        stmt[PlaybackHistoryTable.userId] = record.userId
        stmt[PlaybackHistoryTable.externalImportKey] = record.externalImportKey
        stmt[PlaybackHistoryTable.ratingKey] = record.media.ratingKey
        stmt[PlaybackHistoryTable.mediaType] = record.media.mediaType
        stmt[PlaybackHistoryTable.title] = record.media.title
        stmt[PlaybackHistoryTable.parentTitle] = record.media.parentTitle
        stmt[PlaybackHistoryTable.grandparentTitle] = record.media.grandparentTitle
        stmt[PlaybackHistoryTable.parentIndex] = record.media.parentIndex
        stmt[PlaybackHistoryTable.mediaIndex] = record.media.mediaIndex
        stmt[PlaybackHistoryTable.year] = record.media.year
        stmt[PlaybackHistoryTable.thumbPath] = record.media.thumbPath
        stmt[PlaybackHistoryTable.librarySectionKey] = record.media.librarySectionKey
        stmt[PlaybackHistoryTable.durationMs] = record.media.durationMs
        stmt[PlaybackHistoryTable.viewOffsetMs] = record.viewOffsetMs
        stmt[PlaybackHistoryTable.watchedDurationSec] = record.watchedDurationSec
        stmt[PlaybackHistoryTable.pausedDurationSec] = record.pausedDurationSec
        stmt[PlaybackHistoryTable.completionPercent] = record.completionPercent
        stmt[PlaybackHistoryTable.isWatched] = record.isWatched
        stmt[PlaybackHistoryTable.streamDecision] = record.streamTech.streamDecision
        stmt[PlaybackHistoryTable.videoDecision] = record.streamTech.videoDecision
        stmt[PlaybackHistoryTable.audioDecision] = record.streamTech.audioDecision
        stmt[PlaybackHistoryTable.hwTranscode] = record.streamTech.hwTranscode
        stmt[PlaybackHistoryTable.videoCodec] = record.streamTech.videoCodec
        stmt[PlaybackHistoryTable.audioCodec] = record.streamTech.audioCodec
        stmt[PlaybackHistoryTable.videoResolution] = record.streamTech.videoResolution
        stmt[PlaybackHistoryTable.bandwidthKbps] = record.streamTech.bandwidthKbps
        stmt[PlaybackHistoryTable.transcodeVideoCodec] = record.streamTech.transcodeVideoCodec
        stmt[PlaybackHistoryTable.transcodeAudioCodec] = record.streamTech.transcodeAudioCodec
        stmt[PlaybackHistoryTable.container] = record.streamTech.container
        stmt[PlaybackHistoryTable.transcodeContainer] = record.streamTech.transcodeContainer
        stmt[PlaybackHistoryTable.subtitleDecision] = record.streamTech.subtitleDecision
        stmt[PlaybackHistoryTable.playerName] = record.player.playerName
        stmt[PlaybackHistoryTable.playerPlatform] = record.player.playerPlatform
        stmt[PlaybackHistoryTable.playerProduct] = record.player.playerProduct
        stmt[PlaybackHistoryTable.playerMachineId] = record.player.playerMachineId
        stmt[PlaybackHistoryTable.ipAddress] = record.player.ipAddress
        stmt[PlaybackHistoryTable.location] = record.player.location
        stmt[PlaybackHistoryTable.startedAtMs] = record.startedAtMs
        stmt[PlaybackHistoryTable.stoppedAtMs] = record.stoppedAtMs
    }
}
