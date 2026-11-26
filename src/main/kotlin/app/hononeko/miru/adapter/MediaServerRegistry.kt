package app.hononeko.miru.adapter

import app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.adapter.plex.DelegatingMediaServerAdapter
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.AdminServerStatusDto
import app.hononeko.miru.http.AdminServersResponse
import app.hononeko.miru.http.MediaServerSummaryDto
import app.hononeko.miru.http.MediaServersResponse
import app.hononeko.miru.http.PlexCredentialSource
import app.hononeko.miru.http.ServerDataSummaryDto
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.findActiveStoredServer
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.ServerDataCounts
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.SessionStateEngine
import app.hononeko.miru.webhook.ReconcileHealthSignal
import app.hononeko.miru.webhook.WebhookServerPayload
import arrow.core.Either
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Runtime contract shared by server-specific runtime managers (e.g. Plex, Jellyfin) so
 * [MediaServerRegistry] can manage lifecycles, event streams, and server-scoped routing uniformly.
 */
interface ManagedMediaServerRuntime : RealtimeLifecycle {
    val serverType: String
    val adapter: DelegatingMediaServerAdapter
    val sessionEngine: SessionStateEngine
    val serverIdProvider: suspend () -> Either<MiruError, String>
    val reconcileHealth: SharedFlow<ReconcileHealthSignal>

    fun isConfigured(): Boolean

    fun currentBaseUrl(): String?

    fun currentMachineId(): String?

    fun cachedServerIdOrNull(): String?

    fun websocketEnabled(): Boolean

    fun isEnvConfigured(): Boolean = false

    fun connectionState(): ConnectionState

    fun lastReconcileHealth(): ReconcileHealthSignal?

    suspend fun currentServerSummary(): WebhookServerPayload?

    fun setOnReloadLibrarySync(callback: (suspend () -> Unit)?)

    suspend fun disconnect(serverId: String? = null): Either<MiruError, Int> = Either.Right(0)
}

/**
 * Coordinates all configured media server runtimes, merges their session and health event streams,
 * and resolves server-scoped [MediaServerAdapter] instances for multi-server deployments.
 */
