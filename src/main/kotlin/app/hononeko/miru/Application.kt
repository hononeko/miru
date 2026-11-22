package app.hononeko.miru

import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.adapter.plex.buildPlexRuntime
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.createAvatarHttpClient
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AppConfigLoader
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.NamedReadinessCheck
import app.hononeko.miru.health.ReadinessService
import app.hononeko.miru.http.artworkRoutes
import app.hononeko.miru.http.authRoutes
import app.hononeko.miru.http.healthRoutes
import app.hononeko.miru.http.openApiRoutes
import app.hononeko.miru.http.respondError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseReadinessCheck
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("app.hononeko.miru.Application")
private val REALTIME_SHUTDOWN_TIMEOUT = 5.seconds

val MiruJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

fun main() {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

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

    val databaseHandle =
        DatabaseFactory
            .createAndMigrate(appConfig.database)
            .fold(
                ifLeft = { error ->
                    logger.error("Database initialization failed: {}", error.message)
                    exitProcess(1)
                },
                ifRight = { it },
            )

    val userRepository = ExposedUserRepository(databaseHandle)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(databaseHandle),
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

    val plexRuntime =
        try {
            buildPlexRuntime(
                config = appConfig.plex,
                databaseHandle = databaseHandle,
                sessionConfig = appConfig.session,
            )
        } catch (e: Throwable) {
            runCatching { databaseHandle.close() }
                .onFailure { err -> logger.warn("Failed to close database handle after startup failure: {}", err.message) }
            throw e
        }

    val externalAvatarClient =
        try {
            createAvatarHttpClient()
        } catch (e: Throwable) {
            runBlocking {
                withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { plexRuntime.realtime.stop() }
            }
            runCatching { databaseHandle.close() }
                .onFailure { err -> logger.warn("Failed to close database handle after startup failure: {}", err.message) }
            throw e
        }

    val artworkProxyService =
        ArtworkProxyService(
            adapter = plexRuntime.adapter,
            userRepository = userRepository,
            externalAvatarClient = externalAvatarClient,
            plexBaseUrl = appConfig.plex.baseUrl,
        )

    val readinessChecks = listOf(NamedReadinessCheck("database", DatabaseReadinessCheck(databaseHandle)))

    val server =
        embeddedServer(
            factory = Netty,
            port = appConfig.server.port,
            host = appConfig.server.host,
        ) {
            monitor.subscribe(ApplicationStopped) {
                logger.info("Closing external avatar HTTP client and database handle")
                runCatching { externalAvatarClient.close() }
                    .onFailure { err -> logger.warn("Failed to close external avatar HTTP client on shutdown: {}", err.message) }
                runCatching { databaseHandle.close() }
                    .onFailure { err -> logger.warn("Failed to close database handle on shutdown: {}", err.message) }
            }
            miruModule(
                config = appConfig,
                readinessChecks = readinessChecks,
                realtime = plexRuntime.realtime,
                authService = authService,
                artworkProxyService = artworkProxyService,
            )
        }
    try {
        server.start(wait = true)
    } catch (e: Throwable) {
        runBlocking {
            withTimeoutOrNull(REALTIME_SHUTDOWN_TIMEOUT) { plexRuntime.realtime.stop() }
        }
        runCatching { externalAvatarClient.close() }
            .onFailure { err -> logger.warn("Failed to close external avatar HTTP client after server failure: {}", err.message) }
        runCatching { databaseHandle.close() }
            .onFailure { err -> logger.warn("Failed to close database handle after server failure: {}", err.message) }
        throw e
    }
}

fun Application.miruModule(
    config: AppConfig,
    readinessChecks: List<NamedReadinessCheck> = emptyList(),
    appVersion: String = resolveAppVersion(),
    realtime: RealtimeLifecycle? = null,
    authService: AuthService? = null,
    artworkProxyService: ArtworkProxyService? = null,
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
            authRoutes(authService = authService, authMode = config.auth.mode)
            if (artworkProxyService != null) {
                artworkRoutes(
                    artworkProxyService = artworkProxyService,
                    authService = authService,
                    authMode = config.auth.mode,
                )
            }
        }
    }
}

private fun resolveAppVersion(): String =
    object {}::class.java.`package`?.implementationVersion ?: "0.1.0-dev"
