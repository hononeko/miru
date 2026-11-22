package app.hononeko.miru.http

import kotlinx.serialization.Serializable

@Serializable
enum class PlexCredentialSource {
    ENV_OVERRIDE,
    STORED_ENCRYPTED,
    NOT_CONFIGURED,
}

@Serializable
enum class PlexOAuthPinStatus {
    PENDING,
    AUTHORIZED,
    EXPIRED,
}

@Serializable
data class PlexConnectionStatus(
    val configured: Boolean,
    val credentialSource: PlexCredentialSource,
    val encryptionKeyConfigured: Boolean,
    val serverName: String? = null,
    val machineId: String? = null,
    val baseUrl: String? = null,
    val reachable: Boolean,
    val statusMessage: String? = null,
)

@Serializable
data class PlexOAuthStartResponse(
    val pinId: Long,
    val authUrl: String,
    val expiresAtMs: Long,
)

@Serializable
data class PlexOAuthPollResponse(
    val pinId: Long,
    val status: PlexOAuthPinStatus,
    val expiresAtMs: Long,
)

@Serializable
data class PlexConnectionOptionDto(
    val uri: String,
    val protocol: String,
    val local: Boolean,
    val relay: Boolean,
    val ipv6: Boolean,
)

@Serializable
data class PlexDiscoveredServerDto(
    val name: String,
    val machineId: String,
    val productVersion: String? = null,
    val platform: String? = null,
    val owned: Boolean,
    val presence: Boolean,
    val connections: List<PlexConnectionOptionDto>,
)

@Serializable
data class PlexDiscoveredServersResponse(
    val pinId: Long,
    val servers: List<PlexDiscoveredServerDto>,
)

@Serializable
data class PlexSelectServerRequest(
    val pinId: Long? = null,
    val machineId: String,
    val connectionUri: String,
)

@Serializable
data class PlexSelectServerResponse(
    val saved: Boolean,
    val hotReloaded: Boolean,
    val envOverrideActive: Boolean,
    val serverName: String,
    val machineId: String,
    val baseUrl: String,
    val credentialSource: PlexCredentialSource,
)
