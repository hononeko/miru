package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.session.ActiveSessionsSink
import app.hononeko.miru.session.LoggingActiveSessionsSink
import app.hononeko.miru.session.SessionReconciler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration.Companion.seconds

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
            reconciler.stop()
            listener?.stop()
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
)

fun buildPlexRuntime(
    config: PlexConfig,
    sink: ActiveSessionsSink = LoggingActiveSessionsSink(),
): PlexRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plex-realtime"))
    val restClient = createPlexHttpClient()
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
    val reconciler =
        SessionReconciler(
            adapter = adapter,
            sink = sink,
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
                wsClient?.close()
                restClient.close()
            },
        )
    return PlexRuntime(adapter = adapter, realtime = realtime)
}
