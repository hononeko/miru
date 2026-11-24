package app.hononeko.miru.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFile
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
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

internal data class CompressedVariant(
    val encoding: String,
    val path: Path,
)

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
                call.respondStaticFile(root = root, originalFile = candidate, cacheControl = cacheControlFor(relativePath))
                return@get
            }
            if (segments.last().contains('.')) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
        }

        call.respondStaticFile(root = root, originalFile = indexFile, cacheControl = NO_CACHE_CONTROL)
    }
}

private suspend fun ApplicationCall.respondStaticFile(
    root: Path,
    originalFile: Path,
    cacheControl: String,
) {
    response.header(HttpHeaders.CacheControl, cacheControl)
    val contentType = ContentType.defaultForFile(originalFile.toFile())
    val fileName = originalFile.fileName.toString()

    if (!fileName.endsWith(".br", ignoreCase = true) && !fileName.endsWith(".gz", ignoreCase = true)) {
        val brVariant = resolveSiblingIfSafe(root, originalFile, ".br")
        val gzVariant = resolveSiblingIfSafe(root, originalFile, ".gz")
        if (brVariant != null || gzVariant != null) {
            response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
            val chosen = negotiatePrecompressedVariant(request.headers[HttpHeaders.AcceptEncoding], brVariant, gzVariant)
            if (chosen != null) {
                response.header(HttpHeaders.ContentEncoding, chosen.encoding)
                respond(LocalFileContent(chosen.path.toFile(), contentType = contentType))
                return
            }
        }
    }

    respond(LocalFileContent(originalFile.toFile(), contentType = contentType))
}

private fun resolveSiblingIfSafe(
    root: Path,
    originalFile: Path,
    suffix: String,
): Path? {
    val sibling = originalFile.resolveSibling("${originalFile.fileName}$suffix").normalize()
    return sibling.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
}

internal fun negotiatePrecompressedVariant(
    acceptEncodingHeader: String?,
    brPath: Path?,
    gzPath: Path?,
): CompressedVariant? {
    if (acceptEncodingHeader.isNullOrBlank() || (brPath == null && gzPath == null)) {
        return null
    }
    val orderedEncodings = parseAcceptedEncodings(acceptEncodingHeader)
    for (encoding in orderedEncodings) {
        when (encoding) {
            "br" -> if (brPath != null) return CompressedVariant(encoding = "br", path = brPath)
            "gzip" -> if (gzPath != null) return CompressedVariant(encoding = "gzip", path = gzPath)
        }
    }
    return null
}

private fun parseAcceptedEncodings(header: String): List<String> {
    var brQ = 0.0
    var gzQ = 0.0
    for (rawPart in header.split(',')) {
        val tokens = rawPart.split(';')
        val coding = tokens.firstOrNull()?.trim()?.lowercase() ?: continue
        if (coding != "br" && coding != "gzip") continue

        val qValue = parseQualityValue(tokens.drop(1))
        if (coding == "br" && qValue > brQ) {
            brQ = qValue
        } else if (coding == "gzip" && qValue > gzQ) {
            gzQ = qValue
        }
    }
    val candidates = mutableListOf<Pair<String, Double>>()
    if (brQ > 0.0) candidates += "br" to brQ
    if (gzQ > 0.0) candidates += "gzip" to gzQ
    return candidates
        .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { if (it.first == "br") 0 else 1 })
        .map { it.first }
}

private fun parseQualityValue(params: List<String>): Double {
    for (param in params) {
        val parts = param.split('=', limit = 2)
        if (parts.size == 2 && parts[0].trim().equals("q", ignoreCase = true)) {
            val parsed = parts[1].trim().toDoubleOrNull() ?: return 0.0
            return if (parsed in 0.0..1.0) parsed else 0.0
        }
    }
    return 1.0
}

private fun cacheControlFor(relativePath: String): String =
    when {
        relativePath.startsWith("_app/immutable/") -> IMMUTABLE_CACHE_CONTROL
        relativePath in NO_CACHE_FILES -> NO_CACHE_CONTROL
        else -> DEFAULT_ASSET_CACHE_CONTROL
    }
