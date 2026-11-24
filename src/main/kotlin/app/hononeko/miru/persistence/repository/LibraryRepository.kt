package app.hononeko.miru.persistence.repository

import app.hononeko.miru.MiruJson
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
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
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

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
            val cachedItems =
                MediaItemsCacheTable
                    .selectAll()
                    .where {
                        (MediaItemsCacheTable.serverId eq serverId) and (MediaItemsCacheTable.sectionKey eq sectionKey)
                    }.map { it.toMediaItemCacheRecord() }

            if (cachedItems.isEmpty()) {
                return@exposedQuery StaleMediaReportRecord(
                    items = emptyList(),
                    totalStaleCount = 0,
                    totalReclaimableBytes = 0L,
                    neverPlayedCount = 0,
                )
            }

            val playCountExpr = PlaybackHistoryTable.id.count()
            val lastPlayedExpr = PlaybackHistoryTable.stoppedAtMs.max()
            val playStatsByKey = hashMapOf<String, Pair<Long, Long?>>()

            for (chunk in cachedItems.map { it.ratingKey }.chunked(BATCH_SIZE)) {
                PlaybackHistoryTable
                    .select(PlaybackHistoryTable.ratingKey, playCountExpr, lastPlayedExpr)
                    .where {
                        (PlaybackHistoryTable.serverId eq serverId) and (PlaybackHistoryTable.ratingKey inList chunk)
                    }.groupBy(PlaybackHistoryTable.ratingKey)
                    .forEach { row ->
                        val rk = row[PlaybackHistoryTable.ratingKey]
                        playStatsByKey[rk] = row[playCountExpr] to row[lastPlayedExpr]
                    }
            }

            val staleCandidates =
                cachedItems.mapNotNull { item ->
                    val stats = playStatsByKey[item.ratingKey]
                    val playCount = stats?.first ?: 0L
                    val lastPlayedAtMs = stats?.second
                    val isStale =
                        if (neverPlayedOnly) {
                            playCount == 0L || lastPlayedAtMs == null
                        } else if (unwatchedCutoffMs != null) {
                            lastPlayedAtMs == null || lastPlayedAtMs <= unwatchedCutoffMs
                        } else {
                            lastPlayedAtMs == null
                        }
                    if (isStale) {
                        StaleMediaItemRecord(
                            item = item,
                            playCount = playCount,
                            lastPlayedAtMs = lastPlayedAtMs,
                        )
                    } else {
                        null
                    }
                }

            val comparator =
                when (sort) {
                    StaleMediaSort.SIZE_DESC ->
                        compareByDescending<StaleMediaItemRecord> { it.item.tech.fileSizeBytes ?: 0L }
                            .thenBy { it.item.addedAtMs }
                            .thenBy { it.item.ratingKey }

                    StaleMediaSort.SIZE_ASC ->
                        compareBy<StaleMediaItemRecord> { it.item.tech.fileSizeBytes ?: 0L }
                            .thenBy { it.item.addedAtMs }
                            .thenBy { it.item.ratingKey }

                    StaleMediaSort.AGE_DESC ->
                        compareBy<StaleMediaItemRecord> { it.lastPlayedAtMs ?: it.item.addedAtMs }
                            .thenBy { it.item.addedAtMs }
                            .thenByDescending { it.item.tech.fileSizeBytes ?: 0L }
                            .thenBy { it.item.ratingKey }

                    StaleMediaSort.AGE_ASC ->
                        compareByDescending<StaleMediaItemRecord> { it.lastPlayedAtMs ?: it.item.addedAtMs }
                            .thenByDescending { it.item.addedAtMs }
                            .thenByDescending { it.item.tech.fileSizeBytes ?: 0L }
                            .thenBy { it.item.ratingKey }
                }

            val sorted = staleCandidates.sortedWith(comparator)
            val totalReclaimableBytes = sorted.sumOf { (it.item.tech.fileSizeBytes ?: 0L).coerceAtLeast(0L) }
            val neverPlayedCount = sorted.count { it.playCount == 0L || it.lastPlayedAtMs == null }

            StaleMediaReportRecord(
                items = if (safeLimit == 0) emptyList() else sorted.take(safeLimit),
                totalStaleCount = sorted.size,
                totalReclaimableBytes = totalReclaimableBytes,
                neverPlayedCount = neverPlayedCount,
            )
        }

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
    }
}
