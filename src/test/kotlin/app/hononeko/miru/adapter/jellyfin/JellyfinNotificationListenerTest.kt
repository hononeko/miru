@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.plex.BackoffPolicy
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.config.JellyfinConfig
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

private const val SECRET_KEY = "jf-ws-super-secret-token"

private class ScriptedJellyfinConnector(
    private val script: suspend (
        attempt: Int,
        onConnected: suspend (suspend (String) -> Unit) -> Unit,
        onFrame: suspend (String, suspend (String) -> Unit) -> Unit,
    ) -> Unit,
) : JellyfinNotificationConnector {
    val attempts = AtomicInteger()
    val sentFrames = CopyOnWriteArrayList<String>()

    override suspend fun connect(
        onConnected: suspend (suspend (String) -> Unit) -> Unit,
        onFrame: suspend (String, suspend (String) -> Unit) -> Unit,
    ) {
        val attempt = attempts.incrementAndGet()
        val recordingSend: suspend (String) -> Unit = { msg -> sentFrames += msg }
        script(
            attempt,
            { cb -> onConnected { msg -> recordingSend(msg); cb(msg) } },
            { frame, sendCb -> onFrame(frame) { msg -> recordingSend(msg); sendCb(msg) } },
        )
    }
}

private inline fun captureListenerLogs(block: () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(JellyfinNotificationListener::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.toList()
}

class JellyfinNotificationListenerTest :
    FunSpec({
        test("parser maps ForceKeepAlive, Sessions diffs into PlayingEvent/STOPPED, and LibraryChanged into TimelineEvent") {
            JellyfinNotificationParser.parse("invalid-json").shouldBeLeft()
            JellyfinNotificationParser
                .parse("""{"MessageType":"ForceKeepAlive","Data":30}""")
                .shouldBeRight() shouldBe JellyfinWsParseResult.KeepAliveRequested
            JellyfinNotificationParser
                .parse("""{"MessageType":"UnknownMsg"}""")
                .shouldBeRight() shouldBe JellyfinWsParseResult.Ignored

            val activeSessions = linkedMapOf<String, String?>()
            val sessionFrame =
                """
                {
                  "MessageType": "Sessions",
                  "Data": [
                    {
                      "Id": "jf-s1",
                      "UserId": "u1",
                      "PlayState": { "PositionTicks": 50000000, "IsPaused": false },
                      "NowPlayingItem": { "Id": "m1", "Name": "Matrix" }
                    }
                  ]
                }
                """.trimIndent()
            val update =
                JellyfinNotificationParser
                    .parse(sessionFrame)
                    .shouldBeRight()
                    .shouldBeInstanceOf<JellyfinWsParseResult.SessionsUpdate>()
            val startedEvents = JellyfinNotificationParser.toPlayingEvents(update.sessions, activeSessions)
            startedEvents shouldHaveSize 1
            startedEvents[0].sessionKey shouldBe "jf-s1"
            startedEvents[0].ratingKey shouldBe "m1"
            startedEvents[0].state shouldBe PlaybackState.PLAYING
            startedEvents[0].viewOffsetMs shouldBe 5_000L

            // Session vanishes from next Sessions frame -> emits STOPPED
            val emptyUpdate =
                JellyfinNotificationParser
                    .parse("""{"MessageType":"Sessions","Data":[]}""")
                    .shouldBeRight()
                    .shouldBeInstanceOf<JellyfinWsParseResult.SessionsUpdate>()
            val stoppedEvents = JellyfinNotificationParser.toPlayingEvents(emptyUpdate.sessions, activeSessions)
            stoppedEvents shouldHaveSize 1
            stoppedEvents[0].sessionKey shouldBe "jf-s1"
            stoppedEvents[0].state shouldBe PlaybackState.STOPPED
            activeSessions.isEmpty() shouldBe true

            val libFrame =
                """
                {
                  "MessageType": "LibraryChanged",
                  "Data": { "FoldersAddedTo": ["lib-1"], "FoldersUpdated": ["lib-2", "lib-1"] }
                }
                """.trimIndent()
            val libResult =
                JellyfinNotificationParser
                    .parse(libFrame)
                    .shouldBeRight()
                    .shouldBeInstanceOf<JellyfinWsParseResult.LibraryUpdate>()
            libResult.events shouldHaveSize 2
            libResult.events[0].sectionId shouldBe "lib-1"
            libResult.events[1].sectionId shouldBe "lib-2"
        }

        test("jellyfinWebSocketUrl and redactJellyfinSecrets preserve wss scheme and scrub api_key") {
            val url = jellyfinWebSocketUrl("https://jf.example.com/", SECRET_KEY, "miru-dev")
            url shouldBe "wss://jf.example.com/socket?api_key=$SECRET_KEY&deviceId=miru-dev"
            redactJellyfinSecrets("GET $url failed for $SECRET_KEY", SECRET_KEY).shouldNotContain(SECRET_KEY)
        }

        test("listener sends SessionsStart, replies KeepAlive to ForceKeepAlive, reconnects with backoff, and redacts logs") {
            runTest {
                val secondConnected = CompletableDeferred<Unit>()
                val connector =
                    ScriptedJellyfinConnector { attempt, onConnected, onFrame ->
                        val recordSend: suspend (String) -> Unit = {}
                        if (attempt == 1) {
                            onConnected(recordSend)
                            onFrame("""{"MessageType":"ForceKeepAlive","Data":30}""", recordSend)
                            onFrame(
                                """{"MessageType":"Sessions","Data":[{"Id":"s1",""" +
                                    """"NowPlayingItem":{"Id":"r1"},"PlayState":{"PositionTicks":10000000}}]}""",
                                recordSend,
                            )
                            throw IOException("connect wss://jf.local/socket?api_key=$SECRET_KEY dropped")
                        } else {
                            onConnected(recordSend)
                            secondConnected.complete(Unit)
                            awaitCancellation()
                        }
                    }

                val logs =
                    captureListenerLogs {
                        val listener =
                            JellyfinNotificationListener(
                                connector = connector,
                                scope = backgroundScope,
                                backoff = BackoffPolicy(initial = 1.seconds, max = 8.seconds, resetAfter = 30.seconds),
                                random = Random(42),
                                timeSource = testScheduler.timeSource,
                                secretToRedact = SECRET_KEY,
                            )
                        val events = mutableListOf<MediaServerEvent>()
                        val collector = launch { listener.events.take(1).toList(events) }
                        listener.start()
                        runCurrent()

                        connector.sentFrames.any { it.contains("SessionsStart") } shouldBe true
                        connector.sentFrames.any { it.contains("KeepAlive") } shouldBe true
                        events shouldHaveSize 1

                        advanceTimeBy(1_000.milliseconds)
                        runCurrent()
                        secondConnected.await()
                        listener.connectionState.value shouldBe ConnectionState.Connected
                        collector.cancel()
                        listener.stop()
                    }

                val allMessages = logs.joinToString("\n") { it.formattedMessage }
                allMessages shouldNotContain SECRET_KEY
                connector.attempts.get() shouldBe 2
                connector.sentFrames.filter { it.contains("SessionsStart") } shouldHaveSize 2
            }
        }

        test("auth error backoff hits max cap and redacts api_key") {
            val logs =
                captureListenerLogs {
                    runTest {
                        val connector =
                            ScriptedJellyfinConnector { attempt, onConnected, _ ->
                                if (attempt == 1) {
                                    throw IllegalStateException("Handshake failed expected 101 but was 401")
                                } else {
                                    onConnected {}
                                    awaitCancellation()
                                }
                            }
                        val listener =
                            JellyfinNotificationListener(
                                connector = connector,
                                scope = backgroundScope,
                                backoff = BackoffPolicy(initial = 1.seconds, max = 30.seconds),
                                random = Random(1),
                                timeSource = testScheduler.timeSource,
                                secretToRedact = SECRET_KEY,
                            )
                        listener.start()
                        runCurrent()
                        listener.connectionState.value shouldBe ConnectionState.Backoff(1, 30.seconds)
                        advanceTimeBy(30.seconds)
                        runCurrent()
                        listener.connectionState.value shouldBe ConnectionState.Connected
                        listener.stop()
                    }
                }
            logs.joinToString("\n") { it.formattedMessage } shouldNotContain SECRET_KEY
        }

        test("KtorJellyfinNotificationConnector handles live socket frames, KeepAlive, and reconnect") {
            val receivedStart = AtomicInteger()
            val receivedKeepAlive = AtomicInteger()
            testApplication {
                install(ServerWebSockets)
                routing {
                    webSocket("/socket") {
                        val firstMsg = (incoming.receive() as Frame.Text).readText()
                        if (firstMsg.contains("SessionsStart")) receivedStart.incrementAndGet()
                        send(Frame.Text("""{"MessageType":"ForceKeepAlive","Data":30}"""))
                        val keepAlive = (incoming.receive() as Frame.Text).readText()
                        if (keepAlive.contains("KeepAlive")) receivedKeepAlive.incrementAndGet()
                        send(
                            Frame.Text(
                                """{"MessageType":"Sessions","Data":[{"Id":"ws-jf1",""" +
                                    """"NowPlayingItem":{"Id":"jf-item1"},"PlayState":{"PositionTicks":20000000}}]}""",
                            ),
                        )
                        send(
                            Frame.Text(
                                """{"MessageType":"LibraryChanged","Data":{"CollectionFolders":["lib-1"]}}""",
                            ),
                        )
                        close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                    }
                }
                val wsClient = createClient { install(ClientWebSockets) }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val listener =
                        JellyfinNotificationListener(
                            connector =
                                KtorJellyfinNotificationConnector(
                                    httpClient = wsClient,
                                    config = JellyfinConfig(baseUrl = "http://localhost", apiKey = SECRET_KEY),
                                ),
                            scope = scope,
                            backoff = BackoffPolicy(initial = 20.milliseconds, max = 100.milliseconds),
                            secretToRedact = SECRET_KEY,
                        )
                    val events = scope.async(start = CoroutineStart.UNDISPATCHED) { listener.events.take(2).toList() }
                    listener.start()
                    val collected = withTimeout(5.seconds) { events.await() }
                    collected shouldHaveSize 2
                    withTimeout(5.seconds) {
                        listener.connectionState.first {
                            it == ConnectionState.Connected || it is ConnectionState.Backoff
                        }
                    }
                    listener.stop()
                    listener.connectionState.value shouldBe ConnectionState.Stopped
                } finally {
                    scope.cancel()
                }
            }
            (receivedStart.get() >= 1) shouldBe true
            (receivedKeepAlive.get() >= 1) shouldBe true
        }
    })
