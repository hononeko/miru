package app.hononeko.miru.auth

import app.hononeko.miru.auth.oidc.OidcAuthService
import app.hononeko.miru.auth.oidc.OidcClient
import app.hononeko.miru.auth.oidc.OidcDiscoveryDocument
import app.hononeko.miru.auth.oidc.OidcIdTokenClaims
import app.hononeko.miru.auth.oidc.OidcTokenResponse
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.OidcConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.ApiKeyAuthContext
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.SessionAuthContext
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ApiKeyRepository
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private class CountingAuthAccountRepository(
    private val delegate: AuthAccountRepository,
) : AuthAccountRepository by delegate {
    val findByIdCalls = AtomicInteger(0)
    val findLinkedMediaUserIdsCalls = AtomicInteger(0)

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, AuthAccountRecord?> {
        findByIdCalls.incrementAndGet()
        return delegate.findById(id)
    }

    override suspend fun findLinkedMediaUserIds(accountId: String): Either<MiruError.PersistenceError, Set<String>> {
        findLinkedMediaUserIdsCalls.incrementAndGet()
        return delegate.findLinkedMediaUserIds(accountId)
    }

    fun resetAuthReadCounts() {
        findByIdCalls.set(0)
        findLinkedMediaUserIdsCalls.set(0)
    }
}

