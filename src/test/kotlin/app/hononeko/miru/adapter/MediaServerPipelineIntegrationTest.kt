package app.hononeko.miru.adapter

import app.hononeko.miru.CoreServiceWiring
import app.hononeko.miru.MetricsRuntime
import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.EmailMediaUserLinker
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.MetricsConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.createCoreServiceWiring
import app.hononeko.miru.createMetricsRuntime
import app.hononeko.miru.http.ActiveStreamsResponse
import app.hononeko.miru.http.AdminServersResponse
import app.hononeko.miru.http.HistoryListResponse
import app.hononeko.miru.http.LibraryListResponse
import app.hononeko.miru.http.MediaDetailsResponse
import app.hononeko.miru.http.MediaServersResponse
import app.hononeko.miru.http.TerminateStreamRequest
import app.hononeko.miru.http.UsersListResponse
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.spec.style.funSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.sql.DriverManager

private const val IT_SESSION_SECRET = "miru-integration-session-secret-32c!"

private data class PipelineItEnv(
    val plexServer: EmbeddedPlexTestServer,
    val jfServer: EmbeddedJellyfinTestServer,
    val handle: DatabaseHandle,
    val appConfig: AppConfig,
    val authService: AuthService,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val userRepo: ExposedUserRepository,
    val historyStats: HistoryAndStatsService,
    val wiring: CoreServiceWiring,
    val metricsRuntime: MetricsRuntime,
    val cleanupDb: () -> Unit,
) {
    suspend fun closeAll() {
        metricsRuntime.service.stop()
        metricsRuntime.scope.cancel()
        metricsRuntime.service.close()
        wiring.syncServices.libraryCollectorScope.cancel()
        wiring.syncServices.userSyncScope.cancel()
        wiring.syncServices.guardrailScope?.cancel()
        wiring.syncServices.webhookScope?.cancel()
        wiring.syncServices.sessionCleanupScope?.cancel()
        wiring.mediaServerRegistry.stop()
        wiring.plexOAuthHttpClient.close()
        wiring.webhookBundle.httpClient.close()
        handle.close()
        plexServer.close()
        jfServer.close()
        cleanupDb()
    }
}

private data class PipelineItSessionContext(
    val plexSid: String,
    val jfSid: String,
    val jfAliceId: String,
    val adminCookie: String,
    val plexViewerCookie: String,
)

private suspend fun pollUntil(
    timeoutMs: Long = 10_000L,
    predicate: suspend () -> Boolean,
) {
    withTimeout(timeoutMs) {
        while (!predicate()) {
            delay(50)
        }
    }
}

private suspend fun HttpClient.getJsonOk(
    path: String,
    cookieValue: String,
): String {
    val resp = get(path) { cookie(SESSION_COOKIE_NAME, cookieValue) }
    resp.status shouldBe HttpStatusCode.OK
    return resp.bodyAsText()
}

private fun createPipelineItEnv(
    dbConfig: DatabaseConfig,
    cleanupDb: () -> Unit,
): PipelineItEnv {
    val plexServer = EmbeddedPlexTestServer()
    val jfServer = EmbeddedJellyfinTestServer()
    val handle = DatabaseFactory.createAndMigrate(dbConfig).shouldBeRight()
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val accountRepo = ExposedAuthAccountRepository(handle)
    val sessionRepo = ExposedAuthSessionRepository(handle)

    val authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = IT_SESSION_SECRET)
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = dbConfig,
            plex =
                PlexConfig(
                    baseUrl = plexServer.baseUrl,
                    token = plexServer.token,
                    plexTvBaseUrl = plexServer.baseUrl,
                    pollIntervalSeconds = 5,
                    websocketEnabled = true,
                ),
            jellyfin =
                JellyfinConfig(
                    baseUrl = jfServer.baseUrl,
                    apiKey = jfServer.apiKey,
                    clientIdentifier = "miru-it-jf",
                    pollIntervalSeconds = 5,
                    websocketEnabled = true,
                ),
            session = SessionConfig(minPlayDurationSeconds = 0, resumeMergeWindowSeconds = 0, watchedThresholdPercent = 85),
            auth = authConfig,
            metrics = MetricsConfig(enabled = true, host = "127.0.0.1", port = 8080),
        )

    val emailLinker = EmailMediaUserLinker(accountRepo, userRepo, authConfig)
    val authService =
        AuthService(
            accountRepository = accountRepo,
            sessionRepository = sessionRepo,
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 8192, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    val historyStats = HistoryAndStatsService(historyRepo, userRepo, mediaServerRepository = serverRepo)
    val wiring =
        createCoreServiceWiring(
            appConfig = appConfig,
            appVersion = "1.0.0-it",
            databaseHandle = handle,
            mediaServerRepository = serverRepo,
            userRepository = userRepo,
            playbackHistoryRepository = historyRepo,
            sessionRepository = sessionRepo,
            emailLinker = emailLinker,
        )
    val mergedSyncEvents =
        (listOf(wiring.syncServices.libraryStatsCollector) + wiring.syncServices.additionalLibraryCollectors)
            .map { it.syncEvents }
            .merge()
    val metricsRuntime =
        createMetricsRuntime(
            appConfig = appConfig,
            plexRuntimeManager = wiring.plexRuntimeManager,
            libraryRepository = wiring.libraryRepository,
            mediaServerRepository = serverRepo,
            libraryStatsCollector = wiring.syncServices.libraryStatsCollector,
            mediaServerRegistry = wiring.mediaServerRegistry,
            librarySyncEvents = mergedSyncEvents,
        )!!
    return PipelineItEnv(
        plexServer = plexServer,
        jfServer = jfServer,
        handle = handle,
        appConfig = appConfig,
        authService = authService,
        historyRepo = historyRepo,
        userRepo = userRepo,
        historyStats = historyStats,
        wiring = wiring,
        metricsRuntime = metricsRuntime,
        cleanupDb = cleanupDb,
    )
}

