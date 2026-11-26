package app.hononeko.miru.adapter

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.artwork.ArtworkKind
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.BoundedArtworkCache
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.AuthSource
import app.hononeko.miru.auth.EmailMediaUserLinker
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.cleanupOnStartupFailure
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.MetricsConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.createCompositeLibrarySyncTrigger
import app.hononeko.miru.createCoreServiceWiring
import app.hononeko.miru.createMetricsRuntime
import app.hononeko.miru.createSyncServices
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.MediaServersResponse
import app.hononeko.miru.library.LibraryStatsCollector
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleRecord
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.TerminationAuditFilter
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
import app.hononeko.miru.registerLifecycleHandlers
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.MediaSectionAccessChecker
import app.hononeko.miru.service.StreamControlService
import app.hononeko.miru.session.ReconcileTrigger
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private data class ArtworkRbacContext(
    val dir: Path,
    val handle: DatabaseHandle,
    val plexAdapter: MediaServerAdapter,
    val jfAdapter: MediaServerAdapter,
    val service: ArtworkProxyService,
)

private fun mockArtworkAdapter(
    type: String,
    thumbPathPrefix: String,
    fetchHandler: suspend (String) -> Either<MiruError, ArtworkData>,
): MediaServerAdapter {
    val adapter = mockk<MediaServerAdapter>(relaxed = true)
    every { adapter.serverType } returns type
    every { adapter.mediaArtworkPath(any(), any()) } answers {
        val key = firstArg<String>()
        if (thumbPathPrefix == "plex") "/library/metadata/$key/thumb" else "/Items/$key/Images/Primary"
    }
    coEvery { adapter.fetchArtwork(any(), any(), any()) } coAnswers { fetchHandler(firstArg()) }
    return adapter
}

