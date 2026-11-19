package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseDialect
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.dbQuery
import app.hononeko.miru.persistence.dbTransaction
import app.hononeko.miru.persistence.getDialectBoolean
import app.hononeko.miru.persistence.getNullableLong
import app.hononeko.miru.persistence.getNullableString
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.setDialectBoolean
import app.hononeko.miru.persistence.setNullableLong
import app.hononeko.miru.persistence.setNullableString
import arrow.core.Either
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

interface UserRepository {
    suspend fun upsert(user: UserRecord): Either<MiruError.PersistenceError, UserRecord>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, UserRecord?>

    suspend fun findByExternalId(
        serverId: String,
        externalUserId: String,
    ): Either<MiruError.PersistenceError, UserRecord?>

    suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<UserRecord>>

    suspend fun updateLastSeen(
        id: String,
        lastSeenAtMs: Long,
        updatedAtMs: Long = lastSeenAtMs,
    ): Either<MiruError.PersistenceError, Boolean>
}

class JdbcUserRepository(
    private val dataSource: DataSource,
    private val dialect: DatabaseDialect,
) : UserRepository {
    constructor(handle: DatabaseHandle) : this(handle.dataSource, handle.dialect)

    override suspend fun upsert(user: UserRecord): Either<MiruError.PersistenceError, UserRecord> =
        dataSource.dbTransaction("user_upsert") { conn ->
            if (existsById(conn, user.id)) {
                conn.prepareStatement(UPDATE_BY_ID_SQL).use { stmt ->
                    var idx = 1
                    stmt.setString(idx++, user.serverId)
                    stmt.setString(idx++, user.externalUserId)
                    stmt.setString(idx++, user.username)
                    stmt.setNullableString(idx++, user.displayName)
                    stmt.setNullableString(idx++, user.email)
                    stmt.setNullableString(idx++, user.thumbUrl)
                    stmt.setDialectBoolean(idx++, user.isAdmin, dialect)
                    stmt.setNullableLong(idx++, user.lastSeenAtMs)
                    stmt.setLong(idx++, user.updatedAtMs)
                    stmt.setString(idx, user.id)
                    stmt.executeUpdate()
                }
                selectById(conn, user.id)
                    ?: throw SQLException("Upserted user '${user.id}' could not be reloaded")
            } else {
                conn.prepareStatement(UPSERT_BY_EXTERNAL_ID_SQL).use { stmt ->
                    var idx = 1
                    stmt.setString(idx++, user.id)
                    stmt.setString(idx++, user.serverId)
                    stmt.setString(idx++, user.externalUserId)
                    stmt.setString(idx++, user.username)
                    stmt.setNullableString(idx++, user.displayName)
                    stmt.setNullableString(idx++, user.email)
                    stmt.setNullableString(idx++, user.thumbUrl)
                    stmt.setDialectBoolean(idx++, user.isAdmin, dialect)
                    stmt.setNullableLong(idx++, user.lastSeenAtMs)
                    stmt.setLong(idx++, user.createdAtMs)
                    stmt.setLong(idx, user.updatedAtMs)
                    stmt.executeUpdate()
                }
                selectByExternalId(conn, user.serverId, user.externalUserId)
                    ?: throw SQLException(
                        "Upserted user '${user.serverId}/${user.externalUserId}' could not be reloaded",
                    )
            }
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, UserRecord?> =
        dataSource.dbQuery("user_find_by_id") { conn ->
            selectById(conn, id)
        }

    override suspend fun findByExternalId(
        serverId: String,
        externalUserId: String,
    ): Either<MiruError.PersistenceError, UserRecord?> =
        dataSource.dbQuery("user_find_by_external_id") { conn ->
            selectByExternalId(conn, serverId, externalUserId)
        }

    override suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<UserRecord>> =
        dataSource.dbQuery("user_find_by_server") { conn ->
            conn.prepareStatement(SELECT_BY_SERVER_SQL).use { stmt ->
                stmt.setString(1, serverId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toUserRecord())
                        }
                    }
                }
            }
        }

    override suspend fun updateLastSeen(
        id: String,
        lastSeenAtMs: Long,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        dataSource.dbQuery("user_update_last_seen") { conn ->
            conn.prepareStatement(UPDATE_LAST_SEEN_SQL).use { stmt ->
                stmt.setLong(1, lastSeenAtMs)
                stmt.setLong(2, updatedAtMs)
                stmt.setString(3, id)
                stmt.executeUpdate() > 0
            }
        }

    private fun existsById(
        conn: Connection,
        id: String,
    ): Boolean =
        conn.prepareStatement(EXISTS_BY_ID_SQL).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs -> rs.next() }
        }

    private fun selectById(
        conn: Connection,
        id: String,
    ): UserRecord? =
        conn.prepareStatement(SELECT_BY_ID_SQL).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toUserRecord() else null
            }
        }

    private fun selectByExternalId(
        conn: Connection,
        serverId: String,
        externalUserId: String,
    ): UserRecord? =
        conn.prepareStatement(SELECT_BY_EXTERNAL_ID_SQL).use { stmt ->
            stmt.setString(1, serverId)
            stmt.setString(2, externalUserId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toUserRecord() else null
            }
        }

    private fun ResultSet.toUserRecord(): UserRecord =
        UserRecord(
            id = getString("id"),
            serverId = getString("server_id"),
            externalUserId = getString("external_user_id"),
            username = getString("username"),
            displayName = getNullableString("display_name"),
            email = getNullableString("email"),
            thumbUrl = getNullableString("thumb_url"),
            isAdmin = getDialectBoolean("is_admin", dialect),
            lastSeenAtMs = getNullableLong("last_seen_at_ms"),
            createdAtMs = getLong("created_at_ms"),
            updatedAtMs = getLong("updated_at_ms"),
        )

    private companion object {
        const val COLUMNS =
            "id, server_id, external_user_id, username, display_name, email, thumb_url, " +
                "is_admin, last_seen_at_ms, created_at_ms, updated_at_ms"
        const val EXISTS_BY_ID_SQL = "SELECT 1 FROM users WHERE id = ?"
        const val SELECT_BY_ID_SQL = "SELECT $COLUMNS FROM users WHERE id = ?"
        const val SELECT_BY_EXTERNAL_ID_SQL =
            "SELECT $COLUMNS FROM users WHERE server_id = ? AND external_user_id = ?"
        const val SELECT_BY_SERVER_SQL =
            "SELECT $COLUMNS FROM users WHERE server_id = ? ORDER BY username ASC, id ASC"
        const val UPDATE_LAST_SEEN_SQL =
            "UPDATE users SET last_seen_at_ms = ?, updated_at_ms = ? WHERE id = ?"
        const val UPDATE_BY_ID_SQL =
            """
            UPDATE users
            SET server_id = ?, external_user_id = ?, username = ?, display_name = ?,
                email = ?, thumb_url = ?, is_admin = ?,
                last_seen_at_ms = COALESCE(?, last_seen_at_ms), updated_at_ms = ?
            WHERE id = ?
            """
        const val UPSERT_BY_EXTERNAL_ID_SQL =
            """
            INSERT INTO users (
                id, server_id, external_user_id, username, display_name, email,
                thumb_url, is_admin, last_seen_at_ms, created_at_ms, updated_at_ms
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (server_id, external_user_id) DO UPDATE SET
                username = EXCLUDED.username,
                display_name = EXCLUDED.display_name,
                email = EXCLUDED.email,
                thumb_url = EXCLUDED.thumb_url,
                is_admin = EXCLUDED.is_admin,
                last_seen_at_ms = COALESCE(EXCLUDED.last_seen_at_ms, users.last_seen_at_ms),
                updated_at_ms = EXCLUDED.updated_at_ms
            """
    }
}