private suspend fun bootstrapAndSync(env: PipelineItEnv): PipelineItSessionContext {
    env.wiring.mediaServerRegistry.start()
    env.metricsRuntime.service.start()
    env.plexServer.waitForWsConnected(1)
    env.jfServer.waitForWsConnected(1)
    pollUntil { env.jfServer.sessionsStartCount.get() >= 1 && env.jfServer.keepAliveAckCount.get() >= 1 }

    val plexSid = env.wiring.plexRuntimeManager.serverIdProvider().shouldBeRight()
    val jfRuntime = env.wiring.mediaServerRegistry.runtimes.first { it.serverType == "jellyfin" }
    val jfSid = jfRuntime.serverIdProvider().shouldBeRight()

    env.wiring.syncServices.compositeLibrarySyncTrigger.triggerSync(deep = true, sectionKey = null, serverId = null).shouldBeRight()
    env.wiring.syncServices.plexUserSyncService.syncOnce().shouldBeRight()
    env.wiring.syncServices.additionalUserSyncServices.forEach { it.syncOnce().shouldBeRight() }

    val allUsers = env.userRepo.findAll().shouldBeRight()
    val plexAlice = allUsers.first { it.serverId == plexSid && it.username == "alice" }
    val jfAlice = allUsers.first { it.serverId == jfSid && it.username == "alice" }
    (plexAlice.id != jfAlice.id) shouldBe true

    env.authService.createAccount("it-admin", "AdminPass-123!", role = MiruRole.ADMIN).shouldBeRight()
    env.authService
        .createAccount("it-plex-viewer", "ViewerPass-123!", role = MiruRole.USER, linkedMediaUserIds = setOf(plexAlice.id))
        .shouldBeRight()
    val adminCookie = env.authService.login("it-admin", "AdminPass-123!").shouldBeRight().signedToken
    val plexViewerCookie = env.authService.login("it-plex-viewer", "ViewerPass-123!").shouldBeRight().signedToken
    return PipelineItSessionContext(plexSid, jfSid, jfAlice.id, adminCookie, plexViewerCookie)
}

private suspend fun verifyServersAndCatalog(
    client: HttpClient,
    ctx: PipelineItSessionContext,
) {
    val publicServers = MiruJson.decodeFromString<MediaServersResponse>(client.getJsonOk("/api/v1/servers", ctx.adminCookie))
    publicServers.servers shouldHaveSize 2
    publicServers.servers.all { it.configured && it.websocketState == "CONNECTED" } shouldBe true

    val adminServers = MiruJson.decodeFromString<AdminServersResponse>(client.getJsonOk("/api/v1/admin/servers", ctx.adminCookie))
    adminServers.servers shouldHaveSize 2
    adminServers.servers.all { it.reachable && it.websocketState == "CONNECTED" } shouldBe true

    val allLibs = MiruJson.decodeFromString<LibraryListResponse>(client.getJsonOk("/api/v1/libraries", ctx.adminCookie))
    allLibs.libraries shouldHaveSize 4
    val jfLibs =
        MiruJson.decodeFromString<LibraryListResponse>(
            client.getJsonOk("/api/v1/libraries?serverId=${ctx.jfSid}", ctx.adminCookie),
        )
    jfLibs.libraries shouldHaveSize 2

    val jfUsers =
        MiruJson.decodeFromString<UsersListResponse>(client.getJsonOk("/api/v1/users?serverId=${ctx.jfSid}", ctx.adminCookie))
    jfUsers.users shouldHaveSize 2

    val jfDetails =
        MiruJson.decodeFromString<MediaDetailsResponse>(
            client.getJsonOk("/api/v1/media/jf-ep1?serverId=${ctx.jfSid}", ctx.adminCookie),
        )
    jfDetails.title shouldBe "Dulcinea"
    jfDetails.serverId shouldBe ctx.jfSid
    jfDetails.series?.title shouldBe "The Expanse"
}

