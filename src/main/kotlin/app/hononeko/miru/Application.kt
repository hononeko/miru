package app.hononeko.miru

import app.hononeko.miru.adapter.plex.PlexOAuthClient
import app.hononeko.miru.adapter.plex.PlexOAuthService
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthRateLimiter
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.EmailMediaUserLinker
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
import app.hononeko.miru.http.healthRoutes
import app.hononeko.miru.http.historyStatsRoutes
import app.hononeko.miru.http.installRequestLogging
import app.hononeko.miru.http.installSecurityHeaders
import app.hononeko.miru.http.libraryRoutes
import app.hononeko.miru.http.mediaDetailsRoutes
import app.hononeko.miru.http.openApiRoutes
import app.hononeko.miru.http.plexOAuthRoutes
import app.hononeko.miru.http.resolveWebContentSecurityPolicy
import app.hononeko.miru.http.respondError
import app.hononeko.miru.http.staticWebRoutes
import app.hononeko.miru.http.streamControlRoutes
import app.hononeko.miru.http.streamsAndUsersRoutes
import app.hononeko.miru.http.webhookRoutes
import app.hononeko.miru.importer.TautulliCli
import app.hononeko.miru.importer.TautulliImporterService
import app.hononeko.miru.library.LibraryStatsCollector
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.DatabaseReadinessCheck
import app.hononeko.miru.persistence.repository.AuthSessionRepository
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
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.LibrarySyncTrigger
import app.hononeko.miru.service.MediaDetailsService
import app.hononeko.miru.service.MediaSectionAccessChecker
import app.hononeko.miru.service.PlexUserSyncService
import app.hononeko.miru.service.StreamControlService
import app.hononeko.miru.service.StreamGuardrailRunner
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.service.WebhookService
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
import kotlinx.coroutines.flow.SharedFlow
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

private fun runServer() {
    val appVersion = resolveAppVersion()
    val appConfig = loadStartupConfig()
    val databaseHandle = initDatabase(appConfig)
    val mediaServerRepository = ExposedMediaServerRepository(databaseHandle)
    val userRepository = ExposedUserRepository(databaseHandle)
    val playbackHistoryRepository = ExposedPlaybackHistoryRepository(databaseHandle)
    val accountRepository = ExposedAuthAccountRepository(databaseHandle)
    val sessionRepository = ExposedAuthSessionRepository(databaseHandle)
    val emailLinker = EmailMediaUserLinker(accountRepository, userRepository, appConfig.auth)
    val authService = initAuthService(appConfig, databaseHandle, accountRepository, sessionRepository, userRepository, emailLinker)
    val (oidcClient, oidcAuthService) =
        initOidcServices(appConfig, authService, accountRepository, emailLinker)
    val historyAndStatsService =
        HistoryAndStatsService(playbackHistoryRepository = playbackHistoryRepository, userRepository = userRepository)
    val tautulliImporterService =
        createTautulliImporterService(mediaServerRepository, userRepository, playbackHistoryRepository, appConfig)

    val plexRuntimeManager =
        PlexRuntimeManager(
            plexConfig = appConfig.plex,
            databaseHandle = databaseHandle,
            sessionConfig = appConfig.session,
            mediaServerRepository = mediaServerRepository,
        )
    val libraryRepository = ExposedLibraryRepository(databaseHandle)
    val sectionAccessChecker = MediaSectionAccessChecker(libraryRepository = libraryRepository, adapter = plexRuntimeManager.adapter)
    val plexOAuthHttpClient = LazyCloseableHttpClient { createPlexHttpClient() }
    val plexOAuthService = createPlexOAuthService(appConfig, plexOAuthHttpClient, appVersion, mediaServerRepository, plexRuntimeManager)
    val artworkProxyService = createArtworkProxyService(plexRuntimeManager, userRepository, libraryRepository, sectionAccessChecker)
    val activeSessionRepository = ExposedActiveSessionRepository(databaseHandle)
    val streamsAndUsersService =
        createStreamsAndUsersService(activeSessionRepository, userRepository, playbackHistoryRepository, plexRuntimeManager)
    val streamControlService =
        createStreamControlService(plexRuntimeManager, activeSessionRepository, userRepository, databaseHandle, appConfig)
    val libraryQueryService = LibraryQueryService(libraryRepository = libraryRepository)
    val webhookBundle =
        createWebhookServices(databaseHandle, plexRuntimeManager, streamControlService, userRepository, appConfig, appVersion)
    val mediaDetailsService =
        MediaDetailsService(plexRuntimeManager.adapter, playbackHistoryRepository, libraryRepository, sectionAccessChecker)
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
        ) {
            emailLinker.relinkAll().onLeft { error ->
                logger.warn("Linking SSO accounts by email after the user sync failed: {}", error.message)
            }
        }
    val readinessChecks = listOf(NamedReadinessCheck("database", DatabaseReadinessCheck(databaseHandle)))

    val server =
        embeddedServer(factory = Netty, port = appConfig.server.port, host = appConfig.server.host) {
            registerLifecycleHandlers(syncServices, databaseHandle, plexOAuthHttpClient, webhookBundle.httpClient, oidcClient)
            miruModule(
                config = appConfig,
                readinessChecks = readinessChecks,
                appVersion = appVersion,
                realtime = plexRuntimeManager,
                authService = authService,
                oidcAuthService = oidcAuthService,
                artworkProxyService = artworkProxyService,
                streamsAndUsersService = streamsAndUsersService,
                streamControlService = streamControlService,
                webhookService = webhookBundle.service,
                sessionEvents = plexRuntimeManager.sessionEngine.sessionEvents,
                historyAndStatsService = historyAndStatsService,
                libraryQueryService = libraryQueryService,
                librarySyncTrigger = syncServices.libraryStatsCollector,
                mediaDetailsService = mediaDetailsService,
                tautulliImporterService = tautulliImporterService,
                plexOAuthService = plexOAuthService,
            )
        }
    try {
        server.start(wait = true)
    } catch (e: Throwable) {
        cleanupOnStartupFailure(syncServices, plexRuntimeManager, databaseHandle, plexOAuthHttpClient, webhookBundle.httpClient, oidcClient)
        throw e
    }
}

