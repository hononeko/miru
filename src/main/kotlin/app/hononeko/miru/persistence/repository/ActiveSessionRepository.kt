package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseDialect
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.dbQuery
import app.hononeko.miru.persistence.dbTransaction
import app.hononeko.miru.persistence.getNullableString
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.setNullableString
import arrow.core.Either
import java.sql.ResultSet
import javax.sql.DataSource

interface ActiveSessionRepository {
    suspend fun upsert(session: ActiveSessionRecord): Either<MiruError.PersistenceError, Unit>

    suspend fun findByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, ActiveSessionRecord?>

    suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<ActiveSessionRecord>>

    suspend fun findAll(): Either<MiruError.PersistenceError, List<ActiveSessionRecord>>

    suspend fun deleteByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, Boolean>

    suspend fun deleteStaleMissing(
        serverId: String,
        activeKeys: Set<ActiveSessionKey>,
    ): Either<MiruError.PersistenceError, Int>
}

class JdbcActiveSessionRepository(
    private val dataSource: DataSource,
    private val dialect: DatabaseDialect,
) : ActiveSessionRepository {
    constructor(handle: DatabaseHandle) : this(handle.dataSource, handle.dialect)

    override suspend fun upsert(session: ActiveSessionRecord): Either<MiruError.PersistenceError, Unit> =
        dataSource.dbQuery("active_session_upsert") { conn ->
            conn.prepareStatement(UPSERT_SQL).use { stmt ->
                var idx = 1
                stmt.setString(idx++, session.serverId)
                stmt.setString(idx++, session.sessionKey)
                stmt.setNullableString(idx++, session.sessionId)
                stmt.setString(idx++, session.userId)
                idx = stmt.bindMediaMetadata(idx, session.media)
                stmt.setLong(idx++, session.viewOffsetMs)
                stmt.setString(idx++, session.state)
                idx = stmt.bindStreamTech(idx, session.streamTech, dialect)
                idx = stmt.bindPlayer(idx, session.player)
                stmt.setLong(idx++, session.startedAtMs)
                stmt.setLong(idx++, session.lastSeenAtMs)
                stmt.setInt(idx++, session.pausedDurationSec)
                stmt.setInt(idx, session.missedPolls)
                stmt.executeUpdate()
                return@use
            }
        }

    override suspend fun findByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, ActiveSessionRecord?> =
        dataSource.dbQuery("active_session_find_by_key") { conn ->
            conn.prepareStatement(SELECT_BY_KEY_SQL).use { stmt ->
                stmt.setString(1, key.serverId)
                stmt.setString(2, key.sessionKey)
                stmt.setString(3, key.ratingKey)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toActiveSessionRecord() else null
                }
            }
        }

    override suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<ActiveSessionRecord>> =
        dataSource.dbQuery("active_session_find_by_server") { conn ->
            conn.prepareStatement(SELECT_BY_SERVER_SQL).use { stmt ->
                stmt.setString(1, serverId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toActiveSessionRecord())
                        }
                    }
                }
            }
        }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<ActiveSessionRecord>> =
        dataSource.dbQuery("active_session_find_all") { conn ->
            conn.prepareStatement(SELECT_ALL_SQL).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toActiveSessionRecord())
                        }
                    }
                }
            }
        }

    override suspend fun deleteByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, Boolean> =
        dataSource.dbQuery("active_session_delete_by_key") { conn ->
            conn.prepareStatement(DELETE_BY_KEY_SQL).use { stmt ->
                stmt.setString(1, key.serverId)
                stmt.setString(2, key.sessionKey)
                stmt.setString(3, key.ratingKey)
                stmt.executeUpdate() > 0
            }
        }

    override suspend fun deleteStaleMissing(
        serverId: String,
        activeKeys: Set<ActiveSessionKey>,
    ): Either<MiruError.PersistenceError, Int> =
        dataSource.dbTransaction("active_session_delete_stale_missing") { conn ->
            val serverActiveKeys = activeKeys.filterTo(HashSet()) { it.serverId == serverId }
            if (serverActiveKeys.isEmpty()) {
                return@dbTransaction conn.prepareStatement(DELETE_BY_SERVER_SQL).use { stmt ->
                    stmt.setString(1, serverId)
                    stmt.executeUpdate()
                }
            }

            val existingKeys =
                conn.prepareStatement(SELECT_KEYS_BY_SERVER_SQL).use { stmt ->
                    stmt.setString(1, serverId)
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ActiveSessionKey(
                                        serverId = serverId,
                                        sessionKey = rs.getString("session_key"),
                                        ratingKey = rs.getString("rating_key"),
                                    ),
                                )
                            }
                        }
                    }
                }

            val staleKeys = existingKeys.filter { it !in serverActiveKeys }
            if (staleKeys.isEmpty()) {
                return@dbTransaction 0
            }

            var deleted = 0
            conn.prepareStatement(DELETE_BY_KEY_SQL).use { stmt ->
                for (key in staleKeys) {
                    stmt.setString(1, key.serverId)
                    stmt.setString(2, key.sessionKey)
                    stmt.setString(3, key.ratingKey)
                    deleted += stmt.executeUpdate()
                }
            }
            deleted
        }

    private fun ResultSet.toActiveSessionRecord(): ActiveSessionRecord =
        ActiveSessionRecord(
            serverId = getString("server_id"),
            sessionKey = getString("session_key"),
            sessionId = getNullableString("session_id"),
            userId = getString("user_id"),
            media = readMediaMetadata(),
            viewOffsetMs = getLong("view_offset_ms"),
            state = getString("state"),
            streamTech = readStreamTech(dialect),
            player = readPlayer(),
            startedAtMs = getLong("started_at_ms"),
            lastSeenAtMs = getLong("last_seen_at_ms"),
            pausedDurationSec = getInt("paused_duration_sec"),
            missedPolls = getInt("missed_polls"),
        )

    private companion object {
        const val COLUMNS =
            "server_id, session_key, session_id, user_id, rating_key, media_type, title, " +
                "parent_title, grandparent_title, parent_index, media_index, year, thumb_path, " +
                "library_section_key, duration_ms, view_offset_ms, state, stream_decision, " +
                "video_decision, audio_decision, hw_transcode, video_codec, audio_codec, " +
                "video_resolution, bandwidth_kbps, player_name, player_platform, player_product, " +
                "player_machine_id, ip_address, location, started_at_ms, last_seen_at_ms, " +
                "paused_duration_sec, missed_polls"

        const val SELECT_BY_KEY_SQL =
            "SELECT $COLUMNS FROM active_sessions WHERE server_id = ? AND session_key = ? AND rating_key = ?"
        const val SELECT_BY_SERVER_SQL =
            "SELECT $COLUMNS FROM active_sessions WHERE server_id = ? " +
                "ORDER BY started_at_ms DESC, session_key ASC, rating_key ASC"
        const val SELECT_ALL_SQL =
            "SELECT $COLUMNS FROM active_sessions ORDER BY started_at_ms DESC, server_id ASC, session_key ASC, rating_key ASC"
        const val SELECT_KEYS_BY_SERVER_SQL =
            "SELECT session_key, rating_key FROM active_sessions WHERE server_id = ?"
        const val DELETE_BY_KEY_SQL =
            "DELETE FROM active_sessions WHERE server_id = ? AND session_key = ? AND rating_key = ?"
        const val DELETE_BY_SERVER_SQL =
            "DELETE FROM active_sessions WHERE server_id = ?"

        const val UPSERT_SQL =
            """
            INSERT INTO active_sessions (
                server_id, session_key, session_id, user_id, rating_key, media_type, title,
                parent_title, grandparent_title, parent_index, media_index, year, thumb_path,
                library_section_key, duration_ms, view_offset_ms, state, stream_decision,
                video_decision, audio_decision, hw_transcode, video_codec, audio_codec,
                video_resolution, bandwidth_kbps, player_name, player_platform, player_product,
                player_machine_id, ip_address, location, started_at_ms, last_seen_at_ms,
                paused_duration_sec, missed_polls
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            ON CONFLICT (server_id, session_key, rating_key) DO UPDATE SET
                session_id = EXCLUDED.session_id,
                user_id = EXCLUDED.user_id,
                media_type = EXCLUDED.media_type,
                title = EXCLUDED.title,
                parent_title = EXCLUDED.parent_title,
                grandparent_title = EXCLUDED.grandparent_title,
                parent_index = EXCLUDED.parent_index,
                media_index = EXCLUDED.media_index,
                year = EXCLUDED.year,
                thumb_path = EXCLUDED.thumb_path,
                library_section_key = EXCLUDED.library_section_key,
                duration_ms = EXCLUDED.duration_ms,
                view_offset_ms = EXCLUDED.view_offset_ms,
                state = EXCLUDED.state,
                stream_decision = EXCLUDED.stream_decision,
                video_decision = EXCLUDED.video_decision,
                audio_decision = EXCLUDED.audio_decision,
                hw_transcode = EXCLUDED.hw_transcode,
                video_codec = EXCLUDED.video_codec,
                audio_codec = EXCLUDED.audio_codec,
                video_resolution = EXCLUDED.video_resolution,
                bandwidth_kbps = EXCLUDED.bandwidth_kbps,
                player_name = EXCLUDED.player_name,
                player_platform = EXCLUDED.player_platform,
                player_product = EXCLUDED.player_product,
                player_machine_id = EXCLUDED.player_machine_id,
                ip_address = EXCLUDED.ip_address,
                location = EXCLUDED.location,
                started_at_ms = EXCLUDED.started_at_ms,
                last_seen_at_ms = EXCLUDED.last_seen_at_ms,
                paused_duration_sec = EXCLUDED.paused_duration_sec,
                missed_polls = EXCLUDED.missed_polls
            """
    }
}
