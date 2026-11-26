package app.hononeko.miru.adapter.plex

import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.PlexConnectionOptionDto
import app.hononeko.miru.http.PlexConnectionStatus
import app.hononeko.miru.http.PlexCredentialSource
import app.hononeko.miru.http.PlexDiscoveredServerDto
import app.hononeko.miru.http.PlexDiscoveredServersResponse
import app.hononeko.miru.http.PlexOAuthPinStatus
import app.hononeko.miru.http.PlexOAuthPollResponse
import app.hononeko.miru.http.PlexOAuthStartResponse
import app.hononeko.miru.http.PlexSelectServerResponse
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.findActiveStoredServer
import app.hononeko.miru.persistence.repository.MediaServerRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.UUID

private const val MAX_PIN_SESSIONS = 64
private const val PIN_SESSION_TTL_MS = 15 * 60 * 1000L

/**
 * Coordinates Plex PIN OAuth, server discovery, encrypted credential storage, and live runtime hot-reload.
 *
 * Raw Plex OAuth tokens and per-server access tokens remain strictly in server memory or AES-256-GCM
 * encrypted database storage and are never returned in HTTP DTOs or logs.
 */
class PlexOAuthService(
    private val plexConfig: PlexConfig,
    private val oauthClient: PlexOAuthClient,
    private val mediaServerRepository: MediaServerRepository,
    private val runtimeManager: PlexRuntimeManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private data class PinSession(
        val pinId: Long,
        val code: String,
        val clientIdentifier: String,
        val createdAtMs: Long,
        var expiresAtMs: Long,
        var status: PlexOAuthPinStatus,
        var authToken: String? = null,
        var servers: List<DiscoveredPlexServer> = emptyList(),
    ) {
        override fun toString(): String =
            "PinSession(pinId=$pinId, status=$status, clientIdentifier=$clientIdentifier, " +
                "authToken=${if (authToken != null) "***" else "null"}, servers=${servers.size})"
    }

    private val sessionsMutex = Mutex()
    private val selectMutex = Mutex()
    private val sessions = linkedMapOf<Long, PinSession>()

    suspend fun getConnectionStatus(): Either<MiruError, PlexConnectionStatus> =
        either {
            val encryptionKeyConfigured = CredentialCrypto.isValidKey(plexConfig.encryptionKey)
            val allServers = mediaServerRepository.findAll().bind()
            val storedServer = allServers.findActiveStoredServer("plex")

            val source =
                when {
                    plexConfig.isStaticConfigured -> PlexCredentialSource.ENV_OVERRIDE
                    storedServer != null -> PlexCredentialSource.STORED_ENCRYPTED
                    else -> PlexCredentialSource.NOT_CONFIGURED
                }

            if (source == PlexCredentialSource.NOT_CONFIGURED) {
                return@either PlexConnectionStatus(
                    configured = false,
                    credentialSource = PlexCredentialSource.NOT_CONFIGURED,
                    encryptionKeyConfigured = encryptionKeyConfigured,
                    reachable = false,
                    statusMessage = "No Plex server configured",
                )
            }

            val activeUrl =
                runtimeManager.currentBaseUrl()
                    ?: if (source == PlexCredentialSource.ENV_OVERRIDE) plexConfig.baseUrl else storedServer?.baseUrl
            val matchingDbServer =
                storedServer
                    ?: allServers.firstOrNull { server ->
                        server.serverType.equals("plex", ignoreCase = true) &&
                            activeUrl != null &&
                            server.baseUrl.trimEnd('/') == activeUrl.trimEnd('/')
                    }

            if (source == PlexCredentialSource.STORED_ENCRYPTED && !encryptionKeyConfigured) {
                return@either PlexConnectionStatus(
                    configured = true,
                    credentialSource = source,
                    encryptionKeyConfigured = false,
                    serverName = storedServer?.name,
                    machineId = storedServer?.machineId,
                    baseUrl = activeUrl,
                    reachable = false,
                    statusMessage = "MIRU_ENCRYPTION_KEY must be configured (>= 32 chars) to decrypt stored credentials",
                )
            }

            if (!runtimeManager.isConfigured()) {
                return@either PlexConnectionStatus(
                    configured = true,
                    credentialSource = source,
                    encryptionKeyConfigured = encryptionKeyConfigured,
                    serverName = matchingDbServer?.name,
                    machineId = matchingDbServer?.machineId ?: runtimeManager.currentMachineId(),
                    baseUrl = activeUrl,
                    reachable = false,
                    statusMessage = "Plex adapter is not active",
                )
            }

            runtimeManager.adapter.checkHealth().fold(
                ifRight = { identity ->
                    PlexConnectionStatus(
                        configured = true,
                        credentialSource = source,
                        encryptionKeyConfigured = encryptionKeyConfigured,
                        serverName = identity.friendlyName,
                        machineId = identity.machineIdentifier,
                        baseUrl = activeUrl,
                        reachable = true,
                        statusMessage =
                            if (source == PlexCredentialSource.ENV_OVERRIDE) {
                                "Connected via environment variables (ENV_OVERRIDE)"
                            } else {
                                "Connected via stored encrypted credentials"
                            },
                    )
                },
                ifLeft = { healthError ->
                    PlexConnectionStatus(
                        configured = true,
                        credentialSource = source,
                        encryptionKeyConfigured = encryptionKeyConfigured,
                        serverName = matchingDbServer?.name,
                        machineId = matchingDbServer?.machineId ?: runtimeManager.currentMachineId(),
                        baseUrl = activeUrl,
                        reachable = false,
                        statusMessage = healthError.message,
                    )
                },
            )
        }

    suspend fun startOAuth(): Either<MiruError, PlexOAuthStartResponse> =
        either {
            val clientIdentifier = resolveClientIdentifier().bind()
            val pinStart = oauthClient.createPin(clientIdentifier).bind()
            val nowMs = clock()
            val effectiveExpiresAtMs = maxOf(pinStart.expiresAtMs, nowMs + 1_000L).coerceAtMost(nowMs + PIN_SESSION_TTL_MS)

            sessionsMutex.withLock {
                pruneExpiredLocked(nowMs)
                while (sessions.size >= MAX_PIN_SESSIONS) {
                    val oldestKey = sessions.keys.firstOrNull() ?: break
                    sessions.remove(oldestKey)
                }
                sessions[pinStart.pinId] =
                    PinSession(
                        pinId = pinStart.pinId,
                        code = pinStart.code,
                        clientIdentifier = clientIdentifier,
                        createdAtMs = nowMs,
                        expiresAtMs = effectiveExpiresAtMs,
                        status = PlexOAuthPinStatus.PENDING,
                    )
            }

            PlexOAuthStartResponse(
                pinId = pinStart.pinId,
                authUrl = pinStart.authUrl,
                expiresAtMs = effectiveExpiresAtMs,
            )
        }

    suspend fun pollOAuth(pinId: Long): Either<MiruError, PlexOAuthPollResponse> =
        either {
            val snapshot =
                sessionsMutex.withLock {
                    val nowMs = clock()
                    pruneExpiredLocked(nowMs)
                    sessions[pinId]?.let { Triple(it.code, it.clientIdentifier, it.status to it.expiresAtMs) }
                } ?: raise(MiruError.NotFound(resource = "Plex OAuth session", id = pinId.toString()))

            val (code, clientIdentifier, statusAndExpiry) = snapshot
            val (currentStatus, currentExpiry) = statusAndExpiry
            if (currentStatus == PlexOAuthPinStatus.AUTHORIZED) {
                return@either PlexOAuthPollResponse(
                    pinId = pinId,
                    status = PlexOAuthPinStatus.AUTHORIZED,
                    expiresAtMs = currentExpiry,
                )
            }
            if (currentStatus == PlexOAuthPinStatus.EXPIRED || currentExpiry <= clock()) {
                sessionsMutex.withLock {
                    sessions[pinId]?.status = PlexOAuthPinStatus.EXPIRED
                }
                return@either PlexOAuthPollResponse(
                    pinId = pinId,
                    status = PlexOAuthPinStatus.EXPIRED,
                    expiresAtMs = currentExpiry,
                )
            }

            val pollResult = oauthClient.pollPin(pinId = pinId, code = code, clientIdentifier = clientIdentifier).bind()
            val updated =
                sessionsMutex.withLock {
                    val session =
                        sessions[pinId]
                            ?: raise(MiruError.NotFound(resource = "Plex OAuth session", id = pinId.toString()))
                    when (pollResult) {
                        is PlexPinPollResult.Authorized -> {
                            session.status = PlexOAuthPinStatus.AUTHORIZED
                            session.authToken = pollResult.authToken
                        }

                        is PlexPinPollResult.Pending -> {
                            session.status = PlexOAuthPinStatus.PENDING
                            session.expiresAtMs = pollResult.expiresAtMs
                        }

                        PlexPinPollResult.Expired -> {
                            session.status = PlexOAuthPinStatus.EXPIRED
                        }
                    }
                    PlexOAuthPollResponse(
                        pinId = session.pinId,
                        status = session.status,
                        expiresAtMs = session.expiresAtMs,
                    )
                }
            updated
        }

    suspend fun discoverServers(pinId: Long? = null): Either<MiruError, PlexDiscoveredServersResponse> =
        either {
            val (resolvedPinId, authToken, clientIdentifier) = resolveAuthorizedSession(pinId).bind()
            val discovered = oauthClient.fetchResources(authToken = authToken, clientIdentifier = clientIdentifier).bind()

            sessionsMutex.withLock {
                sessions[resolvedPinId]?.servers = discovered
            }

            PlexDiscoveredServersResponse(
                pinId = resolvedPinId,
                servers = discovered.map { it.toDto() },
            )
        }

    suspend fun selectServer(
        pinId: Long? = null,
        machineId: String,
        connectionUri: String,
    ): Either<MiruError, PlexSelectServerResponse> =
        either {
            val trimmedMachineId = machineId.trim()
            val normalizedUri = connectionUri.trim().trimEnd('/')
            val validationErrors = linkedMapOf<String, String>()
            if (trimmedMachineId.isEmpty()) {
                validationErrors["machineId"] = "must not be blank"
            }
            if (normalizedUri.isEmpty() || !isValidHttpUrl(normalizedUri)) {
                validationErrors["connectionUri"] = "must be a valid http:// or https:// URL"
            }
            ensure(validationErrors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid Plex server selection parameters",
                    fieldErrors = validationErrors,
                )
            }

            val encryptionKey = CredentialCrypto.validateKey(plexConfig.encryptionKey).bind()
            val (resolvedPinId, authToken, clientIdentifier) = resolveAuthorizedSession(pinId).bind()

            val cachedServers =
                sessionsMutex.withLock {
                    sessions[resolvedPinId]?.servers.orEmpty()
                }
            val servers =
                if (cachedServers.isNotEmpty()) {
                    cachedServers
                } else {
                    val fetched = oauthClient.fetchResources(authToken = authToken, clientIdentifier = clientIdentifier).bind()
                    sessionsMutex.withLock {
                        sessions[resolvedPinId]?.servers = fetched
                    }
                    fetched
                }

            val selectedServer =
                servers.firstOrNull { it.machineId == trimmedMachineId }
                    ?: raise(MiruError.NotFound(resource = "Plex server", id = trimmedMachineId))

            val matchesDiscoveredUri = selectedServer.connections.any { it.uri.trimEnd('/') == normalizedUri }
            ensure(matchesDiscoveredUri || isValidHttpUrl(normalizedUri)) {
                MiruError.ValidationError(
                    message = "Invalid connection URI for selected Plex server",
                    fieldErrors = mapOf("connectionUri" to "must match a discovered server connection or a valid http/https URL"),
                )
            }

            val tokenToStore =
                selectedServer.accessToken?.takeIf { it.isNotBlank() }
                    ?: authToken
            val encryptedToken = CredentialCrypto.encrypt(tokenToStore, encryptionKey).bind()

            persistAndActivateSelection(
                resolvedPinId = resolvedPinId,
                selectedServer = selectedServer,
                normalizedUri = normalizedUri,
                encryptedToken = encryptedToken,
                clientIdentifier = clientIdentifier,
            ).bind()
        }

    private suspend fun persistAndActivateSelection(
        resolvedPinId: Long,
        selectedServer: DiscoveredPlexServer,
        normalizedUri: String,
        encryptedToken: String,
        clientIdentifier: String,
    ): Either<MiruError, PlexSelectServerResponse> =
        selectMutex.withLock {
            either {
                val nowMs = clock()
                val existing = mediaServerRepository.findByMachineId(selectedServer.machineId).bind()
                val savedRecord =
                    mediaServerRepository
                        .upsertExclusiveStoredCredentials(
                            MediaServerRecord(
                                id = existing?.id ?: idGenerator(),
                                name = selectedServer.name,
                                serverType = "plex",
                                machineId = selectedServer.machineId,
                                baseUrl = normalizedUri,
                                encryptedToken = encryptedToken,
                                clientIdentifier = clientIdentifier,
                                createdAtMs = existing?.createdAtMs ?: nowMs,
                                updatedAtMs = nowMs,
                            ),
                        ).bind()

                sessionsMutex.withLock {
                    sessions.remove(resolvedPinId)
                }

                if (plexConfig.isStaticConfigured) {
                    PlexSelectServerResponse(
                        saved = true,
                        hotReloaded = false,
                        envOverrideActive = true,
                        serverName = savedRecord.name,
                        machineId = savedRecord.machineId,
                        baseUrl = savedRecord.baseUrl,
                        credentialSource = PlexCredentialSource.ENV_OVERRIDE,
                    )
                } else {
                    val reloaded = runtimeManager.reloadFromStoredCredentials().bind()
                    val activatedRecord =
                        if (reloaded) {
                            runtimeManager.currentMachineId()?.let { activeMachineId ->
                                mediaServerRepository.findByMachineId(activeMachineId).bind()
                            } ?: savedRecord
                        } else {
                            savedRecord
                        }
                    PlexSelectServerResponse(
                        saved = true,
                        hotReloaded = reloaded,
                        envOverrideActive = false,
                        serverName = activatedRecord.name,
                        machineId = activatedRecord.machineId,
                        baseUrl = activatedRecord.baseUrl,
                        credentialSource = PlexCredentialSource.STORED_ENCRYPTED,
                    )
                }
            }
        }

    private suspend fun resolveAuthorizedSession(pinId: Long?): Either<MiruError, Triple<Long, String, String>> =
        either {
            sessionsMutex.withLock {
                val nowMs = clock()
                pruneExpiredLocked(nowMs)

                val session =
                    if (pinId != null) {
                        sessions[pinId]
                            ?: raise(MiruError.NotFound(resource = "Plex OAuth session", id = pinId.toString()))
                    } else {
                        sessions.values
                            .lastOrNull { it.status == PlexOAuthPinStatus.AUTHORIZED && !it.authToken.isNullOrBlank() }
                            ?: raise(MiruError.NotFound(resource = "Authorized Plex OAuth session"))
                    }

                ensure(session.status != PlexOAuthPinStatus.EXPIRED && session.expiresAtMs > nowMs) {
                    session.status = PlexOAuthPinStatus.EXPIRED
                    MiruError.Conflict("Plex OAuth PIN session has expired; start a new sign-in flow")
                }

                val token = session.authToken?.takeIf { it.isNotBlank() }
                ensure(session.status == PlexOAuthPinStatus.AUTHORIZED && token != null) {
                    MiruError.Conflict("Plex OAuth PIN has not been authorized yet")
                }

                Triple(session.pinId, token, session.clientIdentifier)
            }
        }

    private suspend fun resolveClientIdentifier(): Either<MiruError, String> =
        either {
            val fromConfig = plexConfig.clientIdentifier?.trim()?.takeIf { it.isNotEmpty() }
            if (fromConfig != null) return@either fromConfig

            val fromDb =
                mediaServerRepository
                    .findAll()
                    .bind()
                    .firstOrNull { it.serverType.equals("plex", ignoreCase = true) && !it.clientIdentifier.isNullOrBlank() }
                    ?.clientIdentifier
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            fromDb ?: "miru-${idGenerator()}"
        }

    private fun pruneExpiredLocked(nowMs: Long) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            val ageExceeded = nowMs - session.createdAtMs > PIN_SESSION_TTL_MS
            val expiredAfterUse = session.status == PlexOAuthPinStatus.EXPIRED && nowMs > session.expiresAtMs + PIN_SESSION_TTL_MS
            if (ageExceeded || expiredAfterUse) {
                iterator.remove()
            }
        }
    }

    private fun DiscoveredPlexServer.toDto(): PlexDiscoveredServerDto =
        PlexDiscoveredServerDto(
            name = name,
            machineId = machineId,
            productVersion = productVersion,
            platform = platform,
            owned = owned,
            presence = presence,
            connections =
                connections.map { conn ->
                    PlexConnectionOptionDto(
                        uri = conn.uri,
                        protocol = conn.protocol,
                        local = conn.local,
                        relay = conn.relay,
                        ipv6 = conn.ipv6,
                    )
                },
        )

    private fun isValidHttpUrl(raw: String): Boolean =
        runCatching {
            val uri = URI(raw)
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
        }.getOrDefault(false)
}
