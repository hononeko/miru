package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.plex.PlexOAuthClient
import app.hononeko.miru.adapter.plex.PlexOAuthService
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.createAvatarHttpClient
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private const val SESSION_SECRET = "plex-oauth-routes-session-secret-32-chars"
private const val ENCRYPTION_KEY = "plex-oauth-routes-encryption-key-32chars"
private const val RAW_ACCOUNT_TOKEN = "raw-plex-account-oauth-token-secret"
private const val RAW_SERVER_TOKEN = "raw-plex-server-access-token-secret"
private const val MOCK_SERVER_URL = "http://192.168.1.50:32400"

private class OAuthTestHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val serverRepo: ExposedMediaServerRepository,
    val authService: AuthService,
    val runtimeManager: PlexRuntimeManager,
    val oauthService: PlexOAuthService,
    val artworkProxyService: ArtworkProxyService,
    val appConfig: AppConfig,
) : AutoCloseable {
    override fun close() {
        runCatching { kotlinx.coroutines.runBlocking { runtimeManager.stop() } }
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createPlexServerMockEngine(
    plexConfig: PlexConfig,
    plexServerReachable: () -> Boolean,
): MockEngine {
    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    return MockEngine { req ->
        if (!plexServerReachable()) {
            respond("Unavailable", HttpStatusCode.ServiceUnavailable)
        } else {
            when (req.url.encodedPath) {
                "/" -> {
                    req.headers["X-Plex-Token"] shouldBe
                        if (plexConfig.isStaticConfigured) plexConfig.token else RAW_SERVER_TOKEN
                    respond(
                        content =
                            """
                            {
                              "MediaContainer": {
                                "machineIdentifier": "mach-plex-1",
                                "friendlyName": "Living Room Plex",
                                "version": "1.41.0",
                                "platform": "Linux"
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                }
                "/status/sessions" ->
                    respond(
                        content = """{"MediaContainer":{"size":0,"Metadata":[]}}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
    }
}

private fun createPlexTvMockEngine(pinAuthorized: () -> Boolean): MockEngine {
    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    return MockEngine { req ->
        when (req.url.encodedPath) {
            "/api/v2/pins" ->
                respond(
                    content = """{"id":1001,"code":"PINCODE1","expiresIn":900}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders,
                )
            "/api/v2/pins/1001" -> {
                val tokJson = if (pinAuthorized()) """"$RAW_ACCOUNT_TOKEN"""" else "null"
                respond(
                    content = """{"id":1001,"code":"PINCODE1","expiresIn":890,"authToken":$tokJson}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            "/api/v2/resources" ->
                respond(
                    content =
                        """
                        [
                          {
                            "name": "Living Room Plex",
                            "productVersion": "1.41.0",
                            "platform": "Linux",
                            "clientIdentifier": "mach-plex-1",
                            "provides": "server",
                            "owned": 1,
                            "presence": 1,
                            "accessToken": "$RAW_SERVER_TOKEN",
                            "connections": [
                              {"protocol":"http","uri":"$MOCK_SERVER_URL","local":1,"relay":0,"IPv6":0},
                              {"protocol":"https","uri":"https://plex.example.com:32400","local":0,"relay":0,"IPv6":0}
                            ]
                          }
                        ]
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            else -> respond("Not found", HttpStatusCode.NotFound)
        }
    }
}

private fun createOAuthHarness(
    plexConfig: PlexConfig = PlexConfig(websocketEnabled = false, encryptionKey = ENCRYPTION_KEY),
    pinAuthorized: () -> Boolean = { true },
    plexServerReachable: () -> Boolean = { true },
): OAuthTestHarness {
    val dir = Files.createTempDirectory("miru-plex-oauth-test-")
    val dbPath = dir.resolve("miru-oauth.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val nowMs = AtomicLong(1_700_000_000_000L)
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = SESSION_SECRET)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
            clock = { nowMs.get() },
        )
    val plexServerEngine = createPlexServerMockEngine(plexConfig, plexServerReachable)
    val plexTvEngine = createPlexTvMockEngine(pinAuthorized)

    val runtimeManager =
        PlexRuntimeManager(
            plexConfig = plexConfig,
            databaseHandle = handle,
            sessionConfig = SessionConfig(),
            mediaServerRepository = serverRepo,
            httpClientEngineFactory = { plexServerEngine },
        )
    val oauthClient =
        PlexOAuthClient(
            httpClient = createPlexHttpClient(engine = plexTvEngine),
            clock = { nowMs.get() },
        )
    val oauthService =
        PlexOAuthService(
            plexConfig = plexConfig,
            oauthClient = oauthClient,
            mediaServerRepository = serverRepo,
            runtimeManager = runtimeManager,
            clock = { nowMs.get() },
        )
    val artworkProxyService =
        ArtworkProxyService(
            adapter = runtimeManager.adapter,
            userRepository = userRepo,
            externalAvatarClient = createAvatarHttpClient(engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }),
            plexBaseUrlProvider = { runtimeManager.currentBaseUrl() },
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = plexConfig,
            session = SessionConfig(),
            auth = authConfig,
        )
    return OAuthTestHarness(
        tempDir = dir,
        dbHandle = handle,
        serverRepo = serverRepo,
        authService = authService,
        runtimeManager = runtimeManager,
        oauthService = oauthService,
        artworkProxyService = artworkProxyService,
        appConfig = appConfig,
    )
}

private suspend fun seedKeys(harness: OAuthTestHarness): Pair<String, String> {
    val admin = harness.authService.setupInitialAdmin("admin", "Admin-password-1234").shouldBeRight()
    val adminKey =
        harness.authService
            .createApiKey(accountId = admin.principal.accountId, name = "admin-key", role = MiruRole.ADMIN)
            .shouldBeRight()
            .rawKey
    val userAcc =
        harness.authService
            .createAccount(username = "viewer", password = "Viewer-password-1234", role = MiruRole.USER)
            .shouldBeRight()
    val userKey =
        harness.authService
            .createApiKey(accountId = userAcc.account.id, name = "user-key", role = MiruRole.USER)
            .shouldBeRight()
            .rawKey
    return adminKey to userKey
}

private fun ApplicationTestBuilder.setupApp(harness: OAuthTestHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            artworkProxyService = harness.artworkProxyService,
            plexOAuthService = harness.oauthService,
        )
    }
}

class PlexOAuthRoutesTest :
    FunSpec({
        test("enforces ADMIN RBAC on all /api/v1/admin/plex/* endpoints") {
            createOAuthHarness().use { harness ->
                val (_, userKey) = seedKeys(harness)
                testApplication {
                    setupApp(harness)

                    client.get("/api/v1/admin/plex/status").status shouldBe HttpStatusCode.Unauthorized
                    client.post("/api/v1/admin/plex/oauth/start").status shouldBe HttpStatusCode.Unauthorized

                    client
                        .get("/api/v1/admin/plex/status") { header("X-Api-Key", userKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/api/v1/admin/plex/oauth/start") { header("X-Api-Key", userKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/admin/plex/oauth/poll/1001") { header("X-Api-Key", userKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/admin/plex/oauth/servers") { header("X-Api-Key", userKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/api/v1/admin/plex/oauth/select") {
                            header("X-Api-Key", userKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"machineId":"m1","connectionUri":"http://plex.local:32400"}""")
                        }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("completes full PIN OAuth flow, encrypts token at rest, hot-reloads runtime, and never leaks raw tokens") {
            var authorized = false
            createOAuthHarness(pinAuthorized = { authorized }).use { harness ->
                val (adminKey, _) = seedKeys(harness)
                testApplication {
                    setupApp(harness)

                    // Initial status: NOT_CONFIGURED
                    val initialResp =
                        client.get("/api/v1/admin/plex/status") { header("X-Api-Key", adminKey) }
                    initialResp.status shouldBe HttpStatusCode.OK
                    val initialStatus = MiruJson.decodeFromString<PlexConnectionStatus>(initialResp.bodyAsText())
                    initialStatus.configured shouldBe false
                    initialStatus.credentialSource shouldBe PlexCredentialSource.NOT_CONFIGURED
                    initialStatus.encryptionKeyConfigured shouldBe true
                    initialStatus.reachable shouldBe false

                    // Start PIN
                    val startResp =
                        client.post("/api/v1/admin/plex/oauth/start") { header("X-Api-Key", adminKey) }
                    startResp.status shouldBe HttpStatusCode.OK
                    val startBody = startResp.bodyAsText()
                    startBody shouldNotContain RAW_ACCOUNT_TOKEN
                    startBody shouldNotContain RAW_SERVER_TOKEN
                    val started = MiruJson.decodeFromString<PlexOAuthStartResponse>(startBody)
                    started.pinId shouldBe 1001L

                    // Poll while pending
                    val pendingResp =
                        client.get("/api/v1/admin/plex/oauth/poll/${started.pinId}") { header("X-Api-Key", adminKey) }
                    pendingResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<PlexOAuthPollResponse>(pendingResp.bodyAsText()).status shouldBe
                        PlexOAuthPinStatus.PENDING

                    // Discover before authorization -> 409 Conflict
                    client
                        .get("/api/v1/admin/plex/oauth/servers?pinId=${started.pinId}") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.Conflict

                    // User authorizes on app.plex.tv
                    authorized = true
                    val authResp =
                        client.get("/api/v1/admin/plex/oauth/poll/${started.pinId}") { header("X-Api-Key", adminKey) }
                    authResp.status shouldBe HttpStatusCode.OK
                    val authBody = authResp.bodyAsText()
                    authBody shouldNotContain RAW_ACCOUNT_TOKEN
                    MiruJson.decodeFromString<PlexOAuthPollResponse>(authBody).status shouldBe PlexOAuthPinStatus.AUTHORIZED

                    // Discover servers (no tokens in response)
                    val serversResp =
                        client.get("/api/v1/admin/plex/oauth/servers?pinId=${started.pinId}") { header("X-Api-Key", adminKey) }
                    serversResp.status shouldBe HttpStatusCode.OK
                    val serversBody = serversResp.bodyAsText()
                    serversBody shouldNotContain RAW_ACCOUNT_TOKEN
                    serversBody shouldNotContain RAW_SERVER_TOKEN
                    val discovered = MiruJson.decodeFromString<PlexDiscoveredServersResponse>(serversBody)
                    discovered.servers shouldHaveSize 1
                    discovered.servers.single().machineId shouldBe "mach-plex-1"

                    // Select server & hot-reload
                    val selectResp =
                        client.post("/api/v1/admin/plex/oauth/select") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    PlexSelectServerRequest(
                                        pinId = started.pinId,
                                        machineId = "mach-plex-1",
                                        connectionUri = MOCK_SERVER_URL,
                                    ),
                                ),
                            )
                        }
                    selectResp.status shouldBe HttpStatusCode.OK
                    val selectBody = selectResp.bodyAsText()
                    selectBody shouldNotContain RAW_ACCOUNT_TOKEN
                    selectBody shouldNotContain RAW_SERVER_TOKEN
                    val selected = MiruJson.decodeFromString<PlexSelectServerResponse>(selectBody)
                    selected.saved shouldBe true
                    selected.hotReloaded shouldBe true
                    selected.envOverrideActive shouldBe false
                    selected.credentialSource shouldBe PlexCredentialSource.STORED_ENCRYPTED
                    selected.baseUrl shouldBe MOCK_SERVER_URL

                    // Verify DB has encrypted token + clientIdentifier
                    val dbServer = harness.serverRepo.findByMachineId("mach-plex-1").shouldBeRight().shouldNotBeNull()
                    val encToken = dbServer.encryptedToken.shouldNotBeNull()
                    encToken shouldStartWith "v1:"
                    encToken shouldNotContain RAW_SERVER_TOKEN
                    CredentialCrypto.decrypt(encToken, ENCRYPTION_KEY).shouldBeRight() shouldBe RAW_SERVER_TOKEN
                    dbServer.clientIdentifier.shouldNotBeNull()
                    dbServer.toString() shouldNotContain encToken

                    // Verify runtime manager is hot-reloaded and /status reports reachable
                    harness.runtimeManager.isConfigured() shouldBe true
                    harness.runtimeManager.currentBaseUrl() shouldBe MOCK_SERVER_URL

                    val finalStatusResp =
                        client.get("/api/v1/admin/plex/status") { header("X-Api-Key", adminKey) }
                    finalStatusResp.status shouldBe HttpStatusCode.OK
                    val finalStatus = MiruJson.decodeFromString<PlexConnectionStatus>(finalStatusResp.bodyAsText())
                    finalStatus.configured shouldBe true
                    finalStatus.credentialSource shouldBe PlexCredentialSource.STORED_ENCRYPTED
                    finalStatus.reachable shouldBe true
                    finalStatus.serverName shouldBe "Living Room Plex"
                    finalStatus.baseUrl shouldBe MOCK_SERVER_URL
                }
            }
        }

        test("rejects selectServer with 400 ProblemDetails when MIRU_ENCRYPTION_KEY is missing") {
            createOAuthHarness(
                plexConfig = PlexConfig(websocketEnabled = false, encryptionKey = null),
            ).use { harness ->
                val (adminKey, _) = seedKeys(harness)
                testApplication {
                    setupApp(harness)

                    val start =
                        MiruJson.decodeFromString<PlexOAuthStartResponse>(
                            client.post("/api/v1/admin/plex/oauth/start") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    client.get("/api/v1/admin/plex/oauth/poll/${start.pinId}") { header("X-Api-Key", adminKey) }

                    val selectResp =
                        client.post("/api/v1/admin/plex/oauth/select") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    PlexSelectServerRequest(
                                        pinId = start.pinId,
                                        machineId = "mach-plex-1",
                                        connectionUri = MOCK_SERVER_URL,
                                    ),
                                ),
                            )
                        }
                    selectResp.status shouldBe HttpStatusCode.BadRequest
                    val problem = MiruJson.decodeFromString<ProblemDetails>(selectResp.bodyAsText())
                    problem.status shouldBe 400
                    harness.serverRepo.findAll().shouldBeRight() shouldHaveSize 0
                }
            }
        }

        test("preserves ENV_OVERRIDE precedence and reports hotReloaded=false on select") {
            val envConfig =
                PlexConfig(
                    baseUrl = "http://env-plex.local:32400",
                    token = "env-override-token",
                    websocketEnabled = false,
                    encryptionKey = ENCRYPTION_KEY,
                )
            createOAuthHarness(plexConfig = envConfig).use { harness ->
                harness.runtimeManager.initialize().shouldBeRight() shouldBe true
                val (adminKey, _) = seedKeys(harness)
                testApplication {
                    setupApp(harness)

                    val status =
                        MiruJson.decodeFromString<PlexConnectionStatus>(
                            client.get("/api/v1/admin/plex/status") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    status.credentialSource shouldBe PlexCredentialSource.ENV_OVERRIDE
                    status.reachable shouldBe true
                    status.baseUrl shouldBe "http://env-plex.local:32400"

                    val start =
                        MiruJson.decodeFromString<PlexOAuthStartResponse>(
                            client.post("/api/v1/admin/plex/oauth/start") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    client.get("/api/v1/admin/plex/oauth/poll/${start.pinId}") { header("X-Api-Key", adminKey) }

                    val selectResp =
                        client.post("/api/v1/admin/plex/oauth/select") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    PlexSelectServerRequest(
                                        pinId = start.pinId,
                                        machineId = "mach-plex-1",
                                        connectionUri = MOCK_SERVER_URL,
                                    ),
                                ),
                            )
                        }
                    selectResp.status shouldBe HttpStatusCode.OK
                    val selected = MiruJson.decodeFromString<PlexSelectServerResponse>(selectResp.bodyAsText())
                    selected.saved shouldBe true
                    selected.hotReloaded shouldBe false
                    selected.envOverrideActive shouldBe true
                    selected.credentialSource shouldBe PlexCredentialSource.ENV_OVERRIDE
                    harness.runtimeManager.currentBaseUrl() shouldBe "http://env-plex.local:32400"
                }
            }
        }

        test("handles invalid pinIds, expired or unready PINs, and select validation") {
            var pinStatus = "pending"
            createOAuthHarness(
                pinAuthorized = { pinStatus == "authorized" },
                plexServerReachable = { false },
            ).use { harness ->
                val (adminKey, _) = seedKeys(harness)
                val nowMs = AtomicLong(1_700_000_000_000L)
                val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                val edgeTvEngine =
                    MockEngine { req ->
                        when (req.url.encodedPath) {
                            "/api/v2/pins" ->
                                respond("""{"id":2002,"code":"EDGEPIN","expiresIn":60}""", HttpStatusCode.Created, jsonHeaders)
                            "/api/v2/pins/2002" ->
                                when (pinStatus) {
                                    "expired" -> respond("Missing", HttpStatusCode.NotFound)
                                    "authorized" ->
                                        respond(
                                            """{"id":2002,"code":"EDGEPIN","expiresIn":60,"authToken":"$RAW_ACCOUNT_TOKEN"}""",
                                            HttpStatusCode.OK,
                                            jsonHeaders,
                                        )
                                    else ->
                                        respond(
                                            """{"id":2002,"code":"EDGEPIN","expiresIn":60,"authToken":null}""",
                                            HttpStatusCode.OK,
                                            jsonHeaders,
                                        )
                                }
                            "/api/v2/resources" ->
                                respond(
                                    """[{"name":"Plex 2","clientIdentifier":"mach-2","provides":"server","connections":[]}]""",
                                    HttpStatusCode.OK,
                                    jsonHeaders,
                                )
                            else -> respond("Not found", HttpStatusCode.NotFound)
                        }
                    }
                val edgeOAuth =
                    PlexOAuthService(
                        plexConfig = harness.appConfig.plex,
                        oauthClient = PlexOAuthClient(httpClient = createPlexHttpClient(edgeTvEngine), clock = { nowMs.get() }),
                        mediaServerRepository = harness.serverRepo,
                        runtimeManager = harness.runtimeManager,
                        clock = { nowMs.get() },
                    )
                val corsConfig =
                    harness.appConfig.copy(
                        server =
                            ServerConfig(
                                host = "127.0.0.1",
                                port = 8080,
                                corsAllowedOrigins = listOf("https://miru.example.com", "http://localhost:5173", "app.local"),
                            ),
                    )

                testApplication {
                    application {
                        miruModule(config = corsConfig, authService = harness.authService, plexOAuthService = edgeOAuth)
                    }

                    client.get("/api/v1/admin/plex/oauth/poll/abc") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.BadRequest
                    client.get("/api/v1/admin/plex/oauth/poll/99999") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.NotFound
                    client.get("/api/v1/admin/plex/oauth/servers?pinId=0") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.BadRequest
                    client.get("/api/v1/admin/plex/oauth/servers") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.NotFound

                    val start =
                        MiruJson.decodeFromString<PlexOAuthStartResponse>(
                            client.post("/api/v1/admin/plex/oauth/start") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    client.get("/api/v1/admin/plex/oauth/servers?pinId=${start.pinId}") {
                        header("X-Api-Key", adminKey)
                    }.status shouldBe HttpStatusCode.Conflict

                    client.post("/api/v1/admin/plex/oauth/select") {
                        header("X-Api-Key", adminKey)
                        contentType(ContentType.Application.Json)
                        setBody("""{"pinId":${start.pinId},"machineId":"","connectionUri":"ftp://bad"}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    pinStatus = "authorized"
                    client.get("/api/v1/admin/plex/oauth/poll/${start.pinId}") {
                        header("X-Api-Key", adminKey)
                    }.status shouldBe HttpStatusCode.OK
                    // Cached AUTHORIZED re-poll
                    MiruJson.decodeFromString<PlexOAuthPollResponse>(
                        client.get("/api/v1/admin/plex/oauth/poll/${start.pinId}") {
                            header("X-Api-Key", adminKey)
                        }.bodyAsText(),
                    ).status shouldBe PlexOAuthPinStatus.AUTHORIZED
                    // Discover without pinId uses latest authorized session
                    client.get("/api/v1/admin/plex/oauth/servers") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.OK

                    client.post("/api/v1/admin/plex/oauth/select") {
                        header("X-Api-Key", adminKey)
                        contentType(ContentType.Application.Json)
                        setBody("""{"machineId":"missing-mach","connectionUri":"http://plex2.local:32400"}""")
                    }.status shouldBe HttpStatusCode.NotFound

                    client.post("/api/v1/admin/plex/oauth/select") {
                        header("X-Api-Key", adminKey)
                        contentType(ContentType.Application.Json)
                        setBody("""{"machineId":"mach-2","connectionUri":"http://plex2.local:32400"}""")
                    }.status shouldBe HttpStatusCode.OK

                    pinStatus = "expired"
                    val expStart =
                        MiruJson.decodeFromString<PlexOAuthStartResponse>(
                            client.post("/api/v1/admin/plex/oauth/start") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    MiruJson.decodeFromString<PlexOAuthPollResponse>(
                        client.get("/api/v1/admin/plex/oauth/poll/${expStart.pinId}") {
                            header("X-Api-Key", adminKey)
                        }.bodyAsText(),
                    ).status shouldBe PlexOAuthPinStatus.EXPIRED
                    client.get("/api/v1/admin/plex/oauth/servers?pinId=${expStart.pinId}") {
                        header("X-Api-Key", adminKey)
                    }.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("reports status for inactive runtime, missing encryption key on stored server, and unreachable server") {
            createOAuthHarness(plexServerReachable = { false }).use { harness ->
                val (adminKey, _) = seedKeys(harness)
                val encrypted = CredentialCrypto.encrypt(RAW_SERVER_TOKEN, ENCRYPTION_KEY).shouldBeRight()
                harness.serverRepo
                    .upsert(
                        app.hononeko.miru.persistence.model.MediaServerRecord(
                            id = "srv-status",
                            name = "Stored Plex",
                            serverType = "plex",
                            machineId = "mach-status",
                            baseUrl = MOCK_SERVER_URL,
                            encryptedToken = encrypted,
                            clientIdentifier = "cid-status",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                val noKeyOAuth =
                    PlexOAuthService(
                        plexConfig = PlexConfig(websocketEnabled = false, encryptionKey = null),
                        oauthClient =
                            PlexOAuthClient(
                                httpClient = createPlexHttpClient(engine = MockEngine { error("unused") }),
                            ),
                        mediaServerRepository = harness.serverRepo,
                        runtimeManager = harness.runtimeManager,
                    )

                noKeyOAuth.getConnectionStatus().shouldBeRight().let { st ->
                    st.configured shouldBe true
                    st.credentialSource shouldBe PlexCredentialSource.STORED_ENCRYPTED
                    st.encryptionKeyConfigured shouldBe false
                    st.reachable shouldBe false
                }

                // Encrypted server stored, encryption key configured, but runtimeManager is inactive
                harness.oauthService.getConnectionStatus().shouldBeRight().let { st ->
                    st.configured shouldBe true
                    st.encryptionKeyConfigured shouldBe true
                    st.reachable shouldBe false
                }

                // Active runtime where checkHealth fails
                testApplication {
                    setupApp(harness)
                    harness.runtimeManager.initialize().shouldBeRight() shouldBe true
                    val body = client.get("/api/v1/admin/plex/status") { header("X-Api-Key", adminKey) }.bodyAsText()
                    val status = MiruJson.decodeFromString<PlexConnectionStatus>(body)
                    status.configured shouldBe true
                    status.reachable shouldBe false
                }
            }
        }
    })
