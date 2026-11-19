package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.table.MediaServersTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.SQLException

interface MediaServerRepository {
    suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord>

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
            val updatedRows =
                MediaServersTable.update({ MediaServersTable.id eq server.id }) {
                    it[name] = server.name
                    it[serverType] = server.serverType
                    it[machineId] = server.machineId
                    it[baseUrl] = server.baseUrl
                    it[updatedAtMs] = server.updatedAtMs
                }

            if (updatedRows > 0) {
                MediaServersTable
                    .selectAll()
                    .where { MediaServersTable.id eq server.id }
                    .singleOrNull()
                    ?.toMediaServerRecord()
                    ?: throw SQLException("Upserted media server '${server.id}' could not be reloaded")
            } else {
                MediaServersTable.upsert(
                    MediaServersTable.machineId,
                    onUpdateExclude = listOf(MediaServersTable.id, MediaServersTable.createdAtMs),
                ) {
                    it[id] = server.id
                    it[name] = server.name
                    it[serverType] = server.serverType
                    it[machineId] = server.machineId
                    it[baseUrl] = server.baseUrl
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