private suspend fun createArtworkRbacContext(): ArtworkRbacContext {
    val dir = Files.createTempDirectory("miru-artwork-rbac-")
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
    val userRepo = ExposedUserRepository(handle)
    val libraryRepo = ExposedLibraryRepository(handle)
    val serverRepo = ExposedMediaServerRepository(handle)
    serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-a",
                name = "Plex A",
                serverType = "plex",
                machineId = "mach-a",
                baseUrl = "http://plex-a:32400",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        ).shouldBeRight()
    serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-b",
                name = "Jellyfin B",
                serverType = "jellyfin",
                machineId = "mach-b",
                baseUrl = "http://jf-b:8096",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        ).shouldBeRight()
    serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-missing",
                name = "Offline Server",
                serverType = "jellyfin",
                machineId = "mach-missing",
                baseUrl = "http://jf-missing:8096",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        ).shouldBeRight()
    userRepo
        .upsert(
            UserRecord(
                "u-a",
                "srv-a",
                "plex-1",
                "viewer",
                thumbUrl = "/library/metadata/avatar-a",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        ).shouldBeRight()
    userRepo
        .upsert(
            UserRecord(
                "u-b",
                "srv-b",
                "jf-1",
                "jfuser",
                thumbUrl = "/Users/jf-1/Images/Primary",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        ).shouldBeRight()
    userRepo
        .upsert(
            UserRecord(
                "u-orphan",
                "srv-missing",
                "m-1",
                "orphan",
                thumbUrl = "/Users/m-1/Images/Primary",
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        ).shouldBeRight()
    libraryRepo.upsertSection(LibrarySectionRecord("srv-a", "sec-a", "A Movies", "movie", 1, updatedAtMs = 1L)).shouldBeRight()
    libraryRepo.upsertSection(LibrarySectionRecord("srv-b", "sec-b", "B Secret", "movie", 1, updatedAtMs = 1L)).shouldBeRight()
    libraryRepo
        .replaceUserLibraryAccess("srv-a", listOf(UserLibraryAccessRecord("srv-a", "plex-1", allLibraries = true, updatedAtMs = 1L)))
        .shouldBeRight()
    libraryRepo
        .upsertMediaItems(
            listOf(MediaItemCacheRecord("srv-b", "42", "sec-b", "movie", "Secret B Item", addedAtMs = 1L, updatedAtMs = 1L)),
        ).shouldBeRight()

    val plexAdapter = mockArtworkAdapter("plex", "plex") { Either.Left(MiruError.NotFound(resource = "Artwork", id = "42")) }
    val jfAdapter =
        mockArtworkAdapter("jellyfin", "jellyfin") { path ->
            if (path == "/Users/jf-1/Images/Primary") {
                Either.Right(ArtworkData(byteArrayOf(7, 8, 9), "image/png"))
            } else {
                Either.Right(ArtworkData(byteArrayOf(4, 2), "image/jpeg"))
            }
        }
    coEvery { plexAdapter.fetchMediaSectionKey("42") } returns Either.Right("sec-a")
    coEvery { jfAdapter.fetchMediaSectionKey("42") } returns Either.Right("sec-b")
    val adapters = mapOf("srv-a" to plexAdapter, "srv-b" to jfAdapter)
    val accessChecker = MediaSectionAccessChecker(libraryRepo, plexAdapter, adapterForServer = { sid -> adapters[sid] })
    val service =
        ArtworkProxyService(
            adapter = plexAdapter,
            userRepository = userRepo,
            cache = BoundedArtworkCache(),
            libraryRepository = libraryRepo,
            sectionAccessChecker = accessChecker,
            adapterForServer = { sid -> adapters[sid] },
            allServerAdaptersProvider = { listOf("srv-a" to plexAdapter, "srv-b" to jfAdapter) },
        )
    return ArtworkRbacContext(dir, handle, plexAdapter, jfAdapter, service)
}

private fun createPlexSyncEngine(): MockEngine =
    MockEngine { req ->
        when (req.url.encodedPath) {
            "/" ->
                respond(
                    """{"MediaContainer":{"machineIdentifier":"plex-w1","friendlyName":"Home Plex","version":"1.41"}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            "/library/sections" ->
                respond(
                    """{"MediaContainer":{"Directory":[{"key":"1","title":"Plex Movies","type":"movie"}]}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            "/library/sections/1/all" ->
                respond(
                    """{"MediaContainer":{"size":1,"totalSize":1,"Metadata":[{"ratingKey":"pm-1","type":"movie","title":"Plex Film"}]}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            "/accounts" ->
                respond(
                    """{"MediaContainer":{"Account":[{"id":1,"name":"plexuser"}]}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            else -> respond("{}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    }

private fun createJellyfinSyncEngine(terminateCalls: AtomicInteger): MockEngine =
    MockEngine { req ->
        when {
            req.url.encodedPath == "/System/Info" ->
                respond(
                    """{"Id":"jf-w1","ServerName":"Studio Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            req.url.encodedPath == "/Library/VirtualFolders" ->
                respond(
                    """[{"ItemId":"jf-1","Name":"JF Shows","CollectionType":"tvshows"}]""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            req.url.encodedPath == "/Items" ->
                respond(
                    """{"Items":[{"Id":"jm-1","Name":"Pilot","Type":"Episode","ParentId":"jf-1"}],"TotalRecordCount":1}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            req.url.encodedPath == "/Users" ->
                respond(
                    """[{"Id":"jfu-1","Name":"jfuser","Policy":{"IsAdministrator":false,"EnableAllFolders":true}}]""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

            req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/Stop") -> {
                terminateCalls.incrementAndGet()
                respond("", status = HttpStatusCode.NoContent)
            }

            else -> respond("{}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    }

class MultiServerWiringAndSecurityTest :
    FunSpec({
        test("ArtworkProxyService enforces per-target RBAC isolation and strict avatar adapter lookup") {
            val ctx = createArtworkRbacContext()
            try {
                val viewer =
                    MiruPrincipal(
                        accountId = "acc-v",
                        username = "viewer",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("u-a"),
                        authSource = AuthSource.SESSION_COOKIE,
                    )
                val admin =
                    MiruPrincipal(
                        accountId = "acc-adm",
                        username = "admin",
                        role = MiruRole.ADMIN,
                        authSource = AuthSource.SESSION_COOKIE,
                    )

                ctx.service
                    .getMediaArtwork(viewer, "42", ArtworkKind.THUMB, serverId = null)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.Forbidden>()
                coVerify(exactly = 0) { ctx.jfAdapter.fetchArtwork(any(), any(), any()) }

                ctx.service
                    .getMediaArtwork(admin, "42", ArtworkKind.THUMB, serverId = "srv-unknown")
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.NotFound>()
                ctx.service
                    .getMediaArtwork(admin, "42", ArtworkKind.THUMB, serverId = null)
                    .shouldBeRight()
                    .bytes shouldBe byteArrayOf(4, 2)
                ctx.service
                    .getMediaArtwork(admin, "42", ArtworkKind.ART, serverId = "srv-b")
                    .shouldBeRight()
                    .bytes shouldBe byteArrayOf(4, 2)

                ctx.service.getUserAvatar(admin, "u-b").shouldBeRight().bytes shouldBe byteArrayOf(7, 8, 9)
                ctx.service.getUserAvatar(admin, "u-orphan").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                coVerify(exactly = 0) { ctx.plexAdapter.fetchArtwork("/Users/m-1/Images/Primary", any(), any()) }
            } finally {
                ctx.handle.close()
                ctx.dir.toFile().deleteRecursively()
            }
        }

        test("createSyncServices, StreamGuardrailRunner, composite sync trigger, and metrics work across Plex + Jellyfin") {
            val dir = Files.createTempDirectory("miru-sync-wiring-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            val terminateCalls = AtomicInteger(0)
            val plexConfig = PlexConfig(baseUrl = "http://plex.local:32400", token = "plex-tok", websocketEnabled = false)
            val jfConfig = JellyfinConfig(baseUrl = "http://jf.local:8096", apiKey = "jf-tok", websocketEnabled = false)
            val serverRepo = ExposedMediaServerRepository(handle)
            val libraryRepo = ExposedLibraryRepository(handle)
            val userRepo = ExposedUserRepository(handle)
            val guardrailRepo = ExposedGuardrailRuleRepository(handle)
            val auditRepo = ExposedStreamTerminationAuditRepository(handle)
            val plexRuntime =
                PlexRuntimeManager(
                    plexConfig = plexConfig,
                    databaseHandle = handle,
                    mediaServerRepository = serverRepo,
                    httpClientEngineFactory = { createPlexSyncEngine() },
                )
            val jfRuntime =
                JellyfinRuntimeManager(
                    jellyfinConfig = jfConfig,
                    databaseHandle = handle,
                    mediaServerRepository = serverRepo,
                    httpClientEngineFactory = { createJellyfinSyncEngine(terminateCalls) },
                )
            plexRuntime.initialize().shouldBeRight()
            jfRuntime.initialize().shouldBeRight()
            val jfSid = jfRuntime.serverIdProvider().shouldBeRight()
            val registry = MediaServerRegistry(listOf(plexRuntime, jfRuntime), serverRepo, jfConfig)
            val streamControlService =
                StreamControlService(
                    adapter = plexRuntime.adapter,
                    activeSessionRepository = ExposedActiveSessionRepository(handle),
                    userRepository = userRepo,
                    guardrailRuleRepository = guardrailRepo,
                    auditRepository = auditRepo,
                    adapterForServer = { sid -> registry.adapterForServer(sid) },
                )
            val syncServices =
                createSyncServices(
                    plexRuntimeManager = plexRuntime,
                    libraryRepository = libraryRepo,
                    userRepository = userRepo,
                    streamControlService = streamControlService,
                    mediaServerRegistry = registry,
                    additionalRuntimes = listOf(jfRuntime),
                )
            val metricsEvents =
                (listOf(syncServices.libraryStatsCollector) + syncServices.additionalLibraryCollectors)
                    .map { it.syncEvents }
                    .merge()
            val metricsConfig =
                AppConfig(
                    server = ServerConfig("127.0.0.1", 8080),
                    database = DatabaseConfig.Sqlite(dir.resolve("miru.db").toString()),
                    plex = plexConfig,
                    jellyfin = jfConfig,
                    session = SessionConfig(),
                    auth = AuthConfig(mode = AuthMode.NONE),
                    metrics = MetricsConfig(enabled = true, host = "127.0.0.1", port = 8080),
                )
            val metricsRuntime =
                createMetricsRuntime(
                    appConfig = metricsConfig,
                    plexRuntimeManager = plexRuntime,
                    libraryRepository = libraryRepo,
                    mediaServerRepository = serverRepo,
                    libraryStatsCollector = syncServices.libraryStatsCollector,
                    mediaServerRegistry = registry,
                    librarySyncEvents = metricsEvents,
                ).shouldNotBeNull()

            try {
                syncServices.compositeLibrarySyncTrigger
                    .triggerSync(deep = false, sectionKey = null, serverId = "missing-srv")
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.NotFound>()
                syncServices.compositeLibrarySyncTrigger
                    .triggerSync(deep = true, sectionKey = null, serverId = jfSid)
                    .shouldBeRight()
                    .syncedSections shouldBe 1
                val aggSync =
                    syncServices.compositeLibrarySyncTrigger
                        .triggerSync(deep = true, sectionKey = null, serverId = null)
                        .shouldBeRight()
                aggSync.syncedSections shouldBe 2
                aggSync.totalItemsSynced shouldBe 2

                val dormantPlex = PlexRuntimeManager(PlexConfig(), handle, mediaServerRepository = serverRepo)
                val dormantCollector =
                    LibraryStatsCollector(
                        dormantPlex.adapter,
                        dormantPlex.serverIdProvider,
                        libraryRepo,
                        syncServices.libraryCollectorScope,
                    )
                createCompositeLibrarySyncTrigger(listOf(dormantPlex to dormantCollector))
                    .triggerSync(deep = false, sectionKey = null)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                guardrailRepo
                    .upsert(
                        GuardrailRuleRecord(
                            GuardrailRuleType.TRANSCODE_4K,
                            GuardrailMode.ENFORCE,
                            null,
                            "Stop 4K transcode",
                            updatedAtMs = 1L,
                        ),
                    ).shouldBeRight()
                userRepo.upsert(UserRecord("u-jf-guard", jfSid, "jfu-1", "jfuser", createdAtMs = 1L, updatedAtMs = 1L)).shouldBeRight()
                registry.start()
                syncServices.streamGuardrailRunner?.start()
                metricsRuntime.service.start()
                delay(150)

                val snapshot =
                    ActiveStreamSnapshot(
                        sessionId = "jf-4k-sess",
                        sessionKey = "jf-4k-key",
                        state = PlaybackState.PLAYING,
                        viewOffsetMs = 1_000L,
                        media = StreamMedia("jm-1", MediaType.MOVIE, "Big 4K Movie", durationMs = 100_000L),
                        user = StreamUser(externalId = "jfu-1", username = "jfuser"),
                        player =
                            StreamPlayer(
                                machineIdentifier = "jf-dev-1",
                                product = "Jellyfin Web",
                                location = StreamLocation.WAN,
                                isLocal = false,
                            ),
                        technical =
                            StreamTechnicalInfo(
                                decision = StreamDecision.TRANSCODE,
                                videoDecision = StreamDecision.TRANSCODE,
                                videoResolution = "4k",
                            ),
                    )
                jfRuntime.sessionEngine.reconcile(listOf(snapshot), ReconcileTrigger.PERIODIC).shouldBeRight()
                withTimeout(5_000) { while (terminateCalls.get() == 0) delay(25) }
                terminateCalls.get() shouldBe 1
                val auditItems = auditRepo.query(TerminationAuditFilter(limit = 10, offset = 0)).shouldBeRight().items
                auditItems.any { it.sessionId == "jf-4k-sess" } shouldBe true

                jfRuntime.sessionEngine.reconcile(emptyList(), ReconcileTrigger.PERIODIC).shouldBeRight()
                delay(50)
                val scrape = metricsRuntime.service.scrape()
                scrape shouldContain "miru_library_items"
                scrape shouldContain "Studio Jellyfin"
                scrape shouldContain "Home Plex"
            } finally {
                cleanupOnStartupFailure(syncServices, registry, handle, metricsRuntime)
                dir.toFile().deleteRecursively()
            }
        }

        test("createCoreServiceWiring mounts full Ktor routes and cleans up on shutdown") {
            val dir = Files.createTempDirectory("miru-core-wiring-")
            val dbPath = dir.resolve("miru.db").toString()
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            val serverRepo = ExposedMediaServerRepository(handle)
            val userRepo = ExposedUserRepository(handle)
            val historyRepo = ExposedPlaybackHistoryRepository(handle)
            val accountRepo = ExposedAuthAccountRepository(handle)
            val sessionRepo = ExposedAuthSessionRepository(handle)
            val authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = "core-wiring-session-secret-32chars!")
            val emailLinker = EmailMediaUserLinker(accountRepo, userRepo, authConfig)
            val authService =
                AuthService(
                    accountRepository = accountRepo,
                    sessionRepository = sessionRepo,
                    apiKeyRepository = ExposedApiKeyRepository(handle),
                    userRepository = userRepo,
                    passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 8192, iterations = 1, parallelism = 1)),
                    authConfig = authConfig,
                    emailLinker = emailLinker,
                )
            authService.createAccount("admin", "AdminPass-123!", role = MiruRole.ADMIN).shouldBeRight()
            val appConfig =
                AppConfig(
                    server = ServerConfig("127.0.0.1", 8080),
                    database = DatabaseConfig.Sqlite(dbPath),
                    plex = PlexConfig("", ""),
                    jellyfin = JellyfinConfig("", ""),
                    session = SessionConfig(),
                    auth = authConfig,
                    metrics = MetricsConfig(enabled = false),
                )
            val wiring =
                createCoreServiceWiring(
                    appConfig = appConfig,
                    appVersion = "test-1.0",
                    databaseHandle = handle,
                    mediaServerRepository = serverRepo,
                    userRepository = userRepo,
                    playbackHistoryRepository = historyRepo,
                    sessionRepository = sessionRepo,
                    emailLinker = emailLinker,
                )

            try {
                val adminCookie = authService.login("admin", "AdminPass-123!").shouldBeRight().signedToken
                testApplication {
                    application {
                        registerLifecycleHandlers(wiring.syncServices, handle, wiring.plexOAuthHttpClient, wiring.webhookBundle.httpClient)
                        miruModule(
                            config = appConfig,
                            appVersion = "test-1.0",
                            realtime = wiring.mediaServerRegistry,
                            authService = authService,
                            artworkProxyService = wiring.artworkProxyService,
                            streamsAndUsersService = wiring.streamsAndUsersService,
                            streamControlService = wiring.streamControlService,
                            webhookService = wiring.webhookBundle.service,
                            sessionEvents = wiring.mediaServerRegistry.sessionEvents,
                            historyAndStatsService =
                                HistoryAndStatsService(
                                    playbackHistoryRepository = historyRepo,
                                    userRepository = userRepo,
                                    mediaServerRepository = serverRepo,
                                ),
                            libraryQueryService = wiring.libraryQueryService,
                            librarySyncTrigger = wiring.syncServices.compositeLibrarySyncTrigger,
                            mediaDetailsService = wiring.mediaDetailsService,
                            tautulliCompatService = wiring.tautulliCompatService,
                            plexOAuthService = wiring.plexOAuthService,
                            mediaServerRegistry = wiring.mediaServerRegistry,
                        )
                    }

                    client.get("/healthz").status shouldBe HttpStatusCode.OK
                    val serversBody = client.get("/api/v1/servers") { cookie(SESSION_COOKIE_NAME, adminCookie) }.bodyAsText()
                    val servers = MiruJson.decodeFromString<MediaServersResponse>(serversBody)
                    servers.servers shouldHaveSize 0
                    wiring.libraryRepository.findAllSections().shouldBeRight() shouldHaveSize 0
                    wiring.plexRuntimeManager.currentBaseUrl().shouldBeNull()
                }
            } finally {
                cleanupOnStartupFailure(
                    wiring.syncServices,
                    wiring.mediaServerRegistry,
                    handle,
                    wiring.plexOAuthHttpClient,
                    wiring.webhookBundle.httpClient,
                )
                dir.toFile().deleteRecursively()
            }
        }
    })