private fun createPlexOAuthService(
    appConfig: AppConfig,
    plexOAuthHttpClient: LazyCloseableHttpClient,
    appVersion: String,
    mediaServerRepository: ExposedMediaServerRepository,
    plexRuntimeManager: PlexRuntimeManager,
): PlexOAuthService =
    PlexOAuthService(
        plexConfig = appConfig.plex,
        oauthClient = PlexOAuthClient({ plexOAuthHttpClient.client }, appConfig.plex.plexTvBaseUrl, appVersion),
        mediaServerRepository = mediaServerRepository,
        runtimeManager = plexRuntimeManager,
    )

private data class WebhookServicesBundle(
    val httpClient: LazyCloseableHttpClient,
    val scope: CoroutineScope,
    val dispatcher: WebhookDispatcher,
    val runner: WebhookRunner,
    val service: WebhookService,
)

@Suppress("LongParameterList")
private fun createWebhookServices(
    databaseHandle: DatabaseHandle,
    plexRuntimeManager: PlexRuntimeManager,
    streamControlService: StreamControlService,
    userRepository: ExposedUserRepository,
    appConfig: AppConfig,
    appVersion: String,
): WebhookServicesBundle {
    val httpClient = LazyCloseableHttpClient { createWebhookHttpClient() }
    val targetRepository = ExposedWebhookTargetRepository(databaseHandle)
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
            sessionEngine = plexRuntimeManager.sessionEngine,
            terminatedEvents = streamControlService.terminatedEvents,
            reconcileHealthSignals = plexRuntimeManager.reconcileHealth,
            targetRepository = targetRepository,
            userRepository = userRepository,
            serverInfoProvider = { plexRuntimeManager.currentServerSummary() },
            evaluator = evaluator,
            dispatcher = dispatcher,
            scope = scope,
        )
    val service =
        WebhookService(
            targetRepository = targetRepository,
            deliveryRepository = deliveryRepository,
            dispatcher = dispatcher,
            evaluator = evaluator,
            encryptionKey = appConfig.plex.encryptionKey,
            serverInfoProvider = { plexRuntimeManager.currentServerSummary() },
        )
    return WebhookServicesBundle(
        httpClient = httpClient,
        scope = scope,
        dispatcher = dispatcher,
        runner = runner,
        service = service,
    )
}

