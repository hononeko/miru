package app.hononeko.miru

import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.adapter.plex.buildPlexRuntime
import app.hononeko.miru.adapter.plex.createLazyServerIdProvider
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.createAvatarHttpClient
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
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
import app.hononeko.miru.http.libraryRoutes
import app.hononeko.miru.http.openApiRoutes
import app.hononeko.miru.http.respondError
import app.hononeko.miru.http.staticWebRoutes
import app.hononeko.miru.http.streamsAndUsersRoutes
import app.hononeko.miru.importer.TautulliCli
import app.hononeko.miru.importer.TautulliImporterService
import app.hononeko.miru.library.LibraryStatsCollector
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.DatabaseReadinessCheck
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.LibrarySyncTrigger
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.SessionEvent
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
import io.ktor.server.plugins.calllogging.CallLogging
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
    val appConfig = loadStartupConfig()
    val databaseHandle = initDatabase(appConfig)
    val mediaServerRepository = ExposedMediaServerRepository(databaseHandle)
    val userRepository = ExposedUserRepository(databaseHandle)
    val playbackHistoryRepository = ExposedPlaybackHistoryRepository(databaseHandle)
    val accountRepository = ExposedAuthAccountRepository(databaseHandle)
    val authService = initAuthService(appConfig, databaseHandle, accountRepository, userRepository)
    val (oidcClient, oidcAuthService) =
        initOidcServices(appConfig, authService, accountRepository, userRepository)
    val historyAndStatsService =
        HistoryAndStatsService(
            playbackHistoryRepository = playbackHistoryRepository,
            userRepository = userRepository,
        )
    val tautulliImporterService =
        TautulliImporterService(
            mediaServerRepository = mediaServerRepository,
            userRepository = userRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            fallbackBaseUrl = appConfig.plex.baseUrl,
            defaultWatchedThresholdPercent = appConfig.session.watchedThresholdPercent,
        )

    val plexRuntime = initPlexRuntime(appConfig, databaseHandle, oidcClient)

    val externalAvatarClient = createAvatarClientOrCleanup(plexRuntime, databaseHandle, oidcClient)

    val artworkProxyService =
        ArtworkProxyService(
            adapter = plexRuntime.adapter,
            userRepository = userRepository,
            externalAvatarClient = externalAvatarClient,
            plexBaseUrl = appConfig.plex.baseUrl,
        )
    val streamsAndUsersService =
        StreamsAndUsersService(
            activeSessionRepository = ExposedActiveSessionRepository(databaseHandle),
            userRepository = userRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            liveTechnicalProvider = {
                plexRuntime.sessionEngine?.currentLiveTechnical().orEmpty()
            },
        )
    val libraryRepository = ExposedLibraryRepository(databaseHandle)
    val libraryQueryService = LibraryQueryService(libraryRepository = libraryRepository)
    val libraryCollectorScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("library-stats-collector"))
    val libraryStatsCollector =
        createLibraryStatsCollector(
            appConfig = appConfig,
            databaseHandle = databaseHandle,
            plexRuntime = plexRuntime,
            libraryRepository = libraryRepository,
            scope = libraryCollectorScope,
        )
    val readinessChecks = listOf(NamedReadinessCheck("database", DatabaseReadinessCheck(databaseHandle)))

    val server =
        embeddedServer(
            factory = Netty,
            port = appConfig.server.port,
            host = appConfig.server.host,
        ) {
            monitor.subscribe(ApplicationStarted) {
                logger.info("Starting library stats collector")
                libraryStatsCollector.start()
            }
            monitor.subscribe(ApplicationStopping) {
                logger.info("Stopping library stats collector")
                runBlocking {
                    withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { libraryStatsCollector.stop() }
                        ?: logger.warn("Library stats collector did not stop within {}", REALTIME_SHUTDOWN_TIMEOUT)
                }
                libraryCollectorScope.cancel()
            }
            monitor.subscribe(ApplicationStopped) {
                logger.info("Closing external avatar HTTP client, OIDC HTTP client, and database handle")
                runCatching { externalAvatarClient.close() }
                    .onFailure { err -> logger.warn("Failed to close external avatar HTTP client on shutdown: {}", err.message) }
                runCatching { oidcClient?.close() }
                    .onFailure { err -> logger.warn("Failed to close OIDC HTTP client on shutdown: {}", err.message) }
                runCatching { databaseHandle.close() }
                    .onFailure { err -> logger.warn("Failed to close database handle on shutdown: {}", err.message) }
            }
            miruModule(
                config = appConfig,
                readinessChecks = readinessChecks,
                realtime = plexRuntime.realtime,
                authService = authService,
                oidcAuthService = oidcAuthService,
                artworkProxyService = artworkProxyService,
                streamsAndUsersService = streamsAndUsersService,
                sessionEvents = plexRuntime.sessionEngine?.sessionEvents,
                historyAndStatsService = historyAndStatsService,
                libraryQueryService = libraryQueryService,
                librarySyncTrigger = libraryStatsCollector,
                tautulliImporterService = tautulliImporterService,
            )
        }
    try {
        server.start(wait = true)
    } catch (e: Throwable) {
        cleanupAfterServerFailure(
            libraryStatsCollector = libraryStatsCollector,
            plexRuntime = plexRuntime,
            libraryCollectorScope = libraryCollectorScope,
            externalAvatarClient = externalAvatarClient,
            oidcClient = oidcClient,
            databaseHandle = databaseHandle,
        )
        throw e
    }
}

