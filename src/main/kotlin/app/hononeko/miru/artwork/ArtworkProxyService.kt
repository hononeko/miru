package app.hononeko.miru.artwork

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.net.URI

private const val DEFAULT_AVATAR_REQUEST_TIMEOUT_MS = 10_000L
private const val DEFAULT_AVATAR_CONNECT_TIMEOUT_MS = 5_000L
private const val MAX_ARTWORK_DIMENSION = 2_000
private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
private const val AVATAR_CACHE_KEY_HASH_LEN = 16
private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val REDIRECT_STATUS_MIN = 300
private const val REDIRECT_STATUS_MAX = 399
private const val ASCII_SPACE = 0x20
private const val ASCII_DEL = 0x7F

private const val ARTWORK_ACCEPT_HEADER = "image/*, */*;q=0.8"
private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

val DEFAULT_ALLOWED_AVATAR_HOSTS: Set<String> =
    setOf(
        "plex.tv",
        "gravatar.com",
        "secure.gravatar.com",
    )

fun createAvatarHttpClient(
    engine: HttpClientEngine = Java.create(),
    requestTimeoutMs: Long = DEFAULT_AVATAR_REQUEST_TIMEOUT_MS,
    connectTimeoutMs: Long = DEFAULT_AVATAR_CONNECT_TIMEOUT_MS,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
        }
    }

enum class ArtworkKind(
    val pathSegment: String,
) {
    THUMB("thumb"),
    ART("art"),
}

sealed interface AvatarTarget {
    data class PlexPath(
        val path: String,
    ) : AvatarTarget

    data class ExternalHttpsUrl(
        val url: String,
    ) : AvatarTarget
}

/**
 * Proxies media artwork from the configured [MediaServerAdapter] and user avatars from either
 * the media server or an SSRF-allowlisted external HTTPS host, backed by [BoundedArtworkCache].
 */
