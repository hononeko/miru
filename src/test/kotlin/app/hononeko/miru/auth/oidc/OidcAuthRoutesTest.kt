package app.hononeko.miru.auth.oidc

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.EmailMediaUserLinker
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.RoleMode
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.OidcConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.auth.MediaUserLinkMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.AccountListResponse
import app.hononeko.miru.http.AccountResponse
import app.hononeko.miru.http.AuthProvidersResponse
import app.hononeko.miru.http.LoginRequest
import app.hononeko.miru.http.PrincipalResponse
import app.hononeko.miru.http.ProblemDetails
import app.hononeko.miru.http.SetLinkedMediaUsersRequest
import app.hononeko.miru.http.SetMediaUserLinkModeRequest
import app.hononeko.miru.http.SetupAdminRequest
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.cookie
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

private val urlEnc = Base64.getUrlEncoder().withoutPadding()

private fun rsaJwk(
    pub: RSAPublicKey,
    kid: String,
): JwkKey {
    fun encBigInt(v: BigInteger): String {
        val raw = v.toByteArray()
        val unsigned = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        return urlEnc.encodeToString(unsigned)
    }
    return JwkKey(
        kty = "RSA",
        use = "sig",
        kid = kid,
        alg = "RS256",
        n = encBigInt(pub.modulus),
        e = encBigInt(pub.publicExponent),
    )
}

private fun signRs256Jwt(
    kid: String,
    payload: JsonObject,
    privateKey: PrivateKey,
): String {
    val header = buildJsonObject { put("alg", "RS256"); put("kid", kid); put("typ", "JWT") }
    val headerB64 = urlEnc.encodeToString(header.toString().toByteArray(Charsets.UTF_8))
    val payloadB64 = urlEnc.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    val input = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(privateKey)
    sig.update(input)
    return "$headerB64.$payloadB64.${urlEnc.encodeToString(sig.sign())}"
}

