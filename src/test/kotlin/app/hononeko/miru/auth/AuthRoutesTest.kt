package app.hononeko.miru.auth

import app.hononeko.miru.MiruJson
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.http.AccountListResponse
import app.hononeko.miru.http.AccountResponse
import app.hononeko.miru.http.ApiKeyListResponse
import app.hononeko.miru.http.AuthStatusResponse
import app.hononeko.miru.http.CreateAccountRequest
import app.hononeko.miru.http.CreateApiKeyRequest
import app.hononeko.miru.http.CreateApiKeyResponse
import app.hononeko.miru.http.LoginRequest
import app.hononeko.miru.http.PrincipalResponse
import app.hononeko.miru.http.ProblemDetails
import app.hononeko.miru.http.SetLinkedMediaUsersRequest
import app.hononeko.miru.http.SetupAdminRequest
import app.hononeko.miru.http.respondEither
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private class TestAuthHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val nowMs: AtomicLong,
    val accountRepo: ExposedAuthAccountRepository,
    val sessionRepo: ExposedAuthSessionRepository,
    val apiKeyRepo: ExposedApiKeyRepository,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val hasher: PasswordHasher,
    val authService: AuthService,
    val appConfig: AppConfig,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createHarness(
    mode: AuthMode = AuthMode.LOCAL,
    sessionSecret: String = "super-secret-session-signing-key-32-chars",
    adminUsername: String? = null,
    adminPassword: String? = null,
    sessionTtlMs: Long = 60_000L,
): TestAuthHarness {
    val dir = Files.createTempDirectory("miru-auth-test-")
    val dbPath = dir.resolve("miru-auth.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val nowMs = AtomicLong(1_000_000L)
    val accountRepo = ExposedAuthAccountRepository(handle)
    val sessionRepo = ExposedAuthSessionRepository(handle)
    val apiKeyRepo = ExposedApiKeyRepository(handle)
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val hasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1))
    val authConfig =
        AuthConfig(
            mode = mode,
            sessionSecret = sessionSecret,
            adminUsername = adminUsername,
            adminPassword = adminPassword,
        )
    val service =
        AuthService(
            accountRepository = accountRepo,
            sessionRepository = sessionRepo,
            apiKeyRepository = apiKeyRepo,
            userRepository = userRepo,
            passwordHasher = hasher,
            authConfig = authConfig,
            sessionTtlMs = sessionTtlMs,
            clock = { nowMs.get() },
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "plex-secret-token"),
            session = SessionConfig(),
            auth = authConfig,
        )
    return TestAuthHarness(
        tempDir = dir,
        dbHandle = handle,
        nowMs = nowMs,
        accountRepo = accountRepo,
        sessionRepo = sessionRepo,
        apiKeyRepo = apiKeyRepo,
        serverRepo = serverRepo,
        userRepo = userRepo,
        hasher = hasher,
        authService = service,
        appConfig = appConfig,
    )
}

private fun extractCookieValue(
    setCookieHeader: String?,
    cookieName: String = SESSION_COOKIE_NAME,
): String? {
    if (setCookieHeader == null) return null
    val prefix = "$cookieName="
    val start = setCookieHeader.indexOf(prefix)
    if (start < 0) return null
    val after = setCookieHeader.substring(start + prefix.length)
    val end = after.indexOf(';')
    return if (end >= 0) after.substring(0, end) else after
}

private suspend fun seedMediaUser(
    harness: TestAuthHarness,
    userId: String = "media-user-1",
    externalUserId: String = "plex-42",
) {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Main Plex",
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
                id = userId,
                serverId = "srv-1",
                externalUserId = externalUserId,
                username = "plexuser",
                displayName = "Plex User",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
}

private fun ApplicationTestBuilder.setupAuthApp(harness: TestAuthHarness) {
    application {
        miruModule(config = harness.appConfig, authService = harness.authService)
    }
}

