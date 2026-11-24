package app.hononeko.miru.http

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationResponse
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

private val securityHeadersLogger = LoggerFactory.getLogger("app.hononeko.miru.http.SecurityHeaders")

private const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"
private const val CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
private const val FRAME_OPTIONS_HEADER = "X-Frame-Options"
private const val REFERRER_POLICY_HEADER = "Referrer-Policy"
private const val PERMISSIONS_POLICY_HEADER = "Permissions-Policy"

private const val NOSNIFF_VALUE = "nosniff"
private const val DENY_FRAME_VALUE = "DENY"
private const val REFERRER_POLICY_VALUE = "strict-origin-when-cross-origin"
private const val PERMISSIONS_POLICY_VALUE = "camera=(), microphone=(), geolocation=()"

const val API_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'"

private val SCRIPT_TAG_REGEX =
    Regex("<script(\\s[^>]*)?>([\\s\\S]*?)</script>", setOf(RegexOption.IGNORE_CASE))
private val SCRIPT_SRC_ATTR_REGEX =
    Regex("\\bsrc\\s*=", setOf(RegexOption.IGNORE_CASE))

/**
 * Extracts Base64-encoded SHA-256 CSP hashes (`'sha256-...'`) for all non-`src` inline `<script>`
 * blocks present in an HTML document. Newlines are normalized per HTML parsing rules (`\r\n` / `\r`
 * -> `\n`) without trimming whitespace inside the script element so hashes match browser checks.
 */
fun extractInlineScriptSha256Hashes(html: String): List<String> {
    val digest = MessageDigest.getInstance("SHA-256")
    val encoder = Base64.getEncoder()
    return SCRIPT_TAG_REGEX
        .findAll(html)
        .mapNotNull { match ->
            val attrs = match.groupValues[1]
            if (SCRIPT_SRC_ATTR_REGEX.containsMatchIn(attrs)) {
                return@mapNotNull null
            }
            val normalizedBody =
                match.groupValues[2]
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
            if (normalizedBody.isBlank()) {
                return@mapNotNull null
            }
            val hashBytes = digest.digest(normalizedBody.toByteArray(Charsets.UTF_8))
            "'sha256-${encoder.encodeToString(hashBytes)}'"
        }.distinct()
        .toList()
}

fun buildWebContentSecurityPolicy(scriptHashes: List<String> = emptyList()): String {
    val scriptSrc = (listOf("'self'") + scriptHashes).joinToString(" ")
    return "default-src 'self'; " +
        "script-src $scriptSrc; " +
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
        "font-src 'self' https://fonts.gstatic.com; " +
        "img-src 'self' data: blob:; " +
        "connect-src 'self'; " +
        "manifest-src 'self'; " +
        "worker-src 'self'; " +
        "object-src 'none'; " +
        "base-uri 'self'; " +
        "form-action 'self'; " +
        "frame-ancestors 'none'"
}

fun resolveWebContentSecurityPolicy(staticWebDir: String?): String {
    val trimmed = staticWebDir?.trim()?.takeIf { it.isNotEmpty() } ?: return buildWebContentSecurityPolicy()
    val indexPath = Path.of(trimmed).toAbsolutePath().normalize().resolve("index.html").normalize()
    if (!Files.isRegularFile(indexPath) || !Files.isReadable(indexPath)) {
        return buildWebContentSecurityPolicy()
    }
    return runCatching {
        val html = Files.readString(indexPath, Charsets.UTF_8)
        val hashes = extractInlineScriptSha256Hashes(html)
        buildWebContentSecurityPolicy(hashes)
    }.getOrElse { err ->
        securityHeadersLogger.warn("Failed reading '{}' for CSP script hashes: {}", indexPath, err.message)
        buildWebContentSecurityPolicy()
    }
}

fun Application.installSecurityHeaders(webContentSecurityPolicy: String) {
    val plugin =
        createApplicationPlugin("MiruSecurityHeaders") {
            onCall { call ->
                val path = call.request.path()
                val csp =
                    if (isApiOrSystemPath(path)) {
                        API_CONTENT_SECURITY_POLICY
                    } else {
                        webContentSecurityPolicy
                    }
                call.response.appendHeaderIfAbsent(CONTENT_TYPE_OPTIONS_HEADER, NOSNIFF_VALUE)
                call.response.appendHeaderIfAbsent(FRAME_OPTIONS_HEADER, DENY_FRAME_VALUE)
                call.response.appendHeaderIfAbsent(REFERRER_POLICY_HEADER, REFERRER_POLICY_VALUE)
                call.response.appendHeaderIfAbsent(PERMISSIONS_POLICY_HEADER, PERMISSIONS_POLICY_VALUE)
                call.response.appendHeaderIfAbsent(CONTENT_SECURITY_POLICY_HEADER, csp)
            }
        }
    install(plugin)
}

private fun isApiOrSystemPath(path: String): Boolean =
    path == "/api" ||
        path.startsWith("/api/") ||
        path == "/healthz" ||
        path == "/readyz"

private fun ApplicationResponse.appendHeaderIfAbsent(
    name: String,
    value: String,
) {
    if (!headers.contains(name)) {
        headers.append(name, value)
    }
}
