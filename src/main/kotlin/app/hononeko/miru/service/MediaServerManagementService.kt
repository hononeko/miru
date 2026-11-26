package app.hononeko.miru.service

import app.hononeko.miru.adapter.MediaServerRegistry
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.DisconnectServerResponse
import app.hononeko.miru.http.RemoveServerResponse
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.MediaServerRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.slf4j.LoggerFactory

/**
 * Coordinates disconnecting UI-configured media servers (stopping monitoring, closing active
 * sessions into history, revoking credentials where supported, and preserving historical data)
 * and permanently removing disconnected servers along with their cascaded data.
 */
class MediaServerManagementService(
    private val mediaServerRegistry: MediaServerRegistry,
    private val mediaServerRepository: MediaServerRepository,
    private val encryptionKey: String? = null,
    private val jellyfinRevokeApiKey: suspend (baseUrl: String, apiKey: String, deviceId: String) -> Either<MiruError, Unit> = {
        _,
        _,
        _,
        ->
        Either.Left(MiruError.UpstreamUnavailable("jellyfin", "Revocation client is not configured"))
    },
    private val jellyfinDefaultDeviceId: String = "miru",
    private val onServerDisconnected: suspend () -> Unit = {},
    private val onServerRemoved: suspend (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(MediaServerManagementService::class.java)

    suspend fun disconnectServer(
        principal: MiruPrincipal,
        serverId: String,
    ): Either<MiruError, DisconnectServerResponse> =
        either {
            val trimmedId = validateServerId(serverId).bind()
            val record =
                mediaServerRepository.findById(trimmedId).bind()
                    ?: raise(MiruError.NotFound(resource = "Media server", id = trimmedId))

            ensure(!mediaServerRegistry.isEnvManaged(record)) {
                MiruError.Conflict(
                    "Server '${record.name}' is managed via environment variables and cannot be disconnected from the Admin UI.",
                )
            }

            val runtimeActive = mediaServerRegistry.isRuntimeActiveForRecord(record)
            val hasStoredToken = !record.encryptedToken.isNullOrBlank()
            ensure(runtimeActive || hasStoredToken) {
                MiruError.Conflict("Server '${record.name}' is already disconnected.")
            }

            // Decrypt any Jellyfin API key before clearing DB storage; never log or serialize it.
            val encryptedToken = record.encryptedToken
            val jellyfinPlainApiKey =
                if (record.serverType.equals("jellyfin", ignoreCase = true) && !encryptedToken.isNullOrBlank()) {
                    CredentialCrypto.decrypt(encryptedToken, encryptionKey).getOrNull()
                } else {
                    null
                }

            val runtime = mediaServerRegistry.runtimeForServerType(record.serverType)
            val closedSessions =
                when {
                    runtimeActive && runtime != null -> runtime.disconnect(record.id).bind()
                    runtime != null -> runtime.sessionEngine.closeAllSessionsForServer(record.id).bind().size
                    else -> 0
                }

            mediaServerRepository.clearCredentials(record.id, clock()).bind()
            onServerDisconnected()

            val (credentialRevoked, revocationNotice) =
                revokeRemoteCredentialsBestEffort(record, jellyfinPlainApiKey)

            logger.info(
                "Admin action=disconnect admin={} serverId={} serverName={} serverType={} closedSessions={} credentialRevoked={}",
                principal.username,
                record.id,
                record.name,
                record.serverType.lowercase(),
                closedSessions,
                credentialRevoked,
            )

            DisconnectServerResponse(
                serverId = record.id,
                serverName = record.name,
                serverType = record.serverType.lowercase(),
                disconnected = true,
                closedSessions = closedSessions,
                credentialRevoked = credentialRevoked,
                revocationNotice = revocationNotice,
            )
        }

    suspend fun removeServer(
        principal: MiruPrincipal,
        serverId: String,
    ): Either<MiruError, RemoveServerResponse> =
        either {
            val trimmedId = validateServerId(serverId).bind()
            val record =
                mediaServerRepository.findById(trimmedId).bind()
                    ?: raise(MiruError.NotFound(resource = "Media server", id = trimmedId))

            ensure(!mediaServerRegistry.isEnvManaged(record)) {
                MiruError.Conflict(
                    "Server '${record.name}' is managed via environment variables and cannot be removed.",
                )
            }

            val runtimeActive = mediaServerRegistry.isRuntimeActiveForRecord(record)
            val hasStoredToken = !record.encryptedToken.isNullOrBlank()
            ensure(!runtimeActive && !hasStoredToken) {
                MiruError.Conflict("Disconnect server '${record.name}' before removing it and its data.")
            }

            val counts = mediaServerRepository.countServerData(record.id).bind()
            val deleted = mediaServerRepository.delete(record.id).bind()
            ensure(deleted) {
                MiruError.NotFound(resource = "Media server", id = trimmedId)
            }

            onServerRemoved(record.id)

            logger.info(
                "Admin action=remove admin={} serverId={} serverName={} serverType={} deletedPlays={} deletedUsers={} deletedLibraries={}",
                principal.username,
                record.id,
                record.name,
                record.serverType.lowercase(),
                counts.plays,
                counts.users,
                counts.libraries,
            )

            RemoveServerResponse(
                serverId = record.id,
                serverName = record.name,
                serverType = record.serverType.lowercase(),
                removed = true,
                deletedPlays = counts.plays,
                deletedUsers = counts.users,
                deletedLibraries = counts.libraries,
            )
        }

    private suspend fun revokeRemoteCredentialsBestEffort(
        record: MediaServerRecord,
        jellyfinPlainApiKey: String?,
    ): Pair<Boolean, String?> =
        when (record.serverType.lowercase()) {
            "jellyfin" -> {
                if (jellyfinPlainApiKey.isNullOrBlank()) {
                    false to
                        "Stored credentials were cleared locally, but the API key could not be decrypted to revoke remotely. " +
                        "Delete the \"Miru\" key manually under Jellyfin Dashboard -> API Keys."
                } else {
                    val deviceId = record.clientIdentifier?.takeIf { it.isNotBlank() } ?: jellyfinDefaultDeviceId
                    jellyfinRevokeApiKey(record.baseUrl, jellyfinPlainApiKey, deviceId).fold(
                        ifLeft = { err ->
                            logger.warn(
                                "Best-effort Jellyfin API key revocation failed for server '{}' ({}): {}",
                                record.name,
                                record.id,
                                redactSecret(err.message, jellyfinPlainApiKey),
                            )
                            false to
                                "Disconnected locally, but could not revoke the API key on Jellyfin. " +
                                "Delete the \"Miru\" key manually under Jellyfin Dashboard -> API Keys."
                        },
                        ifRight = {
                            true to "The Miru API key was revoked on Jellyfin."
                        },
                    )
                }
            }

            // Plex's official documentation (developer.plex.tv) does not expose a single-token revocation endpoint.
            "plex" ->
                false to
                    "Plex credentials were removed from Miru. To revoke Miru's access on your Plex account, " +
                    "remove Miru under Settings -> Authorized Devices at https://app.plex.tv."

            else -> false to null
        }

    private fun validateServerId(serverId: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = serverId.trim()
            ensure(trimmed.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Server ID must not be empty",
                    fieldErrors = mapOf("id" to "must not be empty"),
                )
            }
            trimmed
        }

    private fun redactSecret(
        raw: String,
        secret: String,
    ): String = if (secret.isNotBlank()) raw.replace(secret, "***") else raw
}
