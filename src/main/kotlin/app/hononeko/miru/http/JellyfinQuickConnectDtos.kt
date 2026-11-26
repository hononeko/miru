package app.hononeko.miru.http

import kotlinx.serialization.Serializable

@Serializable
enum class JellyfinQuickConnectStatus {
    PENDING,
    AUTHORIZED,
    EXPIRED,
    NOT_ADMIN,
    FAILED,
}

@Serializable
data class JellyfinConnectionStatus(
    val configured: Boolean,
    val credentialSource: PlexCredentialSource,
    val encryptionKeyConfigured: Boolean,
    val serverName: String? = null,
    val machineId: String? = null,
    val baseUrl: String? = null,
    val version: String? = null,
    val reachable: Boolean,
    val statusMessage: String? = null,
)

@Serializable
data class JellyfinQuickConnectStartRequest(
    val baseUrl: String,
)

@Serializable
data class JellyfinQuickConnectStartResponse(
    val attemptId: String,
    val code: String,
    val serverName: String,
    val serverVersion: String? = null,
    val expiresAtMs: Long,
) {
    override fun toString(): String =
        "JellyfinQuickConnectStartResponse(attemptId=$attemptId, code=***, serverName=$serverName, " +
            "serverVersion=$serverVersion, expiresAtMs=$expiresAtMs)"
}

@Serializable
data class JellyfinQuickConnectPollResponse(
    val attemptId: String,
    val status: JellyfinQuickConnectStatus,
    val expiresAtMs: Long,
    val message: String? = null,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val machineId: String? = null,
    val baseUrl: String? = null,
    val hotReloaded: Boolean? = null,
    val envOverrideActive: Boolean? = null,
    val credentialSource: PlexCredentialSource? = null,
)
