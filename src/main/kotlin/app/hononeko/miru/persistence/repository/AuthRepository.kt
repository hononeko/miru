package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.table.ApiKeysTable
import app.hononeko.miru.persistence.table.AuthAccountMediaUsersTable
import app.hononeko.miru.persistence.table.AuthAccountsTable
import app.hononeko.miru.persistence.table.AuthSessionsTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.longParam
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

interface AuthAccountRepository {
    suspend fun insert(account: AuthAccountRecord): Either<MiruError.PersistenceError, AuthAccountRecord>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun findByNormalizedUsername(usernameNormalized: String): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun findAll(): Either<MiruError.PersistenceError, List<AuthAccountRecord>>

    suspend fun countAdmins(): Either<MiruError.PersistenceError, Long>

    suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean>

    suspend fun findLinkedMediaUserIds(accountId: String): Either<MiruError.PersistenceError, Set<String>>

    suspend fun replaceLinkedMediaUserIds(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
    ): Either<MiruError.PersistenceError, Unit>
}

interface AuthSessionRepository {
    suspend fun insert(session: AuthSessionRecord): Either<MiruError.PersistenceError, AuthSessionRecord>

    suspend fun findByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, AuthSessionRecord?>

    suspend fun touchLastSeen(
        id: String,
        lastSeenAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean>

    suspend fun deleteByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, Boolean>

    suspend fun deleteByAccountId(accountId: String): Either<MiruError.PersistenceError, Int>

    suspend fun deleteExpired(nowMs: Long): Either<MiruError.PersistenceError, Int>
}

interface ApiKeyRepository {
    suspend fun insert(apiKey: ApiKeyRecord): Either<MiruError.PersistenceError, ApiKeyRecord>

    suspend fun findByKeyHash(keyHash: String): Either<MiruError.PersistenceError, ApiKeyRecord?>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, ApiKeyRecord?>

    suspend fun findAll(accountId: String? = null): Either<MiruError.PersistenceError, List<ApiKeyRecord>>

    suspend fun updateLastUsed(
        id: String,
        lastUsedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean>

    suspend fun revoke(
        id: String,
        revokedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean>

    suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean>
}

class ExposedAuthAccountRepository(
    private val database: Database,
) : AuthAccountRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun insert(account: AuthAccountRecord): Either<MiruError.PersistenceError, AuthAccountRecord> =
        database.exposedQuery("auth_account_insert") {
            AuthAccountsTable.insert {
                it[id] = account.id
                it[username] = account.username
                it[usernameNormalized] = account.usernameNormalized
                it[email] = account.email
                it[passwordHash] = account.passwordHash
                it[role] = account.role
                it[disabled] = account.disabled
                it[createdAtMs] = account.createdAtMs
                it[updatedAtMs] = account.updatedAtMs
            }
            account
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        database.exposedQuery("auth_account_find_by_id") {
            AuthAccountsTable
                .selectAll()
                .where { AuthAccountsTable.id eq id }
                .singleOrNull()
                ?.toAuthAccountRecord()
        }

    override suspend fun findByNormalizedUsername(
        usernameNormalized: String,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        database.exposedQuery("auth_account_find_by_username") {
            AuthAccountsTable
                .selectAll()
                .where { AuthAccountsTable.usernameNormalized eq usernameNormalized }
                .singleOrNull()
                ?.toAuthAccountRecord()
        }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<AuthAccountRecord>> =
        database.exposedQuery("auth_account_find_all") {
            AuthAccountsTable
                .selectAll()
                .orderBy(AuthAccountsTable.createdAtMs to SortOrder.ASC, AuthAccountsTable.id to SortOrder.ASC)
                .map { it.toAuthAccountRecord() }
        }

    override suspend fun countAdmins(): Either<MiruError.PersistenceError, Long> =
        database.exposedQuery("auth_account_count_admins") {
            AuthAccountsTable
                .selectAll()
                .where { AuthAccountsTable.role eq "ADMIN" }
                .count()
        }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("auth_account_delete") {
            AuthAccountsTable.deleteWhere { AuthAccountsTable.id eq id } > 0
        }

    override suspend fun findLinkedMediaUserIds(accountId: String): Either<MiruError.PersistenceError, Set<String>> =
        database.exposedQuery("auth_account_find_linked_media_users") {
            AuthAccountMediaUsersTable
                .selectAll()
                .where { AuthAccountMediaUsersTable.accountId eq accountId }
                .orderBy(AuthAccountMediaUsersTable.mediaUserId to SortOrder.ASC)
                .map { it[AuthAccountMediaUsersTable.mediaUserId] }
                .toSet()
        }

    override suspend fun replaceLinkedMediaUserIds(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
    ): Either<MiruError.PersistenceError, Unit> =
        database.exposedQuery("auth_account_replace_linked_media_users") {
            AuthAccountMediaUsersTable.deleteWhere { AuthAccountMediaUsersTable.accountId eq accountId }
            mediaUserIds.forEach { linkedId ->
                AuthAccountMediaUsersTable.insert {
                    it[AuthAccountMediaUsersTable.accountId] = accountId
                    it[mediaUserId] = linkedId
                    it[AuthAccountMediaUsersTable.createdAtMs] = createdAtMs
                }
            }
        }
}

class ExposedAuthSessionRepository(
    private val database: Database,
) : AuthSessionRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun insert(session: AuthSessionRecord): Either<MiruError.PersistenceError, AuthSessionRecord> =
        database.exposedQuery("auth_session_insert") {
            AuthSessionsTable.insert {
                it[id] = session.id
                it[accountId] = session.accountId
                it[tokenHash] = session.tokenHash
                it[createdAtMs] = session.createdAtMs
                it[expiresAtMs] = session.expiresAtMs
                it[lastSeenAtMs] = session.lastSeenAtMs
            }
            session
        }

    override suspend fun findByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, AuthSessionRecord?> =
        database.exposedQuery("auth_session_find_by_token_hash") {
            AuthSessionsTable
                .selectAll()
                .where { AuthSessionsTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.toAuthSessionRecord()
        }

    override suspend fun touchLastSeen(
        id: String,
        lastSeenAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("auth_session_touch_last_seen") {
            AuthSessionsTable.update({ AuthSessionsTable.id eq id }) {
                it[AuthSessionsTable.lastSeenAtMs] = lastSeenAtMs
            } > 0
        }

    override suspend fun deleteByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("auth_session_delete_by_token_hash") {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.tokenHash eq tokenHash } > 0
        }

    override suspend fun deleteByAccountId(accountId: String): Either<MiruError.PersistenceError, Int> =
        database.exposedQuery("auth_session_delete_by_account_id") {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.accountId eq accountId }
        }

    override suspend fun deleteExpired(nowMs: Long): Either<MiruError.PersistenceError, Int> =
        database.exposedQuery("auth_session_delete_expired") {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.expiresAtMs lessEq nowMs }
        }
}

class ExposedApiKeyRepository(
    private val database: Database,
) : ApiKeyRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun insert(apiKey: ApiKeyRecord): Either<MiruError.PersistenceError, ApiKeyRecord> =
        database.exposedQuery("api_key_insert") {
            ApiKeysTable.insert {
                it[id] = apiKey.id
                it[accountId] = apiKey.accountId
                it[name] = apiKey.name
                it[keyPrefix] = apiKey.keyPrefix
                it[keyHash] = apiKey.keyHash
                it[role] = apiKey.role
                it[lastUsedAtMs] = apiKey.lastUsedAtMs
                it[expiresAtMs] = apiKey.expiresAtMs
                it[revokedAtMs] = apiKey.revokedAtMs
                it[createdAtMs] = apiKey.createdAtMs
            }
            apiKey
        }

