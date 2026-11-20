package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.config.PlexConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val WS_PING_INTERVAL_MS = 30_000L
private const val WS_CONNECT_TIMEOUT_MS = 10_000L
private const val EVENT_BUFFER_CAPACITY = 256
private const val NOTIFICATIONS_PATH = "/:/websockets/notifications"
private const val PLEX_TOKEN_HEADER = "X-Plex-Token"

/**
 * Dedicated client for the long-lived notification socket. Kept separate from the REST client so its
 * request timeout never terminates a healthy WebSocket session.
 */
fun createPlexWebSocketClient(
    engine: HttpClientEngine =
        CIO.create {
            // 0 disables the per-request timeout so idle-but-healthy sessions are not torn down;
            // liveness is detected by WebSocket ping/pong instead.
            requestTimeout = 0
            endpoint.connectTimeout = WS_CONNECT_TIMEOUT_MS
        },
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(WebSockets) {
            pingIntervalMillis = WS_PING_INTERVAL_MS
        }
    }

/** Builds the notifications endpoint (`ws(s)://host:port/:/websockets/notifications`) from the Plex base URL. */
fun plexNotificationsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    val uri = URI(trimmed)
    val wsScheme = if (uri.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
    return "$wsScheme${trimmed.substring(uri.scheme.length)}$NOTIFICATIONS_PATH"
}

/**
 * Opens a single notification session. Implementations must invoke [onConnected] once the socket is
 * established, [onFrame] for each text frame, and return (or throw) when the session ends.
 */
fun interface PlexNotificationConnector {
    suspend fun connect(
        onConnected: suspend () -> Unit,
        onFrame: suspend (String) -> Unit,
    )
}

class KtorPlexNotificationConnector(
    private val httpClient: HttpClient,
    config: PlexConfig,
) : PlexNotificationConnector {
    private val url = plexNotificationsUrl(config.baseUrl)
    private val token = config.token

    override suspend fun connect(
        onConnected: suspend () -> Unit,
        onFrame: suspend (String) -> Unit,
    ) {
        httpClient.webSocket(urlString = url, request = { header(PLEX_TOKEN_HEADER, token) }) {
            onConnected()
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    onFrame(frame.readText())
                }
            }
        }
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(
        val attempt: Int,
    ) : ConnectionState

    data object Connected : ConnectionState

    data class Backoff(
        val attempt: Int,
        val delay: Duration,
    ) : ConnectionState

    data object Stopped : ConnectionState
}

/**
 * Consumes Plex's real-time notification WebSocket, exposing parsed [MediaServerEvent]s as a hot flow and
 * reconnecting with exponential backoff + jitter whenever the socket drops.
 */
class PlexNotificationListener(
    private val connector: PlexNotificationConnector,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val random: Random = Random.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val secretToRedact: String? = null,
) {
    private val logger = LoggerFactory.getLogger(PlexNotificationListener::class.java)
    private val lock = Any()
    private var job: Job? = null

    private val _events =
        MutableSharedFlow<MediaServerEvent>(
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<MediaServerEvent> = _events.asSharedFlow()

    private val _reconnected =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits each time the socket is re-established after a previous successful connection. */
    val reconnected: Flow<Unit> = _reconnected.asSharedFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Starts the connection loop. Idempotent: repeated calls return the already-running job. */
    fun start(): Job =
        synchronized(lock) {
            job?.takeIf { it.isActive }
                ?: scope.launch(CoroutineName("plex-ws-listener")) { runLoop() }.also { job = it }
        }

    suspend fun stop() {
        val current = synchronized(lock) { job.also { job = null } }
        current?.cancelAndJoin()
        transition(ConnectionState.Stopped, reason = "stopped")
    }

    private suspend fun runLoop() {
        var failures = 0
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            transition(ConnectionState.Connecting(failures + 1), reason = "connecting")
            var connectedAt: TimeMark? = null
            var receivedFrame = false

            val failure =
                try {
                    connector.connect(
                        onConnected = {
                            connectedAt = timeSource.markNow()
                            transition(ConnectionState.Connected, reason = "connected")
                            if (everConnected) {
                                _reconnected.tryEmit(Unit)
                            }
                            everConnected = true
                        },
                        onFrame = { text ->
                            receivedFrame = true
                            handleFrame(text)
                        },
                    )
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }

            val stable = receivedFrame || connectedAt?.let { it.elapsedNow() >= backoff.resetAfter } == true
            failures = if (stable) 1 else failures + 1

            val authFailure = failure != null && isAuthFailure(failure)
            val delay = if (authFailure) backoff.max else backoff.delayFor(failures, random)
            val reason =
                when {
                    authFailure -> "auth rejected by Plex (check PLEX_TOKEN)"
                    failure != null -> sanitize(failure.message ?: failure::class.simpleName)
                    else -> "socket closed"
                }
            if (authFailure) {
                logger.error(
                    "Plex notification socket authentication failed {} {}",
                    kv("plex.ws.state", "auth_error"),
                    kv("reason", reason),
                )
            }
            transition(ConnectionState.Backoff(failures, delay), reason = reason)
            delay(delay)
        }
    }

    private fun handleFrame(text: String) {
        PlexNotificationParser.parse(text).fold(
            ifLeft = { error -> logger.debug("Dropping unparseable Plex notification: {}", error.message) },
            ifRight = { events -> events.forEach { _events.tryEmit(it) } },
        )
    }

    private fun transition(
        state: ConnectionState,
        reason: String,
    ) {
        _state.value = state
        val name =
            when (state) {
                is ConnectionState.Disconnected -> "disconnected"
                is ConnectionState.Connecting -> "connecting"
                is ConnectionState.Connected -> "connected"
                is ConnectionState.Backoff -> "backoff"
                is ConnectionState.Stopped -> "stopped"
            }
        val attempt =
            when (state) {
                is ConnectionState.Connecting -> state.attempt
                is ConnectionState.Backoff -> state.attempt
                else -> null
            }
        val delayMs = (state as? ConnectionState.Backoff)?.delay?.inWholeMilliseconds
        val event = if (state is ConnectionState.Backoff) logger.atWarn() else logger.atInfo()
        event
            .setMessage("Plex notification socket state change {} {} {} {}")
            .addArgument(kv("plex.ws.state", name))
            .addArgument(kv("attempt", attempt))
            .addArgument(kv("delayMs", delayMs))
            .addArgument(kv("reason", reason))
            .log()
    }

    private fun isAuthFailure(e: Throwable): Boolean {
        if (e is ResponseException) {
            return e.response.status == HttpStatusCode.Unauthorized || e.response.status == HttpStatusCode.Forbidden
        }
        return AUTH_STATUS_REGEX.containsMatchIn(e.message.orEmpty())
    }

    private fun sanitize(raw: String?): String {
        val text = raw?.takeIf { it.isNotBlank() } ?: "unexpected socket error"
        val secret = secretToRedact
        return if (!secret.isNullOrEmpty()) text.replace(secret, "***") else text
    }

    private companion object {
        val AUTH_STATUS_REGEX = Regex("\\b(401|403)\\b")
    }
}
