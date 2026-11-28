package app.hononeko.miru

import app.hononeko.miru.adapter.ManagedMediaServerRuntime
import app.hononeko.miru.adapter.MediaServerRegistry
import app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectClient
import app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectService
import app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager
import app.hononeko.miru.adapter.jellyfin.createJellyfinHttpClient
import app.hononeko.miru.adapter.plex.PlexOAuthClient
import app.hononeko.miru.adapter.plex.PlexOAuthService
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.auth.AccountProfileAutoImporter
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthRateLimiter
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.EmailMediaUserLinker
import app.hononeko.miru.auth.ProfileImportSources
import app.hononeko.miru.auth.SessionCleanupService
import app.hononeko.miru.auth.oidc.OidcAuthService
import app.hononeko.miru.auth.oidc.OidcClient
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AppConfigLoader
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.NamedReadinessCheck
import app.hononeko.miru.health.ReadinessService
import app.hononeko.miru.http.adminImportRoutes
import app.hononeko.miru.http.artworkRoutes
import app.hononeko.miru.http.authRoutes
import app.hononeko.miru.http.createDedicatedMetricsServer
import app.hononeko.miru.http.healthRoutes
import app.hononeko.miru.http.historyStatsRoutes
import app.hononeko.miru.http.installRequestLogging
import app.hononeko.miru.http.installSecurityHeaders
import app.hononeko.miru.http.jellyfinQuickConnectRoutes
import app.hononeko.miru.http.libraryRoutes
import app.hononeko.miru.http.mediaDetailsRoutes
import app.hononeko.miru.http.metricsRoutes
import app.hononeko.miru.http.openApiRoutes
import app.hononeko.miru.http.plexOAuthRoutes
import app.hononeko.miru.http.resolveWebContentSecurityPolicy
import app.hononeko.miru.http.respondError
import app.hononeko.miru.http.serverRoutes
import app.hononeko.miru.http.staticWebRoutes
import app.hononeko.miru.http.streamControlRoutes
import app.hononeko.miru.http.streamsAndUsersRoutes
import app.hononeko.miru.http.tautulliCompatRoutes
import app.hononeko.miru.http.webhookRoutes
import app.hononeko.miru.importer.TautulliCli
import app.hononeko.miru.importer.TautulliImporterService
import app.hononeko.miru.library.LibraryStatsCollector
import app.hononeko.miru.library.LibrarySyncMode
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.metrics.MiruMetricsService
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.DatabaseReadinessCheck
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import app.hononeko.miru.persistence.repository.CachingMediaServerRepository
import app.hononeko.miru.persistence.repository.CachingWebhookTargetRepository
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedGuardrailRuleRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedStreamTerminationAuditRepository
import app.hononeko.miru.persistence.repository.ExposedUserPreferencesRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookDeliveryRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookTargetRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.LibrarySyncTrigger
import app.hononeko.miru.service.MediaDetailsService
import app.hononeko.miru.service.MediaSectionAccessChecker
import app.hononeko.miru.service.MediaServerManagementService
import app.hononeko.miru.service.PlexUserSyncService
import app.hononeko.miru.service.StatsCacheListener
import app.hononeko.miru.service.StatsResultCache
import app.hononeko.miru.service.StreamControlService
import app.hononeko.miru.service.StreamGuardrailRunner
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.service.TautulliCompatService
import app.hononeko.miru.service.WebhookService
import app.hononeko.miru.session.LiveActiveSessionRepository
import app.hononeko.miru.service.invalidatingStats
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.webhook.WebhookDispatcher
import app.hononeko.miru.webhook.WebhookEventEvaluator
import app.hononeko.miru.webhook.WebhookRunner
import app.hononeko.miru.webhook.createWebhookHttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("app.hononeko.miru.Application")
private val REALTIME_SHUTDOWN_TIMEOUT = 5.seconds
private val DEFAULT_SSE_KEEPALIVE_INTERVAL = 15.seconds
private const val COMPRESSION_MIN_SIZE_BYTES = 1024L
private const val METRICS_SERVER_STOP_GRACE_MS = 500L
private const val METRICS_SERVER_STOP_TIMEOUT_MS = 2_000L
private const val MILLIS_PER_SECOND = 1_000L

val MiruJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

fun main(args: Array<String>) {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    if (args.firstOrNull() == "import") {
        exitProcess(TautulliCli.run(args.drop(1)))
    }

    runServer()
}

internal data class CoreServiceWiring(
    val plexRuntimeManager: PlexRuntimeManager,
    val jellyfinRuntimeManager: JellyfinRuntimeManager,
    val mediaServerRegistry: MediaServerRegistry,
    val libraryRepository: ExposedLibraryRepository,
    val plexOAuthHttpClient: LazyCloseableHttpClient,
    val plexOAuthService: PlexOAuthService,
    val jellyfinQuickConnectHttpClient: LazyCloseableHttpClient,
    val jellyfinQuickConnectClient: JellyfinQuickConnectClient,
    val jellyfinQuickConnectService: JellyfinQuickConnectService,
    val artworkProxyService: ArtworkProxyService,
    val streamsAndUsersService: StreamsAndUsersService,
    val streamControlService: StreamControlService,
    val libraryQueryService: LibraryQueryService,
    val webhookBundle: WebhookServicesBundle,
    val mediaDetailsService: MediaDetailsService,
    val tautulliCompatService: TautulliCompatService,
    val syncServices: SyncServices,
)

