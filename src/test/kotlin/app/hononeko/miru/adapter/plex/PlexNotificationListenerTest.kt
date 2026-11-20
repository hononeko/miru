@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.config.PlexConfig
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

private const val SECRET_TOKEN = "listener-secret-token-123"
private const val SEED = 1234

/** Connector driven by a per-attempt script, tracking concurrently open sessions. */
private class ScriptedConnector(
    private val clock: () -> Long,
    private val script: suspend (attempt: Int, onConnected: suspend () -> Unit, onFrame: suspend (String) -> Unit) -> Unit,
) : PlexNotificationConnector {
    val attempts = AtomicInteger()
    val open = AtomicInteger()
    val maxOpen = AtomicInteger()
    val connectTimes = CopyOnWriteArrayList<Long>()
    val endTimes = CopyOnWriteArrayList<Long>()

    override suspend fun connect(
        onConnected: suspend () -> Unit,
        onFrame: suspend (String) -> Unit,
    ) {
        val attempt = attempts.incrementAndGet()
        connectTimes += clock()
        val nowOpen = open.incrementAndGet()
        maxOpen.updateAndGet { maxOf(it, nowOpen) }
        try {
            script(attempt, onConnected, onFrame)
        } finally {
            open.decrementAndGet()
            endTimes += clock()
        }
    }
}

private fun TestScope.listenerFor(
    connector: PlexNotificationConnector,
    backoff: BackoffPolicy = BackoffPolicy(),
) = PlexNotificationListener(
    connector = connector,
    scope = backgroundScope,
    backoff = backoff,
    random = Random(SEED),
    timeSource = testScheduler.timeSource,
    secretToRedact = SECRET_TOKEN,
)

private fun gaps(
    starts: List<Long>,
    ends: List<Long>,
): List<Long> = (1 until starts.size).map { starts[it] - ends[it - 1] }

private inline fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(PlexNotificationListener::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.toList()
}

