package app.hononeko.miru.http

import kotlinx.serialization.Serializable

@Serializable
data class MediaServerSummaryDto(
    val id: String,
    val name: String,
    val serverType: String,
    val configured: Boolean,
    val reachable: Boolean,
    val websocketEnabled: Boolean,
    val websocketState: String,
)

@Serializable
data class MediaServersResponse(
    val servers: List<MediaServerSummaryDto>,
)

@Serializable
data class AdminServerStatusDto(
    val id: String? = null,
    val name: String,
    val serverType: String,
    val configured: Boolean,
    val credentialSource: PlexCredentialSource,
    val machineId: String? = null,
    val baseUrl: String? = null,
    val reachable: Boolean,
    val websocketEnabled: Boolean,
    val websocketState: String,
    val statusMessage: String? = null,
    val lastCheckedAtMs: Long? = null,
)

@Serializable
data class AdminServersResponse(
    val servers: List<AdminServerStatusDto>,
)