private fun runServer() {
    val appVersion = resolveAppVersion()
    val appConfig = loadStartupConfig()
    val databaseHandle = initDatabase(appConfig)
    val statsCache = createStatsResultCache(appConfig)
    val mediaServerRepository = CachingMediaServerRepository(ExposedMediaServerRepository(databaseHandle).invalidatingStats(statsCache))
    val userRepository = ExposedUserRepository(databaseHandle).invalidatingStats(statsCache)
    val playbackHistoryRepository = ExposedPlaybackHistoryRepository(databaseHandle).invalidatingStats(statsCache)
    val accountRepository = ExposedAuthAccountRepository(databaseHandle).invalidatingStats(statsCache)
    val sessionRepository = ExposedAuthSessionRepository(databaseHandle)
    val emailLinker = EmailMediaUserLinker(accountRepository, userRepository, appConfig.auth)
    val authService = initAuthService(appConfig, databaseHandle, accountRepository, sessionRepository, userRepository, emailLinker)
    val (oidcClient, oidcAuthService) =
        initOidcServices(appConfig, authService, accountRepository, emailLinker)
    val historyAndStatsService =
        HistoryAndStatsService(
            playbackHistoryRepository = playbackHistoryRepository,
            userRepository = userRepository,
            mediaServerRepository = mediaServerRepository,
            authAccountRepository = accountRepository,
            statsCache = statsCache,
        )
    val tautulliImporterService =
        createTautulliImporterService(mediaServerRepository, userRepository, playbackHistoryRepository, appConfig)
    val wiring =
        createCoreServiceWiring(
            appConfig = appConfig,
            appVersion = appVersion,
            databaseHandle = databaseHandle,
            mediaServerRepository = mediaServerRepository,
            userRepository = userRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            sessionRepository = sessionRepository,
            emailLinker = emailLinker,
            accountRepository = accountRepository,
            onExpiredSessionsCleaned = { nowMs -> authService.evictExpiredSessions(nowMs) },
        )
    attachAccountProfileAutoImporter(authService, accountRepository, userRepository, mediaServerRepository, emailLinker, wiring)
    val readinessChecks = listOf(NamedReadinessCheck("database", DatabaseReadinessCheck(databaseHandle)))
    val mergedLibrarySyncEvents =
        (listOf(wiring.syncServices.libraryStatsCollector) + wiring.syncServices.additionalLibraryCollectors)
            .map { it.syncEvents }
            .merge()
    val metricsRuntime =
        createMetricsRuntime(
            appConfig = appConfig,
            plexRuntimeManager = wiring.plexRuntimeManager,
            libraryRepository = wiring.libraryRepository,
            mediaServerRepository = mediaServerRepository,
            libraryStatsCollector = wiring.syncServices.libraryStatsCollector,
            mediaServerRegistry = wiring.mediaServerRegistry,
            librarySyncEvents = mergedLibrarySyncEvents,
        )
    metricsRuntime?.service?.let { statsCache.listener = it.statsCacheListener() }
    val mediaServerManagementService =
        createMediaServerManagementService(
            appConfig = appConfig,
            wiring = wiring,
            mediaServerRepository = mediaServerRepository,
            metricsRuntime = metricsRuntime,
            authService = authService,
        )

    val server =
        embeddedServer(factory = Netty, port = appConfig.server.port, host = appConfig.server.host) {
            registerLifecycleHandlers(
                wiring.syncServices,
                databaseHandle,
                metricsRuntime,
                wiring.plexOAuthHttpClient,
                wiring.jellyfinQuickConnectHttpClient,
                wiring.webhookBundle.httpClient,
                oidcClient,
            )
            miruModule(
                config = appConfig,
                readinessChecks = readinessChecks,
                appVersion = appVersion,
                realtime = wiring.mediaServerRegistry,
                authService = authService,
                oidcAuthService = oidcAuthService,
                artworkProxyService = wiring.artworkProxyService,
                streamsAndUsersService = wiring.streamsAndUsersService,
                streamControlService = wiring.streamControlService,
                webhookService = wiring.webhookBundle.service,
                sessionEvents = wiring.mediaServerRegistry.sessionEvents,
                sseKeepaliveInterval = DEFAULT_SSE_KEEPALIVE_INTERVAL,
                historyAndStatsService = historyAndStatsService,
                libraryQueryService = wiring.libraryQueryService,
                librarySyncTrigger = wiring.syncServices.compositeLibrarySyncTrigger,
                mediaDetailsService = wiring.mediaDetailsService,
                tautulliImporterService = tautulliImporterService,
                tautulliCompatService = wiring.tautulliCompatService,
                plexOAuthService = wiring.plexOAuthService,
                jellyfinQuickConnectService = wiring.jellyfinQuickConnectService,
                metricsService = metricsRuntime?.service,
                mediaServerRegistry = wiring.mediaServerRegistry,
                mediaServerManagementService = mediaServerManagementService,
                syncServerUsers = { serverId -> wiring.syncServices.syncUsersOf(serverId) },
            )
        }
    try {
        metricsRuntime?.dedicatedServer?.let { dedicatedMetricsServer ->
            logger.info("Starting Prometheus metrics listener on {}:{}", appConfig.metrics.host, appConfig.metrics.port)
            dedicatedMetricsServer.start(wait = false)
        }
        server.start(wait = true)
    } catch (e: Throwable) {
        cleanupOnStartupFailure(
            wiring.syncServices,
            wiring.mediaServerRegistry,
            databaseHandle,
            metricsRuntime,
            wiring.plexOAuthHttpClient,
            wiring.jellyfinQuickConnectHttpClient,
            wiring.webhookBundle.httpClient,
            oidcClient,
        )
        throw e
    }
}

@Suppress("LongParameterList")
internal fun createCoreServiceWiring(
    appConfig: AppConfig,
    appVersion: String,
    databaseHandle: DatabaseHandle,
    mediaServerRepository: MediaServerRepository,
    userRepository: UserRepository,
    playbackHistoryRepository: PlaybackHistoryRepository,
    sessionRepository: ExposedAuthSessionRepository,
    emailLinker: EmailMediaUserLinker,
    accountRepository: AuthAccountRepository? = null,
    onExpiredSessionsCleaned: (Long) -> Unit = {},
): CoreServiceWiring {
    val (plexRuntimeManager, jellyfinRuntimeManager, mediaServerRegistry) =
        createMediaServerRuntimes(appConfig, databaseHandle, mediaServerRepository, userRepository, playbackHistoryRepository)
    val libraryRepository = ExposedLibraryRepository(databaseHandle)
    val sectionAccessChecker =
        MediaSectionAccessChecker(
            libraryRepository = libraryRepository,
            adapter = plexRuntimeManager.adapter,
            adapterForServer = { sid -> mediaServerRegistry.adapterForServer(sid) },
        )
    val plexOAuthHttpClient = LazyCloseableHttpClient { createPlexHttpClient() }
    val plexOAuthService =
        createPlexOAuthService(appConfig, plexOAuthHttpClient, appVersion, mediaServerRepository, plexRuntimeManager)
    val jellyfinQuickConnectHttpClient = LazyCloseableHttpClient { createJellyfinHttpClient() }
    val jellyfinQuickConnectClient = JellyfinQuickConnectClient({ jellyfinQuickConnectHttpClient.client }, appVersion)
    val jellyfinQuickConnectService =
        JellyfinQuickConnectService(
            jellyfinConfig = appConfig.jellyfin,
            encryptionKey = appConfig.plex.encryptionKey,
            quickConnectClient = jellyfinQuickConnectClient,
            mediaServerRepository = mediaServerRepository,
            runtimeManager = jellyfinRuntimeManager,
        )
    val artworkProxyService =
        createArtworkProxyService(
            plexRuntimeManager = plexRuntimeManager,
            userRepository = userRepository,
            libraryRepository = libraryRepository,
            sectionAccessChecker = sectionAccessChecker,
            mediaServerRegistry = mediaServerRegistry,
            authAccountRepository = accountRepository,
        )
    val activeSessionRepository = ExposedActiveSessionRepository(databaseHandle)
    val streamsAndUsersService =
        createStreamsAndUsersService(
            LiveActiveSessionRepository(
                delegate = activeSessionRepository,
                engines = mediaServerRegistry.runtimes.map { it.sessionEngine },
                mediaServerRepository = mediaServerRepository,
            ),
            userRepository,
            playbackHistoryRepository,
            mediaServerRepository,
            mediaServerRegistry,
            accountRepository,
        )
    val streamControlService =
        createStreamControlService(
            plexRuntimeManager,
            activeSessionRepository,
            userRepository,
            databaseHandle,
            appConfig,
            mediaServerRegistry,
        )
    val libraryQueryService = LibraryQueryService(libraryRepository = libraryRepository, mediaServerRepository = mediaServerRepository)
    val webhookBundle =
        createWebhookServices(databaseHandle, mediaServerRegistry, streamControlService, userRepository, appConfig, appVersion)
    val mediaDetailsService =
        createMediaDetailsService(
            plexRuntimeManager,
            playbackHistoryRepository,
            libraryRepository,
            sectionAccessChecker,
            mediaServerRegistry,
            mediaServerRepository,
        )
    val tautulliCompatService =
        TautulliCompatService(
            playbackHistoryRepository = playbackHistoryRepository,
            userRepository = userRepository,
            libraryRepository = libraryRepository,
            serverIdProvider = plexRuntimeManager.serverIdProvider,
        )
    val syncServices =
        createSyncServices(
            plexRuntimeManager = plexRuntimeManager,
            libraryRepository = libraryRepository,
            userRepository = userRepository,
            streamControlService = streamControlService,
            webhookRunner = webhookBundle.runner,
            webhookDispatcher = webhookBundle.dispatcher,
            webhookScope = webhookBundle.scope,
            sessionRepository = sessionRepository,
            mediaServerRegistry = mediaServerRegistry,
            additionalRuntimes = listOf(jellyfinRuntimeManager),
            onExpiredSessionsCleaned = onExpiredSessionsCleaned,
        ) {
            emailLinker.relinkAll().onLeft { error ->
                logger.warn("Linking SSO accounts by email after the user sync failed: {}", error.message)
            }
        }
    return CoreServiceWiring(
        plexRuntimeManager = plexRuntimeManager,
        jellyfinRuntimeManager = jellyfinRuntimeManager,
        mediaServerRegistry = mediaServerRegistry,
        libraryRepository = libraryRepository,
        plexOAuthHttpClient = plexOAuthHttpClient,
        plexOAuthService = plexOAuthService,
        jellyfinQuickConnectHttpClient = jellyfinQuickConnectHttpClient,
        jellyfinQuickConnectClient = jellyfinQuickConnectClient,
        jellyfinQuickConnectService = jellyfinQuickConnectService,
        artworkProxyService = artworkProxyService,
        streamsAndUsersService = streamsAndUsersService,
        streamControlService = streamControlService,
        libraryQueryService = libraryQueryService,
        webhookBundle = webhookBundle,
        mediaDetailsService = mediaDetailsService,
        tautulliCompatService = tautulliCompatService,
        syncServices = syncServices,
    )
}

