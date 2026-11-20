@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.session

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val snapshot =
    ActiveStreamSnapshot(
        sessionId = "sess-1",
        sessionKey = "23",
        state = PlaybackState.PLAYING,
        viewOffsetMs = 1_000L,
        media = StreamMedia(ratingKey = "12345", mediaType = MediaType.MOVIE, title = "Spirited Away"),
        user = StreamUser(externalId = "1", username = "owner"),
        player = StreamPlayer(machineIdentifier = "m1", product = "Plex Web", location = StreamLocation.LAN, isLocal = true),
        technical = StreamTechnicalInfo(decision = StreamDecision.DIRECT_PLAY),
    )

private fun playing(state: PlaybackState = PlaybackState.PLAYING) = MediaServerEvent.PlayingEvent(sessionKey = "23", state = state)

private class FakeAdapter(
    private val clock: () -> Long,
    private val eventFlow: Flow<MediaServerEvent>? = null,
) : MediaServerAdapter {
    val events = MutableSharedFlow<MediaServerEvent>()
    val fetchStarts = mutableListOf<Long>()
    var fetchDelay: Duration = Duration.ZERO
    var inFlight = 0
    var maxInFlight = 0
    var response: (call: Int) -> Either<MiruError, List<ActiveStreamSnapshot>> = { emptyList<ActiveStreamSnapshot>().right() }

    override val serverType: String = "fake"

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> {
        fetchStarts += clock()
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            delay(fetchDelay)
            return response(fetchStarts.size)
        } finally {
            inFlight--
        }
    }

    override fun events(): Flow<MediaServerEvent> = eventFlow ?: events

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> = error("unused")

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> = error("unused")

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> = error("unused")

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> = error("unused")

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
    ): Either<MiruError, LibraryItemsPage> = error("unused")

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> = error("unused")
}

private data class Reconciled(
    val at: Long,
    val trigger: ReconcileTrigger,
    val snapshots: List<ActiveStreamSnapshot>,
)

private class RecordingSink(
    private val clock: () -> Long,
    private val failOnCall: Int? = null,
) : ActiveSessionsSink {
    val calls = mutableListOf<Reconciled>()

    override suspend fun onSnapshots(
        snapshots: List<ActiveStreamSnapshot>,
        trigger: ReconcileTrigger,
    ) {
        calls += Reconciled(clock(), trigger, snapshots)
        if (calls.size == failOnCall) error("sink blew up")
    }
}

private class Harness(
    val adapter: FakeAdapter,
    val sink: RecordingSink,
    val reconnects: MutableSharedFlow<Unit>,
    val reconciler: SessionReconciler,
)

private fun TestScope.harness(
    interval: Duration = 20.seconds,
    failSinkOnCall: Int? = null,
    eventFlow: Flow<MediaServerEvent>? = null,
): Harness {
    val clock = { testScheduler.currentTime }
    val adapter = FakeAdapter(clock, eventFlow)
    val sink = RecordingSink(clock, failSinkOnCall)
    val reconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconciler =
        SessionReconciler(
            adapter = adapter,
            sink = sink,
            scope = backgroundScope,
            interval = interval,
            reconnectSignals = reconnects,
        )
    return Harness(adapter, sink, reconnects, reconciler)
}

