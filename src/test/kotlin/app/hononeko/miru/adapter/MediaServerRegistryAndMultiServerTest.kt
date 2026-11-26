package app.hononeko.miru.adapter

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.http.ActiveStreamsResponse
import app.hononeko.miru.http.AdminServersResponse
import app.hononeko.miru.http.HistoryListResponse
import app.hononeko.miru.http.LibraryListResponse
import app.hononeko.miru.http.MediaDetailsResponse
import app.hononeko.miru.http.MediaServersResponse
import app.hononeko.miru.http.UsersListResponse
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserLibraryAccessRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedGuardrailRuleRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedStreamTerminationAuditRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.MediaDetailsService
import app.hononeko.miru.service.MediaSectionAccessChecker
import app.hononeko.miru.service.StreamControlService
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.ReconcileTrigger
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

private const val PLEX_SECRET = "plex-multi-secret-token"
private const val JF_SECRET = "jf-multi-secret-key"

private class MultiServerHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val plexRuntime: PlexRuntimeManager,
    val jellyfinRuntime: JellyfinRuntimeManager,
    val registry: MediaServerRegistry,
    val authService: AuthService,
    val appConfig: AppConfig,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val sessionRepo: ExposedActiveSessionRepository,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val libraryRepo: ExposedLibraryRepository,
) : AutoCloseable {
    override fun close() {
        runBlocking { registry.stop() }
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createPlexMockEngine(): MockEngine =
    MockEngine { req ->
        when (req.url.encodedPath) {
            "/" ->
                respond(
                    """{"MediaContainer":{"machineIdentifier":"plex-mid","friendlyName":"Home Plex","version":"1.41"}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            "/library/metadata/plex-m1" ->
                respond(
                    """{"MediaContainer":{"Metadata":[{"ratingKey":"plex-m1","type":"movie",""" +
                        """"title":"Plex Movie","librarySectionID":"1"}]}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            "/library/metadata/plex-m1/thumb" ->
                respond(byteArrayOf(10, 11), headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
            "/:/sessions/terminate" -> respond("")
            else -> respond("{}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    }

private fun createJellyfinMockEngine(): MockEngine =
    MockEngine { req ->
        when {
            req.url.encodedPath == "/System/Info" ->
                respond(
                    """{"Id":"jf-mid","ServerName":"Studio Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            req.url.encodedPath == "/Items/jf-m1" ->
                respond(
                    """{"Id":"jf-m1","Name":"Jellyfin Movie","Type":"Movie","ParentId":"jf-lib1","ImageTags":{"Primary":"p1"}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            req.url.encodedPath == "/Items/jf-m1/Ancestors" ->
                respond(
                    """[{"Id":"jf-lib1","Name":"JF Movies","Type":"CollectionFolder"}]""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            req.url.encodedPath == "/Items/jf-m1/Images/Primary" ->
                respond(byteArrayOf(20, 21, 22), headers = headersOf(HttpHeaders.ContentType, "image/png"))
            req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/Stop") ->
                respond("", status = HttpStatusCode.NoContent)
            else -> respond("[]", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    }

private suspend fun seedMultiServerFixtures(
    plexSid: String,
    jfSid: String,
    userRepo: ExposedUserRepository,
    libraryRepo: ExposedLibraryRepository,
    sessionRepo: ExposedActiveSessionRepository,
    historyRepo: ExposedPlaybackHistoryRepository,
) {
    userRepo.upsert(UserRecord("u-plex", plexSid, "1", "alice", "Alice", createdAtMs = 1L, updatedAtMs = 1L)).shouldBeRight()
    userRepo.upsert(UserRecord("u-jf", jfSid, "jf1", "alice", "Alice JF", createdAtMs = 1L, updatedAtMs = 1L)).shouldBeRight()
    libraryRepo.upsertSection(LibrarySectionRecord(plexSid, "1", "Movies", "movie", itemCount = 10, updatedAtMs = 1L)).shouldBeRight()
    libraryRepo.upsertSection(
        LibrarySectionRecord(jfSid, "jf-lib1", "JF Movies", "movie", itemCount = 5, updatedAtMs = 1L),
    ).shouldBeRight()
    libraryRepo.replaceUserLibraryAccess(
        jfSid,
        listOf(UserLibraryAccessRecord(jfSid, "jf1", allLibraries = true, updatedAtMs = 1L)),
    ).shouldBeRight()

    val plexMedia = MediaMetadataRecord("plex-m1", "movie", "Plex Movie", durationMs = 100_000L, librarySectionKey = "1")
    val jfMedia =
        MediaMetadataRecord(
            ratingKey = "jf-m1",
            mediaType = "movie",
            title = "Jellyfin Movie",
            thumbPath = "/Items/jf-m1/Images/Primary",
            durationMs = 200_000L,
            librarySectionKey = "jf-lib1",
        )
    sessionRepo.upsert(
        ActiveSessionRecord(
            serverId = plexSid,
            sessionKey = "pk-1",
            sessionId = "ps-1",
            userId = "u-plex",
            media = plexMedia,
            viewOffsetMs = 10_000L,
            state = "playing",
            streamTech = StreamTechRecord("direct_play"),
            player = PlayerRecord(playerName = "Apple TV"),
            startedAtMs = 1_000L,
            lastSeenAtMs = 2_000L,
        ),
    ).shouldBeRight()
    sessionRepo.upsert(
        ActiveSessionRecord(
            serverId = jfSid,
            sessionKey = "jk-1",
            sessionId = "js-1",
            userId = "u-jf",
            media = jfMedia,
            viewOffsetMs = 50_000L,
            state = "playing",
            streamTech = StreamTechRecord("transcode", videoDecision = "transcode", hwTranscode = true),
            player = PlayerRecord(playerName = "Jellyfin Web"),
            startedAtMs = 1_500L,
            lastSeenAtMs = 2_500L,
        ),
    ).shouldBeRight()
    historyRepo.insert(
        PlaybackHistoryRecord(
            id = "h-plex",
            serverId = plexSid,
            userId = "u-plex",
            media = plexMedia,
            viewOffsetMs = 100_000L,
            watchedDurationSec = 100,
            completionPercent = 100.0,
            isWatched = true,
            streamTech = StreamTechRecord("direct_play"),
            player = PlayerRecord(playerName = "Apple TV"),
            startedAtMs = 1_000L,
            stoppedAtMs = 2_000L,
        ),
    ).shouldBeRight()
    historyRepo.insert(
        PlaybackHistoryRecord(
            id = "h-jf",
            serverId = jfSid,
            userId = "u-jf",
            media = jfMedia,
            viewOffsetMs = 200_000L,
            watchedDurationSec = 200,
            completionPercent = 100.0,
            isWatched = true,
            streamTech = StreamTechRecord("transcode"),
            player = PlayerRecord(playerName = "Jellyfin Web"),
            startedAtMs = 1_500L,
            stoppedAtMs = 3_000L,
        ),
    ).shouldBeRight()
}

private suspend fun createMultiServerHarness(): MultiServerHarness {
    val dir = Files.createTempDirectory("miru-multiserver-test-")
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val sessionRepo = ExposedActiveSessionRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val libraryRepo = ExposedLibraryRepository(handle)

    val plexConfig = PlexConfig(baseUrl = "http://plex.local:32400", token = PLEX_SECRET, websocketEnabled = false)
    val jfConfig = JellyfinConfig(baseUrl = "http://jellyfin.local:8096", apiKey = JF_SECRET, websocketEnabled = false)
    val plexEngine = createPlexMockEngine()
    val jfEngine = createJellyfinMockEngine()

    val plexRuntime =
        PlexRuntimeManager(
            plexConfig = plexConfig,
            databaseHandle = handle,
            mediaServerRepository = serverRepo,
            httpClientEngineFactory = { plexEngine },
        )
    val jfRuntime =
        JellyfinRuntimeManager(
            jellyfinConfig = jfConfig,
            databaseHandle = handle,
            mediaServerRepository = serverRepo,
            httpClientEngineFactory = { jfEngine },
        )
    plexRuntime.initialize().shouldBeRight()
    jfRuntime.initialize().shouldBeRight()

    val registry =
        MediaServerRegistry(
            runtimes = listOf(plexRuntime, jfRuntime),
            mediaServerRepository = serverRepo,
            jellyfinConfig = jfConfig,
        )
    val plexSid = plexRuntime.serverIdProvider().shouldBeRight()
    val jfSid = jfRuntime.serverIdProvider().shouldBeRight()
    seedMultiServerFixtures(plexSid, jfSid, userRepo, libraryRepo, sessionRepo, historyRepo)

    val authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = "multi-server-session-secret-32chars!")
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 8192, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    authService.createAccount("admin", "AdminPass-123!", role = MiruRole.ADMIN).shouldBeRight()
    authService.createAccount("viewer", "ViewerPass-123!", role = MiruRole.USER, linkedMediaUserIds = setOf("u-jf")).shouldBeRight()
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dir.resolve("miru.db").toString()),
            plex = plexConfig,
            jellyfin = jfConfig,
            session = SessionConfig(),
            auth = authConfig,
        )

    return MultiServerHarness(
        dir,
        handle,
        plexRuntime,
        jfRuntime,
        registry,
        authService,
        appConfig,
        serverRepo,
        userRepo,
        sessionRepo,
        historyRepo,
        libraryRepo,
    )
}

class MediaServerRegistryAndMultiServerTest :
    FunSpec({
        test("unconfigured JellyfinRuntimeManager stays dormant cleanly") {
            val dir = Files.createTempDirectory("miru-jf-dormant-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            try {
                val jf = JellyfinRuntimeManager(JellyfinConfig(), handle)
                jf.initialize().shouldBeRight() shouldBe false
                jf.isConfigured() shouldBe false
                jf.stop()
            } finally {
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("serves unified Plex + Jellyfin streams, history, libraries, details, artwork, termination, and /servers") {
            val h = createMultiServerHarness()
            try {
                val adminSession = h.authService.login("admin", "AdminPass-123!").shouldBeRight().signedToken
                val viewerSession = h.authService.login("viewer", "ViewerPass-123!").shouldBeRight().signedToken
                val jfSid = h.jellyfinRuntime.serverIdProvider().shouldBeRight()
                val plexSid = h.plexRuntime.serverIdProvider().shouldBeRight()

                val sectionChecker =
                    MediaSectionAccessChecker(
                        libraryRepository = h.libraryRepo,
                        adapter = h.plexRuntime.adapter,
                        adapterForServer = { sid -> h.registry.adapterForServer(sid) },
                    )
                val streamsUsers =
                    StreamsAndUsersService(
                        h.sessionRepo,
                        h.userRepo,
                        h.historyRepo,
                        { h.registry.currentLiveTechnical() },
                        { h.registry.currentDefaultServerName() },
                        h.serverRepo,
                    )
                val historyStats = HistoryAndStatsService(h.historyRepo, h.userRepo, mediaServerRepository = h.serverRepo)
                val libQuery = LibraryQueryService(h.libraryRepo, mediaServerRepository = h.serverRepo)
                val detailsService =
                    MediaDetailsService(
                        h.plexRuntime.adapter,
                        h.historyRepo,
                        h.libraryRepo,
                        sectionChecker,
                        adapterForServer = { sid -> h.registry.adapterForServer(sid) },
                        allServerAdaptersProvider = { h.registry.activeServerAdapters() },
                        mediaServerRepository = h.serverRepo,
                    )
                val artworkService =
                    ArtworkProxyService(
                        adapter = h.plexRuntime.adapter,
                        userRepository = h.userRepo,
                        libraryRepository = h.libraryRepo,
                        sectionAccessChecker = sectionChecker,
                        adapterForServer = { sid -> h.registry.adapterForServer(sid) },
                        allServerAdaptersProvider = { h.registry.activeServerAdapters() },
                        serverBaseUrlsProvider = { h.registry.configuredBaseUrls() },
                    )
                val streamControl =
                    StreamControlService(
                        h.plexRuntime.adapter,
                        h.sessionRepo,
                        h.userRepo,
                        ExposedGuardrailRuleRepository(h.dbHandle),
                        ExposedStreamTerminationAuditRepository(h.dbHandle),
                        adapterForServer = { sid -> h.registry.adapterForServer(sid) },
                    )

                testApplication {
                    application {
                        miruModule(
                            config = h.appConfig,
                            authService = h.authService,
                            streamsAndUsersService = streamsUsers,
                            historyAndStatsService = historyStats,
                            libraryQueryService = libQuery,
                            mediaDetailsService = detailsService,
                            artworkProxyService = artworkService,
                            streamControlService = streamControl,
                            mediaServerRegistry = h.registry,
                        )
                    }

                    // Non-admin /api/v1/servers hides baseUrl/machineId/secrets
                    val publicServersBody =
                        client.get("/api/v1/servers") { cookie(SESSION_COOKIE_NAME, viewerSession) }.bodyAsText()
                    publicServersBody shouldNotContain "plex.local"
                    publicServersBody shouldNotContain JF_SECRET
                    val publicServers = MiruJson.decodeFromString<MediaServersResponse>(publicServersBody)
                    publicServers.servers shouldHaveSize 2
                    publicServers.servers.map { it.serverType }.toSet() shouldBe setOf("plex", "jellyfin")

                    // Admin /api/v1/admin/servers includes details
                    val adminServers =
                        MiruJson.decodeFromString<AdminServersResponse>(
                            client.get("/api/v1/admin/servers") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    adminServers.servers shouldHaveSize 2

                    // Unified streams + ?serverId filter
                    val allStreams =
                        MiruJson.decodeFromString<ActiveStreamsResponse>(
                            client.get("/api/v1/streams") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    allStreams.streams shouldHaveSize 2
                    allStreams.streams.first { it.serverId == jfSid }.serverName shouldBe "Studio Jellyfin"
                    allStreams.streams.first { it.serverId == jfSid }.serverType shouldBe "jellyfin"

                    val jfStreams =
                        MiruJson.decodeFromString<ActiveStreamsResponse>(
                            client.get("/api/v1/streams?serverId=$jfSid") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    jfStreams.streams shouldHaveSize 1
                    jfStreams.streams[0].media.title shouldBe "Jellyfin Movie"

                    // Unified history + ?serverId filter
                    val allHistory =
                        MiruJson.decodeFromString<HistoryListResponse>(
                            client.get("/api/v1/history") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    allHistory.items shouldHaveSize 2
                    val plexHistory =
                        MiruJson.decodeFromString<HistoryListResponse>(
                            client.get("/api/v1/history?serverId=$plexSid") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    plexHistory.items shouldHaveSize 1
                    plexHistory.items[0].serverName shouldBe "Home Plex"

                    // Users + Libraries include server metadata
                    val usersResp =
                        MiruJson.decodeFromString<UsersListResponse>(
                            client.get("/api/v1/users?serverId=$jfSid") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    usersResp.users shouldHaveSize 1
                    usersResp.users[0].serverType shouldBe "jellyfin"

                    val libsResp =
                        MiruJson.decodeFromString<LibraryListResponse>(
                            client.get("/api/v1/libraries") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    libsResp.libraries shouldHaveSize 2

                    // Scoped non-admin access to Jellyfin details + artwork proxy
                    val jfDetails =
                        MiruJson.decodeFromString<MediaDetailsResponse>(
                            client.get("/api/v1/media/jf-m1?serverId=$jfSid") { cookie(SESSION_COOKIE_NAME, viewerSession) }.bodyAsText(),
                        )
                    jfDetails.serverId shouldBe jfSid
                    jfDetails.serverType shouldBe "jellyfin"

                    val artResp =
                        client.get("/api/v1/media/jf-m1/thumb?serverId=$jfSid") {
                            cookie(SESSION_COOKIE_NAME, viewerSession)
                        }
                    artResp.status shouldBe HttpStatusCode.OK
                    artResp.bodyAsBytes() shouldBe byteArrayOf(20, 21, 22)

                    // Viewer cannot read Plex-only artwork
                    client.get("/api/v1/media/plex-m1/thumb?serverId=$plexSid") {
                        cookie(SESSION_COOKIE_NAME, viewerSession)
                    }.status shouldBe HttpStatusCode.Forbidden

                    // Admin terminates Jellyfin stream via server-scoped route
                    client.post("/api/v1/streams/js-1/terminate?serverId=$jfSid") {
                        cookie(SESSION_COOKIE_NAME, adminSession)
                    }.status shouldBe HttpStatusCode.OK

                    // Null serverId returns both details via fallback adapters
                    val jfAuto =
                        MiruJson.decodeFromString<MediaDetailsResponse>(
                            client.get("/api/v1/media/jf-m1") { cookie(SESSION_COOKIE_NAME, adminSession) }.bodyAsText(),
                        )
                    jfAuto.serverName shouldBe "Studio Jellyfin"
                    jfAuto.ratingKey shouldBe "jf-m1"
                    client.get("/api/v1/users") { cookie(SESSION_COOKIE_NAME, viewerSession) }.status shouldBe HttpStatusCode.Forbidden
                    val unlinkedCheck =
                        MiruJson.decodeFromString<ActiveStreamsResponse>(
                            client.get("/api/v1/streams?serverId=$plexSid") { cookie(SESSION_COOKIE_NAME, viewerSession) }.bodyAsText(),
                        )
                    unlinkedCheck.streams shouldHaveSize 0
                    jfAuto.artUrl.shouldBeNull()
                }
            } finally {
                h.close()
            }
        }

        test("registry manages start/stop, server lookup helpers, historical DB servers, and unconfigured server messages") {
            val h = createMultiServerHarness()
            try {
                val jfSid = h.jellyfinRuntime.serverIdProvider().shouldBeRight()
                h.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "hist-srv",
                            name = "Old Jellyfin",
                            serverType = "jellyfin",
                            baseUrl = "http://old-jf:8096",
                            machineId = "old-mid",
                            createdAtMs = 1L,
                            updatedAtMs = 1L,
                        ),
                    ).shouldBeRight()

                val jfSnapshot =
                    ActiveStreamSnapshot(
                        sessionId = "js-live",
                        sessionKey = "js-live",
                        state = PlaybackState.PLAYING,
                        viewOffsetMs = 42_000L,
                        media =
                            StreamMedia(
                                ratingKey = "jf-m1",
                                mediaType = MediaType.MOVIE,
                                title = "Jellyfin Movie",
                                durationMs = 200_000L,
                                librarySectionId = "jf-lib1",
                            ),
                        user = StreamUser(externalId = "jf1", username = "alice"),
                        player =
                            StreamPlayer(
                                machineIdentifier = "jf-dev-1",
                                product = "Jellyfin Web",
                                location = StreamLocation.LAN,
                                isLocal = true,
                            ),
                        technical = StreamTechnicalInfo(decision = StreamDecision.TRANSCODE, isHwTranscode = true),
                    )
                h.jellyfinRuntime.sessionEngine
                    .reconcile(listOf(jfSnapshot), ReconcileTrigger.PERIODIC)
                    .shouldBeRight()
                h.registry.activeAdapters() shouldHaveSize 2
                h.registry.activeServerAdapters() shouldHaveSize 2
                h.registry.configuredBaseUrls() shouldHaveSize 2
                h.registry.serverMap().containsKey("hist-srv") shouldBe true
                h.registry.serverSummaryById(jfSid)?.name shouldBe "Studio Jellyfin"
                h.registry.serverSummaryById("hist-srv")?.name shouldBe "Old Jellyfin"
                h.registry.currentDefaultServerName() shouldBe "Home Plex · Studio Jellyfin"
                (h.registry.currentSessions().any { it.sessionKey == "js-live" }) shouldBe true
                h.registry.currentLiveTechnical().isNotEmpty() shouldBe true
                h.registry.adapterForServer(jfSid)?.serverType shouldBe "jellyfin"
                h.registry.adapterForServer("missing-id") shouldBe null

                val pub = h.registry.listPublicServers()
                pub.servers shouldHaveSize 3
                val adm = h.registry.listAdminServers()
                adm.servers.first { it.id == "hist-srv" }.statusMessage shouldBe
                    "Historical or unconfigured server record"

                h.registry.start()
                h.registry.stop()
            } finally {
                h.close()
            }
        }

        test("registry handles unconfigured runtimes, single-server display names, and async adapterForServer fallback") {
            val dir = Files.createTempDirectory("miru-registry-edge-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            try {
                val serverRepo = ExposedMediaServerRepository(handle)
                val plexUnconfigured =
                    PlexRuntimeManager(PlexConfig(websocketEnabled = false), handle, mediaServerRepository = serverRepo)
                val jfUnconfigured =
                    JellyfinRuntimeManager(JellyfinConfig(websocketEnabled = false), handle, mediaServerRepository = serverRepo)
                val emptyRegistry =
                    MediaServerRegistry(
                        runtimes = listOf(plexUnconfigured, jfUnconfigured),
                        mediaServerRepository = serverRepo,
                        jellyfinConfig = JellyfinConfig(),
                    )
                emptyRegistry.currentDefaultServerName().shouldBeNull()
                emptyRegistry.serverSummaryById(null).shouldBeNull()
                emptyRegistry.adapterForServer("   ").shouldBeNull()
                emptyRegistry.listPublicServers().servers shouldHaveSize 0
                val adminEmpty = emptyRegistry.listAdminServers().servers
                adminEmpty shouldHaveSize 2
                adminEmpty.first { it.serverType == "plex" }.statusMessage shouldBe "No Plex server configured"
                adminEmpty.first { it.serverType == "jellyfin" }.statusMessage shouldBe
                    "No Jellyfin server configured"

                // Single configured Jellyfin runtime whose cachedServerId is not yet warmed exercises async adapterForServer lookup
                val jfEngine = createJellyfinMockEngine()
                val jfConfig = JellyfinConfig(baseUrl = "http://jf-only.local:8096", apiKey = JF_SECRET, websocketEnabled = false)
                val jfOnly =
                    JellyfinRuntimeManager(
                        jellyfinConfig = jfConfig,
                        databaseHandle = handle,
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { jfEngine },
                    )
                jfOnly.initialize().shouldBeRight() shouldBe true
                val singleRegistry = MediaServerRegistry(listOf(jfOnly), serverRepo, jfConfig)
                val jfSid = jfOnly.serverIdProvider().shouldBeRight()
                singleRegistry.currentDefaultServerName() shouldBe "Studio Jellyfin"
                singleRegistry.adapterForServer(jfSid)?.serverType shouldBe "jellyfin"
                singleRegistry.stop()
                emptyRegistry.stop()
            } finally {
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