private fun createMediaServerManagementService(
    appConfig: AppConfig,
    wiring: CoreServiceWiring,
    mediaServerRepository: MediaServerRepository,
    metricsRuntime: MetricsRuntime?,
    authService: AuthService,
): MediaServerManagementService =
    MediaServerManagementService(
        mediaServerRegistry = wiring.mediaServerRegistry,
        mediaServerRepository = mediaServerRepository,
        encryptionKey = appConfig.plex.encryptionKey,
        jellyfinRevokeApiKey = { baseUrl, apiKey, deviceId ->
            wiring.jellyfinQuickConnectClient.deleteApiKey(baseUrl, apiKey, apiKey, deviceId)
        },
        jellyfinDefaultDeviceId = appConfig.jellyfin.clientIdentifier ?: "miru",
        onServerDisconnected = { metricsRuntime?.service?.onServerDisconnected() },
        onServerRemoved = { serverId ->
            metricsRuntime?.service?.onServerRemoved(serverId)
            authService.invalidateAll()
        },
    )

private fun createMediaServerRuntimes(
    appConfig: AppConfig,
    databaseHandle: DatabaseHandle,
    mediaServerRepository: MediaServerRepository,
    userRepository: UserRepository,
    playbackHistoryRepository: PlaybackHistoryRepository,
): Triple<PlexRuntimeManager, JellyfinRuntimeManager, MediaServerRegistry> {
    val plexRuntimeManager =
        PlexRuntimeManager(
            plexConfig = appConfig.plex,
            databaseHandle = databaseHandle,
            sessionConfig = appConfig.session,
            mediaServerRepository = mediaServerRepository,
            userRepository = userRepository,
            playbackHistoryRepository = playbackHistoryRepository,
        )
    val jellyfinRuntimeManager =
        JellyfinRuntimeManager(
            jellyfinConfig = appConfig.jellyfin,
            databaseHandle = databaseHandle,
            sessionConfig = appConfig.session,
            mediaServerRepository = mediaServerRepository,
            encryptionKey = appConfig.plex.encryptionKey,
            userRepository = userRepository,
            playbackHistoryRepository = playbackHistoryRepository,
        )
    val mediaServerRegistry =
        MediaServerRegistry(
            runtimes = listOf(plexRuntimeManager, jellyfinRuntimeManager),
            mediaServerRepository = mediaServerRepository,
            jellyfinConfig = appConfig.jellyfin,
        )
    return Triple(plexRuntimeManager, jellyfinRuntimeManager, mediaServerRegistry)
}

@Suppress("LongParameterList")
private fun createMediaDetailsService(
    plexRuntimeManager: PlexRuntimeManager,
    playbackHistoryRepository: PlaybackHistoryRepository,
    libraryRepository: ExposedLibraryRepository,
    sectionAccessChecker: MediaSectionAccessChecker,
    mediaServerRegistry: MediaServerRegistry,
    mediaServerRepository: MediaServerRepository,
): MediaDetailsService =
    MediaDetailsService(
        mediaServerAdapter = plexRuntimeManager.adapter,
        playbackHistoryRepository = playbackHistoryRepository,
        libraryRepository = libraryRepository,
        sectionAccessChecker = sectionAccessChecker,
        adapterForServer = { sid -> mediaServerRegistry.adapterForServer(sid) },
        allServerAdaptersProvider = { mediaServerRegistry.activeServerAdapters() },
        mediaServerRepository = mediaServerRepository,
    )

private fun createPlexOAuthService(
    appConfig: AppConfig,
    plexOAuthHttpClient: LazyCloseableHttpClient,
    appVersion: String,
    mediaServerRepository: MediaServerRepository,
    plexRuntimeManager: PlexRuntimeManager,
): PlexOAuthService =
    PlexOAuthService(
        plexConfig = appConfig.plex,
        oauthClient = PlexOAuthClient({ plexOAuthHttpClient.client }, appConfig.plex.plexTvBaseUrl, appVersion),
        mediaServerRepository = mediaServerRepository,
        runtimeManager = plexRuntimeManager,
    )

internal data class WebhookServicesBundle(
    val httpClient: LazyCloseableHttpClient,
    val scope: CoroutineScope,
    val dispatcher: WebhookDispatcher,
    val runner: WebhookRunner,
    val service: WebhookService,
)