private fun extractCookieValue(
    headers: List<String>,
    cookieName: String,
): String? {
    val prefix = "$cookieName="
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

private data class MockIdpScenario(
    val subject: String = "sub-1",
    val preferredUsername: String? = "oidc_user",
    val email: String? = "oidc@miru.local",
    val emailVerifiedInIdToken: Boolean? = true,
    val emailVerifiedInUserInfo: Boolean? = null,
    val emailInUserInfo: String? = email,
    val subjectInUserInfo: String = subject,
    val preferredUsernameInUserInfo: String? = null,
    val groupsInIdToken: List<String>? = listOf("miru-users"),
    val groupsInUserInfo: List<String>? = null,
    val includeEndSessionEndpoint: Boolean = true,
    val includeUserInfoEndpoint: Boolean = true,
    val userInfoFails: Boolean = false,
)

private class OidcTestHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val nowMs: AtomicLong,
    val accountRepo: ExposedAuthAccountRepository,
    val sessionRepo: ExposedAuthSessionRepository,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val emailLinker: EmailMediaUserLinker,
    val authService: AuthService,
    val oidcClient: OidcClient?,
    val oidcAuthService: OidcAuthService?,
    val appConfig: AppConfig,
) : AutoCloseable {
    override fun close() {
        oidcClient?.close()
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createMockIdpEngine(
    nowMs: AtomicLong,
    includeEndSessionEndpoint: Boolean,
    scenarioProvider: () -> MockIdpScenario,
    expectedChallengeHolder: MutableMap<String, String>,
): MockEngine {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val jwk = rsaJwk(keyPair.public as RSAPublicKey, kid = "key-1")
    val jwkSet = JwkSet(keys = listOf(jwk))

    return MockEngine { request ->
        when (request.url.encodedPath) {
            "/o/miru/.well-known/openid-configuration" ->
                respond(
                    content =
                        MiruJson.encodeToString(
                            OidcDiscoveryDocument(
                                issuer = "https://idp.miru.test/o/miru",
                                authorizationEndpoint = "https://idp.miru.test/o/miru/authorize",
                                tokenEndpoint = "https://idp.miru.test/o/miru/token",
                                userinfoEndpoint =
                                    if (scenarioProvider().includeUserInfoEndpoint) {
                                        "https://idp.miru.test/o/miru/userinfo"
                                    } else {
                                        null
                                    },
                                jwksUri = "https://idp.miru.test/o/miru/jwks",
                                endSessionEndpoint =
                                    if (includeEndSessionEndpoint) {
                                        "https://idp.miru.test/o/miru/end-session"
                                    } else {
                                        null
                                    },
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            "/o/miru/jwks" ->
                respond(
                    content = MiruJson.encodeToString(jwkSet),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            "/o/miru/token" -> {
                val params = (request.body as FormDataContent).formData
                val code = params["code"].orEmpty()
                val codeVerifier = params["code_verifier"].orEmpty()
                val expectedChallenge = expectedChallengeHolder[code]
                if (expectedChallenge != null) {
                    val actualChallenge =
                        urlEnc.encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII)),
                        )
                    actualChallenge shouldBe expectedChallenge
                }
                val nonce = expectedChallengeHolder["nonce:$code"] ?: "missing-nonce"
                val scenario = scenarioProvider()
                val nowSec = nowMs.get() / 1_000L
                val payload =
                    buildJsonObject {
                        put("iss", "https://idp.miru.test/o/miru")
                        put("sub", scenario.subject)
                        put("aud", "miru-app")
                        put("nonce", nonce)
                        put("exp", nowSec + 600L)
                        put("iat", nowSec)
                        scenario.preferredUsername?.let { put("preferred_username", it) }
                        scenario.email?.let { put("email", it) }
                        scenario.emailVerifiedInIdToken?.let { put("email_verified", it) }
                        scenario.groupsInIdToken?.let { groups ->
                            put("groups", buildJsonArray { groups.forEach { add(JsonPrimitive(it)) } })
                        }
                    }
                val idToken = signRs256Jwt(kid = "key-1", payload = payload, privateKey = keyPair.private)
                respond(
                    content =
                        MiruJson.encodeToString(
                            OidcTokenResponse(
                                accessToken = "access-token-$code",
                                tokenType = "Bearer",
                                expiresIn = 600L,
                                idToken = idToken,
                            ),
                        ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            "/o/miru/userinfo" -> {
                val scenario = scenarioProvider()
                if (scenario.userInfoFails) {
                    respond("Upstream error", HttpStatusCode.BadGateway)
                } else {
                    val body =
                        buildJsonObject {
                            put("sub", scenario.subjectInUserInfo)
                            scenario.emailInUserInfo?.let { put("email", it) }
                            scenario.preferredUsernameInUserInfo?.let { put("preferred_username", it) }
                            scenario.emailVerifiedInUserInfo?.let { put("email_verified", it) }
                            scenario.groupsInUserInfo?.let { groups ->
                                put("groups", buildJsonArray { groups.forEach { add(JsonPrimitive(it)) } })
                            }
                        }
                    respond(
                        content = body.toString(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }

            else -> respond("Not Found", HttpStatusCode.NotFound)
        }
    }
}

private fun createOidcHarness(
    mode: AuthMode = AuthMode.OIDC,
    allowLocalLogin: Boolean = false,
    oidcEnabled: Boolean = true,
    includeEndSessionEndpoint: Boolean = true,
    autoLinkByEmail: Boolean = true,
    scenarioProvider: () -> MockIdpScenario = { MockIdpScenario() },
    expectedChallengeHolder: MutableMap<String, String> = mutableMapOf(),
): OidcTestHarness {
    val dir = Files.createTempDirectory("miru-oidc-test-")
    val dbPath = dir.resolve("miru-oidc.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val nowMs = AtomicLong(1_700_000_000_000L)
    val accountRepo = ExposedAuthAccountRepository(handle)
    val sessionRepo = ExposedAuthSessionRepository(handle)
    val apiKeyRepo = ExposedApiKeyRepository(handle)
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val hasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1))

    val oidcConfig =
        OidcConfig(
            enabled = oidcEnabled,
            issuerUrl = "https://idp.miru.test/o/miru",
            clientId = "miru-app",
            clientSecret = "super-secret-oidc-client-secret-value",
            redirectUri = "http://localhost/api/v1/auth/oidc/callback",
            displayName = "Authentik",
            scopes = listOf("openid", "profile", "email", "groups"),
            groupsClaim = "groups",
            adminGroups = setOf("miru-admins"),
            userGroups = setOf("miru-users"),
            autoLinkByEmail = autoLinkByEmail,
            postLogoutRedirectUri = "http://localhost/login",
        )
    val authConfig =
        AuthConfig(
            mode = mode,
            sessionSecret = "super-secret-session-signing-key-32-chars",
            allowLocalLogin = allowLocalLogin,
            oidc = oidcConfig,
        )

    val emailLinker = EmailMediaUserLinker(accountRepo, userRepo, authConfig, clock = { nowMs.get() })
    val authService =
        AuthService(
            accountRepository = accountRepo,
            sessionRepository = sessionRepo,
            apiKeyRepository = apiKeyRepo,
            userRepository = userRepo,
            passwordHasher = hasher,
            authConfig = authConfig,
            clock = { nowMs.get() },
            emailLinker = emailLinker,
        )

    val mockEngine =
        createMockIdpEngine(
            nowMs = nowMs,
            includeEndSessionEndpoint = includeEndSessionEndpoint,
            scenarioProvider = scenarioProvider,
            expectedChallengeHolder = expectedChallengeHolder,
        )

    val oidcClient =
        if (authConfig.oidcEnabled) {
            OidcClient(
                config = oidcConfig,
                jwtVerifier = OidcJwtVerifier(clock = { nowMs.get() }),
                engine = mockEngine,
                clock = { nowMs.get() },
            )
        } else {
            null
        }
    val oidcAuthService =
        oidcClient?.let {
            OidcAuthService(
                oidcClient = it,
                authService = authService,
                accountRepository = accountRepo,
                emailLinker = emailLinker,
                authConfig = authConfig,
                clock = { nowMs.get() },
            )
        }

    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "plex-token"),
            session = SessionConfig(),
            auth = authConfig,
        )

    return OidcTestHarness(
        tempDir = dir,
        dbHandle = handle,
        nowMs = nowMs,
        accountRepo = accountRepo,
        sessionRepo = sessionRepo,
        serverRepo = serverRepo,
        userRepo = userRepo,
        emailLinker = emailLinker,
        authService = authService,
        oidcClient = oidcClient,
        oidcAuthService = oidcAuthService,
        appConfig = appConfig,
    )
}

private fun HttpResponse.cookieValue(name: String): String =
    extractCookieValue(headers.getAll(HttpHeaders.SetCookie).orEmpty(), name).shouldNotBeNull()

/** Runs the full authorization-code flow against the mock IdP and returns the new session cookie value. */
private suspend fun HttpClient.signInWithOidc(
    code: String,
    expectedChallengeHolder: MutableMap<String, String>,
): String {
    val callback = completeOidcCallback(code, expectedChallengeHolder)
    callback.status shouldBe HttpStatusCode.Found
    return extractCookieValue(callback.headers.getAll(HttpHeaders.SetCookie).orEmpty(), SESSION_COOKIE_NAME).shouldNotBeNull()
}

private suspend fun HttpClient.completeOidcCallback(
    code: String,
    expectedChallengeHolder: MutableMap<String, String>,
): HttpResponse {
    val start = get("/api/v1/auth/oidc/login")
    val location = Url(start.headers[HttpHeaders.Location].shouldNotBeNull())
    val state = location.parameters["state"].shouldNotBeNull()
    expectedChallengeHolder[code] = location.parameters["code_challenge"].shouldNotBeNull()
    expectedChallengeHolder["nonce:$code"] = location.parameters["nonce"].shouldNotBeNull()
    val txCookie =
        extractCookieValue(start.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx").shouldNotBeNull()
    return get("/api/v1/auth/oidc/callback?code=$code&state=$state") {
        cookie("miru_oidc_tx", txCookie)
        header(HttpHeaders.Accept, "application/json")
    }
}

private suspend fun HttpClient.patchAccount(
    session: String,
    accountId: String,
    body: String,
): HttpResponse =
    patch("/api/v1/admin/accounts/$accountId") {
        cookie(SESSION_COOKIE_NAME, session)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend fun HttpClient.me(session: String): PrincipalResponse {
    val me = get("/api/v1/auth/me") { cookie(SESSION_COOKIE_NAME, session) }
    me.status shouldBe HttpStatusCode.OK
    return MiruJson.decodeFromString<PrincipalResponse>(me.bodyAsText())
}

private suspend fun HttpClient.linkedMediaUserIds(session: String): List<String> = me(session).linkedMediaUserIds

private suspend fun OidcTestHarness.upsertMediaUser(
    id: String,
    externalUserId: String,
    email: String?,
) {
    userRepo
        .upsert(
            UserRecord(
                id = id,
                serverId = "srv-1",
                externalUserId = externalUserId,
                username = id,
                displayName = id,
                email = email,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
}

private suspend fun OidcTestHarness.upsertPlexServer() {
    serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Plex",
                serverType = "PLEX",
                machineId = "m1",
                baseUrl = "http://plex.local:32400",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
}

private fun ApplicationTestBuilder.setupApp(harness: OidcTestHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            oidcAuthService = harness.oidcAuthService,
        )
    }
}

class OidcAuthRoutesTest :
    FunSpec({
        test("GET /api/v1/auth/providers reports local-only, OIDC-only, and hybrid modes") {
            createOidcHarness(mode = AuthMode.LOCAL, allowLocalLogin = true, oidcEnabled = false).use { h ->
                testApplication {
                    setupApp(h)
                    val providers = MiruJson.decodeFromString<AuthProvidersResponse>(client.get("/api/v1/auth/providers").bodyAsText())
                    providers shouldBe
                        AuthProvidersResponse(
                            authMode = "local",
                            localEnabled = true,
                            oidcEnabled = false,
                            oidcDisplayName = "Authentik",
                            oidcLoginUrl = null,
                            oidcLogoutUrl = null,
                            setupRequired = true,
                        )
                }
            }

            createOidcHarness(mode = AuthMode.OIDC, allowLocalLogin = false, oidcEnabled = true).use { h ->
                testApplication {
                    setupApp(h)
                    val providers = MiruJson.decodeFromString<AuthProvidersResponse>(client.get("/api/v1/auth/providers").bodyAsText())
                    providers shouldBe
                        AuthProvidersResponse(
                            authMode = "oidc",
                            localEnabled = false,
                            oidcEnabled = true,
                            oidcDisplayName = "Authentik",
                            oidcLoginUrl = "/api/v1/auth/oidc/login",
                            oidcLogoutUrl = "/api/v1/auth/oidc/logout",
                            setupRequired = false,
                        )
                }
            }

            createOidcHarness(mode = AuthMode.LOCAL, allowLocalLogin = true, oidcEnabled = true).use { h ->
                testApplication {
                    setupApp(h)
                    val providers = MiruJson.decodeFromString<AuthProvidersResponse>(client.get("/api/v1/auth/providers").bodyAsText())
                    providers.authMode shouldBe "local"
                    providers.localEnabled shouldBe true
                    providers.oidcEnabled shouldBe true
                    providers.oidcLoginUrl shouldBe "/api/v1/auth/oidc/login"
                    providers.oidcLogoutUrl shouldBe "/api/v1/auth/oidc/logout"
                    providers.setupRequired shouldBe true
                }
            }
        }

        test("completes PKCE login for ADMIN and USER, auto-links media user by email, and supports RP-Initiated Logout") {
            var scenario = MockIdpScenario()
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(
                mode = AuthMode.OIDC,
                allowLocalLogin = false,
                oidcEnabled = true,
                scenarioProvider = { scenario },
                expectedChallengeHolder = expectedChallengeHolder,
            ).use { harness ->
                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-1",
                            name = "Plex",
                            serverType = "PLEX",
                            machineId = "m1",
                            baseUrl = "http://plex.local:32400",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "mu-alice",
                            serverId = "srv-1",
                            externalUserId = "100",
                            username = "alice",
                            displayName = "Alice",
                            email = "ALICE@miru.local",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupApp(harness)
                    val noRedirectClient = createClient { followRedirects = false }

                    // 1. Local login & setup are blocked when AUTH_ALLOW_LOCAL_LOGIN=false / AUTH_MODE=oidc
                    noRedirectClient
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(LoginRequest("admin", "Some-password-123")))
                        }.status shouldBe HttpStatusCode.Conflict

                    noRedirectClient
                        .post("/api/v1/auth/setup") {
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(SetupAdminRequest("admin", "Some-password-123")))
                        }.status shouldBe HttpStatusCode.Conflict

                    // 2. OIDC login as USER (auto-linked to mu-alice via case-insensitive email)
                    scenario =
                        MockIdpScenario(
                            subject = "sub-alice",
                            preferredUsername = "alice",
                            email = "alice@miru.local",
                            groupsInIdToken = listOf("miru-users"),
                        )
                    val loginStart1 = noRedirectClient.get("/api/v1/auth/oidc/login")
                    loginStart1.status shouldBe HttpStatusCode.Found
                    val location1 = Url(loginStart1.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state1 = location1.parameters["state"].shouldNotBeNull()
                    val nonce1 = location1.parameters["nonce"].shouldNotBeNull()
                    val challenge1 = location1.parameters["code_challenge"].shouldNotBeNull()
                    location1.parameters["code_challenge_method"] shouldBe "S256"
                    val txCookie1 =
                        extractCookieValue(loginStart1.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx")
                            .shouldNotBeNull()

                    expectedChallengeHolder["code-alice"] = challenge1
                    expectedChallengeHolder["nonce:code-alice"] = nonce1

                    val callback1 =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-alice&state=$state1") {
                            cookie("miru_oidc_tx", txCookie1)
                        }
                    callback1.status shouldBe HttpStatusCode.Found
                    callback1.headers[HttpHeaders.Location] shouldBe "/"
                    val setCookies1 = callback1.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    val aliceSession = extractCookieValue(setCookies1, SESSION_COOKIE_NAME).shouldNotBeNull()
                    val aliceHint = extractCookieValue(setCookies1, "miru_oidc_hint").shouldNotBeNull()

                    val aliceMe =
                        noRedirectClient.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, aliceSession)
                        }
                    aliceMe.status shouldBe HttpStatusCode.OK
                    val alicePrincipal = MiruJson.decodeFromString<PrincipalResponse>(aliceMe.bodyAsText())
                    alicePrincipal.username shouldBe "alice"
                    alicePrincipal.role shouldBe MiruRole.USER
                    alicePrincipal.linkedMediaUserIds shouldContainExactly listOf("mu-alice")

                    // Replay of same state/code must fail
                    val replay =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-alice&state=$state1") {
                            cookie("miru_oidc_tx", txCookie1)
                        }
                    replay.status shouldBe HttpStatusCode.Unauthorized

                    // Local password login for OIDC account is rejected even if attempted in hybrid mode
                    val savedAlice = harness.accountRepo.findByProviderAndSubject("OIDC", "sub-alice").shouldBeRight()
                    savedAlice.shouldNotBeNull()
                    savedAlice.authProvider shouldBe "OIDC"

                    // 3. RP-Initiated Logout invalidates session and redirects to IdP end_session_endpoint
                    val logoutResp =
                        noRedirectClient.get("/api/v1/auth/oidc/logout") {
                            cookie(SESSION_COOKIE_NAME, aliceSession)
                            cookie("miru_oidc_hint", aliceHint)
                        }
                    logoutResp.status shouldBe HttpStatusCode.Found
                    val logoutLocation = Url(logoutResp.headers[HttpHeaders.Location].shouldNotBeNull())
                    logoutLocation.host shouldBe "idp.miru.test"
                    logoutLocation.encodedPath shouldBe "/o/miru/end-session"
                    logoutLocation.parameters["client_id"] shouldBe "miru-app"
                    logoutLocation.parameters["post_logout_redirect_uri"] shouldBe "http://localhost/login"
                    logoutLocation.parameters["id_token_hint"] shouldBe aliceHint

                    noRedirectClient
                        .get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, aliceSession)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    // 4. OIDC login as ADMIN (both adminGroups and userGroups present -> ADMIN wins; groups fetched via UserInfo)
                    scenario =
                        MockIdpScenario(
                            subject = "sub-admin",
                            preferredUsername = "boss",
                            email = "boss@miru.local",
                            groupsInIdToken = null,
                            groupsInUserInfo = listOf("miru-users", "miru-admins"),
                        )
                    val loginStart2 = noRedirectClient.get("/api/v1/auth/oidc/login")
                    val location2 = Url(loginStart2.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state2 = location2.parameters["state"].shouldNotBeNull()
                    val nonce2 = location2.parameters["nonce"].shouldNotBeNull()
                    val challenge2 = location2.parameters["code_challenge"].shouldNotBeNull()
                    val txCookie2 =
                        extractCookieValue(loginStart2.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx")
                            .shouldNotBeNull()
                    expectedChallengeHolder["code-admin"] = challenge2
                    expectedChallengeHolder["nonce:code-admin"] = nonce2

                    val callback2 =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-admin&state=$state2") {
                            cookie("miru_oidc_tx", txCookie2)
                        }
                    callback2.status shouldBe HttpStatusCode.Found
                    val adminSession =
                        extractCookieValue(callback2.headers.getAll(HttpHeaders.SetCookie).orEmpty(), SESSION_COOKIE_NAME)
                            .shouldNotBeNull()
                    val adminMe =
                        noRedirectClient.get("/api/v1/auth/me") {
                            cookie(SESSION_COOKIE_NAME, adminSession)
                        }
                    adminMe.status shouldBe HttpStatusCode.OK
                    val adminPrincipal = MiruJson.decodeFromString<PrincipalResponse>(adminMe.bodyAsText())
                    adminPrincipal.role shouldBe MiruRole.ADMIN

                    val accountsResp =
                        noRedirectClient.get("/api/v1/admin/accounts") {
                            cookie(SESSION_COOKIE_NAME, adminSession)
                        }
                    accountsResp.status shouldBe HttpStatusCode.OK
                    accountsResp.bodyAsText() shouldNotContain "super-secret-oidc-client-secret-value"
                }
            }
        }

        test("rejects unauthorized group (403 JSON / HTML redirect), invalid state, disabled account, and missing end_session_endpoint") {
            var scenario =
                MockIdpScenario(
                    subject = "sub-outsider",
                    preferredUsername = "outsider",
                    email = "outsider@miru.local",
                    groupsInIdToken = listOf("unrelated-group"),
                )
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(
                mode = AuthMode.LOCAL,
                allowLocalLogin = false,
                oidcEnabled = true,
                includeEndSessionEndpoint = false,
                scenarioProvider = { scenario },
                expectedChallengeHolder = expectedChallengeHolder,
            ).use { harness ->
                testApplication {
                    setupApp(harness)
                    val noRedirectClient = createClient { followRedirects = false }

                    // Unprivileged group -> JSON 403 Problem Details
                    val start1 = noRedirectClient.get("/api/v1/auth/oidc/login")
                    val loc1 = Url(start1.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state1 = loc1.parameters["state"].shouldNotBeNull()
                    expectedChallengeHolder["code-1"] = loc1.parameters["code_challenge"].shouldNotBeNull()
                    expectedChallengeHolder["nonce:code-1"] = loc1.parameters["nonce"].shouldNotBeNull()
                    val tx1 =
                        extractCookieValue(start1.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx")
                            .shouldNotBeNull()

                    val forbiddenJson =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-1&state=$state1") {
                            cookie("miru_oidc_tx", tx1)
                            header(HttpHeaders.Accept, "application/json")
                        }
                    forbiddenJson.status shouldBe HttpStatusCode.Forbidden
                    MiruJson.decodeFromString<ProblemDetails>(forbiddenJson.bodyAsText()).detail shouldContain "group"

                    // Unprivileged group -> HTML client redirects to /login?error=forbidden
                    val start2 = noRedirectClient.get("/api/v1/auth/oidc/login")
                    val loc2 = Url(start2.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state2 = loc2.parameters["state"].shouldNotBeNull()
                    expectedChallengeHolder["code-2"] = loc2.parameters["code_challenge"].shouldNotBeNull()
                    expectedChallengeHolder["nonce:code-2"] = loc2.parameters["nonce"].shouldNotBeNull()
                    val tx2 =
                        extractCookieValue(start2.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx")
                            .shouldNotBeNull()

                    val forbiddenHtml =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-2&state=$state2") {
                            cookie("miru_oidc_tx", tx2)
                            header(HttpHeaders.Accept, "text/html")
                        }
                    forbiddenHtml.status shouldBe HttpStatusCode.Found
                    forbiddenHtml.headers[HttpHeaders.Location] shouldBe "/login?error=forbidden"

                    // Invalid state / missing cookie -> 401 JSON or /login?error=invalid_state HTML
                    val invalidStateHtml =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-x&state=bad-state") {
                            cookie("miru_oidc_tx", tx2)
                            header(HttpHeaders.Accept, "text/html")
                        }
                    invalidStateHtml.status shouldBe HttpStatusCode.Found
                    invalidStateHtml.headers[HttpHeaders.Location] shouldBe "/login?error=invalid_state"

                    // Logout without end_session_endpoint redirects to /login
                    scenario =
                        MockIdpScenario(
                            subject = "sub-valid",
                            preferredUsername = "validuser",
                            email = "valid@miru.local",
                            groupsInIdToken = listOf("miru-users"),
                        )
                    val start3 = noRedirectClient.get("/api/v1/auth/oidc/login")
                    val loc3 = Url(start3.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state3 = loc3.parameters["state"].shouldNotBeNull()
                    expectedChallengeHolder["code-3"] = loc3.parameters["code_challenge"].shouldNotBeNull()
                    expectedChallengeHolder["nonce:code-3"] = loc3.parameters["nonce"].shouldNotBeNull()
                    val tx3 =
                        extractCookieValue(start3.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx")
                            .shouldNotBeNull()

                    val okCallback =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-3&state=$state3") {
                            cookie("miru_oidc_tx", tx3)
                        }
                    okCallback.status shouldBe HttpStatusCode.Found
                    val setCookies3 = okCallback.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    extractCookieValue(setCookies3, "miru_oidc_hint").shouldBeNull()
                    val session3 = extractCookieValue(setCookies3, SESSION_COOKIE_NAME).shouldNotBeNull()

                    val logoutNoEndSession =
                        noRedirectClient.get("/api/v1/auth/oidc/logout") {
                            cookie(SESSION_COOKIE_NAME, session3)
                        }
                    logoutNoEndSession.status shouldBe HttpStatusCode.Found
                    logoutNoEndSession.headers[HttpHeaders.Location] shouldBe "/login"

                    // Rejects local password login against OIDC account
                    harness.authService
                        .login("validuser", "any-password-123")
                        .let { result ->
                            result.isLeft() shouldBe true
                        }

                    // Disabled OIDC account is rejected on callback (401 JSON & /login?error=disabled HTML)
                    harness.accountRepo
                        .insert(
                            app.hononeko.miru.persistence.model.AuthAccountRecord(
                                id = "acc-oidc-disabled",
                                username = "disabledoidc",
                                usernameNormalized = "disabledoidc",
                                email = "disabled@miru.local",
                                passwordHash = "",
                                role = "USER",
                                disabled = true,
                                authProvider = "OIDC",
                                externalSubject = "sub-disabled",
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()
                    scenario =
                        MockIdpScenario(
                            subject = "sub-disabled",
                            preferredUsername = "disabledoidc",
                            email = "disabled@miru.local",
                            groupsInIdToken = listOf("miru-users"),
                        )
                    val start4 = noRedirectClient.get("/api/v1/auth/oidc/login")
                    val loc4 = Url(start4.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state4 = loc4.parameters["state"].shouldNotBeNull()
                    expectedChallengeHolder["code-4"] = loc4.parameters["code_challenge"].shouldNotBeNull()
                    expectedChallengeHolder["nonce:code-4"] = loc4.parameters["nonce"].shouldNotBeNull()
                    val tx4 =
                        extractCookieValue(start4.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx")
                            .shouldNotBeNull()
                    val disabledHtml =
                        noRedirectClient.get("/api/v1/auth/oidc/callback?code=code-4&state=$state4") {
                            cookie("miru_oidc_tx", tx4)
                            header(HttpHeaders.Accept, "text/html")
                        }
                    disabledHtml.status shouldBe HttpStatusCode.Found
                    disabledHtml.headers[HttpHeaders.Location] shouldBe "/login?error=disabled"
                }
            }
        }
        test("keeps the role an admin gave an SSO account until an admin hands it back to the groups") {
            var scenario = MockIdpScenario()
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    val alice = MockIdpScenario(subject = "sub-alice", preferredUsername = "alice", email = "alice@miru.local")
                    val boss =
                        MockIdpScenario(
                            subject = "sub-boss",
                            preferredUsername = "boss",
                            email = "boss@miru.local",
                            groupsInIdToken = listOf("miru-admins"),
                        )

                    scenario = boss
                    val admin = client.signInWithOidc("code-boss", expectedChallengeHolder)
                    scenario = alice
                    client.me(client.signInWithOidc("code-1", expectedChallengeHolder)).role shouldBe MiruRole.USER
                    // Until an admin sets her role, it follows her groups.
                    scenario = alice.copy(groupsInIdToken = listOf("miru-admins"))
                    client.me(client.signInWithOidc("code-1b", expectedChallengeHolder)).role shouldBe MiruRole.ADMIN
                    scenario = alice
                    client.me(client.signInWithOidc("code-1c", expectedChallengeHolder)).role shouldBe MiruRole.USER
                    val aliceId = harness.accountRepo.findByProviderAndSubject("OIDC", "sub-alice").shouldBeRight().shouldNotBeNull().id

                    // Promoted by an admin: the next sign-in keeps ADMIN, although her groups say USER.
                    val promoted = client.patchAccount(admin, aliceId, """{"role":"ADMIN"}""")
                    promoted.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AccountResponse>(promoted.bodyAsText()).roleMode shouldBe RoleMode.MANUAL
                    client.me(client.signInWithOidc("code-2", expectedChallengeHolder)).role shouldBe MiruRole.ADMIN

                    // With another active admin around, an admin still cannot hand over their own role.
                    val bossId = client.me(admin).accountId
                    client.patchAccount(admin, bossId, """{"roleMode":"MANUAL"}""").status shouldBe HttpStatusCode.OK
                    client.patchAccount(admin, bossId, """{"roleMode":"AUTO"}""").status shouldBe HttpStatusCode.Conflict

                    // Demoted by an admin: the next sign-in keeps USER, although her groups say ADMIN.
                    client.patchAccount(admin, aliceId, """{"role":"USER"}""").status shouldBe HttpStatusCode.OK
                    scenario = alice.copy(groupsInIdToken = listOf("miru-admins"))
                    client.me(client.signInWithOidc("code-3", expectedChallengeHolder)).role shouldBe MiruRole.USER

                    // The last active admin keeps the role an admin set, whoever asks.
                    harness.authService
                        .updateAccount(id = bossId, actingAccountId = "someone-else", roleMode = RoleMode.AUTO)
                        .shouldBeLeft()
                        .shouldBeInstanceOf<MiruError.Conflict>()

                    // Disabling and enabling the account does not touch where its role comes from.
                    client.patchAccount(admin, aliceId, """{"disabled":true}""").status shouldBe HttpStatusCode.OK
                    client.patchAccount(admin, aliceId, """{"disabled":false}""").status shouldBe HttpStatusCode.OK
                    client.me(client.signInWithOidc("code-4", expectedChallengeHolder)).role shouldBe MiruRole.USER

                    // A role set by an admin does not replace the groups that signing in takes.
                    client.patchAccount(admin, aliceId, """{"role":"ADMIN"}""").status shouldBe HttpStatusCode.OK
                    scenario = alice.copy(groupsInIdToken = listOf("unrelated-group"))
                    client.completeOidcCallback("code-5", expectedChallengeHolder).status shouldBe HttpStatusCode.Forbidden

                    // Handed back: the role stays until the next sign-in, which takes it from the groups again.
                    val handedBack = client.patchAccount(admin, aliceId, """{"roleMode":"AUTO"}""")
                    handedBack.status shouldBe HttpStatusCode.OK
                    val handedBackAccount = MiruJson.decodeFromString<AccountResponse>(handedBack.bodyAsText())
                    handedBackAccount.role shouldBe MiruRole.ADMIN
                    handedBackAccount.roleMode shouldBe RoleMode.AUTO
                    scenario = alice
                    client.me(client.signInWithOidc("code-6", expectedChallengeHolder)).role shouldBe MiruRole.USER
                }
            }
        }
        test("links media users only by a verified email, taking email_verified from userinfo when the ID token lacks it") {
            var scenario = MockIdpScenario()
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                harness.upsertPlexServer()
                harness.upsertMediaUser(id = "mu-alice", externalUserId = "100", email = "alice@miru.local")

                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    val alice =
                        MockIdpScenario(subject = "sub-alice", preferredUsername = "alice", email = "ALICE@miru.local")

                    // IdP says the email is unverified: no links.
                    scenario = alice.copy(emailVerifiedInIdToken = false)
                    client.linkedMediaUserIds(client.signInWithOidc("code-1", expectedChallengeHolder)).shouldBeEmpty()

                    // ID token omits email_verified and userinfo omits it too: still no links.
                    scenario = alice.copy(emailVerifiedInIdToken = null)
                    client.linkedMediaUserIds(client.signInWithOidc("code-2", expectedChallengeHolder)).shouldBeEmpty()

                    // userinfo marks the same address as verified: linked.
                    scenario = alice.copy(emailVerifiedInIdToken = null, emailVerifiedInUserInfo = true)
                    client.linkedMediaUserIds(client.signInWithOidc("code-3", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-alice")

                    // Without a verified email the account loses its email links, so an address that was linked
                    // before verification was required cannot keep someone else's history.
                    scenario = alice.copy(emailVerifiedInIdToken = false)
                    client.linkedMediaUserIds(client.signInWithOidc("code-4", expectedChallengeHolder)).shouldBeEmpty()

                    // userinfo verifies a different address than the ID token's: not trusted.
                    scenario =
                        alice.copy(
                            emailVerifiedInIdToken = null,
                            emailInUserInfo = "someone-else@miru.local",
                            emailVerifiedInUserInfo = true,
                        )
                    client.linkedMediaUserIds(client.signInWithOidc("code-5", expectedChallengeHolder)).shouldBeEmpty()

                    // userinfo for another subject is ignored (OIDC Core 5.3.2).
                    scenario =
                        alice.copy(
                            emailVerifiedInIdToken = null,
                            emailVerifiedInUserInfo = true,
                            subjectInUserInfo = "sub-someone-else",
                        )
                    client.linkedMediaUserIds(client.signInWithOidc("code-6", expectedChallengeHolder)).shouldBeEmpty()

                    // No email in the ID token: userinfo's verified email is used.
                    scenario =
                        alice.copy(
                            email = null,
                            emailInUserInfo = "alice@miru.local",
                            emailVerifiedInIdToken = null,
                            emailVerifiedInUserInfo = true,
                        )
                    client.linkedMediaUserIds(client.signInWithOidc("code-7", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-alice")

                    // When sign-in needs userinfo for the groups, a userinfo subject mismatch fails it.
                    scenario =
                        alice.copy(
                            groupsInIdToken = null,
                            groupsInUserInfo = listOf("miru-users"),
                            subjectInUserInfo = "sub-someone-else",
                        )
                    val mismatch = client.completeOidcCallback("code-8", expectedChallengeHolder)
                    mismatch.status shouldBe HttpStatusCode.BadGateway
                    extractCookieValue(mismatch.headers.getAll(HttpHeaders.SetCookie).orEmpty(), SESSION_COOKIE_NAME).shouldBeNull()
                }
            }
        }

        test("fetches userinfo only for email_verified without changing the username an account derives from its ID token") {
            var scenario = MockIdpScenario()
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    scenario =
                        MockIdpScenario(
                            subject = "sub-erin",
                            preferredUsername = null,
                            email = "erin@miru.local",
                            emailVerifiedInIdToken = null,
                            emailVerifiedInUserInfo = true,
                            preferredUsernameInUserInfo = "erin-from-userinfo",
                        )
                    client.me(client.signInWithOidc("code-1", expectedChallengeHolder)).username shouldBe "erin"
                }
            }
        }

        test("keeps email links in sync after each user sync, stops after an admin edits them, and resumes when switched back to AUTO") {
            var scenario = MockIdpScenario()
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                harness.upsertPlexServer()
                harness.upsertMediaUser(id = "mu-alice", externalUserId = "100", email = "alice@miru.local")
                harness.upsertMediaUser(id = "mu-other", externalUserId = "200", email = "other@miru.local")

                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    val alice = MockIdpScenario(subject = "sub-alice", preferredUsername = "alice", email = "alice@miru.local")
                    val admin =
                        MockIdpScenario(
                            subject = "sub-admin",
                            preferredUsername = "boss",
                            email = "boss@miru.local",
                            groupsInIdToken = listOf("miru-admins"),
                        )

                    scenario = alice
                    client.linkedMediaUserIds(client.signInWithOidc("code-1", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-alice")
                    val aliceAccountId =
                        harness.accountRepo.findByProviderAndSubject("OIDC", "sub-alice").shouldBeRight().shouldNotBeNull().id

                    harness.accountRepo.findById(aliceAccountId).shouldBeRight().shouldNotBeNull().emailVerified shouldBe true

                    // A sign-in with the same verified email does no linking work, even if Plex users changed...
                    harness.upsertMediaUser(id = "mu-alice-2", externalUserId = "101", email = "Alice@Miru.local")
                    val aliceSession = client.signInWithOidc("code-2", expectedChallengeHolder)
                    client.linkedMediaUserIds(aliceSession) shouldContainExactly listOf("mu-alice")

                    // ...the user sync picks the change up, and the existing session sees it straight away.
                    harness.emailLinker.relinkAll().shouldBeRight() shouldBe 1
                    client.linkedMediaUserIds(aliceSession) shouldContainExactly listOf("mu-alice", "mu-alice-2")

                    // A Plex user whose email no longer matches is unlinked at the next user sync.
                    harness.upsertMediaUser(id = "mu-alice-2", externalUserId = "101", email = "someone-else@miru.local")
                    harness.emailLinker.relinkAll().shouldBeRight() shouldBe 1
                    client.linkedMediaUserIds(aliceSession) shouldContainExactly listOf("mu-alice")
                    harness.emailLinker.relinkAll().shouldBeRight() shouldBe 0

                    scenario = admin
                    val adminSession = client.signInWithOidc("code-admin", expectedChallengeHolder)

                    suspend fun putAsAdmin(
                        path: String,
                        body: String,
                    ) = client.put(path) {
                        cookie(SESSION_COOKIE_NAME, adminSession)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                    // An admin edit makes the links manual; neither the user sync nor sign-in changes them, even when emptied.
                    val handPicked =
                        putAsAdmin(
                            "/api/v1/admin/accounts/$aliceAccountId/media-users",
                            MiruJson.encodeToString(SetLinkedMediaUsersRequest(linkedMediaUserIds = setOf("mu-other"))),
                        )
                    handPicked.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<AccountResponse>(handPicked.bodyAsText()).mediaUserLinkMode shouldBe
                        MediaUserLinkMode.MANUAL
                    harness.emailLinker.relinkAll().shouldBeRight() shouldBe 0
                    scenario = alice.copy(email = "alice.new@miru.local")
                    client.linkedMediaUserIds(client.signInWithOidc("code-4", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-other")
                    scenario = alice
                    client.signInWithOidc("code-4b", expectedChallengeHolder)

                    putAsAdmin(
                        "/api/v1/admin/accounts/$aliceAccountId/media-users",
                        MiruJson.encodeToString(SetLinkedMediaUsersRequest(linkedMediaUserIds = emptySet())),
                    ).status shouldBe HttpStatusCode.OK
                    client.linkedMediaUserIds(client.signInWithOidc("code-5", expectedChallengeHolder)).shouldBeEmpty()

                    // Switching back to AUTO relinks right away from the email verified at the last sign-in.
                    val backToAuto =
                        putAsAdmin(
                            "/api/v1/admin/accounts/$aliceAccountId/media-user-link-mode",
                            MiruJson.encodeToString(SetMediaUserLinkModeRequest(mode = MediaUserLinkMode.AUTO)),
                        )
                    backToAuto.status shouldBe HttpStatusCode.OK
                    val autoAccount = MiruJson.decodeFromString<AccountResponse>(backToAuto.bodyAsText())
                    autoAccount.mediaUserLinkMode shouldBe MediaUserLinkMode.AUTO
                    autoAccount.emailVerified shouldBe true
                    autoAccount.linkedMediaUserIds shouldContainExactly listOf("mu-alice")
                    client.linkedMediaUserIds(client.signInWithOidc("code-6", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-alice")

                    // Switching to MANUAL freezes the current links.
                    val frozen =
                        putAsAdmin(
                            "/api/v1/admin/accounts/$aliceAccountId/media-user-link-mode",
                            MiruJson.encodeToString(SetMediaUserLinkModeRequest(mode = MediaUserLinkMode.MANUAL)),
                        )
                    MiruJson.decodeFromString<AccountResponse>(frozen.bodyAsText()).let { account ->
                        account.mediaUserLinkMode shouldBe MediaUserLinkMode.MANUAL
                        account.linkedMediaUserIds shouldContainExactly listOf("mu-alice")
                    }

                    // AUTO is refused for local accounts, which never sign in with SSO.
                    val local =
                        harness.authService
                            .createAccount(username = "localviewer", password = "Some-password-123")
                            .shouldBeRight()
                    local.account.mediaUserLinkMode shouldBe "MANUAL"
                    putAsAdmin(
                        "/api/v1/admin/accounts/${local.account.id}/media-user-link-mode",
                        MiruJson.encodeToString(SetMediaUserLinkModeRequest(mode = MediaUserLinkMode.AUTO)),
                    ).status shouldBe HttpStatusCode.Conflict
                    putAsAdmin(
                        "/api/v1/admin/accounts/missing/media-user-link-mode",
                        MiruJson.encodeToString(SetMediaUserLinkModeRequest(mode = MediaUserLinkMode.MANUAL)),
                    ).status shouldBe HttpStatusCode.NotFound

                    val accounts =
                        MiruJson.decodeFromString<AccountListResponse>(
                            client.get("/api/v1/admin/accounts") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    accounts.emailLinkingEnabled shouldBe true
                    accounts
                        .accounts
                        .associate { it.username to it.mediaUserLinkMode } shouldBe
                        mapOf(
                            "alice" to MediaUserLinkMode.MANUAL,
                            "boss" to MediaUserLinkMode.AUTO,
                            "localviewer" to MediaUserLinkMode.MANUAL,
                        )
                }
            }
        }

        test("does not link by email when OIDC_AUTO_LINK_BY_EMAIL is false") {
            val expectedChallengeHolder = mutableMapOf<String, String>()
            createOidcHarness(
                autoLinkByEmail = false,
                scenarioProvider = {
                    MockIdpScenario(subject = "sub-alice", preferredUsername = "alice", email = "alice@miru.local")
                },
                expectedChallengeHolder = expectedChallengeHolder,
            ).use { harness ->
                harness.upsertPlexServer()
                harness.upsertMediaUser(id = "mu-alice", externalUserId = "100", email = "alice@miru.local")

                harness.authService.emailLinkingEnabled shouldBe false
                harness.emailLinker.relinkAll().shouldBeRight() shouldBe 0

                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    client.linkedMediaUserIds(client.signInWithOidc("code-1", expectedChallengeHolder)).shouldBeEmpty()
                }
                // Leaving emailVerified unset while linking is off ensures enabling it later links on next sign-in.
                val stored = harness.accountRepo.findByProviderAndSubject("OIDC", "sub-alice").shouldBeRight().shouldNotBeNull()
                stored.emailVerified.shouldBeNull()
            }
        }

        test("keeps a verified SSO account's links when userinfo fails transiently during sign-in") {
            var scenario = MockIdpScenario()
            val expectedChallengeHolder = mutableMapOf<String, String>()

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                harness.upsertPlexServer()
                harness.upsertMediaUser(id = "mu-alice", externalUserId = "100", email = "alice@miru.local")

                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    val alice =
                        MockIdpScenario(
                            subject = "sub-alice",
                            preferredUsername = "alice",
                            email = "alice@miru.local",
                            emailVerifiedInIdToken = null,
                            emailVerifiedInUserInfo = true,
                        )

                    // Initial verified sign-in via userinfo links Alice.
                    scenario = alice
                    client.linkedMediaUserIds(client.signInWithOidc("code-1", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-alice")

                    // A transient 502 from userinfo must not mark the account unverified or wipe her links.
                    scenario = alice.copy(userInfoFails = true)
                    client.linkedMediaUserIds(client.signInWithOidc("code-2", expectedChallengeHolder)) shouldContainExactly
                        listOf("mu-alice")
                    val stored =
                        harness.accountRepo.findByProviderAndSubject("OIDC", "sub-alice").shouldBeRight().shouldNotBeNull()
                    stored.emailVerified shouldBe true
                }
            }
        }

        test("unlinks an AUTO account when the IdP publishes no userinfo endpoint and omits email_verified") {
            val expectedChallengeHolder = mutableMapOf<String, String>()
            val scenario =
                MockIdpScenario(
                    subject = "sub-noui",
                    preferredUsername = "noui",
                    email = "alice@miru.local",
                    emailVerifiedInIdToken = null,
                    includeUserInfoEndpoint = false,
                )

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                harness.upsertPlexServer()
                harness.upsertMediaUser(id = "mu-alice", externalUserId = "100", email = "alice@miru.local")

                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }
                    client.linkedMediaUserIds(client.signInWithOidc("code-1", expectedChallengeHolder)).shouldBeEmpty()
                }
                val stored = harness.accountRepo.findByProviderAndSubject("OIDC", "sub-noui").shouldBeRight().shouldNotBeNull()
                stored.emailVerified shouldBe false
            }
        }

        test("logs requests and redirect targets without their query strings") {
            val expectedChallengeHolder = mutableMapOf<String, String>()
            val scenario = MockIdpScenario(subject = "sub-log", groupsInIdToken = listOf("miru-users"))
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            rootLogger.addAppender(appender)

            val secrets = mutableListOf<String>()
            try {
                createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                    testApplication {
                        setupApp(harness)
                        val client = createClient { followRedirects = false }
                        val start = client.get("/api/v1/auth/oidc/login")
                        val location = Url(start.headers[HttpHeaders.Location].shouldNotBeNull())
                        secrets += listOf("state", "nonce", "code_challenge").map { location.parameters[it].shouldNotBeNull() }

                        val callback = client.completeOidcCallback("code-log", expectedChallengeHolder)
                        val hint = callback.cookieValue("miru_oidc_hint")
                        secrets += listOf("code-log", callback.cookieValue(SESSION_COOKIE_NAME), hint)
                        client.get("/api/v1/auth/oidc/logout") { cookie("miru_oidc_hint", hint) }
                    }
                }
            } finally {
                rootLogger.detachAppender(appender)
            }

            // Copied because other specs log to the root logger too
            val messages = appender.list.toList().map { it.formattedMessage }
            listOf(
                "302 Found: GET - /api/v1/auth/oidc/login in \\d+ms -> https://idp.miru.test/o/miru/authorize",
                "302 Found: GET - /api/v1/auth/oidc/callback in \\d+ms -> /",
                "302 Found: GET - /api/v1/auth/oidc/logout in \\d+ms -> https://idp.miru.test/o/miru/end-session",
            ).forEach { expected -> messages.count { Regex(expected).matches(it) } shouldBeGreaterThanOrEqual 1 }
            secrets shouldHaveSize 6
            for (secret in secrets) {
                messages.filter { secret in it }.shouldBeEmpty()
            }
        }

        test("sets Secure flag consistently on miru_oidc_tx, miru_session, miru_oidc_hint, and OIDC cookie-clearing paths") {
            val expectedChallengeHolder = mutableMapOf<String, String>()
            val scenario =
                MockIdpScenario(
                    subject = "sub-sec",
                    preferredUsername = "secuser",
                    email = "sec@miru.local",
                    groupsInIdToken = listOf("miru-users"),
                )

            fun hasSecureFlag(setCookieHeaders: List<String>, name: String): Boolean {
                val target = setCookieHeaders.first { it.startsWith("$name=") }
                return target.split(';').any { it.trim().equals("Secure", ignoreCase = true) }
            }

            createOidcHarness(scenarioProvider = { scenario }, expectedChallengeHolder = expectedChallengeHolder).use { harness ->
                testApplication {
                    setupApp(harness)
                    val client = createClient { followRedirects = false }

                    // Plain HTTP omits Secure in AUTO mode
                    val plainStart = client.get("/api/v1/auth/oidc/login")
                    hasSecureFlag(plainStart.headers.getAll(HttpHeaders.SetCookie).orEmpty(), "miru_oidc_tx") shouldBe false

                    // X-Forwarded-Proto: https sets Secure across login, callback (including cleared tx + session + hint), and logout
                    val start =
                        client.get("/api/v1/auth/oidc/login") {
                            header("X-Forwarded-Proto", "https")
                        }
                    val startCookies = start.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    hasSecureFlag(startCookies, "miru_oidc_tx") shouldBe true

                    val loc = Url(start.headers[HttpHeaders.Location].shouldNotBeNull())
                    val state = loc.parameters["state"].shouldNotBeNull()
                    expectedChallengeHolder["code-sec"] = loc.parameters["code_challenge"].shouldNotBeNull()
                    expectedChallengeHolder["nonce:code-sec"] = loc.parameters["nonce"].shouldNotBeNull()
                    val txCookie = extractCookieValue(startCookies, "miru_oidc_tx").shouldNotBeNull()

                    val callback =
                        client.get("/api/v1/auth/oidc/callback?code=code-sec&state=$state") {
                            header("X-Forwarded-Proto", "https")
                            cookie("miru_oidc_tx", txCookie)
                        }
                    val callbackCookies = callback.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    hasSecureFlag(callbackCookies, "miru_oidc_tx") shouldBe true
                    hasSecureFlag(callbackCookies, SESSION_COOKIE_NAME) shouldBe true
                    hasSecureFlag(callbackCookies, "miru_oidc_hint") shouldBe true

                    val sessionCookie = extractCookieValue(callbackCookies, SESSION_COOKIE_NAME).shouldNotBeNull()
                    val hintCookie = extractCookieValue(callbackCookies, "miru_oidc_hint").shouldNotBeNull()

                    val logout =
                        client.get("/api/v1/auth/oidc/logout") {
                            header("X-Forwarded-Proto", "https")
                            cookie(SESSION_COOKIE_NAME, sessionCookie)
                            cookie("miru_oidc_hint", hintCookie)
                        }
                    val logoutCookies = logout.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    hasSecureFlag(logoutCookies, SESSION_COOKIE_NAME) shouldBe true
                    hasSecureFlag(logoutCookies, "miru_oidc_hint") shouldBe true
                }
            }
        }
    })