private fun initPlexRuntime(
    appConfig: AppConfig,
    databaseHandle: DatabaseHandle,
    oidcClient: OidcClient?,
): app.hononeko.miru.adapter.plex.PlexRuntime =
    try {
        buildPlexRuntime(
            config = appConfig.plex,
            databaseHandle = databaseHandle,
            sessionConfig = appConfig.session,
        )
    } catch (e: Throwable) {
        runCatching { oidcClient?.close() }
            .onFailure { err -> logger.warn("Failed to close OIDC HTTP client after startup failure: {}", err.message) }
        runCatching { databaseHandle.close() }
            .onFailure { err -> logger.warn("Failed to close database handle after startup failure: {}", err.message) }
        throw e
    }

@Suppress("LongParameterList")
private fun cleanupAfterServerFailure(
    libraryStatsCollector: LibraryStatsCollector,
    plexRuntime: app.hononeko.miru.adapter.plex.PlexRuntime,
    libraryCollectorScope: CoroutineScope,
    externalAvatarClient: io.ktor.client.HttpClient,
    oidcClient: OidcClient?,
    databaseHandle: DatabaseHandle,
) {
    runBlocking {
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { libraryStatsCollector.stop() }
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { plexRuntime.realtime.stop() }
    }
    libraryCollectorScope.cancel()
    runCatching { externalAvatarClient.close() }
        .onFailure { err -> logger.warn("Failed to close external avatar HTTP client after server failure: {}", err.message) }
    runCatching { oidcClient?.close() }
        .onFailure { err -> logger.warn("Failed to close OIDC HTTP client after server failure: {}", err.message) }
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
    userRepository: ExposedUserRepository,
): AuthService {
    val authService =
        AuthService(
            accountRepository = accountRepository,
            sessionRepository = ExposedAuthSessionRepository(databaseHandle),
            apiKeyRepository = ExposedApiKeyRepository(databaseHandle),
            userRepository = userRepository,
            passwordHasher = Argon2idPasswordHasher(),
            authConfig = appConfig.auth,
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
    userRepository: ExposedUserRepository,
): Pair<OidcClient?, OidcAuthService?> {
    if (!appConfig.auth.oidcEnabled) return null to null
    val oidcClient = OidcClient(config = appConfig.auth.oidc)
    val oidcAuthService =
        OidcAuthService(
            oidcClient = oidcClient,
            authService = authService,
            accountRepository = accountRepository,
            userRepository = userRepository,
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
    sessionEvents: SharedFlow<SessionEvent>? = null,
    sseKeepaliveInterval: Duration = DEFAULT_SSE_KEEPALIVE_INTERVAL,
    historyAndStatsService: HistoryAndStatsService? = null,
    libraryQueryService: LibraryQueryService? = null,
    librarySyncTrigger: LibrarySyncTrigger? = null,
    tautulliImporterService: TautulliImporterService? = null,
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

    install(SSE)

    install(CallLogging) {
        disableDefaultColors()
        filter { call -> call.request.path() != "/healthz" }
    }

    if (config.server.corsAllowedOrigins.isNotEmpty()) {
        install(CORS) {
            config.server.corsAllowedOrigins.forEach { origin ->
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

    val readinessService = ReadinessService(readinessChecks)

    routing {
        healthRoutes(readinessService = readinessService, appVersion = appVersion)
        openApiRoutes()
        if (authService != null) {
            authRoutes(
                authService = authService,
                authMode = config.auth.mode,
                oidcAuthService = oidcAuthService,
            )
            if (artworkProxyService != null) {
                artworkRoutes(
                    artworkProxyService = artworkProxyService,
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

private fun createAvatarClientOrCleanup(
    plexRuntime: app.hononeko.miru.adapter.plex.PlexRuntime,
    databaseHandle: app.hononeko.miru.persistence.DatabaseHandle,
    oidcClient: OidcClient?,
) = try {
    createAvatarHttpClient()
} catch (e: Throwable) {
    runBlocking {
        withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { plexRuntime.realtime.stop() }
    }
    runCatching { oidcClient?.close() }
        .onFailure { err -> logger.warn("Failed to close OIDC HTTP client after startup failure: {}", err.message) }
    runCatching { databaseHandle.close() }
        .onFailure { err -> logger.warn("Failed to close database handle after startup failure: {}", err.message) }
    throw e
}

private fun createLibraryStatsCollector(
    appConfig: AppConfig,
    databaseHandle: app.hononeko.miru.persistence.DatabaseHandle,
    plexRuntime: app.hononeko.miru.adapter.plex.PlexRuntime,
    libraryRepository: ExposedLibraryRepository,
    scope: CoroutineScope,
): LibraryStatsCollector {
    val serverIdProvider =
        plexRuntime.serverIdProvider
            ?: createLazyServerIdProvider(
                adapter = plexRuntime.adapter,
                baseUrl = appConfig.plex.baseUrl,
                mediaServerRepository = ExposedMediaServerRepository(databaseHandle),
            )
    return LibraryStatsCollector(
        adapter = plexRuntime.adapter,
        serverIdProvider = serverIdProvider,
        libraryRepository = libraryRepository,
        scope = scope,
    )
}

private fun resolveAppVersion(): String =
    object {}::class.java.`package`?.implementationVersion ?: "0.1.0-dev"
