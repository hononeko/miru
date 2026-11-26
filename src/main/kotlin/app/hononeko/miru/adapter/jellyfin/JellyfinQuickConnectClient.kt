package app.hononeko.miru.adapter.jellyfin

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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val quickConnectLogger = LoggerFactory.getLogger("app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectClient")

data class JellyfinPublicServerInfo(
    val id: String,
    val serverName: String,
    val version: String?,
)

data class JellyfinQuickConnectInitiateResult(
    val secret: String,
    val code: String,
    val dateAddedMs: Long?,
) {
    override fun toString(): String =
        "JellyfinQuickConnectInitiateResult(secret=***, code=***, dateAddedMs=$dateAddedMs)"
}

sealed interface JellyfinQuickConnectPollOutcome {
    data object Pending : JellyfinQuickConnectPollOutcome

    data object Authenticated : JellyfinQuickConnectPollOutcome

    data object Expired : JellyfinQuickConnectPollOutcome
}

data class JellyfinQuickConnectAuthSession(
    val accessToken: String,
    val userId: String?,
    val userName: String?,
    val isAdministrator: Boolean,
) {
    override fun toString(): String =
        "JellyfinQuickConnectAuthSession(accessToken=***, userId=$userId, userName=$userName, isAdministrator=$isAdministrator)"
}

data class JellyfinCreatedAuthKey(
    val accessToken: String,
    val appName: String,
    /** Null when Jellyfin sends no timestamp Miru can read; only used to order keys */
    val dateCreatedMs: Long?,
) {
    override fun toString(): String =
        "JellyfinCreatedAuthKey(accessToken=***, appName=$appName, dateCreatedMs=$dateCreatedMs)"
}

/**
 * Client for Jellyfin Quick Connect endpoints (`/System/Info/Public`, `/QuickConnect/Enabled`,
 * `/QuickConnect/Initiate`, `/QuickConnect/Connect`, `/Users/AuthenticateWithQuickConnect`,
 * `/Auth/Keys`, and `/Sessions/Logout`).
 */