class MediaServerRegistry(
    val runtimes: List<ManagedMediaServerRuntime>,
    private val mediaServerRepository: MediaServerRepository,
    private val jellyfinConfig: JellyfinConfig = JellyfinConfig(),
) : RealtimeLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("media-server-registry"))
    private val lock = Any()
    private val collectorJobs = mutableListOf<Job>()

    private val _sessionEvents =
        MutableSharedFlow<SessionEvent>(
            extraBufferCapacity = MERGED_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    private val _reconcileHealth =
        MutableSharedFlow<ReconcileHealthSignal>(
            extraBufferCapacity = MERGED_HEALTH_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val reconcileHealth: SharedFlow<ReconcileHealthSignal> = _reconcileHealth.asSharedFlow()

    override fun start() {
        synchronized(lock) {
            if (collectorJobs.isEmpty()) {
                for (runtime in runtimes) {
                    collectorJobs +=
                        scope.launch(CoroutineName("registry-sessions-${runtime.serverType}")) {
                            runtime.sessionEngine.sessionEvents.collect { event ->
                                _sessionEvents.emit(event)
                            }
                        }
                    collectorJobs +=
                        scope.launch(CoroutineName("registry-health-${runtime.serverType}")) {
                            runtime.reconcileHealth.collect { signal ->
                                _reconcileHealth.emit(signal)
                            }
                        }
                }
            }
        }
        runtimes.forEach { it.start() }
    }

    override suspend fun stop() {
        val jobs =
            synchronized(lock) {
                collectorJobs.toList().also { collectorJobs.clear() }
            }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.cancelAndJoin() }
        coroutineScope {
            for (runtime in runtimes) {
                launch { runtime.stop() }
            }
        }
        scope.cancel()
    }

    suspend fun currentSessions(): List<ActiveSessionRecord> =
        runtimes.flatMap { it.sessionEngine.currentSessions() }

    suspend fun currentLiveTechnical(): Map<ActiveSessionKey, StreamTechnicalInfo> =
        buildMap {
            for (runtime in runtimes) {
                putAll(runtime.sessionEngine.currentLiveTechnical())
            }
        }

    fun activeAdapters(): List<MediaServerAdapter> =
        runtimes.filter { it.isConfigured() }.map { it.adapter }

    suspend fun activeServerAdapters(): List<Pair<String?, MediaServerAdapter>> =
        runtimes
            .filter { it.isConfigured() }
            .map { runtime ->
                val sid = runtime.cachedServerIdOrNull() ?: runtime.serverIdProvider().getOrNull()
                sid to runtime.adapter
            }

    fun configuredBaseUrls(): List<String> =
        runtimes.mapNotNull { it.currentBaseUrl()?.trim()?.takeIf(String::isNotEmpty) }

    suspend fun adapterForServer(serverId: String?): MediaServerAdapter? {
        val normalizedId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        findRuntimeByCachedId(normalizedId)?.let { return it.adapter }

        val record = mediaServerRepository.findById(normalizedId).getOrNull()
        if (record != null) {
            findRuntimeForRecord(record)?.let { return it.adapter }
        }

        // Fall back to resolving serverIds asynchronously if cache was not warmed yet
        for (runtime in runtimes) {
            if (runtime.isConfigured()) {
                val resolvedId = runtime.serverIdProvider().getOrNull()
                if (resolvedId == normalizedId) {
                    return runtime.adapter
                }
            }
        }
        return null
    }

    suspend fun serverMap(): Map<String, MediaServerRecord> =
        mediaServerRepository
            .findAll()
            .getOrNull()
            .orEmpty()
            .associateBy { it.id }

    suspend fun serverSummaryById(serverId: String?): WebhookServerPayload? {
        val normalizedId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (runtime in runtimes) {
            val summary = runtime.currentServerSummary()
            if (summary != null && summary.id == normalizedId) {
                return summary
            }
        }
        val dbRecord = mediaServerRepository.findById(normalizedId).getOrNull() ?: return null
        return WebhookServerPayload(id = dbRecord.id, name = dbRecord.name)
    }

    suspend fun currentDefaultServerName(): String? {
        val names = runtimes.mapNotNull { it.currentServerSummary()?.name?.trim()?.takeIf(String::isNotEmpty) }
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names.first()
            else -> names.joinToString(" · ")
        }
    }

    fun runtimeForServerType(serverType: String): ManagedMediaServerRuntime? =
        runtimes.firstOrNull { it.serverType.equals(serverType, ignoreCase = true) }

    fun isEnvManaged(record: MediaServerRecord): Boolean {
        val type = record.serverType.lowercase()
        val matchedRuntime = runtimeForServerType(type)
        val envActiveForType =
            when (type) {
                "plex" -> matchedRuntime?.isEnvConfigured() == true
                "jellyfin" -> jellyfinConfig.isConfigured || matchedRuntime?.isEnvConfigured() == true
                else -> matchedRuntime?.isEnvConfigured() == true
            }
        if (!envActiveForType) return false

        if (matchedRuntime != null && matchedRuntime.isEnvConfigured() && matchesRuntimeRecord(matchedRuntime, record)) {
            return true
        }
        return type == "jellyfin" &&
            jellyfinConfig.isConfigured &&
            record.baseUrl.trimEnd('/') == jellyfinConfig.baseUrl.trimEnd('/')
    }

    private fun matchesRuntimeRecord(
        runtime: ManagedMediaServerRuntime,
        record: MediaServerRecord,
    ): Boolean {
        val cachedId = runtime.cachedServerIdOrNull()
        if (cachedId != null && cachedId == record.id) return true
        val mid = runtime.currentMachineId()
        if (mid != null && mid == record.machineId) return true
        val base = runtime.currentBaseUrl()?.trimEnd('/')
        return base != null && base == record.baseUrl.trimEnd('/')
    }

    fun isRuntimeActiveForRecord(record: MediaServerRecord): Boolean {
        val runtime = runtimeForServerType(record.serverType) ?: return false
        if (!runtime.isConfigured()) return false
        val cachedId = runtime.cachedServerIdOrNull()
        if (cachedId != null) return cachedId == record.id
        val mid = runtime.currentMachineId()
        if (mid != null) return mid == record.machineId
        val base = runtime.currentBaseUrl()?.trimEnd('/')
        return base != null && base == record.baseUrl.trimEnd('/')
    }

    suspend fun listPublicServers(): MediaServersResponse {
        val dbServers = mediaServerRepository.findAll().getOrNull().orEmpty()
        val matchedIds = linkedSetOf<String>()
        val summaries = mutableListOf<MediaServerSummaryDto>()

        for (runtime in runtimes) {
            val matchedRecord = matchRecordForRuntime(runtime, dbServers)
            val resolvedId = runtime.cachedServerIdOrNull() ?: matchedRecord?.id
            if (!runtime.isConfigured() && resolvedId == null) {
                continue
            }
            if (resolvedId != null) {
                matchedIds += resolvedId
            }
            val configured = runtime.isConfigured()
            val reachable =
                if (configured) {
                    runtime.lastReconcileHealth()?.reachable
                        ?: (runtime.connectionState() == ConnectionState.Connected)
                } else {
                    false
                }
            summaries +=
                MediaServerSummaryDto(
                    id = resolvedId ?: runtime.serverType,
                    name = matchedRecord?.name ?: defaultDisplayName(runtime.serverType),
                    serverType = runtime.serverType,
                    configured = configured,
                    reachable = reachable,
                    websocketEnabled = if (configured) runtime.websocketEnabled() else false,
                    websocketState = if (configured) runtime.connectionState().toStatusLabel() else "DISCONNECTED",
                )
        }

        for (record in dbServers) {
            if (record.id !in matchedIds) {
                summaries +=
                    MediaServerSummaryDto(
                        id = record.id,
                        name = record.name,
                        serverType = record.serverType.lowercase(),
                        configured = false,
                        reachable = false,
                        websocketEnabled = false,
                        websocketState = "DISCONNECTED",
                    )
            }
        }

        return MediaServersResponse(servers = summaries)
    }

    suspend fun listAdminServers(): AdminServersResponse {
        val dbServers = mediaServerRepository.findAll().getOrNull().orEmpty()
        val dataCounts = mediaServerRepository.countAllServerData().getOrNull().orEmpty()
        val matchedIds = linkedSetOf<String>()
        val items = mutableListOf<AdminServerStatusDto>()

        for (runtime in runtimes) {
            val matchedRecord = matchRecordForRuntime(runtime, dbServers)
            val resolvedId = runtime.cachedServerIdOrNull() ?: matchedRecord?.id
            if (resolvedId != null) {
                matchedIds += resolvedId
            }
            items += buildRuntimeAdminStatus(runtime, matchedRecord, resolvedId, dataCounts)
        }

        for (record in dbServers) {
            if (record.id !in matchedIds) {
                items += buildUnmatchedAdminStatus(record, dataCounts)
            }
        }

        return AdminServersResponse(servers = items)
    }

    private suspend fun buildRuntimeAdminStatus(
        runtime: ManagedMediaServerRuntime,
        matchedRecord: MediaServerRecord?,
        resolvedId: String?,
        dataCounts: Map<String, ServerDataCounts>,
    ): AdminServerStatusDto {
        val configured = runtime.isConfigured()
        val credentialSource = resolveCredentialSource(runtime, matchedRecord)
        val baseUrl = runtime.currentBaseUrl() ?: matchedRecord?.baseUrl
        val machineId = runtime.currentMachineId() ?: matchedRecord?.machineId
        val healthResult = if (configured) runtime.adapter.checkHealth() else null
        val lastHealth = runtime.lastReconcileHealth()

        val reachable = healthResult?.isRight() ?: false
        val statusMessage =
            when {
                !configured -> unconfiguredStatusMessage(runtime.serverType, matchedRecord)
                healthResult is Either.Left -> healthResult.value.message
                lastHealth != null && !lastHealth.reachable -> lastHealth.errorMessage
                else -> null
            }
        val serverName =
            healthResult?.getOrNull()?.friendlyName?.takeIf { it.isNotBlank() }
                ?: matchedRecord?.name
                ?: defaultDisplayName(runtime.serverType)

        val envManaged =
            (matchedRecord != null && isEnvManaged(matchedRecord)) ||
                credentialSource == PlexCredentialSource.ENV_OVERRIDE
        val hasStoredToken = matchedRecord != null && !matchedRecord.encryptedToken.isNullOrBlank()
        val canDisconnect = matchedRecord != null && !envManaged && (configured || hasStoredToken)
        val canRemove = matchedRecord != null && !envManaged && !configured && !hasStoredToken

        return AdminServerStatusDto(
            id = resolvedId,
            name = serverName,
            serverType = runtime.serverType,
            configured = configured,
            credentialSource = credentialSource,
            machineId = machineId,
            baseUrl = baseUrl,
            reachable = reachable,
            websocketEnabled = if (configured) runtime.websocketEnabled() else false,
            websocketState = if (configured) runtime.connectionState().toStatusLabel() else "DISCONNECTED",
            statusMessage = statusMessage,
            lastCheckedAtMs = lastHealth?.timestampMs,
            canDisconnect = canDisconnect,
            canRemove = canRemove,
            dataSummary = resolvedId?.let { (dataCounts[it] ?: ServerDataCounts()).toSummaryDto() },
        )
    }

    private fun buildUnmatchedAdminStatus(
        record: MediaServerRecord,
        dataCounts: Map<String, ServerDataCounts>,
    ): AdminServerStatusDto {
        val envManaged = isEnvManaged(record)
        val hasStoredToken = !record.encryptedToken.isNullOrBlank()
        return AdminServerStatusDto(
            id = record.id,
            name = record.name,
            serverType = record.serverType.lowercase(),
            configured = false,
            credentialSource =
                when {
                    envManaged -> PlexCredentialSource.ENV_OVERRIDE
                    hasStoredToken -> PlexCredentialSource.STORED_ENCRYPTED
                    else -> PlexCredentialSource.NOT_CONFIGURED
                },
            machineId = record.machineId,
            baseUrl = record.baseUrl,
            reachable = false,
            websocketEnabled = false,
            websocketState = "DISCONNECTED",
            statusMessage = "Historical or unconfigured server record",
            canDisconnect = !envManaged && hasStoredToken,
            canRemove = !envManaged && !hasStoredToken,
            dataSummary = (dataCounts[record.id] ?: ServerDataCounts()).toSummaryDto(),
        )
    }

    private fun ServerDataCounts.toSummaryDto(): ServerDataSummaryDto =
        ServerDataSummaryDto(plays = plays, users = users, libraries = libraries)

    private fun findRuntimeByCachedId(serverId: String): ManagedMediaServerRuntime? =
        runtimes.firstOrNull { it.isConfigured() && it.cachedServerIdOrNull() == serverId }

    private fun findRuntimeForRecord(record: MediaServerRecord): ManagedMediaServerRuntime? {
        val normalizedType = record.serverType.lowercase()
        val normalizedUrl = record.baseUrl.trimEnd('/')
        return runtimes.firstOrNull { runtime ->
            runtime.isConfigured() &&
                runtime.serverType.equals(normalizedType, ignoreCase = true) &&
                (
                    (runtime.currentMachineId() != null && runtime.currentMachineId() == record.machineId) ||
                        runtime.currentBaseUrl()?.trimEnd('/') == normalizedUrl
                    )
        } ?: runtimes.firstOrNull { it.isConfigured() && it.serverType.equals(normalizedType, ignoreCase = true) }
    }

    private fun matchRecordForRuntime(
        runtime: ManagedMediaServerRuntime,
        dbServers: List<MediaServerRecord>,
    ): MediaServerRecord? {
        val cachedId = runtime.cachedServerIdOrNull()
        val machineId = runtime.currentMachineId()
        val baseUrl = runtime.currentBaseUrl()?.trimEnd('/')
        val type = runtime.serverType
        return cachedId?.let { id -> dbServers.firstOrNull { it.id == id } }
            ?: machineId?.let { mid -> dbServers.firstOrNull { it.machineId == mid } }
            ?: baseUrl?.let { url ->
                dbServers.firstOrNull {
                    it.serverType.equals(type, ignoreCase = true) && it.baseUrl.trimEnd('/') == url
                }
            }
            ?: dbServers.findActiveStoredServer(type)
            ?: dbServers
                .filter { it.serverType.equals(type, ignoreCase = true) }
                .maxWithOrNull(
                    compareBy<MediaServerRecord> { it.updatedAtMs }
                        .thenBy { it.createdAtMs }
                        .thenBy { it.id },
                )
    }

    private fun resolveCredentialSource(
        runtime: ManagedMediaServerRuntime,
        matchedRecord: MediaServerRecord?,
    ): PlexCredentialSource =
        when (runtime) {
            is PlexRuntimeManager -> runtime.currentCredentialSource()
            is JellyfinRuntimeManager ->
                if (jellyfinConfig.isConfigured) {
                    PlexCredentialSource.ENV_OVERRIDE
                } else if (runtime.isConfigured()) {
                    runtime.currentCredentialSource()
                } else if (matchedRecord != null && !matchedRecord.encryptedToken.isNullOrBlank()) {
                    PlexCredentialSource.STORED_ENCRYPTED
                } else {
                    runtime.currentCredentialSource()
                }
            else ->
                if (runtime.serverType.equals("jellyfin", ignoreCase = true) && jellyfinConfig.isConfigured) {
                    PlexCredentialSource.ENV_OVERRIDE
                } else if (matchedRecord != null && !matchedRecord.encryptedToken.isNullOrBlank()) {
                    PlexCredentialSource.STORED_ENCRYPTED
                } else {
                    PlexCredentialSource.NOT_CONFIGURED
                }
        }

    private fun unconfiguredStatusMessage(
        serverType: String,
        matchedRecord: MediaServerRecord?,
    ): String =
        when (serverType.lowercase()) {
            "plex" ->
                when {
                    matchedRecord != null && !matchedRecord.encryptedToken.isNullOrBlank() -> "Plex adapter is not active"
                    matchedRecord != null -> "Disconnected — playback history, users, and libraries are kept"
                    else -> "No Plex server configured"
                }

            "jellyfin" ->
                when {
                    matchedRecord != null && !matchedRecord.encryptedToken.isNullOrBlank() -> "Jellyfin adapter is not active"
                    matchedRecord != null -> "Disconnected — playback history, users, and libraries are kept"
                    else -> "No Jellyfin server configured"
                }
            else -> "Server is not configured"
        }

    private fun defaultDisplayName(serverType: String): String =
        when (serverType.lowercase()) {
            "plex" -> "Plex"
            "jellyfin" -> "Jellyfin"
            else -> serverType.replaceFirstChar { it.uppercase() }
        }

    private fun ConnectionState.toStatusLabel(): String =
        when (this) {
            ConnectionState.Connected -> "CONNECTED"
            is ConnectionState.Connecting -> "CONNECTING"
            is ConnectionState.Backoff -> "BACKOFF"
            ConnectionState.Disconnected -> "DISCONNECTED"
            ConnectionState.Stopped -> "STOPPED"
        }

    private companion object {
        const val MERGED_EVENT_BUFFER_CAPACITY = 256
        const val MERGED_HEALTH_BUFFER_CAPACITY = 64
    }
}
