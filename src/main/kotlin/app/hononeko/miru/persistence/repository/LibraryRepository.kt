package app.hononeko.miru.persistence.repository

import app.hononeko.miru.MiruJson
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.LibraryGrowthDayRecord
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.model.ResolvedLibraryAccess
import app.hononeko.miru.persistence.model.StaleMediaItemRecord
import app.hononeko.miru.persistence.model.StaleMediaReportRecord
import app.hononeko.miru.persistence.model.StaleMediaSort
import app.hononeko.miru.persistence.model.UserLibraryAccessRecord
import app.hononeko.miru.persistence.table.ActiveSessionsTable
import app.hononeko.miru.persistence.table.LibrarySectionsTable
import app.hononeko.miru.persistence.table.LibrarySnapshotsTable
import app.hononeko.miru.persistence.table.MediaItemsCacheTable
import app.hononeko.miru.persistence.table.PlaybackHistoryTable
import app.hononeko.miru.persistence.table.UserLibraryAccessTable
import app.hononeko.miru.persistence.table.UsersTable
import arrow.core.Either
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.ResultSet

interface LibraryRepository {
    suspend fun upsertSection(section: LibrarySectionRecord): Either<MiruError.PersistenceError, Unit>

    suspend fun findAllSections(): Either<MiruError.PersistenceError, List<LibrarySectionRecord>>

    suspend fun findSection(
        serverId: String,
        sectionKey: String,
    ): Either<MiruError.PersistenceError, LibrarySectionRecord?>