@Suppress("LongParameterList")
private fun createWebhookServices(
    databaseHandle: DatabaseHandle,
    mediaServerRegistry: MediaServerRegistry,
    streamControlService: StreamControlService,
    userRepository: UserRepository,
    appConfig: AppConfig,
    appVersion: String,
): WebhookServicesBundle {
    val httpClient = LazyCloseableHttpClient { createWebhookHttpClient() }
    val targetRepository = CachingWebhookTargetRepository(ExposedWebhookTargetRepository(databaseHandle))
    val deliveryRepository = ExposedWebhookDeliveryRepository(databaseHandle)
    val evaluator = WebhookEventEvaluator()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("outbound-webhooks"))
    val dispatcher =
        WebhookDispatcher(
            httpClientProvider = { httpClient.client },
            deliveryRepository = deliveryRepository,
            encryptionKey = appConfig.plex.encryptionKey,
            appVersion = appVersion,
            scope = scope,
        )
    val runner =
        WebhookRunner(
            sessionEvents = mediaServerRegistry.sessionEvents,
            currentSessionsProvider = { mediaServerRegistry.currentSessions() },
            terminatedEvents = streamControlService.terminatedEvents,
            reconcileHealthSignals = mediaServerRegistry.reconcileHealth,
            targetRepository = targetRepository,
            userRepository = userRepository,
            serverInfoProvider = {
                mediaServerRegistry.runtimes.firstNotNullOfOrNull { it.currentServerSummary() }
            },
            evaluator = evaluator,
            dispatcher = dispatcher,
            scope = scope,
            serverInfoByIdProvider = { sid -> mediaServerRegistry.serverSummaryById(sid) },
        )
    val service =
        WebhookService(
            targetRepository = targetRepository,
            deliveryRepository = deliveryRepository,
            dispatcher = dispatcher,
            evaluator = evaluator,
            encryptionKey = appConfig.plex.encryptionKey,
            serverInfoProvider = {
                mediaServerRegistry.runtimes.firstNotNullOfOrNull { it.currentServerSummary() }
            },
        )
    return WebhookServicesBundle(
        httpClient = httpClient,
        scope = scope,
        dispatcher = dispatcher,
        runner = runner,
        service = service,
    )
}

@Suppress("LongParameterList")
internal fun createArtworkProxyService(
    plexRuntimeManager: PlexRuntimeManager,
    userRepository: UserRepository,
    libraryRepository: ExposedLibraryRepository,
    sectionAccessChecker: MediaSectionAccessChecker,
    mediaServerRegistry: MediaServerRegistry? = null,
    authAccountRepository: AuthAccountRepository? = null,
): ArtworkProxyService =
    ArtworkProxyService(
        adapter = plexRuntimeManager.adapter,
        userRepository = userRepository,
        plexBaseUrlProvider = { plexRuntimeManager.currentBaseUrl() },
        libraryRepository = libraryRepository,
        sectionAccessChecker = sectionAccessChecker,
        adapterForServer = mediaServerRegistry?.let { reg -> { sid -> reg.adapterForServer(sid) } },
        allServerAdaptersProvider = mediaServerRegistry?.let { reg -> { reg.activeServerAdapters() } },
        serverBaseUrlsProvider = mediaServerRegistry?.let { reg -> { reg.configuredBaseUrls() } },
        authAccountRepository = authAccountRepository,
    )

private fun MiruMetricsService.statsCacheListener(): StatsCacheListener =
    StatsCacheListener { endpoint, hit -> recordStatsCacheLookup(endpoint.label, hit) }

private fun createStatsResultCache(appConfig: AppConfig): StatsResultCache =
    StatsResultCache(
        maxBytes = StatsResultCache.megabytes(appConfig.statsCache.maxMegabytes),
        ttlMs = appConfig.statsCache.ttlSeconds * MILLIS_PER_SECOND,
    )

private fun createTautulliImporterService(
    mediaServerRepository: MediaServerRepository,
    userRepository: UserRepository,
    playbackHistoryRepository: PlaybackHistoryRepository,
    appConfig: AppConfig,
): TautulliImporterService =
    TautulliImporterService(
        mediaServerRepository = mediaServerRepository,
        userRepository = userRepository,
        playbackHistoryRepository = playbackHistoryRepository,
        fallbackBaseUrl = appConfig.plex.baseUrl,
        defaultWatchedThresholdPercent = appConfig.session.watchedThresholdPercent,
    )

@Suppress("LongParameterList")
private fun createStreamsAndUsersService(
    activeSessionRepository: ActiveSessionRepository,
    userRepository: UserRepository,
    playbackHistoryRepository: PlaybackHistoryRepository,
    mediaServerRepository: MediaServerRepository,
    mediaServerRegistry: MediaServerRegistry,
    authAccountRepository: AuthAccountRepository? = null,
): StreamsAndUsersService =
    StreamsAndUsersService(
        activeSessionRepository = activeSessionRepository,
        userRepository = userRepository,
        playbackHistoryRepository = playbackHistoryRepository,
        liveTechnicalProvider = { mediaServerRegistry.currentLiveTechnical() },
        serverNameProvider = { mediaServerRegistry.currentDefaultServerName() },
        mediaServerRepository = mediaServerRepository,
        authAccountRepository = authAccountRepository,
    )

private fun createStreamControlService(
    plexRuntimeManager: PlexRuntimeManager,
    activeSessionRepository: ExposedActiveSessionRepository,
    userRepository: UserRepository,
    databaseHandle: DatabaseHandle,
    appConfig: AppConfig,
    mediaServerRegistry: MediaServerRegistry,
): StreamControlService =
    StreamControlService(
        adapter = plexRuntimeManager.adapter,
        activeSessionRepository = activeSessionRepository,
        userRepository = userRepository,
        guardrailRuleRepository = ExposedGuardrailRuleRepository(databaseHandle),
        auditRepository = ExposedStreamTerminationAuditRepository(databaseHandle),
        guardrailsConfig = appConfig.guardrails,
        adapterForServer = { sid -> mediaServerRegistry.adapterForServer(sid) },
    )

internal data class SyncServices(
    val libraryCollectorScope: CoroutineScope,
    val libraryStatsCollector: LibraryStatsCollector,
    val additionalLibraryCollectors: List<LibraryStatsCollector> = emptyList(),
    val userSyncScope: CoroutineScope,
    val plexUserSyncService: PlexUserSyncService,
    val additionalUserSyncServices: List<PlexUserSyncService> = emptyList(),
    val guardrailScope: CoroutineScope? = null,
    val streamGuardrailRunner: StreamGuardrailRunner? = null,
    val webhookScope: CoroutineScope? = null,
    val webhookDispatcher: WebhookDispatcher? = null,
    val webhookRunner: WebhookRunner? = null,
    val sessionCleanupScope: CoroutineScope? = null,
    val sessionCleanupService: SessionCleanupService? = null,
    val compositeLibrarySyncTrigger: LibrarySyncTrigger = libraryStatsCollector,
) {
    /** Re-syncs the users of the media server [serverId] now, logging (not failing on) upstream errors. */
    suspend fun syncUsersOf(serverId: String) {
        for (userSync in listOf(plexUserSyncService) + additionalUserSyncServices) {
            val served =
                userSync.syncIfServes(serverId).fold(
                    ifLeft = { error ->
                        logger.warn("On-demand user sync failed: {}", error.message)
                        false
                    },
                    ifRight = { it },
                )
            if (served) return
        }
    }
}

