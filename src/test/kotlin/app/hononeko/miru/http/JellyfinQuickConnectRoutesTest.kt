package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectClient
import app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectService
import app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager
import app.hononeko.miru.adapter.jellyfin.createJellyfinHttpClient
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val JF_TEST_SESSION_SECRET = "jellyfin-qc-routes-session-secret-32c"
private const val JF_TEST_ENCRYPTION_KEY = "jellyfin-qc-routes-encryption-key-32c"
private const val RAW_QC_SECRET = "raw-jellyfin-qc-secret-xyz"
private const val RAW_TEMP_ACCESS_TOKEN = "raw-jellyfin-temp-access-token"
private const val RAW_OLD_API_KEY = "raw-jellyfin-old-miru-api-key"
private const val RAW_NEW_API_KEY = "raw-jellyfin-new-miru-api-key"
private const val MOCK_JF_URL = "http://192.168.1.75:8096"

private class MockJellyfinQuickConnectState(
    var quickConnectEnabled: Boolean = true,
    var authenticated: Boolean = false,
    var expired: Boolean = false,
    var isAdmin: Boolean = true,
    var machineId: String = "jf-mach-1",
    var serverName: String = "Studio Jellyfin",
    var version: String = "10.10.3",
    var reachable: Boolean = true,
    var nonJellyfin: Boolean = false,
    var failCreateKey: Boolean = false,
    var omitCreatedKeyInList: Boolean = false,
    var includeOlderMiruKey: Boolean = true,
    var issuedApiKey: String = RAW_NEW_API_KEY,
    val nowMs: AtomicLong = AtomicLong(1_700_000_000_000L),
) {
    val logoutCalls = AtomicInteger(0)
    val deletedKeys = mutableListOf<String>()
}