class PlexNotificationListenerTest :
    FunSpec({
        test("reconnects after simulated drops with a single live socket and no leaked coroutines") {
            runTest {
                val connector =
                    ScriptedConnector(clock = { testScheduler.currentTime }) { attempt, onConnected, onFrame ->
                        onConnected()
                        onFrame(PlexNotificationFixtures.playing("s$attempt"))
                        if (attempt >= 4) awaitCancellation()
                    }
                val listener = listenerFor(connector)
                val events = mutableListOf<MediaServerEvent>()
                var reconnects = 0
                backgroundScope.launch { listener.events.collect { events += it } }
                backgroundScope.launch { listener.reconnected.collect { reconnects++ } }
                runCurrent()

                val job = listener.start()
                listener.start() shouldBeSameInstanceAs job

                advanceTimeBy(10.minutes)
                runCurrent()

                connector.attempts.get() shouldBe 4
                connector.maxOpen.get() shouldBe 1
                connector.open.get() shouldBe 1
                listener.connectionState.value shouldBe ConnectionState.Connected
                events.map { (it as MediaServerEvent.PlayingEvent).sessionKey } shouldContainExactly
                    listOf("s1", "s2", "s3", "s4")
                reconnects shouldBe 3
                // each session delivered a frame, so backoff stayed at the first-attempt ceiling
                gaps(connector.connectTimes, connector.endTimes).forEach { it shouldBeLessThanOrEqualTo 1_000L }

                listener.stop()
                connector.open.get() shouldBe 0
                job.isCancelled shouldBe true
                job.children.toList().shouldBeEmpty()
                listener.connectionState.value shouldBe ConnectionState.Stopped

                advanceTimeBy(10.minutes)
                connector.attempts.get() shouldBe 4
            }
        }

        test("consecutive failures back off exponentially with seeded full jitter") {
            runTest {
                val policy = BackoffPolicy()
                val connector =
                    ScriptedConnector(clock = { testScheduler.currentTime }) { attempt, onConnected, _ ->
                        if (attempt <= 6) throw IOException("connection refused")
                        onConnected()
                        awaitCancellation()
                    }
                val listener = listenerFor(connector, policy)
                listener.start()
                advanceTimeBy(30.minutes)
                runCurrent()

                connector.attempts.get() shouldBe 7
                val expectedRandom = Random(SEED)
                val expected = (1..6).map { policy.delayFor(it, expectedRandom).inWholeMilliseconds }
                gaps(connector.connectTimes, connector.endTimes) shouldBe expected
                expected.zip(1..6).forEach { (delay, attempt) ->
                    delay shouldBeLessThanOrEqualTo policy.ceilingFor(attempt).inWholeMilliseconds
                }
                listener.connectionState.value shouldBe ConnectionState.Connected
                listener.stop()
            }
        }

        test("a connection that stays up past resetAfter resets the backoff") {
            runTest {
                val policy = BackoffPolicy(resetAfter = 30.seconds)
                val connector =
                    ScriptedConnector(clock = { testScheduler.currentTime }) { attempt, onConnected, _ ->
                        when {
                            attempt <= 3 -> throw IOException("refused")
                            attempt == 4 -> {
                                onConnected()
                                delay(31.seconds) // healthy but silent, then dropped
                            }
                            else -> {
                                onConnected()
                                awaitCancellation()
                            }
                        }
                    }
                val listener = listenerFor(connector, policy)
                listener.start()
                advanceTimeBy(10.minutes)
                runCurrent()

                connector.attempts.get() shouldBe 5
                val expectedRandom = Random(SEED)
                val expected =
                    listOf(1, 2, 3, 1).map { policy.delayFor(it, expectedRandom).inWholeMilliseconds }
                gaps(connector.connectTimes, connector.endTimes) shouldBe expected
                listener.stop()
            }
        }

        test("auth rejection backs off at the cap and never logs the token") {
            lateinit var states: List<ConnectionState>
            val logs =
                captureLogs {
                    runTest {
                        val connector =
                            ScriptedConnector(clock = { testScheduler.currentTime }) { attempt, onConnected, _ ->
                                when (attempt) {
                                    1 -> throw IllegalStateException("Handshake exception, expected status code 101 but was 401")
                                    2 -> throw IOException("reset by peer while sending X-Plex-Token=$SECRET_TOKEN")
                                    else -> {
                                        onConnected()
                                        awaitCancellation()
                                    }
                                }
                            }
                        val listener = listenerFor(connector)
                        val seen = mutableListOf<ConnectionState>()
                        backgroundScope.launch { listener.connectionState.collect { seen += it } }
                        runCurrent()
                        listener.start()
                        runCurrent()
                        listener.connectionState.value shouldBe ConnectionState.Backoff(1, 60.seconds)

                        advanceTimeBy(5.minutes)
                        runCurrent()
                        gaps(connector.connectTimes, connector.endTimes).first() shouldBe 60_000L
                        listener.stop()
                        runCurrent()
                        states = seen.toList()
                    }
                }

            // StateFlow conflates, so only the start and end states are guaranteed to be observed
            states.first() shouldBe ConnectionState.Disconnected
            states.last() shouldBe ConnectionState.Stopped
            logs
                .map { it.formattedMessage }
                .filter { it.contains("plex.ws.state=connecting") }
                .map { Regex("attempt=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() } shouldBe listOf(1, 2, 3)

            val rendered = logs.map { it.formattedMessage + " " + it.argumentArray.orEmpty().joinToString() }
            rendered.forEach { it shouldNotContain SECRET_TOKEN }
            rendered.any { it.contains("auth rejected") } shouldBe true
            rendered.any { it.contains("X-Plex-Token=***") } shouldBe true
            logs.any { it.formattedMessage.contains("plex.ws.state=backoff") } shouldBe true
        }

        test("unparseable frames are dropped without breaking the session") {
            runTest {
                val connector =
                    ScriptedConnector(clock = { testScheduler.currentTime }) { _, onConnected, onFrame ->
                        onConnected()
                        onFrame("garbage")
                        onFrame(PlexNotificationFixtures.statusFrame)
                        onFrame(PlexNotificationFixtures.timelineFrame)
                        awaitCancellation()
                    }
                val listener = listenerFor(connector)
                val events = mutableListOf<MediaServerEvent>()
                backgroundScope.launch { listener.events.collect { events += it } }
                runCurrent()
                listener.start()
                runCurrent()

                connector.attempts.get() shouldBe 1
                events.size shouldBe 2
                events.forEach { it.shouldBeInstanceOf<MediaServerEvent.TimelineEvent>() }
                listener.stop()
            }
        }

        test("Ktor connector streams real WebSocket frames, sends the token as a header, and reconnects after server close") {
            val sessions = AtomicInteger()
            val open = AtomicInteger()
            val maxOpen = AtomicInteger()
            val headerTokens = CopyOnWriteArrayList<String>()
            val queryTokens = CopyOnWriteArrayList<String>()

            testApplication {
                install(ServerWebSockets)
                routing {
                    webSocket("/:/websockets/notifications") {
                        val n = sessions.incrementAndGet()
                        headerTokens += call.request.headers["X-Plex-Token"].orEmpty()
                        call.request.queryParameters["X-Plex-Token"]?.let { queryTokens += it }
                        val nowOpen = open.incrementAndGet()
                        maxOpen.updateAndGet { maxOf(it, nowOpen) }
                        try {
                            send(Frame.Text(PlexNotificationFixtures.playing("ws$n")))
                            if (n >= 3) {
                                // stay connected until the client goes away
                                do {
                                    val result = incoming.receiveCatching()
                                } while (result.isSuccess)
                            }
                        } finally {
                            open.decrementAndGet()
                        }
                        if (n < 3) close(CloseReason(CloseReason.Codes.GOING_AWAY, "restart"))
                    }
                }

                val wsClient = createClient { install(ClientWebSockets) }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val listener =
                        PlexNotificationListener(
                            connector =
                                KtorPlexNotificationConnector(
                                    httpClient = wsClient,
                                    config = PlexConfig(baseUrl = "http://localhost", token = SECRET_TOKEN),
                                ),
                            scope = scope,
                            backoff = BackoffPolicy(initial = 20.milliseconds, max = 100.milliseconds),
                        )
                    val received =
                        scope.async(start = CoroutineStart.UNDISPATCHED) { listener.events.take(3).toList() }
                    listener.start()

                    val events = withTimeout(10.seconds) { received.await() }
                    events.map { (it as MediaServerEvent.PlayingEvent).sessionKey } shouldContainExactly
                        listOf("ws1", "ws2", "ws3")
                    withTimeout(5.seconds) { listener.connectionState.first { it == ConnectionState.Connected } }

                    sessions.get() shouldBe 3
                    maxOpen.get() shouldBe 1
                    headerTokens.toList() shouldBe List(3) { SECRET_TOKEN }
                    queryTokens.toList().shouldBeEmpty()

                    listener.stop()
                    listener.connectionState.value shouldBe ConnectionState.Stopped
                    awaitZero(open, 5.seconds)
                    delay(300.milliseconds)
                    sessions.get() shouldBe 3
                } finally {
                    scope.cancel()
                }
            }
        }

        test("listener log of the derived url never includes the token") {
            plexNotificationsUrl("http://plex.local:32400") shouldNotContain "X-Plex-Token"
            plexNotificationsUrl("http://plex.local:32400") shouldContain "/:/websockets/notifications"
        }

        test("auth detection walks exception causes and ignores unrelated numbers like ports or timeouts") {
            runTest {
                val policy = BackoffPolicy(initial = 1.seconds, max = 60.seconds)
                val connector =
                    ScriptedConnector(clock = { testScheduler.currentTime }) { attempt, onConnected, _ ->
                        when (attempt) {
                            1 -> throw IOException("connect failed on 127.0.0.1:401 after 403 ms")
                            2 -> throw IOException("upgrade failed", IllegalStateException("expected status code 101 but was 403"))
                            else -> {
                                onConnected()
                                awaitCancellation()
                            }
                        }
                    }
                val listener = listenerFor(connector, policy)
                listener.start()
                advanceTimeBy(5.minutes)
                runCurrent()

                connector.attempts.get() shouldBe 3
                val delays = gaps(connector.connectTimes, connector.endTimes)
                delays[0] shouldBeLessThanOrEqualTo 1_000L
                delays[1] shouldBe 60_000L
                listener.stop()
            }
        }

        test("cancellation as connect returns does not emit a spurious backoff transition") {
            val logs =
                captureLogs {
                    runTest {
                        val jobHolder = CompletableDeferred<kotlinx.coroutines.Job>()
                        val connector =
                            PlexNotificationConnector { onConnected, _ ->
                                onConnected()
                                // Cancel the listener coroutine and return normally without throwing
                                jobHolder.await().cancel()
                            }
                        val listener = listenerFor(connector)
                        val job = listener.start()
                        jobHolder.complete(job)
                        job.join()
                        listener.stop()
                    }
                }

            logs.none { it.formattedMessage.contains("plex.ws.state=backoff") } shouldBe true
        }

        test("Ktor CIO connector treats a real HTTP 401 handshake rejection as an auth failure without leaking the token") {
            val server =
                embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    routing {
                        get("/:/websockets/notifications") {
                            call.respond(HttpStatusCode.Unauthorized, "unauthorized")
                        }
                    }
                }.start(wait = false)
            val wsClient = createPlexWebSocketClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val logs =
                try {
                    val port = server.engine.resolvedConnectors().first().port
                    captureLogs {
                        val policy = BackoffPolicy(initial = 20.milliseconds, max = 5.seconds)
                        val listener =
                            PlexNotificationListener(
                                connector =
                                    KtorPlexNotificationConnector(
                                        httpClient = wsClient,
                                        config = PlexConfig(baseUrl = "http://127.0.0.1:$port", token = SECRET_TOKEN),
                                    ),
                                scope = scope,
                                backoff = policy,
                                secretToRedact = SECRET_TOKEN,
                            )
                        listener.start()
                        val state =
                            withTimeout(5.seconds) {
                                listener.connectionState.first { it is ConnectionState.Backoff }
                            }
                        state shouldBe ConnectionState.Backoff(attempt = 1, delay = 5.seconds)
                        listener.stop()
                    }
                } finally {
                    scope.cancel()
                    wsClient.close()
                    server.stop(0, 1_000)
                }

            val rendered = logs.map { it.formattedMessage + " " + it.argumentArray.orEmpty().joinToString() }
            rendered.forEach { it shouldNotContain SECRET_TOKEN }
            rendered.any { it.contains("plex.ws.state=auth_error") } shouldBe true
        }
    })

private suspend fun awaitZero(
    counter: AtomicInteger,
    timeout: Duration,
) {
    withTimeout(timeout) {
        while (counter.get() != 0) delay(10.milliseconds)
    }
}
