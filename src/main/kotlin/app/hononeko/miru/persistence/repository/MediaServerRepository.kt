package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.table.MediaServersTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.SQLException

interface MediaServerRepository {
    suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord>

    /**
     * Atomically clears stored [MediaServerRecord.encryptedToken] values on all other rows sharing
     * [server]'s server type while preserving their rows and historical data, then upserts [server].
     */
    suspend fun upsertExclusiveStoredCredentials(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord>

    suspend fun updateCredentials(
        id: String,
        name: String,
        baseUrl: String,
        encryptedToken: String?,
        clientIdentifier: String?,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Either<MiruError.PersistenceError, MediaServerRecord?>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?>

    suspend fun findByMachineId(machineId: String): Either<MiruError.PersistenceError, MediaServerRecord?>

    suspend fun findAll(): Either<MiruError.PersistenceError, List<MediaServerRecord>>

    suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean>
}

class ExposedMediaServerRepository(
    private val database: Database,
) : MediaServerRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord> =
        database.exposedQuery("media_server_upsert") {
            upsertLocked(server)
        }

    override suspend fun upsertExclusiveStoredCredentials(
        server: MediaServerRecord,
    ): Either<MiruError.PersistenceError, MediaServerRecord> =
        database.exposedQuery("media_server_upsert_exclusive_credentials") {
            MediaServersTable.update({
                (MediaServersTable.serverType.lowerCase() eq server.serverType.lowercase()) and
                    (MediaServersTable.machineId neq server.machineId) and
                    MediaServersTable.encryptedToken.isNotNull()
            }) {
                it[encryptedToken] = null
                it[updatedAtMs] = server.updatedAtMs
            }
            upsertLocked(server)
        }

    private fun upsertLocked(server: MediaServerRecord): MediaServerRecord {
        val updatedRows =
            MediaServersTable.update({ MediaServersTable.id eq server.id }) {
                it[name] = server.name
                it[serverType] = server.serverType
                it[machineId] = server.machineId
                it[baseUrl] = server.baseUrl
                if (server.encryptedToken != null) {
                    it[encryptedToken] = server.encryptedToken
                }
                if (server.clientIdentifier != null) {
                    it[clientIdentifier] = server.clientIdentifier
                }
                it[updatedAtMs] = server.updatedAtMs
            }

        return if (updatedRows > 0) {
            MediaServersTable
                .selectAll()
                .where { MediaServersTable.id eq server.id }
                .singleOrNull()
                ?.toMediaServerRecord()
                ?: throw SQLException("Upserted media server '${server.id}' could not be reloaded")
        } else {
            MediaServersTable.upsert(
                MediaServersTable.machineId,
                onUpdate = {
                    it[MediaServersTable.name] = insertValue(MediaServersTable.name)
                    it[MediaServersTable.serverType] = insertValue(MediaServersTable.serverType)
                    it[MediaServersTable.baseUrl] = insertValue(MediaServersTable.baseUrl)
                    it[MediaServersTable.encryptedToken] =
                        coalesce(insertValue(MediaServersTable.encryptedToken), MediaServersTable.encryptedToken)
                    it[MediaServersTable.clientIdentifier] =
                        coalesce(insertValue(MediaServersTable.clientIdentifier), MediaServersTable.clientIdentifier)
                    it[MediaServersTable.updatedAtMs] = insertValue(MediaServersTable.updatedAtMs)
                },
            ) {
                it[id] = server.id
                it[name] = server.name
                it[serverType] = server.serverType
                it[machineId] = server.machineId
                it[baseUrl] = server.baseUrl
                it[encryptedToken] = server.encryptedToken
                it[clientIdentifier] = server.clientIdentifier
                it[createdAtMs] = server.createdAtMs
                it[updatedAtMs] = server.updatedAtMs
            }
            MediaServersTable
                .selectAll()
                .where { MediaServersTable.machineId eq server.machineId }
                .singleOrNull()
                ?.toMediaServerRecord()
                ?: throw SQLException("Upserted media server '${server.machineId}' could not be reloaded")
        }
    }

    override suspend fun updateCredentials(
        id: String,
        name: String,
        baseUrl: String,
        encryptedToken: String?,
        clientIdentifier: String?,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, MediaServerRecord?> =
        database.exposedQuery("media_server_update_credentials") {
            val updatedRows =
                MediaServersTable.update({ MediaServersTable.id eq id }) {
                    it[MediaServersTable.name] = name
                    it[MediaServersTable.baseUrl] = baseUrl
                    it[MediaServersTable.encryptedToken] = encryptedToken
                    it[MediaServersTable.clientIdentifier] = clientIdentifier
                    it[MediaServersTable.updatedAtMs] = updatedAtMs
                }
            if (updatedRows > 0) {
                MediaServersTable
                    .selectAll()
                    .where { MediaServersTable.id eq id }
                    .singleOrNull()
                    ?.toMediaServerRecord()
            } else {
                null
            }
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?> =
        database.exposedQuery("media_server_find_by_id") {
            MediaServersTable
                .selectAll()
                .where { MediaServersTable.id eq id }
                .singleOrNull()
                ?.toMediaServerRecord()
        }

    override suspend fun findByMachineId(machineId: String): Either<MiruError.PersistenceError, MediaServerRecord?> =
        database.exposedQuery("media_server_find_by_machine_id") {
            MediaServersTable
                .selectAll()
                .where { MediaServersTable.machineId eq machineId }
                .singleOrNull()
                ?.toMediaServerRecord()
        }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<MediaServerRecord>> =
        database.exposedQuery("media_server_find_all") {
            MediaServersTable
                .selectAll()
                .orderBy(MediaServersTable.createdAtMs to SortOrder.ASC, MediaServersTable.id to SortOrder.ASC)
                .map { it.toMediaServerRecord() }
        }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("media_server_delete") {
            MediaServersTable.deleteWhere { MediaServersTable.id eq id } > 0
        }
}
