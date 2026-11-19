package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.table.UsersTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.longParam
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.SQLException

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

class ExposedUserRepository(
    private val database: Database,
) : UserRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun upsert(user: UserRecord): Either<MiruError.PersistenceError, UserRecord> =
        database.exposedQuery("user_upsert") {
            val updatedRows =
                UsersTable.update({ UsersTable.id eq user.id }) {
                    it[serverId] = user.serverId
                    it[externalUserId] = user.externalUserId
                    it[username] = user.username
                    it[displayName] = user.displayName
                    it[email] = user.email
                    it[thumbUrl] = user.thumbUrl
                    it[isAdmin] = user.isAdmin
                    if (user.lastSeenAtMs != null) {
                        it[lastSeenAtMs] = user.lastSeenAtMs
                    }
                    it[updatedAtMs] = user.updatedAtMs
                }

            if (updatedRows > 0) {
                UsersTable
                    .selectAll()
                    .where { UsersTable.id eq user.id }
                    .singleOrNull()
                    ?.toUserRecord()
                    ?: throw SQLException("Upserted user '${user.id}' could not be reloaded")
            } else {
                UsersTable.upsert(
                    UsersTable.serverId,
                    UsersTable.externalUserId,
                    onUpdate = {
                        it[UsersTable.username] = insertValue(UsersTable.username)
                        it[UsersTable.displayName] = insertValue(UsersTable.displayName)
                        it[UsersTable.email] = insertValue(UsersTable.email)
                        it[UsersTable.thumbUrl] = insertValue(UsersTable.thumbUrl)
                        it[UsersTable.isAdmin] = insertValue(UsersTable.isAdmin)
                        it[UsersTable.lastSeenAtMs] = coalesce(insertValue(UsersTable.lastSeenAtMs), UsersTable.lastSeenAtMs)
                        it[UsersTable.updatedAtMs] = insertValue(UsersTable.updatedAtMs)
                    },
                ) {
                    it[id] = user.id
                    it[serverId] = user.serverId
                    it[externalUserId] = user.externalUserId
                    it[username] = user.username
                    it[displayName] = user.displayName
                    it[email] = user.email
                    it[thumbUrl] = user.thumbUrl
                    it[isAdmin] = user.isAdmin
                    it[lastSeenAtMs] = user.lastSeenAtMs
                    it[createdAtMs] = user.createdAtMs
                    it[updatedAtMs] = user.updatedAtMs
                }
                UsersTable
                    .selectAll()
                    .where { (UsersTable.serverId eq user.serverId) and (UsersTable.externalUserId eq user.externalUserId) }
                    .singleOrNull()
                    ?.toUserRecord()
                    ?: throw SQLException("Upserted user '${user.serverId}/${user.externalUserId}' could not be reloaded")
            }
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, UserRecord?> =
        database.exposedQuery("user_find_by_id") {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id }
                .singleOrNull()
                ?.toUserRecord()
        }

    override suspend fun findByExternalId(
        serverId: String,
        externalUserId: String,
    ): Either<MiruError.PersistenceError, UserRecord?> =
        database.exposedQuery("user_find_by_external_id") {
            UsersTable
                .selectAll()
                .where { (UsersTable.serverId eq serverId) and (UsersTable.externalUserId eq externalUserId) }
                .singleOrNull()
                ?.toUserRecord()
        }

    override suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<UserRecord>> =
        database.exposedQuery("user_find_by_server") {
            UsersTable
                .selectAll()
                .where { UsersTable.serverId eq serverId }
                .orderBy(UsersTable.username to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
                .map { it.toUserRecord() }
        }

    override suspend fun updateLastSeen(
        id: String,
        lastSeenAtMs: Long,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("user_update_last_seen") {
            UsersTable.update({ UsersTable.id eq id }) {
                it[UsersTable.lastSeenAtMs] = longParam(lastSeenAtMs)
                it[UsersTable.updatedAtMs] = updatedAtMs
            } > 0
        }
}
