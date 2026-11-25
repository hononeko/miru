package app.hononeko.miru.auth

import app.hononeko.miru.auth.oidc.OidcAuthService.Companion.PROVIDER_OIDC
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.UpdateUserPreferencesRequest
import app.hononeko.miru.http.UserPreferencesResponse
import app.hononeko.miru.http.toResponse
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.model.UserPreferencesRecord
import app.hononeko.miru.persistence.repository.ApiKeyRepository
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import app.hononeko.miru.persistence.repository.UserPreferencesRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

data class LoginResult(
    val principal: MiruPrincipal,
    val signedToken: String,
    val expiresAtMs: Long,
) {
    override fun toString(): String =
        "LoginResult(principal=$principal, signedToken=***, expiresAtMs=$expiresAtMs)"
}

data class AccountWithLinks(
    val account: AuthAccountRecord,
    val linkedMediaUserIds: Set<String>,
)

data class CreatedApiKey(
    val record: ApiKeyRecord,
    val rawKey: String,
) {
    override fun toString(): String =
        "CreatedApiKey(record=$record, rawKey=***)"
}

data class AuthProvidersInfo(
    val authMode: String,
    val localEnabled: Boolean,
    val oidcEnabled: Boolean,
    val oidcDisplayName: String,
    val oidcLoginUrl: String?,
    val oidcLogoutUrl: String?,
    val setupRequired: Boolean,
)

