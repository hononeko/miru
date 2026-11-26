package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.MediaUserLibraryAccess
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L

fun createJellyfinHttpClient(
    engine: HttpClientEngine = Java.create(),
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

internal fun buildMediaBrowserAuthHeader(
    apiKey: String?,
    deviceId: String,
    version: String = "1.0.0",
): String {
    val safeDeviceId = sanitizeHeaderValue(deviceId).ifBlank { "miru" }
    val safeVersion = sanitizeHeaderValue(version).ifBlank { "1.0.0" }
    val base = "MediaBrowser Client=\"Miru\", Device=\"Miru\", DeviceId=\"$safeDeviceId\", Version=\"$safeVersion\""
    val safeToken = apiKey?.let(::sanitizeHeaderValue)?.takeIf { it.isNotBlank() }
    return if (safeToken != null) "$base, Token=\"$safeToken\"" else base
}

private fun sanitizeHeaderValue(value: String): String =
    value.replace("\"", "").filter { ch -> ch.code >= 0x20 && ch.code != 0x7F }

class JellyfinMediaServerAdapter(
    private val config: JellyfinConfig,
    private val httpClient: HttpClient = createJellyfinHttpClient(),
    private val eventSource: Flow<MediaServerEvent> = emptyFlow(),
    appVersion: String = "1.0.0",
) : MediaServerAdapter {
    override val serverType: String = PROVIDER_NAME

    private val logger = LoggerFactory.getLogger(JellyfinMediaServerAdapter::class.java)
    private val baseUrl: String = config.baseUrl.trimEnd('/')
    private val deviceId: String = config.clientIdentifier?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DEVICE_ID
    private val authHeaderValue: String = buildMediaBrowserAuthHeader(config.apiKey, deviceId, appVersion)

    /** Each library's title item types, by section key, as of the last [fetchLibrarySections] */
    private val sectionTitleTypes = ConcurrentHashMap<String, String>()

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> =
        executeJsonGet<JellyfinSystemInfoDto>(
            url = "$baseUrl/System/Info",
            operation = "check server health",
            notFoundResource = "Jellyfin system info",
        ).map(JellyfinMapper::mapServerIdentity)

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> =
        executeJsonGet<List<JellyfinSessionDto>>(
            url = "$baseUrl/Sessions",
            operation = "fetch active sessions",
            notFoundResource = "Jellyfin sessions",
        ).map(JellyfinMapper::mapSessions)

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> =
        executeJsonGet<List<JellyfinUserDto>>(
            url = "$baseUrl/Users",
            operation = "fetch users",
            notFoundResource = "Jellyfin users",
        ).map(JellyfinMapper::mapUsers)

    override suspend fun fetchUserLibraryAccess(): Either<MiruError, List<MediaUserLibraryAccess>> =
        executeJsonGet<List<JellyfinUserDto>>(
            url = "$baseUrl/Users",
            operation = "fetch user library access",
            notFoundResource = "Jellyfin users",
        ).map(JellyfinMapper::mapUserLibraryAccess)

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> =
        either {
            val folders =
                executeJsonGet<List<JellyfinVirtualFolderDto>>(
                    url = "$baseUrl/Library/VirtualFolders",
                    operation = "fetch library sections",
                    notFoundResource = "Jellyfin virtual folders",
                ).bind()
            val collectionTypes = folders.associate { it.itemId?.trim().orEmpty() to it.collectionType }

            JellyfinMapper.mapLibrarySections(folders).map { section ->
                val collectionType = collectionTypes[section.sectionKey]
                if (JellyfinMapper.isMixedCollection(collectionType)) {
                    val (type, titleTypes) = classifyMixedLibrary(section.sectionKey)
                    sectionTitleTypes[section.sectionKey] = titleTypes
                    section.copy(type = type)
                } else {
                    sectionTitleTypes[section.sectionKey] = JellyfinMapper.titleItemTypes(collectionType)
                    section
                }
            }
        }

    /**
     * A mixed library holding only shows is counted like a TV library (series, seasons, episodes) and
     * one holding only movies like a movie library, as Jellyfin counts them. One holding both counts
     * its movies and series as titles.
     */
    private suspend fun classifyMixedLibrary(sectionKey: String): Pair<LibrarySectionType, String> {
        val series = countItems(sectionKey, "Series")
        val movies = countItems(sectionKey, "Movie")
        if (series.isLeft() || movies.isLeft()) {
            logger.warn("Could not inspect mixed Jellyfin library {}; counting its movies and series", sectionKey)
            return LibrarySectionType.OTHER to MIXED_TITLE_ITEM_TYPES
        }
        val seriesCount = series.getOrNull() ?: 0
        val movieCount = movies.getOrNull() ?: 0
        return when {
            seriesCount > 0 && movieCount == 0 -> LibrarySectionType.SHOW to "Series"
            movieCount > 0 && seriesCount == 0 -> LibrarySectionType.MOVIE to "Movie"
            else -> LibrarySectionType.OTHER to MIXED_TITLE_ITEM_TYPES
        }
    }

    /** The item types to use for a library when no type is requested; see [JellyfinMapper.titleItemTypes] */
    private suspend fun titleTypesFor(sectionKey: String): String {
        sectionTitleTypes[sectionKey]?.let { return it }
        fetchLibrarySections()
        return sectionTitleTypes[sectionKey] ?: JellyfinMapper.GENERIC_TITLE_ITEM_TYPES
    }

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> =
        either {
            validateId(sectionKey, "sectionKey").bind()
            val includeTypes = mapIncludeItemTypes(mediaTypeCode).bind() ?: titleTypesFor(sectionKey)
            countItems(sectionKey, includeTypes).bind()
        }

    private suspend fun countItems(
        sectionKey: String,
        includeTypes: String,
    ): Either<MiruError, Int> =
        executeJsonGet<JellyfinItemsQueryResponse>(
            url = "$baseUrl/Items",
            operation = "fetch library count",
            notFoundResource = "Library section",
            notFoundId = sectionKey,
        ) {
            parameter("ParentId", sectionKey)
            parameter("Recursive", true)
            parameter("StartIndex", 0)
            parameter("Limit", 0)
            parameter("IncludeItemTypes", includeTypes)
        }.map { it.totalRecordCount ?: 0 }

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> =
        either {
            validateId(sectionKey, "sectionKey").bind()
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
            val includeTypes = mapIncludeItemTypes(mediaTypeCode).bind() ?: titleTypesFor(sectionKey)
            val response =
                executeJsonGet<JellyfinItemsQueryResponse>(
                    url = "$baseUrl/Items",
                    operation = "fetch library items",
                    notFoundResource = "Library section",
                    notFoundId = sectionKey,
                ) {
                    parameter("ParentId", sectionKey)
                    parameter("Recursive", true)
                    parameter("StartIndex", start)
                    parameter("Limit", size)
                    parameter("Fields", ITEM_QUERY_FIELDS)
                    parameter("IncludeItemTypes", includeTypes)
                }.bind()

            JellyfinMapper.mapLibraryItemsPage(
                sectionKey = sectionKey,
                requestedStart = start,
                response = response,
            )
        }

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> =
        either {
            validateId(ratingKey, "ratingKey").bind()
            val item =
                executeJsonGet<JellyfinBaseItemDto>(
                    url = "$baseUrl/Items/$ratingKey",
                    operation = "fetch media details",
                    notFoundResource = "Media",
                    notFoundId = ratingKey,
                ) {
                    parameter("Fields", DETAILS_QUERY_FIELDS)
                }.bind()

            val mediaType = JellyfinMapper.mapMediaType(item)
            val (parentSeason, grandparentSeries, ancestors) =
                coroutineScope {
                    val seasonDeferred =
                        if (mediaType == MediaType.EPISODE) {
                            async { fetchOptionalItem(item.seasonId) }
                        } else {
                            null
                        }
                    val seriesDeferred =
                        when (mediaType) {
                            MediaType.EPISODE -> async { fetchOptionalItem(item.seriesId) }
                            MediaType.SEASON -> async { fetchOptionalItem(item.seriesId ?: item.parentId) }
                            else -> null
                        }
                    val ancestorsDeferred = async { fetchOptionalAncestors(ratingKey) }
                    Triple(seasonDeferred?.await(), seriesDeferred?.await(), ancestorsDeferred.await())
                }

            val librarySection = resolveLibraryAncestor(ancestors)
            JellyfinMapper.mapMediaItemDetails(
                item = item,
                parentSeason = parentSeason,
                grandparentSeries = grandparentSeries,
                librarySectionId = librarySection?.id ?: item.parentId,
                librarySectionTitle = librarySection?.name,
            )
        }

    override suspend fun fetchMediaSectionKey(ratingKey: String): Either<MiruError, String?> =
        either {
            validateId(ratingKey, "ratingKey").bind()
            val ancestors =
                executeJsonGet<List<JellyfinBaseItemDto>>(
                    url = "$baseUrl/Items/$ratingKey/Ancestors",
                    operation = "fetch media section",
                    notFoundResource = "Media",
                    notFoundId = ratingKey,
                ).fold(
                    ifLeft = { err ->
                        if (err is MiruError.NotFound) {
                            return@fold emptyList()
                        }
                        raise(err)
                    },
                    ifRight = { it },
                )
            resolveLibraryAncestor(ancestors)?.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: fetchOptionalItem(ratingKey)?.parentId?.trim()?.takeIf { it.isNotEmpty() }
        }

    override fun mediaArtworkPath(
        ratingKey: String,
        isBackdrop: Boolean,
    ): String = "/Items/$ratingKey/Images/${if (isBackdrop) "Backdrop" else "Primary"}"

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> =
        either {
            validateArtworkParams(path = path, width = width, height = height).bind()
            val response =
                executeRequest(
                    url = "$baseUrl$path",
                    acceptHeader = ARTWORK_ACCEPT_HEADER,
                    operation = "fetch artwork",
                    notFoundResource = "Artwork",
                    notFoundId = path,
                ) {
                    if (width != null) {
                        parameter("maxWidth", width)
                    }
                    if (height != null) {
                        parameter("maxHeight", height)
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
                            message = "Failed to read artwork bytes from Jellyfin: ${sanitizeMessage(e.message)}",
                            cause = e,
                        ),
                    )
                }

            val contentType = response.contentType()?.withoutParameters()?.toString() ?: DEFAULT_ARTWORK_CONTENT_TYPE
            ArtworkData(bytes = bytes, contentType = contentType)
        }

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> =
        either {
            val (trimmedSessionId, trimmedReason) = validateTerminateParams(sessionId, reason).bind()
            if (trimmedReason.isNotEmpty()) {
                sendSessionMessageBestEffort(trimmedSessionId, trimmedReason)
            }
            executeRequest(
                url = "$baseUrl/Sessions/$trimmedSessionId/Playing/Stop",
                acceptHeader = ContentType.Application.Json.toString(),
                operation = "terminate session",
                notFoundResource = "Stream session",
                notFoundId = trimmedSessionId,
                method = HttpMethod.Post,
            ).bind()
        }

    override fun events(): Flow<MediaServerEvent> = eventSource

    private suspend fun sendSessionMessageBestEffort(
        sessionId: String,
        reason: String,
    ) {
        executeRequest(
            url = "$baseUrl/Sessions/$sessionId/Message",
            acceptHeader = ContentType.Application.Json.toString(),
            operation = "send termination message",
            notFoundResource = "Stream session",
            notFoundId = sessionId,
            method = HttpMethod.Post,
        ) {
            contentType(ContentType.Application.Json)
            setBody(JellyfinMessageRequest(header = "Miru", text = reason))
        }.onLeft { err ->
            logger.debug("Best-effort Jellyfin session message failed for '{}': {}", sessionId, sanitizeMessage(err.message))
        }
    }

    private suspend fun fetchOptionalItem(itemId: String?): JellyfinBaseItemDto? {
        val key = itemId?.trim()?.takeIf { it.isNotEmpty() && ID_REGEX.matches(it) } ?: return null
        return executeJsonGet<JellyfinBaseItemDto>(
            url = "$baseUrl/Items/$key",
            operation = "fetch parent media details",
            notFoundResource = "Media",
            notFoundId = key,
        ) {
            parameter("Fields", DETAILS_QUERY_FIELDS)
        }.fold(
            ifLeft = { error ->
                logger.debug("Optional Jellyfin item lookup for '{}' failed: {}", key, sanitizeMessage(error.message))
                null
            },
            ifRight = { it },
        )
    }

    private suspend fun fetchOptionalAncestors(itemId: String): List<JellyfinBaseItemDto> =
        executeJsonGet<List<JellyfinBaseItemDto>>(
            url = "$baseUrl/Items/$itemId/Ancestors",
            operation = "fetch item ancestors",
            notFoundResource = "Media ancestors",
            notFoundId = itemId,
        ).fold(
            ifLeft = { error ->
                logger.debug("Optional Jellyfin ancestors lookup for '{}' failed: {}", itemId, sanitizeMessage(error.message))
                emptyList()
            },
            ifRight = { it },
        )

    private fun resolveLibraryAncestor(ancestors: List<JellyfinBaseItemDto>): JellyfinBaseItemDto? =
        ancestors.firstOrNull { ancestor ->
            ancestor.type.equals("CollectionFolder", ignoreCase = true) ||
                ancestor.type.equals("UserView", ignoreCase = true)
        }

    private fun mapIncludeItemTypes(mediaTypeCode: Int?): Either<MiruError.ValidationError, String?> =
        when (mediaTypeCode) {
            null -> null.right()
            PLEX_TYPE_MOVIE -> "Movie".right()
            PLEX_TYPE_SHOW -> "Series".right()
            PLEX_TYPE_SEASON -> "Season".right()
            PLEX_TYPE_EPISODE -> "Episode".right()
            PLEX_TYPE_ARTIST -> "MusicArtist".right()
            PLEX_TYPE_ALBUM -> "MusicAlbum".right()
            PLEX_TYPE_TRACK -> "Audio".right()
            else ->
                if (mediaTypeCode > 0) {
                    null.right()
                } else {
                    MiruError.ValidationError(
                        message = "Invalid media type code",
                        fieldErrors = mapOf("mediaTypeCode" to "must be greater than 0"),
                    ).left()
                }
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
                            message = "Failed reading Jellyfin response during $operation: ${sanitizeMessage(e.message)}",
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
                        message = "Invalid JSON payload from Jellyfin during $operation",
                        cause = e,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                raise(
                    MiruError.UpstreamUnavailable(
                        provider = PROVIDER_NAME,
                        message = "Malformed payload from Jellyfin during $operation",
                        cause = e,
                    ),
                )
            }
        }

    @Suppress("LongParameterList")
    private suspend inline fun executeRequest(
        url: String,
        acceptHeader: String,
        operation: String,
        notFoundResource: String,
        notFoundId: String? = null,
        method: HttpMethod = HttpMethod.Get,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): Either<MiruError, HttpResponse> {
        val response =
            try {
                httpClient.request(url) {
                    this.method = method
                    header(HttpHeaders.Authorization, authHeaderValue)
                    header(HttpHeaders.Accept, acceptHeader)
                    block()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                return mapErrorStatus(e.response.status, operation, notFoundResource, notFoundId).left()
            } catch (e: Exception) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = PROVIDER_NAME,
                        message = "Jellyfin request failed during $operation: ${sanitizeMessage(e.message)}",
                        cause = e,
                    ).left()
            }

        return if (response.status.isSuccess()) {
            response.right()
        } else {
            mapErrorStatus(response.status, operation, notFoundResource, notFoundId).left()
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
                    message = "Jellyfin rejected authentication during $operation (HTTP ${status.value})",
                )

            HttpStatusCode.NotFound ->
                MiruError.NotFound(
                    resource = notFoundResource,
                    id = notFoundId,
                )

            else ->
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER_NAME,
                    message = "Jellyfin returned HTTP ${status.value} during $operation",
                )
        }

    private fun validateId(
        id: String,
        fieldName: String,
    ): Either<MiruError.ValidationError, Unit> =
        if (id.isNotBlank() && ID_REGEX.matches(id)) {
            Unit.right()
        } else {
            MiruError
                .ValidationError(
                    message = "Invalid $fieldName",
                    fieldErrors = mapOf(fieldName to "must match ^[A-Za-z0-9_-]+$"),
                ).left()
        }

    private fun validateArtworkParams(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError.ValidationError, Unit> =
        either {
            val errors = linkedMapOf<String, String>()
            if (!isSafeJellyfinImagePath(path)) {
                errors["path"] = "must be a safe relative Jellyfin image path"
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

    private fun isSafeJellyfinImagePath(path: String): Boolean {
        if (path.isBlank() || path.contains('\\')) {
            return false
        }
        if (path.contains("..") || path.lowercase().contains("%2e%2e")) {
            return false
        }
        if (path.any { ch -> ch.code < ASCII_SPACE || ch.code == ASCII_DEL }) {
            return false
        }
        return SAFE_IMAGE_PATH_REGEX.matches(path)
    }

    private fun validateTerminateParams(
        sessionId: String,
        reason: String,
    ): Either<MiruError.ValidationError, Pair<String, String>> =
        either {
            val trimmedSessionId = sessionId.trim()
            val trimmedReason = reason.trim()
            val errors = linkedMapOf<String, String>()
            if (trimmedSessionId.isEmpty() ||
                trimmedSessionId.length > MAX_TERMINATION_SESSION_ID_LENGTH ||
                !ID_REGEX.matches(trimmedSessionId)
            ) {
                errors["sessionId"] = "must be a valid Jellyfin session identifier"
            }
            if (trimmedReason.length > MAX_TERMINATION_REASON_LENGTH || hasControlChars(reason)) {
                errors["reason"] =
                    "must be at most $MAX_TERMINATION_REASON_LENGTH characters without control characters"
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid stream termination parameters",
                    fieldErrors = errors,
                )
            }
            trimmedSessionId to trimmedReason
        }

    private fun hasControlChars(value: String): Boolean =
        value.any { ch -> ch.code < ASCII_SPACE || ch.code == ASCII_DEL }

    private fun sanitizeMessage(raw: String?): String {
        val text = raw?.takeIf { it.isNotBlank() } ?: "unexpected upstream error"
        return if (config.apiKey.isNotEmpty()) {
            text.replace(config.apiKey, "***")
        } else {
            text
        }
    }

    companion object {
        const val PROVIDER_NAME = "jellyfin"
        private const val DEFAULT_DEVICE_ID = "miru"
        private const val ARTWORK_ACCEPT_HEADER = "image/*, */*;q=0.8"
        private const val DEFAULT_ARTWORK_CONTENT_TYPE = "application/octet-stream"
        private const val ITEM_QUERY_FIELDS = "MediaSources,MediaStreams,DateCreated,Overview,Genres,Studios,ParentId"
        private const val DETAILS_QUERY_FIELDS =
            "Overview,Genres,Studios,People,MediaSources,MediaStreams,DateCreated,ChildCount,RecursiveItemCount,ParentId"
        private const val ASCII_SPACE = 0x20
        private const val ASCII_DEL = 0x7F
        private const val MAX_TERMINATION_SESSION_ID_LENGTH = 256
        private const val MAX_TERMINATION_REASON_LENGTH = 500
        private const val MIXED_TITLE_ITEM_TYPES = "Movie,Series"
        private const val PLEX_TYPE_MOVIE = 1
        private const val PLEX_TYPE_SHOW = 2
        private const val PLEX_TYPE_SEASON = 3
        private const val PLEX_TYPE_EPISODE = 4
        private const val PLEX_TYPE_ARTIST = 8
        private const val PLEX_TYPE_ALBUM = 9
        private const val PLEX_TYPE_TRACK = 10
        private val ID_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val SAFE_IMAGE_PATH_REGEX =
            Regex("^/(?:Items|Users)/[A-Za-z0-9_-]+/Images/[A-Za-z0-9_-]+(?:/[0-9]+)?$")
    }
}
