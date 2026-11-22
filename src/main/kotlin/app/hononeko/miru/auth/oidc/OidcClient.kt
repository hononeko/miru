package app.hononeko.miru.auth.oidc

import app.hononeko.miru.MiruJson
import app.hononeko.miru.config.OidcConfig
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.Closeable

class OidcClient(
    private val config: OidcConfig,
    private val jwtVerifier: OidcJwtVerifier = OidcJwtVerifier(),
    engine: HttpClientEngine = CIO.create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val discoveryCacheTtlMs: Long = DEFAULT_DISCOVERY_CACHE_TTL_MS,
) : Closeable {
    private val httpClient =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
                connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            }
        }

    private val discoveryMutex = Mutex()
    private var cachedDiscovery: CachedDiscovery? = null

    private val jwksMutex = Mutex()
    private var cachedJwks: JwkSet? = null

    override fun close() {
        httpClient.close()
    }

    suspend fun getDiscovery(): Either<MiruError, OidcDiscoveryDocument> =
        discoveryMutex.withLock {
            val now = clock()
            val current = cachedDiscovery
            if (current != null && now - current.fetchedAtMs < discoveryCacheTtlMs) {
                return@withLock Either.Right(current.document)
            }

            fetchDiscovery().onRight { doc ->
                cachedDiscovery = CachedDiscovery(document = doc, fetchedAtMs = now)
            }
        }

    suspend fun verifyIdToken(
        idToken: String,
        expectedNonce: String?,
    ): Either<MiruError, OidcIdTokenClaims> =
        either {
            val discovery = getDiscovery().bind()
            val initialJwks = getOrFetchJwks(discovery.jwksUri, forceRefresh = false).bind()

            val firstAttempt =
                jwtVerifier.verifyDetailed(
                    idToken = idToken,
                    jwkSet = initialJwks,
                    expectedIssuer = discovery.issuer,
                    expectedClientId = config.clientId,
                    expectedNonce = expectedNonce,
                    groupsClaim = config.groupsClaim,
                )

            when (firstAttempt) {
                is Either.Right -> firstAttempt.value
                is Either.Left -> {
                    when (val failure = firstAttempt.value) {
                        is JwtVerificationFailure.UnknownKeyId -> {
                            val refreshedJwks = getOrFetchJwks(discovery.jwksUri, forceRefresh = true).bind()
                            jwtVerifier
                                .verify(
                                    idToken = idToken,
                                    jwkSet = refreshedJwks,
                                    expectedIssuer = discovery.issuer,
                                    expectedClientId = config.clientId,
                                    expectedNonce = expectedNonce,
                                    groupsClaim = config.groupsClaim,
                                ).bind()
                        }

                        is JwtVerificationFailure.Rejected -> raise(failure.error)
                    }
                }
            }
        }

    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
    ): Either<MiruError, OidcTokenResponse> =
        either {
            val discovery = getDiscovery().bind()
            val response =
                try {
                    httpClient.submitForm(
                        url = discovery.tokenEndpoint,
                        formParameters =
                            parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", config.redirectUri)
                                append("client_id", config.clientId)
                                append("code_verifier", codeVerifier)
                                if (config.clientSecret.isNotBlank()) {
                                    append("client_secret", config.clientSecret)
                                }
                            },
                    ) {
                        header(HttpHeaders.Accept, "application/json")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = redactSecrets("Failed to reach OIDC token endpoint: ${e.message ?: "network error"}"),
                            cause = e,
                        ),
                    )
                }

            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val parsedError = runCatching { MiruJson.decodeFromString<OidcErrorResponse>(body) }.getOrNull()
                val detail =
                    parsedError?.error?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${response.status.value}"
                raise(
                    MiruError.UpstreamAuthError(
                        provider = PROVIDER_NAME,
                        message = redactSecrets("OIDC token exchange failed ($detail)"),
                    ),
                )
            }

            try {
                MiruJson.decodeFromString<OidcTokenResponse>(body)
            } catch (_: Exception) {
                raise(
                    MiruError.UpstreamAuthError(
                        provider = PROVIDER_NAME,
                        message = "OIDC token endpoint returned invalid JSON",
                    ),
                )
            }
        }

    suspend fun fetchUserInfo(accessToken: String): Either<MiruError, JsonObject?> =
        either {
            val discovery = getDiscovery().bind()
            val userinfoEndpoint = discovery.userinfoEndpoint?.takeIf { it.isNotBlank() } ?: return@either null

            val response =
                try {
                    httpClient.get(userinfoEndpoint) {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header(HttpHeaders.Accept, "application/json")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = redactSecrets("Failed to reach OIDC userinfo endpoint: ${e.message ?: "network error"}", accessToken),
                            cause = e,
                        ),
                    )
                }

            if (!response.status.isSuccess()) {
                raise(
                    MiruError.UpstreamAuthError(
                        provider = PROVIDER_NAME,
                        message = "OIDC userinfo endpoint returned HTTP ${response.status.value}",
                    ),
                )
            }

            try {
                MiruJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (_: Exception) {
                raise(
                    MiruError.UpstreamAuthError(
                        provider = PROVIDER_NAME,
                        message = "OIDC userinfo endpoint returned invalid JSON",
                    ),
                )
            }
        }

    private suspend fun fetchDiscovery(): Either<MiruError, OidcDiscoveryDocument> =
        either {
            val discoveryUrl = "${config.issuerUrl.trimEnd('/')}/.well-known/openid-configuration"
            val response =
                try {
                    httpClient.get(discoveryUrl) {
                        header(HttpHeaders.Accept, "application/json")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = redactSecrets("Failed to fetch OIDC discovery metadata: ${e.message ?: "network error"}"),
                            cause = e,
                        ),
                    )
                }

            ensure(response.status.isSuccess()) {
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER_NAME,
                    message = "OIDC discovery endpoint returned HTTP ${response.status.value}",
                )
            }

            val document =
                try {
                    MiruJson.decodeFromString<OidcDiscoveryDocument>(response.bodyAsText())
                } catch (_: Exception) {
                    raise(
                        MiruError.UpstreamAuthError(
                            provider = PROVIDER_NAME,
                            message = "OIDC discovery endpoint returned invalid JSON",
                        ),
                    )
                }

            ensure(document.issuer.trimEnd('/') == config.issuerUrl.trimEnd('/')) {
                MiruError.UpstreamAuthError(
                    provider = PROVIDER_NAME,
                    message = "OIDC discovery issuer does not match configured OIDC_ISSUER_URL",
                )
            }
            document
        }

    private suspend fun getOrFetchJwks(
        jwksUri: String,
        forceRefresh: Boolean,
    ): Either<MiruError, JwkSet> =
        jwksMutex.withLock {
            if (!forceRefresh) {
                cachedJwks?.let { return@withLock Either.Right(it) }
            }

            fetchJwks(jwksUri).onRight { jwks ->
                cachedJwks = jwks
            }
        }

    private suspend fun fetchJwks(jwksUri: String): Either<MiruError, JwkSet> =
        either {
            val response =
                try {
                    httpClient.get(jwksUri) {
                        header(HttpHeaders.Accept, "application/json")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER_NAME,
                            message = redactSecrets("Failed to fetch OIDC JWKS: ${e.message ?: "network error"}"),
                            cause = e,
                        ),
                    )
                }

            ensure(response.status.isSuccess()) {
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER_NAME,
                    message = "OIDC JWKS endpoint returned HTTP ${response.status.value}",
                )
            }

            try {
                MiruJson.decodeFromString<JwkSet>(response.bodyAsText())
            } catch (_: Exception) {
                raise(
                    MiruError.UpstreamAuthError(
                        provider = PROVIDER_NAME,
                        message = "OIDC JWKS endpoint returned invalid JSON",
                    ),
                )
            }
        }

    private fun redactSecrets(
        message: String,
        extraSecret: String? = null,
    ): String {
        var sanitized = message
        if (config.clientSecret.isNotBlank()) {
            sanitized = sanitized.replace(config.clientSecret, "***")
        }
        if (!extraSecret.isNullOrBlank()) {
            sanitized = sanitized.replace(extraSecret, "***")
        }
        return sanitized
    }

    private data class CachedDiscovery(
        val document: OidcDiscoveryDocument,
        val fetchedAtMs: Long,
    )

    companion object {
        private const val PROVIDER_NAME = "oidc"
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        const val DEFAULT_DISCOVERY_CACHE_TTL_MS = 60L * 60L * 1000L
    }
}