@Suppress("LongParameterList")
internal fun createSyncServices(
    plexRuntimeManager: PlexRuntimeManager,
    libraryRepository: app.hononeko.miru.persistence.repository.LibraryRepository,
    userRepository: app.hononeko.miru.persistence.repository.UserRepository,
    streamControlService: StreamControlService? = null,
    webhookRunner: WebhookRunner? = null,
    webhookDispatcher: WebhookDispatcher? = null,
    webhookScope: CoroutineScope? = null,
    sessionRepository: AuthSessionRepository? = null,
    mediaServerRegistry: MediaServerRegistry? = null,
    additionalRuntimes: List<ManagedMediaServerRuntime> = emptyList(),
    onExpiredSessionsCleaned: (Long) -> Unit = {},
    afterUserSync: suspend () -> Unit = {},
): SyncServices {
    val libraryCollectorScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("library-stats-collector"))
    val libraryStatsCollector =
        LibraryStatsCollector(
            adapter = plexRuntimeManager.adapter,
            serverIdProvider = plexRuntimeManager.serverIdProvider,
            libraryRepository = libraryRepository,
            scope = libraryCollectorScope,
        )
    val userSyncScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("media-user-sync"))
    val plexUserSyncService =
        PlexUserSyncService(
            adapter = plexRuntimeManager.adapter,
            serverIdProvider = plexRuntimeManager.serverIdProvider,
            userRepository = userRepository,
            scope = userSyncScope,
            onUsersSynced = afterUserSync,
        )

    val extraCollectors = mutableListOf<Pair<ManagedMediaServerRuntime, LibraryStatsCollector>>()
    val extraUserSyncs = mutableListOf<PlexUserSyncService>()
    for (runtime in additionalRuntimes) {
        val collector =
            LibraryStatsCollector(
                adapter = runtime.adapter,
                serverIdProvider = runtime.serverIdProvider,
                libraryRepository = libraryRepository,
                scope = libraryCollectorScope,
            )
        val userSync =
            PlexUserSyncService(
                adapter = runtime.adapter,
                serverIdProvider = runtime.serverIdProvider,
                userRepository = userRepository,
                scope = userSyncScope,
                onUsersSynced = afterUserSync,
            )
        runtime.setOnReloadLibrarySync {
            collector.syncInitial().onLeft { error ->
                logger.warn("Post-reload library sync ({}) failed: {}", runtime.serverType, error.message)
            }
            userSync.syncOnce().onLeft { error ->
                logger.warn("Post-reload user sync ({}) failed: {}", runtime.serverType, error.message)
            }
        }
        extraCollectors += runtime to collector
        extraUserSyncs += userSync
    }

    val guardrailScope =
        streamControlService?.let {
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("stream-guardrail-runner"))
        }
    val streamGuardrailRunner =
        if (streamControlService != null && guardrailScope != null) {
            if (mediaServerRegistry != null) {
                StreamGuardrailRunner(
                    sessionEvents = mediaServerRegistry.sessionEvents,
                    currentSessionsProvider = { mediaServerRegistry.currentSessions() },
                    streamControlService = streamControlService,
                    scope = guardrailScope,
                )
            } else {
                StreamGuardrailRunner(
                    sessionEngine = plexRuntimeManager.sessionEngine,
                    streamControlService = streamControlService,
                    scope = guardrailScope,
                )
            }
        } else {
            null
        }
    val sessionCleanupScope =
        sessionRepository?.let {
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("auth-session-cleanup"))
        }
    val sessionCleanupService =
        if (sessionRepository != null && sessionCleanupScope != null) {
            SessionCleanupService(
                sessionRepository = sessionRepository,
                scope = sessionCleanupScope,
                onExpiredCleaned = onExpiredSessionsCleaned,
            )
        } else {
            null
        }
    plexRuntimeManager.setOnReloadLibrarySync {
        libraryStatsCollector.syncInitial().onLeft { error ->
            logger.warn("Post-reload library sync failed: {}", error.message)
        }
        plexUserSyncService.syncOnce().onLeft { error ->
            logger.warn("Post-reload user sync failed: {}", error.message)
        }
    }

    val runtimeCollectorPairs: List<Pair<ManagedMediaServerRuntime, LibraryStatsCollector>> =
        listOf(plexRuntimeManager to libraryStatsCollector) + extraCollectors
    val compositeLibrarySyncTrigger =
        createCompositeLibrarySyncTrigger(runtimeCollectorPairs)

    return SyncServices(
        libraryCollectorScope = libraryCollectorScope,
        libraryStatsCollector = libraryStatsCollector,
        additionalLibraryCollectors = extraCollectors.map { it.second },
        userSyncScope = userSyncScope,
        plexUserSyncService = plexUserSyncService,
        additionalUserSyncServices = extraUserSyncs,
        guardrailScope = guardrailScope,
        streamGuardrailRunner = streamGuardrailRunner,
        webhookScope = webhookScope,
        webhookDispatcher = webhookDispatcher,
        webhookRunner = webhookRunner,
        sessionCleanupScope = sessionCleanupScope,
        sessionCleanupService = sessionCleanupService,
        compositeLibrarySyncTrigger = compositeLibrarySyncTrigger,
    )
}

internal data class MetricsRuntime(
    val scope: CoroutineScope,
    val service: MiruMetricsService,
    val dedicatedServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null,
)

@Suppress("LongParameterList")
internal fun createMetricsRuntime(
    appConfig: AppConfig,
    plexRuntimeManager: PlexRuntimeManager,
    libraryRepository: app.hononeko.miru.persistence.repository.LibraryRepository,
    mediaServerRepository: app.hononeko.miru.persistence.repository.MediaServerRepository,
    libraryStatsCollector: LibraryStatsCollector,
    mediaServerRegistry: MediaServerRegistry? = null,
    librarySyncEvents: Flow<LibrarySyncSummary> = libraryStatsCollector.syncEvents,
): MetricsRuntime? {
    if (!appConfig.metrics.enabled) return null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("miru-metrics"))
    val service =
        MiruMetricsService(
            sessionEvents = mediaServerRegistry?.sessionEvents ?: plexRuntimeManager.sessionEngine.sessionEvents,
            activeSessionsProvider = {
                mediaServerRegistry?.currentSessions() ?: plexRuntimeManager.sessionEngine.currentSessions()
            },
            librarySectionsProvider = { libraryRepository.findAllSections() },
            librarySyncEvents = librarySyncEvents,
            websocketConnected = plexRuntimeManager.websocketConnected,
            websocketReconnected = plexRuntimeManager.websocketReconnected,
            serverNameByIdProvider = { serverId ->
                mediaServerRepository.findById(serverId).getOrNull()?.name
            },
            activeWebsocketServerLabelProvider = {
                plexRuntimeManager.currentServerName() ?: mediaServerRegistry?.currentDefaultServerName()
            },
            scope = scope,
        )
    val dedicatedServer =
        if (appConfig.metrics.port != appConfig.server.port) {
            createDedicatedMetricsServer(
                host = appConfig.metrics.host,
                port = appConfig.metrics.port,
                metricsService = service,
            )
        } else {
            null
        }
    return MetricsRuntime(scope = scope, service = service, dedicatedServer = dedicatedServer)
}

