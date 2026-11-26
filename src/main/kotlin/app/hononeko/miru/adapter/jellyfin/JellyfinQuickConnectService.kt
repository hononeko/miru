package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.JellyfinConnectionStatus
import app.hononeko.miru.http.JellyfinQuickConnectPollResponse
import app.hononeko.miru.http.JellyfinQuickConnectStartResponse
import app.hononeko.miru.http.JellyfinQuickConnectStatus
import app.hononeko.miru.http.PlexCredentialSource
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.MediaServerRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.UUID

private const val MAX_ATTEMPTS = 64
private const val QUICK_CONNECT_TTL_MS = 10 * 60 * 1000L
private const val EXPIRED_ATTEMPT_RETENTION_MS = 15 * 60 * 1000L
private const val MAX_ATTEMPT_ID_LENGTH = 128
private const val EXPIRED_MESSAGE = "Quick Connect code expired. Start again to generate a new code."
private const val NOT_ADMIN_MESSAGE = "Quick Connect must be approved by a Jellyfin administrator account."

/**
 * Coordinates Jellyfin Quick Connect onboarding, administrator verification, API key creation,
 * temporary session logout, AES-256-GCM credential persistence, reconnect cleanup, and live
 * [JellyfinRuntimeManager] hot-reload.
 */
