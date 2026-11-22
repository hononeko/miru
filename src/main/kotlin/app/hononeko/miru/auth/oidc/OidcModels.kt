package app.hononeko.miru.auth.oidc

import app.hononeko.miru.auth.LoginResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OidcDiscoveryDocument(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String,
    @SerialName("userinfo_endpoint")
    val userinfoEndpoint: String? = null,
    @SerialName("jwks_uri")
    val jwksUri: String,
    @SerialName("end_session_endpoint")
    val endSessionEndpoint: String? = null,
    @SerialName("id_token_signing_alg_values_supported")
    val idTokenSigningAlgValuesSupported: List<String> = emptyList(),
)

@Serializable
data class JwkKey(
    val kty: String,
    val use: String? = null,
    val kid: String? = null,
    val alg: String? = null,
    val n: String? = null,
    val e: String? = null,
    val crv: String? = null,
    val x: String? = null,
    val y: String? = null,
)

@Serializable
data class JwkSet(
    val keys: List<JwkKey> = emptyList(),
)

@Serializable
data class OidcTokenResponse(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("token_type")
    val tokenType: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    val scope: String? = null,
) {
    override fun toString(): String =
        "OidcTokenResponse(accessToken=***, tokenType=$tokenType, expiresIn=$expiresIn, " +
            "idToken=***, refreshToken=***, scope=$scope)"
}

@Serializable
data class OidcErrorResponse(
    val error: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
)

data class OidcIdTokenClaims(
    val subject: String,
    val issuer: String,
    val audiences: List<String>,
    val preferredUsername: String? = null,
    val name: String? = null,
    val email: String? = null,
    val emailVerified: Boolean? = null,
    val groups: Set<String> = emptySet(),
    val issuedAtEpochSec: Long? = null,
    val expiresAtEpochSec: Long,
)

data class OidcPendingTransaction(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    override fun toString(): String =
        "OidcPendingTransaction(state=$state, nonce=***, codeVerifier=***, " +
            "createdAtMs=$createdAtMs, expiresAtMs=$expiresAtMs)"
}

data class OidcAuthorizationStart(
    val authorizationUrl: String,
    val state: String,
    val txCookieValue: String,
) {
    override fun toString(): String =
        "OidcAuthorizationStart(authorizationUrl=$authorizationUrl, state=$state, txCookieValue=***)"
}

data class OidcCallbackResult(
    val loginResult: LoginResult,
    val idTokenHint: String? = null,
) {
    override fun toString(): String =
        "OidcCallbackResult(loginResult=$loginResult, idTokenHint=${if (idTokenHint != null) "***" else "null"})"
}
