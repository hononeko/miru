package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.MediaUserLibraryAccess
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.PlexCredentialSource
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.session.ActiveSessionsSink
import app.hononeko.miru.session.SessionReconciler
import app.hononeko.miru.session.SessionStateEngine
import app.hononeko.miru.webhook.ReconcileHealthSignal
import app.hononeko.miru.webhook.WebhookServerPayload
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val runtimeLogger = LoggerFactory.getLogger("app.hononeko.miru.adapter.plex.PlexRuntimeManager")

/**
 * Thread-safe delegating [MediaServerAdapter] with a stable [events] flow so downstream consumers
 * ([SessionStateEngine], [app.hononeko.miru.library.LibraryStatsCollector], SSE routes) stay subscribed across hot-reloads.
 */
class DelegatingMediaServerAdapter(
    initialDelegate: MediaServerAdapter? = null,
) : MediaServerAdapter {
    override val serverType: String = PLEX_SERVER_TYPE

    private val delegateRef = AtomicReference<MediaServerAdapter?>(initialDelegate)
    private val _events =
        MutableSharedFlow<MediaServerEvent>(
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    fun setDelegate(adapter: MediaServerAdapter?) {
        delegateRef.set(adapter)
    }

    fun currentDelegate(): MediaServerAdapter? = delegateRef.get()

    suspend fun emitEvent(event: MediaServerEvent) {
        _events.emit(event)
    }

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> =
        withDelegate { it.checkHealth() }

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> =
        withDelegate { it.fetchActiveSessions() }

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> =
        withDelegate { it.fetchUsers() }

    override suspend fun fetchUserLibraryAccess(): Either<MiruError, List<MediaUserLibraryAccess>> =
        withDelegate { it.fetchUserLibraryAccess() }

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> =
        withDelegate { it.fetchLibrarySections() }

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> =
        withDelegate { it.fetchLibraryCount(sectionKey, mediaTypeCode) }

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> =
        withDelegate { it.fetchLibraryItems(sectionKey, start, size, mediaTypeCode) }

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> =
        withDelegate { it.fetchMediaDetails(ratingKey) }

    override suspend fun fetchMediaSectionKey(ratingKey: String): Either<MiruError, String?> =
        withDelegate { it.fetchMediaSectionKey(ratingKey) }

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> =
        withDelegate { it.fetchArtwork(path, width, height) }

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> =
        withDelegate { it.terminateSession(sessionId, reason) }

    override fun events(): Flow<MediaServerEvent> = _events.asSharedFlow()

    private inline fun <T> withDelegate(block: (MediaServerAdapter) -> Either<MiruError, T>): Either<MiruError, T> {
        val current = delegateRef.get() ?: return notConfiguredError().left()
        return block(current)
    }

    private fun notConfiguredError(): MiruError.UpstreamUnavailable =
        MiruError.UpstreamUnavailable(
            provider = PLEX_SERVER_TYPE,
            message = "Plex server is not configured",
        )

    private companion object {
        const val PLEX_SERVER_TYPE = "plex"
        const val EVENT_BUFFER_CAPACITY = 256
    }
}

/**
 * Manages the active Plex adapter, WebSocket listener, and [SessionReconciler] lifecycle,
 * supporting zero-config startup and live hot-reload after Plex PIN OAuth server selection.
 */
class PlexRuntimeManager(
    private val plexConfig: PlexConfig,
    databaseHandle: DatabaseHandle,
    sessionConfig: SessionConfig = SessionConfig(),
    private val mediaServerRepository: MediaServerRepository = ExposedMediaServerRepository(databaseHandle),
    private val httpClientEngineFactory: () -> HttpClientEngine = { Java.create() },
    private val wsClientFactory: (() -> HttpClient)? = null,
    private val plexTvBaseUrl: String? = plexConfig.plexTvBaseUrl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : RealtimeLifecycle {
    private data class ActivePipeline(
        val scope: CoroutineScope,
        val restClient: HttpClient,
        val wsClient: HttpClient?,
        val listener: PlexNotificationListener?,
        val reconciler: SessionReconciler,
        val eventForwarderJob: Job?,
    ) {
        suspend fun stopAndClose() {
            try {
                coroutineScope {
                    launch { reconciler.stop() }
                    listener?.let { active -> launch { active.stop() } }
                }
                eventForwarderJob?.cancelAndJoin()
            } finally {
                scope.cancel()
                runCatching { wsClient?.close() }
                    .onFailure { runtimeLogger.debug("Failed to close Plex WebSocket client: {}", it.message) }
                runCatching { restClient.close() }
                    .onFailure { runtimeLogger.debug("Failed to close Plex HTTP client: {}", it.message) }
            }
        }
    }

    val adapter: DelegatingMediaServerAdapter = DelegatingMediaServerAdapter()

    private val _reconcileHealth =
        MutableSharedFlow<ReconcileHealthSignal>(
            extraBufferCapacity = RECONCILE_HEALTH_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val reconcileHealth: SharedFlow<ReconcileHealthSignal> = _reconcileHealth.asSharedFlow()

    private val reloadMutex = Mutex()
    private val serverIdMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plex-runtime-manager"))

    @Volatile
    private var activeBaseUrl: String? = null

    @Volatile
    private var activeMachineId: String? = null

    @Volatile
    private var cachedServerId: String? = null

    @Volatile
    private var activeCredentialSource: PlexCredentialSource = PlexCredentialSource.NOT_CONFIGURED

    @Volatile
    private var onReloadLibrarySync: (suspend () -> Unit)? = null

    private var activePipeline: ActivePipeline? = null
    private var startupJob: Job? = null

    val serverIdProvider: suspend () -> Either<MiruError, String> = {
        cachedServerId?.right()
            ?: serverIdMutex.withLock {
                cachedServerId?.right()
                    ?: resolveCurrentServerId().onRight { resolved -> cachedServerId = resolved }
            }
    }

    val sessionEngine: SessionStateEngine =
        SessionStateEngine(
            serverIdProvider = serverIdProvider,
            userRepository = ExposedUserRepository(databaseHandle),
            activeSessionRepository = ExposedActiveSessionRepository(databaseHandle),
            playbackHistoryRepository = ExposedPlaybackHistoryRepository(databaseHandle),
            config = sessionConfig,
            clock = clock,
            idGenerator = idGenerator,
        )

    fun setOnReloadLibrarySync(callback: (suspend () -> Unit)?) {
        onReloadLibrarySync = callback
    }

    fun currentBaseUrl(): String? = activeBaseUrl

    fun currentMachineId(): String? = activeMachineId

    fun currentCredentialSource(): PlexCredentialSource = activeCredentialSource

    fun isConfigured(): Boolean = adapter.currentDelegate() != null

    suspend fun currentServerName(): String? = currentServerSummary()?.name

    suspend fun currentServerSummary(): WebhookServerPayload? {
        if (!isConfigured()) return null
        val machineId = activeMachineId
        val baseUrl = activeBaseUrl?.trimEnd('/')
        val servers = mediaServerRepository.findAll().getOrNull().orEmpty()
        val matched =
            machineId?.let { mid -> servers.firstOrNull { it.machineId == mid } }
                ?: baseUrl?.let { url -> servers.firstOrNull { it.baseUrl.trimEnd('/') == url } }
                ?: servers.firstOrNull { it.serverType.equals("plex", ignoreCase = true) }
                ?: servers.firstOrNull()
        val id = cachedServerId ?: matched?.id ?: return null
        val name = matched?.name?.trim()?.takeIf { it.isNotEmpty() }
        return WebhookServerPayload(id = id, name = name)
    }

    override fun start() {
        startupJob =
            managerScope.launch {
                initialize()
            }
    }

    suspend fun initialize(): Either<MiruError, Boolean> =
        reloadMutex.withLock {
            if (plexConfig.isStaticConfigured) {
                reloadLocked(
                    baseUrl = plexConfig.baseUrl,
                    token = plexConfig.token,
                    machineId = null,
                    source = PlexCredentialSource.ENV_OVERRIDE,
                    triggerSync = true,
                )
                true.right()
            } else {
                loadAndActivateStoredLocked(triggerSync = true)
            }
        }

    suspend fun reloadFromStoredCredentials(): Either<MiruError, Boolean> =
        reloadMutex.withLock {
            if (plexConfig.isStaticConfigured) {
                false.right()
            } else {
                loadAndActivateStoredLocked(triggerSync = true)
            }
        }

    suspend fun reload(
        baseUrl: String,
        token: String,
        machineId: String? = null,
        source: PlexCredentialSource = PlexCredentialSource.STORED_ENCRYPTED,
    ) {
        reloadMutex.withLock {
            reloadLocked(
                baseUrl = baseUrl,
                token = token,
                machineId = machineId,
                source = source,
                triggerSync = true,
            )
        }
    }

    override suspend fun stop() {
        val pendingStartup = startupJob
        startupJob = null
        pendingStartup?.cancelAndJoin()

        reloadMutex.withLock {
            stopActivePipelineLocked()
            adapter.setDelegate(null)
            activeBaseUrl = null
            activeMachineId = null
            cachedServerId = null
            activeCredentialSource = PlexCredentialSource.NOT_CONFIGURED
        }
        managerScope.cancel()
    }

    private suspend fun loadAndActivateStoredLocked(triggerSync: Boolean): Either<MiruError, Boolean> =
        either {
            val servers = mediaServerRepository.findAll().bind()
            val stored =
                servers.firstOrNull { server ->
                    server.serverType.equals("plex", ignoreCase = true) && !server.encryptedToken.isNullOrBlank()
                }
            if (stored == null) {
                runtimeLogger.info("No Plex server configured yet; Miru is running in zero-config mode awaiting Plex OAuth setup")
                return@either false
            }

            val encrypted = stored.encryptedToken ?: return@either false
            val decrypted =
                CredentialCrypto.decrypt(encrypted, plexConfig.encryptionKey).fold(
                    ifLeft = { error ->
                        runtimeLogger.warn(
                            "Found stored Plex credentials for '{}' but failed to decrypt ({}); " +
                                "waiting for valid MIRU_ENCRYPTION_KEY or re-auth",
                            stored.name,
                            error.message,
                        )
                        raise(error)
                    },
                    ifRight = { it },
                )

            reloadLocked(
                baseUrl = stored.baseUrl,
                token = decrypted,
                machineId = stored.machineId,
                source = PlexCredentialSource.STORED_ENCRYPTED,
                triggerSync = triggerSync,
            )
            true
        }

    private suspend fun reloadLocked(
        baseUrl: String,
        token: String,
        machineId: String?,
        source: PlexCredentialSource,
        triggerSync: Boolean,
    ) {
        stopActivePipelineLocked()

        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val runtimePlexConfig =
            plexConfig.copy(
                baseUrl = normalizedBaseUrl,
                token = token,
            )
        val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plex-realtime"))
        val restClient = createPlexHttpClient(engine = httpClientEngineFactory())
        val wsClient =
            if (runtimePlexConfig.websocketEnabled) {
                wsClientFactory?.invoke() ?: createPlexWebSocketClient()
            } else {
                null
            }

        val listener =
            wsClient?.let {
                PlexNotificationListener(
                    connector = KtorPlexNotificationConnector(httpClient = it, config = runtimePlexConfig),
                    scope = pipelineScope,
                    secretToRedact = token,
                )
            }
        val forwarderJob =
            listener?.let { activeListener ->
                pipelineScope.launch(CoroutineName("plex-event-forwarder")) {
                    activeListener.events.collect { event ->
                        adapter.emitEvent(event)
                    }
                }
            }
        val concreteAdapter =
            PlexMediaServerAdapter(
                config = runtimePlexConfig,
                httpClient = restClient,
                plexTvBaseUrl = plexTvBaseUrl,
                eventSource = listener?.events ?: emptyFlow(),
            )
        val sink: ActiveSessionsSink = sessionEngine
        val reconciler =
            SessionReconciler(
                adapter = concreteAdapter,
                sink = sink,
                scope = pipelineScope,
                interval = runtimePlexConfig.pollIntervalSeconds.seconds,
                reconnectSignals = listener?.reconnected ?: emptyFlow(),
                onReconcileOutcome = { outcome ->
                    val nowMs = clock()
                    val signal =
                        outcome.fold(
                            ifLeft = { err ->
                                ReconcileHealthSignal(
                                    reachable = false,
                                    errorMessage = err.message,
                                    timestampMs = nowMs,
                                )
                            },
                            ifRight = {
                                ReconcileHealthSignal(
                                    reachable = true,
                                    errorMessage = null,
                                    timestampMs = nowMs,
                                )
                            },
                        )
                    _reconcileHealth.tryEmit(signal)
                },
            )

        activePipeline =
            ActivePipeline(
                scope = pipelineScope,
                restClient = restClient,
                wsClient = wsClient,
                listener = listener,
                reconciler = reconciler,
                eventForwarderJob = forwarderJob,
            )

        activeBaseUrl = normalizedBaseUrl
        activeMachineId = machineId
        activeCredentialSource = source
        cachedServerId = null
        adapter.setDelegate(concreteAdapter)

        listener?.start()
        reconciler.start()

        runtimeLogger.info(
            "Activated Plex runtime for {} (source={})",
            normalizedBaseUrl,
            source,
        )

        if (triggerSync) {
            onReloadLibrarySync?.let { syncCallback ->
                managerScope.launch(CoroutineName("plex-post-reload-sync")) {
                    try {
                        syncCallback()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        runtimeLogger.warn("Post-reload library sync failed: {}", e.message)
                    }
                }
            }
        }
    }

    private suspend fun stopActivePipelineLocked() {
        val current = activePipeline
        activePipeline = null
        current?.stopAndClose()
    }

    private suspend fun resolveCurrentServerId(): Either<MiruError, String> {
        val normalizedBaseUrl =
            activeBaseUrl?.trimEnd('/')
                ?: return MiruError.UpstreamUnavailable("plex", "Plex server is not configured").left()

        return adapter.checkHealth().fold(
            ifRight = { identity ->
                val nowMs = clock()
                val record =
                    MediaServerRecord(
                        id = idGenerator(),
                        name = identity.friendlyName,
                        serverType = adapter.serverType,
                        machineId = identity.machineIdentifier,
                        baseUrl = normalizedBaseUrl,
                        createdAtMs = nowMs,
                        updatedAtMs = nowMs,
                    )
                mediaServerRepository.upsert(record).map { it.id }
            },
            ifLeft = { healthError ->
                mediaServerRepository.findAll().flatMap { servers ->
                    val matching =
                        servers.firstOrNull { server ->
                            server.serverType.equals(adapter.serverType, ignoreCase = true) &&
                                server.baseUrl.trimEnd('/') == normalizedBaseUrl
                        }
                    matching?.id?.right() ?: healthError.left()
                }
            },
        )
    }

    private companion object {
        const val RECONCILE_HEALTH_BUFFER_CAPACITY = 64
    }
}