class JellyfinQuickConnectService(
    private val jellyfinConfig: JellyfinConfig,
    private val encryptionKey: String?,
    private val quickConnectClient: JellyfinQuickConnectClient,
    private val mediaServerRepository: MediaServerRepository,
    private val runtimeManager: JellyfinRuntimeManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private data class PendingAttempt(
        val attemptId: String,
        var secret: String?,
        val code: String,
        val baseUrl: String,
        val machineId: String,
        val serverName: String,
        val serverVersion: String?,
        val deviceId: String,
        val startedAtMs: Long,
        val expiresAtMs: Long,
        var status: JellyfinQuickConnectStatus = JellyfinQuickConnectStatus.PENDING,
        var message: String? = null,
        var cachedResponse: JellyfinQuickConnectPollResponse? = null,
        val mutex: Mutex = Mutex(),
    ) {
        override fun toString(): String =
            "PendingAttempt(attemptId=$attemptId, secret=${if (secret != null) "***" else "null"}, " +
                "code=***, baseUrl=$baseUrl, machineId=$machineId, status=$status)"
    }

    private val attemptsMutex = Mutex()
    private val attempts = linkedMapOf<String, PendingAttempt>()

    suspend fun getConnectionStatus(): Either<MiruError, JellyfinConnectionStatus> =
        either {
            val encryptionKeyConfigured = CredentialCrypto.isValidKey(encryptionKey)
            val allServers = mediaServerRepository.findAll().bind()
            val storedServer =
                allServers.firstOrNull { server ->
                    server.serverType.equals(JellyfinMediaServerAdapter.PROVIDER_NAME, ignoreCase = true) &&
                        !server.encryptedToken.isNullOrBlank()
                }

            val source =
                when {
                    jellyfinConfig.isConfigured -> PlexCredentialSource.ENV_OVERRIDE
                    storedServer != null -> PlexCredentialSource.STORED_ENCRYPTED
                    else -> PlexCredentialSource.NOT_CONFIGURED
                }

            if (source == PlexCredentialSource.NOT_CONFIGURED) {
                return@either JellyfinConnectionStatus(
                    configured = false,
                    credentialSource = PlexCredentialSource.NOT_CONFIGURED,
                    encryptionKeyConfigured = encryptionKeyConfigured,
                    reachable = false,
                    statusMessage = "No Jellyfin server configured",
                )
            }

            val activeUrl =
                runtimeManager.currentBaseUrl()
                    ?: if (source == PlexCredentialSource.ENV_OVERRIDE) jellyfinConfig.baseUrl else storedServer?.baseUrl
            val matchingDbServer =
                storedServer
                    ?: allServers.firstOrNull { server ->
                        server.serverType.equals(JellyfinMediaServerAdapter.PROVIDER_NAME, ignoreCase = true) &&
                            activeUrl != null &&
                            server.baseUrl.trimEnd('/') == activeUrl.trimEnd('/')
                    }

            if (source == PlexCredentialSource.STORED_ENCRYPTED && !encryptionKeyConfigured) {
                return@either JellyfinConnectionStatus(
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
                return@either JellyfinConnectionStatus(
                    configured = true,
                    credentialSource = source,
                    encryptionKeyConfigured = encryptionKeyConfigured,
                    serverName = matchingDbServer?.name,
                    machineId = matchingDbServer?.machineId ?: runtimeManager.currentMachineId(),
                    baseUrl = activeUrl,
                    reachable = false,
                    statusMessage = "Jellyfin adapter is not active",
                )
            }

            runtimeManager.adapter.checkHealth().fold(
                ifRight = { identity ->
                    JellyfinConnectionStatus(
                        configured = true,
                        credentialSource = source,
                        encryptionKeyConfigured = encryptionKeyConfigured,
                        serverName = identity.friendlyName,
                        machineId = identity.machineIdentifier,
                        baseUrl = activeUrl,
                        version = identity.version,
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
                    JellyfinConnectionStatus(
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

    suspend fun startQuickConnect(rawBaseUrl: String): Either<MiruError, JellyfinQuickConnectStartResponse> =
        either {
            CredentialCrypto.validateKey(encryptionKey).bind()
            val normalizedUrl = normalizeAndValidateUrl(rawBaseUrl).bind()

            val serverInfo = quickConnectClient.fetchPublicSystemInfo(normalizedUrl).bind()
            val quickConnectEnabled = quickConnectClient.isQuickConnectEnabled(normalizedUrl).bind()
            ensure(quickConnectEnabled) {
                MiruError.Conflict(
                    "Quick Connect is disabled on this Jellyfin server. " +
                        "Enable it in Jellyfin Dashboard -> General -> Enable Quick Connect.",
                )
            }

            val deviceId = resolveDeviceId().bind()
            val startedAtMs = clock()
            val initiated = quickConnectClient.initiateQuickConnect(normalizedUrl, deviceId).bind()
            val attemptId = idGenerator()
            val expiresAtMs = startedAtMs + QUICK_CONNECT_TTL_MS

            attemptsMutex.withLock {
                pruneExpiredLocked(startedAtMs)
                while (attempts.size >= MAX_ATTEMPTS) {
                    val oldestKey = attempts.keys.firstOrNull() ?: break
                    attempts.remove(oldestKey)
                }
                attempts[attemptId] =
                    PendingAttempt(
                        attemptId = attemptId,
                        secret = initiated.secret,
                        code = initiated.code,
                        baseUrl = normalizedUrl,
                        machineId = serverInfo.id,
                        serverName = serverInfo.serverName,
                        serverVersion = serverInfo.version,
                        deviceId = deviceId,
                        startedAtMs = startedAtMs,
                        expiresAtMs = expiresAtMs,
                    )
            }

            JellyfinQuickConnectStartResponse(
                attemptId = attemptId,
                code = initiated.code,
                serverName = serverInfo.serverName,
                serverVersion = serverInfo.version,
                expiresAtMs = expiresAtMs,
            )
        }

    suspend fun pollQuickConnect(attemptId: String): Either<MiruError, JellyfinQuickConnectPollResponse> =
        either {
            val trimmedId = attemptId.trim()
            ensure(trimmedId.isNotEmpty() && trimmedId.length <= MAX_ATTEMPT_ID_LENGTH && !trimmedId.any { it.isWhitespace() }) {
                MiruError.ValidationError(
                    message = "Invalid Quick Connect attempt identifier",
                    fieldErrors = mapOf("attemptId" to "must be a valid non-empty attempt identifier"),
                )
            }

            val attempt =
                attemptsMutex.withLock {
                    pruneExpiredLocked(clock())
                    attempts[trimmedId]
                } ?: raise(MiruError.NotFound(resource = "Jellyfin Quick Connect attempt", id = trimmedId))

            attempt.mutex.withLock {
                attempt.cachedResponse?.let { return@either it }

                if (attempt.status != JellyfinQuickConnectStatus.PENDING) {
                    return@either terminalResponse(attempt)
                }

                val nowMs = clock()
                if (nowMs >= attempt.expiresAtMs || attempt.secret.isNullOrBlank()) {
                    return@either markExpired(attempt)
                }

                val secret = attempt.secret ?: return@either markExpired(attempt)
                when (quickConnectClient.checkQuickConnect(attempt.baseUrl, secret, attempt.deviceId).bind()) {
                    JellyfinQuickConnectPollOutcome.Pending ->
                        JellyfinQuickConnectPollResponse(
                            attemptId = attempt.attemptId,
                            status = JellyfinQuickConnectStatus.PENDING,
                            expiresAtMs = attempt.expiresAtMs,
                        )

                    JellyfinQuickConnectPollOutcome.Expired ->
                        markExpired(attempt)

                    JellyfinQuickConnectPollOutcome.Authenticated ->
                        completeAuthorizationLocked(attempt, secret).bind()
                }
            }
        }

    private suspend fun completeAuthorizationLocked(
        attempt: PendingAttempt,
        secret: String,
    ): Either<MiruError, JellyfinQuickConnectPollResponse> =
        either {
            val validEncryptionKey = CredentialCrypto.validateKey(encryptionKey).bind()
            val authSession =
                quickConnectClient.authenticateWithQuickConnect(attempt.baseUrl, secret, attempt.deviceId).fold(
                    ifLeft = {
                        return@either markFailed(
                            attempt = attempt,
                            message = "Failed to authenticate Quick Connect session with Jellyfin.",
                        )
                    },
                    ifRight = { it },
                )

            try {
                if (!authSession.isAdministrator) {
                    val userSuffix = authSession.userName?.let { " ('$it' is not an administrator)" }.orEmpty()
                    return@either markNotAdmin(
                        attempt = attempt,
                        message = "$NOT_ADMIN_MESSAGE$userSuffix",
                    )
                }

                val createResult =
                    quickConnectClient.createApiKey(
                        baseUrl = attempt.baseUrl,
                        accessToken = authSession.accessToken,
                        deviceId = attempt.deviceId,
                        appName = JellyfinQuickConnectClient.MIRU_APP_NAME,
                    )
                if (createResult.isLeft()) {
                    return@either markFailed(
                        attempt = attempt,
                        message = "Failed to create a Miru API key on Jellyfin.",
                    )
                }

                val keys =
                    quickConnectClient.listApiKeys(
                        baseUrl = attempt.baseUrl,
                        accessToken = authSession.accessToken,
                        deviceId = attempt.deviceId,
                    ).fold(
                        ifLeft = {
                            return@either markFailed(
                                attempt = attempt,
                                message = "Failed to retrieve the newly created Miru API key from Jellyfin.",
                            )
                        },
                        ifRight = { it },
                    )

                val createdKey =
                    keys
                        .filter { key ->
                            key.appName == JellyfinQuickConnectClient.MIRU_APP_NAME &&
                                key.dateCreatedMs >= attempt.startedAtMs
                        }.maxByOrNull { it.dateCreatedMs }
                        ?: return@either markFailed(
                            attempt = attempt,
                            message = "Miru API key was not found after creation on Jellyfin.",
                        )

                persistAndReloadAuthorizedServer(
                    attempt = attempt,
                    newApiKey = createdKey.accessToken,
                    validEncryptionKey = validEncryptionKey,
                ).bind()
            } finally {
                quickConnectClient.logoutSessionBestEffort(
                    baseUrl = attempt.baseUrl,
                    accessToken = authSession.accessToken,
                    deviceId = attempt.deviceId,
                )
            }
        }

    private suspend fun persistAndReloadAuthorizedServer(
        attempt: PendingAttempt,
        newApiKey: String,
        validEncryptionKey: String,
    ): Either<MiruError, JellyfinQuickConnectPollResponse> =
        either {
            val encryptedToken = CredentialCrypto.encrypt(newApiKey, validEncryptionKey).bind()
            val allServers = mediaServerRepository.findAll().bind()
            val existingSameServer =
                allServers.firstOrNull { it.machineId == attempt.machineId }
                    ?: mediaServerRepository.findByMachineId(attempt.machineId).bind()

            val previousKeyToDelete =
                existingSameServer
                    ?.takeIf {
                        it.serverType.equals(JellyfinMediaServerAdapter.PROVIDER_NAME, ignoreCase = true) &&
                            !it.encryptedToken.isNullOrBlank()
                    }?.encryptedToken
                    ?.let { encrypted -> CredentialCrypto.decrypt(encrypted, validEncryptionKey).getOrNull() }
                    ?.takeIf { it.isNotBlank() && it != newApiKey }

            val nowMs = clock()
            val savedRecord =
                mediaServerRepository
                    .upsert(
                        MediaServerRecord(
                            id = existingSameServer?.id ?: idGenerator(),
                            name = attempt.serverName,
                            serverType = JellyfinMediaServerAdapter.PROVIDER_NAME,
                            machineId = attempt.machineId,
                            baseUrl = attempt.baseUrl,
                            encryptedToken = encryptedToken,
                            clientIdentifier = attempt.deviceId,
                            createdAtMs = existingSameServer?.createdAtMs ?: nowMs,
                            updatedAtMs = nowMs,
                        ),
                    ).bind()

            // Clear stored tokens on any other Jellyfin server records so switching servers never boots the old server on restart.
            allServers
                .filter { other ->
                    other.id != savedRecord.id &&
                        other.serverType.equals(JellyfinMediaServerAdapter.PROVIDER_NAME, ignoreCase = true) &&
                        !other.encryptedToken.isNullOrBlank()
                }.forEach { other ->
                    mediaServerRepository
                        .updateCredentials(
                            id = other.id,
                            name = other.name,
                            baseUrl = other.baseUrl,
                            encryptedToken = null,
                            clientIdentifier = other.clientIdentifier,
                            updatedAtMs = nowMs,
                        ).bind()
                }

            val envOverrideActive = jellyfinConfig.isConfigured
            val hotReloaded =
                if (envOverrideActive) {
                    false
                } else {
                    runtimeManager.reloadFromStoredCredentials().bind()
                }
            val credentialSource =
                if (envOverrideActive) {
                    PlexCredentialSource.ENV_OVERRIDE
                } else {
                    PlexCredentialSource.STORED_ENCRYPTED
                }

            if (previousKeyToDelete != null) {
                quickConnectClient.deleteApiKeyBestEffort(
                    baseUrl = attempt.baseUrl,
                    workingApiKey = newApiKey,
                    keyToDelete = previousKeyToDelete,
                    deviceId = attempt.deviceId,
                )
            }

            attempt.secret = null
            attempt.status = JellyfinQuickConnectStatus.AUTHORIZED
            attempt.message = null
            JellyfinQuickConnectPollResponse(
                attemptId = attempt.attemptId,
                status = JellyfinQuickConnectStatus.AUTHORIZED,
                expiresAtMs = attempt.expiresAtMs,
                serverName = savedRecord.name,
                serverVersion = attempt.serverVersion,
                machineId = savedRecord.machineId,
                baseUrl = savedRecord.baseUrl,
                hotReloaded = hotReloaded,
                envOverrideActive = envOverrideActive,
                credentialSource = credentialSource,
            ).also { attempt.cachedResponse = it }
        }

    private fun markExpired(attempt: PendingAttempt): JellyfinQuickConnectPollResponse {
        attempt.secret = null
        attempt.status = JellyfinQuickConnectStatus.EXPIRED
        attempt.message = EXPIRED_MESSAGE
        return terminalResponse(attempt).also { attempt.cachedResponse = it }
    }

    private fun markNotAdmin(
        attempt: PendingAttempt,
        message: String,
    ): JellyfinQuickConnectPollResponse {
        attempt.secret = null
        attempt.status = JellyfinQuickConnectStatus.NOT_ADMIN
        attempt.message = message
        return terminalResponse(attempt).also { attempt.cachedResponse = it }
    }

    private fun markFailed(
        attempt: PendingAttempt,
        message: String,
    ): JellyfinQuickConnectPollResponse {
        attempt.secret = null
        attempt.status = JellyfinQuickConnectStatus.FAILED
        attempt.message = message
        return terminalResponse(attempt).also { attempt.cachedResponse = it }
    }

    private fun terminalResponse(attempt: PendingAttempt): JellyfinQuickConnectPollResponse =
        attempt.cachedResponse
            ?: JellyfinQuickConnectPollResponse(
                attemptId = attempt.attemptId,
                status = attempt.status,
                expiresAtMs = attempt.expiresAtMs,
                message = attempt.message,
            )

    private suspend fun resolveDeviceId(): Either<MiruError, String> =
        either {
            val fromConfig = jellyfinConfig.clientIdentifier?.trim()?.takeIf { it.isNotEmpty() }
            if (fromConfig != null) return@either fromConfig

            val fromDb =
                mediaServerRepository
                    .findAll()
                    .bind()
                    .firstOrNull {
                        it.serverType.equals(JellyfinMediaServerAdapter.PROVIDER_NAME, ignoreCase = true) &&
                            !it.clientIdentifier.isNullOrBlank()
                    }?.clientIdentifier
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            fromDb ?: "miru-${idGenerator()}"
        }

    private fun normalizeAndValidateUrl(rawBaseUrl: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = rawBaseUrl.trim().trimEnd('/')
            val valid =
                trimmed.isNotEmpty() &&
                    runCatching {
                        val uri = URI(trimmed)
                        val schemeValid = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
                        schemeValid &&
                            !uri.host.isNullOrBlank() &&
                            uri.rawUserInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null
                    }.getOrDefault(false)

            ensure(valid) {
                MiruError.ValidationError(
                    message = "Invalid Jellyfin server URL",
                    fieldErrors = mapOf("baseUrl" to "must be a valid http:// or https:// URL without embedded credentials"),
                )
            }
            trimmed
        }

    private fun pruneExpiredLocked(nowMs: Long) {
        val iterator = attempts.entries.iterator()
        while (iterator.hasNext()) {
            val attempt = iterator.next().value
            val ageExceeded = nowMs - attempt.startedAtMs > EXPIRED_ATTEMPT_RETENTION_MS
            val expiredAfterTtl =
                attempt.status != JellyfinQuickConnectStatus.PENDING &&
                    nowMs > attempt.expiresAtMs + EXPIRED_ATTEMPT_RETENTION_MS
            if (ageExceeded || expiredAfterTtl) {
                attempt.secret = null
                iterator.remove()
            }
        }
    }
}