class SessionReconcilerTest :
    FunSpec({
        test("debounces a burst of playing notifications into a single fetch") {
            runTest {
                val h = harness()
                h.reconciler.start()
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L)

                advanceTimeBy(100)
                repeat(10) {
                    h.adapter.events.emit(playing())
                    advanceTimeBy(50)
                }
                // last event at t=550; quiet window ends at t=1050
                advanceTimeBy(449)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L)

                advanceTimeBy(2)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L, 1_050L)
                h.sink.calls.map { it.trigger } shouldContainExactly
                    listOf(ReconcileTrigger.PERIODIC, ReconcileTrigger.NOTIFICATION)
            }
        }

        test("a continuous stream of notifications still reconciles at least every maxWait") {
            runTest {
                val h = harness()
                h.reconciler.start()
                runCurrent()
                repeat(50) {
                    h.adapter.events.emit(playing())
                    advanceTimeBy(100)
                }
                runCurrent()

                val notificationTimes =
                    h.sink.calls.filter { it.trigger == ReconcileTrigger.NOTIFICATION }.map { it.at }
                notificationTimes.size shouldBeGreaterThanOrEqualTo 2
                notificationTimes.first() shouldBe 2_000L
                notificationTimes.zipWithNext { a, b -> b - a }.forEach { it shouldBeLessThanOrEqualTo 2_100L }
            }
        }

        test("timeline and activity notifications do not trigger a session fetch") {
            runTest {
                val h = harness()
                h.reconciler.start()
                runCurrent()
                repeat(10) {
                    h.adapter.events.emit(MediaServerEvent.TimelineEvent(itemId = "$it"))
                    h.adapter.events.emit(MediaServerEvent.ActivityEvent(uuid = "$it", event = "updated"))
                    advanceTimeBy(100)
                }
                advanceTimeBy(5.seconds)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L)
            }
        }

        test("fallback reconcile catches a client that disappeared without a stopped event") {
            runTest {
                val h = harness()
                h.adapter.response = { listOf(snapshot).right() }
                h.reconciler.start()
                runCurrent()

                h.adapter.events.emit(playing(PlaybackState.PLAYING))
                advanceTimeBy(501)
                runCurrent()
                h.sink.calls.last() shouldBe Reconciled(500L, ReconcileTrigger.NOTIFICATION, listOf(snapshot))

                // the client crashes: Plex drops the session but never emits `stopped`
                h.adapter.response = { emptyList<ActiveStreamSnapshot>().right() }
                advanceTimeBy(20.seconds)
                runCurrent()

                val last = h.sink.calls.last()
                last.trigger shouldBe ReconcileTrigger.PERIODIC
                last.at shouldBe 20_500L
                last.snapshots.shouldBeEmpty()
            }
        }

        test("a notification-driven reconcile restarts the fallback timer") {
            runTest {
                val h = harness()
                h.reconciler.start()
                runCurrent()

                advanceTimeBy(10.seconds)
                h.adapter.events.emit(playing())
                advanceTimeBy(15.seconds)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L, 10_500L)

                advanceTimeBy(5_501)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L, 10_500L, 30_500L)
                h.sink.calls.last().trigger shouldBe ReconcileTrigger.PERIODIC
            }
        }

        test("periodic fallback keeps ticking at the configured interval") {
            runTest {
                val h = harness(interval = 5.seconds)
                h.reconciler.start()
                advanceTimeBy(20_001)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L, 5_000L, 10_000L, 15_000L, 20_000L)
                h.sink.calls.map { it.trigger }.toSet() shouldBe setOf(ReconcileTrigger.PERIODIC)
            }
        }

        test("a socket reconnect triggers an immediate reconcile") {
            runTest {
                val h = harness()
                h.reconciler.start()
                runCurrent()
                advanceTimeBy(5.seconds)
                h.reconnects.emit(Unit)
                runCurrent()

                h.sink.calls.last() shouldBe Reconciled(5_000L, ReconcileTrigger.RECONNECT, emptyList())
            }
        }

        test("fetches are single-flight and pending triggers are conflated") {
            runTest {
                val h = harness()
                h.adapter.fetchDelay = 5.seconds
                h.reconciler.start()
                runCurrent()

                advanceTimeBy(1_000)
                h.adapter.events.emit(playing())
                h.reconnects.emit(Unit)
                advanceTimeBy(1_000)
                h.reconnects.emit(Unit)
                advanceTimeBy(1_000)
                h.reconnects.emit(Unit)
                advanceTimeBy(20_000)
                runCurrent()

                h.adapter.maxInFlight shouldBe 1
                h.adapter.fetchStarts shouldContainExactly listOf(0L, 5_000L)
                h.sink.calls.map { it.trigger } shouldContainExactly
                    listOf(ReconcileTrigger.PERIODIC, ReconcileTrigger.RECONNECT)
            }
        }

        test("fetch errors and sink failures are logged and the loop keeps running") {
            runTest {
                val h = harness(failSinkOnCall = 1)
                h.adapter.response = { call ->
                    if (call == 1) {
                        MiruError.UpstreamUnavailable(provider = "plex", message = "down").left()
                    } else {
                        listOf(snapshot).right()
                    }
                }
                h.reconciler.start()
                advanceTimeBy(40_001)
                runCurrent()

                h.adapter.fetchStarts shouldContainExactly listOf(0L, 20_000L, 40_000L)
                h.sink.calls.map { it.at } shouldContainExactly listOf(20_000L, 40_000L)
            }
        }

        test("a failing event stream does not stop the fallback reconciler") {
            runTest {
                val h = harness(eventFlow = flow { error("socket exploded") })
                h.reconciler.start()
                advanceTimeBy(20_001)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L, 20_000L)
            }
        }

        test("start is idempotent and stop cancels every child coroutine") {
            runTest {
                val h = harness()
                val job = h.reconciler.start()
                h.reconciler.start() shouldBeSameInstanceAs job
                runCurrent()
                job.children.count() shouldBe 1 // supervisorScope coroutine hosting the workers

                h.reconciler.stop()
                job.isCancelled shouldBe true
                job.children.toList().shouldBeEmpty()

                h.adapter.events.emit(playing())
                h.reconnects.emit(Unit)
                advanceTimeBy(2.seconds * 60)
                runCurrent()
                h.adapter.fetchStarts shouldContainExactly listOf(0L)

                h.reconciler.start() shouldBe h.reconciler.start()
            }
        }

        test("invalid timing configuration is rejected") {
            runTest {
                val clock = { 0L }
                val adapter = FakeAdapter(clock)
                val sink = RecordingSink(clock)
                shouldThrow<IllegalArgumentException> {
                    SessionReconciler(adapter, sink, backgroundScope, interval = Duration.ZERO)
                }
                shouldThrow<IllegalArgumentException> {
                    SessionReconciler(
                        adapter,
                        sink,
                        backgroundScope,
                        interval = 1.seconds,
                        debounceWindow = 1.seconds,
                        maxWait = 500.milliseconds,
                        reconnectSignals = emptyFlow(),
                    )
                }
            }
        }
    })