private class CountingAuthSessionRepository(
    private val delegate: AuthSessionRepository,
) : AuthSessionRepository by delegate {
    val findByTokenHashCalls = AtomicInteger(0)
    val findContextCalls = AtomicInteger(0)
    val touchLastSeenCalls = AtomicInteger(0)
    var failNextTouch = false
    var contextLookupHook: (suspend () -> Unit)? = null

    override suspend fun findByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, AuthSessionRecord?> {
        findByTokenHashCalls.incrementAndGet()
        return delegate.findByTokenHash(tokenHash)
    }

    override suspend fun findSessionAuthContextByTokenHash(
        tokenHash: String,
    ): Either<MiruError.PersistenceError, SessionAuthContext?> {
        findContextCalls.incrementAndGet()
        contextLookupHook?.invoke()
        return delegate.findSessionAuthContextByTokenHash(tokenHash)
    }

    override suspend fun touchLastSeen(
        id: String,
        lastSeenAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> {
        touchLastSeenCalls.incrementAndGet()
        if (failNextTouch) {
            failNextTouch = false
            return MiruError.PersistenceError("auth_session_touch_last_seen", "simulated db failure").left()
        }
        return delegate.touchLastSeen(id, lastSeenAtMs)
    }

    fun resetCounts() {
        findByTokenHashCalls.set(0)
        findContextCalls.set(0)
        touchLastSeenCalls.set(0)
    }
}

private class CountingApiKeyRepository(
    private val delegate: ApiKeyRepository,
) : ApiKeyRepository by delegate {
    val findByKeyHashCalls = AtomicInteger(0)
    val findContextCalls = AtomicInteger(0)
    val updateLastUsedCalls = AtomicInteger(0)
    var failNextUpdateLastUsed = false

    override suspend fun findByKeyHash(keyHash: String): Either<MiruError.PersistenceError, ApiKeyRecord?> {
        findByKeyHashCalls.incrementAndGet()
        return delegate.findByKeyHash(keyHash)
    }

    override suspend fun findApiKeyAuthContextByKeyHash(
        keyHash: String,
    ): Either<MiruError.PersistenceError, ApiKeyAuthContext?> {
        findContextCalls.incrementAndGet()
        return delegate.findApiKeyAuthContextByKeyHash(keyHash)
    }

    override suspend fun updateLastUsed(
        id: String,
        lastUsedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> {
        updateLastUsedCalls.incrementAndGet()
        if (failNextUpdateLastUsed) {
            failNextUpdateLastUsed = false
            return MiruError.PersistenceError("api_key_update_last_used", "simulated db failure").left()
        }
        return delegate.updateLastUsed(id, lastUsedAtMs)
    }

    fun resetCounts() {
        findByKeyHashCalls.set(0)
        findContextCalls.set(0)
        updateLastUsedCalls.set(0)
    }
}

private class HotPathHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val nowMs: AtomicLong,
    val rawAccountRepo: ExposedAuthAccountRepository,
    val rawSessionRepo: ExposedAuthSessionRepository,
    val rawApiKeyRepo: ExposedApiKeyRepository,
    val accountRepo: CountingAuthAccountRepository,
    val sessionRepo: CountingAuthSessionRepository,
    val apiKeyRepo: CountingApiKeyRepository,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val authConfig: AuthConfig,
    val emailLinker: EmailMediaUserLinker,
    val authService: AuthService,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createHotPathHarness(
    authConfig: AuthConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        ),
    sessionTtlMs: Long = 3_600_000L,
    lastSeenTouchThresholdMs: Long = AuthService.DEFAULT_LAST_SEEN_TOUCH_THRESHOLD_MS,
    principalCacheTtlMs: Long = AuthService.DEFAULT_PRINCIPAL_CACHE_TTL_MS,
    principalCacheMaxEntries: Int = AuthService.DEFAULT_PRINCIPAL_CACHE_MAX_ENTRIES,
): HotPathHarness {
    val dir = Files.createTempDirectory("miru-auth-hot-path-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val nowMs = AtomicLong(1_000_000L)
    val rawAccountRepo = ExposedAuthAccountRepository(handle)
    val rawSessionRepo = ExposedAuthSessionRepository(handle)
    val rawApiKeyRepo = ExposedApiKeyRepository(handle)
    val accountRepo = CountingAuthAccountRepository(rawAccountRepo)
    val sessionRepo = CountingAuthSessionRepository(rawSessionRepo)
    val apiKeyRepo = CountingApiKeyRepository(rawApiKeyRepo)
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val emailLinker = EmailMediaUserLinker(accountRepo, userRepo, authConfig, clock = { nowMs.get() })
    val authService =
        AuthService(
            accountRepository = accountRepo,
            sessionRepository = sessionRepo,
            apiKeyRepository = apiKeyRepo,
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
            sessionTtlMs = sessionTtlMs,
            clock = { nowMs.get() },
            emailLinker = emailLinker,
            lastSeenTouchThresholdMs = lastSeenTouchThresholdMs,
            principalCacheTtlMs = principalCacheTtlMs,
            principalCacheMaxEntries = principalCacheMaxEntries,
        )
    return HotPathHarness(
        tempDir = dir,
        dbHandle = handle,
        nowMs = nowMs,
        rawAccountRepo = rawAccountRepo,
        rawSessionRepo = rawSessionRepo,
        rawApiKeyRepo = rawApiKeyRepo,
        accountRepo = accountRepo,
        sessionRepo = sessionRepo,
        apiKeyRepo = apiKeyRepo,
        serverRepo = serverRepo,
        userRepo = userRepo,
        authConfig = authConfig,
        emailLinker = emailLinker,
        authService = authService,
    )
}

private suspend fun seedMediaUsers(harness: HotPathHarness) {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Plex",
                serverType = "PLEX",
                machineId = "mach-1",
                baseUrl = "http://plex.local:32400",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    harness.userRepo
        .upsert(
            UserRecord(
                id = "mu-1",
                serverId = "srv-1",
                externalUserId = "ext-1",
                username = "alice",
                email = "alice@miru.local",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    harness.userRepo
        .upsert(
            UserRecord(
                id = "mu-2",
                serverId = "srv-1",
                externalUserId = "ext-2",
                username = "bob",
                email = "bob@miru.local",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
}

class AuthHotPathOptimizationTest :
    FunSpec({
        context("BoundedPrincipalCache") {
            test("evicts least recently used entries when bounded capacity is reached and redacts hashes in toString") {
                val cache = BoundedPrincipalCache(ttlMs = 15_000L, maxEntries = 2)
                val gen = cache.currentGeneration()

                fun makeEntry(
                    hash: String,
                    accountId: String,
                    credExpiresAtMs: Long? = 100_000L,
                    cachedAtMs: Long = 1_000L,
                ) = CachedAuthEntry(
                    kind = CachedCredentialKind.SESSION,
                    credentialHash = hash,
                    credentialId = "cred-$accountId",
                    accountId = accountId,
                    principal = MiruPrincipal(accountId = accountId, username = accountId, role = MiruRole.USER),
                    credentialExpiresAtMs = credExpiresAtMs,
                    cachedAtMs = cachedAtMs,
                    cacheExpiresAtMs = cachedAtMs + 15_000L,
                    lastActivityAtMs = cachedAtMs,
                )

                val entry1 = makeEntry("secret-sha256-hash-1", "acc-1")
                entry1.toString() shouldNotContain "secret-sha256-hash-1"
                entry1.toString() shouldContain "credentialHash=***"

                cache.putIfGeneration(entry1, gen)
                cache.putIfGeneration(makeEntry("secret-sha256-hash-2", "acc-2"), gen)
                cache.size() shouldBe 2

                // Touch acc-1 so acc-2 becomes the LRU entry
                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.SESSION, "secret-sha256-hash-1", 2_000L, 60_000L)
                    .shouldBeInstanceOf<PrincipalCacheLookup.Hit>()

                cache.putIfGeneration(makeEntry("secret-sha256-hash-3", "acc-3"), gen)
                cache.size() shouldBe 2

                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.SESSION, "secret-sha256-hash-2", 2_000L, 60_000L)
                    .shouldBe(PrincipalCacheLookup.Miss)
                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.SESSION, "secret-sha256-hash-1", 2_000L, 60_000L)
                    .shouldBeInstanceOf<PrincipalCacheLookup.Hit>()
                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.SESSION, "secret-sha256-hash-3", 2_000L, 60_000L)
                    .shouldBeInstanceOf<PrincipalCacheLookup.Hit>()
            }

            test("strictly enforces credential expiresAtMs <= now and cache TTL expiration") {
                val cache = BoundedPrincipalCache(ttlMs = 15_000L, maxEntries = 8)
                val gen = cache.currentGeneration()

                cache.putIfGeneration(
                    CachedAuthEntry(
                        kind = CachedCredentialKind.API_KEY,
                        credentialHash = "khash-exp",
                        credentialId = "k-exp",
                        accountId = "acc-1",
                        principal = MiruPrincipal(accountId = "acc-1", username = "u1", role = MiruRole.USER),
                        credentialExpiresAtMs = 5_000L,
                        cachedAtMs = 1_000L,
                        cacheExpiresAtMs = 16_000L,
                        lastActivityAtMs = 1_000L,
                    ),
                    gen,
                )

                // Before expiry -> Hit
                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.API_KEY, "khash-exp", 4_999L, 60_000L)
                    .shouldBeInstanceOf<PrincipalCacheLookup.Hit>()

                // Exactly at expiresAtMs -> ExpiredCredential and evicted immediately
                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.API_KEY, "khash-exp", 5_000L, 60_000L)
                    .shouldBeInstanceOf<PrincipalCacheLookup.ExpiredCredential>()
                cache.size() shouldBe 0

                // Cache TTL expiration check
                cache.putIfGeneration(
                    CachedAuthEntry(
                        kind = CachedCredentialKind.SESSION,
                        credentialHash = "shash-ttl",
                        credentialId = "s-ttl",
                        accountId = "acc-1",
                        principal = MiruPrincipal(accountId = "acc-1", username = "u1", role = MiruRole.USER),
                        credentialExpiresAtMs = 100_000L,
                        cachedAtMs = 1_000L,
                        cacheExpiresAtMs = 16_000L,
                        lastActivityAtMs = 1_000L,
                    ),
                    cache.currentGeneration(),
                )
                cache
                    .getAndClaimActivityUpdate(CachedCredentialKind.SESSION, "shash-ttl", 16_000L, 60_000L)
                    .shouldBe(PrincipalCacheLookup.Miss)
                cache.size() shouldBe 0
            }

            test("disables caching when ttlMs or maxEntries is zero") {
                val zeroTtl = BoundedPrincipalCache(ttlMs = 0L, maxEntries = 10)
                val zeroCap = BoundedPrincipalCache(ttlMs = 15_000L, maxEntries = 0)
                val entry =
                    CachedAuthEntry(
                        kind = CachedCredentialKind.SESSION,
                        credentialHash = "shash",
                        credentialId = "s1",
                        accountId = "acc-1",
                        principal = MiruPrincipal(accountId = "acc-1", username = "u1", role = MiruRole.USER),
                        credentialExpiresAtMs = 100_000L,
                        cachedAtMs = 1_000L,
                        cacheExpiresAtMs = 1_000L,
                        lastActivityAtMs = 1_000L,
                    )
                zeroTtl.putIfGeneration(entry, zeroTtl.currentGeneration())
                zeroCap.putIfGeneration(entry.copy(cacheExpiresAtMs = 16_000L), zeroCap.currentGeneration())
                zeroTtl.size() shouldBe 0
                zeroCap.size() shouldBe 0
            }
        }

        context("AuthService single-query lookup, write throttling, and cache hits") {
            test("cookie auth uses single query on miss, zero DB queries on hit, and throttles touchLastSeen") {
                createHotPathHarness(
                    lastSeenTouchThresholdMs = 60_000L,
                    principalCacheTtlMs = 15_000L,
                ).use { h ->
                    seedMediaUsers(h)
                    val admin = h.authService.setupInitialAdmin("admin", "StrongPass123!").shouldBeRight()
                    h.authService.setLinkedMediaUsers(admin.principal.accountId, setOf("mu-1")).shouldBeRight()

                    h.accountRepo.resetAuthReadCounts()
                    h.sessionRepo.resetCounts()

                    // Cache miss at t=1_005_000 (5s after login): 1 single-query lookup, 0 legacy reads, 0 writes
                    h.nowMs.set(1_005_000L)
                    val p1 = h.authService.authenticateCookie(admin.signedToken).shouldBeRight()
                    p1.linkedMediaUserIds shouldBe setOf("mu-1")
                    h.sessionRepo.findContextCalls.get() shouldBe 1
                    h.sessionRepo.findByTokenHashCalls.get() shouldBe 0
                    h.accountRepo.findByIdCalls.get() shouldBe 0
                    h.accountRepo.findLinkedMediaUserIdsCalls.get() shouldBe 0
                    h.sessionRepo.touchLastSeenCalls.get() shouldBe 0

                    // Cache hit at t=1_010_000: 0 DB reads, 0 DB writes
                    h.nowMs.set(1_010_000L)
                    h.authService.authenticateCookie(admin.signedToken).shouldBeRight() shouldBe p1
                    h.sessionRepo.findContextCalls.get() shouldBe 1
                    h.sessionRepo.touchLastSeenCalls.get() shouldBe 0

                    // Cache TTL expires at t=1_025_000, still inside 60s throttle window: 1 read, 0 writes
                    h.nowMs.set(1_025_000L)
                    h.authService.authenticateCookie(admin.signedToken).shouldBeRight() shouldBe p1
                    h.sessionRepo.findContextCalls.get() shouldBe 2
                    h.sessionRepo.touchLastSeenCalls.get() shouldBe 0

                    // At t=1_060_000 (60s after creation at 1_000_000): 1 read + 1 touchLastSeen write
                    h.nowMs.set(1_060_000L)
                    h.authService.authenticateCookie(admin.signedToken).shouldBeRight() shouldBe p1
                    h.sessionRepo.findContextCalls.get() shouldBe 3
                    h.sessionRepo.touchLastSeenCalls.get() shouldBe 1

                    val verifiedToken =
                        TokenCrypto.verifySignedSessionToken(admin.signedToken, h.authConfig.sessionSecret)!!
                    val tokenHash = TokenCrypto.sha256Hex(verifiedToken)
                    h.rawSessionRepo.findByTokenHash(tokenHash).shouldBeRight()?.lastSeenAtMs shouldBe 1_060_000L
                }
            }

            test("coalesces throttled lastUsedAtMs and lastSeenAtMs writes across cache hits and concurrent requests") {
                createHotPathHarness(
                    lastSeenTouchThresholdMs = 60_000L,
                    principalCacheTtlMs = 120_000L,
                ).use { h ->
                    val admin = h.authService.setupInitialAdmin("admin", "StrongPass123!").shouldBeRight()
                    val apiKey =
                        h.authService
                            .createApiKey(admin.principal.accountId, "bench-key", MiruRole.ADMIN)
                            .shouldBeRight()
                    h.apiKeyRepo.resetCounts()
                    h.accountRepo.resetAuthReadCounts()

                    // First API key call: lastUsedAtMs is null -> 1 context read + 1 updateLastUsed write
                    val p1 = h.authService.authenticateApiKey(apiKey.rawKey).shouldBeRight()
                    p1.role shouldBe MiruRole.ADMIN
                    h.apiKeyRepo.findContextCalls.get() shouldBe 1
                    h.apiKeyRepo.findByKeyHashCalls.get() shouldBe 0
                    h.accountRepo.findByIdCalls.get() shouldBe 0
                    h.accountRepo.findLinkedMediaUserIdsCalls.get() shouldBe 0
                    h.apiKeyRepo.updateLastUsedCalls.get() shouldBe 1
                    h.rawApiKeyRepo.findById(apiKey.record.id).shouldBeRight()?.lastUsedAtMs shouldBe 1_000_000L

                    // Next 10 calls inside throttle window -> 0 reads, 0 writes
                    h.nowMs.set(1_030_000L)
                    repeat(10) {
                        h.authService.authenticateApiKey(apiKey.rawKey).shouldBeRight() shouldBe p1
                    }
                    h.apiKeyRepo.findContextCalls.get() shouldBe 1
                    h.apiKeyRepo.updateLastUsedCalls.get() shouldBe 1

                    // 10 concurrent cache-hit requests right when 60s threshold elapses -> 0 reads, exactly 1 write!
                    h.nowMs.set(1_060_000L)
                    coroutineScope {
                        (1..10)
                            .map {
                                async {
                                    h.authService.authenticateApiKey(apiKey.rawKey).shouldBeRight()
                                }
                            }.awaitAll()
                    }
                    h.apiKeyRepo.findContextCalls.get() shouldBe 1
                    h.apiKeyRepo.updateLastUsedCalls.get() shouldBe 2
                    h.rawApiKeyRepo.findById(apiKey.record.id).shouldBeRight()?.lastUsedAtMs shouldBe 1_060_000L

                    // If a throttled DB write fails, the cache entry is invalidated and the error propagates
                    h.nowMs.set(1_120_000L)
                    h.apiKeyRepo.failNextUpdateLastUsed = true
                    h.authService.authenticateApiKey(apiKey.rawKey).shouldBeLeft().shouldBeInstanceOf<MiruError.PersistenceError>()
                    h.authService.principalCacheSize() shouldBe 0
                }
            }

            test("strictly rejects and deletes expired cached session and rejects expired cached API key before cache TTL") {
                createHotPathHarness(
                    sessionTtlMs = 5_000L,
                    principalCacheTtlMs = 60_000L,
                ).use { h ->
                    val admin = h.authService.setupInitialAdmin("admin", "StrongPass123!").shouldBeRight()
                    val apiKey =
                        h.authService
                            .createApiKey(admin.principal.accountId, "short-key", MiruRole.ADMIN, expiresAtMs = 1_005_000L)
                            .shouldBeRight()

                    h.authService.authenticateCookie(admin.signedToken).shouldBeRight()
                    h.authService.authenticateApiKey(apiKey.rawKey).shouldBeRight()
                    h.authService.principalCacheSize() shouldBe 2

                    // Jump to exact expiry timestamp while cache TTL (60s) is still active
                    h.nowMs.set(1_005_000L)
                    val sessErr = h.authService.authenticateCookie(admin.signedToken).shouldBeLeft()
                    sessErr.message shouldBe "Session expired"
                    val rawToken = TokenCrypto.verifySignedSessionToken(admin.signedToken, h.authConfig.sessionSecret)!!
                    h.rawSessionRepo.findByTokenHash(TokenCrypto.sha256Hex(rawToken)).shouldBeRight().shouldBeNull()

                    val keyErr = h.authService.authenticateApiKey(apiKey.rawKey).shouldBeLeft()
                    keyErr.message shouldBe "API key has expired"
                    h.authService.principalCacheSize() shouldBe 0
                }
            }
        }

        context("Immediate invalidation on auth, RBAC, and linking mutations") {
            test("logout, revokeApiKey, password change, account disable/role change/delete evict immediately") {
                createHotPathHarness(principalCacheTtlMs = 60_000L).use { h ->
                    seedMediaUsers(h)
                    val admin = h.authService.setupInitialAdmin("admin", "StrongPass123!").shouldBeRight()
                    val userWithLinks =
                        h.authService
                            .createAccount("viewer", "ViewerPass123!", role = MiruRole.ADMIN, linkedMediaUserIds = setOf("mu-1"))
                            .shouldBeRight()
                    val viewerLogin = h.authService.login("viewer", "ViewerPass123!").shouldBeRight()
                    val viewerKey =
                        h.authService
                            .createApiKey(userWithLinks.account.id, "viewer-key", MiruRole.ADMIN)
                            .shouldBeRight()

                    // Populate cache for all credentials
                    h.authService.authenticateCookie(viewerLogin.signedToken).shouldBeRight().role shouldBe MiruRole.ADMIN
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeRight().role shouldBe MiruRole.ADMIN

                    // 1. Role downgrade ADMIN -> USER: session deleted & API key effectiveRole drops immediately
                    h.authService
                        .updateAccount(userWithLinks.account.id, admin.principal.accountId, role = "USER")
                        .shouldBeRight()
                    h.authService.authenticateCookie(viewerLogin.signedToken).shouldBeLeft()
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeRight().role shouldBe MiruRole.USER

                    // 2. Role upgrade USER -> ADMIN takes effect immediately on cached API key
                    h.authService
                        .updateAccount(userWithLinks.account.id, admin.principal.accountId, role = "ADMIN")
                        .shouldBeRight()
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeRight().role shouldBe MiruRole.ADMIN

                    // 3. Account disable rejects cached session and API key immediately
                    val viewerLogin2 = h.authService.login("viewer", "ViewerPass123!").shouldBeRight()
                    h.authService.authenticateCookie(viewerLogin2.signedToken).shouldBeRight()
                    h.authService
                        .updateAccount(userWithLinks.account.id, admin.principal.accountId, disabled = true)
                        .shouldBeRight()
                    h.authService.authenticateCookie(viewerLogin2.signedToken).shouldBeLeft()
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeLeft().message shouldBe "Account is disabled"

                    // 4. Re-enable + setLinkedMediaUsers updates linkedMediaUserIds immediately
                    h.authService
                        .updateAccount(userWithLinks.account.id, admin.principal.accountId, disabled = false)
                        .shouldBeRight()
                    val viewerLogin3 = h.authService.login("viewer", "ViewerPass123!").shouldBeRight()
                    h.authService.authenticateCookie(viewerLogin3.signedToken).shouldBeRight().linkedMediaUserIds shouldBe setOf("mu-1")
                    h.authService.setLinkedMediaUsers(userWithLinks.account.id, setOf("mu-2")).shouldBeRight()
                    h.authService.authenticateCookie(viewerLogin3.signedToken).shouldBeRight().linkedMediaUserIds shouldBe setOf("mu-2")
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeRight().linkedMediaUserIds shouldBe setOf("mu-2")

                    // 5. changeOwnPassword invalidates prior session immediately
                    val rotated =
                        h.authService
                            .changeOwnPassword(
                                principal = h.authService.authenticateCookie(viewerLogin3.signedToken).shouldBeRight(),
                                currentPassword = "ViewerPass123!",
                                newPassword = "ViewerPass456!",
                            ).shouldBeRight()!!
                    h.authService.authenticateCookie(viewerLogin3.signedToken).shouldBeLeft()
                    h.authService.authenticateCookie(rotated.signedToken).shouldBeRight()

                    // 6. logout invalidates session immediately
                    h.authService.logout(rotated.signedToken).shouldBeRight()
                    h.authService.authenticateCookie(rotated.signedToken).shouldBeLeft()

                    // 7. revokeApiKey invalidates API key immediately with zero grace window
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeRight()
                    h.authService.revokeApiKey(viewerKey.record.id).shouldBeRight()
                    h.authService.authenticateApiKey(viewerKey.rawKey).shouldBeLeft().message shouldBe "API key has been revoked"

                    // 8. deleteAccount invalidates remaining credentials immediately
                    val deleteKey = h.authService.createApiKey(userWithLinks.account.id, "k2", MiruRole.USER).shouldBeRight()
                    h.authService.authenticateApiKey(deleteKey.rawKey).shouldBeRight()
                    h.authService.deleteAccount(userWithLinks.account.id, admin.principal.accountId).shouldBeRight()
                    h.authService.authenticateApiKey(deleteKey.rawKey).shouldBeLeft()
                }
            }

            test("EmailMediaUserLinker.relinkAll, setMediaUserLinkMode, OIDC login, SessionCleanupService, and invalidateAll evict cache") {
                val oidcAuthConfig =
                    AuthConfig(
                        mode = AuthMode.LOCAL,
                        sessionSecret = "super-secret-session-signing-key-32-chars",
                        oidc =
                            OidcConfig(
                                enabled = true,
                                issuerUrl = "https://idp.miru.local",
                                clientId = "miru",
                                redirectUri = "http://localhost:8080/api/v1/auth/oidc/callback",
                                adminGroups = setOf("admins"),
                                userGroups = setOf("users"),
                                autoLinkByEmail = true,
                            ),
                    )
                createHotPathHarness(authConfig = oidcAuthConfig, principalCacheTtlMs = 60_000L).use { h ->
                    seedMediaUsers(h)
                    val ssoAccount =
                        AuthAccountRecord(
                            id = "acc-oidc-1",
                            username = "alice_sso",
                            usernameNormalized = "alice_sso",
                            email = "alice@miru.local",
                            passwordHash = "",
                            role = MiruRole.USER.name,
                            authProvider = OidcAuthService.PROVIDER_OIDC,
                            externalSubject = "sub-alice",
                            mediaUserLinkMode = MediaUserLinkMode.AUTO.name,
                            roleMode = RoleMode.AUTO.name,
                            emailVerified = true,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        )
                    h.rawAccountRepo.insert(ssoAccount).shouldBeRight()
                    val login1 = h.authService.issueSessionForAccount(ssoAccount, emptySet()).shouldBeRight()
                    h.authService.authenticateCookie(login1.signedToken).shouldBeRight().linkedMediaUserIds shouldBe emptySet()

                    // EmailMediaUserLinker.relinkAll links mu-1 and immediately invalidates cached principal
                    h.emailLinker.relinkAll().shouldBeRight() shouldBe 1
                    h.authService.authenticateCookie(login1.signedToken).shouldBeRight().linkedMediaUserIds shouldBe setOf("mu-1")

                    // setMediaUserLinkMode invalidates cache
                    h.authService.setMediaUserLinkMode(ssoAccount.id, MediaUserLinkMode.MANUAL).shouldBeRight()
                    h.authService.setLinkedMediaUsers(ssoAccount.id, setOf("mu-2")).shouldBeRight()
                    h.authService.authenticateCookie(login1.signedToken).shouldBeRight().linkedMediaUserIds shouldBe setOf("mu-2")
                    h.authService.setMediaUserLinkMode(ssoAccount.id, MediaUserLinkMode.AUTO).shouldBeRight()
                    h.authService.authenticateCookie(login1.signedToken).shouldBeRight().linkedMediaUserIds shouldBe setOf("mu-1")

                    // OidcAuthService.handleCallback updating groups/role immediately invalidates existing cached session
                    val oidcClient = mockk<OidcClient>()
                    coEvery { oidcClient.getDiscovery() } returns
                        OidcDiscoveryDocument(
                            issuer = "https://idp.miru.local",
                            authorizationEndpoint = "https://idp.miru.local/auth",
                            tokenEndpoint = "https://idp.miru.local/token",
                            jwksUri = "https://idp.miru.local/jwks",
                        ).right()
                    coEvery { oidcClient.exchangeCode(any(), any()) } returns
                        OidcTokenResponse(accessToken = "at-1", idToken = "idt-1").right()
                    coEvery { oidcClient.verifyIdToken("idt-1", any()) } returns
                        OidcIdTokenClaims(
                            subject = "sub-alice",
                            issuer = "https://idp.miru.local",
                            audiences = listOf("miru"),
                            preferredUsername = "alice_sso",
                            email = "alice@miru.local",
                            emailVerified = true,
                            groups = setOf("admins"),
                            expiresAtEpochSec = 2_000_000L,
                        ).right()

                    val oidcService =
                        OidcAuthService(
                            oidcClient = oidcClient,
                            authService = h.authService,
                            accountRepository = h.accountRepo,
                            emailLinker = h.emailLinker,
                            authConfig = oidcAuthConfig,
                            clock = { h.nowMs.get() },
                        )
                    val start = oidcService.startAuthorization().shouldBeRight()
                    oidcService
                        .handleCallback(
                            code = "code-1",
                            state = start.state,
                            txCookie = start.txCookieValue,
                            errorParam = null,
                            errorDescription = null,
                        ).shouldBeRight()

                    h.authService.authenticateCookie(login1.signedToken).shouldBeRight().role shouldBe MiruRole.ADMIN

                    // SessionCleanupService evicts expired cached entries
                    val shortSess =
                        AuthSessionRecord(
                            id = "sess-short",
                            accountId = ssoAccount.id,
                            tokenHash = TokenCrypto.sha256Hex("raw-short-token"),
                            createdAtMs = 1_000L,
                            expiresAtMs = 1_005_000L,
                            lastSeenAtMs = 1_000L,
                        )
                    h.rawSessionRepo.insert(shortSess).shouldBeRight()
                    val signedShort = TokenCrypto.signSessionToken("raw-short-token", h.authConfig.sessionSecret)
                    h.authService.authenticateCookie(signedShort).shouldBeRight()
                    h.nowMs.set(1_005_000L)
                    val cleanup =
                        SessionCleanupService(
                            sessionRepository = h.sessionRepo,
                            clock = { h.nowMs.get() },
                            onExpiredCleaned = { nowMs -> h.authService.evictExpiredSessions(nowMs) },
                        )
                    cleanup.cleanupOnce().shouldBeRight() shouldBe 1
                    h.authService.authenticateCookie(signedShort).shouldBeLeft()

                    // invalidateAll clears remaining cached principals
                    h.authService.authenticateCookie(login1.signedToken).shouldBeRight()
                    h.authService.principalCacheSize() shouldBe 1
                    h.authService.invalidateAll()
                    h.authService.principalCacheSize() shouldBe 0
                }
            }

            test("concurrent invalidation during in-flight cache-miss lookup prevents stale cache poisoning") {
                createHotPathHarness(principalCacheTtlMs = 60_000L).use { h ->
                    val admin = h.authService.setupInitialAdmin("admin", "StrongPass123!").shouldBeRight()
                    val user =
                        h.authService
                            .createAccount("racer", "RacerPass123!", role = MiruRole.ADMIN)
                            .shouldBeRight()
                    val login = h.authService.login("racer", "RacerPass123!").shouldBeRight()

                    val enteredDbLookup = CompletableDeferred<Unit>()
                    val allowDbLookupProceed = CompletableDeferred<Unit>()
                    h.sessionRepo.contextLookupHook = {
                        enteredDbLookup.complete(Unit)
                        allowDbLookupProceed.await()
                    }

                    coroutineScope {
                        val authJob = async { h.authService.authenticateCookie(login.signedToken) }
                        enteredDbLookup.await()
                        // Concurrent mutation runs and invalidates while authJob's DB read is paused
                        h.authService
                            .updateAccount(user.account.id, admin.principal.accountId, role = "USER")
                            .shouldBeRight()
                        h.sessionRepo.contextLookupHook = null
                        allowDbLookupProceed.complete(Unit)
                        yield()
                        authJob.await()
                    }

                    // Cache MUST NOT hold stale ADMIN entry after concurrent mutation
                    h.authService.principalCacheSize() shouldBe 0
                    h.authService.authenticateCookie(login.signedToken).shouldBeLeft()
                }
            }
        }
    })
