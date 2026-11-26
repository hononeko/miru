package app.hononeko.miru.http

import app.hononeko.miru.metrics.MiruMetricsService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private val PROMETHEUS_CONTENT_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")

fun Route.metricsRoutes(metricsService: MiruMetricsService) {
    get("/metrics") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            text = metricsService.scrape(),
            contentType = PROMETHEUS_CONTENT_TYPE,
            status = HttpStatusCode.OK,
        )
    }
}

fun Application.metricsModule(metricsService: MiruMetricsService) {
    installSecurityHeaders(API_CONTENT_SECURITY_POLICY)
    routing {
        metricsRoutes(metricsService)
    }
}

fun createDedicatedMetricsServer(
    host: String,
    port: Int,
    metricsService: MiruMetricsService,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(factory = Netty, port = port, host = host) {
        metricsModule(metricsService)
    }
