package app.hononeko.miru.auth

import app.hononeko.miru.MiruJson
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.CreateAccountRequest
import app.hononeko.miru.http.CreateApiKeyRequest
import app.hononeko.miru.http.CreateApiKeyResponse
import app.hononeko.miru.http.LoginRequest
import app.hononeko.miru.http.PrincipalResponse
import app.hononeko.miru.http.SetLinkedMediaUsersRequest
import app.hononeko.miru.http.SetupAdminRequest
import app.hononeko.miru.http.UpdateAccountRequest
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseDialect
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
import app.hononeko.miru.persistence.repository.ExposedUserPreferencesRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private class PgCountingAccountRepo(
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

    fun resetReadCounts() {
        findByIdCalls.set(0)
        findLinkedMediaUserIdsCalls.set(0)
    }
}

private class PgCountingSessionRepo(
    private val delegate: AuthSessionRepository,
) : AuthSessionRepository by delegate {
    val findByTokenHashCalls = AtomicInteger(0)
    val findContextCalls = AtomicInteger(0)
    val touchLastSeenCalls = AtomicInteger(0)

    override suspend fun findByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, AuthSessionRecord?> {
        findByTokenHashCalls.incrementAndGet()
        return delegate.findByTokenHash(tokenHash)
    }

    override suspend fun findSessionAuthContextByTokenHash(
        tokenHash: String,
    ): Either<MiruError.PersistenceError, SessionAuthContext?> {
        findContextCalls.incrementAndGet()
        return delegate.findSessionAuthContextByTokenHash(tokenHash)
    }

    override suspend fun touchLastSeen(
        id: String,
        lastSeenAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> {
        touchLastSeenCalls.incrementAndGet()
        return delegate.touchLastSeen(id, lastSeenAtMs)
    }

    fun resetCounts() {
        findByTokenHashCalls.set(0)
        findContextCalls.set(0)
        touchLastSeenCalls.set(0)
    }
}

private class PgCountingApiKeyRepo(
    private val delegate: ApiKeyRepository,
) : ApiKeyRepository by delegate {
    val findByKeyHashCalls = AtomicInteger(0)
    val findContextCalls = AtomicInteger(0)
    val updateLastUsedCalls = AtomicInteger(0)

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
        return delegate.updateLastUsed(id, lastUsedAtMs)
    }

    fun resetCounts() {
        findByKeyHashCalls.set(0)
        findContextCalls.set(0)
        updateLastUsedCalls.set(0)
    }
}

