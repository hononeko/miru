package app.hononeko.miru.auth

import app.hononeko.miru.artwork.AvatarImageValidator
import app.hononeko.miru.auth.oidc.OidcAuthService.Companion.PROVIDER_OIDC
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.AccountResponse
import app.hononeko.miru.http.PrincipalResponse
import app.hononeko.miru.http.UpdateProfileRequest
import app.hononeko.miru.http.UpdateUserPreferencesRequest
import app.hononeko.miru.http.UserPreferencesResponse
import app.hononeko.miru.http.toResponse
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.model.AvatarMutation
import app.hononeko.miru.persistence.model.DisplayNameMutation
import app.hononeko.miru.persistence.model.UserPreferencesRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ApiKeyRepository
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import app.hononeko.miru.persistence.repository.UserPreferencesRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.ViewerIdentityResolver
import app.hononeko.miru.service.ViewerIdentityResolver.avatarProxyUrl
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
import java.sql.SQLException
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

@Suppress("LongParameterList", "TooManyFunctions")
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
    private val lastSeenTouchThresholdMs: Long = DEFAULT_LAST_SEEN_TOUCH_THRESHOLD_MS,
    private val principalCacheTtlMs: Long = DEFAULT_PRINCIPAL_CACHE_TTL_MS,
    private val principalCacheMaxEntries: Int = DEFAULT_PRINCIPAL_CACHE_MAX_ENTRIES,
) {
    private val principalCache =
        BoundedPrincipalCache(
            ttlMs = principalCacheTtlMs,
            maxEntries = principalCacheMaxEntries,
        )

    init {
        require(maxConcurrentPasswordHashes >= 1) { "maxConcurrentPasswordHashes must be >= 1" }
        require(lastSeenTouchThresholdMs >= 0L) { "lastSeenTouchThresholdMs must be >= 0" }
        require(principalCacheTtlMs >= 0L) { "principalCacheTtlMs must be >= 0" }
        require(principalCacheMaxEntries >= 0) { "principalCacheMaxEntries must be >= 0" }
        emailLinker?.addOnLinksChangedListener { accountId ->
            invalidateAccount(accountId)
        }
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

    /**
     * Anyone who can reach Miru can complete first-run setup, so it is only open before the first account exists.
     * An instance that loses its last admin, for example when SSO groups change, must not open it again.
     */
    suspend fun isSetupRequired(): Either<MiruError, Boolean> =
        either {
            authConfig.localLoginEnabled && accountRepository.count().bind() == 0L
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
                    ensure(accountRepository.count().bind() == 0L) {
                        MiruError.Conflict("First-run setup is only available before the first account exists")
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

    fun invalidateAccount(accountId: String) {
        principalCache.invalidateByAccountId(accountId)
    }

    fun evictExpiredSessions(nowMs: Long = clock()) {
        principalCache.evictExpired(nowMs)
    }

    fun invalidateAll() {
        principalCache.invalidateAll()
    }

    internal fun principalCacheSize(): Int = principalCache.size()

    suspend fun logout(signedToken: String?): Either<MiruError, Unit> =
        either {
            if (signedToken.isNullOrBlank()) {
                return@either
            }
            val rawToken =
                TokenCrypto.verifySignedSessionToken(signedToken, authConfig.sessionSecret)
                    ?: return@either
            val tokenHash = TokenCrypto.sha256Hex(rawToken)
            principalCache.invalidateByKey(CachedCredentialKind.SESSION, tokenHash)
            sessionRepository.deleteByTokenHash(tokenHash).bind()
        }

    suspend fun authenticateCookie(signedToken: String): Either<MiruError, MiruPrincipal> =
        either {
            val rawToken =
                TokenCrypto.verifySignedSessionToken(signedToken, authConfig.sessionSecret)
                    ?: raise(MiruError.Unauthorized("Invalid session token"))

            val tokenHash = TokenCrypto.sha256Hex(rawToken)
            val now = clock()

            when (
                val cached =
                    principalCache.getAndClaimActivityUpdate(
                        kind = CachedCredentialKind.SESSION,
                        hash = tokenHash,
                        nowMs = now,
                        touchThresholdMs = lastSeenTouchThresholdMs,
                    )
            ) {
                is PrincipalCacheLookup.Hit -> {
                    if (cached.shouldPersistActivity) {
                        persistActivityWrite(CachedCredentialKind.SESSION, tokenHash) {
                            sessionRepository.touchLastSeen(cached.entry.credentialId, now)
                        }.bind()
                    }
                    return@either cached.entry.principal
                }

                is PrincipalCacheLookup.ExpiredCredential -> {
                    sessionRepository.deleteByTokenHash(tokenHash).bind()
                    raise(MiruError.Unauthorized("Session expired"))
                }

                PrincipalCacheLookup.Miss -> Unit
            }

            val generation = principalCache.currentGeneration()
            val context =
                sessionRepository.findSessionAuthContextByTokenHash(tokenHash).bind()
                    ?: raise(MiruError.Unauthorized("Session not found"))

            if (context.session.expiresAtMs <= now) {
                sessionRepository.deleteByTokenHash(tokenHash).bind()
                raise(MiruError.Unauthorized("Session expired"))
            }

            if (context.account.disabled) {
                principalCache.invalidateByAccountId(context.account.id)
                raise(MiruError.Unauthorized("Account is disabled"))
            }

            val effectiveLastSeenAtMs =
                if (BoundedPrincipalCache.shouldPersistActivity(context.session.lastSeenAtMs, now, lastSeenTouchThresholdMs)) {
                    sessionRepository.touchLastSeen(context.session.id, now).bind()
                    now
                } else {
                    context.session.lastSeenAtMs
                }

            val principal = context.account.toPrincipal(context.linkedMediaUserIds, AuthSource.SESSION_COOKIE)
            principalCache.putIfGeneration(
                entry =
                    CachedAuthEntry(
                        kind = CachedCredentialKind.SESSION,
                        credentialHash = tokenHash,
                        credentialId = context.session.id,
                        accountId = context.account.id,
                        principal = principal,
                        credentialExpiresAtMs = context.session.expiresAtMs,
                        cachedAtMs = now,
                        cacheExpiresAtMs = now + principalCacheTtlMs,
                        lastActivityAtMs = effectiveLastSeenAtMs,
                    ),
                expectedGeneration = generation,
            )
            principal
        }

    suspend fun authenticateApiKey(rawKey: String): Either<MiruError, MiruPrincipal> =
        either {
            val trimmed = rawKey.trim()
            ensure(trimmed.isNotEmpty()) {
                MiruError.Unauthorized("Missing API key")
            }

            val keyHash = TokenCrypto.sha256Hex(trimmed)
            val now = clock()

            when (
                val cached =
                    principalCache.getAndClaimActivityUpdate(
                        kind = CachedCredentialKind.API_KEY,
                        hash = keyHash,
                        nowMs = now,
                        touchThresholdMs = lastSeenTouchThresholdMs,
                    )
            ) {
                is PrincipalCacheLookup.Hit -> {
                    if (cached.shouldPersistActivity) {
                        persistActivityWrite(CachedCredentialKind.API_KEY, keyHash) {
                            apiKeyRepository.updateLastUsed(cached.entry.credentialId, now)
                        }.bind()
                    }
                    return@either cached.entry.principal
                }

                is PrincipalCacheLookup.ExpiredCredential -> {
                    raise(MiruError.Unauthorized("API key has expired"))
                }

                PrincipalCacheLookup.Miss -> Unit
            }

            val generation = principalCache.currentGeneration()
            val context =
                apiKeyRepository.findApiKeyAuthContextByKeyHash(keyHash).bind()
                    ?: raise(MiruError.Unauthorized("Invalid API key"))

            ensure(context.apiKey.revokedAtMs == null) {
                MiruError.Unauthorized("API key has been revoked")
            }
            ensure(context.apiKey.expiresAtMs == null || context.apiKey.expiresAtMs > now) {
                MiruError.Unauthorized("API key has expired")
            }
            if (context.account.disabled) {
                principalCache.invalidateByAccountId(context.account.id)
                raise(MiruError.Unauthorized("Account is disabled"))
            }

            val effectiveLastUsedAtMs =
                if (BoundedPrincipalCache.shouldPersistActivity(context.apiKey.lastUsedAtMs, now, lastSeenTouchThresholdMs)) {
                    apiKeyRepository.updateLastUsed(context.apiKey.id, now).bind()
                    now
                } else {
                    context.apiKey.lastUsedAtMs
                }

            val accountRole = parseRole(context.account.role)
            val keyRole = parseRole(context.apiKey.role)
            val effectiveRole =
                if (accountRole == MiruRole.USER) {
                    MiruRole.USER
                } else {
                    keyRole
                }

            val principal =
                MiruPrincipal(
                    accountId = context.account.id,
                    username = context.account.username,
                    email = context.account.email,
                    role = effectiveRole,
                    linkedMediaUserIds = context.linkedMediaUserIds,
                    authSource = AuthSource.API_KEY,
                    authProvider = context.account.authProvider,
                    displayName = context.account.displayName,
                    avatarSourceServerId = context.account.avatarSourceServerId,
                    avatarUpdatedAtMs = context.account.avatarUpdatedAtMs,
                )
            principalCache.putIfGeneration(
                entry =
                    CachedAuthEntry(
                        kind = CachedCredentialKind.API_KEY,
                        credentialHash = keyHash,
                        credentialId = context.apiKey.id,
                        accountId = context.account.id,
                        principal = principal,
                        credentialExpiresAtMs = context.apiKey.expiresAtMs,
                        cachedAtMs = now,
                        cacheExpiresAtMs = now + principalCacheTtlMs,
                        lastActivityAtMs = effectiveLastUsedAtMs,
                    ),
                expectedGeneration = generation,
            )
            principal
        }

    private suspend inline fun persistActivityWrite(
        kind: CachedCredentialKind,
        hash: String,
        crossinline block: suspend () -> Either<MiruError.PersistenceError, Boolean>,
    ): Either<MiruError.PersistenceError, Unit> =
        when (val result = block()) {
            is Either.Left -> {
                principalCache.invalidateByKey(kind, hash)
                result
            }
            is Either.Right -> {
                if (!result.value) {
                    principalCache.invalidateByKey(kind, hash)
                }
                Either.Right(Unit)
            }
        }

    /** The `/auth/me` view of [principal]: its own display name and avatar, else those of its linked media users. */
    suspend fun resolvePrincipalResponse(principal: MiruPrincipal): Either<MiruError, PrincipalResponse> =
        either {
            val needsProfiles =
                principal.linkedMediaUserIds.isNotEmpty() &&
                    (principal.displayName.isNullOrBlank() || principal.avatarUpdatedAtMs == null)
            val orderedProfiles =
                if (needsProfiles) {
                    userRepository
                        .findByIds(principal.linkedMediaUserIds)
                        .bind()
                        .sortedWith(ViewerIdentityResolver.stableProfileComparator)
                } else {
                    emptyList()
                }

            val resolvedDisplayName = ViewerIdentityResolver.resolveDisplayName(principal.displayName, orderedProfiles)
            val avatarUrl =
                ViewerIdentityResolver.accountAvatarUrl(principal.accountId, principal.avatarUpdatedAtMs)
                    ?: orderedProfiles.firstNotNullOfOrNull { it.avatarProxyUrl() }

            principal.toResponse(
                avatarUrl = avatarUrl,
                resolvedDisplayName = resolvedDisplayName,
            )
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

    suspend fun listAccountResponses(): Either<MiruError, List<AccountResponse>> =
        either {
            val accounts = listAccounts().bind()
            val allLinkedIds = accounts.flatMapTo(HashSet()) { it.linkedMediaUserIds }
            val usersById =
                if (allLinkedIds.isEmpty()) {
                    emptyMap()
                } else {
                    userRepository.findByIds(allLinkedIds).bind().associateBy { it.id }
                }
            accounts.map { toAccountResponse(it, preloadedUsersById = usersById).bind() }
        }

    suspend fun toAccountResponse(
        accountWithLinks: AccountWithLinks,
        preloadedUsersById: Map<String, UserRecord>? = null,
    ): Either<MiruError, AccountResponse> =
        either {
            val account = accountWithLinks.account
            val avatarUrl =
                ViewerIdentityResolver.accountAvatarUrl(account.id, account.avatarUpdatedAtMs)
                    ?: if (accountWithLinks.linkedMediaUserIds.isNotEmpty()) {
                    val linkedUsers =
                        if (preloadedUsersById != null) {
                            accountWithLinks.linkedMediaUserIds.mapNotNull { preloadedUsersById[it] }
                        } else {
                            userRepository.findByIds(accountWithLinks.linkedMediaUserIds).bind()
                        }
                    linkedUsers
                        .sortedWith(ViewerIdentityResolver.stableProfileComparator)
                        .firstNotNullOfOrNull { it.avatarProxyUrl() }
                } else {
                    null
                }
            accountWithLinks.toResponse(avatarUrl = avatarUrl)
        }

    suspend fun createAccount(
        username: String,
        password: String,
        email: String? = null,
        role: MiruRole = MiruRole.USER,
        linkedMediaUserIds: Set<String> = emptySet(),
        displayName: String? = null,
    ): Either<MiruError, AccountWithLinks> =
        either {
            val validated = validateCredentials(username, password, email).bind()
            val validatedDisplayName = validateOptionalDisplayName(displayName).bind()
            val normalizedLinks = linkedMediaUserIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            validateMediaUserLinks(normalizedLinks, excludingAccountId = null).bind()

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
                    displayName = validatedDisplayName,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            accountRepository.insert(account).bind()
            if (normalizedLinks.isNotEmpty()) {
                replaceLinksOrConflict(account.id, normalizedLinks, now).bind()
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
            principalCache.invalidateSessionsByAccountId(account.id)
            logger.info("Password changed for local account '{}'", updated.username)

            if (principal.authSource == AuthSource.SESSION_COOKIE) {
                val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
                issueSession(updated, linkedIds).bind()
            } else {
                null
            }
        }

    /** Sets or clears the caller's own display name or avatar; importing from a server is for admins. */
    suspend fun updateOwnProfile(
        principal: MiruPrincipal,
        request: UpdateProfileRequest,
    ): Either<MiruError, PrincipalResponse> =
        either {
            ensure(authConfig.mode != AuthMode.NONE && principal.authSource != AuthSource.DEV_BYPASS) {
                MiruError.Conflict("Profile management is disabled when authentication is disabled")
            }
            val hasChange = request.displayName != null || request.clearDisplayName || request.clearAvatar
            ensure(hasChange) {
                MiruError.ValidationError(
                    message = "At least one profile field must be provided",
                )
            }

            val account =
                accountRepository.findById(principal.accountId).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = principal.accountId))
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            val mutations =
                resolveProfileMutations(
                    accountId = account.id,
                    displayName = request.displayName,
                    clearDisplayName = request.clearDisplayName,
                    clearAvatar = request.clearAvatar,
                    importDisplayNameFromServerId = null,
                    importAvatarFromServerId = null,
                ).bind()

            val updated =
                accountRepository
                    .updateProfile(
                        id = account.id,
                        displayName = mutations.displayName,
                        avatar = mutations.avatar,
                        updatedAtMs = clock(),
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = account.id))

            principalCache.invalidateByAccountId(account.id)
            val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
            resolvePrincipalResponse(updated.toPrincipal(linkedIds, principal.authSource)).bind()
        }

    suspend fun uploadOwnAvatar(
        principal: MiruPrincipal,
        bytes: ByteArray,
        declaredContentType: String? = null,
    ): Either<MiruError, PrincipalResponse> =
        either {
            ensure(authConfig.mode != AuthMode.NONE && principal.authSource != AuthSource.DEV_BYPASS) {
                MiruError.Conflict("Profile management is disabled when authentication is disabled")
            }
            val validated = AvatarImageValidator.validate(bytes, declaredContentType).bind()
            val account =
                accountRepository.findById(principal.accountId).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = principal.accountId))
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            val now = clock()
            val updated =
                accountRepository
                    .updateProfile(
                        id = account.id,
                        avatar =
                            AvatarMutation.Set(
                                bytes = validated.bytes,
                                contentType = validated.contentType,
                                sourceServerId = null,
                                avatarUpdatedAtMs = now,
                            ),
                        updatedAtMs = now,
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = account.id))

            principalCache.invalidateByAccountId(account.id)
            val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
            resolvePrincipalResponse(updated.toPrincipal(linkedIds, principal.authSource)).bind()
        }

    suspend fun deleteOwnAvatar(principal: MiruPrincipal): Either<MiruError, PrincipalResponse> =
        either {
            ensure(authConfig.mode != AuthMode.NONE && principal.authSource != AuthSource.DEV_BYPASS) {
                MiruError.Conflict("Profile management is disabled when authentication is disabled")
            }
            val account =
                accountRepository.findById(principal.accountId).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = principal.accountId))
            ensure(!account.disabled) {
                MiruError.Unauthorized("Account is disabled")
            }

            val updated =
                accountRepository
                    .updateProfile(
                        id = account.id,
                        avatar = AvatarMutation.Clear,
                        updatedAtMs = clock(),
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = account.id))

            principalCache.invalidateByAccountId(account.id)
            val linkedIds = accountRepository.findLinkedMediaUserIds(account.id).bind()
            resolvePrincipalResponse(updated.toPrincipal(linkedIds, principal.authSource)).bind()
        }

    @Suppress("LongParameterList")
    suspend fun updateAccount(
        id: String,
        actingAccountId: String,
        role: String? = null,
        roleMode: RoleMode? = null,
        disabled: Boolean? = null,
        password: String? = null,
        displayName: String? = null,
        clearDisplayName: Boolean = false,
        clearAvatar: Boolean = false,
        importDisplayNameFromServerId: String? = null,
        importAvatarFromServerId: String? = null,
        profileImport: ProfileImportSources? = null,
    ): Either<MiruError, AccountWithLinks> =
        either {
            val hasCoreChange = role != null || roleMode != null || disabled != null || password != null
            val hasProfileChange =
                displayName != null ||
                    clearDisplayName ||
                    clearAvatar ||
                    importDisplayNameFromServerId != null ||
                    importAvatarFromServerId != null
            ensure(hasCoreChange || hasProfileChange) {
                MiruError.ValidationError("At least one field must be provided")
            }

            val corePlan = validateAccountCorePlan(role, roleMode, disabled, password).bind()
            val target =
                accountRepository.findById(id).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = id))
            val resolvedCore = resolveAccountCoreUpdate(id, actingAccountId, target, corePlan).bind()

            val profileMutations =
                if (hasProfileChange) {
                    resolveProfileMutations(
                        accountId = id,
                        displayName = displayName,
                        clearDisplayName = clearDisplayName,
                        clearAvatar = clearAvatar,
                        importDisplayNameFromServerId = importDisplayNameFromServerId,
                        importAvatarFromServerId = importAvatarFromServerId,
                        profileImport = profileImport,
                    ).bind()
                } else {
                    ProfileMutations(DisplayNameMutation.Keep, AvatarMutation.Keep)
                }

            val now = clock()
            val newPasswordHash = password?.let { hashPassword(it) }
            var latest = target
            if (hasCoreChange) {
                latest =
                    accountRepository
                        .updateAccount(
                            id = id,
                            role = resolvedCore.nextRole.name,
                            disabled = resolvedCore.nextDisabled,
                            passwordHash = newPasswordHash,
                            roleMode = resolvedCore.nextRoleMode?.name,
                            updatedAtMs = now,
                        ).bind()
                        ?: raise(MiruError.NotFound(resource = "Account", id = id))
            }
            if (profileMutations.displayName != DisplayNameMutation.Keep || profileMutations.avatar != AvatarMutation.Keep) {
                latest =
                    accountRepository
                        .updateProfile(
                            id = id,
                            displayName = profileMutations.displayName,
                            avatar = profileMutations.avatar,
                            updatedAtMs = now,
                        ).bind()
                        ?: raise(MiruError.NotFound(resource = "Account", id = id))
            }

            if (resolvedCore.nextDisabled || resolvedCore.roleDowngraded || newPasswordHash != null) {
                sessionRepository.deleteByAccountId(id).bind()
            }
            principalCache.invalidateByAccountId(id)

            val links = accountRepository.findLinkedMediaUserIds(id).bind()
            AccountWithLinks(account = latest, linkedMediaUserIds = links)
        }

    private fun validateAccountCorePlan(
        role: String?,
        roleMode: RoleMode?,
        disabled: Boolean?,
        password: String?,
    ): Either<MiruError.ValidationError, AccountCorePlan> =
        either {
            val errors = linkedMapOf<String, String>()
            val parsedRole =
                role?.let { raw ->
                    parseExplicitRole(raw).also { parsed ->
                        if (parsed == null) {
                            errors["role"] = "must be 'ADMIN' or 'USER'"
                        }
                    }
                }
            if (role != null && roleMode == RoleMode.AUTO) {
                errors["roleMode"] = "cannot be 'AUTO' when a role is given"
            }
            if (password != null) {
                PasswordPolicy.validationError(password)?.let { message ->
                    errors["password"] = message
                }
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError("Invalid account update request", fieldErrors = errors)
            }
            AccountCorePlan(parsedRole = parsedRole, roleMode = roleMode, disabled = disabled, password = password)
        }

    private suspend fun resolveAccountCoreUpdate(
        id: String,
        actingAccountId: String,
        target: AuthAccountRecord,
        plan: AccountCorePlan,
    ): Either<MiruError, ResolvedAccountCoreUpdate> =
        either {
            if (plan.password != null) {
                ensure(target.authProvider.equals("LOCAL", ignoreCase = true)) {
                    MiruError.Conflict("Password reset is only supported for local accounts")
                }
            }

            ensure(plan.roleMode != RoleMode.AUTO || target.authProvider.equals(PROVIDER_OIDC, ignoreCase = true)) {
                MiruError.Conflict("Only SSO accounts can take their role from groups")
            }

            val currentRole = parseRole(target.role)
            val nextRole = plan.parsedRole ?: currentRole
            val nextRoleMode = plan.roleMode ?: RoleMode.MANUAL.takeIf { plan.parsedRole != null }
            // The groups may take the admin role away at the next sign-in.
            val handsRoleToGroups = nextRoleMode == RoleMode.AUTO && RoleMode.fromStored(target.roleMode) != RoleMode.AUTO
            val nextDisabled = plan.disabled ?: target.disabled

            if (id == actingAccountId) {
                ensure(nextRole == MiruRole.ADMIN) {
                    MiruError.Conflict("Cannot demote your own account")
                }
                ensure(!handsRoleToGroups) {
                    MiruError.Conflict("Cannot hand the role of your own account to its groups")
                }
                ensure(!nextDisabled) {
                    MiruError.Conflict("Cannot disable your own account")
                }
            }

            val isTargetActiveAdmin = currentRole == MiruRole.ADMIN && !target.disabled
            val losingActiveAdmin = isTargetActiveAdmin && (nextRole != MiruRole.ADMIN || nextDisabled || handsRoleToGroups)
            if (losingActiveAdmin) {
                val activeAdmins = accountRepository.countActiveAdmins().bind()
                ensure(activeAdmins > 1L) {
                    MiruError.Conflict("Cannot demote or disable the last active admin account")
                }
            }

            ResolvedAccountCoreUpdate(
                nextRole = nextRole,
                nextRoleMode = nextRoleMode,
                nextDisabled = nextDisabled,
                roleDowngraded = currentRole == MiruRole.ADMIN && nextRole != MiruRole.ADMIN,
            )
        }

    /**
     * Copies the display name and/or avatar of the account's linked media user on [serverId] into the
     * account itself, as an admin import does, for imports Miru makes on its own (see [AccountProfileAutoImporter]).
     */
    suspend fun importProfileFromServer(
        accountId: String,
        serverId: String,
        importDisplayName: Boolean,
        importAvatar: Boolean,
        profileImport: ProfileImportSources,
    ): Either<MiruError, AccountWithLinks> =
        either {
            val mutations =
                resolveProfileMutations(
                    accountId = accountId,
                    displayName = null,
                    clearDisplayName = false,
                    clearAvatar = false,
                    importDisplayNameFromServerId = serverId.takeIf { importDisplayName },
                    importAvatarFromServerId = serverId.takeIf { importAvatar },
                    profileImport = profileImport,
                ).bind()
            val updated =
                accountRepository
                    .updateProfile(
                        id = accountId,
                        displayName = mutations.displayName,
                        avatar = mutations.avatar,
                        updatedAtMs = clock(),
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = accountId))
            principalCache.invalidateByAccountId(accountId)
            AccountWithLinks(account = updated, linkedMediaUserIds = accountRepository.findLinkedMediaUserIds(accountId).bind())
        }

    suspend fun uploadAccountAvatar(
        id: String,
        bytes: ByteArray,
        declaredContentType: String? = null,
    ): Either<MiruError, AccountWithLinks> =
        either {
            val validated = AvatarImageValidator.validate(bytes, declaredContentType).bind()
            val now = clock()
            val updated =
                accountRepository
                    .updateProfile(
                        id = id,
                        avatar =
                            AvatarMutation.Set(
                                bytes = validated.bytes,
                                contentType = validated.contentType,
                                sourceServerId = null,
                                avatarUpdatedAtMs = now,
                            ),
                        updatedAtMs = now,
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = id))
            principalCache.invalidateByAccountId(id)
            val links = accountRepository.findLinkedMediaUserIds(id).bind()
            AccountWithLinks(account = updated, linkedMediaUserIds = links)
        }

    suspend fun deleteAccountAvatar(id: String): Either<MiruError, AccountWithLinks> =
        either {
            val updated =
                accountRepository
                    .updateProfile(
                        id = id,
                        avatar = AvatarMutation.Clear,
                        updatedAtMs = clock(),
                    ).bind()
                    ?: raise(MiruError.NotFound(resource = "Account", id = id))
            principalCache.invalidateByAccountId(id)
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
            principalCache.invalidateByAccountId(id)
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
            validateMediaUserLinks(normalizedLinks, excludingAccountId = accountId).bind()
            val now = clock()
            val manual = MediaUserLinkMode.MANUAL.name
            replaceLinksOrConflict(accountId, normalizedLinks, now, linkMode = manual).bind()
            principalCache.invalidateByAccountId(accountId)
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
            principalCache.invalidateByAccountId(accountId)
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
            principalCache.invalidateByKey(CachedCredentialKind.API_KEY, existing.keyHash)
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
            val current = getPreferences(principal).bind()
            val validated = validatePreferences(request, current).bind()
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

            validated.copy(updatedAtMs = now)
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

    /**
     * Checks that every media user exists, that no two are on the same media server (a Miru account
     * stands for one person, who has one account on each media server), and that none of them is
     * already owned by a different Miru account.
     */
    private suspend fun validateMediaUserLinks(
        mediaUserIds: Set<String>,
        excludingAccountId: String?,
    ): Either<MiruError, Unit> =
        either {
            if (mediaUserIds.isEmpty()) return@either
            val users =
                mediaUserIds.map { mediaUserId ->
                    userRepository.findById(mediaUserId).bind()
                        ?: raise(MiruError.NotFound(resource = "MediaUser", id = mediaUserId))
                }
            val sameServer = users.groupBy { it.serverId }.values.firstOrNull { it.size > 1 }
            ensure(sameServer == null) {
                val names = sameServer.sortedBy { it.username }.joinToString(" and ") { "'${it.username}'" }
                MiruError.ValidationError(
                    message = "A Miru account can be linked to only one media user on each media server",
                    fieldErrors = mapOf("linkedMediaUserIds" to "links $names, who are on the same media server"),
                )
            }
            val ownersByMediaUser = accountRepository.findAccountIdsByMediaUserIds(mediaUserIds).bind()
            val conflictEntry = ownersByMediaUser.entries.firstOrNull { (_, ownerId) -> ownerId != excludingAccountId }
            if (conflictEntry != null) {
                val (conflictingMediaUserId, ownerAccountId) = conflictEntry
                val conflictingUser = users.first { it.id == conflictingMediaUserId }
                val ownerAccount = accountRepository.findById(ownerAccountId).bind()
                val ownerLabel = ownerAccount?.username ?: ownerAccountId
                raise(
                    MiruError.Conflict(
                        "Media user '${conflictingUser.username}' is already linked to account '$ownerLabel'",
                    ),
                )
            }
        }

    private suspend fun replaceLinksOrConflict(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
        linkMode: String? = null,
    ): Either<MiruError, Unit> =
        accountRepository.replaceLinkedMediaUserIds(accountId, mediaUserIds, createdAtMs, linkMode).mapLeft { err ->
            if (isUniqueConstraintViolation(err.cause)) {
                MiruError.Conflict("One or more media users are already linked to another account")
            } else {
                err
            }
        }

    private fun isUniqueConstraintViolation(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            if (current is SQLException) {
                if (current.sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE) return true
                val msg = current.message.orEmpty()
                if (current.sqlState == SQLITE_CONSTRAINT_SQLSTATE && msg.contains("UNIQUE", ignoreCase = true)) {
                    return true
                }
                if (msg.contains("UNIQUE constraint failed", ignoreCase = true)) return true
            }
            current = current.cause
        }
        return false
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

    @Suppress("LongParameterList")
    private suspend fun resolveProfileMutations(
        accountId: String,
        displayName: String?,
        clearDisplayName: Boolean,
        clearAvatar: Boolean,
        importDisplayNameFromServerId: String?,
        importAvatarFromServerId: String?,
        profileImport: ProfileImportSources? = null,
    ): Either<MiruError, ProfileMutations> =
        either {
            val errors = linkedMapOf<String, String>()
            if (clearDisplayName && (displayName != null || importDisplayNameFromServerId != null)) {
                errors["clearDisplayName"] = "cannot be combined with displayName or importDisplayNameFromServerId"
            }
            if (displayName != null && importDisplayNameFromServerId != null) {
                errors["displayName"] = "cannot be combined with importDisplayNameFromServerId"
            }
            if (clearAvatar && importAvatarFromServerId != null) {
                errors["clearAvatar"] = "cannot be combined with importAvatarFromServerId"
            }
            val validatedExplicitDisplayName =
                if (displayName != null && !clearDisplayName && importDisplayNameFromServerId == null) {
                    validateExplicitDisplayName(displayName).fold(
                        ifLeft = { err ->
                            errors.putAll(err.fieldErrors)
                            null
                        },
                        ifRight = { it },
                    )
                } else {
                    null
                }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError("Invalid profile update request", fieldErrors = errors)
            }

            // Import what the server has now, not what the last scheduled user sync saw
            setOfNotNull(importDisplayNameFromServerId, importAvatarFromServerId)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { serverId -> profileImport?.syncServerUsers?.invoke(serverId) }

            val displayNameMutation: DisplayNameMutation =
                when {
                    clearDisplayName -> DisplayNameMutation.Set(null)
                    importDisplayNameFromServerId != null -> {
                        val linkedUser =
                            resolveLinkedUserForServer(
                                accountId = accountId,
                                serverId = importDisplayNameFromServerId,
                                fieldName = "importDisplayNameFromServerId",
                            ).bind()
                        val importedName =
                            sanitizeImportedDisplayName(linkedUser.displayName)
                                ?: sanitizeImportedDisplayName(linkedUser.username)
                                ?: raise(
                                    MiruError.ValidationError(
                                        message = "Linked user on server '${linkedUser.serverId}' has no display name or username",
                                        fieldErrors = mapOf("importDisplayNameFromServerId" to "no display name available"),
                                    ),
                                )
                        DisplayNameMutation.Set(importedName)
                    }
                    displayName != null -> DisplayNameMutation.Set(validatedExplicitDisplayName)
                    else -> DisplayNameMutation.Keep
                }

            val avatarMutation: AvatarMutation =
                when {
                    clearAvatar -> AvatarMutation.Clear
                    importAvatarFromServerId != null -> {
                        val linkedUser =
                            resolveLinkedUserForServer(
                                accountId = accountId,
                                serverId = importAvatarFromServerId,
                                fieldName = "importAvatarFromServerId",
                            ).bind()
                        val proxy =
                            profileImport?.artworkProxyService
                                ?: raise(
                                    MiruError.UpstreamUnavailable(
                                        provider = "media_server",
                                        message = "Artwork proxy service is not configured for avatar import",
                                    ),
                                )
                        val fetched =
                            proxy
                                .fetchMediaUserAvatarFromSource(linkedUser, preferredMaxBytes = AvatarImageValidator.MAX_AVATAR_BYTES)
                                .bind()
                        val validated =
                            AvatarImageValidator
                                .validate(fetched.bytes, maxBytes = AvatarImageValidator.MAX_IMPORTED_AVATAR_BYTES)
                                .bind()
                        AvatarMutation.Set(
                            bytes = validated.bytes,
                            contentType = validated.contentType,
                            sourceServerId = linkedUser.serverId,
                            avatarUpdatedAtMs = clock(),
                        )
                    }
                    else -> AvatarMutation.Keep
                }

            ProfileMutations(displayName = displayNameMutation, avatar = avatarMutation)
        }

    private suspend fun resolveLinkedUserForServer(
        accountId: String,
        serverId: String,
        fieldName: String,
    ): Either<MiruError, UserRecord> =
        either {
            val trimmedServerId = serverId.trim()
            ensure(trimmedServerId.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid server id",
                    fieldErrors = mapOf(fieldName to "must not be blank"),
                )
            }
            val linkedIds = accountRepository.findLinkedMediaUserIds(accountId).bind()
            if (linkedIds.isEmpty()) {
                raise(MiruError.NotFound(resource = "Linked media user on server", id = trimmedServerId))
            }
            val linkedUsers = userRepository.findByIds(linkedIds).bind().sortedWith(ViewerIdentityResolver.stableProfileComparator)
            linkedUsers.firstOrNull { it.serverId == trimmedServerId }
                ?: raise(MiruError.NotFound(resource = "Linked media user on server", id = trimmedServerId))
        }

    /** A media server's name for a user, without control characters and cut to the display name limit. */
    private fun sanitizeImportedDisplayName(raw: String?): String? =
        raw
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(MAX_DISPLAY_NAME_LENGTH)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun validateOptionalDisplayName(raw: String?): Either<MiruError.ValidationError, String?> =
        either {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return@either null
            validateExplicitDisplayName(trimmed).bind()
        }

    private fun validateExplicitDisplayName(raw: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = raw.trim()
            val errors = linkedMapOf<String, String>()
            if (trimmed.isEmpty()) {
                errors["displayName"] = "must not be blank; use clearDisplayName to clear"
            } else if (trimmed.length > MAX_DISPLAY_NAME_LENGTH) {
                errors["displayName"] = "must be at most $MAX_DISPLAY_NAME_LENGTH characters"
            } else if (trimmed.any { it.isISOControl() }) {
                errors["displayName"] = "must not contain control characters"
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError("Invalid display name", fieldErrors = errors)
            }
            trimmed
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
            displayName = displayName,
            avatarSourceServerId = avatarSourceServerId,
            avatarUpdatedAtMs = avatarUpdatedAtMs,
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

    /** Returns [current] with the preferences given in [request]. */
    private fun validatePreferences(
        request: UpdateUserPreferencesRequest,
        current: UserPreferencesResponse,
    ): Either<MiruError.ValidationError, UserPreferencesResponse> =
        either {
            val theme = request.theme?.trim() ?: current.theme
            val timeFormat = request.timeFormat?.trim() ?: current.timeFormat
            val dateFormat = request.dateFormat?.trim() ?: current.dateFormat
            val defaultStatsRange = request.defaultStatsRange?.trim() ?: current.defaultStatsRange
            val analyticsMetric = request.analyticsMetric?.trim() ?: current.analyticsMetric
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

            UserPreferencesResponse(
                theme = theme,
                timeFormat = timeFormat,
                dateFormat = dateFormat,
                defaultStatsRange = defaultStatsRange,
                relativeTimestamps = request.relativeTimestamps ?: current.relativeTimestamps,
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

    private data class ProfileMutations(
        val displayName: DisplayNameMutation,
        val avatar: AvatarMutation,
    )

    private data class AccountCorePlan(
        val parsedRole: MiruRole?,
        val roleMode: RoleMode?,
        val disabled: Boolean?,
        val password: String?,
    )

    private data class ResolvedAccountCoreUpdate(
        val nextRole: MiruRole,
        val nextRoleMode: RoleMode?,
        val nextDisabled: Boolean,
        val roleDowngraded: Boolean,
    )

    companion object {
        const val MIN_USERNAME_LENGTH = 3
        const val MAX_USERNAME_LENGTH = 64
        const val MAX_DISPLAY_NAME_LENGTH = 100
        const val DEFAULT_MAX_CONCURRENT_PASSWORD_HASHES = 4
        const val MIN_PASSWORD_LENGTH = PasswordPolicy.MIN_LENGTH
        const val MAX_API_KEY_NAME_LENGTH = 100
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val DAYS_7 = 7L
        private const val DAYS_30 = 30L
        private const val DAYS_90 = 90L
        private const val DAYS_365 = 365L
        const val DEFAULT_SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_LAST_SEEN_TOUCH_THRESHOLD_MS = 60_000L
        const val DEFAULT_PRINCIPAL_CACHE_TTL_MS = 15_000L
        const val DEFAULT_PRINCIPAL_CACHE_MAX_ENTRIES = 1_024
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
        private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
        private const val SQLITE_CONSTRAINT_SQLSTATE = "23000"
    }
}
