package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.plex.BackoffPolicy
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.logging.kv
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val WS_PING_INTERVAL_MS = 30_000L
private const val WS_CONNECT_TIMEOUT_MS = 10_000L
private const val EVENT_BUFFER_CAPACITY = 256
private const val SOCKET_PATH = "/socket"
private const val DEFAULT_SESSIONS_START_DATA = "0,1500"

fun createJellyfinWebSocketClient(
    engine: HttpClientEngine =
        Java.create {
            config {
                connectTimeout(java.time.Duration.ofMillis(WS_CONNECT_TIMEOUT_MS))
            }
        },
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(WebSockets) {
            pingIntervalMillis = WS_PING_INTERVAL_MS
        }
    }

/** Builds the Jellyfin WebSocket URL (`ws(s)://host:port/socket?api_key=...&deviceId=...`). */
fun jellyfinWebSocketUrl(
    baseUrl: String,
    apiKey: String,
    deviceId: String = "miru",
): String {
    val trimmed = baseUrl.trimEnd('/')
    val uri = URI(trimmed)
    val wsScheme = if (uri.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
    val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
    val encodedDeviceId = URLEncoder.encode(deviceId.ifBlank { "miru" }, StandardCharsets.UTF_8)
    return "$wsScheme${trimmed.substring(uri.scheme.length)}$SOCKET_PATH?api_key=$encodedKey&deviceId=$encodedDeviceId"
}

/** Redacts any `api_key` query value and explicit secret literal from strings before logging. */
internal fun redactJellyfinSecrets(
    raw: String?,
    secret: String? = null,
): String {
    var text = raw?.takeIf { it.isNotBlank() } ?: "unexpected socket error"
    text = API_KEY_QUERY_REGEX.replace(text, "$1***")
    if (!secret.isNullOrEmpty()) {
        text = text.replace(secret, "***")
        val encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8)
        if (encoded.isNotEmpty()) {
            text = text.replace(encoded, "***")
        }
    }
    return text
}

private val API_KEY_QUERY_REGEX = Regex("([?&]api_key=)[^&\\s]+", RegexOption.IGNORE_CASE)

/**
 * Opens a single Jellyfin WebSocket session, invoking [onConnected] with a `sendText` callback
 * once connected, [onFrame] for each inbound text frame, and returning when the session ends.
 */
fun interface JellyfinNotificationConnector {
    suspend fun connect(
        onConnected: suspend (sendText: suspend (String) -> Unit) -> Unit,
        onFrame: suspend (String, suspend (String) -> Unit) -> Unit,
    )
}

class KtorJellyfinNotificationConnector(
    private val httpClient: HttpClient,
    config: JellyfinConfig,
) : JellyfinNotificationConnector {
    private val deviceId = config.clientIdentifier?.trim()?.takeIf { it.isNotEmpty() } ?: "miru"
    private val url = jellyfinWebSocketUrl(config.baseUrl, config.apiKey, deviceId)

    override suspend fun connect(
        onConnected: suspend (sendText: suspend (String) -> Unit) -> Unit,
        onFrame: suspend (String, suspend (String) -> Unit) -> Unit,
    ) {
        httpClient.webSocket(urlString = url) {
            val sendText: suspend (String) -> Unit = { payload ->
                send(Frame.Text(payload))
            }
            onConnected(sendText)
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    onFrame(frame.readText(), sendText)
                }
            }
        }
    }
}

/**
 * Consumes Jellyfin's `/socket` WebSocket, sends `SessionsStart`, responds to `ForceKeepAlive`,
 * emits [MediaServerEvent]s, and reconnects with exponential backoff + jitter.
 */