internal fun createCompositeLibrarySyncTrigger(
    collectors: List<Pair<ManagedMediaServerRuntime, LibraryStatsCollector>>,
): LibrarySyncTrigger =
    object : LibrarySyncTrigger {
        override suspend fun triggerSync(
            deep: Boolean,
            sectionKey: String?,
        ): arrow.core.Either<MiruError, LibrarySyncSummary> =
            triggerSync(deep = deep, sectionKey = sectionKey, serverId = null)

        override suspend fun triggerSync(
            deep: Boolean,
            sectionKey: String?,
            serverId: String?,
        ): arrow.core.Either<MiruError, LibrarySyncSummary> {
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedServerId != null) {
                val match =
                    collectors.firstOrNull { (runtime, _) ->
                        runtime.isConfigured() &&
                            (runtime.cachedServerIdOrNull() == normalizedServerId ||
                                runtime.serverIdProvider().getOrNull() == normalizedServerId)
                    }
                        ?: return arrow.core.Either.Left(
                            MiruError.NotFound(resource = "Media server", id = normalizedServerId),
                        )
                return match.second.triggerSync(deep = deep, sectionKey = sectionKey)
            }

            val active = collectors.filter { (runtime, _) -> runtime.isConfigured() }
            if (active.isEmpty()) {
                return collectors.first().second.triggerSync(deep = deep, sectionKey = sectionKey)
            }
            val results = active.map { (_, collector) -> collector.triggerSync(deep = deep, sectionKey = sectionKey) }
            if (results.size == 1) return results.first()

            val successes = results.mapNotNull { it.getOrNull() }
            if (successes.isEmpty()) return results.first()
            return arrow.core.Either.Right(
                LibrarySyncSummary(
                    mode = if (deep) LibrarySyncMode.DEEP else LibrarySyncMode.FAST,
                    syncedSections = successes.sumOf { it.syncedSections },
                    failedSections = successes.sumOf { it.failedSections },
                    totalItemsSynced = successes.sumOf { it.totalItemsSynced },
                    snapshotsCreated = successes.sumOf { it.snapshotsCreated },
                    prunedItems = successes.sumOf { it.prunedItems },
                    syncedAtMs = successes.maxOf { it.syncedAtMs },
                ),
            )
        }
    }

internal fun Application.registerLifecycleHandlers(
    syncServices: SyncServices,
    databaseHandle: DatabaseHandle,
    vararg clients: AutoCloseable?,
) {
    registerLifecycleHandlers(
        syncServices = syncServices,
        databaseHandle = databaseHandle,
        metricsRuntime = null,
        clients = clients,
    )
}

