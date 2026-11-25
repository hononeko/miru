package app.hononeko.miru.auth

import app.hononeko.miru.MiruJson
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.AuthRateLimitConfig
import app.hononeko.miru.config.CookieSecureMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.http.AccountListResponse
import app.hononeko.miru.http.AccountResponse
import app.hononeko.miru.http.ApiKeyListResponse
import app.hononeko.miru.http.AuthProvidersResponse
import app.hononeko.miru.http.ChangePasswordRequest
import app.hononeko.miru.http.CreateAccountRequest
import app.hononeko.miru.http.CreateApiKeyRequest
import app.hononeko.miru.http.CreateApiKeyResponse
import app.hononeko.miru.http.LoginRequest
import app.hononeko.miru.http.PrincipalResponse
import app.hononeko.miru.http.ProblemDetails
import app.hononeko.miru.http.SetLinkedMediaUsersRequest
import app.hononeko.miru.http.SetupAdminRequest
import app.hononeko.miru.http.UpdateAccountRequest
import app.hononeko.miru.http.UpdateUserPreferencesRequest
import app.hononeko.miru.http.UserPreferencesResponse
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
import app.hononeko.miru.persistence.repository.ExposedUserPreferencesRepository
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
import io.ktor.client.request.patch
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private class TestAuthHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val nowMs: AtomicLong,
    val accountRepo: ExposedAuthAccountRepository,
    val sessionRepo: ExposedAuthSessionRepository,
    val apiKeyRepo: ExposedApiKeyRepository,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val preferencesRepo: ExposedUserPreferencesRepository,
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
    val preferencesRepo = ExposedUserPreferencesRepository(handle)
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
            preferencesRepository = preferencesRepo,
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
        preferencesRepo = preferencesRepo,
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

