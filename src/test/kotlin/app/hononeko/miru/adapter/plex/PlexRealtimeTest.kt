@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.session.ActiveSessionsSink
import app.hononeko.miru.session.SessionReconciler
import arrow.core.Either
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class StubMediaServerAdapter(
    private val eventsFlow: Flow<MediaServerEvent> = emptyFlow(),
    private val onFetch: suspend () -> Unit = {},
) : MediaServerAdapter {
    val fetches = AtomicInteger()

    override val serverType: String = "stub"

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> {
        fetches.incrementAndGet()
        onFetch()
        return emptyList<ActiveStreamSnapshot>().right()
    }

    override fun events(): Flow<MediaServerEvent> = eventsFlow

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
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> = error("unused")

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> = error("unused")

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> = error("unused")
}

class PlexRealtimeTest :
    FunSpec({
        test("stops reconciler and listener concurrently, cancels ownedScope, and runs onClose") {
            runTest {
                val ownedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val closeCount = AtomicInteger()
                val listener =
                    PlexNotificationListener(
                        connector =
                            PlexNotificationConnector { onConnected, _ ->
                                onConnected()
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) { delay(500.milliseconds) }
                                }
                            },
                        scope = ownedScope,
                    )
                val adapter =
                    StubMediaServerAdapter(eventsFlow = listener.events) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { delay(500.milliseconds) }
                        }
                    }
                val reconciler =
                    SessionReconciler(
                        adapter = adapter,
                        sink = ActiveSessionsSink { _, _ -> },
                        scope = ownedScope,
                        interval = 20.seconds,
                        reconnectSignals = listener.reconnected,
                    )
                val realtime =
                    PlexRealtime(
                        listener = listener,
                        reconciler = reconciler,
                        ownedScope = ownedScope,
                        onClose = { closeCount.incrementAndGet() },
                    )

                realtime.start()
                runCurrent()
                listener.connectionState.value shouldBe ConnectionState.Connected
                adapter.fetches.get() shouldBe 1

                val stopStart = testScheduler.currentTime
                realtime.stop()
                val stopElapsed = testScheduler.currentTime - stopStart

                // Concurrent stop takes ~500ms rather than 1000ms sequential
                stopElapsed shouldBe 500L
                listener.connectionState.value shouldBe ConnectionState.Stopped
                ownedScope.isActive shouldBe false
                closeCount.get() shouldBe 1
            }
        }

        test("cancels ownedScope and invokes onClose even when stop times out") {
            runTest {
                val ownedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val closeCount = AtomicInteger()
                val adapter =
                    StubMediaServerAdapter {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { delay(10.seconds) }
                        }
                    }
                val reconciler =
                    SessionReconciler(
                        adapter = adapter,
                        sink = ActiveSessionsSink { _, _ -> },
                        scope = ownedScope,
                        interval = 20.seconds,
                    )
                val realtime =
                    PlexRealtime(
                        listener = null,
                        reconciler = reconciler,
                        ownedScope = ownedScope,
                        onClose = { closeCount.incrementAndGet() },
                    )

                realtime.start()
                runCurrent()

                val completed = withTimeoutOrNull(1.seconds) { realtime.stop() }
                completed shouldBe null
                ownedScope.isActive shouldBe false
                closeCount.get() shouldBe 1

                advanceTimeBy(15.seconds)
                runCurrent()
            }
        }

        test("buildPlexRuntime with websocketEnabled=false runs in polling-only mode and closes cleanly") {
            runTest {
                val runtime =
                    buildPlexRuntime(
                        config =
                            PlexConfig(
                                baseUrl = "http://plex.local:32400",
                                token = "test-token",
                                pollIntervalSeconds = 20,
                                websocketEnabled = false,
                            ),
                    )
                runtime.adapter.events().toList().shouldBeEmpty()
                runtime.realtime.stop()
            }
        }
    })
