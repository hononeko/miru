package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.table.LibrarySectionsTable
import app.hononeko.miru.persistence.table.LibrarySnapshotsTable
import app.hononeko.miru.persistence.table.MediaItemsCacheTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
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

    suspend fun deleteItemsNotIn(
        serverId: String,
        sectionKey: String,
        activeRatingKeys: Set<String>,
    ): Either<MiruError.PersistenceError, Int>
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

    private companion object {
        const val BATCH_SIZE = 500
    }
}
