package app.hononeko.miru.http

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.HealthResponse
import app.hononeko.miru.health.ReadinessService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val yamlContentType = ContentType("application", "yaml")

fun Route.healthRoutes(
    readinessService: ReadinessService,
    appVersion: String,
) {
    get("/healthz") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ok",
                service = "miru",
                version = appVersion,
            ),
        )
    }

    get("/readyz") {
        val (ready, response) = readinessService.evaluate()
        val status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, response)
    }
}

fun Route.openApiRoutes(specResourcePath: String = "openapi/openapi.yaml") {
    get("/api/v1/openapi.yaml") {
        val spec =
            Thread.currentThread()
                .contextClassLoader
                .getResourceAsStream(specResourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }

        if (spec == null) {
            call.respondError(MiruError.NotFound(resource = "OpenAPI specification"))
            return@get
        }

        call.respondText(
            text = spec,
            contentType = yamlContentType,
            status = HttpStatusCode.OK,
        )
    }
}
