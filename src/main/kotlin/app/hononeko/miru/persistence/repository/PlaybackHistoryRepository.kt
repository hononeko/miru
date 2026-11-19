package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseDialect
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.dbQuery
import app.hononeko.miru.persistence.escapeLikePattern
import app.hononeko.miru.persistence.getDialectBoolean
import app.hononeko.miru.persistence.getNullableString
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistoryPage
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.setDialectBoolean
import app.hononeko.miru.persistence.setNullableString
import arrow.core.Either
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

interface PlaybackHistoryRepository {
    suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord>

    suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, PlaybackHistoryRecord?>

    suspend fun findLatestMergeCandidate(
        serverId: String,
        userId: String,
        ratingKey: String,
        minStoppedAtMs: Long,
    ): Either<MiruError.PersistenceError, PlaybackHistoryRecord?>

    suspend fun updateMergedSession(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean>

    suspend fun query(filter: HistoryFilter): Either<MiruError.PersistenceError, HistoryPage>
}

class JdbcPlaybackHistoryRepository(
    private val dataSource: DataSource,
    private val dialect: DatabaseDialect,
) : PlaybackHistoryRepository {
    constructor(handle: DatabaseHandle) : this(handle.dataSource, handle.dialect)

    override suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord> =
        dataSource.dbQuery("playback_history_insert") { conn ->
            conn.prepareStatement(INSERT_SQL).use { stmt ->
                bindInsertStatement(stmt, record)
                stmt.executeUpdate()
            }
            record
        }

    override suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        dataSource.dbQuery("playback_history_insert_if_absent") { conn ->
            conn.prepareStatement(INSERT_IF_ABSENT_SQL).use { stmt ->
                bindInsertStatement(stmt, record)
                stmt.executeUpdate() > 0
            }
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, PlaybackHistoryRecord?> =
        dataSource.dbQuery("playback_history_find_by_id") { conn ->
            conn.prepareStatement(SELECT_BY_ID_SQL).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlaybackHistoryRecord() else null
                }
            }
        }