internal fun createArtworkProxyService(
    plexRuntimeManager: PlexRuntimeManager,
    userRepository: ExposedUserRepository,
    libraryRepository: ExposedLibraryRepository,
    sectionAccessChecker: MediaSectionAccessChecker,
): ArtworkProxyService =
    ArtworkProxyService(
        adapter = plexRuntimeManager.adapter,
        userRepository = userRepository,
        plexBaseUrlProvider = { plexRuntimeManager.currentBaseUrl() },
        libraryRepository = libraryRepository,
        sectionAccessChecker = sectionAccessChecker,
    )

private fun createTautulliImporterService(
    mediaServerRepository: ExposedMediaServerRepository,
    userRepository: ExposedUserRepository,
    playbackHistoryRepository: ExposedPlaybackHistoryRepository,
    appConfig: AppConfig,
): TautulliImporterService =
    TautulliImporterService(
        mediaServerRepository = mediaServerRepository,
        userRepository = userRepository,
        playbackHistoryRepository = playbackHistoryRepository,
        fallbackBaseUrl = appConfig.plex.baseUrl,
        defaultWatchedThresholdPercent = appConfig.session.watchedThresholdPercent,
    )

private fun createStreamsAndUsersService(
    activeSessionRepository: ExposedActiveSessionRepository,
    userRepository: ExposedUserRepository,
    playbackHistoryRepository: ExposedPlaybackHistoryRepository,
    plexRuntimeManager: PlexRuntimeManager,
): StreamsAndUsersService =
    StreamsAndUsersService(
        activeSessionRepository = activeSessionRepository,
        userRepository = userRepository,
        playbackHistoryRepository = playbackHistoryRepository,
        liveTechnicalProvider = { plexRuntimeManager.sessionEngine.currentLiveTechnical() },
        serverNameProvider = { plexRuntimeManager.currentServerName() },
    )

private fun createStreamControlService(
    plexRuntimeManager: PlexRuntimeManager,
    activeSessionRepository: ExposedActiveSessionRepository,
    userRepository: ExposedUserRepository,
    databaseHandle: DatabaseHandle,
    appConfig: AppConfig,
): StreamControlService =
    StreamControlService(
        adapter = plexRuntimeManager.adapter,
        activeSessionRepository = activeSessionRepository,
        userRepository = userRepository,
        guardrailRuleRepository = ExposedGuardrailRuleRepository(databaseHandle),
        auditRepository = ExposedStreamTerminationAuditRepository(databaseHandle),
        guardrailsConfig = appConfig.guardrails,
    )

internal data class SyncServices(
    val libraryCollectorScope: CoroutineScope,
    val libraryStatsCollector: LibraryStatsCollector,
    val userSyncScope: CoroutineScope,
    val plexUserSyncService: PlexUserSyncService,
    val guardrailScope: CoroutineScope? = null,
    val streamGuardrailRunner: StreamGuardrailRunner? = null,
    val webhookScope: CoroutineScope? = null,
    val webhookDispatcher: WebhookDispatcher? = null,
    val webhookRunner: WebhookRunner? = null,
    val sessionCleanupScope: CoroutineScope? = null,
    val sessionCleanupService: SessionCleanupService? = null,
)

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
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plex-user-sync"))
    val plexUserSyncService =
        PlexUserSyncService(
            adapter = plexRuntimeManager.adapter,
            serverIdProvider = plexRuntimeManager.serverIdProvider,
            userRepository = userRepository,
            scope = userSyncScope,
            onUsersSynced = afterUserSync,
        )
    val guardrailScope =
        streamControlService?.let {
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("stream-guardrail-runner"))
        }
    val streamGuardrailRunner =
        if (streamControlService != null && guardrailScope != null) {
            StreamGuardrailRunner(
                sessionEngine = plexRuntimeManager.sessionEngine,
                streamControlService = streamControlService,
                scope = guardrailScope,
            )
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
            )
        } else {
            null
        }
    plexRuntimeManager.setOnReloadLibrarySync {
        libraryStatsCollector.syncFastCounts()
        plexUserSyncService.syncOnce().onLeft { error ->
            logger.warn("Post-reload user sync failed: {}", error.message)
        }
    }
    return SyncServices(
        libraryCollectorScope = libraryCollectorScope,
        libraryStatsCollector = libraryStatsCollector,
        userSyncScope = userSyncScope,
        plexUserSyncService = plexUserSyncService,
        guardrailScope = guardrailScope,
        streamGuardrailRunner = streamGuardrailRunner,
        webhookScope = webhookScope,
        webhookDispatcher = webhookDispatcher,
        webhookRunner = webhookRunner,
        sessionCleanupScope = sessionCleanupScope,
        sessionCleanupService = sessionCleanupService,
    )
}

