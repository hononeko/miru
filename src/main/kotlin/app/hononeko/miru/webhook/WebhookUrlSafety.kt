package app.hononeko.miru.webhook

import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.artwork.SystemDnsResolver
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MAX_URL_LENGTH = 2048
private const val MAX_PORT = 65_535
private const val MAX_HEADER_NAME_LENGTH = 128
private const val MAX_ERROR_LENGTH = 500
private const val ASCII_SPACE = 0x20
private const val ASCII_DEL = 0x7F
private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"
private val HEADER_NAME_REGEX = Regex("^[A-Za-z0-9!#$%&'*+\\-.^_`|~]+$")
private val BLOCKED_METADATA_HOSTS =
    setOf(
        "metadata.google.internal",
        "metadata",
        "metadata.goog",
        "instance-data",
    )
private val FORBIDDEN_AUTH_HEADER_NAMES =
    setOf(
        "host",
        "content-length",
        "content-type",
        "transfer-encoding",
        "connection",
        "upgrade",
        "user-agent",
        "x-miru-event",
        "x-miru-delivery",
        "x-miru-signature-256",
    )

/**
 * URL validation, SSRF IP filtering, secret/query redaction, and HMAC-SHA256 payload signing for outbound webhooks.
 *
 * Blocks cloud metadata / link-local (`169.254.0.0/16`, `fe80::/10`), unspecified (`0.0.0.0/8`, `::`), and
 * multicast/reserved ranges while intentionally permitting RFC 1918 LAN and Kubernetes cluster-internal hosts.
 */
object WebhookUrlSafety {
    fun validateUrl(raw: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = raw.trim()
            ensure(trimmed.isNotEmpty() && trimmed.length <= MAX_URL_LENGTH) {
                urlError("must be between 1 and $MAX_URL_LENGTH characters")
            }
            ensure(trimmed.none { it.code <= ASCII_SPACE || it.code == ASCII_DEL }) {
                urlError("must not contain whitespace or control characters")
            }

            val uri =
                try {
                    URI(trimmed)
                } catch (_: Exception) {
                    raise(urlError("must be a valid http:// or https:// URL"))
                }

            val scheme = uri.scheme?.lowercase()
            ensure(scheme == "http" || scheme == "https") {
                urlError("scheme must be http or https")
            }
            ensure(uri.rawUserInfo == null && uri.rawAuthority?.contains('@') != true) {
                urlError("must not include embedded credentials (userinfo)")
            }
            val host = uri.host?.trim()?.takeIf { it.isNotEmpty() }
            ensure(host != null) {
                urlError("must include a non-blank host")
            }
            ensure(uri.port == -1 || uri.port in 1..MAX_PORT) {
                urlError("port must be between 1 and $MAX_PORT")
            }

            val normalizedHost = host.lowercase().trimEnd('.')
            ensure(normalizedHost !in BLOCKED_METADATA_HOSTS) {
                urlError("host '$host' is blocked for SSRF protection")
            }

            parseLiteralIpAddress(host)?.let { literalIp ->
                ensure(isAllowedWebhookAddress(literalIp)) {
                    urlError("target IP address is in a blocked link-local, metadata, multicast, or unspecified range")
                }
            }

            trimmed
        }

