package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.escapeLikePattern
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistoryPage
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.UserPlaybackSummary
import app.hononeko.miru.persistence.table.PlaybackHistoryTable
import arrow.core.Either
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

interface PlaybackHistoryRepository {
    suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord>

    suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean>

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

    suspend fun listRecentByUser(
        userId: String,
        limit: Int = 10,
    ): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>>

    suspend fun listByUser(userId: String): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>>
}

class ExposedPlaybackHistoryRepository(
    private val database: Database,
) : PlaybackHistoryRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord> =
        database.exposedQuery("playback_history_insert") {
            PlaybackHistoryTable.insert { populateInsert(it, record) }
            record
        }

    override suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("playback_history_insert_if_absent") {
            PlaybackHistoryTable.insertIgnore { populateInsert(it, record) }.insertedCount > 0
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, PlaybackHistoryRecord?> =
        database.exposedQuery("playback_history_find_by_id") {
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
        database.exposedQuery("playback_history_find_merge_candidate") {
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

    override suspend fun query(filter: HistoryFilter): Either<MiruError.PersistenceError, HistoryPage> =
        database.exposedQuery("playback_history_query") {
            val safeLimit = filter.limit.coerceAtLeast(0)
            val safeOffset = filter.offset.coerceAtLeast(0)
            val condition = buildFilterCondition(filter)

            val totalCount =
                PlaybackHistoryTable
                    .selectAll()
                    .where { condition }
                    .count()

            val items =
                if (safeLimit == 0) {
                    emptyList()
                } else {
                    PlaybackHistoryTable
                        .selectAll()
                        .where { condition }
                        .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC, PlaybackHistoryTable.id to SortOrder.ASC)
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

    override suspend fun summarizeByUser(
        userIds: Set<String>?,
    ): Either<MiruError.PersistenceError, Map<String, UserPlaybackSummary>> {
        if (userIds != null && userIds.isEmpty()) {
            return Either.Right(emptyMap())
        }
        return database.exposedQuery("playback_history_summarize_by_user") {
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

    override suspend fun listRecentByUser(
        userId: String,
        limit: Int,
    ): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>> =
        database.exposedQuery("playback_history_list_recent_by_user") {
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
        database.exposedQuery("playback_history_list_by_user") {
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
        filter.mediaType?.let { predicates += PlaybackHistoryTable.mediaType eq it }
        filter.librarySectionKey?.let { predicates += PlaybackHistoryTable.librarySectionKey eq it }
        filter.streamDecision?.let { predicates += PlaybackHistoryTable.streamDecision eq it }
        filter.fromMs?.let { predicates += PlaybackHistoryTable.stoppedAtMs greaterEq it }
        filter.toMs?.let { predicates += PlaybackHistoryTable.stoppedAtMs lessEq it }
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
