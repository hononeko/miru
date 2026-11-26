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
data class ServerDataSummaryDto(
    val plays: Long,
    val users: Long,
    val libraries: Long,
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
    val canDisconnect: Boolean = false,
    val canRemove: Boolean = false,
    val dataSummary: ServerDataSummaryDto? = null,
)

@Serializable
data class AdminServersResponse(
    val servers: List<AdminServerStatusDto>,
)

@Serializable
data class DisconnectServerResponse(
    val serverId: String,
    val serverName: String,
    val serverType: String,
    val disconnected: Boolean,
    val closedSessions: Int,
    val credentialRevoked: Boolean,
    val revocationNotice: String? = null,
)

@Serializable
data class RemoveServerResponse(
    val serverId: String,
    val serverName: String,
    val serverType: String,
    val removed: Boolean,
    val deletedPlays: Long,
    val deletedUsers: Long,
    val deletedLibraries: Long,
)