internal fun Application.registerLifecycleHandlers(
    syncServices: SyncServices,
    databaseHandle: DatabaseHandle,
    vararg clients: AutoCloseable?,
) {
    monitor.subscribe(ApplicationStarted) {
        logger.info("Starting library stats collector, Plex user sync, webhooks, and session cleanup")
        syncServices.libraryStatsCollector.start()
        syncServices.plexUserSyncService.start()
        syncServices.streamGuardrailRunner?.start()
        syncServices.webhookDispatcher?.start()
        syncServices.webhookRunner?.start()
        syncServices.sessionCleanupService?.start()
    }
    monitor.subscribe(ApplicationStopping) {
        logger.info("Stopping library stats collector, Plex user sync, webhooks, and session cleanup")
        runBlocking {
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { syncServices.libraryStatsCollector.stop() }
                ?: logger.warn("Library stats collector did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { syncServices.plexUserSyncService.stop() }
                ?: logger.warn("Plex user sync did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
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
        }
        syncServices.libraryCollectorScope.cancel()
        syncServices.userSyncScope.cancel()
        syncServices.guardrailScope?.cancel()
        syncServices.webhookScope?.cancel()
        syncServices.sessionCleanupScope?.cancel()
    }
    monitor.subscribe(ApplicationStopped) {
        logger.info("Closing external HTTP clients and database handle")
        clients.forEach { client ->
            if (client != null) {
                runCatching { client.close() }
                    .onFailure { err -> logger.warn("Failed to close HTTP client on shutdown: {}", err.message) }
            }
        }
        runCatching { databaseHandle.close() }
            .onFailure { err -> logger.warn("Failed to close database handle on shutdown: {}", err.message) }
    }
}

internal fun cleanupOnStartupFailure(
    syncServices: SyncServices,
    plexRuntimeManager: PlexRuntimeManager,
    databaseHandle: DatabaseHandle,
    vararg clients: AutoCloseable?,
) {
    runBlocking {
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { syncServices.libraryStatsCollector.stop() }
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { syncServices.plexUserSyncService.stop() }
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
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { plexRuntimeManager.stop() }
    }
    syncServices.libraryCollectorScope.cancel()
    syncServices.userSyncScope.cancel()
    syncServices.guardrailScope?.cancel()
    syncServices.webhookScope?.cancel()
    syncServices.sessionCleanupScope?.cancel()
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

private fun initAuthService(
    appConfig: AppConfig,
    databaseHandle: DatabaseHandle,
    accountRepository: ExposedAuthAccountRepository,
    sessionRepository: ExposedAuthSessionRepository,
    userRepository: ExposedUserRepository,
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
    accountRepository: ExposedAuthAccountRepository,
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
    plexOAuthService: PlexOAuthService? = null,
    authRateLimiter: AuthRateLimiter = AuthRateLimiter(config.auth.rateLimit),
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
            authRoutes(
                authService = authService,
                authMode = config.auth.mode,
                cookieSecureMode = config.auth.cookieSecure,
                authRateLimiter = authRateLimiter,
                trustProxyHeaders = config.server.trustProxyHeaders,
                oidcAuthService = oidcAuthService,
            )
            if (plexOAuthService != null) {
                plexOAuthRoutes(
                    plexOAuthService = plexOAuthService,
                    authService = authService,
                    authMode = config.auth.mode,
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
        }
        staticWebRoutes(config.server.staticWebDir)
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
