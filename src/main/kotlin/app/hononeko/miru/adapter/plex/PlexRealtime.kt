package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.config.PlexConfig
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
import app.hononeko.miru.session.LoggingActiveSessionsSink
import app.hononeko.miru.session.SessionReconciler
import app.hononeko.miru.session.SessionStateEngine
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("app.hononeko.miru.adapter.plex.PlexRealtime")

/** Lifecycle of the real-time pipeline (notification listener + session reconciler). */
interface RealtimeLifecycle {
    fun start()

    suspend fun stop()
}

class PlexRealtime(
    private val listener: PlexNotificationListener?,
    private val reconciler: SessionReconciler,
    private val ownedScope: CoroutineScope? = null,
    private val onClose: () -> Unit = {},
) : RealtimeLifecycle {
    override fun start() {
        listener?.start()
        reconciler.start()
    }

    override suspend fun stop() {
        try {
            coroutineScope {
                launch { reconciler.stop() }
                listener?.let { active -> launch { active.stop() } }
            }
        } finally {
            ownedScope?.cancel()
            onClose()
        }
    }
}

/** Wiring for the Plex adapter and its real-time pipeline. */
class PlexRuntime(
    val adapter: MediaServerAdapter,
    val realtime: PlexRealtime,
    val sessionEngine: SessionStateEngine? = null,
)

fun buildPlexRuntime(
    config: PlexConfig,
    sink: ActiveSessionsSink? = null,
    databaseHandle: DatabaseHandle? = null,
    sessionConfig: SessionConfig = SessionConfig(),
    httpClientEngine: HttpClientEngine? = null,
): PlexRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plex-realtime"))
    val restClient = createPlexHttpClient(engine = httpClientEngine ?: CIO.create())
    val wsClient = if (config.websocketEnabled) createPlexWebSocketClient() else null

    val listener =
        wsClient?.let {
            PlexNotificationListener(
                connector = KtorPlexNotificationConnector(httpClient = it, config = config),
                scope = scope,
                secretToRedact = config.token,
            )
        }
    val adapter =
        PlexMediaServerAdapter(
            config = config,
            httpClient = restClient,
            eventSource = listener?.events ?: emptyFlow(),
        )
    val sessionEngine =
        if (sink == null && databaseHandle != null) {
            val mediaServerRepository = ExposedMediaServerRepository(databaseHandle)
            val userRepository = ExposedUserRepository(databaseHandle)
            val activeSessionRepository = ExposedActiveSessionRepository(databaseHandle)
            val playbackHistoryRepository = ExposedPlaybackHistoryRepository(databaseHandle)
            SessionStateEngine(
                serverIdProvider =
                    createLazyServerIdProvider(
                        adapter = adapter,
                        baseUrl = config.baseUrl,
                        mediaServerRepository = mediaServerRepository,
                    ),
                userRepository = userRepository,
                activeSessionRepository = activeSessionRepository,
                playbackHistoryRepository = playbackHistoryRepository,
                config = sessionConfig,
            )
        } else {
            sink as? SessionStateEngine
        }
    val resolvedSink = sink ?: sessionEngine ?: LoggingActiveSessionsSink()
    val reconciler =
        SessionReconciler(
            adapter = adapter,
            sink = resolvedSink,
            scope = scope,
            interval = config.pollIntervalSeconds.seconds,
            reconnectSignals = listener?.reconnected ?: emptyFlow(),
        )
    val realtime =
        PlexRealtime(
            listener = listener,
            reconciler = reconciler,
            ownedScope = scope,
            onClose = {
                runCatching { wsClient?.close() }
                    .onFailure { logger.debug("Failed to close Plex WebSocket client: {}", it.message) }
                runCatching { restClient.close() }
                    .onFailure { logger.debug("Failed to close Plex HTTP client: {}", it.message) }
            },
        )
    return PlexRuntime(adapter = adapter, realtime = realtime, sessionEngine = sessionEngine)
}

internal fun createLazyServerIdProvider(
    adapter: MediaServerAdapter,
    baseUrl: String,
    mediaServerRepository: MediaServerRepository,
    clock: () -> Long = System::currentTimeMillis,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
): suspend () -> Either<MiruError, String> {
    val mutex = Mutex()
    var cachedServerId: String? = null
    val normalizedBaseUrl = baseUrl.trimEnd('/')

    return {
        cachedServerId?.right()
            ?: mutex.withLock {
                cachedServerId?.right()
                    ?: resolveAndCacheServerId(
                        adapter = adapter,
                        normalizedBaseUrl = normalizedBaseUrl,
                        mediaServerRepository = mediaServerRepository,
                        clock = clock,
                        idGenerator = idGenerator,
                    ).onRight { resolved -> cachedServerId = resolved }
            }
    }
}

private suspend fun resolveAndCacheServerId(
    adapter: MediaServerAdapter,
    normalizedBaseUrl: String,
    mediaServerRepository: MediaServerRepository,
    clock: () -> Long,
    idGenerator: () -> String,
): Either<MiruError, String> =
    adapter.checkHealth().fold(
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
                        server.serverType == adapter.serverType &&
                            server.baseUrl.trimEnd('/') == normalizedBaseUrl
                    }
                matching?.id?.right() ?: healthError.left()
            }
        },
    )