private suspend fun verifyLiveStreamsTerminationAndHistory(
    client: HttpClient,
    env: PipelineItEnv,
    ctx: PipelineItSessionContext,
) {
    env.plexServer.setActiveSessionsJson(plexActiveMovieSessionJson())
    env.jfServer.setActiveSessionsJson(jellyfinActiveEpisodeSessionJson(paused = false))
    env.plexServer.pushPlayingNotification("pk-live-1", "pm-1", "playing", 1_200_000L)
    env.jfServer.pushSessionsUpdate()

    pollUntil { env.wiring.mediaServerRegistry.currentSessions().size == 2 }
    val liveStreams = MiruJson.decodeFromString<ActiveStreamsResponse>(client.getJsonOk("/api/v1/streams", ctx.adminCookie))
    liveStreams.totalCount shouldBe 2
    liveStreams.streams.first { it.serverId == ctx.plexSid }.media.title shouldBe "Spirited Away"
    val jfStream = liveStreams.streams.first { it.serverId == ctx.jfSid }
    jfStream.media.title shouldBe "Dulcinea"
    jfStream.technical.hwTranscode shouldBe true
    pollUntil { env.metricsRuntime.service.scrape().contains("miru_active_streams") }
    env.metricsRuntime.service.scrape() shouldContain "miru_active_streams"

    env.jfServer.setActiveSessionsJson(jellyfinActiveEpisodeSessionJson(paused = true))
    env.jfServer.pushSessionsUpdate()
    pollUntil {
        env.wiring.mediaServerRegistry.currentSessions().firstOrNull { it.serverId == ctx.jfSid }?.state == "paused"
    }

    val termPlexResp =
        client.post("/api/v1/streams/ps-live-1/terminate?serverId=${ctx.plexSid}") {
            cookie(SESSION_COOKIE_NAME, ctx.adminCookie)
            contentType(ContentType.Application.Json)
            setBody(MiruJson.encodeToString(TerminateStreamRequest(reason = "Plex IT stop")))
        }
    termPlexResp.status shouldBe HttpStatusCode.OK
    env.plexServer.terminatedSessions.any { it.first == "ps-live-1" && it.second == "Plex IT stop" } shouldBe true

    val termJfResp =
        client.post("/api/v1/streams/jf-live-1/terminate?serverId=${ctx.jfSid}") {
            cookie(SESSION_COOKIE_NAME, ctx.adminCookie)
            contentType(ContentType.Application.Json)
            setBody(MiruJson.encodeToString(TerminateStreamRequest(reason = "JF IT stop")))
        }
    termJfResp.status shouldBe HttpStatusCode.OK
    env.jfServer.receivedMessages.any { it.first == "jf-live-1" && it.second.contains("JF IT stop") } shouldBe true
    env.jfServer.stoppedSessions.contains("jf-live-1") shouldBe true

    env.plexServer.setActiveSessionsJson("""{"MediaContainer":{"size":0,"Metadata":[]}}""")
    env.jfServer.setActiveSessionsJson("[]")
    env.plexServer.pushPlayingNotification("pk-live-1", "pm-1", "stopped", 1_500_000L)
    env.jfServer.pushSessionsUpdate("[]")

    pollUntil {
        env.wiring.mediaServerRegistry.currentSessions().isEmpty() &&
            env.historyRepo.query(HistoryFilter()).shouldBeRight().totalCount >= 2L
    }
    val jfHistory =
        MiruJson.decodeFromString<HistoryListResponse>(
            client.getJsonOk("/api/v1/history?serverId=${ctx.jfSid}", ctx.adminCookie),
        )
    jfHistory.items shouldHaveSize 1
    jfHistory.items.single().title shouldBe "Dulcinea"
}