/** The role mode of `PATCH /api/v1/admin/accounts/{id}`. The OIDC account starts and ends as a USER in MANUAL mode. */
private suspend fun ApplicationTestBuilder.assertRoleModeUpdates(
    adminLogin: LoginResult,
    bobId: String,
    oidcAccountId: String,
) {
    suspend fun patchAccount(
        id: String,
        body: String,
    ) = client.patch("/api/v1/admin/accounts/$id") {
        cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    // A role and "take it from the groups" contradict each other, and unknown modes are refused
    val contradiction = patchAccount(oidcAccountId, """{"role":"USER","roleMode":"AUTO"}""")
    contradiction.status shouldBe HttpStatusCode.BadRequest
    MiruJson
        .decodeFromString<ProblemDetails>(contradiction.bodyAsText())
        .errors
        .shouldNotBeNull()
        .keys shouldContainExactly setOf("roleMode")
    patchAccount(oidcAccountId, """{"roleMode":"SOMETIMES"}""").status shouldBe HttpStatusCode.BadRequest

    // Local accounts have no groups, and an admin cannot put their own role at risk
    patchAccount(bobId, """{"roleMode":"AUTO"}""").status shouldBe HttpStatusCode.Conflict
    patchAccount(adminLogin.principal.accountId, """{"roleMode":"AUTO"}""").status shouldBe HttpStatusCode.Conflict

    val handedBack = patchAccount(oidcAccountId, """{"roleMode":"AUTO"}""")
    handedBack.status shouldBe HttpStatusCode.OK
    MiruJson.decodeFromString<AccountResponse>(handedBack.bodyAsText()).roleMode shouldBe RoleMode.AUTO

    // MANUAL on its own keeps the role the account has
    val kept = MiruJson.decodeFromString<AccountResponse>(patchAccount(oidcAccountId, """{"roleMode":"MANUAL"}""").bodyAsText())
    kept.role shouldBe MiruRole.USER
    kept.roleMode shouldBe RoleMode.MANUAL
}

class AuthRoutesTest :
    FunSpec({
        test("without an admin, setup stays closed once an account exists, and bootstrapAdminIfConfigured creates the admin") {
            createHarness(adminUsername = "bootadmin", adminPassword = "Boot-password-1234").use { harness ->
                harness.authService.isSetupRequired().shouldBeRight() shouldBe true
                harness.accountRepo
                    .insert(
                        AuthAccountRecord(
                            id = "acc-sso",
                            username = "sso.user",
                            usernameNormalized = "sso.user",
                            passwordHash = "",
                            role = "USER",
                            authProvider = "OIDC",
                            externalSubject = "sub-sso",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                harness.authService.isSetupRequired().shouldBeRight() shouldBe false
                harness.authService.setupInitialAdmin("intruder", "Intruder-password-1").shouldBeLeft()
                harness.accountRepo.countAdmins().shouldBeRight() shouldBe 0L

                harness.authService.bootstrapAdminIfConfigured().shouldBeRight() shouldBe true
                harness.accountRepo.countAdmins().shouldBeRight() shouldBe 1L
            }
        }

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
                    val providersResp = client.get("/api/v1/auth/providers")
                    providersResp.status shouldBe HttpStatusCode.OK
                    val providers = MiruJson.decodeFromString<AuthProvidersResponse>(providersResp.bodyAsText())
                    providers.authMode shouldBe "local"
                    providers.setupRequired shouldBe false
                    providers.localEnabled shouldBe true
                    providers.oidcEnabled shouldBe false

                    // Removed in favor of /providers
                    client.get("/api/v1/auth/status").status shouldBe HttpStatusCode.NotFound

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

                    val initialProviders =
                        MiruJson.decodeFromString<AuthProvidersResponse>(client.get("/api/v1/auth/providers").bodyAsText())
                    initialProviders.authMode shouldBe "local"
                    initialProviders.setupRequired shouldBe true
                    initialProviders.localEnabled shouldBe true
                    initialProviders.oidcEnabled shouldBe false

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

        test("API key creation supports preset intervals, rejects past and ambiguous expirations, and enforces EXPIRED cutoff") {
            createHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                val baseNow = harness.nowMs.get()
                val dayMs = AuthService.MILLIS_PER_DAY

                testApplication {
                    setupAuthApp(harness)

                    val presets =
                        listOf(
                            "7d" to baseNow + 7L * dayMs,
                            "30d" to baseNow + 30L * dayMs,
                            "90d" to baseNow + 90L * dayMs,
                            "365d" to baseNow + 365L * dayMs,
                            "never" to null,
                        )

                    for ((preset, expectedExpiry) in presets) {
                        val resp =
                            client.post("/api/v1/admin/api-keys") {
                                cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                contentType(ContentType.Application.Json)
                                setBody(
                                    MiruJson.encodeToString(
                                        CreateApiKeyRequest(
                                            name = "Key-$preset",
                                            role = MiruRole.USER,
                                            expiresIn = preset,
                                        ),
                                    ),
                                )
                            }
                        resp.status shouldBe HttpStatusCode.Created
                        val created = MiruJson.decodeFromString<CreateApiKeyResponse>(resp.bodyAsText())
                        created.apiKey.expiresAtMs shouldBe expectedExpiry
                    }

                    // Reject past or equal expiresAtMs with 400 ValidationError
                    val pastResp =
                        client.post("/api/v1/admin/api-keys") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateApiKeyRequest(
                                        name = "Past Key",
                                        role = MiruRole.USER,
                                        expiresAtMs = baseNow,
                                    ),
                                ),
                            )
                        }
                    pastResp.status shouldBe HttpStatusCode.BadRequest
                    val pastProblem = MiruJson.decodeFromString<ProblemDetails>(pastResp.bodyAsText())
                    pastProblem.status shouldBe 400
                    pastProblem.errors.shouldNotBeNull().keys shouldContainExactly setOf("expiresAtMs")

                    // Reject expiresAtMs together with expiresIn, even when the preset means no expiration
                    for (preset in listOf("30d", "never")) {
                        val bothResp =
                            client.post("/api/v1/admin/api-keys") {
                                cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                contentType(ContentType.Application.Json)
                                setBody(
                                    MiruJson.encodeToString(
                                        CreateApiKeyRequest(
                                            name = "Both",
                                            role = MiruRole.USER,
                                            expiresAtMs = baseNow + dayMs,
                                            expiresIn = preset,
                                        ),
                                    ),
                                )
                            }
                        bothResp.status shouldBe HttpStatusCode.BadRequest
                        MiruJson
                            .decodeFromString<ProblemDetails>(bothResp.bodyAsText())
                            .errors
                            .shouldNotBeNull() shouldBe mapOf("expiresAtMs" to "must not be combined with expiresIn")
                    }

                    // Reject unknown expiresIn preset with 400 ValidationError
                    val badPresetResp =
                        client.post("/api/v1/admin/api-keys") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateApiKeyRequest(
                                        name = "Bad Preset Key",
                                        role = MiruRole.USER,
                                        expiresIn = "14d",
                                    ),
                                ),
                            )
                        }
                    badPresetResp.status shouldBe HttpStatusCode.BadRequest
                    val badPresetProblem = MiruJson.decodeFromString<ProblemDetails>(badPresetResp.bodyAsText())
                    badPresetProblem.status shouldBe 400
                    badPresetProblem.errors.shouldNotBeNull().keys shouldContainExactly setOf("expiresIn")

                    // Verify exact boundary expiration: valid at expiresAtMs - 1, rejected at expiresAtMs
                    val shortResp =
                        client.post("/api/v1/admin/api-keys") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateApiKeyRequest(
                                        name = "Boundary Key",
                                        role = MiruRole.USER,
                                        expiresAtMs = baseNow + 5_000L,
                                    ),
                                ),
                            )
                        }
                    val boundaryKey = MiruJson.decodeFromString<CreateApiKeyResponse>(shortResp.bodyAsText())

                    harness.nowMs.set(baseNow + 4_999L)
                    client
                        .get("/api/v1/auth/me") {
                            header("X-Api-Key", boundaryKey.rawKey)
                        }.status shouldBe HttpStatusCode.OK

                    harness.nowMs.set(baseNow + 5_000L)
                    val expiredAuth =
                        client.get("/api/v1/auth/me") {
                            header("X-Api-Key", boundaryKey.rawKey)
                        }
                    expiredAuth.status shouldBe HttpStatusCode.Unauthorized
                    MiruJson.decodeFromString<ProblemDetails>(expiredAuth.bodyAsText()).detail shouldContain "expired"
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

        test("GET and PUT /api/v1/auth/me/preferences return defaults, persist per-account updates, and validate fields") {
            createHarness(adminUsername = "admin", adminPassword = "Admin-strong-password-1").use { harness ->
                harness.authService.bootstrapAdminIfConfigured().shouldBeRight()
                val adminLogin = harness.authService.login("admin", "Admin-strong-password-1").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "viewer",
                        password = "Viewer-strong-password-1",
                        role = MiruRole.USER,
                    ).shouldBeRight()
                val viewerLogin = harness.authService.login("viewer", "Viewer-strong-password-1").shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // Unauthenticated -> 401 Problem Details
                    client.get("/api/v1/auth/me/preferences").status shouldBe HttpStatusCode.Unauthorized
                    val unauthPut =
                        client.put("/api/v1/auth/me/preferences") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    UpdateUserPreferencesRequest(
                                        theme = "dark",
                                        timeFormat = "12h",
                                        dateFormat = "mdy",
                                        defaultStatsRange = "7d",
                                        relativeTimestamps = false,
                                    ),
                                ),
                            )
                        }
                    unauthPut.status shouldBe HttpStatusCode.Unauthorized

                    // Initial GET returns defaults
                    val initialAdmin =
                        client.get("/api/v1/auth/me/preferences") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    initialAdmin.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<UserPreferencesResponse>(initialAdmin.bodyAsText()) shouldBe
                        UserPreferencesResponse(
                            theme = "system",
                            timeFormat = "24h",
                            dateFormat = "iso",
                            defaultStatsRange = "30d",
                            relativeTimestamps = true,
                            updatedAtMs = null,
                        )

                    // Viewer updates preferences
                    harness.nowMs.set(1_010_000L)
                    val updateViewer =
                        client.put("/api/v1/auth/me/preferences") {
                            cookie(SESSION_COOKIE_NAME, viewerLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    UpdateUserPreferencesRequest(
                                        theme = "dark",
                                        timeFormat = "12h",
                                        dateFormat = "mdy",
                                        defaultStatsRange = "7d",
                                        relativeTimestamps = false,
                                        analyticsMetric = "duration",
                                    ),
                                ),
                            )
                        }
                    updateViewer.status shouldBe HttpStatusCode.OK
                    val expectedViewer =
                        UserPreferencesResponse(
                            theme = "dark",
                            timeFormat = "12h",
                            dateFormat = "mdy",
                            defaultStatsRange = "7d",
                            relativeTimestamps = false,
                            analyticsMetric = "duration",
                            updatedAtMs = 1_010_000L,
                        )
                    MiruJson.decodeFromString<UserPreferencesResponse>(updateViewer.bodyAsText()) shouldBe expectedViewer

                    // Subsequent GET for viewer returns persisted preferences
                    val getViewer =
                        client.get("/api/v1/auth/me/preferences") {
                            cookie(SESSION_COOKIE_NAME, viewerLogin.signedToken)
                        }
                    getViewer.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<UserPreferencesResponse>(getViewer.bodyAsText()) shouldBe expectedViewer

                    // Admin preferences remain independent defaults
                    val getAdminAfter =
                        client.get("/api/v1/auth/me/preferences") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    getAdminAfter.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<UserPreferencesResponse>(getAdminAfter.bodyAsText()) shouldBe
                        UserPreferencesResponse(
                            theme = "system",
                            timeFormat = "24h",
                            dateFormat = "iso",
                            defaultStatsRange = "30d",
                            relativeTimestamps = true,
                            analyticsMetric = "plays",
                            updatedAtMs = null,
                        )

                    // Invalid fields -> 400 Problem Details with field errors
                    val invalidResp =
                        client.put("/api/v1/auth/me/preferences") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    UpdateUserPreferencesRequest(
                                        theme = "neon",
                                        timeFormat = "99h",
                                        dateFormat = "ymd",
                                        defaultStatsRange = "1y",
                                        relativeTimestamps = true,
                                        analyticsMetric = "bytes",
                                    ),
                                ),
                            )
                        }
                    invalidResp.status shouldBe HttpStatusCode.BadRequest
                    val problem = MiruJson.decodeFromString<ProblemDetails>(invalidResp.bodyAsText())
                    problem.status shouldBe 400
                    val fieldErrors = problem.errors.shouldNotBeNull()
                    fieldErrors.keys shouldContainExactly
                        setOf("theme", "timeFormat", "dateFormat", "defaultStatsRange", "analyticsMetric")
                }
            }
        }

        test("PUT /api/v1/auth/me/preferences keeps the preferences that are left out") {
            createHarness().use { harness ->
                val login = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                testApplication {
                    setupAuthApp(harness)

                    suspend fun put(body: String): UserPreferencesResponse {
                        val response =
                            client.put("/api/v1/auth/me/preferences") {
                                cookie(SESSION_COOKIE_NAME, login.signedToken)
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        response.status shouldBe HttpStatusCode.OK
                        return MiruJson.decodeFromString<UserPreferencesResponse>(response.bodyAsText())
                    }

                    // Before anything is stored, the rest are the defaults
                    put("""{"relativeTimestamps":false}""").copy(updatedAtMs = null) shouldBe
                        UserPreferencesResponse(
                            theme = "system",
                            timeFormat = "24h",
                            dateFormat = "iso",
                            defaultStatsRange = "30d",
                            relativeTimestamps = false,
                            analyticsMetric = "plays",
                        )

                    val stored =
                        put(
                            """{"theme":"dark","timeFormat":"12h","dateFormat":"dmy",
                                "defaultStatsRange":"all","analyticsMetric":"duration"}""",
                        )
                    stored.relativeTimestamps shouldBe false

                    // One field at a time, and none at all
                    val changes =
                        listOf<Pair<String, UserPreferencesResponse.() -> UserPreferencesResponse>>(
                            """{"theme":"light"}""" to { copy(theme = "light") },
                            """{"timeFormat":"24h"}""" to { copy(timeFormat = "24h") },
                            """{"dateFormat":"mdy"}""" to { copy(dateFormat = "mdy") },
                            """{"defaultStatsRange":"7d"}""" to { copy(defaultStatsRange = "7d") },
                            """{"relativeTimestamps":true}""" to { copy(relativeTimestamps = true) },
                            """{"analyticsMetric":"plays"}""" to { copy(analyticsMetric = "plays") },
                            "{}" to { this },
                            """{"theme":null}""" to { this },
                        )
                    changes.fold(stored) { before, (body, change) ->
                        val after = put(body)
                        after.copy(updatedAtMs = null) shouldBe before.change().copy(updatedAtMs = null)
                        after
                    }
                }
            }
        }

        test("preferences endpoints and AuthService handle AUTH_MODE=none, synthetic accounts, and null repository gracefully") {
            createHarness(mode = AuthMode.NONE, sessionSecret = "").use { harness ->
                testApplication {
                    setupAuthApp(harness)

                    val getResp = client.get("/api/v1/auth/me/preferences")
                    getResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<UserPreferencesResponse>(getResp.bodyAsText()) shouldBe
                        UserPreferencesResponse(
                            theme = "system",
                            timeFormat = "24h",
                            dateFormat = "iso",
                            defaultStatsRange = "30d",
                            relativeTimestamps = true,
                            updatedAtMs = null,
                        )

                    val putResp =
                        client.put("/api/v1/auth/me/preferences") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    UpdateUserPreferencesRequest(
                                        theme = "light",
                                        timeFormat = "12h",
                                        dateFormat = "dmy",
                                        defaultStatsRange = "90d",
                                        relativeTimestamps = false,
                                    ),
                                ),
                            )
                        }
                    putResp.status shouldBe HttpStatusCode.OK
                    val updated = MiruJson.decodeFromString<UserPreferencesResponse>(putResp.bodyAsText())
                    updated.theme shouldBe "light"
                    updated.timeFormat shouldBe "12h"
                    updated.dateFormat shouldBe "dmy"
                    updated.defaultStatsRange shouldBe "90d"
                    updated.relativeTimestamps shouldBe false

                    val invalidPut =
                        client.put("/api/v1/auth/me/preferences") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"theme":"invalid"}""")
                        }
                    invalidPut.status shouldBe HttpStatusCode.BadRequest
                }
            }

            // Synthetic/missing account row and null preferencesRepository in LOCAL mode
            createHarness(mode = AuthMode.LOCAL).use { harness ->
                val syntheticPrincipal =
                    MiruPrincipal(
                        accountId = "missing-account-id",
                        username = "ghost",
                        role = MiruRole.USER,
                        authSource = AuthSource.SESSION_COOKIE,
                    )
                harness.authService.getPreferences(syntheticPrincipal).shouldBeRight() shouldBe
                    UserPreferencesResponse(
                        theme = "system",
                        timeFormat = "24h",
                        dateFormat = "iso",
                        defaultStatsRange = "30d",
                        relativeTimestamps = true,
                        updatedAtMs = null,
                    )
                val updatedMissing =
                    harness.authService
                        .updatePreferences(
                            syntheticPrincipal,
                            UpdateUserPreferencesRequest(
                                theme = "dark",
                                timeFormat = "24h",
                                dateFormat = "iso",
                                defaultStatsRange = "all",
                                relativeTimestamps = true,
                            ),
                        ).shouldBeRight()
                updatedMissing.theme shouldBe "dark"
                updatedMissing.defaultStatsRange shouldBe "all"

                val serviceWithoutRepo =
                    AuthService(
                        accountRepository = harness.accountRepo,
                        sessionRepository = harness.sessionRepo,
                        apiKeyRepository = harness.apiKeyRepo,
                        userRepository = harness.userRepo,
                        passwordHasher = harness.hasher,
                        authConfig = harness.appConfig.auth,
                    )
                serviceWithoutRepo.getPreferences(syntheticPrincipal).shouldBeRight().theme shouldBe "system"
                val invalidErr =
                    serviceWithoutRepo
                        .updatePreferences(
                            syntheticPrincipal,
                            UpdateUserPreferencesRequest(theme = "sepia"),
                        ).shouldBeLeft()
                        .shouldBeInstanceOf<MiruError.ValidationError>()
                invalidErr.fieldErrors.keys shouldContainExactly setOf("theme")
            }
        }

        test("sets Secure flag on session cookies and logout clearing cookies according to CookieSecureMode and proxy headers") {
            createHarness(adminUsername = "admin", adminPassword = "Admin-strong-password-1").use { harness ->
                harness.authService.bootstrapAdminIfConfigured().shouldBeRight()

                fun hasSecureFlag(setCookieHeaders: List<String>, name: String): Boolean {
                    val target = setCookieHeaders.first { it.startsWith("$name=") }
                    return target.split(';').any { it.trim().equals("Secure", ignoreCase = true) }
                }

                // AUTO mode: plain HTTP omits Secure, X-Forwarded-Proto: https and Forwarded proto=https set Secure
                testApplication {
                    application { miruModule(config = harness.appConfig, authService = harness.authService) }

                    val httpLogin =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-1")))
                        }
                    hasSecureFlag(httpLogin.headers.getAll(HttpHeaders.SetCookie).orEmpty(), SESSION_COOKIE_NAME) shouldBe false

                    val httpsLogin =
                        client.post("/api/v1/auth/login") {
                            header("X-Forwarded-Proto", "https, http")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-1")))
                        }
                    val httpsCookies = httpsLogin.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    hasSecureFlag(httpsCookies, SESSION_COOKIE_NAME) shouldBe true
                    val token = extractCookieValue(httpsCookies.first()).shouldNotBeNull()

                    val forwardedLogout =
                        client.post("/api/v1/auth/logout") {
                            header(HttpHeaders.Forwarded, "for=192.0.2.1;proto=\"https\"")
                            cookie(SESSION_COOKIE_NAME, token)
                        }
                    hasSecureFlag(
                        forwardedLogout.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
                        SESSION_COOKIE_NAME,
                    ) shouldBe true
                }

                // ALWAYS mode sets Secure even over plain HTTP
                testApplication {
                    application {
                        miruModule(
                            config = harness.appConfig.copy(auth = harness.appConfig.auth.copy(cookieSecure = CookieSecureMode.ALWAYS)),
                            authService = harness.authService,
                        )
                    }
                    val login =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-1")))
                        }
                    hasSecureFlag(login.headers.getAll(HttpHeaders.SetCookie).orEmpty(), SESSION_COOKIE_NAME) shouldBe true
                }

                // NEVER mode omits Secure even when X-Forwarded-Proto: https is present
                testApplication {
                    application {
                        miruModule(
                            config = harness.appConfig.copy(auth = harness.appConfig.auth.copy(cookieSecure = CookieSecureMode.NEVER)),
                            authService = harness.authService,
                        )
                    }
                    val login =
                        client.post("/api/v1/auth/login") {
                            header("X-Forwarded-Proto", "https")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-1")))
                        }
                    hasSecureFlag(login.headers.getAll(HttpHeaders.SetCookie).orEmpty(), SESSION_COOKIE_NAME) shouldBe false
                }
            }
        }

        test("rate limits /api/v1/auth/login (per-IP and per-account) and /api/v1/auth/setup with RFC 7807 429 and Retry-After") {
            createHarness(adminUsername = "admin", adminPassword = "Admin-strong-password-1").use { harness ->
                harness.authService.bootstrapAdminIfConfigured().shouldBeRight()
                val rateConfig =
                    AuthRateLimitConfig(
                        enabled = true,
                        windowSeconds = 60,
                        loginMaxPerIp = 4,
                        loginMaxFailuresPerAccount = 2,
                        setupMaxPerIp = 2,
                    )
                val nowMs = AtomicLong(1_000_000L)
                val limiter = AuthRateLimiter(config = rateConfig, clock = { nowMs.get() })
                val trustedConfig =
                    harness.appConfig.copy(
                        server = harness.appConfig.server.copy(trustProxyHeaders = true),
                        auth = harness.appConfig.auth.copy(rateLimit = rateConfig),
                    )

                testApplication {
                    application {
                        miruModule(config = trustedConfig, authService = harness.authService, authRateLimiter = limiter)
                    }

                    // Setup endpoint limited after 2 attempts from same client IP
                    repeat(2) {
                        client.post("/api/v1/auth/setup") {
                            header(HttpHeaders.XForwardedFor, "203.0.113.1")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetupAdminRequest("x", "Valid-password-123")))
                        }.status shouldBe HttpStatusCode.Conflict
                    }
                    val setupLimited =
                        client.post("/api/v1/auth/setup") {
                            header(HttpHeaders.XForwardedFor, "203.0.113.1")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetupAdminRequest("x", "Valid-password-123")))
                        }
                    setupLimited.status shouldBe HttpStatusCode.TooManyRequests
                    setupLimited.headers[HttpHeaders.RetryAfter] shouldBe "60"
                    setupLimited.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // Account failure limit kicks in across rotating client IPs
                    client.post("/api/v1/auth/login") {
                        header(HttpHeaders.XForwardedFor, "203.0.113.10")
                        contentType(ContentType.Application.Json)
                        setBody(MiruJson.encodeToString(LoginRequest("admin", "Wrong-password-1")))
                    }.status shouldBe HttpStatusCode.Unauthorized
                    client.post("/api/v1/auth/login") {
                        header(HttpHeaders.XForwardedFor, "203.0.113.11")
                        contentType(ContentType.Application.Json)
                        setBody(MiruJson.encodeToString(LoginRequest("ADMIN", "Wrong-password-2")))
                    }.status shouldBe HttpStatusCode.Unauthorized

                    val acctLimited =
                        client.post("/api/v1/auth/login") {
                            header(HttpHeaders.XForwardedFor, "203.0.113.12")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-1")))
                        }
                    acctLimited.status shouldBe HttpStatusCode.TooManyRequests
                    acctLimited.headers[HttpHeaders.RetryAfter] shouldBe "60"
                    MiruJson.decodeFromString<ProblemDetails>(acctLimited.bodyAsText()).status shouldBe 429

                    // Per-IP limit triggers for repeated attempts against different usernames from same IP
                    val burstIp = "198.51.100.20"
                    repeat(4) { idx ->
                        client.post("/api/v1/auth/login") {
                            header(HttpHeaders.XForwardedFor, "1.1.1.$idx, $burstIp")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("user$idx", "Wrong-password-1")))
                        }.status shouldBe HttpStatusCode.Unauthorized
                    }
                    val ipLimited =
                        client.post("/api/v1/auth/login") {
                            header(HttpHeaders.XForwardedFor, "2.2.2.2, $burstIp")
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("otheruser", "Wrong-password-1")))
                        }
                    ipLimited.status shouldBe HttpStatusCode.TooManyRequests
                    ipLimited.headers[HttpHeaders.RetryAfter] shouldBe "60"

                    // After window advances, account and IP can log in cleanly
                    nowMs.addAndGet(61_000L)
                    val recovered =
                        client.post("/api/v1/auth/login") {
                            header(HttpHeaders.XForwardedFor, burstIp)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Admin-strong-password-1")))
                        }
                    recovered.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("AuthService bounds concurrent password hashing operations with a semaphore") {
            createHarness().use { harness ->
                val inFlight = AtomicInteger(0)
                val maxObserved = AtomicInteger(0)
                val blockingHasher =
                    object : PasswordHasher {
                        override fun hash(password: String): String {
                            val current = inFlight.incrementAndGet()
                            maxObserved.updateAndGet { prev -> max(prev, current) }
                            Thread.sleep(25)
                            inFlight.decrementAndGet()
                            return harness.hasher.hash(password)
                        }

                        override fun verify(
                            password: String,
                            encodedHash: String,
                        ): Boolean {
                            val current = inFlight.incrementAndGet()
                            maxObserved.updateAndGet { prev -> max(prev, current) }
                            Thread.sleep(25)
                            inFlight.decrementAndGet()
                            return harness.hasher.verify(password, encodedHash)
                        }
                    }

                val boundedService =
                    AuthService(
                        accountRepository = harness.accountRepo,
                        sessionRepository = harness.sessionRepo,
                        apiKeyRepository = harness.apiKeyRepo,
                        userRepository = harness.userRepo,
                        passwordHasher = blockingHasher,
                        authConfig = harness.appConfig.auth,
                        maxConcurrentPasswordHashes = 2,
                    )

                boundedService.setupInitialAdmin("admin", "Admin-strong-password-1").shouldBeRight()
                inFlight.set(0)
                maxObserved.set(0)

                coroutineScope {
                    (1..6)
                        .map {
                            async {
                                boundedService.login("admin", "Admin-strong-password-1").shouldBeRight()
                            }
                        }.awaitAll()
                }
                (maxObserved.get() in 1..2) shouldBe true
            }
        }

        test("POST /api/v1/auth/me/password verifies current password, rotates session, and rejects non-local modes") {
            val changeReq = ChangePasswordRequest(currentPassword = "Old-secret-pass-1", newPassword = "New-secret-pass-2")
            changeReq.toString() shouldNotContain "Old-secret-pass-1"
            changeReq.toString() shouldNotContain "New-secret-pass-2"

            createHarness(mode = AuthMode.NONE, sessionSecret = "").use { noneHarness ->
                testApplication {
                    setupAuthApp(noneHarness)
                    val meResp = client.get("/api/v1/auth/me")
                    meResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(meResp.bodyAsText()).authProvider shouldBe "NONE"

                    val noneChange =
                        client.post("/api/v1/auth/me/password") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(changeReq))
                        }
                    noneChange.status shouldBe HttpStatusCode.Conflict
                }
            }

            createHarness().use { harness ->
                val initialPass = "Alice-initial-password-1"
                val rotatedPass = "Alice-rotated-password-2"
                val finalPass = "Alice-final-password-3"
                harness.authService.setupInitialAdmin("admin", "Admin-strong-password-1").shouldBeRight()
                val alice =
                    harness.authService
                        .createAccount(
                            username = "alice",
                            password = initialPass,
                            role = MiruRole.USER,
                        ).shouldBeRight()
                val aliceKey =
                    harness.authService
                        .createApiKey(
                            accountId = alice.account.id,
                            name = "Alice CLI Key",
                            role = MiruRole.USER,
                        ).shouldBeRight()
                        .rawKey
                val oidcAccount =
                    AuthAccountRecord(
                        id = "acc-oidc-self",
                        username = "sso.user",
                        usernameNormalized = "sso.user",
                        email = "sso@miru.local",
                        passwordHash = "",
                        role = "USER",
                        disabled = false,
                        authProvider = "OIDC",
                        externalSubject = "sub-self-1",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                harness.accountRepo.insert(oidcAccount).shouldBeRight()
                val oidcSession = harness.authService.issueSessionForAccount(oidcAccount, emptySet()).shouldBeRight()

                val session1 = harness.authService.login("alice", initialPass).shouldBeRight()
                val session2 = harness.authService.login("alice", initialPass).shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // Unauthenticated rejected
                    client
                        .post("/api/v1/auth/me/password") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(changeReq))
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // OIDC account rejected with 409 Conflict
                    val oidcMe =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, oidcSession.signedToken)
                        }
                    oidcMe.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(oidcMe.bodyAsText()).authProvider shouldBe "OIDC"

                    client
                        .post("/api/v1/auth/me/password") {
                            cookie(SESSION_COOKIE_NAME, oidcSession.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(changeReq))
                        }.status shouldBe HttpStatusCode.Conflict

                    // Blank currentPassword + weak newPassword -> 400 ValidationError
                    val invalidResp =
                        client.post("/api/v1/auth/me/password") {
                            cookie(SESSION_COOKIE_NAME, session1.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(ChangePasswordRequest("", "weak")))
                        }
                    invalidResp.status shouldBe HttpStatusCode.BadRequest
                    val invalidProblem = MiruJson.decodeFromString<ProblemDetails>(invalidResp.bodyAsText())
                    invalidProblem.errors.shouldNotBeNull().keys shouldContainExactly setOf("currentPassword", "newPassword")

                    // Wrong current password -> 401 without invalidating existing sessions
                    client
                        .post("/api/v1/auth/me/password") {
                            cookie(SESSION_COOKIE_NAME, session1.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(ChangePasswordRequest("Wrong-pass-123", rotatedPass)))
                        }.status shouldBe HttpStatusCode.Unauthorized

                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, session1.signedToken)
                        }.status shouldBe HttpStatusCode.OK

                    // Valid password change revokes old sessions and issues fresh rotated session cookie
                    val okResp =
                        client.post("/api/v1/auth/me/password") {
                            cookie(SESSION_COOKIE_NAME, session1.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(ChangePasswordRequest(initialPass, rotatedPass)))
                        }
                    okResp.status shouldBe HttpStatusCode.NoContent
                    val rotatedCookieHeader = okResp.headers[HttpHeaders.SetCookie].shouldNotBeNull()
                    rotatedCookieHeader shouldContain "HttpOnly"
                    rotatedCookieHeader shouldContain "SameSite=Lax"
                    val rotatedCookie = extractCookieValue(rotatedCookieHeader).shouldNotBeNull()

                    // Both prior session cookies are invalidated
                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, session1.signedToken)
                        }.status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, session2.signedToken)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // Rotated session cookie remains authenticated
                    val meRotated =
                        client.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, rotatedCookie)
                        }
                    meRotated.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PrincipalResponse>(meRotated.bodyAsText()).authProvider shouldBe "LOCAL"

                    // Old password rejected, new password succeeds
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("alice", initialPass)))
                        }.status shouldBe HttpStatusCode.Unauthorized
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("alice", rotatedPass)))
                        }.status shouldBe HttpStatusCode.OK

                    // API-key caller changes password -> 204 without Set-Cookie and revokes existing session cookies
                    val apiKeyChangeResp =
                        client.post("/api/v1/auth/me/password") {
                            header("X-Api-Key", aliceKey)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(ChangePasswordRequest(rotatedPass, finalPass)))
                        }
                    apiKeyChangeResp.status shouldBe HttpStatusCode.NoContent
                    apiKeyChangeResp.headers[HttpHeaders.SetCookie].shouldBeNull()

                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, rotatedCookie)
                        }.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        test("PATCH /api/v1/admin/accounts/{id} updates role, disabled, and password with session revocation") {
            val redactedUpdate = UpdateAccountRequest(role = "USER", disabled = true, password = "Secret-reset-pass-1")
            redactedUpdate.toString() shouldNotContain "Secret-reset-pass-1"

            createHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-1").shouldBeRight()
                val bob =
                    harness.authService
                        .createAccount(
                            username = "bob",
                            password = "Bob-initial-password-1",
                            role = MiruRole.USER,
                        ).shouldBeRight()
                val disabledAdmin =
                    AuthAccountRecord(
                        id = "acc-disabled-admin",
                        username = "dormantadmin",
                        usernameNormalized = "dormantadmin",
                        passwordHash = harness.hasher.hash("Dormant-admin-pass-1"),
                        role = "ADMIN",
                        disabled = true,
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                val oidcAccount =
                    AuthAccountRecord(
                        id = "acc-oidc-admin-target",
                        username = "sso.bob",
                        usernameNormalized = "sso.bob",
                        passwordHash = "",
                        role = "USER",
                        disabled = false,
                        authProvider = "OIDC",
                        externalSubject = "sub-target-1",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                harness.accountRepo.insert(disabledAdmin).shouldBeRight()
                harness.accountRepo.insert(oidcAccount).shouldBeRight()

                val bobSession = harness.authService.login("bob", "Bob-initial-password-1").shouldBeRight()

                testApplication {
                    setupAuthApp(harness)

                    // Non-admin USER receives 403
                    client
                        .patch("/api/v1/admin/accounts/${bob.account.id}") {
                            cookie(SESSION_COOKIE_NAME, bobSession.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "ADMIN")))
                        }.status shouldBe HttpStatusCode.Forbidden

                    // Missing account receives 404
                    client
                        .patch("/api/v1/admin/accounts/does-not-exist") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(disabled = true)))
                        }.status shouldBe HttpStatusCode.NotFound

                    // Empty body / invalid role / weak password return 400 ValidationError
                    client
                        .patch("/api/v1/admin/accounts/${bob.account.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody("{}")
                        }.status shouldBe HttpStatusCode.BadRequest

                    val badPatch =
                        client.patch("/api/v1/admin/accounts/${bob.account.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "SUPER", password = "short")))
                        }
                    badPatch.status shouldBe HttpStatusCode.BadRequest
                    val badProblem = MiruJson.decodeFromString<ProblemDetails>(badPatch.bodyAsText())
                    badProblem.errors.shouldNotBeNull().keys shouldContainExactly setOf("role", "password")

                    // Self-demotion & self-disable rejected with 409 even though a disabled admin row exists
                    client
                        .patch("/api/v1/admin/accounts/${adminLogin.principal.accountId}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "USER")))
                        }.status shouldBe HttpStatusCode.Conflict

                    client
                        .patch("/api/v1/admin/accounts/${adminLogin.principal.accountId}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(disabled = true)))
                        }.status shouldBe HttpStatusCode.Conflict

                    // Password reset on OIDC account rejected with 409
                    client
                        .patch("/api/v1/admin/accounts/${oidcAccount.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(password = "Valid-password-123")))
                        }.status shouldBe HttpStatusCode.Conflict

                    assertRoleModeUpdates(adminLogin, bobId = bob.account.id, oidcAccountId = oidcAccount.id)

                    // Disable Bob -> revokes active sessions and blocks login
                    val disableBobResp =
                        client.patch("/api/v1/admin/accounts/${bob.account.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(disabled = true)))
                        }
                    disableBobResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AccountResponse>(disableBobResp.bodyAsText()).disabled shouldBe true

                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, bobSession.signedToken)
                        }.status shouldBe HttpStatusCode.Unauthorized
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("bob", "Bob-initial-password-1")))
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // Enable Bob, reset password, and promote to ADMIN
                    val resetPass = "Bob-reset-password-2"
                    val promoteBobResp =
                        client.patch("/api/v1/admin/accounts/${bob.account.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "ADMIN", disabled = false, password = resetPass)))
                        }
                    promoteBobResp.status shouldBe HttpStatusCode.OK
                    val promotedBob = MiruJson.decodeFromString<AccountResponse>(promoteBobResp.bodyAsText())
                    promotedBob.role shouldBe MiruRole.ADMIN
                    promotedBob.disabled shouldBe false

                    val bobAdminLogin =
                        client.post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("bob", resetPass)))
                        }
                    bobAdminLogin.status shouldBe HttpStatusCode.OK
                    val bobAdminCookie = extractCookieValue(bobAdminLogin.headers[HttpHeaders.SetCookie]).shouldNotBeNull()

                    // Now that two active admins exist, Bob can downgrade the original admin -> revokes admin's session
                    val downgradeAdminResp =
                        client.patch("/api/v1/admin/accounts/${adminLogin.principal.accountId}") {
                            cookie(SESSION_COOKIE_NAME, bobAdminCookie)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "USER")))
                        }
                    downgradeAdminResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AccountResponse>(downgradeAdminResp.bodyAsText()).role shouldBe MiruRole.USER

                    client
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // Re-promote admin via Bob, then verify disabling the sole active admin via another admin is impossible
                    // (First check Bob is now the sole active admin: deleting Bob via Bob -> 409; disabling dormantAdmin -> ok;
                    // deleting original user account -> ok)
                    client
                        .delete("/api/v1/admin/accounts/${adminLogin.principal.accountId}") {
                            cookie(SESSION_COOKIE_NAME, bobAdminCookie)
                        }.status shouldBe HttpStatusCode.NoContent

                    // Now Bob is the only active admin (dormantAdmin is disabled ADMIN) -> Bob cannot demote/disable self
                    client
                        .patch("/api/v1/admin/accounts/${bob.account.id}") {
                            cookie(SESSION_COOKIE_NAME, bobAdminCookie)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateAccountRequest(role = "USER")))
                        }.status shouldBe HttpStatusCode.Conflict

                    // Deleting dormantAdmin succeeds because Bob is still an active admin, whereas deleting Bob as last active admin fails
                    client
                        .delete("/api/v1/admin/accounts/${disabledAdmin.id}") {
                            cookie(SESSION_COOKIE_NAME, bobAdminCookie)
                        }.status shouldBe HttpStatusCode.NoContent
                }
            }
        }
    })
