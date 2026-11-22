package app.hononeko.miru.adapter.plex

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L

fun createPlexHttpClient(
    engine: HttpClientEngine = CIO.create(),
    requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(MiruJson)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
        }
    }

class PlexMediaServerAdapter(
    private val config: PlexConfig,
    private val httpClient: HttpClient = createPlexHttpClient(),
    plexTvBaseUrl: String? = null,
    private val eventSource: Flow<MediaServerEvent> = emptyFlow(),
) : MediaServerAdapter {
    override val serverType: String = PROVIDER_NAME

    private val logger = LoggerFactory.getLogger(PlexMediaServerAdapter::class.java)
    private val baseUrl: String = config.baseUrl.trimEnd('/')
    private val normalizedPlexTvBaseUrl: String? =
        plexTvBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private val ownerMutex = Mutex()

    @Volatile
    private var cachedOwner: MediaUser? = null

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> =
        executeJsonGet<PlexServerInfoResponse>(
            url = "$baseUrl/",
            operation = "check server health",
            notFoundResource = "Plex server root",
        ).map { response ->
            PlexMapper.mapServerIdentity(response.mediaContainer)
        }

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> =
        either {
            val response =
                executeJsonGet<PlexSessionsResponse>(
                    url = "$baseUrl/status/sessions",
                    operation = "fetch active sessions",
                    notFoundResource = "Plex sessions",
                ).bind()

            val items = response.mediaContainer.metadata.orEmpty()
            val hasLocalOwner = items.any { it.user?.id == LOCAL_OWNER_USER_ID }
            val ownerUser = if (hasLocalOwner) resolveOwnerBestEffort() else null

            PlexMapper.mapSessions(response.mediaContainer, ownerUser = ownerUser)
        }

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> =
        either {
            val accountsResponse =
                executeJsonGet<PlexAccountsResponse>(
                    url = "$baseUrl/accounts",
                    operation = "fetch accounts",
                    notFoundResource = "Plex accounts",
                ).bind()

            val accounts = accountsResponse.mediaContainer.account.orEmpty()
            val plexTvUser = fetchOptionalPlexTvUser()
            val plexTvFriends = fetchOptionalPlexTvFriends()

            val resolvedOwner = PlexMapper.resolveOwnerUser(accounts, plexTvUser)
            if (resolvedOwner != null) {
                ownerMutex.withLock {
                    cachedOwner = resolvedOwner
                }
            }

            PlexMapper.mapUsers(
                accounts = accounts,
                plexTvUser = plexTvUser,
                plexTvFriends = plexTvFriends,
            )
        }

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> =
        executeJsonGet<PlexLibrarySectionsResponse>(
            url = "$baseUrl/library/sections",
            operation = "fetch library sections",
            notFoundResource = "Plex library sections",
        ).map { response ->
            PlexMapper.mapLibrarySections(response.mediaContainer)
        }

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> =
        either {
            validateSectionKey(sectionKey).bind()
            if (mediaTypeCode != null) {
                ensure(mediaTypeCode > 0) {
                    MiruError.ValidationError(
                        message = "Invalid media type code",
                        fieldErrors = mapOf("mediaTypeCode" to "must be greater than 0"),
                    )
                }
            }

            val response =
                executeJsonGet<PlexLibraryItemsResponse>(
                    url = "$baseUrl/library/sections/$sectionKey/all",
                    operation = "fetch library count",
                    notFoundResource = "Library section",
                    notFoundId = sectionKey,
                ) {
                    parameter("X-Plex-Container-Start", 0)
                    parameter("X-Plex-Container-Size", 0)
                    if (mediaTypeCode != null) {
                        parameter("type", mediaTypeCode)
                    }
                }.bind()

            response.mediaContainer.totalSize ?: response.mediaContainer.size ?: 0
        }

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> =
        either {
            validateSectionKey(sectionKey).bind()
            ensure(start >= 0) {
                MiruError.ValidationError(
                    message = "Invalid pagination offset",
                    fieldErrors = mapOf("start" to "must be greater than or equal to 0"),
                )
            }
            ensure(size > 0) {
                MiruError.ValidationError(
                    message = "Invalid pagination size",
                    fieldErrors = mapOf("size" to "must be greater than 0"),
                )
            }
            if (mediaTypeCode != null) {
                ensure(mediaTypeCode > 0) {
                    MiruError.ValidationError(
                        message = "Invalid media type code",
                        fieldErrors = mapOf("mediaTypeCode" to "must be greater than 0"),
                    )
                }
            }

            val response =
                executeJsonGet<PlexLibraryItemsResponse>(
                    url = "$baseUrl/library/sections/$sectionKey/all",
                    operation = "fetch library items",
                    notFoundResource = "Library section",
                    notFoundId = sectionKey,
                ) {
                    parameter("X-Plex-Container-Start", start)
                    parameter("X-Plex-Container-Size", size)
                    if (mediaTypeCode != null) {
                        parameter("type", mediaTypeCode)
                    }
                }.bind()

            PlexMapper.mapLibraryItemsPage(
                sectionKey = sectionKey,
                requestedStart = start,
                container = response.mediaContainer,
            )
        }

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> =
        either {
            validateArtworkParams(path = path, width = width, height = height).bind()

            val useTranscode = width != null || height != null
            val requestUrl = if (useTranscode) "$baseUrl/photo/:/transcode" else "$baseUrl$path"

            val response =
                executeRequest(
                    url = requestUrl,
                    acceptHeader = ARTWORK_ACCEPT_HEADER,
                    operation = "fetch artwork",
                    notFoundResource = "Artwork",
                    notFoundId = path,
                ) {
                    if (useTranscode) {
                        if (width != null) {
                            parameter("width", width)
                        }
                        if (height != null) {
                            parameter("height", height)
                        }
                        parameter("minSize", 1)
                        parameter("upscale", 1)
                        parameter("url", path)
                    }
                }.bind()

            val bytes =
                try {
                    response.bodyAsBytes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = "Failed to read artwork bytes from Plex: ${sanitizeMessage(e.message)}",
                            cause = e,
                        ),
                    )
                }

            val contentType = response.contentType()?.withoutParameters()?.toString() ?: DEFAULT_ARTWORK_CONTENT_TYPE
            ArtworkData(bytes = bytes, contentType = contentType)
        }

    override fun events(): Flow<MediaServerEvent> = eventSource

    private suspend fun resolveOwnerBestEffort(): MediaUser? {
        cachedOwner?.let { return it }

        return ownerMutex.withLock {
            cachedOwner?.let { return@withLock it }

            val plexTvUser = fetchOptionalPlexTvUser()
            val accounts =
                if (plexTvUser == null) {
                    executeJsonGet<PlexAccountsResponse>(
                        url = "$baseUrl/accounts",
                        operation = "resolve local owner account",
                        notFoundResource = "Plex accounts",
                    ).fold(
                        ifLeft = { error ->
                            logger.debug("Best-effort local owner resolution via /accounts failed: {}", error.message)
                            emptyList()
                        },
                        ifRight = { it.mediaContainer.account.orEmpty() },
                    )
                } else {
                    emptyList()
                }

            PlexMapper.resolveOwnerUser(accounts = accounts, plexTvUser = plexTvUser)?.also { resolved ->
                cachedOwner = resolved
            }
        }
    }

    private suspend fun fetchOptionalPlexTvUser(): PlexTvUserDto? {
        val tvBase = normalizedPlexTvBaseUrl ?: return null
        return executeJsonGet<PlexTvUserDto>(
            url = "$tvBase/api/v2/user",
            operation = "fetch plex.tv user",
            notFoundResource = "Plex.tv user",
        ).fold(
            ifLeft = { error ->
                logger.warn("Optional plex.tv user lookup failed: {}", sanitizeMessage(error.message))
                null
            },
            ifRight = { it },
        )
    }

    private suspend fun fetchOptionalPlexTvFriends(): List<PlexTvFriendDto> {
        val tvBase = normalizedPlexTvBaseUrl ?: return emptyList()
        return executeJsonGet<List<PlexTvFriendDto>>(
            url = "$tvBase/api/v2/friends",
            operation = "fetch plex.tv friends",
            notFoundResource = "Plex.tv friends",
        ).fold(
            ifLeft = { error ->
                logger.warn("Optional plex.tv friends lookup failed: {}", sanitizeMessage(error.message))
                emptyList()
            },
            ifRight = { it },
        )
    }

    private suspend inline fun <reified T> executeJsonGet(
        url: String,
        operation: String,
        notFoundResource: String,
        notFoundId: String? = null,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): Either<MiruError, T> =
        either {
            val response =
                executeRequest(
                    url = url,
                    acceptHeader = ContentType.Application.Json.toString(),
                    operation = operation,
                    notFoundResource = notFoundResource,
                    notFoundId = notFoundId,
                    block = block,
                ).bind()

            val bodyText =
                try {
                    response.bodyAsText()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = "Failed reading Plex response during $operation: ${sanitizeMessage(e.message)}",
                            cause = e,
                        ),
                    )
                }

            try {
                MiruJson.decodeFromString<T>(bodyText)
            } catch (e: SerializationException) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = PROVIDER_NAME,
                        message = "Invalid JSON payload from Plex during $operation",
                        cause = e,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = PROVIDER_NAME,
                        message = "Malformed payload from Plex during $operation",
                        cause = e,
                    ),
                )
            }
        }

    private suspend inline fun executeRequest(
        url: String,
        acceptHeader: String,
        operation: String,
        notFoundResource: String,
        notFoundId: String? = null,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): Either<MiruError, HttpResponse> {
        val response =
            try {
                httpClient.get(url) {
                    header(PLEX_TOKEN_HEADER, config.token)
                    header(HttpHeaders.Accept, acceptHeader)
                    block()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                return mapErrorStatus(
                    status = e.response.status,
                    operation = operation,
                    notFoundResource = notFoundResource,
                    notFoundId = notFoundId,
                ).left()
            } catch (e: Exception) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = PROVIDER_NAME,
                        message = "Plex request failed during $operation: ${sanitizeMessage(e.message)}",
                        cause = e,
                    ).left()
            }

        return if (response.status.isSuccess()) {
            response.right()
        } else {
            mapErrorStatus(
                status = response.status,
                operation = operation,
                notFoundResource = notFoundResource,
                notFoundId = notFoundId,
            ).left()
        }
    }

    private fun mapErrorStatus(
        status: HttpStatusCode,
        operation: String,
        notFoundResource: String,
        notFoundId: String?,
    ): MiruError =
        when (status) {
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            ->
                MiruError.UpstreamAuthError(
                    provider = PROVIDER_NAME,
                    message = "Plex rejected authentication during $operation (HTTP ${status.value})",
                )

            HttpStatusCode.NotFound ->
                MiruError.NotFound(
                    resource = notFoundResource,
                    id = notFoundId,
                )

            else ->
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER_NAME,
                    message = "Plex returned HTTP ${status.value} during $operation",
                )
        }

    private fun validateSectionKey(sectionKey: String): Either<MiruError.ValidationError, Unit> =
        if (sectionKey.isNotBlank() && SECTION_KEY_REGEX.matches(sectionKey)) {
            Unit.right()
        } else {
            MiruError
                .ValidationError(
                    message = "Invalid library section key",
                    fieldErrors = mapOf("sectionKey" to "must be a non-empty alphanumeric section key"),
                ).left()
        }

    private fun validateArtworkParams(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError.ValidationError, Unit> =
        either {
            val errors = linkedMapOf<String, String>()
            if (!isSafeArtworkPath(path)) {
                errors["path"] = "must be a relative Plex path starting with '/' without traversal or URI schemes"
            }
            if (width != null && width <= 0) {
                errors["width"] = "must be greater than 0"
            }
            if (height != null && height <= 0) {
                errors["height"] = "must be greater than 0"
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid artwork request parameters",
                    fieldErrors = errors,
                )
            }
        }

    private fun isSafeArtworkPath(path: String): Boolean {
        if (path.isBlank() || !path.startsWith("/") || path.startsWith("//")) {
            return false
        }
        val lowered = path.lowercase()
        val hasUnsafeToken =
            path.contains("..") ||
                lowered.contains("%2e%2e") ||
                path.contains("://") ||
                path.contains('?') ||
                path.contains('#') ||
                path.contains('\\')
        if (hasUnsafeToken) {
            return false
        }
        return path.none { ch -> ch.code < ASCII_SPACE || ch.code == ASCII_DEL }
    }

    private fun sanitizeMessage(raw: String?): String {
        val text = raw?.takeIf { it.isNotBlank() } ?: "unexpected upstream error"
        return if (config.token.isNotEmpty()) {
            text.replace(config.token, "***")
        } else {
            text
        }
    }

    private companion object {
        const val PROVIDER_NAME = "plex"
        const val PLEX_TOKEN_HEADER = "X-Plex-Token"
        const val LOCAL_OWNER_USER_ID = "1"
        const val ARTWORK_ACCEPT_HEADER = "image/*, */*;q=0.8"
        const val DEFAULT_ARTWORK_CONTENT_TYPE = "application/octet-stream"
        const val ASCII_SPACE = 0x20
        const val ASCII_DEL = 0x7F
        val SECTION_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }
}
