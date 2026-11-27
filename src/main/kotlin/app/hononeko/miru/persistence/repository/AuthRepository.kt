package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.exposedReadQuery
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.model.UserPreferencesRecord
import app.hononeko.miru.persistence.table.ApiKeysTable
import app.hononeko.miru.persistence.table.AuthAccountMediaUsersTable
import app.hononeko.miru.persistence.table.AuthAccountsTable
import app.hononeko.miru.persistence.table.AuthSessionsTable
import app.hononeko.miru.persistence.table.UserPreferencesTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.longParam
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

/** Stored value of `auth_accounts.media_user_link_mode` for accounts linked by email (MediaUserLinkMode.AUTO). */
private const val AUTO_LINK_MODE = "AUTO"

/** Stored value of `auth_accounts.role_mode` for accounts whose role an SSO sign-in sets from their groups. */
private const val AUTO_ROLE_MODE = "AUTO"

interface AuthAccountRepository {
    suspend fun insert(account: AuthAccountRecord): Either<MiruError.PersistenceError, AuthAccountRecord>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun findByNormalizedUsername(usernameNormalized: String): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun findByProviderAndSubject(
        authProvider: String,
        externalSubject: String,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?>

    /**
     * Stores what an SSO sign-in says about the account. [role] is stored only while the account is in AUTO
     * role mode, checked in the same transaction, so the role an admin set is kept.
     */
    suspend fun updateOidcProfile(
        id: String,
        username: String,
        usernameNormalized: String,
        email: String?,
        emailVerified: Boolean?,
        role: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun findAll(): Either<MiruError.PersistenceError, List<AuthAccountRecord>>

    suspend fun count(): Either<MiruError.PersistenceError, Long>

    suspend fun countAdmins(): Either<MiruError.PersistenceError, Long>

    suspend fun countActiveAdmins(): Either<MiruError.PersistenceError, Long>

    suspend fun updatePasswordHash(
        id: String,
        passwordHash: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun updateAccount(
        id: String,
        role: String,
        disabled: Boolean,
        passwordHash: String? = null,
        roleMode: String? = null,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?>

    suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean>

    suspend fun findLinkedMediaUserIds(accountId: String): Either<MiruError.PersistenceError, Set<String>>

    /** Returns a map of `media_user_id -> account_id` across all linked media users. */
    suspend fun findAllMediaUserLinks(): Either<MiruError.PersistenceError, Map<String, String>>

    /** Returns the owning Miru `account_id` for [mediaUserId], or `null` if unlinked. */
    suspend fun findAccountIdByMediaUserId(mediaUserId: String): Either<MiruError.PersistenceError, String?>

    /** Returns `media_user_id -> account_id` for every linked ID in [mediaUserIds]. */
    suspend fun findAccountIdsByMediaUserIds(
        mediaUserIds: Collection<String>,
    ): Either<MiruError.PersistenceError, Map<String, String>>

    /**
     * Replaces the account's linked media users. When [linkMode] is given, the account's
     * media user link mode is set to it in the same transaction.
     */
    suspend fun replaceLinkedMediaUserIds(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
        linkMode: String? = null,
    ): Either<MiruError.PersistenceError, Unit>

    /**
     * Replaces the links of an account that is still in AUTO link mode, checking the mode and writing the
     * links in one transaction. Returns false, and changes nothing, when the account is no longer AUTO.
     */
    suspend fun replaceLinkedMediaUserIdsIfAuto(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean>

    suspend fun updateMediaUserLinkMode(
        id: String,
        linkMode: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?>
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

interface UserPreferencesRepository {
    suspend fun findByAccountId(accountId: String): Either<MiruError.PersistenceError, UserPreferencesRecord?>

    suspend fun upsert(preferences: UserPreferencesRecord): Either<MiruError.PersistenceError, UserPreferencesRecord>
}

class ExposedAuthAccountRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : AuthAccountRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

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
                it[authProvider] = account.authProvider
                it[externalSubject] = account.externalSubject
                it[mediaUserLinkMode] = account.mediaUserLinkMode
                it[roleMode] = account.roleMode
                it[emailVerified] = account.emailVerified
                it[createdAtMs] = account.createdAtMs
                it[updatedAtMs] = account.updatedAtMs
            }
            account
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        readDatabase.exposedReadQuery("auth_account_find_by_id") {
            AuthAccountsTable
                .selectAll()
                .where { AuthAccountsTable.id eq id }
                .singleOrNull()
                ?.toAuthAccountRecord()
        }

    override suspend fun findByNormalizedUsername(
        usernameNormalized: String,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        readDatabase.exposedReadQuery("auth_account_find_by_username") {
            AuthAccountsTable
                .selectAll()
                .where { AuthAccountsTable.usernameNormalized eq usernameNormalized }
                .singleOrNull()
                ?.toAuthAccountRecord()
        }

    override suspend fun findByProviderAndSubject(
        authProvider: String,
        externalSubject: String,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        readDatabase.exposedReadQuery("auth_account_find_by_provider_subject") {
            AuthAccountsTable
                .selectAll()
                .where {
                    (AuthAccountsTable.authProvider eq authProvider) and
                        (AuthAccountsTable.externalSubject eq externalSubject)
                }.singleOrNull()
                ?.toAuthAccountRecord()
        }

    override suspend fun updateOidcProfile(
        id: String,
        username: String,
        usernameNormalized: String,
        email: String?,
        emailVerified: Boolean?,
        role: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        database.exposedQuery("auth_account_update_oidc_profile") {
            val updated =
                AuthAccountsTable.update({ AuthAccountsTable.id eq id }) {
                    it[AuthAccountsTable.username] = username
                    it[AuthAccountsTable.usernameNormalized] = usernameNormalized
                    it[AuthAccountsTable.email] = email
                    it[AuthAccountsTable.emailVerified] = emailVerified
                    it[AuthAccountsTable.updatedAtMs] = updatedAtMs
                }
            if (updated <= 0) {
                null
            } else {
                // The update above holds the account row, so this sees a role an admin set meanwhile.
                AuthAccountsTable.update({
                    (AuthAccountsTable.id eq id) and (AuthAccountsTable.roleMode eq AUTO_ROLE_MODE)
                }) {
                    it[AuthAccountsTable.role] = role
                }
                findAccountByIdInTx(id)
            }
        }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<AuthAccountRecord>> =
        readDatabase.exposedReadQuery("auth_account_find_all") {
            AuthAccountsTable
                .selectAll()
                .orderBy(AuthAccountsTable.createdAtMs to SortOrder.ASC, AuthAccountsTable.id to SortOrder.ASC)
                .map { it.toAuthAccountRecord() }
        }

    override suspend fun count(): Either<MiruError.PersistenceError, Long> =
        readDatabase.exposedReadQuery("auth_account_count") {
            AuthAccountsTable.selectAll().count()
        }

    override suspend fun countAdmins(): Either<MiruError.PersistenceError, Long> =
        readDatabase.exposedReadQuery("auth_account_count_admins") {
            AuthAccountsTable
                .selectAll()
                .where { AuthAccountsTable.role eq "ADMIN" }
                .count()
        }

    override suspend fun countActiveAdmins(): Either<MiruError.PersistenceError, Long> =
        readDatabase.exposedReadQuery("auth_account_count_active_admins") {
            AuthAccountsTable
                .selectAll()
                .where { (AuthAccountsTable.role eq "ADMIN") and (AuthAccountsTable.disabled eq false) }
                .count()
        }

    override suspend fun updatePasswordHash(
        id: String,
        passwordHash: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        database.exposedQuery("auth_account_update_password_hash") {
            val updated =
                AuthAccountsTable.update({ AuthAccountsTable.id eq id }) {
                    it[AuthAccountsTable.passwordHash] = passwordHash
                    it[AuthAccountsTable.updatedAtMs] = updatedAtMs
                }
            if (updated <= 0) null else findAccountByIdInTx(id)
        }

    override suspend fun updateAccount(
        id: String,
        role: String,
        disabled: Boolean,
        passwordHash: String?,
        roleMode: String?,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        database.exposedQuery("auth_account_update") {
            val updated =
                AuthAccountsTable.update({ AuthAccountsTable.id eq id }) {
                    it[AuthAccountsTable.role] = role
                    it[AuthAccountsTable.disabled] = disabled
                    if (passwordHash != null) {
                        it[AuthAccountsTable.passwordHash] = passwordHash
                    }
                    if (roleMode != null) {
                        it[AuthAccountsTable.roleMode] = roleMode
                    }
                    it[AuthAccountsTable.updatedAtMs] = updatedAtMs
                }
            if (updated <= 0) null else findAccountByIdInTx(id)
        }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("auth_account_delete") {
            AuthAccountsTable.deleteWhere { AuthAccountsTable.id eq id } > 0
        }

    override suspend fun findLinkedMediaUserIds(accountId: String): Either<MiruError.PersistenceError, Set<String>> =
        readDatabase.exposedReadQuery("auth_account_find_linked_media_users") {
            AuthAccountMediaUsersTable
                .selectAll()
                .where { AuthAccountMediaUsersTable.accountId eq accountId }
                .orderBy(AuthAccountMediaUsersTable.mediaUserId to SortOrder.ASC)
                .map { it[AuthAccountMediaUsersTable.mediaUserId] }
                .toSet()
        }

    override suspend fun findAllMediaUserLinks(): Either<MiruError.PersistenceError, Map<String, String>> =
        readDatabase.exposedReadQuery("auth_account_find_all_media_user_links") {
            AuthAccountMediaUsersTable
                .select(AuthAccountMediaUsersTable.mediaUserId, AuthAccountMediaUsersTable.accountId)
                .orderBy(AuthAccountMediaUsersTable.mediaUserId to SortOrder.ASC)
                .associate { row ->
                    row[AuthAccountMediaUsersTable.mediaUserId] to row[AuthAccountMediaUsersTable.accountId]
                }
        }

    override suspend fun findAccountIdByMediaUserId(mediaUserId: String): Either<MiruError.PersistenceError, String?> =
        readDatabase.exposedReadQuery("auth_account_find_account_by_media_user") {
            AuthAccountMediaUsersTable
                .select(AuthAccountMediaUsersTable.accountId)
                .where { AuthAccountMediaUsersTable.mediaUserId eq mediaUserId }
                .singleOrNull()
                ?.get(AuthAccountMediaUsersTable.accountId)
        }

    override suspend fun findAccountIdsByMediaUserIds(
        mediaUserIds: Collection<String>,
    ): Either<MiruError.PersistenceError, Map<String, String>> {
        val uniqueIds = mediaUserIds.distinct()
        if (uniqueIds.isEmpty()) return Either.Right(emptyMap())
        return readDatabase.exposedReadQuery("auth_account_find_accounts_by_media_users") {
            AuthAccountMediaUsersTable
                .select(AuthAccountMediaUsersTable.mediaUserId, AuthAccountMediaUsersTable.accountId)
                .where { AuthAccountMediaUsersTable.mediaUserId inList uniqueIds }
                .associate { row ->
                    row[AuthAccountMediaUsersTable.mediaUserId] to row[AuthAccountMediaUsersTable.accountId]
                }
        }
    }

    override suspend fun replaceLinkedMediaUserIds(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
        linkMode: String?,
    ): Either<MiruError.PersistenceError, Unit> =
        database.exposedQuery("auth_account_replace_linked_media_users") {
            // Update the account row before the links, in the same order as replaceLinkedMediaUserIdsIfAuto,
            // so concurrent writers wait on the account row lock instead of deadlocking.
            if (linkMode != null) {
                AuthAccountsTable.update({ AuthAccountsTable.id eq accountId }) {
                    it[AuthAccountsTable.mediaUserLinkMode] = linkMode
                    it[AuthAccountsTable.updatedAtMs] = createdAtMs
                }
            }
            writeLinkedMediaUserIds(accountId, mediaUserIds, createdAtMs)
        }

    override suspend fun replaceLinkedMediaUserIdsIfAuto(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("auth_account_replace_auto_linked_media_users") {
            // A no-op update that matches only while the account is AUTO. It locks the account row, so an admin
            // switching it to MANUAL either waits for this write or turns it into a no-op.
            val stillAuto =
                AuthAccountsTable.update({
                    (AuthAccountsTable.id eq accountId) and (AuthAccountsTable.mediaUserLinkMode eq AUTO_LINK_MODE)
                }) {
                    it[AuthAccountsTable.mediaUserLinkMode] = AUTO_LINK_MODE
                } > 0
            if (stillAuto) {
                writeLinkedMediaUserIds(accountId, mediaUserIds, createdAtMs)
            }
            stillAuto
        }

    private fun writeLinkedMediaUserIds(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
    ) {
        AuthAccountMediaUsersTable.deleteWhere { AuthAccountMediaUsersTable.accountId eq accountId }
        mediaUserIds.forEach { linkedId ->
            AuthAccountMediaUsersTable.insert {
                it[AuthAccountMediaUsersTable.accountId] = accountId
                it[mediaUserId] = linkedId
                it[AuthAccountMediaUsersTable.createdAtMs] = createdAtMs
            }
        }
    }

    override suspend fun updateMediaUserLinkMode(
        id: String,
        linkMode: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        database.exposedQuery("auth_account_update_media_user_link_mode") {
            val updated =
                AuthAccountsTable.update({ AuthAccountsTable.id eq id }) {
                    it[AuthAccountsTable.mediaUserLinkMode] = linkMode
                    it[AuthAccountsTable.updatedAtMs] = updatedAtMs
                }
            if (updated <= 0) {
                null
            } else {
                findAccountByIdInTx(id)
            }
        }

    private fun findAccountByIdInTx(id: String): AuthAccountRecord? =
        AuthAccountsTable
            .selectAll()
            .where { AuthAccountsTable.id eq id }
            .singleOrNull()
            ?.toAuthAccountRecord()
}

class ExposedAuthSessionRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : AuthSessionRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

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
        readDatabase.exposedReadQuery("auth_session_find_by_token_hash") {
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
    private val readDatabase: Database = database,
) : ApiKeyRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

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
        readDatabase.exposedReadQuery("api_key_find_by_key_hash") {
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.keyHash eq keyHash }
                .singleOrNull()
                ?.toApiKeyRecord()
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, ApiKeyRecord?> =
        readDatabase.exposedReadQuery("api_key_find_by_id") {
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.id eq id }
                .singleOrNull()
                ?.toApiKeyRecord()
        }

    override suspend fun findAll(accountId: String?): Either<MiruError.PersistenceError, List<ApiKeyRecord>> =
        readDatabase.exposedReadQuery("api_key_find_all") {
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

class ExposedUserPreferencesRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : UserPreferencesRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

    override suspend fun findByAccountId(
        accountId: String,
    ): Either<MiruError.PersistenceError, UserPreferencesRecord?> =
        readDatabase.exposedReadQuery("user_preferences_find_by_account_id") {
            UserPreferencesTable
                .selectAll()
                .where { UserPreferencesTable.accountId eq accountId }
                .singleOrNull()
                ?.toUserPreferencesRecord()
        }

    override suspend fun upsert(
        preferences: UserPreferencesRecord,
    ): Either<MiruError.PersistenceError, UserPreferencesRecord> =
        database.exposedQuery("user_preferences_upsert") {
            UserPreferencesTable.upsert(UserPreferencesTable.accountId) {
                it[accountId] = preferences.accountId
                it[theme] = preferences.theme
                it[timeFormat] = preferences.timeFormat
                it[dateFormat] = preferences.dateFormat
                it[defaultStatsRange] = preferences.defaultStatsRange
                it[relativeTimestamps] = preferences.relativeTimestamps
                it[analyticsMetric] = preferences.analyticsMetric
                it[updatedAtMs] = preferences.updatedAtMs
            }
            preferences
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
        authProvider = this[AuthAccountsTable.authProvider],
        externalSubject = this[AuthAccountsTable.externalSubject],
        mediaUserLinkMode = this[AuthAccountsTable.mediaUserLinkMode],
        roleMode = this[AuthAccountsTable.roleMode],
        emailVerified = this[AuthAccountsTable.emailVerified],
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

private fun ResultRow.toUserPreferencesRecord(): UserPreferencesRecord =
    UserPreferencesRecord(
        accountId = this[UserPreferencesTable.accountId],
        theme = this[UserPreferencesTable.theme],
        timeFormat = this[UserPreferencesTable.timeFormat],
        dateFormat = this[UserPreferencesTable.dateFormat],
        defaultStatsRange = this[UserPreferencesTable.defaultStatsRange],
        relativeTimestamps = this[UserPreferencesTable.relativeTimestamps],
        analyticsMetric = this[UserPreferencesTable.analyticsMetric],
        updatedAtMs = this[UserPreferencesTable.updatedAtMs],
    )
