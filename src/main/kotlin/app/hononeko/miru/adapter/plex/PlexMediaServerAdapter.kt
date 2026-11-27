package app.hononeko.miru.adapter.plex

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaTechnicalSpecs
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.MediaUserLibraryAccess
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.config.PlexConfig
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
private const val SOURCE_SPECS_CACHE_SIZE = 512
private const val SOURCE_SPECS_TTL_MS = 60L * 60L * 1000L
private const val SOURCE_SPECS_RETRY_MS = 5L * 60L * 1000L
private const val LRU_LOAD_FACTOR = 0.75f

private data class CachedSourceSpecs(
    val versions: List<Pair<String?, MediaTechnicalSpecs>>?,
    val fetchedAtMs: Long,
)
private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L

fun createPlexHttpClient(
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

class PlexMediaServerAdapter(
    private val config: PlexConfig,
    private val httpClient: HttpClient = createPlexHttpClient(),
    plexTvBaseUrl: String? = null,
    private val eventSource: Flow<MediaServerEvent> = emptyFlow(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MediaServerAdapter {
    override val serverType: String = PROVIDER_NAME

    private val logger = LoggerFactory.getLogger(PlexMediaServerAdapter::class.java)
    private val baseUrl: String = config.baseUrl.trimEnd('/')
    private val normalizedPlexTvBaseUrl: String? =
        plexTvBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private val ownerMutex = Mutex()
    private val machineIdMutex = Mutex()
    private val sourceSpecsMutex = Mutex()
    private val sourceSpecsCache =
        object : LinkedHashMap<String, CachedSourceSpecs>(SOURCE_SPECS_CACHE_SIZE, LRU_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSourceSpecs>?): Boolean =
                size > SOURCE_SPECS_CACHE_SIZE
        }

    @Volatile
    private var cachedOwner: MediaUser? = null

    @Volatile
    private var cachedMachineId: String? = null

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> =
        executeJsonGet<PlexServerInfoResponse>(
            url = "$baseUrl/",
            operation = "check server health",
            notFoundResource = "Plex server root",
        ).map { response ->
            PlexMapper.mapServerIdentity(response.mediaContainer).also { identity ->
                val machineId = identity.machineIdentifier.trim()
                if (machineId.isNotEmpty() && machineId != "unknown") {
                    cachedMachineId = machineId
                }
            }
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

            val mapped =
                items.map { item ->
                    PlexMapper.mapSession(metadata = item, ownerUser = ownerUser) to PlexMapper.selectedMediaId(item)
                }
            coroutineScope {
                mapped.map { (snapshot, mediaId) -> async { withSourceSpecs(snapshot, mediaId) } }.awaitAll()
            }
        }

    /**
     * Fills in the file's container and codecs for a session Plex is converting, whose session data describes
     * the output. Each item is looked up from /library/metadata and cached (matching the playing Media version
     * when an item has multiple versions); entries expire after an hour, failed lookups are retried after five
     * minutes, and sessions that already have all source fields skip the lookup.
     */
    private suspend fun withSourceSpecs(
        snapshot: ActiveStreamSnapshot,
        mediaId: String?,
    ): ActiveStreamSnapshot {
        val tech = snapshot.technical
        if (tech.decision == StreamDecision.DIRECT_PLAY) return snapshot
        if (tech.container != null && tech.videoCodec != null && tech.audioCodec != null) return snapshot
        val versions = sourceSpecs(snapshot.media.ratingKey) ?: return snapshot
        val source =
            versions.firstOrNull { mediaId != null && it.first == mediaId }?.second
                ?: versions.firstOrNull()?.second
                ?: return snapshot
        return snapshot.copy(
            technical =
                tech.copy(
                    container = tech.container ?: source.container?.takeIf { it.isNotBlank() },
                    videoCodec = tech.videoCodec ?: source.videoCodec?.takeIf { it.isNotBlank() },
                    audioCodec = tech.audioCodec ?: source.audioCodec?.takeIf { it.isNotBlank() },
                ),
        )
    }

    private suspend fun sourceSpecs(ratingKey: String): List<Pair<String?, MediaTechnicalSpecs>>? {
        val now = clock()
        sourceSpecsMutex.withLock {
            val cached = sourceSpecsCache[ratingKey]
            val ttl = if (cached?.versions != null) SOURCE_SPECS_TTL_MS else SOURCE_SPECS_RETRY_MS
            if (cached != null && now - cached.fetchedAtMs < ttl) {
                return cached.versions
            }
        }
        val versions = fetchSourceSpecs(ratingKey)
        sourceSpecsMutex.withLock { sourceSpecsCache[ratingKey] = CachedSourceSpecs(versions, clock()) }
        return versions
    }

    private suspend fun fetchSourceSpecs(ratingKey: String): List<Pair<String?, MediaTechnicalSpecs>>? {
        val key = ratingKey.trim().takeIf { SECTION_KEY_REGEX.matches(it) } ?: return null
        return executeJsonGet<PlexLibraryItemsResponse>(
            url = "$baseUrl/library/metadata/$key",
            operation = "fetch source media",
            notFoundResource = "Media",
            notFoundId = key,
        ).fold(
            ifLeft = { error ->
                logger.debug("Source media lookup for '{}' failed: {}", key, sanitizeMessage(error.message))
                null
            },
            ifRight = { response ->
                response.mediaContainer.metadata
                    ?.firstOrNull()
                    ?.let { PlexMapper.mapTechnicalSpecsByMediaId(it) }
                    ?.takeIf { it.isNotEmpty() }
            },
        )
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

    override suspend fun fetchUserLibraryAccess(): Either<MiruError, List<MediaUserLibraryAccess>> =
        either {
            val accessByUserId = linkedMapOf<String, MediaUserLibraryAccess>()
            val owner = resolveOwnerBestEffort()
            val ownerId = owner?.externalId?.trim()?.takeIf { it.isNotEmpty() }
            if (ownerId != null) {
                accessByUserId[ownerId] =
                    MediaUserLibraryAccess(
                        externalUserId = ownerId,
                        allLibraries = true,
                        sectionKeys = emptySet(),
                    )
            }

            val tvBase = normalizedPlexTvBaseUrl ?: return@either accessByUserId.values.toList()
            val machineId = resolveMachineIdentifier().bind()
            ensure(MACHINE_ID_REGEX.matches(machineId)) {
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER_NAME,
                    message = "Plex server returned an invalid machine identifier",
                )
            }

            val response =
                executeRequest(
                    url = "$tvBase/api/servers/$machineId/shared_servers",
                    acceptHeader = ContentType.Application.Xml.toString(),
                    operation = "fetch shared library permissions",
                    notFoundResource = "Plex shared servers",
                    notFoundId = machineId,
                ) {
                    applyPlexTvHeaders()
                }.bind()

            val bodyText =
                try {
                    response.bodyAsText()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = "Failed reading Plex.tv shared servers response: ${sanitizeMessage(e.message)}",
                            cause = e,
                        ),
                    )
                }

            val parsedShares =
                try {
                    PlexTvSharedServersXmlParser.parse(bodyText)
                } catch (e: IllegalArgumentException) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = "Malformed Plex.tv shared servers XML payload: ${sanitizeMessage(e.message)}",
                            cause = e,
                        ),
                    )
                }

            for (entry in parsedShares) {
                val existing = accessByUserId[entry.externalUserId]
                accessByUserId[entry.externalUserId] =
                    if (existing == null) {
                        entry
                    } else {
                        val combinedAll = existing.allLibraries || entry.allLibraries
                        MediaUserLibraryAccess(
                            externalUserId = entry.externalUserId,
                            allLibraries = combinedAll,
                            sectionKeys = if (combinedAll) emptySet() else existing.sectionKeys + entry.sectionKeys,
                        )
                    }
            }

            accessByUserId.values.toList()
        }

    private suspend fun resolveMachineIdentifier(): Either<MiruError, String> {
        cachedMachineId?.let { return it.right() }
        return machineIdMutex.withLock {
            cachedMachineId?.let { return@withLock it.right() }
            checkHealth().map { identity -> identity.machineIdentifier.trim() }
        }
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

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> =
        either {
            validateRatingKey(ratingKey).bind()

            val response =
                executeJsonGet<PlexLibraryItemsResponse>(
                    url = "$baseUrl/library/metadata/$ratingKey",
                    operation = "fetch media details",
                    notFoundResource = "Media",
                    notFoundId = ratingKey,
                ).bind()

            val primary =
                response.mediaContainer.metadata?.firstOrNull()
                    ?: raise(MiruError.NotFound(resource = "Media", id = ratingKey))

            val mediaType = PlexMapper.mapMediaType(primary)
            val (parentSeason, grandparentSeries) =
                when (mediaType) {
                    MediaType.EPISODE ->
                        coroutineScope {
                            val seasonDeferred = async { fetchOptionalMetadata(primary.parentRatingKey) }
                            val seriesDeferred = async { fetchOptionalMetadata(primary.grandparentRatingKey) }
                            seasonDeferred.await() to seriesDeferred.await()
                        }

                    MediaType.SEASON -> null to fetchOptionalMetadata(primary.parentRatingKey)
                    else -> null to null
                }

            PlexMapper.mapMediaItemDetails(
                metadata = primary,
                parentSeason = parentSeason,
                grandparentSeries = grandparentSeries,
            )
        }

    override suspend fun fetchMediaSectionKey(ratingKey: String): Either<MiruError, String?> =
        either {
            validateRatingKey(ratingKey).bind()

            val response =
                executeJsonGet<PlexLibraryItemsResponse>(
                    url = "$baseUrl/library/metadata/$ratingKey",
                    operation = "fetch media section",
                    notFoundResource = "Media",
                    notFoundId = ratingKey,
                ).bind()

            val primary =
                response.mediaContainer.metadata?.firstOrNull()
                    ?: raise(MiruError.NotFound(resource = "Media", id = ratingKey))

            primary.librarySectionID?.trim()?.takeIf { it.isNotEmpty() }
        }

    private suspend fun fetchOptionalMetadata(ratingKey: String?): PlexMetadataDto? {
        val key = ratingKey?.trim()?.takeIf { it.isNotEmpty() && SECTION_KEY_REGEX.matches(it) } ?: return null
        return executeJsonGet<PlexLibraryItemsResponse>(
            url = "$baseUrl/library/metadata/$key",
            operation = "fetch parent media metadata",
            notFoundResource = "Media",
            notFoundId = key,
        ).fold(
            ifLeft = { error ->
                logger.debug("Optional parent metadata lookup for '{}' failed: {}", key, sanitizeMessage(error.message))
                null
            },
            ifRight = { it.mediaContainer.metadata?.firstOrNull() },
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
            readArtwork(response).bind()
        }

    /** Plex's documented photo transcoder (`GET /photo/:/transcode`) fetches [source] itself and scales it. */
    override suspend fun fetchScaledImage(
        source: String,
        maxDimension: Int,
    ): Either<MiruError, ArtworkData> =
        either {
            val trimmedSource = source.trim()
            val remote = trimmedSource.startsWith("https://", ignoreCase = true)
            ensure((remote || isSafeArtworkPath(trimmedSource)) && maxDimension > 0) {
                MiruError.ValidationError(
                    message = "Invalid image scaling request parameters",
                    fieldErrors =
                        mapOf(
                            "source" to "must be a relative Plex path or an absolute HTTPS URL",
                            "maxDimension" to "must be greater than 0",
                        ),
                )
            }
            val response =
                executeRequest(
                    url = "$baseUrl/photo/:/transcode",
                    acceptHeader = ARTWORK_ACCEPT_HEADER,
                    operation = "scale image",
                    notFoundResource = "Artwork",
                    notFoundId = if (remote) "remote image" else trimmedSource,
                ) {
                    parameter("url", trimmedSource)
                    parameter("width", maxDimension)
                    parameter("height", maxDimension)
                    parameter("format", "png")
                }.bind()
            readArtwork(response).bind()
        }

    private suspend fun readArtwork(response: HttpResponse): Either<MiruError, ArtworkData> =
        either {
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

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> =
        either {
            val (trimmedSessionId, trimmedReason) = validateTerminateParams(sessionId, reason).bind()
            executeRequest(
                url = "$baseUrl/status/sessions/terminate",
                acceptHeader = ContentType.Application.Json.toString(),
                operation = "terminate session",
                notFoundResource = "Stream session",
                notFoundId = trimmedSessionId,
                method = HttpMethod.Post,
                mapStatus = ::mapTerminationRefusal,
            ) {
                parameter("sessionId", trimmedSessionId)
                parameter("reason", trimmedReason)
            }.bind()
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
                if (normalizedPlexTvBaseUrl == null || resolved.externalId != LOCAL_OWNER_USER_ID) {
                    cachedOwner = resolved
                }
            }
        }
    }

    private suspend fun fetchOptionalPlexTvUser(): PlexTvUserDto? {
        val tvBase = normalizedPlexTvBaseUrl ?: return null
        return executeJsonGet<PlexTvUserDto>(
            url = "$tvBase/api/v2/user",
            operation = "fetch plex.tv user",
            notFoundResource = "Plex.tv user",
        ) {
            applyPlexTvHeaders()
        }.fold(
            ifLeft = { error ->
                logger.warn("Optional plex.tv user lookup failed: {}", sanitizeMessage(error.message))
                null
            },
            ifRight = { it },
        )
    }

    private suspend fun fetchOptionalPlexTvFriends(): List<PlexTvFriendDto> {
        val tvBase = normalizedPlexTvBaseUrl ?: return emptyList()
        return executeRequest(
            url = "$tvBase/api/users",
            acceptHeader = ContentType.Application.Xml.toString(),
            operation = "fetch plex.tv users",
            notFoundResource = "Plex.tv users",
        ) {
            applyPlexTvHeaders()
        }.fold(
            ifLeft = { error ->
                logger.warn("Optional plex.tv users lookup failed: {}", sanitizeMessage(error.message))
                emptyList()
            },
            ifRight = { response ->
                try {
                    val bodyText = response.bodyAsText()
                    PlexTvUsersXmlParser.parse(bodyText)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Failed to parse plex.tv users XML: {}", sanitizeMessage(e.message))
                    emptyList()
                }
            },
        )
    }

    private fun HttpRequestBuilder.applyPlexTvHeaders() {
        val clientId = config.clientIdentifier?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLIENT_IDENTIFIER
        header(PLEX_CLIENT_ID_HEADER, clientId)
        header(PLEX_PRODUCT_HEADER, PLEX_PRODUCT_NAME)
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

    @Suppress("LongParameterList")
    private suspend inline fun executeRequest(
        url: String,
        acceptHeader: String,
        operation: String,
        notFoundResource: String,
        notFoundId: String? = null,
        method: HttpMethod = HttpMethod.Get,
        noinline mapStatus: (HttpStatusCode) -> MiruError? = { null },
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): Either<MiruError, HttpResponse> {
        val response =
            try {
                httpClient.request(url) {
                    this.method = method
                    header(PLEX_TOKEN_HEADER, config.token)
                    header(HttpHeaders.Accept, acceptHeader)
                    block()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                val error =
                    mapStatus(e.response.status)
                        ?: mapErrorStatus(
                            status = e.response.status,
                            operation = operation,
                            notFoundResource = notFoundResource,
                            notFoundId = notFoundId,
                        )
                return error.left()
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
            val error =
                mapStatus(response.status)
                    ?: mapErrorStatus(
                        status = response.status,
                        operation = operation,
                        notFoundResource = notFoundResource,
                        notFoundId = notFoundId,
                    )
            error.left()
        }
    }

    /**
     * Statuses that Plex documents for `POST /status/sessions/terminate` with a meaning of their own:
     * 401 is "Server does not have the feature enabled" and 403 is "sessionId is empty".
     */
    private fun mapTerminationRefusal(status: HttpStatusCode): MiruError? =
        when (status) {
            HttpStatusCode.Unauthorized ->
                MiruError.UpstreamAuthError(
                    provider = PROVIDER_NAME,
                    message = "Plex refused to terminate the session: the server does not have the feature enabled (HTTP 401)",
                    detail = TERMINATION_NOT_ENABLED_DETAIL,
                )

            HttpStatusCode.Forbidden ->
                MiruError.UpstreamAuthError(
                    provider = PROVIDER_NAME,
                    message = "Plex refused to terminate the session: it did not accept the session ID (HTTP 403)",
                    detail = TERMINATION_SESSION_REJECTED_DETAIL,
                )

            else -> null
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

    private fun validateRatingKey(ratingKey: String): Either<MiruError.ValidationError, Unit> =
        if (ratingKey.isNotBlank() && SECTION_KEY_REGEX.matches(ratingKey)) {
            Unit.right()
        } else {
            MiruError
                .ValidationError(
                    message = "Invalid media ratingKey",
                    fieldErrors = mapOf("ratingKey" to "must match ^[A-Za-z0-9_-]+$"),
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
                hasControlChars(sessionId)
            ) {
                errors["sessionId"] =
                    "must be 1..$MAX_TERMINATION_SESSION_ID_LENGTH characters without control characters"
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
        return if (config.token.isNotEmpty()) {
            text.replace(config.token, "***")
        } else {
            text
        }
    }

    companion object {
        const val DEFAULT_PLEX_TV_BASE_URL = "https://plex.tv"
        private const val PROVIDER_NAME = "plex"
        private const val PLEX_TOKEN_HEADER = "X-Plex-Token"
        private const val PLEX_CLIENT_ID_HEADER = "X-Plex-Client-Identifier"
        private const val PLEX_PRODUCT_HEADER = "X-Plex-Product"
        private const val DEFAULT_CLIENT_IDENTIFIER = "miru"
        private const val PLEX_PRODUCT_NAME = "Miru"
        private const val LOCAL_OWNER_USER_ID = "1"
        private const val ARTWORK_ACCEPT_HEADER = "image/*, */*;q=0.8"
        private const val DEFAULT_ARTWORK_CONTENT_TYPE = "application/octet-stream"
        private const val ASCII_SPACE = 0x20
        private const val ASCII_DEL = 0x7F
        private const val MAX_TERMINATION_SESSION_ID_LENGTH = 256
        private const val MAX_TERMINATION_REASON_LENGTH = 500
        private const val TERMINATION_NOT_ENABLED_DETAIL =
            "Plex refused to stop the stream because the server does not have this feature enabled. " +
                "Check that the server owner has Plex Pass and that Miru is connected with the owner's account."
        private const val TERMINATION_SESSION_REJECTED_DETAIL =
            "Plex refused to stop the stream because it did not accept its session ID."
        private val SECTION_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val MACHINE_ID_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }
}
