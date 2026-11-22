package app.hononeko.miru.adapter.plex

import app.hononeko.miru.MiruJson
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

private const val DEFAULT_PLEX_TV_BASE_URL = "https://plex.tv"
private const val PLEX_AUTH_BASE_URL = "https://app.plex.tv/auth"
private const val PLEX_TV_PROVIDER = "plex.tv"
private const val DEFAULT_PIN_TTL_MS = 15 * 60 * 1000L
private const val MILLIS_PER_SECOND = 1_000L

@Serializable
internal data class PlexPinDto(
    val id: Long,
    val code: String,
    val clientIdentifier: String? = null,
    val expiresIn: Long? = null,
    val expiresAt: String? = null,
    val authToken: String? = null,
) {
    override fun toString(): String =
        "PlexPinDto(id=$id, code=***, clientIdentifier=$clientIdentifier, expiresIn=$expiresIn, expiresAt=$expiresAt, " +
            "authToken=${if (authToken != null) "***" else "null"})"
}

@Serializable
internal data class PlexResourceDto(
    val name: String,
    val product: String? = null,
    val productVersion: String? = null,
    val platform: String? = null,
    val clientIdentifier: String,
    val provides: String? = null,
    val owned: FlexibleBoolean? = null,
    val accessToken: String? = null,
    val presence: FlexibleBoolean? = null,
    val connections: List<PlexResourceConnectionDto>? = null,
) {
    override fun toString(): String =
        "PlexResourceDto(name=$name, clientIdentifier=$clientIdentifier, provides=$provides, owned=$owned, " +
            "accessToken=${if (accessToken != null) "***" else "null"}, presence=$presence)"
}

@Serializable
internal data class PlexResourceConnectionDto(
    val protocol: String? = null,
    val address: String? = null,
    val port: Int? = null,
    val uri: String,
    val local: FlexibleBoolean? = null,
    val relay: FlexibleBoolean? = null,
    @SerialName("IPv6")
    val ipv6: FlexibleBoolean? = null,
)

data class PlexPinStart(
    val pinId: Long,
    val code: String,
    val authUrl: String,
    val expiresAtMs: Long,
) {
    override fun toString(): String = "PlexPinStart(pinId=$pinId, code=***, authUrl=***, expiresAtMs=$expiresAtMs)"
}

sealed interface PlexPinPollResult {
    data class Pending(
        val expiresAtMs: Long,
    ) : PlexPinPollResult

    data class Authorized(
        val authToken: String,
    ) : PlexPinPollResult {
        override fun toString(): String = "Authorized(authToken=***)"
    }

    data object Expired : PlexPinPollResult
}

data class DiscoveredPlexConnection(
    val uri: String,
    val protocol: String,
    val local: Boolean,
    val relay: Boolean,
    val ipv6: Boolean,
)

data class DiscoveredPlexServer(
    val name: String,
    val machineId: String,
    val productVersion: String?,
    val platform: String?,
    val owned: Boolean,
    val presence: Boolean,
    val accessToken: String?,
    val connections: List<DiscoveredPlexConnection>,
) {
    override fun toString(): String =
        "DiscoveredPlexServer(name=$name, machineId=$machineId, productVersion=$productVersion, platform=$platform, " +
            "owned=$owned, presence=$presence, accessToken=${if (accessToken != null) "***" else "null"}, " +
            "connections=${connections.size})"
}

/**
 * Client for Plex.tv PIN OAuth (`POST /api/v2/pins`, `GET /api/v2/pins/{id}`) and server resource discovery
 * (`GET /api/v2/resources`).
 */
