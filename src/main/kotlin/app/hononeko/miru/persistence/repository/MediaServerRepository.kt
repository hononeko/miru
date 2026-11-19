package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.dbQuery
import app.hononeko.miru.persistence.dbTransaction
import app.hononeko.miru.persistence.model.MediaServerRecord
import arrow.core.Either
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

interface MediaServerRepository {
    suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?>

    suspend fun findByMachineId(machineId: String): Either<MiruError.PersistenceError, MediaServerRecord?>

    suspend fun findAll(): Either<MiruError.PersistenceError, List<MediaServerRecord>>

    suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean>
}

class JdbcMediaServerRepository(
    private val dataSource: DataSource,
) : MediaServerRepository {
    constructor(handle: DatabaseHandle) : this(handle.dataSource)

    override suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord> =
        dataSource.dbTransaction("media_server_upsert") { conn ->
            if (existsById(conn, server.id)) {
                conn.prepareStatement(UPDATE_BY_ID_SQL).use { stmt ->
                    stmt.setString(1, server.name)
                    stmt.setString(2, server.serverType)
                    stmt.setString(3, server.machineId)
                    stmt.setString(4, server.baseUrl)
                    stmt.setLong(5, server.updatedAtMs)
                    stmt.setString(6, server.id)
                    stmt.executeUpdate()
                }
                selectById(conn, server.id)
                    ?: throw SQLException("Upserted media server '${server.id}' could not be reloaded")
            } else {
                conn.prepareStatement(UPSERT_BY_MACHINE_ID_SQL).use { stmt ->
                    stmt.setString(1, server.id)
                    stmt.setString(2, server.name)
                    stmt.setString(3, server.serverType)
                    stmt.setString(4, server.machineId)
                    stmt.setString(5, server.baseUrl)
                    stmt.setLong(6, server.createdAtMs)
                    stmt.setLong(7, server.updatedAtMs)
                    stmt.executeUpdate()
                }
                selectByMachineId(conn, server.machineId)
                    ?: throw SQLException("Upserted media server '${server.machineId}' could not be reloaded")
            }
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?> =
        dataSource.dbQuery("media_server_find_by_id") { conn ->
            selectById(conn, id)
        }

    override suspend fun findByMachineId(machineId: String): Either<MiruError.PersistenceError, MediaServerRecord?> =
        dataSource.dbQuery("media_server_find_by_machine_id") { conn ->
            selectByMachineId(conn, machineId)
        }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<MediaServerRecord>> =
        dataSource.dbQuery("media_server_find_all") { conn ->
            conn.prepareStatement(SELECT_ALL_SQL).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toMediaServerRecord())
                        }
                    }
                }
            }
        }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        dataSource.dbQuery("media_server_delete") { conn ->
            conn.prepareStatement(DELETE_BY_ID_SQL).use { stmt ->
                stmt.setString(1, id)
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
    ): MediaServerRecord? =
        conn.prepareStatement(SELECT_BY_ID_SQL).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toMediaServerRecord() else null
            }
        }

    private fun selectByMachineId(
        conn: Connection,
        machineId: String,
    ): MediaServerRecord? =
        conn.prepareStatement(SELECT_BY_MACHINE_ID_SQL).use { stmt ->
            stmt.setString(1, machineId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toMediaServerRecord() else null
            }
        }

    private fun ResultSet.toMediaServerRecord(): MediaServerRecord =
        MediaServerRecord(
            id = getString("id"),
            name = getString("name"),
            serverType = getString("server_type"),
            machineId = getString("machine_id"),
            baseUrl = getString("base_url"),
            createdAtMs = getLong("created_at_ms"),
            updatedAtMs = getLong("updated_at_ms"),
        )

    private companion object {
        const val COLUMNS = "id, name, server_type, machine_id, base_url, created_at_ms, updated_at_ms"
        const val EXISTS_BY_ID_SQL = "SELECT 1 FROM media_servers WHERE id = ?"
        const val SELECT_BY_ID_SQL = "SELECT $COLUMNS FROM media_servers WHERE id = ?"
        const val SELECT_BY_MACHINE_ID_SQL = "SELECT $COLUMNS FROM media_servers WHERE machine_id = ?"
        const val SELECT_ALL_SQL = "SELECT $COLUMNS FROM media_servers ORDER BY created_at_ms ASC, id ASC"
        const val DELETE_BY_ID_SQL = "DELETE FROM media_servers WHERE id = ?"
        const val UPDATE_BY_ID_SQL =
            """
            UPDATE media_servers
            SET name = ?, server_type = ?, machine_id = ?, base_url = ?, updated_at_ms = ?
            WHERE id = ?
            """
        const val UPSERT_BY_MACHINE_ID_SQL =
            """
            INSERT INTO media_servers (id, name, server_type, machine_id, base_url, created_at_ms, updated_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (machine_id) DO UPDATE SET
                name = EXCLUDED.name,
                server_type = EXCLUDED.server_type,
                base_url = EXCLUDED.base_url,
                updated_at_ms = EXCLUDED.updated_at_ms
            """
    }
}