    override suspend fun findLatestMergeCandidate(
        serverId: String,
        userId: String,
        ratingKey: String,
        minStoppedAtMs: Long,
    ): Either<MiruError.PersistenceError, PlaybackHistoryRecord?> =
        dataSource.dbQuery("playback_history_find_merge_candidate") { conn ->
            conn.prepareStatement(SELECT_MERGE_CANDIDATE_SQL).use { stmt ->
                stmt.setString(1, serverId)
                stmt.setString(2, userId)
                stmt.setString(3, ratingKey)
                stmt.setLong(4, minStoppedAtMs)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlaybackHistoryRecord() else null
                }
            }
        }

    override suspend fun updateMergedSession(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        dataSource.dbQuery("playback_history_update_merged") { conn ->
            conn.prepareStatement(UPDATE_MERGED_SQL).use { stmt ->
                var idx = 1
                stmt.setLong(idx++, record.viewOffsetMs)
                stmt.setInt(idx++, record.watchedDurationSec)
                stmt.setInt(idx++, record.pausedDurationSec)
                stmt.setDouble(idx++, record.completionPercent)
                stmt.setDialectBoolean(idx++, record.isWatched, dialect)
                idx = stmt.bindStreamTech(idx, record.streamTech, dialect)
                idx = stmt.bindPlayer(idx, record.player)
                stmt.setLong(idx++, record.startedAtMs)
                stmt.setLong(idx++, record.stoppedAtMs)
                stmt.setString(idx, record.id)
                stmt.executeUpdate() > 0
            }
        }

    override suspend fun query(filter: HistoryFilter): Either<MiruError.PersistenceError, HistoryPage> =
        dataSource.dbQuery("playback_history_query") { conn ->
            val safeLimit = filter.limit.coerceAtLeast(0)
            val safeOffset = filter.offset.coerceAtLeast(0)
            val (whereClause, binders) = buildWhereClause(filter)

            val countSql = "SELECT COUNT(*) AS total_count FROM playback_history$whereClause"
            val totalCount =
                conn.prepareStatement(countSql).use { stmt ->
                    binders.forEachIndexed { index, bind -> bind(stmt, index + 1) }
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong("total_count") else 0L
                    }
                }

            val items =
                if (safeLimit == 0) {
                    emptyList()
                } else {
                    val dataSql =
                        "SELECT $COLUMNS FROM playback_history$whereClause " +
                            "ORDER BY stopped_at_ms DESC, id ASC LIMIT ? OFFSET ?"
                    conn.prepareStatement(dataSql).use { stmt ->
                        binders.forEachIndexed { index, bind -> bind(stmt, index + 1) }
                        stmt.setInt(binders.size + 1, safeLimit)
                        stmt.setInt(binders.size + 2, safeOffset)
                        stmt.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.toPlaybackHistoryRecord())
                                }
                            }
                        }
                    }
                }

            HistoryPage(
                items = items,
                totalCount = totalCount,
                limit = safeLimit,
                offset = safeOffset,
            )
        }

    private fun buildWhereClause(filter: HistoryFilter): Pair<String, List<(PreparedStatement, Int) -> Unit>> {
        val clauses = mutableListOf<String>()
        val binders = mutableListOf<(PreparedStatement, Int) -> Unit>()

        filter.serverId?.let { value ->
            clauses += "server_id = ?"
            binders += { stmt, idx -> stmt.setString(idx, value) }
        }
        filter.userId?.let { value ->
            clauses += "user_id = ?"
            binders += { stmt, idx -> stmt.setString(idx, value) }
        }
        filter.mediaType?.let { value ->
            clauses += "media_type = ?"
            binders += { stmt, idx -> stmt.setString(idx, value) }
        }
        filter.librarySectionKey?.let { value ->
            clauses += "library_section_key = ?"
            binders += { stmt, idx -> stmt.setString(idx, value) }
        }
        filter.streamDecision?.let { value ->
            clauses += "stream_decision = ?"
            binders += { stmt, idx -> stmt.setString(idx, value) }
        }
        filter.fromMs?.let { value ->
            clauses += "stopped_at_ms >= ?"
            binders += { stmt, idx -> stmt.setLong(idx, value) }
        }
        filter.toMs?.let { value ->
            clauses += "stopped_at_ms <= ?"
            binders += { stmt, idx -> stmt.setLong(idx, value) }
        }
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val pattern = "%${escapeLikePattern(raw.lowercase())}%"
            clauses +=
                "(LOWER(title) LIKE ? ESCAPE '\\' OR " +
                "LOWER(parent_title) LIKE ? ESCAPE '\\' OR " +
                "LOWER(grandparent_title) LIKE ? ESCAPE '\\')"
            repeat(3) {
                binders += { stmt, idx -> stmt.setString(idx, pattern) }
            }
        }

        val whereClause = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return whereClause to binders
    }

    private fun bindInsertStatement(
        stmt: PreparedStatement,
        record: PlaybackHistoryRecord,
    ) {
        var idx = 1
        stmt.setString(idx++, record.id)
        stmt.setString(idx++, record.serverId)
        stmt.setString(idx++, record.userId)
        stmt.setNullableString(idx++, record.externalImportKey)
        idx = stmt.bindMediaMetadata(idx, record.media)
        stmt.setLong(idx++, record.viewOffsetMs)
        stmt.setInt(idx++, record.watchedDurationSec)
        stmt.setInt(idx++, record.pausedDurationSec)
        stmt.setDouble(idx++, record.completionPercent)
        stmt.setDialectBoolean(idx++, record.isWatched, dialect)
        idx = stmt.bindStreamTech(idx, record.streamTech, dialect)
        idx = stmt.bindPlayer(idx, record.player)
        stmt.setLong(idx++, record.startedAtMs)
        stmt.setLong(idx, record.stoppedAtMs)
    }

    private fun ResultSet.toPlaybackHistoryRecord(): PlaybackHistoryRecord =
        PlaybackHistoryRecord(
            id = getString("id"),
            serverId = getString("server_id"),
            userId = getString("user_id"),
            externalImportKey = getNullableString("external_import_key"),
            media = readMediaMetadata(),
            viewOffsetMs = getLong("view_offset_ms"),
            watchedDurationSec = getInt("watched_duration_sec"),
            pausedDurationSec = getInt("paused_duration_sec"),
            completionPercent = getDouble("completion_percent"),
            isWatched = getDialectBoolean("is_watched", dialect),
            streamTech = readStreamTech(dialect),
            player = readPlayer(),
            startedAtMs = getLong("started_at_ms"),
            stoppedAtMs = getLong("stopped_at_ms"),
        )

    private companion object {
        const val COLUMNS =
            "id, server_id, user_id, external_import_key, rating_key, media_type, title, " +
                "parent_title, grandparent_title, parent_index, media_index, year, thumb_path, " +
                "library_section_key, duration_ms, view_offset_ms, watched_duration_sec, " +
                "paused_duration_sec, completion_percent, is_watched, stream_decision, " +
                "video_decision, audio_decision, hw_transcode, video_codec, audio_codec, " +
                "video_resolution, bandwidth_kbps, player_name, player_platform, player_product, " +
                "player_machine_id, ip_address, location, started_at_ms, stopped_at_ms"

        const val INSERT_VALUES_SQL =
            """
            INSERT INTO playback_history (
                id, server_id, user_id, external_import_key, rating_key, media_type, title,
                parent_title, grandparent_title, parent_index, media_index, year, thumb_path,
                library_section_key, duration_ms, view_offset_ms, watched_duration_sec,
                paused_duration_sec, completion_percent, is_watched, stream_decision,
                video_decision, audio_decision, hw_transcode, video_codec, audio_codec,
                video_resolution, bandwidth_kbps, player_name, player_platform, player_product,
                player_machine_id, ip_address, location, started_at_ms, stopped_at_ms
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """

        const val INSERT_SQL = INSERT_VALUES_SQL
        const val INSERT_IF_ABSENT_SQL =
            "$INSERT_VALUES_SQL ON CONFLICT (external_import_key) DO NOTHING"

        const val SELECT_BY_ID_SQL = "SELECT $COLUMNS FROM playback_history WHERE id = ?"

        const val SELECT_MERGE_CANDIDATE_SQL =
            """
            SELECT $COLUMNS
            FROM playback_history
            WHERE server_id = ? AND user_id = ? AND rating_key = ? AND stopped_at_ms >= ?
            ORDER BY stopped_at_ms DESC, id ASC
            LIMIT 1
            """

        const val UPDATE_MERGED_SQL =
            """
            UPDATE playback_history
            SET view_offset_ms = ?,
                watched_duration_sec = ?,
                paused_duration_sec = ?,
                completion_percent = ?,
                is_watched = ?,
                stream_decision = ?,
                video_decision = ?,
                audio_decision = ?,
                hw_transcode = ?,
                video_codec = ?,
                audio_codec = ?,
                video_resolution = ?,
                bandwidth_kbps = ?,
                player_name = ?,
                player_platform = ?,
                player_product = ?,
                player_machine_id = ?,
                ip_address = ?,
                location = ?,
                started_at_ms = ?,
                stopped_at_ms = ?
            WHERE id = ?
            """
    }
}