class AuthRoutesTest :
    FunSpec({
        test("bootstrapAdminIfConfigured creates admin idempotently when env credentials are provided") {
            createHarness(adminUsername = "bootadmin", adminPassword = "Boot-password-1234").use { harness ->
                harness.authService.bootstrapAdminIfConfigured().shouldBeRight() shouldBe true
                harness.authService.bootstrapAdminIfConfigured().shouldBeRight() shouldBe false
                harness.authService.isSetupRequired().shouldBeRight() shouldBe false

                val accounts = harness.accountRepo.findAll().shouldBeRight()
                accounts shouldHaveSize 1
                accounts.first().username shouldBe "bootadmin"
                accounts.first().role shouldBe "ADMIN"

                testApplication {
                    setupAuthApp(harness)
                    val statusResp = client.get("/api/v1/auth/status")
                    statusResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AuthStatusResponse>(statusResp.bodyAsText()) shouldBe
                        AuthStatusResponse(
                            authMode = "local",
                            setupRequired = false,
                            localEnabled = true,
                            oidcEnabled = false,
                        )

                    val setupResp =
                        client.post("/api/v1/auth/setup") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetupAdminRequest("other", "Another-strong-password-1")))
                        }
                    setupResp.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("first-run setup succeeds once, sets signed HttpOnly SameSite=Lax cookie, and locks afterward") {
            createHarness().use { harness ->
                testApplication {
                    setupAuthApp(harness)

                    val initialStatus =
                        MiruJson.decodeFromString<AuthStatusResponse>(client.get("/api/v1/auth/status").bodyAsText())
                    initialStatus shouldBe
                        AuthStatusResponse(
                            authMode = "local",
                            setupRequired = true,
                            localEnabled = true,
                            oidcEnabled = false,
                        )

                    val setupResponse =
                        client.post("/api/v1/auth/setup") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    SetupAdminRequest(
                                        username = "admin",
                                        password = "Admin-strong-password-123",
                                        email = "admin@miru.local",
                                    ),
                                ),
                            )
                        }
                    setupResponse.status shouldBe HttpStatusCode.Created
                    val setCookie = setupResponse.headers[HttpHeaders.SetCookie].shouldNotBeNull()
                    setCookie shouldContain "HttpOnly"
                    setCookie shouldContain "SameSite=Lax"
                    val cookieVal = extractCookieValue(setCookie).shouldNotBeNull()

                    val principal = MiruJson.decodeFromString<PrincipalResponse>(setupResponse.bodyAsText())
                    principal.username shouldBe "admin"
                    principal.role shouldBe MiruRole.ADMIN
                    principal.authSource shouldBe AuthSource.SESSION_COOKIE

                    val meResp =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, cookieVal)
                        }
                    meResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(meResp.bodyAsText()).accountId shouldBe principal.accountId

                    val secondSetup =
                        client.post("/api/v1/auth/setup") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetupAdminRequest("admin2", "Admin2-strong-password-1")))
                        }
                    secondSetup.status shouldBe HttpStatusCode.Conflict
                    secondSetup.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
                }
            }
        }

        test("enforces password complexity requirements on setup and account creation") {
            createHarness().use { harness ->
                val weakPasswords =
                    listOf(
                        "Short1a",
                        "alllowercase12",
                        "ALLUPPERCASE12",
                        "NoDigitsPasswordHere",
                    )
                for (weak in weakPasswords) {
                    val setupErr = harness.authService.setupInitialAdmin("admin", weak).shouldBeLeft()
                    setupErr.shouldBeInstanceOf<MiruError.ValidationError>().fieldErrors.keys shouldContainExactly setOf("password")
                }

                harness.authService.setupInitialAdmin("admin", "Valid-admin-pass-123").shouldBeRight()
                for (weak in weakPasswords) {
                    val accErr = harness.authService.createAccount("viewer", weak).shouldBeLeft()
                    accErr.shouldBeInstanceOf<MiruError.ValidationError>().fieldErrors.keys shouldContainExactly setOf("password")
                }
            }
        }

        test("login validates credentials, rejects disabled accounts, rejects tampered/expired cookies, and supports logout") {
            createHarness(sessionTtlMs = 30_000L).use { harness ->
                val activePass = "Active-strong-password-123"
                val disabledPass = "Disabled-strong-password-123"
                harness.authService.setupInitialAdmin("admin", activePass).shouldBeRight()
                harness.accountRepo
                    .insert(
                        AuthAccountRecord(
                            id = "acc-disabled",
                            username = "locked",
                            usernameNormalized = "locked",
                            passwordHash = harness.hasher.hash(disabledPass),
                            role = "USER",
                            disabled = true,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // Invalid password and unknown user both return 401
                    val badPassResp =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "wrong-password")))
                        }
                    badPassResp.status shouldBe HttpStatusCode.Unauthorized

                    val unknownUserResp =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("nobody", "wrong-password")))
                        }
                    unknownUserResp.status shouldBe HttpStatusCode.Unauthorized

                    // Disabled account rejected
                    val disabledResp =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("locked", disabledPass)))
                        }
                    disabledResp.status shouldBe HttpStatusCode.Unauthorized

                    // Valid login
                    val loginResp =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("ADMIN", activePass)))
                        }
                    loginResp.status shouldBe HttpStatusCode.OK
                    val cookieVal = extractCookieValue(loginResp.headers[HttpHeaders.SetCookie]).shouldNotBeNull()

                    // Tampered signature rejected before DB lookup
                    val dotIndex = cookieVal.indexOf('.')
                    val sigFirst = cookieVal[dotIndex + 1]
                    val tampered =
                        cookieVal.substring(0, dotIndex + 1) +
                            (if (sigFirst == 'A') 'Z' else 'A') +
                            cookieVal.substring(dotIndex + 2)
                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, tampered)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // Unauthenticated /me rejected
                    client.get("/api/v1/auth/me").status shouldBe HttpStatusCode.Unauthorized

                    // Expired session rejected
                    harness.nowMs.addAndGet(31_000L)
                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, cookieVal)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // Fresh login then logout invalidates session
                    val loginResp2 =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", activePass)))
                        }
                    loginResp2.status shouldBe HttpStatusCode.OK
                    val cookieVal2 = extractCookieValue(loginResp2.headers[HttpHeaders.SetCookie]).shouldNotBeNull()

                    val logoutResp =
                        client.post("/api/v1/auth/logout") {
                            cookie(SESSION_COOKIE_NAME, cookieVal2)
                        }
                    logoutResp.status shouldBe HttpStatusCode.NoContent
                    val clearedHeader = logoutResp.headers[HttpHeaders.SetCookie].shouldNotBeNull()
                    clearedHeader shouldContain "Max-Age=0"

                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, cookieVal2)
                        }.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        test("enforces ADMIN vs USER RBAC, media user linking, and self/last-admin deletion guards") {
            createHarness().use { harness ->
                seedMediaUser(harness, userId = "mu-alice")
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // Create a USER account linked to mu-alice
                    val createUserResp =
                        client.post("/api/v1/admin/accounts") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateAccountRequest(
                                        username = "alice",
                                        password = "Alice-password-123",
                                        email = "alice@miru.local",
                                        role = MiruRole.USER,
                                        linkedMediaUserIds = setOf("mu-alice"),
                                    ),
                                ),
                            )
                        }
                    createUserResp.status shouldBe HttpStatusCode.Created
                    val createdAlice = MiruJson.decodeFromString<AccountResponse>(createUserResp.bodyAsText())
                    createdAlice.linkedMediaUserIds shouldContainExactly listOf("mu-alice")

                    // Unknown media user returns 404
                    val missingLinkResp =
                        client.put("/api/v1/admin/accounts/${createdAlice.id}/media-users") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetLinkedMediaUsersRequest(setOf("missing-user"))))
                        }
                    missingLinkResp.status shouldBe HttpStatusCode.NotFound

                    // Log in as USER alice
                    val aliceLogin =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("alice", "Alice-password-123")))
                        }
                    val aliceCookie = extractCookieValue(aliceLogin.headers[HttpHeaders.SetCookie]).shouldNotBeNull()

                    // USER can access /auth/me, denied on /admin/*
                    val aliceMeResp =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, aliceCookie)
                        }
                    aliceMeResp.status shouldBe HttpStatusCode.OK
                    val alicePrincipalBody = MiruJson.decodeFromString<PrincipalResponse>(aliceMeResp.bodyAsText())
                    alicePrincipalBody.role shouldBe MiruRole.USER
                    alicePrincipalBody.linkedMediaUserIds shouldContainExactly listOf("mu-alice")

                    val forbiddenAdminResp =
                        client.get("/api/v1/admin/accounts") {
                            cookie(SESSION_COOKIE_NAME, aliceCookie)
                        }
                    forbiddenAdminResp.status shouldBe HttpStatusCode.Forbidden
                    forbiddenAdminResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // MiruPrincipal scoping helpers
                    val adminPrincipal = adminLogin.principal
                    adminPrincipal.isAdmin() shouldBe true
                    adminPrincipal.requireAdmin().shouldBeRight()
                    adminPrincipal.canAccessMediaUser("any-user") shouldBe true
                    adminPrincipal.requireMediaUserAccess("any-user").shouldBeRight()

                    val userPrincipal =
                        MiruPrincipal(
                            accountId = createdAlice.id,
                            username = "alice",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("mu-alice"),
                        )
                    userPrincipal.isAdmin() shouldBe false
                    userPrincipal.requireAdmin().shouldBeLeft()
                    userPrincipal.canAccessMediaUser("mu-alice") shouldBe true
                    userPrincipal.requireMediaUserAccess("mu-alice").shouldBeRight()
                    userPrincipal.canAccessMediaUser("mu-bob") shouldBe false
                    userPrincipal.requireMediaUserAccess("mu-bob").shouldBeLeft()

                    // Admin cannot delete self (last admin check too)
                    client
                        .delete("/api/v1/admin/accounts/${adminLogin.principal.accountId}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Conflict

                    // Replace links to empty, list accounts, and delete USER account
                    val clearLinksResp =
                        client.put("/api/v1/admin/accounts/${createdAlice.id}/media-users") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetLinkedMediaUsersRequest(emptySet())))
                        }
                    clearLinksResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AccountResponse>(clearLinksResp.bodyAsText()).linkedMediaUserIds shouldHaveSize 0

                    val listResp =
                        client.get("/api/v1/admin/accounts") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    listResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AccountListResponse>(listResp.bodyAsText()).accounts shouldHaveSize 2

                    client
                        .delete("/api/v1/admin/accounts/${createdAlice.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        test("API keys support X-Api-Key and Bearer auth, role clamping, expiration, revocation, and secret redaction") {
            createHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                val userAccount =
                    harness.authService
                        .createAccount(
                            username = "viewer",
                            password = "Viewer-password-123",
                            role = MiruRole.USER,
                        ).shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // Create ADMIN API key for admin account
                    val createAdminKeyResp =
                        client.post("/api/v1/admin/api-keys") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateApiKeyRequest(
                                        name = "Automation Key",
                                        role = MiruRole.ADMIN,
                                        expiresAtMs = harness.nowMs.get() + 10_000L,
                                    ),
                                ),
                            )
                        }
                    createAdminKeyResp.status shouldBe HttpStatusCode.Created
                    val createdAdminKey = MiruJson.decodeFromString<CreateApiKeyResponse>(createAdminKeyResp.bodyAsText())
                    createdAdminKey.rawKey shouldStartWith "miru_"
                    createdAdminKey.apiKey.keyPrefix shouldBe createdAdminKey.rawKey.take(12)

                    // Create key labeled ADMIN on a USER account -> effective role must be clamped to USER
                    val createClampedKeyResp =
                        client.post("/api/v1/admin/api-keys") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateApiKeyRequest(
                                        accountId = userAccount.account.id,
                                        name = "Viewer Key",
                                        role = MiruRole.ADMIN,
                                    ),
                                ),
                            )
                        }
                    createClampedKeyResp.status shouldBe HttpStatusCode.Created
                    val clampedKey = MiruJson.decodeFromString<CreateApiKeyResponse>(createClampedKeyResp.bodyAsText())

                    // List keys must never contain raw keys or key hashes
                    val listKeysResp =
                        client.get("/api/v1/admin/api-keys") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    listKeysResp.status shouldBe HttpStatusCode.OK
                    val listBody = listKeysResp.bodyAsText()
                    listBody shouldNotContain createdAdminKey.rawKey
                    listBody shouldNotContain clampedKey.rawKey
                    val listedKeys = MiruJson.decodeFromString<ApiKeyListResponse>(listBody).apiKeys
                    listedKeys shouldHaveSize 2

                    // Authenticate with X-Api-Key
                    val meWithHeader =
                        client.get("/api/v1/auth/me") {
                            header("X-Api-Key", createdAdminKey.rawKey)
                        }
                    meWithHeader.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(meWithHeader.bodyAsText()).authSource shouldBe AuthSource.API_KEY

                    // Authenticate with Authorization: Bearer
                    val adminWithBearer =
                        client.get("/api/v1/admin/accounts") {
                            header(HttpHeaders.Authorization, "Bearer ${createdAdminKey.rawKey}")
                        }
                    adminWithBearer.status shouldBe HttpStatusCode.OK

                    // Clamped USER key can call /auth/me (as USER) but gets 403 on /admin/accounts
                    val clampedMe =
                        client.get("/api/v1/auth/me") {
                            header("X-Api-Key", clampedKey.rawKey)
                        }
                    clampedMe.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(clampedMe.bodyAsText()).role shouldBe MiruRole.USER

                    client
                        .get("/api/v1/admin/accounts") {
                            header(HttpHeaders.Authorization, "Bearer ${clampedKey.rawKey}")
                        }.status shouldBe HttpStatusCode.Forbidden

                    // Revoke clamped key -> 401 afterward
                    client
                        .delete("/api/v1/admin/api-keys/${clampedKey.apiKey.id}") {
                            header("X-Api-Key", createdAdminKey.rawKey)
                        }.status shouldBe HttpStatusCode.NoContent

                    client
                        .get("/api/v1/auth/me") {
                            header("X-Api-Key", clampedKey.rawKey)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // Advance clock past admin key expiry -> 401
                    harness.nowMs.addAndGet(15_000L)
                    client
                        .get("/api/v1/auth/me") {
                            header("X-Api-Key", createdAdminKey.rawKey)
                        }.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        test("AUTH_MODE=none bypasses auth as DEV_BYPASS admin and system endpoints remain unauthenticated") {
            createHarness(mode = AuthMode.NONE, sessionSecret = "").use { harness ->
                testApplication {
                    application {
                        miruModule(config = harness.appConfig, authService = harness.authService)
                        routing {
                            authenticated(authService = harness.authService, authMode = AuthMode.NONE, requiredRole = MiruRole.ADMIN) {
                                get("/test/protected") {
                                    call.respondEither(call.requireMiruPrincipal().map { it.username })
                                }
                            }
                        }
                    }

                    client.get("/healthz").status shouldBe HttpStatusCode.OK
                    client.get("/readyz").status shouldBe HttpStatusCode.OK
                    client.get("/api/v1/openapi.yaml").status shouldBe HttpStatusCode.OK

                    val meResp = client.get("/api/v1/auth/me")
                    meResp.status shouldBe HttpStatusCode.OK
                    val principal = MiruJson.decodeFromString<PrincipalResponse>(meResp.bodyAsText())
                    principal.accountId shouldBe "dev-admin"
                    principal.role shouldBe MiruRole.ADMIN
                    principal.authSource shouldBe AuthSource.DEV_BYPASS

                    val adminResp = client.get("/api/v1/admin/accounts")
                    adminResp.status shouldBe HttpStatusCode.OK

                    val customResp = client.get("/test/protected")
                    customResp.status shouldBe HttpStatusCode.OK
                    customResp.bodyAsText() shouldContain "local-dev"
                }
            }

            // Also verify system endpoints are unauthenticated when AUTH_MODE=local
            createHarness(mode = AuthMode.LOCAL).use { harness ->
                testApplication {
                    setupAuthApp(harness)
                    client.get("/healthz").status shouldBe HttpStatusCode.OK
                    client.get("/readyz").status shouldBe HttpStatusCode.OK
                    client.get("/api/v1/openapi.yaml").status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("redacts secrets from toString(), AuthConfig, persistence records, DTOs, and error responses") {
            createHarness(adminUsername = "admin", adminPassword = "Top-secret-password-999").use { harness ->
                val rawPassword = "User-super-secret-password1"
                val created =
                    harness.authService
                        .createAccount(username = "secretcheck", password = rawPassword, role = MiruRole.ADMIN)
                        .shouldBeRight()
                val login = harness.authService.login("secretcheck", rawPassword).shouldBeRight()
                val apiKey =
                    harness.authService
                        .createApiKey(accountId = created.account.id, name = "Key", role = MiruRole.ADMIN)
                        .shouldBeRight()

                val authConfigText = harness.appConfig.auth.toString()
                authConfigText shouldNotContain "Top-secret-password-999"
                authConfigText shouldNotContain harness.appConfig.auth.sessionSecret

                val accountRecordText = created.account.toString()
                accountRecordText shouldNotContain created.account.passwordHash
                accountRecordText shouldNotContain rawPassword

                val sessionRecords = harness.sessionRepo.findByTokenHash(TokenCrypto.sha256Hex("nonexistent"))
                sessionRecords.shouldBeRight().shouldBeNull()

                val loginText = login.toString()
                loginText shouldNotContain login.signedToken

                val apiKeyText = apiKey.toString()
                apiKeyText shouldNotContain apiKey.rawKey
                apiKeyText shouldNotContain apiKey.record.keyHash

                val setupDto = SetupAdminRequest("u", rawPassword)
                setupDto.toString() shouldNotContain rawPassword
                LoginRequest("u", rawPassword).toString() shouldNotContain rawPassword
                CreateAccountRequest("u", rawPassword).toString() shouldNotContain rawPassword

                testApplication {
                    setupAuthApp(harness)
                    val badAuth =
                        client.get("/api/v1/auth/me") {
                            header("X-Api-Key", "miru_wrong_secret_key_value")
                        }
                    badAuth.status shouldBe HttpStatusCode.Unauthorized
                    val problem = MiruJson.decodeFromString<ProblemDetails>(badAuth.bodyAsText())
                    problem.status shouldBe 401
                    badAuth.bodyAsText() shouldNotContain "miru_wrong_secret_key_value"
                }
            }
        }

        test("/api/v1/auth/me and /login include proxy avatarUrl when linked user has thumbUrl and null otherwise") {
            createHarness().use { harness ->
                harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-1",
                            name = "Main Plex",
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
                            id = "media-no-thumb",
                            serverId = "srv-1",
                            externalUserId = "plex-10",
                            username = "nothumb",
                            thumbUrl = null,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "media-with-thumb",
                            serverId = "srv-1",
                            externalUserId = "plex-11",
                            username = "withthumb",
                            thumbUrl = "https://plex.tv/users/secret-hash/avatar?X-Plex-Token=leak",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                harness.authService
                    .createAccount(
                        username = "alice",
                        password = "Alice-strong-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("media-no-thumb", "media-with-thumb"),
                    ).shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "bob",
                        password = "Bob-strong-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("media-no-thumb"),
                    ).shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // User with linked user having thumbUrl gets proxy avatarUrl (never raw Plex URL/token)
                    val aliceLogin =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("alice", "Alice-strong-password-123")))
                        }
                    aliceLogin.status shouldBe HttpStatusCode.OK
                    val aliceLoginBody = aliceLogin.bodyAsText()
                    aliceLoginBody shouldNotContain "plex.tv"
                    aliceLoginBody shouldNotContain "secret-hash"
                    MiruJson.decodeFromString<PrincipalResponse>(aliceLoginBody).avatarUrl shouldBe
                        "/api/v1/users/media-with-thumb/avatar"

                    val aliceCookie = extractCookieValue(aliceLogin.headers[HttpHeaders.SetCookie]).shouldNotBeNull()
                    val aliceMe =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, aliceCookie)
                        }
                    aliceMe.status shouldBe HttpStatusCode.OK
                    val aliceMeBody = aliceMe.bodyAsText()
                    aliceMeBody shouldNotContain "plex.tv"
                    MiruJson.decodeFromString<PrincipalResponse>(aliceMeBody).avatarUrl shouldBe
                        "/api/v1/users/media-with-thumb/avatar"

                    // User whose linked user has no thumbUrl gets null avatarUrl
                    val bobLogin =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("bob", "Bob-strong-password-123")))
                        }
                    val bobCookie = extractCookieValue(bobLogin.headers[HttpHeaders.SetCookie]).shouldNotBeNull()
                    val bobMe =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, bobCookie)
                        }
                    bobMe.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(bobMe.bodyAsText()).avatarUrl.shouldBeNull()

                    // Unlinked admin gets null avatarUrl
                    val adminLogin =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-123")))
                        }
                    val adminCookie = extractCookieValue(adminLogin.headers[HttpHeaders.SetCookie]).shouldNotBeNull()
                    val adminMe =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, adminCookie)
                        }
                    adminMe.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(adminMe.bodyAsText()).avatarUrl.shouldBeNull()
                }
            }
        }
    })