@Suppress("LongParameterList")
class AuthService(
    private val accountRepository: AuthAccountRepository,
    private val sessionRepository: AuthSessionRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val authConfig: AuthConfig,
    private val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val preferencesRepository: UserPreferencesRepository? = null,
    private val emailLinker: EmailMediaUserLinker? = null,
    private val maxConcurrentPasswordHashes: Int = DEFAULT_MAX_CONCURRENT_PASSWORD_HASHES,
) {
    init {
        require(maxConcurrentPasswordHashes >= 1) { "maxConcurrentPasswordHashes must be >= 1" }
    }

    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val setupMutex = Mutex()
    private val passwordHashSemaphore = Semaphore(maxConcurrentPasswordHashes)
    private val dummyPasswordHash: String by lazy {
        passwordHasher.hash("miru-constant-time-dummy-password")
    }

    suspend fun bootstrapAdminIfConfigured(): Either<MiruError, Boolean> =
        either {
            val adminUsername = authConfig.adminUsername
            val adminPassword = authConfig.adminPassword
            if (!authConfig.localLoginEnabled || adminUsername.isNullOrBlank() || adminPassword.isNullOrBlank()) {
                return@either false
            }

            setupMutex.withLock {
                val existingAdmins = accountRepository.countAdmins().bind()
                if (existingAdmins > 0L) {
                    return@either false
                }

                val validated = validateCredentials(adminUsername, adminPassword, email = null).bind()
                val existingByUsername = accountRepository.findByNormalizedUsername(validated.usernameNormalized).bind()
                ensure(existingByUsername == null) {
                    MiruError.Conflict("Username '${validated.username}' already exists")
                }

                val now = clock()
                val record =
                    AuthAccountRecord(
                        id = idGenerator(),
                        username = validated.username,
                        usernameNormalized = validated.usernameNormalized,
                        email = null,
                        passwordHash = hashPassword(adminPassword),
                        role = MiruRole.ADMIN.name,
                        disabled = false,
                        createdAtMs = now,
                        updatedAtMs = now,
                    )
                accountRepository.insert(record).bind()
                logger.info("Bootstrapped initial local admin account '{}'", validated.username)
                true
            }
        }

    suspend fun isSetupRequired(): Either<MiruError, Boolean> =
        either {
            authConfig.localLoginEnabled && accountRepository.countAdmins().bind() == 0L
        }

    suspend fun getAuthProviders(): Either<MiruError, AuthProvidersInfo> =
        either {
            val setupRequired = isSetupRequired().bind()
            val oidcEnabled = authConfig.oidcEnabled
            AuthProvidersInfo(
                authMode = authConfig.mode.name.lowercase(),
                localEnabled = authConfig.localLoginEnabled,
                oidcEnabled = oidcEnabled,
                oidcDisplayName = authConfig.oidc.displayName,
                oidcLoginUrl = if (oidcEnabled) "/api/v1/auth/oidc/login" else null,
                oidcLogoutUrl = if (oidcEnabled) "/api/v1/auth/oidc/logout" else null,
                setupRequired = setupRequired,
            )
        }

    suspend fun setupInitialAdmin(
        username: String,
        password: String,
        email: String? = null,
    ): Either<MiruError, LoginResult> =
        either {
            ensure(authConfig.localLoginEnabled) {
                MiruError.Conflict("Local authentication is disabled")
            }

            val created =
                setupMutex.withLock {
                    val existingAdmins = accountRepository.countAdmins().bind()
                    ensure(existingAdmins == 0L) {
                        MiruError.Conflict("Initial admin account has already been configured")
                    }

                    val validated = validateCredentials(username, password, email).bind()
                    val existingByUsername = accountRepository.findByNormalizedUsername(validated.usernameNormalized).bind()
                    ensure(existingByUsername == null) {
                        MiruError.Conflict("Username '${validated.username}' already exists")
                    }

                    val now = clock()
                    val account =
                        AuthAccountRecord(
                            id = idGenerator(),
                            username = validated.username,
                            usernameNormalized = validated.usernameNormalized,
                            email = validated.email,
                            passwordHash = hashPassword(password),
                            role = MiruRole.ADMIN.name,
                            disabled = false,
                            createdAtMs = now,
                            updatedAtMs = now,
                        )
                    accountRepository.insert(account).bind()
                }

            logger.info("Created initial admin account '{}' via first-run setup", created.username)
            issueSession(created, linkedMediaUserIds = emptySet()).bind()
        }

    suspend fun login(
        username: String,
        password: String,
    ): Either<MiruError, LoginResult> =
        either {
            ensure(authConfig.localLoginEnabled) {
                MiruError.Conflict("Local authentication is disabled")
            }

            val normalized = username.trim().lowercase()
            val account = accountRepository.findByNormalizedUsername(normalized).bind()

            if (account == null || !account.authProvider.equals("LOCAL", ignoreCase = true)) {
                verifyPassword(password, dummyPasswordHash)
                raise(MiruError.Unauthorized("Invalid username or password"))
            }

            val passwordMatches = verifyPassword(password, account.passwordHash)
            ensure(passwordMatches) {
                MiruError.Unauthorized("Invalid username or password")
            }
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            val linkedMediaUserIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
            issueSession(account, linkedMediaUserIds).bind()
        }

    suspend fun issueSessionForAccount(
        account: AuthAccountRecord,
        linkedMediaUserIds: Set<String>,
    ): Either<MiruError, LoginResult> =
        either {
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }
            issueSession(account, linkedMediaUserIds).bind()
        }

    suspend fun logout(signedToken: String?): Either<MiruError, Unit> =
        either {
            if (signedToken.isNullOrBlank()) {
                return@either
            }
            val rawToken =
                TokenCrypto.verifySignedSessionToken(signedToken, authConfig.sessionSecret)
                    ?: return@either
            val tokenHash = TokenCrypto.sha256Hex(rawToken)
            sessionRepository.deleteByTokenHash(tokenHash).bind()
        }

    suspend fun authenticateCookie(signedToken: String): Either<MiruError, MiruPrincipal> =
        either {
            val rawToken =
                TokenCrypto.verifySignedSessionToken(signedToken, authConfig.sessionSecret)
                    ?: raise(MiruError.Unauthorized("Invalid session token"))

            val tokenHash = TokenCrypto.sha256Hex(rawToken)
            val session =
                sessionRepository.findByTokenHash(tokenHash).bind()
                    ?: raise(MiruError.Unauthorized("Session not found"))

            val now = clock()
            if (session.expiresAtMs <= now) {
                sessionRepository.deleteByTokenHash(tokenHash).bind()
                raise(MiruError.Unauthorized("Session expired"))
            }

            val account =
                accountRepository.findById(session.accountId).bind()
                    ?: raise(MiruError.Unauthorized("Account not found"))
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            sessionRepository.touchLastSeen(session.id, now).bind()
            val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
            account.toPrincipal(linkedIds, AuthSource.SESSION_COOKIE)
        }

    suspend fun authenticateApiKey(rawKey: String): Either<MiruError, MiruPrincipal> =
        either {
            val trimmed = rawKey.trim()
            ensure(trimmed.isNotEmpty()) {
                MiruError.Unauthorized("Missing API key")
            }

            val keyHash = TokenCrypto.sha256Hex(trimmed)
            val record =
                apiKeyRepository.findByKeyHash(keyHash).bind()
                    ?: raise(MiruError.Unauthorized("Invalid API key"))

            val now = clock()
            ensure(record.revokedAtMs == null) {
                MiruError.Unauthorized("API key has been revoked")
            }
            ensure(record.expiresAtMs == null || record.expiresAtMs > now) {
                MiruError.Unauthorized("API key has expired")
            }

            val account =
                accountRepository.findById(record.accountId).bind()
                    ?: raise(MiruError.Unauthorized("Account not found"))
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            apiKeyRepository.updateLastUsed(record.id, now).bind()
            val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
            val accountRole = parseRole(account.role)
            val keyRole = parseRole(record.role)
            val effectiveRole =
                if (accountRole == MiruRole.USER) {
                    MiruRole.USER
                } else {
                    keyRole
                }

            MiruPrincipal(
                accountId = account.id,
                username = account.username,
                email = account.email,
                role = effectiveRole,
                linkedMediaUserIds = linkedIds,
                authSource = AuthSource.API_KEY,
                authProvider = account.authProvider,
            )
        }

    suspend fun resolveAvatarUrl(principal: MiruPrincipal): Either<MiruError, String?> =
        either {
            if (principal.linkedMediaUserIds.isEmpty()) {
                return@either null
            }
            for (mediaUserId in principal.linkedMediaUserIds.sorted()) {
                val user = userRepository.findById(mediaUserId).bind()
                if (!user?.thumbUrl.isNullOrBlank()) {
                    return@either "/api/v1/users/${user.id}/avatar"
                }
            }
            null
        }

    /** Whether SSO sign-in links AUTO accounts to media users by email (OIDC enabled and OIDC_AUTO_LINK_BY_EMAIL on). */
    val emailLinkingEnabled: Boolean
        get() = emailLinker?.enabled ?: (authConfig.oidcEnabled && authConfig.oidc.autoLinkByEmail)

    suspend fun listAccounts(): Either<MiruError, List<AccountWithLinks>> =
        either {
            val accounts = accountRepository.findAll().bind()
            accounts.map { account ->
                val links = accountRepository.findLinkedMediaUserIds(account.id).bind()
                AccountWithLinks(account = account, linkedMediaUserIds = links)
            }
        }

    suspend fun createAccount(
        username: String,
        password: String,
        email: String? = null,
        role: MiruRole = MiruRole.USER,
        linkedMediaUserIds: Set<String> = emptySet(),
    ): Either<MiruError, AccountWithLinks> =
        either {
            val validated = validateCredentials(username, password, email).bind()
            val normalizedLinks = linkedMediaUserIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            validateMediaUserIdsExist(normalizedLinks).bind()

            val existing = accountRepository.findByNormalizedUsername(validated.usernameNormalized).bind()
            ensure(existing == null) {
                MiruError.Conflict("Username '${validated.username}' already exists")
            }

            val now = clock()
            val account =
                AuthAccountRecord(
                    id = idGenerator(),
                    username = validated.username,
                    usernameNormalized = validated.usernameNormalized,
                    email = validated.email,
                    passwordHash = hashPassword(password),
                    role = role.name,
                    disabled = false,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            accountRepository.insert(account).bind()
            if (normalizedLinks.isNotEmpty()) {
                accountRepository.replaceLinkedMediaUserIds(account.id, normalizedLinks, now).bind()
            }
            AccountWithLinks(account = account, linkedMediaUserIds = normalizedLinks)
        }

    suspend fun changeOwnPassword(
        principal: MiruPrincipal,
        currentPassword: String,
        newPassword: String,
    ): Either<MiruError, LoginResult?> =
        either {
            ensure(authConfig.mode != AuthMode.NONE && principal.authSource != AuthSource.DEV_BYPASS) {
                MiruError.Conflict("Password management is disabled when authentication is disabled")
            }

            val errors = linkedMapOf<String, String>()
            if (currentPassword.isEmpty()) {
                errors["currentPassword"] = "must not be blank"
            }
            PasswordPolicy.validationError(newPassword)?.let { message ->
                errors["newPassword"] = message
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError("Invalid password change request", fieldErrors = errors)
            }

            val account =
                accountRepository.findById(principal.accountId).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = principal.accountId))
            ensure(account.authProvider.equals("LOCAL", ignoreCase = true)) {
                MiruError.Conflict("Password change is only supported for local accounts")
            }
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            val matches = verifyPassword(currentPassword, account.passwordHash)
            ensure(matches) {
                MiruError.Unauthorized("Current password is incorrect")
            }

            val newHash = hashPassword(newPassword)
            val updated =
                accountRepository
                    .updatePasswordHash(account.id, newHash, clock())
                    .bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = account.id))

            sessionRepository.deleteByAccountId(account.id).bind()
            logger.info("Password changed for local account '{}'", updated.username)

            if (principal.authSource == AuthSource.SESSION_COOKIE) {
                val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
                issueSession(updated, linkedIds).bind()
            } else {
                null
            }
        }

    suspend fun updateAccount(
        id: String,
        actingAccountId: String,
        role: String? = null,
        disabled: Boolean? = null,
        password: String? = null,
    ): Either<MiruError, AccountWithLinks> =
        either {
            ensure(role != null || disabled != null || password != null) {
                MiruError.ValidationError("At least one field (role, disabled, password) must be provided")
            }

            val errors = linkedMapOf<String, String>()
            val parsedRole =
                role?.let { raw ->
                    parseExplicitRole(raw).also { parsed ->
                        if (parsed == null) {
                            errors["role"] = "must be 'ADMIN' or 'USER'"
                        }
                    }
                }
            if (password != null) {
                PasswordPolicy.validationError(password)?.let { message ->
                    errors["password"] = message
                }
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError("Invalid account update request", fieldErrors = errors)
            }

            val target =
                accountRepository.findById(id).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = id))

            if (password != null) {
                ensure(target.authProvider.equals("LOCAL", ignoreCase = true)) {
                    MiruError.Conflict("Password reset is only supported for local accounts")
                }
            }

            val currentRole = parseRole(target.role)
            val nextRole = parsedRole ?: currentRole
            val nextDisabled = disabled ?: target.disabled

            if (id == actingAccountId) {
                ensure(nextRole == MiruRole.ADMIN) {
                    MiruError.Conflict("Cannot demote your own account")
                }
                ensure(!nextDisabled) {
                    MiruError.Conflict("Cannot disable your own account")
                }
            }

            val isTargetActiveAdmin = currentRole == MiruRole.ADMIN && !target.disabled
            val losingActiveAdmin = isTargetActiveAdmin && (nextRole != MiruRole.ADMIN || nextDisabled)
            if (losingActiveAdmin) {
                val activeAdmins = accountRepository.countActiveAdmins().bind()
                ensure(activeAdmins > 1L) {
                    MiruError.Conflict("Cannot demote or disable the last active admin account")
                }
            }

            val newPasswordHash = password?.let { hashPassword(it) }
            val updated =
                accountRepository
                    .updateAccount(
                        id = id,
                        role = nextRole.name,
                        disabled = nextDisabled,
                        passwordHash = newPasswordHash,
                        updatedAtMs = clock(),
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = id))

            val roleDowngraded = currentRole == MiruRole.ADMIN && nextRole != MiruRole.ADMIN
            if (nextDisabled || roleDowngraded || newPasswordHash != null) {
                sessionRepository.deleteByAccountId(id).bind()
            }

            val links = accountRepository.findLinkedMediaUserIds(id).bind()
            AccountWithLinks(account = updated, linkedMediaUserIds = links)
        }

    suspend fun deleteAccount(
        id: String,
        actingAccountId: String,
    ): Either<MiruError, Unit> =
        either {
            ensure(id != actingAccountId) {
                MiruError.Conflict("Cannot delete your own account")
            }
            val target =
                accountRepository.findById(id).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = id))
            if (parseRole(target.role) == MiruRole.ADMIN && !target.disabled) {
                val activeAdminCount = accountRepository.countActiveAdmins().bind()
                ensure(activeAdminCount > 1L) {
                    MiruError.Conflict("Cannot delete the last remaining active admin account")
                }
            }
            val deleted = accountRepository.delete(id).bind()
            ensure(deleted) {
                MiruError.NotFound(resource = "Account", id = id)
            }
        }

    /**
     * Replaces the account's linked media users with an admin's choice and switches the account to
     * [MediaUserLinkMode.MANUAL], so SSO sign-in never relinks it by email afterwards.
     */
    suspend fun setLinkedMediaUsers(
        accountId: String,
        mediaUserIds: Set<String>,
    ): Either<MiruError, AccountWithLinks> =
        either {
            val account =
                accountRepository.findById(accountId).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = accountId))
            val normalizedLinks = mediaUserIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            validateMediaUserIdsExist(normalizedLinks).bind()
            val now = clock()
            val manual = MediaUserLinkMode.MANUAL.name
            accountRepository.replaceLinkedMediaUserIds(accountId, normalizedLinks, now, linkMode = manual).bind()
            AccountWithLinks(
                account = account.copy(mediaUserLinkMode = manual, updatedAtMs = now),
                linkedMediaUserIds = normalizedLinks,
            )
        }

    /**
     * Sets how the account's linked media users are maintained. [MediaUserLinkMode.AUTO] applies only to SSO
     * accounts and relinks the account right away from the email its last sign-in recorded; an account with no
     * recorded verification keeps its links until its next sign-in.
     */
    suspend fun setMediaUserLinkMode(
        accountId: String,
        mode: MediaUserLinkMode,
    ): Either<MiruError, AccountWithLinks> =
        either {
            val account =
                accountRepository.findById(accountId).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = accountId))
            ensure(mode == MediaUserLinkMode.MANUAL || account.authProvider.equals(PROVIDER_OIDC, ignoreCase = true)) {
                MiruError.Conflict("Automatic media user linking by email is only available for SSO accounts")
            }
            val updated =
                accountRepository.updateMediaUserLinkMode(accountId, mode.name, clock()).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = accountId))
            val links =
                if (mode == MediaUserLinkMode.AUTO && emailLinker != null) {
                    emailLinker.relink(updated).bind()
                } else {
                    accountRepository.findLinkedMediaUserIds(accountId).bind()
                }
            AccountWithLinks(account = updated, linkedMediaUserIds = links)
        }

    suspend fun listApiKeys(accountId: String? = null): Either<MiruError, List<ApiKeyRecord>> =
        apiKeyRepository.findAll(accountId)

    suspend fun createApiKey(
        accountId: String,
        name: String,
        role: MiruRole,
        expiresAtMs: Long? = null,
        expiresIn: String? = null,
    ): Either<MiruError, CreatedApiKey> =
        either {
            val trimmedName = name.trim()
            val errors = linkedMapOf<String, String>()
            if (trimmedName.isEmpty() || trimmedName.length > MAX_API_KEY_NAME_LENGTH) {
                errors["name"] = "must be between 1 and $MAX_API_KEY_NAME_LENGTH characters"
            }
            val now = clock()
            val resolvedExpiresAtMs = resolveApiKeyExpiration(now, expiresAtMs, expiresIn, errors)
            ensure(errors.isEmpty()) {
                MiruError.ValidationError("Invalid API key request", fieldErrors = errors)
            }

            accountRepository.findById(accountId).bind()
                ?: raise(MiruError.NotFound(resource = "Account", id = accountId))

            val (rawKey, prefix) = TokenCrypto.generateApiKey()
            val record =
                ApiKeyRecord(
                    id = idGenerator(),
                    accountId = accountId,
                    name = trimmedName,
                    keyPrefix = prefix,
                    keyHash = TokenCrypto.sha256Hex(rawKey),
                    role = role.name,
                    lastUsedAtMs = null,
                    expiresAtMs = resolvedExpiresAtMs,
                    revokedAtMs = null,
                    createdAtMs = now,
                )
            apiKeyRepository.insert(record).bind()
            CreatedApiKey(record = record, rawKey = rawKey)
        }

    private fun resolveApiKeyExpiration(
        nowMs: Long,
        expiresAtMs: Long?,
        expiresIn: String?,
        errors: MutableMap<String, String>,
    ): Long? {
        if (expiresAtMs != null && expiresAtMs <= nowMs) {
            errors["expiresAtMs"] = "must be in the future"
        }
        val normalizedPreset = expiresIn?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (expiresAtMs != null && normalizedPreset != null) {
            errors["expiresAtMs"] = "must not be combined with expiresIn"
        }
        val presetExpiresAtMs =
            when (normalizedPreset) {
                null, "never" -> null
                "7d" -> nowMs + DAYS_7 * MILLIS_PER_DAY
                "30d" -> nowMs + DAYS_30 * MILLIS_PER_DAY
                "90d" -> nowMs + DAYS_90 * MILLIS_PER_DAY
                "365d" -> nowMs + DAYS_365 * MILLIS_PER_DAY
                else -> {
                    errors["expiresIn"] = "must be one of: 7d, 30d, 90d, 365d, never"
                    null
                }
            }
        return expiresAtMs ?: presetExpiresAtMs
    }

    suspend fun revokeApiKey(id: String): Either<MiruError, Unit> =
        either {
            val existing =
                apiKeyRepository.findById(id).bind()
                    ?: raise(MiruError.NotFound(resource = "ApiKey", id = id))
            if (existing.revokedAtMs == null) {
                apiKeyRepository.revoke(id, clock()).bind()
            }
        }

    suspend fun getPreferences(principal: MiruPrincipal): Either<MiruError, UserPreferencesResponse> =
        either {
            if (authConfig.mode == AuthMode.NONE ||
                principal.authSource == AuthSource.DEV_BYPASS ||
                preferencesRepository == null
            ) {
                return@either defaultPreferencesResponse()
            }
            val stored = preferencesRepository.findByAccountId(principal.accountId).bind()
            stored?.toResponse() ?: defaultPreferencesResponse()
        }

    suspend fun updatePreferences(
        principal: MiruPrincipal,
        request: UpdateUserPreferencesRequest,
    ): Either<MiruError, UserPreferencesResponse> =
        either {
            val validated = validatePreferences(request).bind()
            val now = clock()
            val canPersist =
                authConfig.mode != AuthMode.NONE &&
                    principal.authSource != AuthSource.DEV_BYPASS &&
                    preferencesRepository != null

            if (canPersist) {
                val account = accountRepository.findById(principal.accountId).bind()
                if (account != null) {
                    val record =
                        UserPreferencesRecord(
                            accountId = account.id,
                            theme = validated.theme,
                            timeFormat = validated.timeFormat,
                            dateFormat = validated.dateFormat,
                            defaultStatsRange = validated.defaultStatsRange,
                            relativeTimestamps = validated.relativeTimestamps,
                            analyticsMetric = validated.analyticsMetric,
                            updatedAtMs = now,
                        )
                    return@either preferencesRepository.upsert(record).bind().toResponse()
                }
            }

            UserPreferencesResponse(
                theme = validated.theme,
                timeFormat = validated.timeFormat,
                dateFormat = validated.dateFormat,
                defaultStatsRange = validated.defaultStatsRange,
                relativeTimestamps = validated.relativeTimestamps,
                analyticsMetric = validated.analyticsMetric,
                updatedAtMs = now,
            )
        }

    private suspend fun issueSession(
        account: AuthAccountRecord,
        linkedMediaUserIds: Set<String>,
    ): Either<MiruError, LoginResult> =
        either {
            val now = clock()
            val rawToken = TokenCrypto.generateToken()
            val expiresAtMs = now + sessionTtlMs
            val session =
                AuthSessionRecord(
                    id = idGenerator(),
                    accountId = account.id,
                    tokenHash = TokenCrypto.sha256Hex(rawToken),
                    createdAtMs = now,
                    expiresAtMs = expiresAtMs,
                    lastSeenAtMs = now,
                )
            sessionRepository.insert(session).bind()
            val signedToken = TokenCrypto.signSessionToken(rawToken, authConfig.sessionSecret)
            LoginResult(
                principal = account.toPrincipal(linkedMediaUserIds, AuthSource.SESSION_COOKIE),
                signedToken = signedToken,
                expiresAtMs = expiresAtMs,
            )
        }

    private suspend fun validateMediaUserIdsExist(mediaUserIds: Set<String>): Either<MiruError, Unit> =
        either {
            for (mediaUserId in mediaUserIds) {
                val found = userRepository.findById(mediaUserId).bind()
                if (found == null) {
                    raise(MiruError.NotFound(resource = "MediaUser", id = mediaUserId))
                }
            }
        }

    private fun validateCredentials(
        username: String,
        password: String,
        email: String?,
    ): Either<MiruError.ValidationError, ValidatedCredentials> =
        either {
            val trimmedUsername = username.trim()
            val trimmedEmail = email?.trim()?.takeIf { it.isNotEmpty() }
            val errors = linkedMapOf<String, String>()

            if (trimmedUsername.length !in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH ||
                !USERNAME_REGEX.matches(trimmedUsername)
            ) {
                errors["username"] =
                    "must be $MIN_USERNAME_LENGTH-$MAX_USERNAME_LENGTH characters matching [A-Za-z0-9._-]"
            }
            PasswordPolicy.validationError(password)?.let { message ->
                errors["password"] = message
            }

            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid account credentials",
                    fieldErrors = errors,
                )
            }

            ValidatedCredentials(
                username = trimmedUsername,
                usernameNormalized = trimmedUsername.lowercase(),
                email = trimmedEmail,
            )
        }

    private fun AuthAccountRecord.toPrincipal(
        linkedMediaUserIds: Set<String>,
        authSource: AuthSource,
    ): MiruPrincipal =
        MiruPrincipal(
            accountId = id,
            username = username,
            email = email,
            role = parseRole(role),
            linkedMediaUserIds = linkedMediaUserIds,
            authSource = authSource,
            authProvider = authProvider,
        )

    private fun parseRole(raw: String): MiruRole =
        when (raw.uppercase()) {
            "ADMIN" -> MiruRole.ADMIN
            else -> MiruRole.USER
        }

    private fun parseExplicitRole(raw: String): MiruRole? =
        when (raw.trim().uppercase()) {
            "ADMIN" -> MiruRole.ADMIN
            "USER" -> MiruRole.USER
            else -> null
        }

    private fun validatePreferences(
        request: UpdateUserPreferencesRequest,
    ): Either<MiruError.ValidationError, UpdateUserPreferencesRequest> =
        either {
            val theme = request.theme.trim()
            val timeFormat = request.timeFormat.trim()
            val dateFormat = request.dateFormat.trim()
            val defaultStatsRange = request.defaultStatsRange.trim()
            val analyticsMetric = request.analyticsMetric.trim()
            val errors = linkedMapOf<String, String>()

            if (theme !in ALLOWED_THEMES) {
                errors["theme"] = "must be one of: ${ALLOWED_THEMES.joinToString(", ")}"
            }
            if (timeFormat !in ALLOWED_TIME_FORMATS) {
                errors["timeFormat"] = "must be one of: ${ALLOWED_TIME_FORMATS.joinToString(", ")}"
            }
            if (dateFormat !in ALLOWED_DATE_FORMATS) {
                errors["dateFormat"] = "must be one of: ${ALLOWED_DATE_FORMATS.joinToString(", ")}"
            }
            if (defaultStatsRange !in ALLOWED_STATS_RANGES) {
                errors["defaultStatsRange"] = "must be one of: ${ALLOWED_STATS_RANGES.joinToString(", ")}"
            }
            if (analyticsMetric !in ALLOWED_ANALYTICS_METRICS) {
                errors["analyticsMetric"] = "must be one of: ${ALLOWED_ANALYTICS_METRICS.joinToString(", ")}"
            }

            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid user preferences",
                    fieldErrors = errors,
                )
            }

            UpdateUserPreferencesRequest(
                theme = theme,
                timeFormat = timeFormat,
                dateFormat = dateFormat,
                defaultStatsRange = defaultStatsRange,
                relativeTimestamps = request.relativeTimestamps,
                analyticsMetric = analyticsMetric,
            )
        }

    private fun defaultPreferencesResponse(): UserPreferencesResponse =
        UserPreferencesResponse(
            theme = DEFAULT_THEME,
            timeFormat = DEFAULT_TIME_FORMAT,
            dateFormat = DEFAULT_DATE_FORMAT,
            defaultStatsRange = DEFAULT_STATS_RANGE,
            relativeTimestamps = DEFAULT_RELATIVE_TIMESTAMPS,
            analyticsMetric = DEFAULT_ANALYTICS_METRIC,
            updatedAtMs = null,
        )

    private suspend fun hashPassword(password: String): String =
        passwordHashSemaphore.withPermit {
            withContext(Dispatchers.Default) {
                passwordHasher.hash(password)
            }
        }

    private suspend fun verifyPassword(
        password: String,
        encodedHash: String,
    ): Boolean =
        passwordHashSemaphore.withPermit {
            withContext(Dispatchers.Default) {
                passwordHasher.verify(password, encodedHash)
            }
        }

    private data class ValidatedCredentials(
        val username: String,
        val usernameNormalized: String,
        val email: String?,
    )

    companion object {
        const val MIN_USERNAME_LENGTH = 3
        const val MAX_USERNAME_LENGTH = 64
        const val DEFAULT_MAX_CONCURRENT_PASSWORD_HASHES = 4
        const val MIN_PASSWORD_LENGTH = PasswordPolicy.MIN_LENGTH
        const val MAX_API_KEY_NAME_LENGTH = 100
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val DAYS_7 = 7L
        private const val DAYS_30 = 30L
        private const val DAYS_90 = 90L
        private const val DAYS_365 = 365L
        const val DEFAULT_SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_THEME = "system"
        const val DEFAULT_TIME_FORMAT = "24h"
        const val DEFAULT_DATE_FORMAT = "iso"
        const val DEFAULT_STATS_RANGE = "30d"
        const val DEFAULT_RELATIVE_TIMESTAMPS = true
        const val DEFAULT_ANALYTICS_METRIC = "plays"
        val ALLOWED_THEMES = listOf("system", "dark", "light")
        val ALLOWED_TIME_FORMATS = listOf("24h", "12h")
        val ALLOWED_DATE_FORMATS = listOf("iso", "mdy", "dmy")
        val ALLOWED_STATS_RANGES = listOf("7d", "30d", "90d", "all")
        val ALLOWED_ANALYTICS_METRICS = listOf("plays", "duration")
        private val USERNAME_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }
}
