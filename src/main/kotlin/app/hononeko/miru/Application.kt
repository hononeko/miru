package app.hononeko.miru

import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.adapter.plex.buildPlexRuntime
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AppConfigLoader
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.NamedReadinessCheck
import app.hononeko.miru.health.ReadinessService
import app.hononeko.miru.http.healthRoutes
import app.hononeko.miru.http.openApiRoutes
import app.hononeko.miru.http.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
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

    val plexRuntime = buildPlexRuntime(appConfig.plex)

    embeddedServer(
        factory = Netty,
        port = appConfig.server.port,
        host = appConfig.server.host,
    ) {
        miruModule(config = appConfig, realtime = plexRuntime.realtime)
    }.start(wait = true)
}

fun Application.miruModule(
    config: AppConfig,
    readinessChecks: List<NamedReadinessCheck> = emptyList(),
    appVersion: String = resolveAppVersion(),
    realtime: RealtimeLifecycle? = null,
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
    }
}

private fun resolveAppVersion(): String =
    object {}::class.java.`package`?.implementationVersion ?: "0.1.0-dev"