internal fun Application.registerLifecycleHandlers(
    syncServices: SyncServices,
    databaseHandle: DatabaseHandle,
    metricsRuntime: MetricsRuntime?,
    vararg clients: AutoCloseable?,
) {
    val allCollectors = listOf(syncServices.libraryStatsCollector) + syncServices.additionalLibraryCollectors
    val allUserSyncs = listOf(syncServices.plexUserSyncService) + syncServices.additionalUserSyncServices
    monitor.subscribe(ApplicationStarted) {
        logger.info("Starting library stats collectors, media user sync, webhooks, and session cleanup")
        allCollectors.forEach { collector -> collector.start() }
        allUserSyncs.forEach { userSync -> userSync.start() }
        syncServices.streamGuardrailRunner?.start()
        syncServices.webhookDispatcher?.start()
        syncServices.webhookRunner?.start()
        syncServices.sessionCleanupService?.start()
        metricsRuntime?.service?.start()
    }
    monitor.subscribe(ApplicationStopping) {
        logger.info("Stopping library stats collectors, media user sync, webhooks, and session cleanup")
        runBlocking {
            allCollectors.forEach { collector ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { collector.stop() }
                    ?: logger.warn("Library stats collector did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
            allUserSyncs.forEach { userSync ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { userSync.stop() }
                    ?: logger.warn("Media user sync did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
            syncServices.streamGuardrailRunner?.let { runner ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { runner.stop() }
                    ?: logger.warn("Stream guardrail runner did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
            syncServices.webhookRunner?.let { runner ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { runner.stop() }
                    ?: logger.warn("Webhook runner did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
            syncServices.webhookDispatcher?.let { dispatcher ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { dispatcher.stop() }
                    ?: logger.warn("Webhook dispatcher did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
            syncServices.sessionCleanupService?.let { cleanup ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { cleanup.stop() }
                    ?: logger.warn("Auth session cleanup service did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
            metricsRuntime?.service?.let { service ->
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { service.stop() }
                    ?: logger.warn("Metrics service did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
        }
        syncServices.libraryCollectorScope.cancel()
        syncServices.userSyncScope.cancel()
        syncServices.guardrailScope?.cancel()
        syncServices.webhookScope?.cancel()
        syncServices.sessionCleanupScope?.cancel()
        metricsRuntime?.scope?.cancel()
        metricsRuntime?.dedicatedServer?.let { metricsServer ->
            runCatching { metricsServer.stop(METRICS_SERVER_STOP_GRACE_MS, METRICS_SERVER_STOP_TIMEOUT_MS) }
                .onFailure { err -> logger.warn("Failed to stop Prometheus metrics listener on shutdown: {}", err.message) }
        }
    }
    monitor.subscribe(ApplicationStopped) {
        logger.info("Closing external HTTP clients and database handle")
        clients.forEach { client ->
            if (client != null) {
                runCatching { client.close() }
                    .onFailure { err -> logger.warn("Failed to close HTTP client on shutdown: {}", err.message) }
            }
        }
        metricsRuntime?.service?.let { service ->
            runCatching { service.close() }
                .onFailure { err -> logger.warn("Failed to close metrics registry on shutdown: {}", err.message) }
        }
        runCatching { databaseHandle.close() }
            .onFailure { err -> logger.warn("Failed to close database handle on shutdown: {}", err.message) }
    }
}

internal fun cleanupOnStartupFailure(
    syncServices: SyncServices,
    plexRuntimeManager: RealtimeLifecycle,
    databaseHandle: DatabaseHandle,
    vararg clients: AutoCloseable?,
) {
    cleanupOnStartupFailure(
        syncServices = syncServices,
        plexRuntimeManager = plexRuntimeManager,
        databaseHandle = databaseHandle,
        metricsRuntime = null,
        clients = clients,
    )
}

internal fun cleanupOnStartupFailure(
    syncServices: SyncServices,
    plexRuntimeManager: RealtimeLifecycle,
    databaseHandle: DatabaseHandle,
    metricsRuntime: MetricsRuntime?,
    vararg clients: AutoCloseable?,
) {
    val allCollectors = listOf(syncServices.libraryStatsCollector) + syncServices.additionalLibraryCollectors
    val allUserSyncs = listOf(syncServices.plexUserSyncService) + syncServices.additionalUserSyncServices
    runBlocking {
        allCollectors.forEach { collector ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { collector.stop() }
        }
        allUserSyncs.forEach { userSync ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { userSync.stop() }
        }
        syncServices.streamGuardrailRunner?.let { runner ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { runner.stop() }
        }
        syncServices.webhookRunner?.let { runner ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { runner.stop() }
        }
        syncServices.webhookDispatcher?.let { dispatcher ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { dispatcher.stop() }
        }
        syncServices.sessionCleanupService?.let { cleanup ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { cleanup.stop() }
        }
        metricsRuntime?.service?.let { service ->
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { service.stop() }
        }
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { plexRuntimeManager.stop() }
    }
    syncServices.libraryCollectorScope.cancel()
    syncServices.userSyncScope.cancel()
    syncServices.guardrailScope?.cancel()
    syncServices.webhookScope?.cancel()
    syncServices.sessionCleanupScope?.cancel()
    metricsRuntime?.scope?.cancel()
    metricsRuntime?.dedicatedServer?.let { metricsServer ->
        runCatching { metricsServer.stop(METRICS_SERVER_STOP_GRACE_MS, METRICS_SERVER_STOP_TIMEOUT_MS) }
            .onFailure { err -> logger.warn("Failed to stop Prometheus metrics listener after failure: {}", err.message) }
    }
    metricsRuntime?.service?.let { service ->
        runCatching { service.close() }
            .onFailure { err -> logger.warn("Failed to close metrics registry after failure: {}", err.message) }
    }
    clients.forEach { client ->
        if (client != null) {
            runCatching { client.close() }
                .onFailure { err -> logger.warn("Failed to close HTTP client after server failure: {}", err.message) }
        }
    }
    runCatching { databaseHandle.close() }
        .onFailure { err -> logger.warn("Failed to close database handle after server failure: {}", err.message) }
}

private fun loadStartupConfig(): AppConfig {
    val hoconConfig = ApplicationConfig("application.conf")
    val appConfig =
        AppConfigLoader
            .load(env = System.getenv(), hocon = hoconConfig)
            .fold(
                ifLeft = { error ->
                    logger.error("Startup configuration failed: {}", error.message)
                    error.fieldErrors.forEach { (field, detail) ->
                        logger.error("  - {}: {}", field, detail)
                    }
                    exitProcess(1)
                },
                ifRight = { it },
            )
    logger.info(
        "Starting Miru on {}:{} (db={}, auth={})",
        appConfig.server.host,
        appConfig.server.port,
        appConfig.database::class.simpleName,
        appConfig.auth.mode,
    )
    return appConfig
}

private fun initDatabase(appConfig: AppConfig): DatabaseHandle =
    DatabaseFactory
        .createAndMigrate(appConfig.database)
        .fold(
            ifLeft = { error ->
                logger.error("Database initialization failed: {}", error.message)
                exitProcess(1)
            },
            ifRight = { it },
        )

/** Gives accounts SSO links by email the profile of their user on the first-connected server. */
private fun attachAccountProfileAutoImporter(
    authService: AuthService,
    accountRepository: AuthAccountRepository,
    userRepository: UserRepository,
    mediaServerRepository: MediaServerRepository,
    emailLinker: EmailMediaUserLinker,
    wiring: CoreServiceWiring,
) {
    AccountProfileAutoImporter(
        authService = authService,
        accountRepository = accountRepository,
        userRepository = userRepository,
        mediaServerRepository = mediaServerRepository,
        profileImport = ProfileImportSources(artworkProxyService = wiring.artworkProxyService),
        scope = wiring.syncServices.userSyncScope,
    ).attachTo(emailLinker)
}

private fun initAuthService(
    appConfig: AppConfig,
    databaseHandle: DatabaseHandle,
    accountRepository: AuthAccountRepository,
    sessionRepository: ExposedAuthSessionRepository,
    userRepository: UserRepository,
    emailLinker: EmailMediaUserLinker,
): AuthService {
    val authService =
        AuthService(
            accountRepository = accountRepository,
            sessionRepository = sessionRepository,
            apiKeyRepository = ExposedApiKeyRepository(databaseHandle),
            userRepository = userRepository,
            passwordHasher = Argon2idPasswordHasher(),
            authConfig = appConfig.auth,
            preferencesRepository = ExposedUserPreferencesRepository(databaseHandle),
            emailLinker = emailLinker,
        )
    runBlocking {
        authService.bootstrapAdminIfConfigured()
    }.onLeft { error ->
        logger.error("Initial admin bootstrap failed: {}", error.message)
        databaseHandle.close()
        exitProcess(1)
    }
    return authService
}

private fun initOidcServices(
    appConfig: AppConfig,
    authService: AuthService,
    accountRepository: AuthAccountRepository,
    emailLinker: EmailMediaUserLinker,
): Pair<OidcClient?, OidcAuthService?> {
    if (!appConfig.auth.oidcEnabled) return null to null
    val oidcClient = OidcClient(config = appConfig.auth.oidc)
    val oidcAuthService =
        OidcAuthService(
            oidcClient = oidcClient,
            authService = authService,
            accountRepository = accountRepository,
            emailLinker = emailLinker,
            authConfig = appConfig.auth,
        )
    return oidcClient to oidcAuthService
}

@Suppress("LongParameterList")
fun Application.miruModule(
    config: AppConfig,
    readinessChecks: List<NamedReadinessCheck> = emptyList(),
    appVersion: String = resolveAppVersion(),
    realtime: RealtimeLifecycle? = null,
    authService: AuthService? = null,
    oidcAuthService: OidcAuthService? = null,
    artworkProxyService: ArtworkProxyService? = null,
    streamsAndUsersService: StreamsAndUsersService? = null,
    streamControlService: StreamControlService? = null,
    webhookService: WebhookService? = null,
    sessionEvents: SharedFlow<SessionEvent>? = null,
    sseKeepaliveInterval: Duration = DEFAULT_SSE_KEEPALIVE_INTERVAL,
    historyAndStatsService: HistoryAndStatsService? = null,
    libraryQueryService: LibraryQueryService? = null,
    librarySyncTrigger: LibrarySyncTrigger? = null,
    mediaDetailsService: MediaDetailsService? = null,
    tautulliImporterService: TautulliImporterService? = null,
    tautulliCompatService: TautulliCompatService? = null,
    plexOAuthService: PlexOAuthService? = null,
    jellyfinQuickConnectService: JellyfinQuickConnectService? = null,
    authRateLimiter: AuthRateLimiter = AuthRateLimiter(config.auth.rateLimit),
    metricsService: MiruMetricsService? = null,
    mediaServerRegistry: MediaServerRegistry? = null,
    mediaServerManagementService: MediaServerManagementService? = null,
    syncServerUsers: (suspend (serverId: String) -> Unit)? = null,
) {
    if (realtime != null) {
        monitor.subscribe(ApplicationStarted) {
            logger.info("Starting real-time media server pipeline")
            realtime.start()
        }
        monitor.subscribe(ApplicationStopping) {
            logger.info("Stopping real-time media server pipeline")
            runBlocking {
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { realtime.stop() }
                    ?: logger.warn("Real-time pipeline did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            }
        }
    }

    install(ContentNegotiation) {
        json(MiruJson)
    }

    installResponseCompression()

    install(SSE)

    installRequestLogging()

    installSecurityHeaders(resolveWebContentSecurityPolicy(config.server.staticWebDir))
    installConfiguredCors(config.server.corsAllowedOrigins)
    installDefaultStatusPages()

    val readinessService = ReadinessService(readinessChecks)

    routing {
        healthRoutes(readinessService = readinessService, appVersion = appVersion)
        openApiRoutes()
        if (authService != null) {
            registerAuthenticatedRoutes(
                config = config,
                appVersion = appVersion,
                authService = authService,
                oidcAuthService = oidcAuthService,
                artworkProxyService = artworkProxyService,
                streamsAndUsersService = streamsAndUsersService,
                streamControlService = streamControlService,
                webhookService = webhookService,
                sessionEvents = sessionEvents,
                sseKeepaliveInterval = sseKeepaliveInterval,
                historyAndStatsService = historyAndStatsService,
                libraryQueryService = libraryQueryService,
                librarySyncTrigger = librarySyncTrigger,
                mediaDetailsService = mediaDetailsService,
                tautulliImporterService = tautulliImporterService,
                tautulliCompatService = tautulliCompatService,
                plexOAuthService = plexOAuthService,
                jellyfinQuickConnectService = jellyfinQuickConnectService,
                authRateLimiter = authRateLimiter,
                mediaServerRegistry = mediaServerRegistry,
                mediaServerManagementService = mediaServerManagementService,
                syncServerUsers = syncServerUsers,
            )
        }
        if (metricsService != null && config.metrics.enabled && config.metrics.port == config.server.port) {
            metricsRoutes(metricsService)
        }
        staticWebRoutes(config.server.staticWebDir)
    }
}

@Suppress("LongParameterList")
private fun io.ktor.server.routing.Routing.registerAuthenticatedRoutes(
    config: AppConfig,
    appVersion: String,
    authService: AuthService,
    oidcAuthService: OidcAuthService?,
    artworkProxyService: ArtworkProxyService?,
    streamsAndUsersService: StreamsAndUsersService?,
    streamControlService: StreamControlService?,
    webhookService: WebhookService?,
    sessionEvents: SharedFlow<SessionEvent>?,
    sseKeepaliveInterval: Duration,
    historyAndStatsService: HistoryAndStatsService?,
    libraryQueryService: LibraryQueryService?,
    librarySyncTrigger: LibrarySyncTrigger?,
    mediaDetailsService: MediaDetailsService?,
    tautulliImporterService: TautulliImporterService?,
    tautulliCompatService: TautulliCompatService?,
    plexOAuthService: PlexOAuthService?,
    jellyfinQuickConnectService: JellyfinQuickConnectService?,
    authRateLimiter: AuthRateLimiter,
    mediaServerRegistry: MediaServerRegistry?,
    mediaServerManagementService: MediaServerManagementService?,
    syncServerUsers: (suspend (serverId: String) -> Unit)?,
) {
    authRoutes(
        authService = authService,
        authMode = config.auth.mode,
        cookieSecureMode = config.auth.cookieSecure,
        authRateLimiter = authRateLimiter,
        trustProxyHeaders = config.server.trustProxyHeaders,
        oidcAuthService = oidcAuthService,
        profileImport = ProfileImportSources(artworkProxyService = artworkProxyService, syncServerUsers = syncServerUsers ?: {}),
    )
    if (plexOAuthService != null) {
        plexOAuthRoutes(
            plexOAuthService = plexOAuthService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (jellyfinQuickConnectService != null) {
        jellyfinQuickConnectRoutes(
            jellyfinQuickConnectService = jellyfinQuickConnectService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (mediaServerRegistry != null) {
        serverRoutes(
            mediaServerRegistry = mediaServerRegistry,
            authService = authService,
            authMode = config.auth.mode,
            mediaServerManagementService = mediaServerManagementService,
        )
    }
    if (artworkProxyService != null) {
        artworkRoutes(
            artworkProxyService = artworkProxyService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (mediaDetailsService != null) {
        mediaDetailsRoutes(
            mediaDetailsService = mediaDetailsService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (streamsAndUsersService != null) {
        streamsAndUsersRoutes(
            authService = authService,
            authMode = config.auth.mode,
            streamsAndUsersService = streamsAndUsersService,
            sessionEvents = sessionEvents,
            keepaliveInterval = sseKeepaliveInterval,
        )
    }
    if (streamControlService != null) {
        streamControlRoutes(
            streamControlService = streamControlService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (webhookService != null) {
        webhookRoutes(
            webhookService = webhookService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (historyAndStatsService != null) {
        historyStatsRoutes(
            historyAndStatsService = historyAndStatsService,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (libraryQueryService != null) {
        libraryRoutes(
            libraryQueryService = libraryQueryService,
            librarySyncTrigger = librarySyncTrigger,
            authService = authService,
            authMode = config.auth.mode,
        )
    }
    if (tautulliImporterService != null) {
        adminImportRoutes(
            tautulliImporterService = tautulliImporterService,
            authService = authService,
            authMode = config.auth.mode,
            watchedThresholdPercent = config.session.watchedThresholdPercent,
        )
    }
    if (tautulliCompatService != null && config.tautulliCompat.enabled) {
        tautulliCompatRoutes(
            tautulliCompatService = tautulliCompatService,
            authService = authService,
            authMode = config.auth.mode,
            watchedThresholdPercent = config.session.watchedThresholdPercent,
            appVersion = appVersion,
        )
    }
}

private fun Application.installResponseCompression() {
    install(Compression) {
        gzip {
            priority = 1.0
            minimumSize(COMPRESSION_MIN_SIZE_BYTES)
        }
        deflate {
            priority = 0.9
            minimumSize(COMPRESSION_MIN_SIZE_BYTES)
        }
        excludeContentType(
            ContentType.Text.EventStream,
            ContentType.Image.Any,
        )
        condition {
            request.path() != "/api/v1/streams/events" &&
                !response.headers.contains(HttpHeaders.ContentEncoding)
        }
    }
}

private fun Application.installConfiguredCors(allowedOrigins: List<String>) {
    if (allowedOrigins.isEmpty()) return
    install(CORS) {
        allowedOrigins.forEach { origin ->
            val sanitized = origin.removePrefix("https://").removePrefix("http://")
            val schemes =
                when {
                    origin.startsWith("https://") -> listOf("https")
                    origin.startsWith("http://") -> listOf("http")
                    else -> listOf("http", "https")
                }
            allowHost(sanitized, schemes = schemes)
        }
        allowCredentials = true
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Api-Key")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }
}

private fun Application.installDefaultStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondError(
                MiruError.ValidationError(
                    message = cause.message ?: "Malformed request payload",
                ),
            )
        }
        exception<Throwable> { call, cause ->
            call.respondError(
                MiruError.InternalError(
                    message = cause.message ?: "Unhandled server exception",
                    cause = cause,
                ),
            )
        }
    }
}

internal class LazyCloseableHttpClient(
    private val factory: () -> io.ktor.client.HttpClient,
) : AutoCloseable {
    private val lock = Any()

    @Volatile
    private var instance: io.ktor.client.HttpClient? = null

    val isInitialized: Boolean
        get() = instance != null

    val client: io.ktor.client.HttpClient
        get() =
            instance ?: synchronized(lock) {
                instance ?: factory().also { instance = it }
            }

    override fun close() {
        val current = synchronized(lock) { instance.also { instance = null } }
        current?.close()
    }
}

internal fun resolveAppVersion(): String =
    resolveAppVersion(
        envVersion = System.getenv("MIRU_VERSION"),
        manifestVersion = object {}::class.java.`package`?.implementationVersion,
    )

internal fun resolveAppVersion(
    envVersion: String?,
    manifestVersion: String?,
): String =
    envVersion?.trim()?.takeIf { it.isNotEmpty() }
        ?: manifestVersion?.trim()?.takeIf { it.isNotEmpty() }
        ?: "dev"
