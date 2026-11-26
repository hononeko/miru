package app.hononeko.miru.adapter

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectClient
import app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager
import app.hononeko.miru.adapter.jellyfin.createJellyfinHttpClient
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
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
import app.hononeko.miru.config.MetricsConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.http.AdminServersResponse
import app.hononeko.miru.http.DisconnectServerResponse
import app.hononeko.miru.http.MediaServersResponse
import app.hononeko.miru.http.PlexCredentialSource
import app.hononeko.miru.http.ProblemDetails
import app.hononeko.miru.http.RemoveServerResponse
import app.hononeko.miru.metrics.MiruMetricsService
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleRecord
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedGuardrailRuleRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.MediaServerManagementService
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.ReconcileTrigger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val MGMT_ENCRYPTION_KEY = "mgmt-test-encryption-key-32-chars!"
private const val MGMT_SESSION_SECRET = "mgmt-test-session-secret-32-chars!!"
private const val RAW_PLEX_SECRET = "plex-disconnect-secret-token-xyz"
private const val RAW_JF_SECRET = "jf-disconnect-secret-api-key-abc"

private class DisconnectHarness(
    val dir: Path?,
    val handle: DatabaseHandle,
    val plexConfig: PlexConfig,
    val jfConfig: JellyfinConfig,
    val sessionConfig: SessionConfig = SessionConfig(minPlayDurationSeconds = 30),
    val nowMs: AtomicLong = AtomicLong(1_700_000_000_000L),
    val plexRequests: MutableList<Pair<HttpMethod, String>> = mutableListOf(),
    val jfRevokedKeys: MutableList<String> = mutableListOf(),
    val jfReachable: AtomicBoolean = AtomicBoolean(true),
    val plexTvCalls: MutableList<String> = mutableListOf(),
) : AutoCloseable {
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val activeSessionRepo = ExposedActiveSessionRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val libraryRepo = ExposedLibraryRepository(handle)
    val guardrailRepo = ExposedGuardrailRuleRepository(handle)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(iterations = 1, memoryKb = 1024, parallelism = 1)),
            authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = MGMT_SESSION_SECRET),
        )

    private val plexEngine =
        MockEngine { req ->
            val path = req.url.encodedPath
            plexRequests += req.method to path
            if (req.url.host.contains("plex.tv")) {
                plexTvCalls += req.url.toString()
                return@MockEngine respond("", status = HttpStatusCode.InternalServerError)
            }
            when (path) {
                "/" ->
                    respond(
                        """{"MediaContainer":{"machineIdentifier":"plex-mid-1","friendlyName":"Home Plex",""" +
                            """"version":"1.41.2","platform":"Linux"}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )

                else ->
                    respond(
                        """{"MediaContainer":{"size":0,"Metadata":[]}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
            }
        }

    private val jfEngine =
        MockEngine { req ->
            val path = req.url.encodedPath
            if (!jfReachable.get()) {
                return@MockEngine respond("Unreachable", status = HttpStatusCode.ServiceUnavailable)
            }
            when {
                path == "/System/Info" ->
                    respond(
                        """{"Id":"jf-mid-1","ServerName":"Studio Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )

                req.method == HttpMethod.Delete && path.startsWith("/Auth/Keys/") -> {
                    jfRevokedKeys += path.removePrefix("/Auth/Keys/")
                    respond("", status = HttpStatusCode.NoContent)
                }

                else ->
                    respond("[]", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }

    private val qcHttpClient = createJellyfinHttpClient(engine = jfEngine)
    val qcClient = JellyfinQuickConnectClient({ qcHttpClient }, "1.0.0")

    val plexRuntime =
        PlexRuntimeManager(
            plexConfig = plexConfig,
            databaseHandle = handle,
            sessionConfig = sessionConfig,
            mediaServerRepository = serverRepo,
            httpClientEngineFactory = { plexEngine },
            plexTvBaseUrl = "https://plex.tv",
            clock = { nowMs.get() },
        )

    val jfRuntime =
        JellyfinRuntimeManager(
            jellyfinConfig = jfConfig,
            databaseHandle = handle,
            sessionConfig = sessionConfig,
            mediaServerRepository = serverRepo,
            httpClientEngineFactory = { jfEngine },
            encryptionKey = MGMT_ENCRYPTION_KEY,
            clock = { nowMs.get() },
        )

    val registry =
        MediaServerRegistry(
            runtimes = listOf(plexRuntime, jfRuntime),
            mediaServerRepository = serverRepo,
            jellyfinConfig = jfConfig,
        )

    val metricsService =
        MiruMetricsService(
            sessionEvents = registry.sessionEvents,
            activeSessionsProvider = { registry.currentSessions() },
            librarySectionsProvider = { libraryRepo.findAllSections() },
            serverNameByIdProvider = { id -> registry.serverSummaryById(id)?.name },
            activeWebsocketServerLabelProvider = { registry.currentDefaultServerName() },
        )

    val managementService =
        MediaServerManagementService(
            mediaServerRegistry = registry,
            mediaServerRepository = serverRepo,
            encryptionKey = MGMT_ENCRYPTION_KEY,
            jellyfinRevokeApiKey = { baseUrl, apiKey, deviceId ->
                qcClient.deleteApiKey(baseUrl, apiKey, apiKey, deviceId)
            },
            jellyfinDefaultDeviceId = "miru-jf-device",
            onServerDisconnected = { metricsService.onServerDisconnected() },
            onServerRemoved = { id -> metricsService.onServerRemoved(id) },
            clock = { nowMs.get() },
        )

    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(":memory:"),
            plex = plexConfig,
            jellyfin = jfConfig,
            session = sessionConfig,
            auth = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = MGMT_SESSION_SECRET),
            metrics = MetricsConfig(enabled = false),
        )

    fun seedAdminAndUserKeys(): Pair<String, String> =
        runBlocking {
            val admin = authService.setupInitialAdmin("admin", "AdminPassword123!").shouldBeRight()
            val viewer = authService.createAccount("viewer", "ViewerPassword123!", role = MiruRole.USER).shouldBeRight()
            val adminKey =
                authService.createApiKey(accountId = admin.principal.accountId, name = "admin-key", role = MiruRole.ADMIN)
                    .shouldBeRight()
                    .rawKey
            val viewerKey =
                authService.createApiKey(accountId = viewer.account.id, name = "viewer-key", role = MiruRole.USER)
                    .shouldBeRight()
                    .rawKey
            adminKey to viewerKey
        }

    fun setupInto(builder: ApplicationTestBuilder) {
        builder.application {
            miruModule(
                config = appConfig,
                authService = authService,
                streamsAndUsersService =
                    StreamsAndUsersService(
                        activeSessionRepository = activeSessionRepo,
                        userRepository = userRepo,
                        playbackHistoryRepository = historyRepo,
                        mediaServerRepository = serverRepo,
                    ),
                historyAndStatsService = HistoryAndStatsService(historyRepo, userRepo, mediaServerRepository = serverRepo),
                libraryQueryService = LibraryQueryService(libraryRepo, mediaServerRepository = serverRepo),
                mediaServerRegistry = registry,
                mediaServerManagementService = managementService,
            )
        }
    }

    override fun close() {
        runCatching { runBlocking { registry.stop() } }
        qcHttpClient.close()
        metricsService.close()
        handle.close()
        dir?.toFile()?.deleteRecursively()
    }
}

private fun sqliteHarness(
    plexConfig: PlexConfig = PlexConfig(encryptionKey = MGMT_ENCRYPTION_KEY, websocketEnabled = false),
    jfConfig: JellyfinConfig = JellyfinConfig(websocketEnabled = false),
): DisconnectHarness {
    val dir = Files.createTempDirectory("miru-disconnect-test-")
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
    return DisconnectHarness(dir = dir, handle = handle, plexConfig = plexConfig, jfConfig = jfConfig)
}

private suspend fun seedStoredJellyfinServer(
    harness: DisconnectHarness,
    id: String = "srv-jf-ui",
    name: String = "Studio Jellyfin",
    machineId: String = "jf-mid-1",
    baseUrl: String = "http://jf.local:8096",
    apiKey: String = RAW_JF_SECRET,
) {
    val encrypted = CredentialCrypto.encrypt(apiKey, MGMT_ENCRYPTION_KEY).shouldBeRight()
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = id,
                name = name,
                serverType = "jellyfin",
                machineId = machineId,
                baseUrl = baseUrl,
                encryptedToken = encrypted,
                clientIdentifier = "miru-jf-device",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
}

private suspend fun seedStoredPlexServer(
    harness: DisconnectHarness,
    id: String = "srv-plex-ui",
    name: String = "Home Plex",
    machineId: String = "plex-mid-1",
    baseUrl: String = "http://plex.local:32400",
    token: String = RAW_PLEX_SECRET,
) {
    val encrypted = CredentialCrypto.encrypt(token, MGMT_ENCRYPTION_KEY).shouldBeRight()
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = id,
                name = name,
                serverType = "plex",
                machineId = machineId,
                baseUrl = baseUrl,
                encryptedToken = encrypted,
                clientIdentifier = "miru-plex-device",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
}

private fun snapshot(
    sessionKey: String,
    ratingKey: String,
    title: String,
    userId: String = "u-1",
    username: String = "alice",
): ActiveStreamSnapshot =
    ActiveStreamSnapshot(
        sessionKey = sessionKey,
        sessionId = "sid-$sessionKey",
        state = PlaybackState.PLAYING,
        viewOffsetMs = 120_000L,
        media = StreamMedia(ratingKey = ratingKey, mediaType = MediaType.MOVIE, title = title, durationMs = 6_000_000L),
        user = StreamUser(externalId = userId, username = username),
        player =
            StreamPlayer(
                title = "Web",
                product = "Jellyfin Web",
                machineIdentifier = "dev-1",
                isLocal = true,
                location = StreamLocation.LAN,
            ),
        technical = StreamTechnicalInfo(decision = StreamDecision.DIRECT_PLAY),
    )

class MediaServerDisconnectAndRemoveTest :
    FunSpec({
        test("disconnects UI Jellyfin, closes live sessions to history, revokes API key, keeps data, and stays dormant on restart") {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val rootLogger = LoggerFactory.getLogger("app.hononeko.miru") as Logger
            val prevLevel = rootLogger.level
            rootLogger.level = Level.INFO
            rootLogger.addAppender(appender)

            try {
                sqliteHarness().use { harness ->
                    val (adminKey, viewerKey) = harness.seedAdminAndUserKeys()
                    seedStoredJellyfinServer(harness)
                    harness.jfRuntime.initialize().shouldBeRight() shouldBe true
                    val jfServerId = harness.jfRuntime.serverIdProvider().shouldBeRight()
                    harness.libraryRepo
                        .upsertSection(
                            LibrarySectionRecord(
                                serverId = jfServerId,
                                sectionKey = "lib-1",
                                title = "JF Movies",
                                sectionType = "movie",
                                itemCount = 12,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()
                    harness.metricsService.refreshLibraryStats()
                    harness.metricsService.scrape() shouldContain """server="Studio Jellyfin""""

                    // Reconcile two active streams: long play (120s >= 30s) and short play (10s < 30s)
                    harness.nowMs.set(1_700_000_000_000L)
                    harness.jfRuntime.sessionEngine
                        .reconcile(listOf(snapshot("jf-long", "m-long", "Dune")), ReconcileTrigger.NOTIFICATION)
                        .shouldBeRight()
                    harness.nowMs.set(1_700_000_110_000L)
                    harness.jfRuntime.sessionEngine
                        .reconcile(
                            listOf(
                                snapshot("jf-long", "m-long", "Dune"),
                                snapshot("jf-short", "m-short", "Trailer"),
                            ),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    harness.nowMs.set(1_700_000_120_000L)

                    testApplication {
                        harness.setupInto(this)

                        // Non-admin USER cannot disconnect or remove (403 Forbidden)
                        val forbiddenResp =
                            client.post("/api/v1/admin/servers/$jfServerId/disconnect") {
                                header("X-Api-Key", viewerKey)
                            }
                        forbiddenResp.status shouldBe HttpStatusCode.Forbidden

                        // Unknown server ID returns RFC 7807 404 Not Found
                        val missingResp =
                            client.post("/api/v1/admin/servers/unknown-id/disconnect") {
                                header("X-Api-Key", adminKey)
                            }
                        missingResp.status shouldBe HttpStatusCode.NotFound
                        MiruJson.decodeFromString<ProblemDetails>(missingResp.bodyAsText()).status shouldBe 404

                        // Cannot delete while still connected (409 Conflict)
                        val removeConnected =
                            client.delete("/api/v1/admin/servers/$jfServerId") {
                                header("X-Api-Key", adminKey)
                            }
                        removeConnected.status shouldBe HttpStatusCode.Conflict

                        // Disconnect UI Jellyfin
                        val discResp =
                            client.post("/api/v1/admin/servers/$jfServerId/disconnect") {
                                header("X-Api-Key", adminKey)
                            }
                        discResp.status shouldBe HttpStatusCode.OK
                        val discBody = discResp.bodyAsText()
                        discBody shouldNotContain RAW_JF_SECRET
                        val discDto = MiruJson.decodeFromString<DisconnectServerResponse>(discBody)
                        discDto.disconnected shouldBe true
                        discDto.closedSessions shouldBe 2
                        discDto.credentialRevoked shouldBe true
                        discDto.revocationNotice.shouldNotBeNull() shouldContain "revoked"

                        // Runtime stopped & no server left -> public servers has 0 configured servers (zero-config awaiting setup)
                        harness.jfRuntime.isConfigured() shouldBe false
                        harness.registry.currentSessions().shouldBeEmpty()
                        harness.activeSessionRepo.findByServer(jfServerId).shouldBeRight().shouldBeEmpty()

                        // Long stream reached history; short stream did not; DB token is cleared
                        val histPage = harness.historyRepo.query(HistoryFilter(serverId = jfServerId)).shouldBeRight()
                        histPage.totalCount shouldBe 1L
                        histPage.items.single().media.title shouldBe "Dune"

                        val dbRow = harness.serverRepo.findById(jfServerId).shouldBeRight().shouldNotBeNull()
                        dbRow.encryptedToken.shouldBeNull()
                        harness.jfRevokedKeys shouldContainExactly listOf(RAW_JF_SECRET)

                        val pub =
                            MiruJson.decodeFromString<MediaServersResponse>(
                                client.get("/api/v1/servers") { header("X-Api-Key", adminKey) }.bodyAsText(),
                            )
                        pub.servers.count { it.configured } shouldBe 0
                        pub.servers.single().configured shouldBe false

                        val adm =
                            MiruJson.decodeFromString<AdminServersResponse>(
                                client.get("/api/v1/admin/servers") { header("X-Api-Key", adminKey) }.bodyAsText(),
                            )
                        val jfAdm = adm.servers.first { it.id == jfServerId }
                        jfAdm.configured shouldBe false
                        jfAdm.credentialSource shouldBe PlexCredentialSource.NOT_CONFIGURED
                        jfAdm.canDisconnect shouldBe false
                        jfAdm.canRemove shouldBe true
                        jfAdm.dataSummary?.plays shouldBe 1L
                        jfAdm.dataSummary?.libraries shouldBe 1L

                        // Second disconnect returns 409 Conflict
                        val againResp =
                            client.post("/api/v1/admin/servers/$jfServerId/disconnect") {
                                header("X-Api-Key", adminKey)
                            }
                        againResp.status shouldBe HttpStatusCode.Conflict
                    }

                    // Simulated restart: a fresh runtime manager against the same DB stays dormant
                    val restartedRuntime =
                        JellyfinRuntimeManager(
                            jellyfinConfig = JellyfinConfig(websocketEnabled = false),
                            databaseHandle = harness.handle,
                            mediaServerRepository = harness.serverRepo,
                            encryptionKey = MGMT_ENCRYPTION_KEY,
                        )
                    restartedRuntime.initialize().shouldBeRight() shouldBe false
                    restartedRuntime.isConfigured() shouldBe false
                    restartedRuntime.stop()

                    // Reconnecting same server (same machineId) reuses same DB row & keeps prior history
                    val reEncrypted = CredentialCrypto.encrypt("jf-reconnected-key", MGMT_ENCRYPTION_KEY).shouldBeRight()
                    val reSaved =
                        harness.serverRepo
                            .upsertExclusiveStoredCredentials(
                                MediaServerRecord(
                                    id = "ignored-new-id",
                                    name = "Studio Jellyfin",
                                    serverType = "jellyfin",
                                    machineId = "jf-mid-1",
                                    baseUrl = "http://jf.local:8096",
                                    encryptedToken = reEncrypted,
                                    clientIdentifier = "miru-jf-device",
                                    createdAtMs = 2_000L,
                                    updatedAtMs = 2_000L,
                                ),
                            ).shouldBeRight()
                    reSaved.id shouldBe jfServerId
                    harness.historyRepo.query(HistoryFilter(serverId = jfServerId)).shouldBeRight().totalCount shouldBe 1L
                    harness.jfRuntime.reloadFromStoredCredentials().shouldBeRight() shouldBe true
                    harness.jfRuntime.isConfigured() shouldBe true
                }

                val joinedLogs = appender.list.joinToString("\n") { it.formattedMessage }
                joinedLogs shouldNotContain RAW_JF_SECRET
                joinedLogs shouldContain "action=disconnect"
                joinedLogs shouldContain "admin=admin"
                joinedLogs shouldContain "Studio Jellyfin"
            } finally {
                rootLogger.detachAppender(appender)
                rootLogger.level = prevLevel
            }
        }

        test("disconnects Jellyfin even when unreachable, tells admin to remove Miru key manually, and leaks no secrets") {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val rootLogger = LoggerFactory.getLogger("app.hononeko.miru") as Logger
            rootLogger.addAppender(appender)

            try {
                sqliteHarness().use { harness ->
                    val (adminKey, _) = harness.seedAdminAndUserKeys()
                    seedStoredJellyfinServer(harness, id = "srv-jf-down", apiKey = RAW_JF_SECRET)
                    harness.jfRuntime.initialize().shouldBeRight() shouldBe true
                    harness.jfReachable.set(false)

                    testApplication {
                        harness.setupInto(this)
                        val resp =
                            client.post("/api/v1/admin/servers/srv-jf-down/disconnect") {
                                header("X-Api-Key", adminKey)
                            }
                        resp.status shouldBe HttpStatusCode.OK
                        val body = resp.bodyAsText()
                        body shouldNotContain RAW_JF_SECRET
                        val dto = MiruJson.decodeFromString<DisconnectServerResponse>(body)
                        dto.disconnected shouldBe true
                        dto.credentialRevoked shouldBe false
                        dto.revocationNotice.shouldNotBeNull() shouldContain "Jellyfin Dashboard -> API Keys"
                    }

                    harness.serverRepo.findById("srv-jf-down").shouldBeRight().shouldNotBeNull().encryptedToken.shouldBeNull()
                }
                appender.list.joinToString("\n") { it.formattedMessage } shouldNotContain RAW_JF_SECRET
            } finally {
                rootLogger.detachAppender(appender)
            }
        }

        test("disconnects UI Plex locally without calling undocumented plex.tv endpoints, leaks no secret, and clears metrics on remove") {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val rootLogger = LoggerFactory.getLogger("app.hononeko.miru") as Logger
            rootLogger.addAppender(appender)

            try {
                sqliteHarness().use { harness ->
                    val (adminKey, _) = harness.seedAdminAndUserKeys()
                    seedStoredPlexServer(harness, id = "srv-plex-ui", token = RAW_PLEX_SECRET)
                    seedStoredJellyfinServer(harness, id = "srv-jf-sibling", machineId = "jf-sib-mid", name = "Sibling JF")
                    harness.plexRuntime.initialize().shouldBeRight() shouldBe true
                    harness.jfRuntime.initialize().shouldBeRight() shouldBe true
                    val plexServerId = harness.plexRuntime.serverIdProvider().shouldBeRight()
                    harness.userRepo.upsert(
                        UserRecord("u-p1", plexServerId, "p-1", "alice", createdAtMs = 1L, updatedAtMs = 1L),
                    ).shouldBeRight()
                    harness.libraryRepo.upsertSection(
                        LibrarySectionRecord(plexServerId, "1", "Plex Movies", "movie", itemCount = 5, updatedAtMs = 1L),
                    ).shouldBeRight()
                    harness.historyRepo.insert(
                        PlaybackHistoryRecord(
                            id = "h-p1",
                            serverId = plexServerId,
                            userId = "u-p1",
                            media = MediaMetadataRecord("m-1", "movie", "Spirited Away", durationMs = 7_500_000L),
                            viewOffsetMs = 7_000_000L,
                            watchedDurationSec = 7_000,
                            completionPercent = 93.3,
                            isWatched = true,
                            streamTech = StreamTechRecord("direct_play"),
                            player = PlayerRecord(playerName = "Apple TV"),
                            startedAtMs = 1_000L,
                            stoppedAtMs = 8_000L,
                        ),
                    ).shouldBeRight()
                    harness.guardrailRepo.upsert(
                        GuardrailRuleRecord(
                            ruleType = GuardrailRuleType.TRANSCODE_4K,
                            mode = GuardrailMode.WARN,
                            thresholdInt = null,
                            message = "No 4K",
                            updatedAtMs = 1_000L,
                            updatedBy = "admin",
                        ),
                    ).shouldBeRight()
                    harness.metricsService.refreshLibraryStats()
                    harness.metricsService.scrape() shouldContain """server="Home Plex""""

                    testApplication {
                        harness.setupInto(this)
                        val discResp =
                            client.post("/api/v1/admin/servers/$plexServerId/disconnect") {
                                header("X-Api-Key", adminKey)
                            }
                        discResp.status shouldBe HttpStatusCode.OK
                        val discBody = discResp.bodyAsText()
                        discBody shouldNotContain RAW_PLEX_SECRET
                        val discDto = MiruJson.decodeFromString<DisconnectServerResponse>(discBody)
                        discDto.credentialRevoked shouldBe false
                        discDto.revocationNotice.shouldNotBeNull() shouldContain "Authorized Devices"
                        harness.plexTvCalls.shouldBeEmpty()
                        harness.plexRuntime.isConfigured() shouldBe false
                        harness.serverRepo.findById(plexServerId).shouldBeRight().shouldNotBeNull().encryptedToken.shouldBeNull()

                        // Sibling Jellyfin server stays connected; metrics still has Plex library until removed
                        harness.jfRuntime.isConfigured() shouldBe true

                        // Remove disconnected Plex server and verify counts + metrics cleanup + sibling/global survival
                        val removeResp =
                            client.delete("/api/v1/admin/servers/$plexServerId") {
                                header("X-Api-Key", adminKey)
                            }
                        removeResp.status shouldBe HttpStatusCode.OK
                        val remDto = MiruJson.decodeFromString<RemoveServerResponse>(removeResp.bodyAsText())
                        remDto.removed shouldBe true
                        remDto.deletedPlays shouldBe 1L
                        remDto.deletedUsers shouldBe 1L
                        remDto.deletedLibraries shouldBe 1L

                        harness.serverRepo.findById(plexServerId).shouldBeRight().shouldBeNull()
                        harness.serverRepo.findById("srv-jf-sibling").shouldBeRight().shouldNotBeNull()
                        harness.guardrailRepo.findByRuleType(GuardrailRuleType.TRANSCODE_4K).shouldBeRight().shouldNotBeNull()
                        harness.metricsService.scrape() shouldNotContain """server="Home Plex""""
                    }

                    // Fresh Plex runtime after disconnect stays dormant
                    val restartedPlex =
                        PlexRuntimeManager(
                            plexConfig = PlexConfig(encryptionKey = MGMT_ENCRYPTION_KEY, websocketEnabled = false),
                            databaseHandle = harness.handle,
                            mediaServerRepository = harness.serverRepo,
                        )
                    restartedPlex.initialize().shouldBeRight() shouldBe false
                    restartedPlex.stop()
                }
                appender.list.joinToString("\n") { it.formattedMessage } shouldNotContain RAW_PLEX_SECRET
            } finally {
                rootLogger.detachAppender(appender)
            }
        }

        test("rejects disconnect and remove with 409 Conflict for env-managed Plex and Jellyfin servers") {
            val envPlex = PlexConfig(baseUrl = "http://env-plex.local:32400", token = "env-plex-secret", websocketEnabled = false)
            val envJf = JellyfinConfig(baseUrl = "http://env-jf.local:8096", apiKey = "env-jf-secret", websocketEnabled = false)

            sqliteHarness(plexConfig = envPlex, jfConfig = envJf).use { harness ->
                val (adminKey, _) = harness.seedAdminAndUserKeys()
                harness.plexRuntime.initialize().shouldBeRight() shouldBe true
                harness.jfRuntime.initialize().shouldBeRight() shouldBe true
                val plexId = harness.plexRuntime.serverIdProvider().shouldBeRight()
                val jfId = harness.jfRuntime.serverIdProvider().shouldBeRight()

                testApplication {
                    harness.setupInto(this)
                    val adm =
                        MiruJson.decodeFromString<AdminServersResponse>(
                            client.get("/api/v1/admin/servers") { header("X-Api-Key", adminKey) }.bodyAsText(),
                        )
                    adm.servers.forEach {
                        it.credentialSource shouldBe PlexCredentialSource.ENV_OVERRIDE
                        it.canDisconnect shouldBe false
                        it.canRemove shouldBe false
                    }

                    for (id in listOf(plexId, jfId)) {
                        val disc = client.post("/api/v1/admin/servers/$id/disconnect") { header("X-Api-Key", adminKey) }
                        disc.status shouldBe HttpStatusCode.Conflict
                        disc.bodyAsText() shouldNotContain "env-plex-secret"
                        disc.bodyAsText() shouldNotContain "env-jf-secret"
                        MiruJson.decodeFromString<ProblemDetails>(disc.bodyAsText()).status shouldBe 409

                        val del = client.delete("/api/v1/admin/servers/$id") { header("X-Api-Key", adminKey) }
                        del.status shouldBe HttpStatusCode.Conflict
                    }
                }
            }
        }

        test("disconnect and remove work end-to-end on PostgreSQL while isolating sibling server data") {
            val container =
                PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("miru_mgmt_test")
                    .withUsername("miru")
                    .withPassword("miru_test_password")
            container.start()
            val handle =
                DatabaseFactory
                    .createAndMigrate(
                        DatabaseConfig.Postgres(
                            url = container.jdbcUrl,
                            user = container.username,
                            password = container.password,
                            maxPoolSize = 4,
                        ),
                    ).shouldBeRight()
            try {
                DisconnectHarness(
                    dir = null,
                    handle = handle,
                    plexConfig = PlexConfig(encryptionKey = MGMT_ENCRYPTION_KEY, websocketEnabled = false),
                    jfConfig = JellyfinConfig(websocketEnabled = false),
                ).use { harness ->
                    val (adminKey, _) = harness.seedAdminAndUserKeys()
                    seedStoredJellyfinServer(harness, id = "pg-jf")
                    seedStoredPlexServer(harness, id = "pg-plex")
                    harness.jfRuntime.initialize().shouldBeRight() shouldBe true
                    harness.plexRuntime.initialize().shouldBeRight() shouldBe true

                    val jfId = harness.jfRuntime.serverIdProvider().shouldBeRight()
                    val plexId = harness.plexRuntime.serverIdProvider().shouldBeRight()
                    harness.libraryRepo.upsertSection(
                        LibrarySectionRecord(jfId, "jf-l1", "JF Movies", "movie", updatedAtMs = 1L),
                    ).shouldBeRight()
                    harness.libraryRepo.upsertSection(
                        LibrarySectionRecord(plexId, "p-l1", "Plex Movies", "movie", updatedAtMs = 1L),
                    ).shouldBeRight()

                    harness.nowMs.set(1_700_000_000_000L)
                    harness.jfRuntime.sessionEngine
                        .reconcile(listOf(snapshot("pg-s1", "pg-m1", "Arrival")), ReconcileTrigger.NOTIFICATION)
                        .shouldBeRight()
                    harness.nowMs.set(1_700_000_120_000L)

                    testApplication {
                        harness.setupInto(this)
                        val disc = client.post("/api/v1/admin/servers/$jfId/disconnect") { header("X-Api-Key", adminKey) }
                        disc.status shouldBe HttpStatusCode.OK
                        MiruJson.decodeFromString<DisconnectServerResponse>(disc.bodyAsText()).closedSessions shouldBe 1

                        harness.historyRepo.query(HistoryFilter(serverId = jfId)).shouldBeRight().totalCount shouldBe 1L
                        harness.serverRepo.findById(jfId).shouldBeRight().shouldNotBeNull().encryptedToken.shouldBeNull()

                        val rem = client.delete("/api/v1/admin/servers/$jfId") { header("X-Api-Key", adminKey) }
                        rem.status shouldBe HttpStatusCode.OK
                        val remDto = MiruJson.decodeFromString<RemoveServerResponse>(rem.bodyAsText())
                        remDto.deletedPlays shouldBe 1L
                        remDto.deletedUsers shouldBe 1L
                        remDto.deletedLibraries shouldBe 1L

                        harness.serverRepo.findById(jfId).shouldBeRight().shouldBeNull()
                        harness.libraryRepo.findSectionsByServer(plexId).shouldBeRight() shouldHaveSize 1
                    }
                }
            } finally {
                if (container.isRunning) container.stop()
            }
        }
    })
