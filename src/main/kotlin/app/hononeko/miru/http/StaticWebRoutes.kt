package app.hononeko.miru.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val staticWebLogger = LoggerFactory.getLogger("app.hononeko.miru.http.StaticWebRoutes")
private val RESERVED_PREFIXES = setOf("api", "healthz", "readyz")
private val NO_CACHE_FILES = setOf("index.html", "service-worker.js", "manifest.webmanifest")

private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
private const val DEFAULT_ASSET_CACHE_CONTROL = "public, max-age=3600"
private const val NO_CACHE_CONTROL = "no-cache"

fun Route.staticWebRoutes(staticWebDir: String?) {
    val trimmed = staticWebDir?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val root = Path.of(trimmed).toAbsolutePath().normalize()
    val indexFile = root.resolve("index.html").normalize()

    if (!Files.isDirectory(root) || !Files.isRegularFile(indexFile)) {
        staticWebLogger.info("Static web directory '{}' missing or has no index.html; SPA serving disabled", root)
        return
    }

    staticWebLogger.info("Serving static web PWA from '{}'", root)

    get("/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
        val firstSegment = segments.firstOrNull()?.lowercase()
        if (firstSegment in RESERVED_PREFIXES) {
            return@get
        }

        if (segments.any { it == ".." || it.contains('/') || it.contains('\\') }) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val relativePath = segments.joinToString("/")
        if (relativePath.isNotEmpty()) {
            val candidate = root.resolve(relativePath).normalize()
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
                call.response.header(HttpHeaders.CacheControl, cacheControlFor(relativePath))
                call.respondFile(candidate.toFile())
                return@get
            }
            if (segments.last().contains('.')) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
        }

        call.response.header(HttpHeaders.CacheControl, NO_CACHE_CONTROL)
        call.respondFile(indexFile.toFile())
    }
}

private fun cacheControlFor(relativePath: String): String =
    when {
        relativePath.startsWith("_app/immutable/") -> IMMUTABLE_CACHE_CONTROL
        relativePath in NO_CACHE_FILES -> NO_CACHE_CONTROL
        else -> DEFAULT_ASSET_CACHE_CONTROL
    }
