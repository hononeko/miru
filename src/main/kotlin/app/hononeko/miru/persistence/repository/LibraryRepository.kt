package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.dbQuery
import app.hononeko.miru.persistence.dbTransaction
import app.hononeko.miru.persistence.getNullableInt
import app.hononeko.miru.persistence.getNullableLong
import app.hononeko.miru.persistence.getNullableString
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.setNullableInt
import app.hononeko.miru.persistence.setNullableLong
import app.hononeko.miru.persistence.setNullableString
import arrow.core.Either
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

interface LibraryRepository {
    suspend fun upsertSection(section: LibrarySectionRecord): Either<MiruError.PersistenceError, Unit>

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

class JdbcLibraryRepository(
    private val dataSource: DataSource,
) : LibraryRepository {
    constructor(handle: DatabaseHandle) : this(handle.dataSource)

    override suspend fun upsertSection(section: LibrarySectionRecord): Either<MiruError.PersistenceError, Unit> =
        dataSource.dbQuery("library_upsert_section") { conn ->
            conn.prepareStatement(UPSERT_SECTION_SQL).use { stmt ->
                var idx = 1
                stmt.setString(idx++, section.serverId)
                stmt.setString(idx++, section.sectionKey)
                stmt.setString(idx++, section.title)
                stmt.setString(idx++, section.sectionType)
                stmt.setInt(idx++, section.itemCount)
                stmt.setInt(idx++, section.childCount)
                stmt.setInt(idx++, section.leafCount)
                stmt.setLong(idx++, section.totalSizeBytes)
                stmt.setLong(idx++, section.totalDurationMs)
                stmt.setNullableLong(idx++, section.lastSyncedAtMs)
                stmt.setLong(idx, section.updatedAtMs)
                stmt.executeUpdate()
                return@use
            }
        }

    override suspend fun findSectionsByServer(serverId: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        dataSource.dbQuery("library_find_sections_by_server") { conn ->
            conn.prepareStatement(SELECT_SECTIONS_BY_SERVER_SQL).use { stmt ->
                stmt.setString(1, serverId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toLibrarySectionRecord())
                        }
                    }
                }
            }
        }

    override suspend fun insertSnapshot(snapshot: LibrarySnapshotRecord): Either<MiruError.PersistenceError, Unit> =
        dataSource.dbQuery("library_insert_snapshot") { conn ->
            conn.prepareStatement(INSERT_SNAPSHOT_SQL).use { stmt ->
                var idx = 1
                stmt.setString(idx++, snapshot.id)
                stmt.setString(idx++, snapshot.serverId)
                stmt.setString(idx++, snapshot.sectionKey)
                stmt.setLong(idx++, snapshot.capturedAtMs)
                stmt.setInt(idx++, snapshot.itemCount)
                stmt.setInt(idx++, snapshot.childCount)
                stmt.setInt(idx++, snapshot.leafCount)
                stmt.setLong(idx++, snapshot.totalSizeBytes)
                stmt.setLong(idx++, snapshot.totalDurationMs)
                stmt.setNullableString(idx, snapshot.aggregatesJson)
                stmt.executeUpdate()
                return@use
            }
        }

    override suspend fun findSnapshots(
        serverId: String,
        sectionKey: String,
        limit: Int,
        fromMs: Long?,
        toMs: Long?,
    ): Either<MiruError.PersistenceError, List<LibrarySnapshotRecord>> =
        dataSource.dbQuery("library_find_snapshots") { conn ->
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) return@dbQuery emptyList()

            val clauses = mutableListOf("server_id = ?", "section_key = ?")
            val binders =
                mutableListOf<(PreparedStatement, Int) -> Unit>(
                    { stmt, idx -> stmt.setString(idx, serverId) },
                    { stmt, idx -> stmt.setString(idx, sectionKey) },
                )
            fromMs?.let { value ->
                clauses += "captured_at_ms >= ?"
                binders += { stmt, idx -> stmt.setLong(idx, value) }
            }
            toMs?.let { value ->
                clauses += "captured_at_ms <= ?"
                binders += { stmt, idx -> stmt.setLong(idx, value) }
            }

            val sql =
                "SELECT $SNAPSHOT_COLUMNS FROM library_snapshots " +
                    "WHERE ${clauses.joinToString(" AND ")} " +
                    "ORDER BY captured_at_ms DESC, id ASC LIMIT ?"

            conn.prepareStatement(sql).use { stmt ->
                binders.forEachIndexed { index, bind -> bind(stmt, index + 1) }
                stmt.setInt(binders.size + 1, safeLimit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toLibrarySnapshotRecord())
                        }
                    }
                }
            }
        }

    override suspend fun upsertMediaItems(items: List<MediaItemCacheRecord>): Either<MiruError.PersistenceError, Int> {
        if (items.isEmpty()) return Either.Right(0)
        return dataSource.dbTransaction("library_upsert_media_items") { conn ->
            conn.prepareStatement(UPSERT_MEDIA_ITEM_SQL).use { stmt ->
                items.chunked(BATCH_SIZE).forEach { chunk ->
                    for (item in chunk) {
                        var idx = 1
                        stmt.setString(idx++, item.serverId)
                        stmt.setString(idx++, item.ratingKey)
                        stmt.setString(idx++, item.sectionKey)
                        stmt.setString(idx++, item.mediaType)
                        stmt.setString(idx++, item.title)
                        stmt.setNullableString(idx++, item.parentTitle)
                        stmt.setNullableString(idx++, item.grandparentTitle)
                        stmt.setNullableInt(idx++, item.year)
                        stmt.setNullableString(idx++, item.thumbPath)
                        stmt.setNullableLong(idx++, item.durationMs)
                        stmt.setNullableLong(idx++, item.tech.fileSizeBytes)
                        stmt.setNullableString(idx++, item.tech.videoResolution)
                        stmt.setNullableString(idx++, item.tech.videoCodec)
                        stmt.setNullableString(idx++, item.tech.audioCodec)
                        stmt.setLong(idx++, item.addedAtMs)
                        stmt.setLong(idx, item.updatedAtMs)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
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
        dataSource.dbQuery("library_find_recently_added") { conn ->
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) return@dbQuery emptyList()

            val sql =
                if (sectionKey != null) {
                    "SELECT $MEDIA_ITEM_COLUMNS FROM media_items_cache " +
                        "WHERE server_id = ? AND section_key = ? " +
                        "ORDER BY added_at_ms DESC, rating_key ASC LIMIT ?"
                } else {
                    "SELECT $MEDIA_ITEM_COLUMNS FROM media_items_cache " +
                        "WHERE server_id = ? ORDER BY added_at_ms DESC, rating_key ASC LIMIT ?"
                }

            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, serverId)
                if (sectionKey != null) {
                    stmt.setString(idx++, sectionKey)
                }
                stmt.setInt(idx, safeLimit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toMediaItemCacheRecord())
                        }
                    }
                }
            }
        }

    override suspend fun deleteItemsNotIn(
        serverId: String,
        sectionKey: String,
        activeRatingKeys: Set<String>,
    ): Either<MiruError.PersistenceError, Int> =
        dataSource.dbTransaction("library_delete_items_not_in") { conn ->
            if (activeRatingKeys.isEmpty()) {
                return@dbTransaction conn.prepareStatement(DELETE_SECTION_ITEMS_SQL).use { stmt ->
                    stmt.setString(1, serverId)
                    stmt.setString(2, sectionKey)
                    stmt.executeUpdate()
                }
            }

            val existingKeys =
                conn.prepareStatement(SELECT_SECTION_RATING_KEYS_SQL).use { stmt ->
                    stmt.setString(1, serverId)
                    stmt.setString(2, sectionKey)
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.getString("rating_key"))
                            }
                        }
                    }
                }

            val toDelete = existingKeys.filter { it !in activeRatingKeys }
            if (toDelete.isEmpty()) return@dbTransaction 0

            var deletedCount = 0
            toDelete.chunked(BATCH_SIZE).forEach { chunk ->
                val placeholders = List(chunk.size) { "?" }.joinToString(", ")
                val deleteSql =
                    "DELETE FROM media_items_cache " +
                        "WHERE server_id = ? AND section_key = ? AND rating_key IN ($placeholders)"
                conn.prepareStatement(deleteSql).use { stmt ->
                    stmt.setString(1, serverId)
                    stmt.setString(2, sectionKey)
                    chunk.forEachIndexed { index, key ->
                        stmt.setString(index + 3, key)
                    }
                    deletedCount += stmt.executeUpdate()
                }
            }
            deletedCount
        }

    private fun ResultSet.toLibrarySectionRecord(): LibrarySectionRecord =
        LibrarySectionRecord(
            serverId = getString("server_id"),
            sectionKey = getString("section_key"),
            title = getString("title"),
            sectionType = getString("section_type"),
            itemCount = getInt("item_count"),
            childCount = getInt("child_count"),
            leafCount = getInt("leaf_count"),
            totalSizeBytes = getLong("total_size_bytes"),
            totalDurationMs = getLong("total_duration_ms"),
            lastSyncedAtMs = getNullableLong("last_synced_at_ms"),
            updatedAtMs = getLong("updated_at_ms"),
        )

    private fun ResultSet.toLibrarySnapshotRecord(): LibrarySnapshotRecord =
        LibrarySnapshotRecord(
            id = getString("id"),
            serverId = getString("server_id"),
            sectionKey = getString("section_key"),
            capturedAtMs = getLong("captured_at_ms"),
            itemCount = getInt("item_count"),
            childCount = getInt("child_count"),
            leafCount = getInt("leaf_count"),
            totalSizeBytes = getLong("total_size_bytes"),
            totalDurationMs = getLong("total_duration_ms"),
            aggregatesJson = getNullableString("aggregates_json"),
        )

    private fun ResultSet.toMediaItemCacheRecord(): MediaItemCacheRecord =
        MediaItemCacheRecord(
            serverId = getString("server_id"),
            ratingKey = getString("rating_key"),
            sectionKey = getString("section_key"),
            mediaType = getString("media_type"),
            title = getString("title"),
            parentTitle = getNullableString("parent_title"),
            grandparentTitle = getNullableString("grandparent_title"),
            year = getNullableInt("year"),
            thumbPath = getNullableString("thumb_path"),
            durationMs = getNullableLong("duration_ms"),
            tech =
                MediaItemTechRecord(
                    fileSizeBytes = getNullableLong("file_size_bytes"),
                    videoResolution = getNullableString("video_resolution"),
                    videoCodec = getNullableString("video_codec"),
                    audioCodec = getNullableString("audio_codec"),
                ),
            addedAtMs = getLong("added_at_ms"),
            updatedAtMs = getLong("updated_at_ms"),
        )

    private companion object {
        const val BATCH_SIZE = 500

        const val SECTION_COLUMNS =
            "server_id, section_key, title, section_type, item_count, child_count, leaf_count, " +
                "total_size_bytes, total_duration_ms, last_synced_at_ms, updated_at_ms"
        const val SNAPSHOT_COLUMNS =
            "id, server_id, section_key, captured_at_ms, item_count, child_count, leaf_count, " +
                "total_size_bytes, total_duration_ms, aggregates_json"
        const val MEDIA_ITEM_COLUMNS =
            "server_id, rating_key, section_key, media_type, title, parent_title, " +
                "grandparent_title, year, thumb_path, duration_ms, file_size_bytes, " +
                "video_resolution, video_codec, audio_codec, added_at_ms, updated_at_ms"

        const val SELECT_SECTIONS_BY_SERVER_SQL =
            "SELECT $SECTION_COLUMNS FROM library_sections WHERE server_id = ? ORDER BY title ASC, section_key ASC"
        const val SELECT_SECTION_RATING_KEYS_SQL =
            "SELECT rating_key FROM media_items_cache WHERE server_id = ? AND section_key = ?"
        const val DELETE_SECTION_ITEMS_SQL =
            "DELETE FROM media_items_cache WHERE server_id = ? AND section_key = ?"

        const val UPSERT_SECTION_SQL =
            """
            INSERT INTO library_sections (
                server_id, section_key, title, section_type, item_count, child_count, leaf_count,
                total_size_bytes, total_duration_ms, last_synced_at_ms, updated_at_ms
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (server_id, section_key) DO UPDATE SET
                title = EXCLUDED.title,
                section_type = EXCLUDED.section_type,
                item_count = EXCLUDED.item_count,
                child_count = EXCLUDED.child_count,
                leaf_count = EXCLUDED.leaf_count,
                total_size_bytes = EXCLUDED.total_size_bytes,
                total_duration_ms = EXCLUDED.total_duration_ms,
                last_synced_at_ms = EXCLUDED.last_synced_at_ms,
                updated_at_ms = EXCLUDED.updated_at_ms
            """

        const val INSERT_SNAPSHOT_SQL =
            """
            INSERT INTO library_snapshots (
                id, server_id, section_key, captured_at_ms, item_count, child_count,
                leaf_count, total_size_bytes, total_duration_ms, aggregates_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """

        const val UPSERT_MEDIA_ITEM_SQL =
            """
            INSERT INTO media_items_cache (
                server_id, rating_key, section_key, media_type, title, parent_title,
                grandparent_title, year, thumb_path, duration_ms, file_size_bytes,
                video_resolution, video_codec, audio_codec, added_at_ms, updated_at_ms
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (server_id, rating_key) DO UPDATE SET
                section_key = EXCLUDED.section_key,
                media_type = EXCLUDED.media_type,
                title = EXCLUDED.title,
                parent_title = EXCLUDED.parent_title,
                grandparent_title = EXCLUDED.grandparent_title,
                year = EXCLUDED.year,
                thumb_path = EXCLUDED.thumb_path,
                duration_ms = EXCLUDED.duration_ms,
                file_size_bytes = EXCLUDED.file_size_bytes,
                video_resolution = EXCLUDED.video_resolution,
                video_codec = EXCLUDED.video_codec,
                audio_codec = EXCLUDED.audio_codec,
                added_at_ms = EXCLUDED.added_at_ms,
                updated_at_ms = EXCLUDED.updated_at_ms
            """
    }
}