private suspend fun verifyArtworkRbacAndReconnect(
    client: HttpClient,
    env: PipelineItEnv,
    ctx: PipelineItSessionContext,
) {
    val jfPoster =
        client.get("/api/v1/media/jf-m1/thumb?serverId=${ctx.jfSid}") {
            cookie(SESSION_COOKIE_NAME, ctx.adminCookie)
        }
    jfPoster.status shouldBe HttpStatusCode.OK
    jfPoster.bodyAsBytes() shouldBe TEST_PNG_BYTES

    val jfAvatar =
        client.get("/api/v1/users/${ctx.jfAliceId}/avatar") {
            cookie(SESSION_COOKIE_NAME, ctx.adminCookie)
        }
    jfAvatar.status shouldBe HttpStatusCode.OK
    jfAvatar.bodyAsBytes() shouldBe TEST_PNG_BYTES

    val plexPoster =
        client.get("/api/v1/media/pm-1/thumb?serverId=${ctx.plexSid}") {
            cookie(SESSION_COOKIE_NAME, ctx.adminCookie)
        }
    plexPoster.status shouldBe HttpStatusCode.OK
    plexPoster.bodyAsBytes() shouldBe TEST_PNG_BYTES

    val forbiddenJfPoster =
        client.get("/api/v1/media/jf-m1/thumb?serverId=${ctx.jfSid}") {
            cookie(SESSION_COOKIE_NAME, ctx.plexViewerCookie)
        }
    (forbiddenJfPoster.status == HttpStatusCode.Forbidden || forbiddenJfPoster.status == HttpStatusCode.NotFound) shouldBe true

    val jfRuntime = env.wiring.mediaServerRegistry.runtimes.first { it.serverType == "jellyfin" }
    val plexWsBefore = env.plexServer.wsConnectionCount.get()
    val jfWsBefore = env.jfServer.wsConnectionCount.get()
    env.plexServer.dropAllWebSockets()
    env.jfServer.dropAllWebSockets()
    env.plexServer.waitForWsConnected(minConnections = plexWsBefore + 1)
    env.jfServer.waitForWsConnected(minConnections = jfWsBefore + 1)
    pollUntil { jfRuntime.connectionState() == ConnectionState.Connected }

    env.jfServer.outageEnabled.set(true)
    val unhealthy = withTimeout(12_000L) { jfRuntime.reconcileHealth.first { !it.reachable } }
    unhealthy.reachable shouldBe false

    env.jfServer.outageEnabled.set(false)
    val recovered = withTimeout(12_000L) { jfRuntime.reconcileHealth.first { it.reachable } }
    recovered.reachable shouldBe true
}

private fun mediaServerPipelineIntegrationSpec(
    dialectLabel: String,
    databaseConfigFactory: () -> Pair<DatabaseConfig, () -> Unit>,
) = funSpec {
    test("$dialectLabel: real-socket Plex + Jellyfin WS/HTTP pipeline, history, sync, artwork RBAC, termination, metrics, and reconnect") {
        val (dbConfig, cleanupDb) = databaseConfigFactory()
        val env = createPipelineItEnv(dbConfig, cleanupDb)
        try {
            val ctx = bootstrapAndSync(env)
            testApplication {
                application {
                    miruModule(
                        config = env.appConfig,
                        appVersion = "1.0.0-it",
                        authService = env.authService,
                        artworkProxyService = env.wiring.artworkProxyService,
                        streamsAndUsersService = env.wiring.streamsAndUsersService,
                        streamControlService = env.wiring.streamControlService,
                        historyAndStatsService = env.historyStats,
                        libraryQueryService = env.wiring.libraryQueryService,
                        librarySyncTrigger = env.wiring.syncServices.compositeLibrarySyncTrigger,
                        mediaDetailsService = env.wiring.mediaDetailsService,
                        metricsService = env.metricsRuntime.service,
                        mediaServerRegistry = env.wiring.mediaServerRegistry,
                    )
                }
                verifyServersAndCatalog(client, ctx)
                verifyLiveStreamsTerminationAndHistory(client, env, ctx)
                verifyArtworkRbacAndReconnect(client, env, ctx)
            }
        } finally {
            env.closeAll()
        }
    }
}

class MediaServerPipelineIntegrationTest :
    FunSpec({
        val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("miru_pipeline_it")
                .withUsername("miru")
                .withPassword("miru_it_password")

        beforeSpec {
            postgres.start()
        }

        afterSpec {
            if (postgres.isRunning) {
                postgres.stop()
            }
        }

        include(
            mediaServerPipelineIntegrationSpec("SQLite") {
                val tempDir = Files.createTempDirectory("miru-pipeline-sqlite-it-")
                DatabaseConfig.Sqlite(tempDir.resolve("miru.db").toString()) to {
                    tempDir.toFile().deleteRecursively()
                }
            },
        )

        include(
            mediaServerPipelineIntegrationSpec("PostgreSQL") {
                val dbName = "miru_pipeline_run"
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                    conn.createStatement().use { it.executeUpdate("CREATE DATABASE $dbName") }
                }
                DatabaseConfig.Postgres(
                    url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$dbName"),
                    user = postgres.username,
                    password = postgres.password,
                    maxPoolSize = 6,
                ) to {}
            },
        )
    })