    fun resolveAndValidateHost(
        host: String,
        dnsResolver: DnsResolver = SystemDnsResolver,
    ): Either<MiruError, List<InetAddress>> =
        either {
            val lookupHost =
                if (host.startsWith('[') && host.endsWith(']')) {
                    host.substring(1, host.length - 1)
                } else {
                    host
                }
            val normalizedHost = lookupHost.lowercase().trimEnd('.')
            ensure(normalizedHost !in BLOCKED_METADATA_HOSTS) {
                MiruError.ValidationError(
                    message = "Webhook target host '$host' is blocked for SSRF protection",
                    fieldErrors = mapOf("url" to "host is blocked for SSRF protection"),
                )
            }

            val resolved =
                try {
                    dnsResolver.resolveAll(lookupHost)
                } catch (e: UnknownHostException) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = "webhook",
                            message = "Failed to resolve webhook host '$host'",
                            cause = e,
                        ),
                    )
                } catch (e: SecurityException) {
                    raise(
                        MiruError.ValidationError(
                            message = "DNS resolution blocked for webhook host '$host'",
                            fieldErrors = mapOf("url" to "DNS resolution blocked"),
                        ),
                    )
                }

            ensure(resolved.isNotEmpty()) {
                MiruError.UpstreamUnavailable(
                    provider = "webhook",
                    message = "Webhook host '$host' resolved to no addresses",
                )
            }

            ensure(resolved.all(::isAllowedWebhookAddress)) {
                MiruError.ValidationError(
                    message = "Webhook target host '$host' resolved to a disallowed link-local, metadata, multicast, or unspecified IP",
                    fieldErrors = mapOf("url" to "resolved to a blocked IP range"),
                )
            }

            resolved
        }

    fun isAllowedWebhookAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
            return false
        }
        return when (address) {
            is Inet4Address -> isAllowedIpv4(address.address)
            is Inet6Address -> isAllowedIpv6(address.address)
            else -> false
        }
    }

    fun validateAuthHeaderName(raw: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = raw.trim()
            ensure(trimmed.isNotEmpty() && trimmed.length <= MAX_HEADER_NAME_LENGTH && HEADER_NAME_REGEX.matches(trimmed)) {
                MiruError.ValidationError(
                    message = "Invalid auth header name",
                    fieldErrors = mapOf("authHeaderName" to "must be a valid HTTP header name (1..$MAX_HEADER_NAME_LENGTH chars)"),
                )
            }
            ensure(trimmed.lowercase() !in FORBIDDEN_AUTH_HEADER_NAMES) {
                MiruError.ValidationError(
                    message = "Auth header name '$trimmed' is reserved",
                    fieldErrors = mapOf("authHeaderName" to "header name '$trimmed' cannot be overridden"),
                )
            }
            trimmed
        }

    fun redactUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
                return redactFallbackUrl(trimmed)
            }
            val sb = StringBuilder()
            sb.append(scheme).append("://")
            if (!uri.rawUserInfo.isNullOrEmpty()) {
                sb.append("***@")
            }
            sb.append(host)
            if (uri.port != -1) {
                sb.append(':').append(uri.port)
            }
            val path = uri.rawPath.orEmpty()
            sb.append(path)
            if (!uri.rawQuery.isNullOrEmpty()) {
                sb.append("?***")
            }
            sb.toString()
        } catch (_: Exception) {
            redactFallbackUrl(trimmed)
        }
    }

    fun signPayload(
        secret: String,
        bodyBytes: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256_ALGORITHM))
        val digest = mac.doFinal(bodyBytes)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return "sha256=" + String(hex)
    }

    fun sanitizeErrorMessage(
        raw: String?,
        secretsToRedact: List<String> = emptyList(),
    ): String {
        var cleaned = raw.orEmpty().trim()
        if (cleaned.isEmpty()) return "Unknown webhook delivery error"
        for (secret in secretsToRedact) {
            if (secret.isNotBlank()) {
                cleaned = cleaned.replace(secret, "***")
            }
        }
        cleaned = cleaned.replace(Regex("""https?://[^\s"'<>]+""")) { match -> redactUrl(match.value) }
        cleaned = cleaned.replace(Regex("""[\r\n\t]+"""), " ")
        cleaned = cleaned.filterNot { it.code < ASCII_SPACE || it.code == ASCII_DEL }.trim()
        if (cleaned.isEmpty()) return "Webhook delivery failed"
        return if (cleaned.length <= MAX_ERROR_LENGTH) cleaned else cleaned.take(MAX_ERROR_LENGTH - 1) + "…"
    }

    private fun isAllowedIpv4(raw: ByteArray): Boolean {
        if (raw.size != 4) return false
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        val disallowed =
            when {
                b0 == 0 -> true // 0.0.0.0/8 unspecified
                b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 link-local / cloud metadata IMDS
                b0 >= 224 -> true // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved / broadcast
                else -> false
            }
        return !disallowed
    }

    private fun isAllowedIpv6(raw: ByteArray): Boolean {
        if (raw.size != 16) return false
        val b = IntArray(16) { idx -> raw[idx].toInt() and 0xFF }
        val isUnspecified = isAllZero(b, 0, 16)
        val isMulticast = b[0] == 0xFF
        // Link-local fe80::/10
        val isLinkLocal = b[0] == 0xFE && (b[1] and 0xC0) == 0x80
        if (isUnspecified || isMulticast || isLinkLocal) return false

        val embeddedIpv4 = extractEmbeddedIpv4(raw, b)
        return embeddedIpv4 == null || isAllowedIpv4(embeddedIpv4)
    }

    private fun extractEmbeddedIpv4(
        raw: ByteArray,
        b: IntArray,
    ): ByteArray? =
        when {
            // IPv4-mapped ::ffff:0:0/96
            isAllZero(b, 0, 10) && b[10] == 0xFF && b[11] == 0xFF -> raw.copyOfRange(12, 16)
            // IPv4-compatible ::/96 (excluding :: and ::1)
            isAllZero(b, 0, 12) && !(b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] <= 1) -> raw.copyOfRange(12, 16)
            // Well-known NAT64 64:ff9b::/96
            b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && isAllZero(b, 4, 12) -> raw.copyOfRange(12, 16)
            // 6to4 2002::/16
            b[0] == 0x20 && b[1] == 0x02 -> raw.copyOfRange(2, 6)
            else -> null
        }

    private fun isAllZero(
        b: IntArray,
        fromInclusive: Int,
        toExclusive: Int,
    ): Boolean {
        for (i in fromInclusive until toExclusive) {
            if (b[i] != 0) return false
        }
        return true
    }

    private fun parseLiteralIpAddress(host: String): InetAddress? {
        val unbracketed =
            if (host.startsWith('[') && host.endsWith(']')) {
                host.substring(1, host.length - 1)
            } else {
                host
            }
        val looksLikeIpv6 = unbracketed.contains(':')
        val looksLikeIpv4 = unbracketed.all { it.isDigit() || it == '.' } && unbracketed.contains('.')
        if (!looksLikeIpv6 && !looksLikeIpv4) return null
        return runCatching { InetAddress.getByName(unbracketed) }.getOrNull()
    }

    private fun redactFallbackUrl(raw: String): String {
        val noFragment = raw.substringBefore('#')
        val queryIdx = noFragment.indexOf('?')
        val beforeQuery = if (queryIdx >= 0) noFragment.substring(0, queryIdx) else noFragment
        val redactedAuthority = beforeQuery.replace(Regex("""(https?://)([^/@]+)@"""), "$1***@")
        return if (queryIdx >= 0) "$redactedAuthority?***" else redactedAuthority
    }

    private fun urlError(detail: String): MiruError.ValidationError =
        MiruError.ValidationError(
            message = "Invalid webhook URL: $detail",
            fieldErrors = mapOf("url" to detail),
        )
}