    override suspend fun findByKeyHash(keyHash: String): Either<MiruError.PersistenceError, ApiKeyRecord?> =
        database.exposedQuery("api_key_find_by_key_hash") {
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.keyHash eq keyHash }
                .singleOrNull()
                ?.toApiKeyRecord()
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, ApiKeyRecord?> =
        database.exposedQuery("api_key_find_by_id") {
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.id eq id }
                .singleOrNull()
                ?.toApiKeyRecord()
        }

    override suspend fun findAll(accountId: String?): Either<MiruError.PersistenceError, List<ApiKeyRecord>> =
        database.exposedQuery("api_key_find_all") {
            val query =
                if (accountId != null) {
                    ApiKeysTable.selectAll().where { ApiKeysTable.accountId eq accountId }
                } else {
                    ApiKeysTable.selectAll()
                }
            query
                .orderBy(ApiKeysTable.createdAtMs to SortOrder.ASC, ApiKeysTable.id to SortOrder.ASC)
                .map { it.toApiKeyRecord() }
        }

    override suspend fun updateLastUsed(
        id: String,
        lastUsedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("api_key_update_last_used") {
            ApiKeysTable.update({ ApiKeysTable.id eq id }) {
                it[ApiKeysTable.lastUsedAtMs] = longParam(lastUsedAtMs)
            } > 0
        }

    override suspend fun revoke(
        id: String,
        revokedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("api_key_revoke") {
            ApiKeysTable.update({ ApiKeysTable.id eq id }) {
                it[ApiKeysTable.revokedAtMs] = longParam(revokedAtMs)
            } > 0
        }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("api_key_delete") {
            ApiKeysTable.deleteWhere { ApiKeysTable.id eq id } > 0
        }
}

private fun ResultRow.toAuthAccountRecord(): AuthAccountRecord =
    AuthAccountRecord(
        id = this[AuthAccountsTable.id],
        username = this[AuthAccountsTable.username],
        usernameNormalized = this[AuthAccountsTable.usernameNormalized],
        email = this[AuthAccountsTable.email],
        passwordHash = this[AuthAccountsTable.passwordHash],
        role = this[AuthAccountsTable.role],
        disabled = this[AuthAccountsTable.disabled],
        createdAtMs = this[AuthAccountsTable.createdAtMs],
        updatedAtMs = this[AuthAccountsTable.updatedAtMs],
    )

private fun ResultRow.toAuthSessionRecord(): AuthSessionRecord =
    AuthSessionRecord(
        id = this[AuthSessionsTable.id],
        accountId = this[AuthSessionsTable.accountId],
        tokenHash = this[AuthSessionsTable.tokenHash],
        createdAtMs = this[AuthSessionsTable.createdAtMs],
        expiresAtMs = this[AuthSessionsTable.expiresAtMs],
        lastSeenAtMs = this[AuthSessionsTable.lastSeenAtMs],
    )

private fun ResultRow.toApiKeyRecord(): ApiKeyRecord =
    ApiKeyRecord(
        id = this[ApiKeysTable.id],
        accountId = this[ApiKeysTable.accountId],
        name = this[ApiKeysTable.name],
        keyPrefix = this[ApiKeysTable.keyPrefix],
        keyHash = this[ApiKeysTable.keyHash],
        role = this[ApiKeysTable.role],
        lastUsedAtMs = this[ApiKeysTable.lastUsedAtMs],
        expiresAtMs = this[ApiKeysTable.expiresAtMs],
        revokedAtMs = this[ApiKeysTable.revokedAtMs],
        createdAtMs = this[ApiKeysTable.createdAtMs],
    )