class JellyfinNotificationListener(
    private val connector: JellyfinNotificationConnector,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val random: Random = Random.Default,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val secretToRedact: String? = null,
    private val sessionsStartData: String = DEFAULT_SESSIONS_START_DATA,
) {
    private val logger = LoggerFactory.getLogger(JellyfinNotificationListener::class.java)
    private val lock = Any()
    private var job: Job? = null
    private val activeWsSessions = linkedMapOf<String, String?>()

    private val _events =
        MutableSharedFlow<MediaServerEvent>(
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<MediaServerEvent> = _events.asSharedFlow()

    private val _reconnected =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reconnected: Flow<Unit> = _reconnected.asSharedFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    fun start(): Job =
        synchronized(lock) {
            job?.takeIf { it.isActive }
                ?: scope.launch(CoroutineName("jellyfin-ws-listener")) { runLoop() }.also { job = it }
        }

    suspend fun stop() {
        val current = synchronized(lock) { job.also { job = null } }
        current?.cancelAndJoin()
        val stillStopped = synchronized(lock) { job == null }
        if (stillStopped) {
            transition(ConnectionState.Stopped, reason = "stopped")
        }
    }

    private suspend fun runLoop() {
        var failures = 0
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            transition(ConnectionState.Connecting(failures + 1), reason = "connecting")
            var connectedAt: TimeMark? = null
            var receivedFrame = false
            activeWsSessions.clear()

            val failure =
                try {
                    connector.connect(
                        onConnected = { sendText ->
                            connectedAt = timeSource.markNow()
                            sendText(
                                MiruJson.encodeToString(
                                    JellyfinWsOutboundFrame(
                                        messageType = "SessionsStart",
                                        data = sessionsStartData,
                                    ),
                                ),
                            )
                            transition(ConnectionState.Connected, reason = "connected")
                            if (everConnected) {
                                _reconnected.tryEmit(Unit)
                            }
                            everConnected = true
                        },
                        onFrame = { text, sendText ->
                            receivedFrame = true
                            handleFrame(text, sendText)
                        },
                    )
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }

            currentCoroutineContext().ensureActive()

            val stable = receivedFrame || connectedAt?.let { it.elapsedNow() >= backoff.resetAfter } == true
            failures = if (stable) 1 else failures + 1

            val authFailure = failure != null && isAuthFailure(failure)
            val delay = if (authFailure) backoff.max else backoff.delayFor(failures, random)
            val reason =
                when {
                    authFailure -> "auth rejected by Jellyfin (check JELLYFIN_API_KEY)"
                    failure != null -> redactJellyfinSecrets(failure.message ?: failure::class.simpleName, secretToRedact)
                    else -> "socket closed"
                }
            if (authFailure) {
                logger.error(
                    "Jellyfin notification socket authentication failed {} {}",
                    kv("jellyfin.ws.state", "auth_error"),
                    kv("reason", reason),
                )
            }
            transition(ConnectionState.Backoff(failures, delay), reason = reason)
            delay(delay)
        }
    }

    private suspend fun handleFrame(
        text: String,
        sendText: suspend (String) -> Unit,
    ) {
        JellyfinNotificationParser.parse(text).fold(
            ifLeft = { error ->
                logger.debug("Dropping unparseable Jellyfin notification: {}", redactJellyfinSecrets(error.message, secretToRedact))
            },
            ifRight = { result ->
                when (result) {
                    JellyfinWsParseResult.KeepAliveRequested -> {
                        sendText(MiruJson.encodeToString(JellyfinWsOutboundFrame(messageType = "KeepAlive")))
                    }

                    JellyfinWsParseResult.KeepAliveAck,
                    JellyfinWsParseResult.Ignored,
                    -> Unit

                    is JellyfinWsParseResult.SessionsUpdate -> {
                        val events = JellyfinNotificationParser.toPlayingEvents(result.sessions, activeWsSessions)
                        events.forEach { _events.tryEmit(it) }
                    }

                    is JellyfinWsParseResult.LibraryUpdate -> {
                        result.events.forEach { _events.tryEmit(it) }
                    }
                }
            },
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
            .setMessage("Jellyfin notification socket state change {} {} {} {}")
            .addArgument(kv("jellyfin.ws.state", name))
            .addArgument(kv("attempt", attempt))
            .addArgument(kv("delayMs", delayMs))
            .addArgument(kv("reason", redactJellyfinSecrets(reason, secretToRedact)))
            .log()
    }

    private fun isAuthFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { cause ->
            when (cause) {
                is ResponseException ->
                    cause.response.status == HttpStatusCode.Unauthorized ||
                        cause.response.status == HttpStatusCode.Forbidden
                is java.net.http.WebSocketHandshakeException ->
                    cause.response?.statusCode() == HttpStatusCode.Unauthorized.value ||
                        cause.response?.statusCode() == HttpStatusCode.Forbidden.value
                else -> AUTH_STATUS_REGEX.containsMatchIn(cause.message.orEmpty())
            }
        }

    private companion object {
        val AUTH_STATUS_REGEX =
            Regex("(?:status(?:\\s+code)?|HTTP(?:/\\d(?:\\.\\d)?)?|was)\\s+(401|403)\\b", RegexOption.IGNORE_CASE)
    }
}
