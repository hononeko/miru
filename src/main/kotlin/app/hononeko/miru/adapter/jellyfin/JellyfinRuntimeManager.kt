package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.adapter.ManagedMediaServerRuntime
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.adapter.plex.DelegatingMediaServerAdapter
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val jellyfinRuntimeLogger = LoggerFactory.getLogger("app.hononeko.miru.adapter.jellyfin.JellyfinRuntimeManager")

/**
 * Manages the active Jellyfin adapter, WebSocket listener, and [SessionReconciler] lifecycle,
 * supporting zero-config startup when [JellyfinConfig.isConfigured] is false.
 */
class JellyfinRuntimeManager(
    private val jellyfinConfig: JellyfinConfig,
    databaseHandle: DatabaseHandle,
    sessionConfig: SessionConfig = SessionConfig(),
    private val mediaServerRepository: MediaServerRepository = ExposedMediaServerRepository(databaseHandle),
    private val httpClientEngineFactory: () -> HttpClientEngine = { Java.create() },
    private val wsClientFactory: (() -> HttpClient)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : RealtimeLifecycle,
    ManagedMediaServerRuntime {
    private data class ActivePipeline(
        val scope: CoroutineScope,
        val restClient: HttpClient,
        val wsClient: HttpClient?,
        val listener: JellyfinNotificationListener?,
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
                    .onFailure { jellyfinRuntimeLogger.debug("Failed to close Jellyfin WebSocket client: {}", it.message) }
                runCatching { restClient.close() }
                    .onFailure { jellyfinRuntimeLogger.debug("Failed to close Jellyfin HTTP client: {}", it.message) }
            }
        }
    }

    override val serverType: String = JellyfinMediaServerAdapter.PROVIDER_NAME
    override val adapter: DelegatingMediaServerAdapter =
        DelegatingMediaServerAdapter(serverType = JellyfinMediaServerAdapter.PROVIDER_NAME)

    private val _reconcileHealth =
        MutableSharedFlow<ReconcileHealthSignal>(
            extraBufferCapacity = RECONCILE_HEALTH_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val reconcileHealth: SharedFlow<ReconcileHealthSignal> = _reconcileHealth.asSharedFlow()

    private val reloadMutex = Mutex()
    private val serverIdMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jellyfin-runtime-manager"))

    @Volatile
    private var activeBaseUrl: String? = null

    @Volatile
    private var activeMachineId: String? = null

    @Volatile
    private var cachedServerId: String? = null

    @Volatile
    private var latestReconcileHealth: ReconcileHealthSignal? = null

    @Volatile
    private var onReloadLibrarySync: (suspend () -> Unit)? = null

    private var activePipeline: ActivePipeline? = null
    private var startupJob: Job? = null

    override val serverIdProvider: suspend () -> Either<MiruError, String> = {
        cachedServerId?.right()
            ?: serverIdMutex.withLock {
                cachedServerId?.right()
                    ?: resolveCurrentServerId().onRight { resolved -> cachedServerId = resolved }
            }
    }

    override val sessionEngine: SessionStateEngine =
        SessionStateEngine(
            serverIdProvider = serverIdProvider,
            userRepository = ExposedUserRepository(databaseHandle),
            activeSessionRepository = ExposedActiveSessionRepository(databaseHandle),
            playbackHistoryRepository = ExposedPlaybackHistoryRepository(databaseHandle),
            config = sessionConfig,
            clock = clock,
            idGenerator = idGenerator,
        )

    override fun setOnReloadLibrarySync(callback: (suspend () -> Unit)?) {
        onReloadLibrarySync = callback
    }

    override fun isConfigured(): Boolean = adapter.currentDelegate() != null

    override fun currentBaseUrl(): String? = activeBaseUrl

    override fun currentMachineId(): String? = activeMachineId

    override fun cachedServerIdOrNull(): String? = cachedServerId

    override fun websocketEnabled(): Boolean = jellyfinConfig.websocketEnabled

    override fun connectionState(): ConnectionState =
        activePipeline?.listener?.connectionState?.value ?: ConnectionState.Disconnected

    override fun lastReconcileHealth(): ReconcileHealthSignal? = latestReconcileHealth

    override suspend fun currentServerSummary(): WebhookServerPayload? {
        if (!isConfigured()) return null
        val machineId = activeMachineId
        val baseUrl = activeBaseUrl?.trimEnd('/')
        val servers = mediaServerRepository.findAll().getOrNull().orEmpty()
        val matched =
            machineId?.let { mid -> servers.firstOrNull { it.machineId == mid } }
                ?: baseUrl?.let { url -> servers.firstOrNull { it.baseUrl.trimEnd('/') == url } }
                ?: servers.firstOrNull { it.serverType.equals(serverType, ignoreCase = true) }
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
            if (!jellyfinConfig.isConfigured) {
                jellyfinRuntimeLogger.debug("Jellyfin is not configured; Jellyfin runtime remains dormant")
                false.right()
            } else {
                activateLocked(triggerSync = true)
                true.right()
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
            latestReconcileHealth = null
        }
        managerScope.cancel()
    }

    private suspend fun activateLocked(triggerSync: Boolean) {
        stopActivePipelineLocked()

        val normalizedBaseUrl = jellyfinConfig.baseUrl.trim().trimEnd('/')
        val runtimeConfig = jellyfinConfig.copy(baseUrl = normalizedBaseUrl)
        val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jellyfin-realtime"))
        val restClient = createJellyfinHttpClient(engine = httpClientEngineFactory())
        val wsClient =
            if (runtimeConfig.websocketEnabled) {
                wsClientFactory?.invoke() ?: createJellyfinWebSocketClient()
            } else {
                null
            }

        val listener =
            wsClient?.let {
                JellyfinNotificationListener(
                    connector = KtorJellyfinNotificationConnector(httpClient = it, config = runtimeConfig),
                    scope = pipelineScope,
                    secretToRedact = runtimeConfig.apiKey,
                )
            }
        val forwarderJob =
            listener?.let { activeListener ->
                pipelineScope.launch(CoroutineName("jellyfin-event-forwarder")) {
                    activeListener.events.collect { event ->
                        adapter.emitEvent(event)
                    }
                }
            }
        val concreteAdapter =
            JellyfinMediaServerAdapter(
                config = runtimeConfig,
                httpClient = restClient,
                eventSource = listener?.events ?: emptyFlow(),
            )
        val sink: ActiveSessionsSink = sessionEngine
        val reconciler =
            SessionReconciler(
                adapter = concreteAdapter,
                sink = sink,
                scope = pipelineScope,
                interval = runtimeConfig.pollIntervalSeconds.seconds,
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
                                    serverId = cachedServerId,
                                )
                            },
                            ifRight = {
                                ReconcileHealthSignal(
                                    reachable = true,
                                    errorMessage = null,
                                    timestampMs = nowMs,
                                    serverId = cachedServerId,
                                )
                            },
                        )
                    latestReconcileHealth = signal
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
        activeMachineId = null
        cachedServerId = null
        latestReconcileHealth = null
        adapter.setDelegate(concreteAdapter)

        listener?.start()
        reconciler.start()

        jellyfinRuntimeLogger.info("Activated Jellyfin runtime for {}", normalizedBaseUrl)

        if (triggerSync) {
            onReloadLibrarySync?.let { syncCallback ->
                managerScope.launch(CoroutineName("jellyfin-post-reload-sync")) {
                    try {
                        syncCallback()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        jellyfinRuntimeLogger.warn("Post-reload Jellyfin library sync failed: {}", e.message)
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
                ?: return MiruError.UpstreamUnavailable(serverType, "Jellyfin server is not configured").left()

        return adapter.checkHealth().fold(
            ifRight = { identity ->
                activeMachineId = identity.machineIdentifier
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
