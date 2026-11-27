package app.hononeko.miru.artwork

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.LibraryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.MediaSectionAccessChecker
import app.hononeko.miru.service.ViewerIdentityResolver
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
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
private const val MAX_AVATAR_REDIRECT_HOPS = 3
private val AVATAR_REDIRECT_STATUS_CODES =
    setOf(
        HttpStatusCode.MovedPermanently.value,
        HttpStatusCode.Found.value,
        HttpStatusCode.SeeOther.value,
        HttpStatusCode.TemporaryRedirect.value,
        HttpStatusCode.PermanentRedirect.value,
    )
private const val ASCII_SPACE = 0x20
private const val ASCII_DEL = 0x7F

private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

val DEFAULT_ALLOWED_AVATAR_HOSTS: Set<String> =
    setOf(
        "plex.tv",
        "gravatar.com",
        "secure.gravatar.com",
    )

private val GRAVATAR_PHOTON_HOSTS: Set<String> =
    setOf(
        "i0.wp.com",
        "i1.wp.com",
        "i2.wp.com",
        "i3.wp.com",
    )

private const val PHOTON_PLEX_AVATAR_PREFIX = "/plex.tv/avatars/"

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
@Suppress("LongParameterList")
class ArtworkProxyService(
    private val adapter: MediaServerAdapter,
    private val userRepository: UserRepository,
    externalAvatarClient: HttpClient? = null,
    private val cache: BoundedArtworkCache = BoundedArtworkCache(),
    allowedAvatarHosts: Set<String> = DEFAULT_ALLOWED_AVATAR_HOSTS,
    private val plexBaseUrlProvider: () -> String? = { null },
    private val libraryRepository: LibraryRepository? = null,
    private val sectionAccessChecker: MediaSectionAccessChecker =
        MediaSectionAccessChecker(libraryRepository = libraryRepository, adapter = adapter),
    private val dnsResolver: DnsResolver = SystemDnsResolver,
    private val externalAvatarFetcher: ExternalAvatarFetcher =
        externalAvatarClient?.let(::HttpClientExternalAvatarFetcher) ?: PinnedHttpsAvatarFetcher(),
    private val adapterForServer: (suspend (String?) -> MediaServerAdapter?)? = null,
    private val allServerAdaptersProvider: (suspend () -> List<Pair<String?, MediaServerAdapter>>)? = null,
    private val serverBaseUrlsProvider: (() -> List<String>)? = null,
    private val authAccountRepository: AuthAccountRepository? = null,
) {
    constructor(
        adapter: MediaServerAdapter,
        userRepository: UserRepository,
        externalAvatarClient: HttpClient? = null,
        cache: BoundedArtworkCache = BoundedArtworkCache(),
        allowedAvatarHosts: Set<String> = DEFAULT_ALLOWED_AVATAR_HOSTS,
        plexBaseUrl: String?,
        libraryRepository: LibraryRepository? = null,
        dnsResolver: DnsResolver = SystemDnsResolver,
        externalAvatarFetcher: ExternalAvatarFetcher =
            externalAvatarClient?.let(::HttpClientExternalAvatarFetcher) ?: PinnedHttpsAvatarFetcher(),
    ) : this(
        adapter = adapter,
        userRepository = userRepository,
        externalAvatarClient = externalAvatarClient,
        cache = cache,
        allowedAvatarHosts = allowedAvatarHosts,
        plexBaseUrlProvider = { plexBaseUrl },
        libraryRepository = libraryRepository,
        dnsResolver = dnsResolver,
        externalAvatarFetcher = externalAvatarFetcher,
    )

    private val normalizedAllowedHosts: Set<String> =
        allowedAvatarHosts
            .map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .toSet()

    private val gravatarPhotonFallbackEnabled: Boolean =
        ("gravatar.com" in normalizedAllowedHosts || "secure.gravatar.com" in normalizedAllowedHosts) &&
            "plex.tv" in normalizedAllowedHosts

    private fun currentServerOrigins(): List<NormalizedOrigin> =
        buildList {
            plexBaseUrlProvider()?.let { add(it) }
            serverBaseUrlsProvider?.invoke()?.let { addAll(it) }
        }.mapNotNull { raw ->
            raw.trim().takeIf { it.isNotEmpty() }?.let(::parseOrigin)
        }.distinct()

    suspend fun getMediaArtwork(
        principal: MiruPrincipal,
        ratingKey: String,
        kind: ArtworkKind,
        width: Int? = null,
        height: Int? = null,
        serverId: String? = null,
    ): Either<MiruError, CachedArtwork> =
        either {
            validateMediaArtworkRequest(ratingKey = ratingKey, width = width, height = height).bind()
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            sectionAccessChecker
                .requireAccessForRatingKey(principal = principal, ratingKey = ratingKey, serverId = normalizedServerId)
                .bind()

            val targetServers = resolveArtworkTargets(ratingKey = ratingKey, requestedServerId = normalizedServerId).bind()
            val cachePrefix = normalizedServerId ?: targetServers.singleOrNull()?.first
            val primaryCacheKey = buildMediaCacheKey(cachePrefix, ratingKey, kind, width, height)
            cache.get(primaryCacheKey)?.let { return@either it }

            var lastError: MiruError? = null
            for ((resolvedServerId, targetAdapter) in targetServers) {
                if (normalizedServerId == null && !principal.isAdmin()) {
                    val perServerAccess =
                        sectionAccessChecker.requireAccessForRatingKey(
                            principal = principal,
                            ratingKey = ratingKey,
                            serverId = resolvedServerId,
                        )
                    if (perServerAccess is Either.Left) {
                        if (lastError == null) {
                            lastError = perServerAccess.value
                        }
                        continue
                    }
                }
                val scopedCacheKey = buildMediaCacheKey(resolvedServerId, ratingKey, kind, width, height)
                cache.get(scopedCacheKey)?.let { return@either it }

                val artworkPath = targetAdapter.mediaArtworkPath(ratingKey, isBackdrop = kind == ArtworkKind.ART)
                when (val fetched = targetAdapter.fetchArtwork(artworkPath, width = width, height = height)) {
                    is Either.Right -> {
                        val artwork = fetched.value
                        if (artwork.bytes.isEmpty()) {
                            lastError =
                                MiruError.UpstreamUnavailable(
                                    provider = targetAdapter.serverType,
                                    message = "Upstream returned empty artwork payload",
                                )
                            continue
                        }
                        val contentType = sanitizeImageContentType(artwork.contentType)
                        return@either cache.put(key = scopedCacheKey, bytes = artwork.bytes, contentType = contentType)
                    }

                    is Either.Left -> {
                        lastError = fetched.value
                    }
                }
            }

            raise(
                lastError
                    ?: MiruError.NotFound(resource = "Artwork", id = ratingKey),
            )
        }

    private suspend fun resolveArtworkTargets(
        ratingKey: String,
        requestedServerId: String?,
    ): Either<MiruError, List<Pair<String?, MediaServerAdapter>>> =
        either {
            if (requestedServerId != null) {
                val targetAdapter =
                    if (adapterForServer != null) {
                        adapterForServer(requestedServerId)
                            ?: raise(MiruError.NotFound(resource = "Media server", id = requestedServerId))
                    } else {
                        adapter
                    }
                return@either listOf(requestedServerId to targetAdapter)
            }

            val targets = linkedMapOf<String?, MediaServerAdapter>()
            if (adapterForServer != null) {
                val dbServerIds =
                    libraryRepository
                        ?.findSectionKeysByRatingKey(ratingKey)
                        ?.getOrNull()
                        .orEmpty()
                        .map { it.first }
                        .distinct()
                for (dbServerId in dbServerIds) {
                    adapterForServer(dbServerId)?.let { targets[dbServerId] = it }
                }
            }
            allServerAdaptersProvider?.invoke()?.forEach { (sid, candidateAdapter) ->
                targets.putIfAbsent(sid, candidateAdapter)
            }
            if (targets.isEmpty()) {
                targets[null] = adapter
            }
            targets.entries.map { it.key to it.value }
        }

    private fun buildMediaCacheKey(
        serverId: String?,
        ratingKey: String,
        kind: ArtworkKind,
        width: Int?,
        height: Int?,
    ): String {
        val scope = serverId?.let { "$it:" }.orEmpty()
        return "media:$scope$ratingKey:${kind.pathSegment}:w=${width ?: 0}:h=${height ?: 0}"
    }

    suspend fun getUserAvatar(
        principal: MiruPrincipal,
        userId: String,
    ): Either<MiruError, CachedArtwork> =
        either {
            val trimmedUserId = userId.trim()
            ensure(trimmedUserId.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid user id",
                    fieldErrors = mapOf("id" to "must not be blank"),
                )
            }
            ViewerIdentityResolver.requireTargetViewerAccess(principal, trimmedUserId).bind()

            val directUser = userRepository.findById(trimmedUserId).bind()
            val user =
                if (directUser != null) {
                    directUser
                } else {
                    val linkedIds =
                        authAccountRepository
                            ?.findById(trimmedUserId)
                            ?.bind()
                            ?.let { authAccountRepository.findLinkedMediaUserIds(it.id).bind() }
                            .orEmpty()
                    val profiles =
                        if (linkedIds.isEmpty()) {
                            emptyList()
                        } else {
                            userRepository.findByIds(linkedIds).bind().sortedWith(ViewerIdentityResolver.stableProfileComparator)
                        }
                    if (profiles.isEmpty()) {
                        raise(MiruError.NotFound(resource = "User", id = trimmedUserId))
                    }
                    profiles.firstOrNull { !it.thumbUrl.isNullOrBlank() } ?: profiles.first()
                }

            val rawThumbUrl =
                user.thumbUrl?.trim()?.takeIf { it.isNotEmpty() }
                    ?: raise(MiruError.NotFound(resource = "User avatar", id = trimmedUserId))

            val target = validateAvatarTarget(rawThumbUrl).bind()

            val cacheKey = "avatar:${user.id}:${BoundedArtworkCache.sha256Hex(rawThumbUrl).take(AVATAR_CACHE_KEY_HASH_LEN)}"
            cache.get(cacheKey)?.let { return@either it }

            when (target) {
                is AvatarTarget.PlexPath -> {
                    val targetAdapter =
                        if (adapterForServer != null) {
                            adapterForServer.invoke(user.serverId)
                                ?: raise(
                                    MiruError.UpstreamUnavailable(
                                        provider = "media_server",
                                        message = "Media server adapter is not available for user '$trimmedUserId'",
                                    ),
                                )
                        } else {
                            adapter
                        }
                    val artwork = targetAdapter.fetchArtwork(target.path).bind()
                    ensure(artwork.bytes.isNotEmpty()) {
                        MiruError.UpstreamUnavailable(
                            provider = targetAdapter.serverType,
                            message = "Upstream returned empty avatar payload",
                        )
                    }
                    val contentType = sanitizeImageContentType(artwork.contentType)
                    cache.put(key = cacheKey, bytes = artwork.bytes, contentType = contentType)
                }

                is AvatarTarget.ExternalHttpsUrl -> {
                    fetchExternalAvatar(
                        url = target.url,
                        userId = trimmedUserId,
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
            if (origin in currentServerOrigins()) {
                val plexPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                ensure(isSafeRelativePlexPath(plexPath)) {
                    invalidAvatarTargetError("Media server avatar path is not a safe relative path")
                }
                return@either AvatarTarget.PlexPath(plexPath)
            }

            ensure(scheme == "https") {
                invalidAvatarTargetError("External avatar URL must use HTTPS")
            }
            ensure(!isIpLiteral(host)) {
                invalidAvatarTargetError("External avatar host must not be an IP address")
            }
            ensure(isAllowedAvatarUrl(host = host, rawPath = uri.rawPath)) {
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
            val response = executeExternalAvatarGetWithRedirects(initialUrl = url).bind()

            val statusCode = response.statusCode
            if (statusCode == HttpStatusCode.NotFound.value) {
                raise(MiruError.NotFound(resource = "User avatar", id = userId))
            }
            if (statusCode in REDIRECT_STATUS_MIN..REDIRECT_STATUS_MAX) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar host attempted redirect (HTTP $statusCode)",
                    ),
                )
            }
            if (!HttpStatusCode.fromValue(statusCode).isSuccess()) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar host returned HTTP $statusCode",
                    ),
                )
            }

            val declaredLength = response.contentLength
            ensure(declaredLength == null || declaredLength <= MAX_ARTWORK_BYTES) {
                MiruError.UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "External avatar payload exceeds maximum size",
                )
            }

            val bytes = response.bytes
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

            val sanitizedType = sanitizeImageContentType(response.contentType)
            cache.put(key = cacheKey, bytes = bytes, contentType = sanitizedType)
        }

    private suspend fun executeExternalAvatarGetWithRedirects(
        initialUrl: String,
    ): Either<MiruError, ExternalAvatarHttpResponse> =
        either {
            var currentUrl = initialUrl
            repeat(MAX_AVATAR_REDIRECT_HOPS) {
                val response = executeSingleExternalGet(url = currentUrl).bind()
                if (response.statusCode !in AVATAR_REDIRECT_STATUS_CODES) {
                    return@either response
                }
                currentUrl = resolveAndValidateRedirectTarget(currentUrl = currentUrl, response = response).bind()
            }
            val finalResponse = executeSingleExternalGet(url = currentUrl).bind()
            ensure(finalResponse.statusCode !in AVATAR_REDIRECT_STATUS_CODES) {
                MiruError.UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "External avatar redirect chain exceeded $MAX_AVATAR_REDIRECT_HOPS hops",
                )
            }
            finalResponse
        }

    private suspend fun executeSingleExternalGet(
        url: String,
    ): Either<MiruError, ExternalAvatarHttpResponse> =
        either {
            val host =
                runCatching { URI(url).host?.lowercase()?.trimEnd('.') }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "External avatar URL is missing a host",
                        ),
                    )
            val pinnedAddresses = IpAddressSafety.resolveAndValidatePublicIps(host = host, dnsResolver = dnsResolver).bind()
            externalAvatarFetcher.get(url = url, pinnedAddresses = pinnedAddresses).bind()
        }

    private fun resolveAndValidateRedirectTarget(
        currentUrl: String,
        response: ExternalAvatarHttpResponse,
    ): Either<MiruError.UpstreamUnavailable, String> =
        either {
            val location =
                response.locationHeader
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "External avatar host returned redirect without Location header",
                        ),
                    )

            ensure(!containsPathTraversal(location)) {
                MiruError.UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "External avatar redirect target contains disallowed path traversal",
                )
            }

            val resolvedUrl =
                runCatching { URI(currentUrl).resolve(location).toString() }.getOrElse { e ->
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "External avatar host returned malformed redirect Location",
                            cause = e,
                        ),
                    )
                }

            val validatedTarget =
                validateAvatarTarget(resolvedUrl).getOrElse { validationError ->
                    val detail = validationError.fieldErrors["thumbUrl"] ?: validationError.message
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "External avatar redirect target blocked: $detail",
                        ),
                    )
                }

            val externalTarget =
                validatedTarget as? AvatarTarget.ExternalHttpsUrl
                    ?: raise(
                        MiruError.UpstreamUnavailable(
                            provider = EXTERNAL_AVATAR_PROVIDER,
                            message = "External avatar redirect target must be an allowlisted HTTPS URL",
                        ),
                    )

            externalTarget.url
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

    private fun isAllowedAvatarUrl(
        host: String,
        rawPath: String?,
    ): Boolean {
        if (isAllowedAvatarHost(host)) return true
        if (!gravatarPhotonFallbackEnabled) return false
        return host in GRAVATAR_PHOTON_HOSTS &&
            rawPath != null &&
            rawPath.startsWith(PHOTON_PLEX_AVATAR_PREFIX) &&
            !rawPath.contains("//")
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