class AuthHotPathPostgresIntegrationTest :
    FunSpec({
        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("miru_auth_hotpath")
                .withUsername("miru")
                .withPassword("miru_test_password")

        var handle: DatabaseHandle? = null

        beforeSpec {
            container.start()
            handle =
                DatabaseFactory
                    .createAndMigrate(
                        DatabaseConfig.Postgres(
                            url = container.jdbcUrl,
                            user = container.username,
                            password = container.password,
                            maxPoolSize = 4,
                        ),
                    ).shouldBeRight()
        }

        afterSpec {
            handle?.close()
            if (container.isRunning) {
                container.stop()
            }
        }

        test("PostgreSQL end-to-end auth routes consolidate lookups, throttle writes, cache principals, and invalidate immediately") {
            val db = checkNotNull(handle)
            db.dialect shouldBe DatabaseDialect.POSTGRES

            val nowMs = AtomicLong(2_000_000L)
            val rawAccountRepo = ExposedAuthAccountRepository(db)
            val rawSessionRepo = ExposedAuthSessionRepository(db)
            val rawApiKeyRepo = ExposedApiKeyRepository(db)
            val accountRepo = PgCountingAccountRepo(rawAccountRepo)
            val sessionRepo = PgCountingSessionRepo(rawSessionRepo)
            val apiKeyRepo = PgCountingApiKeyRepo(rawApiKeyRepo)
            val serverRepo = ExposedMediaServerRepository(db)
            val userRepo = ExposedUserRepository(db)
            val prefsRepo = ExposedUserPreferencesRepository(db)

            serverRepo
                .upsert(
                    MediaServerRecord(
                        id = "srv-pg-1",
                        name = "PG Plex",
                        serverType = "PLEX",
                        machineId = "mach-pg-1",
                        baseUrl = "http://plex.pg:32400",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ).shouldBeRight()
            userRepo
                .upsert(
                    UserRecord(
                        id = "mu-pg-1",
                        serverId = "srv-pg-1",
                        externalUserId = "ext-pg-1",
                        username = "pguser1",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ).shouldBeRight()

            val authConfig =
                AuthConfig(
                    mode = AuthMode.LOCAL,
                    sessionSecret = "pg-integration-session-secret-32-chars",
                )
            val emailLinker = EmailMediaUserLinker(accountRepo, userRepo, authConfig, clock = { nowMs.get() })
            val authService =
                AuthService(
                    accountRepository = accountRepo,
                    sessionRepository = sessionRepo,
                    apiKeyRepository = apiKeyRepo,
                    userRepository = userRepo,
                    passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
                    authConfig = authConfig,
                    sessionTtlMs = 3_600_000L,
                    clock = { nowMs.get() },
                    preferencesRepository = prefsRepo,
                    emailLinker = emailLinker,
                    lastSeenTouchThresholdMs = 60_000L,
                    principalCacheTtlMs = 15_000L,
                    principalCacheMaxEntries = 64,
                )
            val appConfig =
                AppConfig(
                    server = ServerConfig(host = "127.0.0.1", port = 8080),
                    database = DatabaseConfig.Postgres(container.jdbcUrl, container.username, container.password, 4),
                    plex = PlexConfig(),
                    session = SessionConfig(),
                    auth = authConfig,
                )

            testApplication {
                application {
                    miruModule(config = appConfig, authService = authService)
                }

                val setupResp =
                    client.post("/api/v1/auth/setup") {
                        contentType(ContentType.Application.Json)
                        setBody(MiruJson.encodeToString(SetupAdminRequest("pgadmin", "StrongPass123!")))
                    }
                setupResp.status shouldBe HttpStatusCode.Created
                val adminCookie = extractSessionCookie(setupResp.headers.getAll(HttpHeaders.SetCookie))!!

                val createViewerResp =
                    client.post("/api/v1/admin/accounts") {
                        cookie(SESSION_COOKIE_NAME, adminCookie)
                        contentType(ContentType.Application.Json)
                        setBody(
                            MiruJson.encodeToString(
                                CreateAccountRequest(
                                    username = "pgviewer",
                                    password = "ViewerPass123!",
                                    role = MiruRole.ADMIN,
                                    linkedMediaUserIds = setOf("mu-pg-1"),
                                ),
                            ),
                        )
                    }
                createViewerResp.status shouldBe HttpStatusCode.Created
                val viewerAccount = MiruJson.decodeFromString<app.hononeko.miru.http.AccountResponse>(createViewerResp.bodyAsText())

                val loginResp =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(MiruJson.encodeToString(LoginRequest("pgviewer", "ViewerPass123!")))
                    }
                loginResp.status shouldBe HttpStatusCode.OK
                val viewerCookie = extractSessionCookie(loginResp.headers.getAll(HttpHeaders.SetCookie))!!

                val keyResp =
                    client.post("/api/v1/admin/api-keys") {
                        cookie(SESSION_COOKIE_NAME, adminCookie)
                        contentType(ContentType.Application.Json)
                        setBody(
                            MiruJson.encodeToString(
                                CreateApiKeyRequest(
                                    name = "pg-key",
                                    role = MiruRole.ADMIN,
                                    accountId = viewerAccount.id,
                                ),
                            ),
                        )
                    }
                keyResp.status shouldBe HttpStatusCode.Created
                val createdKey = MiruJson.decodeFromString<CreateApiKeyResponse>(keyResp.bodyAsText())

                accountRepo.resetReadCounts()
                sessionRepo.resetCounts()
                apiKeyRepo.resetCounts()

                // Cookie cache miss -> 1 single-query lookup, 0 writes inside throttle window
                nowMs.set(2_005_000L)
                val me1 = client.get("/api/v1/auth/me") { cookie(SESSION_COOKIE_NAME, viewerCookie) }
                me1.status shouldBe HttpStatusCode.OK
                MiruJson.decodeFromString<PrincipalResponse>(me1.bodyAsText()).linkedMediaUserIds shouldBe setOf("mu-pg-1")
                sessionRepo.findContextCalls.get() shouldBe 1
                sessionRepo.findByTokenHashCalls.get() shouldBe 0
                accountRepo.findByIdCalls.get() shouldBe 0
                accountRepo.findLinkedMediaUserIdsCalls.get() shouldBe 0
                sessionRepo.touchLastSeenCalls.get() shouldBe 0

                // Cookie cache hit -> 0 reads, 0 writes
                repeat(5) {
                    client.get("/api/v1/auth/me") { cookie(SESSION_COOKIE_NAME, viewerCookie) }.status shouldBe HttpStatusCode.OK
                }
                sessionRepo.findContextCalls.get() shouldBe 1
                sessionRepo.touchLastSeenCalls.get() shouldBe 0

                // API key: first call writes null -> now, repeated calls hit cache with 0 reads & 0 writes
                val keyMe1 = client.get("/api/v1/auth/me") { header("X-Api-Key", createdKey.rawKey) }
                keyMe1.status shouldBe HttpStatusCode.OK
                apiKeyRepo.findContextCalls.get() shouldBe 1
                apiKeyRepo.updateLastUsedCalls.get() shouldBe 1
                repeat(5) {
                    client.get("/api/v1/auth/me") { header("X-Api-Key", createdKey.rawKey) }.status shouldBe HttpStatusCode.OK
                }
                apiKeyRepo.findContextCalls.get() shouldBe 1
                apiKeyRepo.updateLastUsedCalls.get() shouldBe 1

                // Cross 60s threshold (since last activity at 2_000_000 / 2_005_000) -> 1 touchLastSeen + 1 updateLastUsed
                nowMs.set(2_065_000L)
                client.get("/api/v1/auth/me") { cookie(SESSION_COOKIE_NAME, viewerCookie) }.status shouldBe HttpStatusCode.OK
                client.get("/api/v1/auth/me") { header("X-Api-Key", createdKey.rawKey) }.status shouldBe HttpStatusCode.OK
                sessionRepo.touchLastSeenCalls.get() shouldBe 1
                apiKeyRepo.updateLastUsedCalls.get() shouldBe 2

                // Immediate invalidation: unlink media users + role downgrade + disable + revoke key + logout
                client.put("/api/v1/admin/accounts/${viewerAccount.id}/media-users") {
                    cookie(SESSION_COOKIE_NAME, adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(MiruJson.encodeToString(SetLinkedMediaUsersRequest(emptySet())))
                }.status shouldBe HttpStatusCode.OK
                val keyAfterUnlink =
                    MiruJson.decodeFromString<PrincipalResponse>(
                        client.get("/api/v1/auth/me") { header("X-Api-Key", createdKey.rawKey) }.bodyAsText(),
                    )
                keyAfterUnlink.linkedMediaUserIds shouldBe emptySet()

                client.patch("/api/v1/admin/accounts/${viewerAccount.id}") {
                    cookie(SESSION_COOKIE_NAME, adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "USER")))
                }.status shouldBe HttpStatusCode.OK
                client.get("/api/v1/auth/me") { cookie(SESSION_COOKIE_NAME, viewerCookie) }.status shouldBe HttpStatusCode.Unauthorized
                val downgradedKeyPrincipal =
                    MiruJson.decodeFromString<PrincipalResponse>(
                        client.get("/api/v1/auth/me") { header("X-Api-Key", createdKey.rawKey) }.bodyAsText(),
                    )
                downgradedKeyPrincipal.role shouldBe MiruRole.USER

                client.delete("/api/v1/admin/api-keys/${createdKey.apiKey.id}") {
                    cookie(SESSION_COOKIE_NAME, adminCookie)
                }.status shouldBe HttpStatusCode.NoContent
                client.get("/api/v1/auth/me") { header("X-Api-Key", createdKey.rawKey) }.status shouldBe HttpStatusCode.Unauthorized

                client.post("/api/v1/auth/logout") {
                    cookie(SESSION_COOKIE_NAME, adminCookie)
                }.status shouldBe HttpStatusCode.NoContent
                client.get("/api/v1/auth/me") { cookie(SESSION_COOKIE_NAME, adminCookie) }.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })

private fun extractSessionCookie(headers: List<String>?): String? {
    if (headers == null) return null
    val prefix = "$SESSION_COOKIE_NAME="
    for (header in headers) {
        val start = header.indexOf(prefix)
        if (start >= 0) {
            val after = header.substring(start + prefix.length)
            val end = after.indexOf(';')
            val value = if (end >= 0) after.substring(0, end) else after
            if (value.isNotEmpty()) return value
        }
    }
    return null
}