private class QuickConnectTestHarness(
    val tempDir: Path,
    val dbPath: String,
    val dbHandle: DatabaseHandle,
    val serverRepo: ExposedMediaServerRepository,
    val authService: AuthService,
    val runtimeManager: JellyfinRuntimeManager,
    val quickConnectService: JellyfinQuickConnectService,
    val appConfig: AppConfig,
    val mockEngine: MockEngine,
    val nowMs: AtomicLong,
) : AutoCloseable {
    override fun close() {
        runCatching { runBlocking { runtimeManager.stop() } }
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createMockJellyfinEngine(state: MockJellyfinQuickConnectState): MockEngine {
    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    return MockEngine { req ->
        if (!state.reachable) {
            error("Connection refused")
        }
        if (state.nonJellyfin) {
            return@MockEngine respond("<html>Not Jellyfin</html>", HttpStatusCode.OK)
        }

        when {
            req.url.encodedPath == "/System/Info/Public" ->
                respond(
                    """{"Id":"${state.machineId}","ServerName":"${state.serverName}","Version":"${state.version}"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )

            req.url.encodedPath == "/System/Info" -> {
                val auth = req.headers[HttpHeaders.Authorization].orEmpty()
                if (auth.contains("""Token="${state.issuedApiKey}"""") || auth.contains("""Token="env-jf-key"""")) {
                    respond(
                        """{"Id":"${state.machineId}","ServerName":"${state.serverName}","Version":"${state.version}"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                } else {
                    respond("Unauthorized", HttpStatusCode.Unauthorized)
                }
            }

            req.url.encodedPath == "/Sessions" ->
                respond("[]", HttpStatusCode.OK, jsonHeaders)

            req.url.encodedPath == "/QuickConnect/Enabled" ->
                respond(state.quickConnectEnabled.toString(), HttpStatusCode.OK, jsonHeaders)

            req.method == HttpMethod.Post && req.url.encodedPath == "/QuickConnect/Initiate" ->
                respond(
                    """
                    {
                      "Secret": "$RAW_QC_SECRET",
                      "Code": "739104",
                      "Authenticated": false,
                      "DateAdded": "${Instant.ofEpochMilli(state.nowMs.get())}"
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )

            req.method == HttpMethod.Get && req.url.encodedPath == "/QuickConnect/Connect" -> {
                req.url.parameters["secret"] shouldBe RAW_QC_SECRET
                when {
                    state.expired -> respond("Not found", HttpStatusCode.NotFound)
                    state.authenticated ->
                        respond(
                            """{"Authenticated":true,"Secret":"$RAW_QC_SECRET","Code":"739104"}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    else ->
                        respond(
                            """{"Authenticated":false,"Secret":"$RAW_QC_SECRET","Code":"739104"}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                }
            }

            req.method == HttpMethod.Post && req.url.encodedPath == "/Users/AuthenticateWithQuickConnect" ->
                respond(
                    """
                    {
                      "AccessToken": "$RAW_TEMP_ACCESS_TOKEN",
                      "ServerId": "${state.machineId}",
                      "User": {
                        "Id": "jf-user-1",
                        "Name": "${if (state.isAdmin) "alice" else "bob"}",
                        "Policy": { "IsAdministrator": ${state.isAdmin} }
                      }
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )

            req.url.encodedPath == "/Auth/Keys" -> handleMockAuthKeysRequest(req, state, jsonHeaders)

            req.method == HttpMethod.Delete && req.url.encodedPath.startsWith("/Auth/Keys/") -> {
                req.headers[HttpHeaders.Authorization].orEmpty() shouldContain """Token="${state.issuedApiKey}""""
                state.deletedKeys += req.url.encodedPath.removePrefix("/Auth/Keys/")
                respond("", HttpStatusCode.NoContent)
            }

            req.method == HttpMethod.Post && req.url.encodedPath == "/Sessions/Logout" -> {
                req.headers[HttpHeaders.Authorization].orEmpty() shouldContain """Token="$RAW_TEMP_ACCESS_TOKEN""""
                state.logoutCalls.incrementAndGet()
                respond("", HttpStatusCode.NoContent)
            }

            else -> respond("Not found", HttpStatusCode.NotFound)
        }
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.handleMockAuthKeysRequest(
    req: io.ktor.client.request.HttpRequestData,
    state: MockJellyfinQuickConnectState,
    jsonHeaders: io.ktor.http.Headers,
): io.ktor.client.request.HttpResponseData =
    when (req.method) {
        HttpMethod.Post -> {
            req.headers[HttpHeaders.Authorization].orEmpty() shouldContain """Token="$RAW_TEMP_ACCESS_TOKEN""""
            req.url.parameters["app"] shouldBe "Miru"
            if (state.failCreateKey) {
                respond("Server error", HttpStatusCode.InternalServerError)
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }

        HttpMethod.Get -> {
            req.headers[HttpHeaders.Authorization].orEmpty() shouldContain """Token="$RAW_TEMP_ACCESS_TOKEN""""
            val items = mutableListOf<String>()
            if (state.includeOlderMiruKey) {
                val olderTs = Instant.ofEpochMilli(state.nowMs.get() - 60_000L).toString()
                items += """{"Id":1,"AccessToken":"$RAW_OLD_API_KEY","AppName":"Miru","IsActive":true,"DateCreated":"$olderTs"}"""
            }
            val otherAppTs = Instant.ofEpochMilli(state.nowMs.get() + 1_000L).toString()
            items += """{"Id":2,"AccessToken":"other-app-key","AppName":"OtherClient","IsActive":true,"DateCreated":"$otherAppTs"}"""
            if (!state.omitCreatedKeyInList) {
                val newTs = Instant.ofEpochMilli(state.nowMs.get() + 500L).toString()
                items += """{"Id":3,"AccessToken":"${state.issuedApiKey}","AppName":"Miru","IsActive":true,"DateCreated":"$newTs"}"""
            }
            respond("""{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size}}""", HttpStatusCode.OK, jsonHeaders)
        }

        else -> respond("Method not allowed", HttpStatusCode.MethodNotAllowed)
    }

private fun createQuickConnectHarness(
    jellyfinConfig: JellyfinConfig = JellyfinConfig(websocketEnabled = false),
    encryptionKey: String? = JF_TEST_ENCRYPTION_KEY,
    state: MockJellyfinQuickConnectState = MockJellyfinQuickConnectState(),
): QuickConnectTestHarness {
    val dir = Files.createTempDirectory("miru-jf-qc-test-")
    val dbPath = dir.resolve("miru-qc.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = JF_TEST_SESSION_SECRET)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
            clock = { state.nowMs.get() },
        )
    val engine = createMockJellyfinEngine(state)
    val runtimeManager =
        JellyfinRuntimeManager(
            jellyfinConfig = jellyfinConfig,
            databaseHandle = handle,
            sessionConfig = SessionConfig(),
            mediaServerRepository = serverRepo,
            httpClientEngineFactory = { engine },
            encryptionKey = encryptionKey,
            clock = { state.nowMs.get() },
        )
    val qcClient =
        JellyfinQuickConnectClient(
            httpClient = createJellyfinHttpClient(engine = engine),
            appVersion = "1.0.0-test",
        )
    val qcService =
        JellyfinQuickConnectService(
            jellyfinConfig = jellyfinConfig,
            encryptionKey = encryptionKey,
            quickConnectClient = qcClient,
            mediaServerRepository = serverRepo,
            runtimeManager = runtimeManager,
            clock = { state.nowMs.get() },
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(websocketEnabled = false, encryptionKey = encryptionKey),
            jellyfin = jellyfinConfig,
            session = SessionConfig(),
            auth = authConfig,
        )
    return QuickConnectTestHarness(
        tempDir = dir,
        dbPath = dbPath,
        dbHandle = handle,
        serverRepo = serverRepo,
        authService = authService,
        runtimeManager = runtimeManager,
        quickConnectService = qcService,
        appConfig = appConfig,
        mockEngine = engine,
        nowMs = state.nowMs,
    )
}

private suspend fun seedAdminAndUserKeys(harness: QuickConnectTestHarness): Pair<String, String> {
    val admin = harness.authService.setupInitialAdmin("admin", "Admin-password-1234").shouldBeRight()
    val adminKey =
        harness.authService
            .createApiKey(accountId = admin.principal.accountId, name = "admin-key", role = MiruRole.ADMIN)
            .shouldBeRight()
            .rawKey
    val viewer =
        harness.authService
            .createAccount(username = "viewer", password = "Viewer-password-1234", role = MiruRole.USER)
            .shouldBeRight()
    val userKey =
        harness.authService
            .createApiKey(accountId = viewer.account.id, name = "user-key", role = MiruRole.USER)
            .shouldBeRight()
            .rawKey
    return adminKey to userKey
}

private fun ApplicationTestBuilder.setupQcApp(harness: QuickConnectTestHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            jellyfinQuickConnectService = harness.quickConnectService,
        )
    }
}

class JellyfinQuickConnectRoutesTest :
    FunSpec({
        test("enforces ADMIN RBAC on all /api/v1/admin/jellyfin/* endpoints") {
            createQuickConnectHarness().use { harness ->
                val (_, userKey) = seedAdminAndUserKeys(harness)
                testApplication {
                    setupQcApp(harness)

                    client.get("/api/v1/admin/jellyfin/status").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .post("/api/v1/admin/jellyfin/quick-connect") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                        }.status shouldBe HttpStatusCode.Unauthorized

                    client
                        .get("/api/v1/admin/jellyfin/status") { header("X-Api-Key", userKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/api/v1/admin/jellyfin/quick-connect") {
                            header("X-Api-Key", userKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/admin/jellyfin/quick-connect/some-attempt") { header("X-Api-Key", userKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test(
            "completes Quick Connect, skips older Miru keys, logs out temp session, persists encrypted key, " +
                "hot-reloads, survives restart, and leaks no secrets",
        ) {
            val state = MockJellyfinQuickConnectState(includeOlderMiruKey = true)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val rootLogger = LoggerFactory.getLogger("app.hononeko.miru") as Logger
            val prevLevel = rootLogger.level
            rootLogger.level = Level.DEBUG
            rootLogger.addAppender(appender)

            try {
                createQuickConnectHarness(state = state).use { harness ->
                    val (adminKey, _) = seedAdminAndUserKeys(harness)
                    testApplication {
                        setupQcApp(harness)

                        // Initial status: NOT_CONFIGURED
                        val initialStatus =
                            MiruJson.decodeFromString<JellyfinConnectionStatus>(
                                client.get("/api/v1/admin/jellyfin/status") { header("X-Api-Key", adminKey) }.bodyAsText(),
                            )
                        initialStatus.configured shouldBe false
                        initialStatus.credentialSource shouldBe PlexCredentialSource.NOT_CONFIGURED
                        initialStatus.encryptionKeyConfigured shouldBe true
                        initialStatus.reachable shouldBe false

                        // Start Quick Connect
                        val startResp =
                            client.post("/api/v1/admin/jellyfin/quick-connect") {
                                header("X-Api-Key", adminKey)
                                contentType(ContentType.Application.Json)
                                setBody(MiruJson.encodeToString(JellyfinQuickConnectStartRequest("$MOCK_JF_URL/")))
                            }
                        startResp.status shouldBe HttpStatusCode.OK
                        val startBody = startResp.bodyAsText()
                        startBody shouldNotContain RAW_QC_SECRET
                        startBody shouldNotContain RAW_TEMP_ACCESS_TOKEN
                        startBody shouldNotContain RAW_NEW_API_KEY
                        val started = MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(startBody)
                        started.code shouldBe "739104"
                        started.serverName shouldBe "Studio Jellyfin"
                        started.serverVersion shouldBe "10.10.3"
                        started.toString() shouldNotContain "739104"

                        // Pending poll
                        val pendingResp =
                            client.get("/api/v1/admin/jellyfin/quick-connect/${started.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }
                        pendingResp.status shouldBe HttpStatusCode.OK
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(pendingResp.bodyAsText()).status shouldBe
                            JellyfinQuickConnectStatus.PENDING

                        // User approves Quick Connect on Jellyfin
                        state.authenticated = true
                        val authResp =
                            client.get("/api/v1/admin/jellyfin/quick-connect/${started.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }
                        authResp.status shouldBe HttpStatusCode.OK
                        val authBody = authResp.bodyAsText()
                        authBody shouldNotContain RAW_QC_SECRET
                        authBody shouldNotContain RAW_TEMP_ACCESS_TOKEN
                        authBody shouldNotContain RAW_NEW_API_KEY
                        authBody shouldNotContain RAW_OLD_API_KEY

                        val completed = MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(authBody)
                        completed.status shouldBe JellyfinQuickConnectStatus.AUTHORIZED
                        completed.serverName shouldBe "Studio Jellyfin"
                        completed.serverVersion shouldBe "10.10.3"
                        completed.machineId shouldBe "jf-mach-1"
                        completed.baseUrl shouldBe MOCK_JF_URL
                        completed.hotReloaded shouldBe true
                        completed.envOverrideActive shouldBe false
                        completed.credentialSource shouldBe PlexCredentialSource.STORED_ENCRYPTED

                        // Temporary session logged out
                        state.logoutCalls.get() shouldBe 1

                        // DB holds newly created Miru key encrypted (NOT the older Miru key!)
                        val saved = harness.serverRepo.findByMachineId("jf-mach-1").shouldBeRight().shouldNotBeNull()
                        val encToken = saved.encryptedToken.shouldNotBeNull()
                        encToken shouldStartWith "v1:"
                        encToken shouldNotContain RAW_NEW_API_KEY
                        CredentialCrypto.decrypt(encToken, JF_TEST_ENCRYPTION_KEY).shouldBeRight() shouldBe RAW_NEW_API_KEY
                        saved.clientIdentifier.shouldNotBeNull()
                        saved.toString() shouldNotContain encToken

                        // Runtime hot-reloaded without restart
                        harness.runtimeManager.isConfigured() shouldBe true
                        harness.runtimeManager.currentBaseUrl() shouldBe MOCK_JF_URL
                        harness.runtimeManager.currentCredentialSource() shouldBe PlexCredentialSource.STORED_ENCRYPTED

                        // /status reflects connected server + version
                        val finalStatus =
                            MiruJson.decodeFromString<JellyfinConnectionStatus>(
                                client.get("/api/v1/admin/jellyfin/status") { header("X-Api-Key", adminKey) }.bodyAsText(),
                            )
                        finalStatus.configured shouldBe true
                        finalStatus.reachable shouldBe true
                        finalStatus.serverName shouldBe "Studio Jellyfin"
                        finalStatus.version shouldBe "10.10.3"
                        finalStatus.credentialSource shouldBe PlexCredentialSource.STORED_ENCRYPTED

                        // Repeated poll returns cached AUTHORIZED without calling Jellyfin again
                        val cachedResp =
                            MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                                client.get("/api/v1/admin/jellyfin/quick-connect/${started.attemptId}") {
                                    header("X-Api-Key", adminKey)
                                }.bodyAsText(),
                            )
                        cachedResp.status shouldBe JellyfinQuickConnectStatus.AUTHORIZED
                        state.logoutCalls.get() shouldBe 1
                    }

                    // Simulated restart: fresh JellyfinRuntimeManager with zero env vars loads stored encrypted key
                    val restartedManager =
                        JellyfinRuntimeManager(
                            jellyfinConfig = JellyfinConfig(websocketEnabled = false),
                            databaseHandle = harness.dbHandle,
                            mediaServerRepository = harness.serverRepo,
                            httpClientEngineFactory = { harness.mockEngine },
                            encryptionKey = JF_TEST_ENCRYPTION_KEY,
                        )
                    restartedManager.initialize().shouldBeRight() shouldBe true
                    restartedManager.isConfigured() shouldBe true
                    restartedManager.currentBaseUrl() shouldBe MOCK_JF_URL
                    restartedManager.currentMachineId() shouldBe "jf-mach-1"
                    restartedManager.currentCredentialSource() shouldBe PlexCredentialSource.STORED_ENCRYPTED
                    restartedManager.adapter.checkHealth().shouldBeRight().friendlyName shouldBe "Studio Jellyfin"
                    restartedManager.stop()
                }

                val logOutput = appender.list.joinToString("\n") { it.formattedMessage }
                logOutput shouldNotContain RAW_QC_SECRET
                logOutput shouldNotContain RAW_TEMP_ACCESS_TOKEN
                logOutput shouldNotContain RAW_NEW_API_KEY
                logOutput shouldNotContain RAW_OLD_API_KEY
            } finally {
                rootLogger.detachAppender(appender)
                rootLogger.level = prevLevel
            }
        }

        test("reconnect deletes previous stored Jellyfin API key and switching servers clears old encrypted token") {
            val state = MockJellyfinQuickConnectState(includeOlderMiruKey = false, issuedApiKey = "rotated-jf-key-2")
            createQuickConnectHarness(state = state).use { harness ->
                val (adminKey, _) = seedAdminAndUserKeys(harness)
                // Pre-seed same server (jf-mach-1) with an existing encrypted key
                val oldEnc = CredentialCrypto.encrypt(RAW_OLD_API_KEY, JF_TEST_ENCRYPTION_KEY).shouldBeRight()
                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "jf-existing-id",
                            name = "Studio Jellyfin",
                            serverType = "jellyfin",
                            machineId = "jf-mach-1",
                            baseUrl = MOCK_JF_URL,
                            encryptedToken = oldEnc,
                            clientIdentifier = "existing-dev-id",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupQcApp(harness)

                    // 1. Reconnect same server -> rotates key & deletes RAW_OLD_API_KEY on Jellyfin
                    state.authenticated = true
                    val start1 =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    val poll1 =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${start1.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    poll1.status shouldBe JellyfinQuickConnectStatus.AUTHORIZED
                    state.deletedKeys shouldContain RAW_OLD_API_KEY

                    // 2. Switch to a second Jellyfin server (jf-mach-2) -> clears encrypted_token on jf-mach-1
                    state.machineId = "jf-mach-2"
                    state.serverName = "Second Jellyfin"
                    state.issuedApiKey = "second-server-key"
                    val start2 =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    val poll2 =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${start2.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    poll2.status shouldBe JellyfinQuickConnectStatus.AUTHORIZED
                    poll2.machineId shouldBe "jf-mach-2"

                    harness.serverRepo.findByMachineId("jf-mach-1").shouldBeRight().shouldNotBeNull().encryptedToken.shouldBeNull()
                    val server2Enc =
                        harness.serverRepo.findByMachineId("jf-mach-2").shouldBeRight().shouldNotBeNull().encryptedToken.shouldNotBeNull()
                    CredentialCrypto.decrypt(server2Enc, JF_TEST_ENCRYPTION_KEY).shouldBeRight() shouldBe "second-server-key"
                }

                // Restart loads jf-mach-2 (not jf-mach-1)
                val restarted =
                    JellyfinRuntimeManager(
                        jellyfinConfig = JellyfinConfig(websocketEnabled = false),
                        databaseHandle = harness.dbHandle,
                        mediaServerRepository = harness.serverRepo,
                        httpClientEngineFactory = { harness.mockEngine },
                        encryptionKey = JF_TEST_ENCRYPTION_KEY,
                    )
                restarted.initialize().shouldBeRight() shouldBe true
                restarted.currentMachineId() shouldBe "jf-mach-2"
                restarted.stop()
            }
        }

        test("rejects non-admin user, logs out temporary session, and stores nothing") {
            val state = MockJellyfinQuickConnectState(authenticated = true, isAdmin = false)
            createQuickConnectHarness(state = state).use { harness ->
                val (adminKey, _) = seedAdminAndUserKeys(harness)
                testApplication {
                    setupQcApp(harness)

                    val started =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )

                    val pollResp =
                        client.get("/api/v1/admin/jellyfin/quick-connect/${started.attemptId}") {
                            header("X-Api-Key", adminKey)
                        }
                    pollResp.status shouldBe HttpStatusCode.OK
                    val poll = MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(pollResp.bodyAsText())
                    poll.status shouldBe JellyfinQuickConnectStatus.NOT_ADMIN
                    poll.message.shouldNotBeNull() shouldContain "administrator"

                    state.logoutCalls.get() shouldBe 1
                    harness.serverRepo.findAll().shouldBeRight() shouldHaveSize 0
                    harness.runtimeManager.isConfigured() shouldBe false
                }
            }
        }

        test("logs out temporary session on API key creation/lookup failure and handles disabled/expired/invalid/unreachable cases") {
            val state = MockJellyfinQuickConnectState()
            createQuickConnectHarness(state = state).use { harness ->
                val (adminKey, _) = seedAdminAndUserKeys(harness)
                testApplication {
                    setupQcApp(harness)

                    // Invalid URLs -> 400 ValidationError
                    listOf(
                        "",
                        "ftp://jf.local:8096",
                        "http://user:pass@jf.local:8096",
                        "http://jf.local:8096?foo=bar",
                    ).forEach { badUrl ->
                        val badResp =
                            client.post("/api/v1/admin/jellyfin/quick-connect") {
                                header("X-Api-Key", adminKey)
                                contentType(ContentType.Application.Json)
                                setBody(MiruJson.encodeToString(JellyfinQuickConnectStartRequest(badUrl)))
                            }
                        badResp.status shouldBe HttpStatusCode.BadRequest
                    }

                    // Non-Jellyfin URL -> 400 ValidationError
                    state.nonJellyfin = true
                    val nonJfResp =
                        client.post("/api/v1/admin/jellyfin/quick-connect") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                        }
                    nonJfResp.status shouldBe HttpStatusCode.BadRequest
                    state.nonJellyfin = false

                    // Unreachable URL -> 502 UpstreamUnavailable
                    state.reachable = false
                    client
                        .post("/api/v1/admin/jellyfin/quick-connect") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                        }.status shouldBe HttpStatusCode.BadGateway
                    state.reachable = true

                    // Quick Connect disabled -> 409 Conflict with Dashboard guidance
                    state.quickConnectEnabled = false
                    val disabledResp =
                        client.post("/api/v1/admin/jellyfin/quick-connect") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                        }
                    disabledResp.status shouldBe HttpStatusCode.Conflict
                    MiruJson.decodeFromString<ProblemDetails>(disabledResp.bodyAsText()).detail shouldContain
                        "Jellyfin Dashboard -> General -> Enable Quick Connect"
                    state.quickConnectEnabled = true

                    // Unknown attemptId -> 404 NotFound, blank attemptId -> 400
                    client.get("/api/v1/admin/jellyfin/quick-connect/missing-id") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.NotFound
                    client.get("/api/v1/admin/jellyfin/quick-connect/%20%20") { header("X-Api-Key", adminKey) }.status shouldBe
                        HttpStatusCode.BadRequest

                    // 404 from /QuickConnect/Connect -> EXPIRED
                    val expStart =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    state.expired = true
                    val expPoll =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${expStart.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    expPoll.status shouldBe JellyfinQuickConnectStatus.EXPIRED
                    expPoll.message.shouldNotBeNull() shouldContain "expired"
                    state.expired = false

                    // TTL elapsed -> EXPIRED
                    val ttlStart =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    harness.nowMs.addAndGet(11 * 60 * 1000L)
                    val ttlPoll =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${ttlStart.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    ttlPoll.status shouldBe JellyfinQuickConnectStatus.EXPIRED

                    // Key creation failure still logs out temp session
                    state.authenticated = true
                    state.failCreateKey = true
                    val createFailStart =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    val createFailPoll =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${createFailStart.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    createFailPoll.status shouldBe JellyfinQuickConnectStatus.FAILED
                    state.logoutCalls.get() shouldBe 1
                    state.failCreateKey = false

                    // Missing new Miru key (only older Miru key exists) -> FAILED + temp logout
                    state.omitCreatedKeyInList = true
                    state.includeOlderMiruKey = true
                    val missingKeyStart =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    val missingKeyPoll =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${missingKeyStart.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    missingKeyPoll.status shouldBe JellyfinQuickConnectStatus.FAILED
                    state.logoutCalls.get() shouldBe 2
                    harness.serverRepo.findAll().shouldBeRight() shouldHaveSize 0
                }
            }
        }

        test("disables Quick Connect setup without MIRU_ENCRYPTION_KEY and preserves ENV_OVERRIDE precedence") {
            createQuickConnectHarness(encryptionKey = null).use { noKeyHarness ->
                val (adminKey, _) = seedAdminAndUserKeys(noKeyHarness)
                testApplication {
                    setupQcApp(noKeyHarness)

                    val status =
                        MiruJson.decodeFromString<JellyfinConnectionStatus>(
                            client.get("/api/v1/admin/jellyfin/status") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    status.encryptionKeyConfigured shouldBe false
                    status.configured shouldBe false

                    val startResp =
                        client.post("/api/v1/admin/jellyfin/quick-connect") {
                            header("X-Api-Key", adminKey)
                            contentType(ContentType.Application.Json)
                            setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                        }
                    startResp.status shouldBe HttpStatusCode.BadRequest
                    MiruJson.decodeFromString<ProblemDetails>(startResp.bodyAsText()).detail shouldContain "MIRU_ENCRYPTION_KEY"
                }
            }

            val envConfig =
                JellyfinConfig(
                    baseUrl = "http://env-jellyfin.local:8096",
                    apiKey = "env-jf-key",
                    websocketEnabled = false,
                )
            val envState = MockJellyfinQuickConnectState(authenticated = true)
            createQuickConnectHarness(jellyfinConfig = envConfig, state = envState).use { envHarness ->
                envHarness.runtimeManager.initialize().shouldBeRight() shouldBe true
                val (adminKey, _) = seedAdminAndUserKeys(envHarness)
                testApplication {
                    setupQcApp(envHarness)

                    val status =
                        MiruJson.decodeFromString<JellyfinConnectionStatus>(
                            client.get("/api/v1/admin/jellyfin/status") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    status.credentialSource shouldBe PlexCredentialSource.ENV_OVERRIDE
                    status.reachable shouldBe true
                    status.baseUrl shouldBe "http://env-jellyfin.local:8096"

                    val started =
                        MiruJson.decodeFromString<JellyfinQuickConnectStartResponse>(
                            client
                                .post("/api/v1/admin/jellyfin/quick-connect") {
                                    header("X-Api-Key", adminKey)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"baseUrl":"$MOCK_JF_URL"}""")
                                }.bodyAsText(),
                        )
                    val poll =
                        MiruJson.decodeFromString<JellyfinQuickConnectPollResponse>(
                            client.get("/api/v1/admin/jellyfin/quick-connect/${started.attemptId}") {
                                header("X-Api-Key", adminKey)
                            }.bodyAsText(),
                        )
                    poll.status shouldBe JellyfinQuickConnectStatus.AUTHORIZED
                    poll.envOverrideActive shouldBe true
                    poll.hotReloaded shouldBe false
                    poll.credentialSource shouldBe PlexCredentialSource.ENV_OVERRIDE
                    envHarness.runtimeManager.currentBaseUrl() shouldBe "http://env-jellyfin.local:8096"
                    envHarness.runtimeManager.reloadFromStoredCredentials().shouldBeRight() shouldBe false
                }
            }
        }
    })