class PlexOAuthClient(
    private val httpClient: HttpClient,
    plexTvBaseUrl: String = DEFAULT_PLEX_TV_BASE_URL,
    private val appVersion: String = "0.1.0",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val baseUrl: String = plexTvBaseUrl.trim().trimEnd('/')

    suspend fun createPin(clientIdentifier: String): Either<MiruError, PlexPinStart> =
        either {
            ensure(clientIdentifier.isNotBlank()) {
                MiruError.ValidationError(
                    message = "Plex client identifier must not be blank",
                    fieldErrors = mapOf("clientIdentifier" to "must not be blank"),
                )
            }

            val dto =
                executeJson<PlexPinDto>(
                    operation = "create Plex OAuth PIN",
                    secretToRedact = null,
                ) {
                    httpClient.post("$baseUrl/api/v2/pins") {
                        applyPlexHeaders(clientIdentifier)
                        parameter("strong", true)
                    }
                }.bind()

            val expiresAtMs = resolveExpiresAtMs(dto.expiresAt, dto.expiresIn)
            PlexPinStart(
                pinId = dto.id,
                code = dto.code,
                authUrl = buildAuthUrl(clientIdentifier = clientIdentifier, code = dto.code),
                expiresAtMs = expiresAtMs,
            )
        }

    suspend fun pollPin(
        pinId: Long,
        code: String,
        clientIdentifier: String,
    ): Either<MiruError, PlexPinPollResult> =
        either {
            val response =
                executeRaw(
                    operation = "poll Plex OAuth PIN",
                    secretToRedact = code,
                    allowNotFound = true,
                ) {
                    httpClient.get("$baseUrl/api/v2/pins/$pinId") {
                        applyPlexHeaders(clientIdentifier)
                        parameter("code", code)
                    }
                }.bind()

            if (response.status == HttpStatusCode.NotFound) {
                return@either PlexPinPollResult.Expired
            }

            val dto = parseJson<PlexPinDto>(response, "poll Plex OAuth PIN", secretToRedact = code).bind()
            val token = dto.authToken?.trim()?.takeIf { it.isNotEmpty() }
            if (token != null) {
                return@either PlexPinPollResult.Authorized(token)
            }

            val expiresAtMs = resolveExpiresAtMs(dto.expiresAt, dto.expiresIn)
            if (expiresAtMs <= clock() || (dto.expiresIn != null && dto.expiresIn <= 0L)) {
                PlexPinPollResult.Expired
            } else {
                PlexPinPollResult.Pending(expiresAtMs)
            }
        }

    suspend fun fetchResources(
        authToken: String,
        clientIdentifier: String,
    ): Either<MiruError, List<DiscoveredPlexServer>> =
        either {
            ensure(authToken.isNotBlank()) {
                MiruError.Unauthorized("Plex OAuth token is missing")
            }

            val resources =
                executeJson<List<PlexResourceDto>>(
                    operation = "fetch Plex server resources",
                    secretToRedact = authToken,
                ) {
                    httpClient.get("$baseUrl/api/v2/resources") {
                        applyPlexHeaders(clientIdentifier)
                        header(PLEX_TOKEN_HEADER, authToken)
                        parameter("includeHttps", 1)
                        parameter("includeRelay", 1)
                    }
                }.bind()

            resources
                .filter(::providesServer)
                .map { resource ->
                    DiscoveredPlexServer(
                        name = resource.name,
                        machineId = resource.clientIdentifier,
                        productVersion = resource.productVersion,
                        platform = resource.platform,
                        owned = resource.owned ?: false,
                        presence = resource.presence ?: true,
                        accessToken = resource.accessToken?.trim()?.takeIf { it.isNotEmpty() },
                        connections = mapAndSortConnections(resource.connections.orEmpty()),
                    )
                }
        }

    private fun HttpRequestBuilder.applyPlexHeaders(clientIdentifier: String) {
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        header("X-Plex-Product", "Miru")
        header("X-Plex-Version", appVersion)
        header("X-Plex-Client-Identifier", clientIdentifier)
        header("X-Plex-Device", "Miru Server")
        header("X-Plex-Platform", "Web")
    }

    private suspend inline fun <reified T> executeJson(
        operation: String,
        secretToRedact: String?,
        crossinline request: suspend () -> HttpResponse,
    ): Either<MiruError, T> =
        either {
            val response =
                executeRaw(
                    operation = operation,
                    secretToRedact = secretToRedact,
                    allowNotFound = false,
                    request = request,
                ).bind()
            parseJson<T>(response, operation, secretToRedact).bind()
        }

    private suspend inline fun executeRaw(
        operation: String,
        secretToRedact: String?,
        allowNotFound: Boolean,
        crossinline request: suspend () -> HttpResponse,
    ): Either<MiruError, HttpResponse> {
        val response =
            try {
                request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                if (allowNotFound && e.response.status == HttpStatusCode.NotFound) {
                    return e.response.right()
                }
                return mapErrorStatus(e.response.status, operation).left()
            } catch (e: Exception) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = PLEX_TV_PROVIDER,
                        message = "Plex.tv request failed during $operation: ${sanitizeMessage(e.message, secretToRedact)}",
                        cause = e,
                    ).left()
            }

        return if (response.status.isSuccess() || (allowNotFound && response.status == HttpStatusCode.NotFound)) {
            response.right()
        } else {
            mapErrorStatus(response.status, operation).left()
        }
    }

    private suspend inline fun <reified T> parseJson(
        response: HttpResponse,
        operation: String,
        secretToRedact: String?,
    ): Either<MiruError, T> {
        val bodyText =
            try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = PLEX_TV_PROVIDER,
                        message = "Failed to read Plex.tv response during $operation: ${sanitizeMessage(e.message, secretToRedact)}",
                        cause = e,
                    ).left()
            }

        return try {
            MiruJson.decodeFromString<T>(bodyText).right()
        } catch (e: SerializationException) {
            MiruError
                .UpstreamUnavailable(
                    provider = PLEX_TV_PROVIDER,
                    message = "Invalid JSON payload from Plex.tv during $operation",
                    cause = e,
                ).left()
        } catch (e: IllegalArgumentException) {
            MiruError
                .UpstreamUnavailable(
                    provider = PLEX_TV_PROVIDER,
                    message = "Malformed payload from Plex.tv during $operation",
                    cause = e,
                ).left()
        }
    }

    private fun mapErrorStatus(
        status: HttpStatusCode,
        operation: String,
    ): MiruError =
        when (status) {
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            ->
                MiruError.UpstreamAuthError(
                    provider = PLEX_TV_PROVIDER,
                    message = "Plex.tv rejected authentication during $operation (HTTP ${status.value})",
                )

            HttpStatusCode.NotFound ->
                MiruError.NotFound(
                    resource = "Plex.tv resource",
                    message = "Plex.tv resource not found during $operation",
                )

            else ->
                MiruError.UpstreamUnavailable(
                    provider = PLEX_TV_PROVIDER,
                    message = "Plex.tv returned HTTP ${status.value} during $operation",
                )
        }

    private fun resolveExpiresAtMs(
        expiresAt: String?,
        expiresInSeconds: Long?,
    ): Long {
        val parsed =
            expiresAt
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() }
        if (parsed != null) return parsed
        val ttlMs = expiresInSeconds?.let { it * MILLIS_PER_SECOND } ?: DEFAULT_PIN_TTL_MS
        return clock() + ttlMs
    }

    private fun buildAuthUrl(
        clientIdentifier: String,
        code: String,
    ): String {
        val encodedClientId = urlEncode(clientIdentifier)
        val encodedCode = urlEncode(code)
        return "$PLEX_AUTH_BASE_URL#?clientID=$encodedClientId&code=$encodedCode&context%5Bdevice%5D%5Bproduct%5D=Miru"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun providesServer(resource: PlexResourceDto): Boolean =
        resource.provides
            ?.split(',')
            ?.any { it.trim().equals("server", ignoreCase = true) }
            ?: false

    private fun mapAndSortConnections(connections: List<PlexResourceConnectionDto>): List<DiscoveredPlexConnection> =
        connections
            .mapNotNull { conn ->
                val normalizedUri = conn.uri.trim().trimEnd('/')
                if (!isValidHttpUri(normalizedUri)) return@mapNotNull null
                val protocol =
                    conn.protocol?.trim()?.lowercase()?.takeIf { it == "http" || it == "https" }
                        ?: if (normalizedUri.startsWith("https://", ignoreCase = true)) "https" else "http"
                DiscoveredPlexConnection(
                    uri = normalizedUri,
                    protocol = protocol,
                    local = conn.local ?: false,
                    relay = conn.relay ?: false,
                    ipv6 = conn.ipv6 ?: false,
                )
            }.distinctBy { it.uri }
            .sortedWith(
                compareBy<DiscoveredPlexConnection>(
                    { connectionTier(it) },
                    { if (it.protocol == "https") 0 else 1 },
                    { if (it.ipv6) 1 else 0 },
                    { it.uri },
                ),
            )

    private fun connectionTier(conn: DiscoveredPlexConnection): Int =
        when {
            conn.relay -> TIER_RELAY
            conn.local -> TIER_LOCAL_LAN
            else -> TIER_DIRECT_REMOTE
        }

    private fun isValidHttpUri(raw: String): Boolean =
        runCatching {
            val uri = URI(raw)
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
        }.getOrDefault(false)

    private fun sanitizeMessage(
        raw: String?,
        secretToRedact: String?,
    ): String {
        val text = raw?.takeIf { it.isNotBlank() } ?: "unexpected upstream error"
        return if (!secretToRedact.isNullOrEmpty()) {
            text.replace(secretToRedact, "***")
        } else {
            text
        }
    }

    private companion object {
        const val PLEX_TOKEN_HEADER = "X-Plex-Token"
        const val TIER_LOCAL_LAN = 0
        const val TIER_DIRECT_REMOTE = 1
        const val TIER_RELAY = 2
    }
}