class JellyfinQuickConnectClient(
    private val httpClientProvider: () -> HttpClient,
    private val appVersion: String = "1.0.0",
) {
    constructor(
        httpClient: HttpClient,
        appVersion: String = "1.0.0",
    ) : this({ httpClient }, appVersion)

    private val httpClient: HttpClient
        get() = httpClientProvider()

    suspend fun fetchPublicSystemInfo(baseUrl: String): Either<MiruError, JellyfinPublicServerInfo> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val response =
                executeRaw(
                    operation = "inspect Jellyfin public system info",
                    secretsToRedact = emptyList(),
                    allowNotFound = true,
                ) {
                    httpClient.get("$normalizedBaseUrl/System/Info/Public") {
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                }.bind()

            if (response.status == HttpStatusCode.NotFound) {
                raise(notJellyfinServerError())
            }

            val dto =
                parseJson<JellyfinSystemInfoDto>(
                    response = response,
                    operation = "inspect Jellyfin public system info",
                    secretsToRedact = emptyList(),
                    onInvalidPayload = { notJellyfinServerError() },
                ).bind()

            val id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: raise(notJellyfinServerError())
            val serverName = dto.serverName?.trim()?.takeIf { it.isNotEmpty() } ?: raise(notJellyfinServerError())
            val version = dto.version?.trim()?.takeIf { it.isNotEmpty() }

            JellyfinPublicServerInfo(
                id = id,
                serverName = serverName,
                version = version,
            )
        }

    suspend fun isQuickConnectEnabled(baseUrl: String): Either<MiruError, Boolean> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val response =
                executeRaw(
                    operation = "check Jellyfin Quick Connect status",
                    secretsToRedact = emptyList(),
                    allowNotFound = true,
                ) {
                    httpClient.get("$normalizedBaseUrl/QuickConnect/Enabled") {
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                }.bind()

            if (response.status == HttpStatusCode.NotFound) {
                return@either false
            }

            parseJson<Boolean>(
                response = response,
                operation = "check Jellyfin Quick Connect status",
                secretsToRedact = emptyList(),
            ).bind()
        }

    suspend fun initiateQuickConnect(
        baseUrl: String,
        deviceId: String,
    ): Either<MiruError, JellyfinQuickConnectInitiateResult> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val dto =
                executeJson<JellyfinQuickConnectResultDto>(
                    operation = "initiate Jellyfin Quick Connect",
                    secretsToRedact = emptyList(),
                ) {
                    httpClient.post("$normalizedBaseUrl/QuickConnect/Initiate") {
                        header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(null, deviceId, appVersion))
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                }.bind()

            val secret = dto.secret?.trim()?.takeIf { it.isNotEmpty() }
            val code = dto.code?.trim()?.takeIf { it.isNotEmpty() }
            ensure(secret != null && code != null) {
                MiruError.UpstreamUnavailable(
                    provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                    message = "Jellyfin Quick Connect initiation response was missing secret or code",
                )
            }

            JellyfinQuickConnectInitiateResult(
                secret = secret,
                code = code,
                dateAddedMs = parseIsoTimestampMs(dto.dateAdded),
            )
        }

    suspend fun checkQuickConnect(
        baseUrl: String,
        secret: String,
        deviceId: String,
    ): Either<MiruError, JellyfinQuickConnectPollOutcome> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val response =
                executeRaw(
                    operation = "poll Jellyfin Quick Connect",
                    secretsToRedact = listOf(secret),
                    allowNotFound = true,
                ) {
                    httpClient.get("$normalizedBaseUrl/QuickConnect/Connect") {
                        header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(null, deviceId, appVersion))
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        parameter("secret", secret)
                    }
                }.bind()

            if (response.status == HttpStatusCode.NotFound) {
                return@either JellyfinQuickConnectPollOutcome.Expired
            }

            val dto =
                parseJson<JellyfinQuickConnectResultDto>(
                    response = response,
                    operation = "poll Jellyfin Quick Connect",
                    secretsToRedact = listOf(secret),
                ).bind()

            if (dto.authenticated == true) {
                JellyfinQuickConnectPollOutcome.Authenticated
            } else {
                JellyfinQuickConnectPollOutcome.Pending
            }
        }

    suspend fun authenticateWithQuickConnect(
        baseUrl: String,
        secret: String,
        deviceId: String,
    ): Either<MiruError, JellyfinQuickConnectAuthSession> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val dto =
                executeJson<JellyfinAuthenticationResultDto>(
                    operation = "authenticate with Jellyfin Quick Connect",
                    secretsToRedact = listOf(secret),
                ) {
                    httpClient.post("$normalizedBaseUrl/Users/AuthenticateWithQuickConnect") {
                        header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(null, deviceId, appVersion))
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        contentType(ContentType.Application.Json)
                        setBody(JellyfinQuickConnectAuthRequest(secret = secret))
                    }
                }.bind()

            val accessToken = dto.accessToken?.trim()?.takeIf { it.isNotEmpty() }
            ensure(accessToken != null) {
                MiruError.UpstreamAuthError(
                    provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                    message = "Jellyfin authentication response did not include an access token",
                )
            }

            JellyfinQuickConnectAuthSession(
                accessToken = accessToken,
                userId = dto.user?.id?.trim()?.takeIf { it.isNotEmpty() },
                userName = dto.user?.name?.trim()?.takeIf { it.isNotEmpty() },
                isAdministrator = dto.user?.policy?.isAdministrator == true,
            )
        }

    suspend fun createApiKey(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
        appName: String = MIRU_APP_NAME,
    ): Either<MiruError, Unit> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            executeRaw(
                operation = "create Jellyfin API key",
                secretsToRedact = listOf(accessToken),
                allowNotFound = false,
            ) {
                httpClient.post("$normalizedBaseUrl/Auth/Keys") {
                    header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(accessToken, deviceId, appVersion))
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    parameter("app", appName)
                }
            }.bind()
        }

    suspend fun listApiKeys(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
    ): Either<MiruError, List<JellyfinCreatedAuthKey>> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val response =
                executeJson<JellyfinAuthKeysQueryResponse>(
                    operation = "list Jellyfin API keys",
                    secretsToRedact = listOf(accessToken),
                ) {
                    httpClient.get("$normalizedBaseUrl/Auth/Keys") {
                        header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(accessToken, deviceId, appVersion))
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                }.bind()

            // Jellyfin builds this list from its API key table and fills in only AppName, AccessToken
            // and DateCreated (AuthenticationManager.GetApiKeys in 10.11): Id is 0 and IsActive is
            // false for every key, and a deleted key is simply absent. Those fields carry no
            // information here, so they are ignored rather than used as filters.
            response.items.orEmpty().mapNotNull { item ->
                val token = item.accessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val app = item.appName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                JellyfinCreatedAuthKey(
                    accessToken = token,
                    appName = app,
                    dateCreatedMs = parseIsoTimestampMs(item.dateCreated),
                )
            }
        }

    suspend fun logoutSessionBestEffort(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
    ) {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        try {
            val response =
                httpClient.post("$normalizedBaseUrl/Sessions/Logout") {
                    header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(accessToken, deviceId, appVersion))
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
            if (!response.status.isSuccess()) {
                quickConnectLogger.debug("Best-effort Jellyfin temporary session logout returned HTTP {}", response.status.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            quickConnectLogger.debug(
                "Best-effort Jellyfin temporary session logout failed: {}",
                sanitizeMessage(e.message, listOf(accessToken)),
            )
        }
    }

    suspend fun deleteApiKey(
        baseUrl: String,
        workingApiKey: String,
        keyToDelete: String,
        deviceId: String,
    ): Either<MiruError, Unit> =
        either {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val encodedKey = URLEncoder.encode(keyToDelete, StandardCharsets.UTF_8).replace("+", "%20")
            val secretsToRedact = listOf(workingApiKey, keyToDelete, encodedKey)
            val response =
                try {
                    httpClient.delete("$normalizedBaseUrl/Auth/Keys/$encodedKey") {
                        header(HttpHeaders.Authorization, buildMediaBrowserAuthHeader(workingApiKey, deviceId, appVersion))
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ResponseException) {
                    if (e.response.status == HttpStatusCode.NotFound) {
                        return@either
                    }
                    raise(mapErrorStatus(e.response.status, "revoke Jellyfin API key"))
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                            message = "Failed to revoke Jellyfin API key: ${sanitizeMessage(e.message, secretsToRedact)}",
                            cause = e,
                        ),
                    )
                }

            if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
                raise(mapErrorStatus(response.status, "revoke Jellyfin API key"))
            }
        }

    suspend fun deleteApiKeyBestEffort(
        baseUrl: String,
        workingApiKey: String,
        keyToDelete: String,
        deviceId: String,
    ) {
        deleteApiKey(
            baseUrl = baseUrl,
            workingApiKey = workingApiKey,
            keyToDelete = keyToDelete,
            deviceId = deviceId,
        ).onLeft { err ->
            val encodedKey = URLEncoder.encode(keyToDelete, StandardCharsets.UTF_8).replace("+", "%20")
            quickConnectLogger.debug(
                "Best-effort Jellyfin API key deletion failed: {}",
                sanitizeMessage(err.message, listOf(workingApiKey, keyToDelete, encodedKey)),
            )
        }
    }

    private suspend inline fun <reified T> executeJson(
        operation: String,
        secretsToRedact: List<String>,
        crossinline request: suspend () -> HttpResponse,
    ): Either<MiruError, T> =
        either {
            val response =
                executeRaw(
                    operation = operation,
                    secretsToRedact = secretsToRedact,
                    allowNotFound = false,
                    request = request,
                ).bind()
            parseJson<T>(response, operation, secretsToRedact).bind()
        }

    private suspend inline fun executeRaw(
        operation: String,
        secretsToRedact: List<String>,
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
                        provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                        message = "Jellyfin request failed during $operation: ${sanitizeMessage(e.message, secretsToRedact)}",
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
        secretsToRedact: List<String>,
        noinline onInvalidPayload: (() -> MiruError)? = null,
    ): Either<MiruError, T> {
        val bodyText =
            try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                        message = "Failed reading Jellyfin response during $operation: ${sanitizeMessage(e.message, secretsToRedact)}",
                        cause = e,
                    ).left()
            }

        return try {
            MiruJson.decodeFromString<T>(bodyText).right()
        } catch (e: SerializationException) {
            (
                onInvalidPayload?.invoke()
                    ?: MiruError.UpstreamUnavailable(
                        provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                        message = "Invalid JSON payload from Jellyfin during $operation",
                        cause = e,
                    )
            ).left()
        } catch (e: IllegalArgumentException) {
            (
                onInvalidPayload?.invoke()
                    ?: MiruError.UpstreamUnavailable(
                        provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                        message = "Malformed payload from Jellyfin during $operation",
                        cause = e,
                    )
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
                    provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                    message = "Jellyfin rejected authentication during $operation (HTTP ${status.value})",
                )

            HttpStatusCode.NotFound ->
                MiruError.NotFound(
                    resource = "Jellyfin Quick Connect resource",
                    message = "Jellyfin resource not found during $operation",
                )

            else ->
                MiruError.UpstreamUnavailable(
                    provider = JellyfinMediaServerAdapter.PROVIDER_NAME,
                    message = "Jellyfin returned HTTP ${status.value} during $operation",
                )
        }

    private fun notJellyfinServerError(): MiruError.ValidationError =
        MiruError.ValidationError(
            message = "The specified URL did not respond as a Jellyfin server",
            fieldErrors = mapOf("baseUrl" to "did not return valid Jellyfin /System/Info/Public metadata"),
        )

    private fun parseIsoTimestampMs(raw: String?): Long? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(trimmed).toEpochMilli() }
            // Jellyfin stores UTC but can serialize a date read back from its database without an offset
            .recoverCatching { LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()
    }

    private fun sanitizeMessage(
        raw: String?,
        secretsToRedact: List<String>,
    ): String {
        var text = raw?.takeIf { it.isNotBlank() } ?: "unexpected upstream error"
        for (secret in secretsToRedact) {
            if (secret.isNotEmpty()) {
                text = text.replace(secret, "***")
                val encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8)
                if (encoded.isNotEmpty()) {
                    text = text.replace(encoded, "***")
                }
            }
        }
        return text
    }

    companion object {
        const val MIRU_APP_NAME = "Miru"
    }
}