    suspend fun findSectionsByKey(sectionKey: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>>

    suspend fun findSectionsByServer(serverId: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>>

    suspend fun insertSnapshot(snapshot: LibrarySnapshotRecord): Either<MiruError.PersistenceError, Unit>

    suspend fun findSnapshots(
        serverId: String,
        sectionKey: String,
        limit: Int = 100,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Either<MiruError.PersistenceError, List<LibrarySnapshotRecord>>

    /**
     * The most recent [limit] UTC days on which items (movies, episodes or tracks) were added or removed, newest first.
     * Counts are the changes between consecutive snapshots, so the first snapshot is a baseline and days without a
     * change are left out.
     */
    suspend fun findDailyGrowth(
        serverId: String,
        sectionKey: String,
        limit: Int,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Either<MiruError.PersistenceError, List<LibraryGrowthDayRecord>> = Either.Right(emptyList())

    suspend fun upsertMediaItems(items: List<MediaItemCacheRecord>): Either<MiruError.PersistenceError, Int>

    suspend fun findRecentlyAdded(
        serverId: String,
        sectionKey: String? = null,
        limit: Int = 25,
    ): Either<MiruError.PersistenceError, List<MediaItemCacheRecord>>

    suspend fun findStaleMedia(
        serverId: String,
        sectionKey: String,
        unwatchedCutoffMs: Long?,
        neverPlayedOnly: Boolean = false,
        sort: StaleMediaSort = StaleMediaSort.SIZE_DESC,
        limit: Int = 100,
    ): Either<MiruError.PersistenceError, StaleMediaReportRecord> =
        Either.Right(
            StaleMediaReportRecord(
                items = emptyList(),
                totalStaleCount = 0,
                totalReclaimableBytes = 0L,
                neverPlayedCount = 0,
            ),
        )

    suspend fun deleteItemsNotIn(
        serverId: String,
        sectionKey: String,
        activeRatingKeys: Set<String>,
    ): Either<MiruError.PersistenceError, Int>

    suspend fun replaceUserLibraryAccess(
        serverId: String,
        records: List<UserLibraryAccessRecord>,
    ): Either<MiruError.PersistenceError, Unit>

    suspend fun findUserLibraryAccessByServer(
        serverId: String,
    ): Either<MiruError.PersistenceError, List<UserLibraryAccessRecord>>

    suspend fun findAccessibleLibrariesForMediaUsers(
        mediaUserIds: Set<String>,
    ): Either<MiruError.PersistenceError, ResolvedLibraryAccess>

    suspend fun findSectionKeysByRatingKey(
        ratingKey: String,
    ): Either<MiruError.PersistenceError, Set<Pair<String, String>>> = Either.Right(emptySet())
}

class ExposedLibraryRepository(
    private val database: Database,
) : LibraryRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun upsertSection(section: LibrarySectionRecord): Either<MiruError.PersistenceError, Unit> =
        database
            .exposedQuery("library_upsert_section") {
                LibrarySectionsTable.upsert(
                    LibrarySectionsTable.serverId,
                    LibrarySectionsTable.sectionKey,
                ) {
                    it[serverId] = section.serverId
                    it[sectionKey] = section.sectionKey
                    it[title] = section.title
                    it[sectionType] = section.sectionType
                    it[itemCount] = section.itemCount
                    it[childCount] = section.childCount
                    it[leafCount] = section.leafCount
                    it[totalSizeBytes] = section.totalSizeBytes
                    it[totalDurationMs] = section.totalDurationMs
                    it[lastSyncedAtMs] = section.lastSyncedAtMs
                    it[updatedAtMs] = section.updatedAtMs
                }
            }.map { }

    override suspend fun findAllSections(): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        database.exposedQuery("library_find_all_sections") {
            LibrarySectionsTable
                .selectAll()
                .orderBy(
                    LibrarySectionsTable.serverId to SortOrder.ASC,
                    LibrarySectionsTable.title to SortOrder.ASC,
                    LibrarySectionsTable.sectionKey to SortOrder.ASC,
                ).map { it.toLibrarySectionRecord() }
        }

    override suspend fun findSection(
        serverId: String,
        sectionKey: String,
    ): Either<MiruError.PersistenceError, LibrarySectionRecord?> =
        database.exposedQuery("library_find_section") {
            LibrarySectionsTable
                .selectAll()
                .where {
                    (LibrarySectionsTable.serverId eq serverId) and (LibrarySectionsTable.sectionKey eq sectionKey)
                }.singleOrNull()
                ?.toLibrarySectionRecord()
        }

    override suspend fun findSectionsByKey(sectionKey: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        database.exposedQuery("library_find_sections_by_key") {
            LibrarySectionsTable
                .selectAll()
                .where { LibrarySectionsTable.sectionKey eq sectionKey }
                .orderBy(LibrarySectionsTable.serverId to SortOrder.ASC)
                .map { it.toLibrarySectionRecord() }
        }

    override suspend fun findSectionsByServer(serverId: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        database.exposedQuery("library_find_sections_by_server") {
            LibrarySectionsTable
                .selectAll()
                .where { LibrarySectionsTable.serverId eq serverId }
                .orderBy(LibrarySectionsTable.title to SortOrder.ASC, LibrarySectionsTable.sectionKey to SortOrder.ASC)
                .map { it.toLibrarySectionRecord() }
        }

    override suspend fun insertSnapshot(snapshot: LibrarySnapshotRecord): Either<MiruError.PersistenceError, Unit> =
        database
            .exposedQuery("library_insert_snapshot") {
                LibrarySnapshotsTable.insert {
                    it[id] = snapshot.id
                    it[serverId] = snapshot.serverId
                    it[sectionKey] = snapshot.sectionKey
                    it[capturedAtMs] = snapshot.capturedAtMs
                    it[itemCount] = snapshot.itemCount
                    it[childCount] = snapshot.childCount
                    it[leafCount] = snapshot.leafCount
                    it[totalSizeBytes] = snapshot.totalSizeBytes
                    it[totalDurationMs] = snapshot.totalDurationMs
                    it[aggregatesJson] = snapshot.aggregatesJson
                }
            }.map { }

    override suspend fun findSnapshots(
        serverId: String,
        sectionKey: String,
        limit: Int,
        fromMs: Long?,
        toMs: Long?,
    ): Either<MiruError.PersistenceError, List<LibrarySnapshotRecord>> =
        database.exposedQuery("library_find_snapshots") {
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) return@exposedQuery emptyList()

            var condition: Op<Boolean> =
                (LibrarySnapshotsTable.serverId eq serverId) and (LibrarySnapshotsTable.sectionKey eq sectionKey)
            fromMs?.let { condition = condition and (LibrarySnapshotsTable.capturedAtMs greaterEq it) }
            toMs?.let { condition = condition and (LibrarySnapshotsTable.capturedAtMs lessEq it) }

            LibrarySnapshotsTable
                .selectAll()
                .where { condition }
                .orderBy(LibrarySnapshotsTable.capturedAtMs to SortOrder.DESC, LibrarySnapshotsTable.id to SortOrder.ASC)
                .limit(safeLimit)
                .map { it.toLibrarySnapshotRecord() }
        }

    override suspend fun findDailyGrowth(
        serverId: String,
        sectionKey: String,
        limit: Int,
        fromMs: Long?,
        toMs: Long?,
    ): Either<MiruError.PersistenceError, List<LibraryGrowthDayRecord>> =
        database.exposedQuery("library_find_daily_growth") {
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) return@exposedQuery emptyList()

            val args =
                mutableListOf<Pair<IColumnType<*>, Any?>>(
                    TextColumnType() to serverId,
                    TextColumnType() to sectionKey,
                )
            // A window still reads the snapshot before it, which its first delta is relative to
            val scanPredicates =
                buildString {
                    toMs?.let {
                        append(" AND captured_at_ms <= ?")
                        args += LongColumnType() to it
                    }
                    fromMs?.let {
                        append(
                            " AND captured_at_ms >= COALESCE((SELECT MAX(captured_at_ms) FROM library_snapshots" +
                                " WHERE server_id = ? AND section_key = ? AND captured_at_ms < ?), ?)",
                        )
                        args += TextColumnType() to serverId
                        args += TextColumnType() to sectionKey
                        args += LongColumnType() to it
                        args += LongColumnType() to it
                    }
                }
            val windowPredicate =
                fromMs
                    ?.let {
                        args += LongColumnType() to it
                        " AND captured_at_ms >= ?"
                    }.orEmpty()
            args += IntegerColumnType() to safeLimit

            val sql =
                """
                SELECT
                    epoch_day,
                    SUM(CASE WHEN leaf_delta > 0 THEN leaf_delta ELSE 0 END) AS added,
                    SUM(CASE WHEN leaf_delta < 0 THEN -leaf_delta ELSE 0 END) AS removed,
                    SUM(size_delta) AS size_delta_bytes
                FROM (
                    SELECT
                        captured_at_ms,
                        captured_at_ms / $MILLIS_PER_DAY AS epoch_day,
                        leaf_count - LAG(leaf_count) OVER (ORDER BY captured_at_ms, id) AS leaf_delta,
                        total_size_bytes - LAG(total_size_bytes) OVER (ORDER BY captured_at_ms, id) AS size_delta
                    FROM library_snapshots
                    WHERE server_id = ? AND section_key = ?$scanPredicates
                ) deltas
                WHERE leaf_delta IS NOT NULL$windowPredicate
                GROUP BY epoch_day
                HAVING SUM(ABS(leaf_delta)) > 0
                ORDER BY epoch_day DESC
                LIMIT ?
                """.trimIndent()

            exec(sql, args = args, explicitStatementType = StatementType.SELECT) { rs ->
                val days = mutableListOf<LibraryGrowthDayRecord>()
                while (rs.next()) {
                    days +=
                        LibraryGrowthDayRecord(
                            epochDay = rs.getLong("epoch_day"),
                            added = rs.getLong("added"),
                            removed = rs.getLong("removed"),
                            sizeDeltaBytes = rs.getLong("size_delta_bytes"),
                        )
                }
                days
            }.orEmpty()
        }

    override suspend fun upsertMediaItems(items: List<MediaItemCacheRecord>): Either<MiruError.PersistenceError, Int> {
        if (items.isEmpty()) return Either.Right(0)
        return database.exposedQuery("library_upsert_media_items") {
            items.chunked(BATCH_SIZE).forEach { chunk ->
                MediaItemsCacheTable.batchUpsert(
                    data = chunk,
                    MediaItemsCacheTable.serverId,
                    MediaItemsCacheTable.ratingKey,
                    shouldReturnGeneratedValues = false,
                ) { item ->
                    this[MediaItemsCacheTable.serverId] = item.serverId
                    this[MediaItemsCacheTable.ratingKey] = item.ratingKey
                    this[MediaItemsCacheTable.sectionKey] = item.sectionKey
                    this[MediaItemsCacheTable.mediaType] = item.mediaType
                    this[MediaItemsCacheTable.title] = item.title
                    this[MediaItemsCacheTable.parentTitle] = item.parentTitle
                    this[MediaItemsCacheTable.grandparentTitle] = item.grandparentTitle
                    this[MediaItemsCacheTable.year] = item.year
                    this[MediaItemsCacheTable.thumbPath] = item.thumbPath
                    this[MediaItemsCacheTable.durationMs] = item.durationMs
                    this[MediaItemsCacheTable.fileSizeBytes] = item.tech.fileSizeBytes
                    this[MediaItemsCacheTable.videoResolution] = item.tech.videoResolution
                    this[MediaItemsCacheTable.videoCodec] = item.tech.videoCodec
                    this[MediaItemsCacheTable.audioCodec] = item.tech.audioCodec
                    this[MediaItemsCacheTable.addedAtMs] = item.addedAtMs
                    this[MediaItemsCacheTable.updatedAtMs] = item.updatedAtMs
                }
            }
            items.size
        }
    }

    override suspend fun findRecentlyAdded(
        serverId: String,
        sectionKey: String?,
        limit: Int,
    ): Either<MiruError.PersistenceError, List<MediaItemCacheRecord>> =
        database.exposedQuery("library_find_recently_added") {
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) return@exposedQuery emptyList()

            val condition =
                if (sectionKey != null) {
                    (MediaItemsCacheTable.serverId eq serverId) and (MediaItemsCacheTable.sectionKey eq sectionKey)
                } else {
                    MediaItemsCacheTable.serverId eq serverId
                }

            MediaItemsCacheTable
                .selectAll()
                .where { condition }
                .orderBy(MediaItemsCacheTable.addedAtMs to SortOrder.DESC, MediaItemsCacheTable.ratingKey to SortOrder.ASC)
                .limit(safeLimit)
                .map { it.toMediaItemCacheRecord() }
        }

    override suspend fun findStaleMedia(
        serverId: String,
        sectionKey: String,
        unwatchedCutoffMs: Long?,
        neverPlayedOnly: Boolean,
        sort: StaleMediaSort,
        limit: Int,
    ): Either<MiruError.PersistenceError, StaleMediaReportRecord> =
        database.exposedQuery("library_find_stale_media") {
            val safeLimit = limit.coerceAtLeast(0)
            val (stalePredicate, filterArgs) = buildStalePredicate(serverId, sectionKey, unwatchedCutoffMs, neverPlayedOnly)
            val fromAndWhere =
                """
                FROM media_items_cache m
                LEFT JOIN (
                    SELECT rating_key, COUNT(id) AS play_count, MAX(stopped_at_ms) AS last_played_at_ms
                    FROM playback_history
                    WHERE server_id = ?
                    GROUP BY rating_key
                ) p ON p.rating_key = m.rating_key
                WHERE m.server_id = ? AND m.section_key = ? AND $stalePredicate
                """.trimIndent()

            val summarySql =
                """
                SELECT
                    COUNT(*) AS total_stale_count,
                    COALESCE(SUM(CASE WHEN m.file_size_bytes > 0 THEN m.file_size_bytes ELSE 0 END), 0) AS total_reclaimable_bytes,
                    COALESCE(
                        SUM(CASE WHEN p.play_count IS NULL OR p.play_count = 0 OR p.last_played_at_ms IS NULL THEN 1 ELSE 0 END),
                        0
                    ) AS never_played_count
                $fromAndWhere
                """.trimIndent()

            val summary =
                exec(summarySql, args = filterArgs, explicitStatementType = StatementType.SELECT) { rs ->
                    if (rs.next()) {
                        Triple(
                            rs.getInt("total_stale_count"),
                            rs.getLong("total_reclaimable_bytes"),
                            rs.getInt("never_played_count"),
                        )
                    } else {
                        Triple(0, 0L, 0)
                    }
                } ?: Triple(0, 0L, 0)

            val (totalStaleCount, totalReclaimableBytes, neverPlayedCount) = summary
            if (totalStaleCount == 0 || safeLimit == 0) {
                return@exposedQuery StaleMediaReportRecord(
                    items = emptyList(),
                    totalStaleCount = totalStaleCount,
                    totalReclaimableBytes = totalReclaimableBytes,
                    neverPlayedCount = neverPlayedCount,
                )
            }

            val orderByClause = staleSortOrderByClause(sort)
            val pageSql =
                """
                SELECT
                    m.server_id,
                    m.rating_key,
                    m.section_key,
                    m.media_type,
                    m.title,
                    m.parent_title,
                    m.grandparent_title,
                    m.year,
                    m.thumb_path,
                    m.duration_ms,
                    m.file_size_bytes,
                    m.video_resolution,
                    m.video_codec,
                    m.audio_codec,
                    m.added_at_ms,
                    m.updated_at_ms,
                    COALESCE(p.play_count, 0) AS play_count,
                    p.last_played_at_ms
                $fromAndWhere
                $orderByClause
                LIMIT ?
                """.trimIndent()

            val pageArgs = filterArgs + (IntegerColumnType() to safeLimit)
            val items =
                exec(pageSql, args = pageArgs, explicitStatementType = StatementType.SELECT) { rs ->
                    val list = ArrayList<StaleMediaItemRecord>(minOf(safeLimit, totalStaleCount))
                    while (rs.next()) {
                        list += rs.toStaleMediaItemRecord()
                    }
                    list
                }.orEmpty()

            StaleMediaReportRecord(
                items = items,
                totalStaleCount = totalStaleCount,
                totalReclaimableBytes = totalReclaimableBytes,
                neverPlayedCount = neverPlayedCount,
            )
        }

    private fun buildStalePredicate(
        serverId: String,
        sectionKey: String,
        unwatchedCutoffMs: Long?,
        neverPlayedOnly: Boolean,
    ): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val baseArgs =
            mutableListOf<Pair<IColumnType<*>, Any?>>(
                TextColumnType() to serverId,
                TextColumnType() to serverId,
                TextColumnType() to sectionKey,
            )
        val predicate =
            when {
                neverPlayedOnly -> "(p.play_count IS NULL OR p.play_count = 0 OR p.last_played_at_ms IS NULL)"
                unwatchedCutoffMs != null -> {
                    baseArgs += LongColumnType() to unwatchedCutoffMs
                    "(p.last_played_at_ms IS NULL OR p.last_played_at_ms <= ?)"
                }
                else -> "(p.last_played_at_ms IS NULL)"
            }
        return predicate to baseArgs
    }

    private fun staleSortOrderByClause(sort: StaleMediaSort): String =
        when (sort) {
            StaleMediaSort.SIZE_DESC ->
                "ORDER BY COALESCE(m.file_size_bytes, 0) DESC, m.added_at_ms ASC, m.rating_key ASC"
            StaleMediaSort.SIZE_ASC ->
                "ORDER BY COALESCE(m.file_size_bytes, 0) ASC, m.added_at_ms ASC, m.rating_key ASC"
            StaleMediaSort.AGE_DESC ->
                "ORDER BY COALESCE(p.last_played_at_ms, m.added_at_ms) ASC, m.added_at_ms ASC, " +
                    "COALESCE(m.file_size_bytes, 0) DESC, m.rating_key ASC"
            StaleMediaSort.AGE_ASC ->
                "ORDER BY COALESCE(p.last_played_at_ms, m.added_at_ms) DESC, m.added_at_ms DESC, " +
                    "COALESCE(m.file_size_bytes, 0) DESC, m.rating_key ASC"
        }

    private fun ResultSet.toStaleMediaItemRecord(): StaleMediaItemRecord =
        StaleMediaItemRecord(
            item =
                MediaItemCacheRecord(
                    serverId = getString("server_id"),
                    ratingKey = getString("rating_key"),
                    sectionKey = getString("section_key"),
                    mediaType = getString("media_type"),
                    title = getString("title"),
                    parentTitle = getString("parent_title"),
                    grandparentTitle = getString("grandparent_title"),
                    year = (getObject("year") as? Number)?.toInt(),
                    thumbPath = getString("thumb_path"),
                    durationMs = (getObject("duration_ms") as? Number)?.toLong(),
                    tech =
                        MediaItemTechRecord(
                            fileSizeBytes = (getObject("file_size_bytes") as? Number)?.toLong(),
                            videoResolution = getString("video_resolution"),
                            videoCodec = getString("video_codec"),
                            audioCodec = getString("audio_codec"),
                        ),
                    addedAtMs = getLong("added_at_ms"),
                    updatedAtMs = getLong("updated_at_ms"),
                ),
            playCount = getLong("play_count"),
            lastPlayedAtMs = (getObject("last_played_at_ms") as? Number)?.toLong(),
        )

    override suspend fun deleteItemsNotIn(
        serverId: String,
        sectionKey: String,
        activeRatingKeys: Set<String>,
    ): Either<MiruError.PersistenceError, Int> =
        database.exposedQuery("library_delete_items_not_in") {
            if (activeRatingKeys.isEmpty()) {
                return@exposedQuery MediaItemsCacheTable.deleteWhere {
                    (MediaItemsCacheTable.serverId eq serverId) and (MediaItemsCacheTable.sectionKey eq sectionKey)
                }
            }

            val existingKeys =
                MediaItemsCacheTable
                    .select(MediaItemsCacheTable.ratingKey)
                    .where {
                        (MediaItemsCacheTable.serverId eq serverId) and (MediaItemsCacheTable.sectionKey eq sectionKey)
                    }.map { it[MediaItemsCacheTable.ratingKey] }

            val toDelete = existingKeys.filter { it !in activeRatingKeys }
            if (toDelete.isEmpty()) return@exposedQuery 0

            var deletedCount = 0
            toDelete.chunked(BATCH_SIZE).forEach { chunk ->
                deletedCount +=
                    MediaItemsCacheTable.deleteWhere {
                        (MediaItemsCacheTable.serverId eq serverId) and
                            (MediaItemsCacheTable.sectionKey eq sectionKey) and
                            (MediaItemsCacheTable.ratingKey inList chunk)
                    }
            }
            deletedCount
        }

    override suspend fun replaceUserLibraryAccess(
        serverId: String,
        records: List<UserLibraryAccessRecord>,
    ): Either<MiruError.PersistenceError, Unit> =
        database.exposedQuery("library_replace_user_access") {
            UserLibraryAccessTable.deleteWhere { UserLibraryAccessTable.serverId eq serverId }

            val deduplicated = linkedMapOf<String, UserLibraryAccessRecord>()
            for (record in records) {
                val externalId = record.externalUserId.trim()
                if (externalId.isEmpty()) continue
                val existing = deduplicated[externalId]
                deduplicated[externalId] =
                    if (existing == null) {
                        record.copy(
                            serverId = serverId,
                            externalUserId = externalId,
                            sectionKeys =
                                if (record.allLibraries) {
                                    emptySet()
                                } else {
                                    record.sectionKeys.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                },
                        )
                    } else {
                        val combinedAll = existing.allLibraries || record.allLibraries
                        val combinedKeys =
                            if (combinedAll) {
                                emptySet()
                            } else {
                                existing.sectionKeys + record.sectionKeys.map { it.trim() }.filter { it.isNotEmpty() }
                            }
                        existing.copy(
                            allLibraries = combinedAll,
                            sectionKeys = combinedKeys,
                            updatedAtMs = maxOf(existing.updatedAtMs, record.updatedAtMs),
                        )
                    }
            }

            for (record in deduplicated.values) {
                val encodedKeys =
                    if (record.allLibraries) {
                        "[]"
                    } else {
                        MiruJson.encodeToString(record.sectionKeys.sorted())
                    }
                UserLibraryAccessTable.insert {
                    it[UserLibraryAccessTable.serverId] = serverId
                    it[externalUserId] = record.externalUserId
                    it[allLibraries] = record.allLibraries
                    it[sectionKeysJson] = encodedKeys
                    it[updatedAtMs] = record.updatedAtMs
                }
            }
        }

    override suspend fun findUserLibraryAccessByServer(
        serverId: String,
    ): Either<MiruError.PersistenceError, List<UserLibraryAccessRecord>> =
        database.exposedQuery("library_find_user_access_by_server") {
            UserLibraryAccessTable
                .selectAll()
                .where { UserLibraryAccessTable.serverId eq serverId }
                .orderBy(UserLibraryAccessTable.externalUserId to SortOrder.ASC)
                .map { it.toUserLibraryAccessRecord() }
        }

    override suspend fun findAccessibleLibrariesForMediaUsers(
        mediaUserIds: Set<String>,
    ): Either<MiruError.PersistenceError, ResolvedLibraryAccess> {
        if (mediaUserIds.isEmpty()) {
            return Either.Right(ResolvedLibraryAccess.EMPTY)
        }
        return database.exposedQuery("library_find_accessible_for_users") {
            val userRows =
                UsersTable
                    .select(
                        UsersTable.id,
                        UsersTable.serverId,
                        UsersTable.externalUserId,
                        UsersTable.isAdmin,
                    ).where { UsersTable.id inList mediaUserIds }
                    .toList()

            if (userRows.isEmpty()) {
                return@exposedQuery ResolvedLibraryAccess.EMPTY
            }

            val allLibrariesServerIds = linkedSetOf<String>()
            val pendingUserPairs = linkedSetOf<Pair<String, String>>()
            for (row in userRows) {
                val serverId = row[UsersTable.serverId]
                if (row[UsersTable.isAdmin]) {
                    allLibrariesServerIds += serverId
                } else {
                    pendingUserPairs += serverId to row[UsersTable.externalUserId]
                }
            }

            val filteredPending = pendingUserPairs.filter { (serverId, _) -> serverId !in allLibrariesServerIds }
            if (filteredPending.isEmpty()) {
                return@exposedQuery ResolvedLibraryAccess(
                    allLibrariesServerIds = allLibrariesServerIds,
                    allowedSections = emptySet(),
                )
            }

            val pendingByServer = filteredPending.groupBy({ it.first }, { it.second })
            val allowedSections = linkedSetOf<Pair<String, String>>()

            val accessRows =
                UserLibraryAccessTable
                    .selectAll()
                    .where { UserLibraryAccessTable.serverId inList pendingByServer.keys }
                    .map { it.toUserLibraryAccessRecord() }

            for (record in accessRows) {
                val targetExternalIds = pendingByServer[record.serverId].orEmpty()
                if (record.externalUserId !in targetExternalIds) continue

                if (record.allLibraries) {
                    allLibrariesServerIds += record.serverId
                } else {
                    for (sectionKey in record.sectionKeys) {
                        allowedSections += record.serverId to sectionKey
                    }
                }
            }

            ResolvedLibraryAccess(
                allLibrariesServerIds = allLibrariesServerIds,
                allowedSections = allowedSections,
            )
        }
    }

    override suspend fun findSectionKeysByRatingKey(
        ratingKey: String,
    ): Either<MiruError.PersistenceError, Set<Pair<String, String>>> {
        val trimmedKey = ratingKey.trim()
        if (trimmedKey.isEmpty()) {
            return Either.Right(emptySet())
        }
        return database.exposedQuery("library_find_sections_by_rating_key") {
            val result = linkedSetOf<Pair<String, String>>()

            MediaItemsCacheTable
                .select(MediaItemsCacheTable.serverId, MediaItemsCacheTable.sectionKey)
                .where { MediaItemsCacheTable.ratingKey eq trimmedKey }
                .forEach { row ->
                    val serverId = row[MediaItemsCacheTable.serverId].trim()
                    val sectionKey = row[MediaItemsCacheTable.sectionKey].trim()
                    if (serverId.isNotEmpty() && sectionKey.isNotEmpty()) {
                        result += serverId to sectionKey
                    }
                }

            PlaybackHistoryTable
                .select(PlaybackHistoryTable.serverId, PlaybackHistoryTable.librarySectionKey)
                .where { PlaybackHistoryTable.ratingKey eq trimmedKey }
                .forEach { row ->
                    val serverId = row[PlaybackHistoryTable.serverId].trim()
                    val sectionKey = row[PlaybackHistoryTable.librarySectionKey]?.trim().orEmpty()
                    if (serverId.isNotEmpty() && sectionKey.isNotEmpty()) {
                        result += serverId to sectionKey
                    }
                }

            ActiveSessionsTable
                .select(ActiveSessionsTable.serverId, ActiveSessionsTable.librarySectionKey)
                .where { ActiveSessionsTable.ratingKey eq trimmedKey }
                .forEach { row ->
                    val serverId = row[ActiveSessionsTable.serverId].trim()
                    val sectionKey = row[ActiveSessionsTable.librarySectionKey]?.trim().orEmpty()
                    if (serverId.isNotEmpty() && sectionKey.isNotEmpty()) {
                        result += serverId to sectionKey
                    }
                }

            result
        }
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