class ArtworkProxyService(
    private val adapter: MediaServerAdapter,
    private val userRepository: UserRepository,
    private val externalAvatarClient: HttpClient,
    private val cache: BoundedArtworkCache = BoundedArtworkCache(),
    allowedAvatarHosts: Set<String> = DEFAULT_ALLOWED_AVATAR_HOSTS,
    private val plexBaseUrlProvider: () -> String? = { null },
) {
    constructor(
        adapter: MediaServerAdapter,
        userRepository: UserRepository,
        externalAvatarClient: HttpClient,
        cache: BoundedArtworkCache = BoundedArtworkCache(),
        allowedAvatarHosts: Set<String> = DEFAULT_ALLOWED_AVATAR_HOSTS,
        plexBaseUrl: String?,
    ) : this(
        adapter = adapter,
        userRepository = userRepository,
        externalAvatarClient = externalAvatarClient,
        cache = cache,
        allowedAvatarHosts = allowedAvatarHosts,
        plexBaseUrlProvider = { plexBaseUrl },
    )

    private val normalizedAllowedHosts: Set<String> =
        allowedAvatarHosts
            .map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun currentPlexOrigin(): NormalizedOrigin? =
        plexBaseUrlProvider()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::parseOrigin)

    suspend fun getMediaArtwork(
        ratingKey: String,
        kind: ArtworkKind,
        width: Int? = null,
        height: Int? = null,
    ): Either<MiruError, CachedArtwork> =
        either {
            validateMediaArtworkRequest(ratingKey = ratingKey, width = width, height = height).bind()

            val cacheKey = "media:$ratingKey:${kind.pathSegment}:w=${width ?: 0}:h=${height ?: 0}"
            cache.get(cacheKey)?.let { return@either it }

            val artworkPath = "/library/metadata/$ratingKey/${kind.pathSegment}"
            val artwork = adapter.fetchArtwork(artworkPath, width = width, height = height).bind()
            ensure(artwork.bytes.isNotEmpty()) {
                MiruError.UpstreamUnavailable(
                    provider = adapter.serverType,
                    message = "Upstream returned empty artwork payload",
                )
            }

            val contentType = sanitizeImageContentType(artwork.contentType)
            cache.put(key = cacheKey, bytes = artwork.bytes, contentType = contentType)
        }

    suspend fun getUserAvatar(
        principal: MiruPrincipal,
        userId: String,
    ): Either<MiruError, CachedArtwork> =
        either {
            ensure(userId.isNotBlank()) {
                MiruError.ValidationError(
                    message = "Invalid user id",
                    fieldErrors = mapOf("id" to "must not be blank"),
                )
            }
            principal.requireMediaUserAccess(userId).bind()

            val user =
                userRepository.findById(userId).bind()
                    ?: raise(MiruError.NotFound(resource = "User", id = userId))

            val rawThumbUrl =
                user.thumbUrl?.trim()?.takeIf { it.isNotEmpty() }
                    ?: raise(MiruError.NotFound(resource = "User avatar", id = userId))

            val target = validateAvatarTarget(rawThumbUrl).bind()

            val cacheKey = "avatar:$userId:${BoundedArtworkCache.sha256Hex(rawThumbUrl).take(AVATAR_CACHE_KEY_HASH_LEN)}"
            cache.get(cacheKey)?.let { return@either it }

            when (target) {
                is AvatarTarget.PlexPath -> {
                    val artwork = adapter.fetchArtwork(target.path).bind()
                    ensure(artwork.bytes.isNotEmpty()) {
                        MiruError.UpstreamUnavailable(
                            provider = adapter.serverType,
                            message = "Upstream returned empty avatar payload",
                        )
                    }
                    val contentType = sanitizeImageContentType(artwork.contentType)
                    cache.put(key = cacheKey, bytes = artwork.bytes, contentType = contentType)
                }

                is AvatarTarget.ExternalHttpsUrl -> {
                    fetchExternalAvatar(
                        url = target.url,
                        userId = userId,
                        cacheKey = cacheKey,
                    ).bind()
                }
            }
        }

    internal fun validateAvatarTarget(rawUrl: String): Either<MiruError.ValidationError, AvatarTarget> =
        either {
            val trimmed = rawUrl.trim()
            ensure(trimmed.isNotEmpty()) { invalidAvatarTargetError("Avatar URL must not be blank") }
            ensure(!containsUnsafeChars(trimmed)) { invalidAvatarTargetError("Avatar URL contains disallowed characters") }
            ensure(!containsPathTraversal(trimmed)) { invalidAvatarTargetError("Avatar URL contains disallowed path traversal") }
            ensure(!trimmed.contains('#')) { invalidAvatarTargetError("Avatar URL must not contain fragments") }

            if (trimmed.startsWith("/")) {
                ensure(isSafeRelativePlexPath(trimmed)) {
                    invalidAvatarTargetError("Avatar path is not a safe relative path")
                }
                return@either AvatarTarget.PlexPath(trimmed)
            }

            val uri =
                runCatching { URI(trimmed) }.getOrElse {
                    raise(invalidAvatarTargetError("Avatar URL is malformed"))
                }

            val scheme = uri.scheme?.lowercase()
            ensure(scheme == "http" || scheme == "https") {
                invalidAvatarTargetError("Avatar URL scheme is not allowed")
            }
            ensure(uri.rawUserInfo == null && uri.userInfo == null) {
                invalidAvatarTargetError("Avatar URL must not contain userinfo")
            }

            val rawAuthority = uri.rawAuthority
            ensure(!rawAuthority.isNullOrBlank() && !rawAuthority.contains('@')) {
                invalidAvatarTargetError("Avatar URL authority is invalid")
            }

            val host = uri.host?.lowercase()?.trimEnd('.')
            ensure(!host.isNullOrBlank()) {
                invalidAvatarTargetError("Avatar URL host is missing")
            }

            val origin = NormalizedOrigin(scheme = scheme, host = host, port = effectivePort(scheme, uri.port))
            val parsedPlexOrigin = currentPlexOrigin()
            if (parsedPlexOrigin != null && origin == parsedPlexOrigin) {
                val plexPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                ensure(isSafeRelativePlexPath(plexPath)) {
                    invalidAvatarTargetError("Plex avatar path is not a safe relative path")
                }
                return@either AvatarTarget.PlexPath(plexPath)
            }

            ensure(scheme == "https") {
                invalidAvatarTargetError("External avatar URL must use HTTPS")
            }
            ensure(!isIpLiteral(host)) {
                invalidAvatarTargetError("External avatar host must not be an IP address")
            }
            ensure(isAllowedAvatarHost(host)) {
                invalidAvatarTargetError("External avatar host is not in the allowlist")
            }

            AvatarTarget.ExternalHttpsUrl(trimmed)
        }

    private suspend fun fetchExternalAvatar(
        url: String,
        userId: String,
        cacheKey: String,
    ): Either<MiruError, CachedArtwork> =
        either {
            val response =
                try {
                    externalAvatarClient.get(url) {
                        header(HttpHeaders.Accept, ARTWORK_ACCEPT_HEADER)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ResponseException) {
                    if (e.response.status == HttpStatusCode.NotFound) {
                        raise(MiruError.NotFound(resource = "User avatar", id = userId))
                    }
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "External avatar host returned HTTP ${e.response.status.value}",
                            cause = e,
                        ),
                    )
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "Failed to fetch external user avatar",
                            cause = e,
                        ),
                    )
                }

            val status = response.status
            if (status == HttpStatusCode.NotFound) {
                raise(MiruError.NotFound(resource = "User avatar", id = userId))
            }
            if (status.value in REDIRECT_STATUS_MIN..REDIRECT_STATUS_MAX) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar host attempted redirect (HTTP ${status.value})",
                    ),
                )
            }
            if (!status.isSuccess()) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar host returned HTTP ${status.value}",
                    ),
                )
            }

            val declaredLength = response.contentLength()
            ensure(declaredLength == null || declaredLength <= MAX_ARTWORK_BYTES) {
                MiruError.UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "External avatar payload exceeds maximum size",
                )
            }

            val bytes =
                try {
                    response.bodyAsBytes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "Failed to read external avatar response bytes",
                            cause = e,
                        ),
                    )
                }

            ensure(bytes.isNotEmpty()) {
                MiruError.UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "External avatar host returned empty payload",
                )
            }
            ensure(bytes.size <= MAX_ARTWORK_BYTES) {
                MiruError.UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "External avatar payload exceeds maximum size",
                )
            }

            val sanitizedType = sanitizeImageContentType(response.contentType()?.toString())
            cache.put(key = cacheKey, bytes = bytes, contentType = sanitizedType)
        }

    private fun validateMediaArtworkRequest(
        ratingKey: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError.ValidationError, Unit> =
        either {
            val errors = linkedMapOf<String, String>()
            if (!RATING_KEY_REGEX.matches(ratingKey)) {
                errors["ratingKey"] = "must match ^[A-Za-z0-9_-]+$"
            }
            if (width != null && width !in 1..MAX_ARTWORK_DIMENSION) {
                errors["w"] = "must be between 1 and $MAX_ARTWORK_DIMENSION"
            }
            if (height != null && height !in 1..MAX_ARTWORK_DIMENSION) {
                errors["h"] = "must be between 1 and $MAX_ARTWORK_DIMENSION"
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid artwork request parameters",
                    fieldErrors = errors,
                )
            }
        }

    private fun isAllowedAvatarHost(host: String): Boolean {
        if (!HOSTNAME_REGEX.matches(host)) return false
        return host in normalizedAllowedHosts ||
            normalizedAllowedHosts.any { allowed -> host.endsWith(".$allowed") }
    }

    private fun isSafeRelativePlexPath(path: String): Boolean =
        path.startsWith("/") &&
            !path.startsWith("//") &&
            !path.contains("://") &&
            !path.contains('?') &&
            !path.contains('@')

    private fun containsUnsafeChars(value: String): Boolean =
        value.contains('\\') ||
            value.any { ch -> ch.code <= ASCII_SPACE || ch.code == ASCII_DEL }

    private fun containsPathTraversal(value: String): Boolean {
        val lowered = value.lowercase()
        return value.contains("..") ||
            lowered.contains("%2e%2e") ||
            lowered.contains(".%2e") ||
            lowered.contains("%2e.")
    }

    private fun isIpLiteral(host: String): Boolean =
        host.startsWith("[") ||
            host.contains(':') ||
            IPV4_REGEX.matches(host) ||
            host.all { it.isDigit() } ||
            host.startsWith("0x")

    private fun invalidAvatarTargetError(reason: String): MiruError.ValidationError =
        MiruError.ValidationError(
            message = "Invalid avatar URL",
            fieldErrors = mapOf("thumbUrl" to reason),
        )

    private data class NormalizedOrigin(
        val scheme: String,
        val host: String,
        val port: Int,
    )

    private fun parseOrigin(rawUrl: String): NormalizedOrigin? =
        runCatching {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            NormalizedOrigin(scheme = scheme, host = host, port = effectivePort(scheme, uri.port))
        }.getOrNull()

    private fun effectivePort(
        scheme: String,
        port: Int,
    ): Int =
        when {
            port > 0 -> port
            scheme == "http" -> HTTP_DEFAULT_PORT
            scheme == "https" -> HTTPS_DEFAULT_PORT
            else -> -1
        }

    companion object {
        private const val EXTERNAL_AVATAR_PROVIDER = "avatar"
        private val RATING_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val HOSTNAME_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

        internal fun sanitizeImageContentType(raw: String?): String {
            val candidate =
                raw
                    ?.trim()
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() && !it.contains('\r') && !it.contains('\n') }
                    ?: return DEFAULT_CONTENT_TYPE
            if (!candidate.startsWith("image/")) {
                return DEFAULT_CONTENT_TYPE
            }
            val parsed = runCatching { ContentType.parse(candidate) }.getOrNull() ?: return DEFAULT_CONTENT_TYPE
            return if (parsed.contentType.equals("image", ignoreCase = true) && parsed.contentSubtype.isNotBlank()) {
                "${parsed.contentType.lowercase()}/${parsed.contentSubtype.lowercase()}"
            } else {
                DEFAULT_CONTENT_TYPE
            }
        }

        fun matchesIfNoneMatch(
            headerValue: String?,
            etag: String,
        ): Boolean {
            val raw = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (raw == "*") return true

            val normalizedTarget = normalizeEtag(etag)
            return raw
                .split(',')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .any { candidate ->
                    candidate == "*" || normalizeEtag(candidate) == normalizedTarget
                }
        }

        private fun normalizeEtag(value: String): String =
            if (value.startsWith("W/")) {
                value.substring(2).trim()
            } else {
                value
            }
    }
}
