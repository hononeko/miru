@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.plex.BackoffPolicy
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.http.HttpResponse
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class JellyfinNotificationParserTest :
    FunSpec({
        test("parse handles KeepAlive, ForceKeepAlive, Ignored, malformed JSON, and null/invalid Sessions payloads") {
            JellyfinNotificationParser.parse("""{"MessageType":"KeepAlive"}""").shouldBeRight() shouldBe
                JellyfinWsParseResult.KeepAliveAck
            JellyfinNotificationParser.parse("""{"MessageType":"ForceKeepAlive","Data":60}""").shouldBeRight() shouldBe
                JellyfinWsParseResult.KeepAliveRequested
            JellyfinNotificationParser.parse("""{"MessageType":"ScheduledTaskEnded"}""").shouldBeRight() shouldBe
                JellyfinWsParseResult.Ignored
            JellyfinNotificationParser.parse("{invalid").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            // Sessions with null Data returns empty list; non-array Data fails with UpstreamUnavailable
            val emptySessions =
                JellyfinNotificationParser
                    .parse("""{"MessageType":"Sessions"}""")
                    .shouldBeRight()
                    .shouldBeInstanceOf<JellyfinWsParseResult.SessionsUpdate>()
            emptySessions.sessions.shouldBeEmpty()

            JellyfinNotificationParser
                .parse("""{"MessageType":"Sessions","Data":"not-an-array"}""")
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
        }

        test("parse handles LibraryChanged with folders, items-only, null Data, and malformed Data") {
            JellyfinNotificationParser.parse("""{"MessageType":"LibraryChanged"}""").shouldBeRight() shouldBe
                JellyfinWsParseResult.Ignored
            JellyfinNotificationParser
                .parse("""{"MessageType":"LibraryChanged","Data":"bad-shape"}""")
                .shouldBeRight() shouldBe JellyfinWsParseResult.Ignored

            val withFolders =
                JellyfinNotificationParser
                    .parse(
                        """
                        {
                          "MessageType": "LibraryChanged",
                          "Data": {
                            "CollectionFolders": ["lib-a", " "],
                            "FoldersAddedTo": ["lib-b"],
                            "FoldersRemovedFrom": ["lib-c"],
                            "FoldersUpdated": ["lib-a"],
                            "ItemsAdded": ["item-1", "item-2"]
                          }
                        }
                        """.trimIndent(),
                    ).shouldBeRight()
                    .shouldBeInstanceOf<JellyfinWsParseResult.LibraryUpdate>()
            withFolders.events.map { it.sectionId } shouldContainExactly listOf("lib-a", "lib-b", "lib-c")
            withFolders.events.all { it.itemId == "item-1" } shouldBe true

            val itemsOnly =
                JellyfinNotificationParser
                    .parse(
                        """
                        {
                          "MessageType": "LibraryChanged",
                          "Data": {
                            "ItemsAdded": ["i1"],
                            "ItemsUpdated": ["i2"],
                            "ItemsRemoved": ["i3", " "]
                          }
                        }
                        """.trimIndent(),
                    ).shouldBeRight()
                    .shouldBeInstanceOf<JellyfinWsParseResult.LibraryUpdate>()
            itemsOnly.events.map { it.itemId } shouldContainExactly listOf("i1", "i2", "i3")
            itemsOnly.events.forEach { it.sectionId.shouldBeNull() }
        }

        test("toPlayingEvents tracks concurrent playing, paused, idle skipping, and partial stops") {
            val active = linkedMapOf<String, String?>()

            val firstBatch =
                JellyfinNotificationParser.toPlayingEvents(
                    sessions =
                        listOf(
                            JellyfinSessionDto(id = "  ", nowPlayingItem = JellyfinBaseItemDto(id = "skip-id")),
                            JellyfinSessionDto(id = "idle-1", nowPlayingItem = null),
                            JellyfinSessionDto(
                                id = "s-1",
                                deviceId = "dev-1",
                                playState = JellyfinPlayStateDto(positionTicks = 30_000_000L, isPaused = false),
                                nowPlayingItem = JellyfinBaseItemDto(id = "m-1"),
                            ),
                            JellyfinSessionDto(
                                id = "s-2",
                                deviceId = "dev-2",
                                playState = JellyfinPlayStateDto(positionTicks = 90_000_000L, isPaused = true),
                                nowPlayingItem = JellyfinBaseItemDto(id = "m-2"),
                            ),
                        ),
                    previouslyActiveSessions = active,
                )
            firstBatch shouldHaveSize 2
            firstBatch[0] shouldBe
                MediaServerEvent.PlayingEvent(
                    sessionKey = "s-1",
                    ratingKey = "m-1",
                    state = PlaybackState.PLAYING,
                    viewOffsetMs = 3_000L,
                    clientIdentifier = "dev-1",
                )
            firstBatch[1] shouldBe
                MediaServerEvent.PlayingEvent(
                    sessionKey = "s-2",
                    ratingKey = "m-2",
                    state = PlaybackState.PAUSED,
                    viewOffsetMs = 9_000L,
                    clientIdentifier = "dev-2",
                )

            // s-2 vanishes while s-1 transitions to paused -> s-1 emits PAUSED and s-2 emits STOPPED with remembered ratingKey
            val secondBatch =
                JellyfinNotificationParser.toPlayingEvents(
                    sessions =
                        listOf(
                            JellyfinSessionDto(
                                id = "s-1",
                                deviceId = "dev-1",
                                playState = JellyfinPlayStateDto(positionTicks = 40_000_000L, isPaused = true),
                                nowPlayingItem = JellyfinBaseItemDto(id = "m-1"),
                            ),
                        ),
                    previouslyActiveSessions = active,
                )
            secondBatch shouldHaveSize 2
            secondBatch[0].state shouldBe PlaybackState.PAUSED
            secondBatch[1] shouldBe
                MediaServerEvent.PlayingEvent(
                    sessionKey = "s-2",
                    ratingKey = "m-2",
                    state = PlaybackState.STOPPED,
                )
            active.keys shouldContainExactly listOf("s-1")
        }

        test("jellyfinWebSocketUrl and redactJellyfinSecrets handle http/https, blank deviceId, and URL-encoded secrets") {
            val httpUrl = jellyfinWebSocketUrl("http://jf.local:8096///", "key+with/chars=", "   ")
            httpUrl shouldBe "ws://jf.local:8096/socket?api_key=key%2Bwith%2Fchars%3D&deviceId=miru"

            val redacted =
                redactJellyfinSecrets(
                    raw = "Failed connecting to $httpUrl with key+with/chars= and ?api_key=other-secret&foo=1",
                    secret = "key+with/chars=",
                )
            redacted shouldNotContain "key+with/chars="
            redacted shouldNotContain "key%2Bwith%2Fchars%3D"
            redacted shouldNotContain "other-secret"

            redactJellyfinSecrets(null) shouldBe "unexpected socket error"
            redactJellyfinSecrets("   ") shouldBe "unexpected socket error"
        }

        test("listener handles idempotent start, KeepAliveAck, unparseable frames, LibraryChanged, and WebSocketHandshakeException 403") {
            runTest {
                val attempts = AtomicInteger()
                val handshake403 =
                    WebSocketHandshakeException(
                        io.mockk.mockk<HttpResponse<*>> {
                            io.mockk.every { statusCode() } returns 403
                        },
                    )
                val connector =
                    JellyfinNotificationConnector { onConnected, onFrame ->
                        when (attempts.incrementAndGet()) {
                            1 -> {
                                onConnected {}
                                onFrame("{bad-json", {})
                                onFrame("""{"MessageType":"KeepAlive"}""", {})
                                onFrame("""{"MessageType":"IgnoredEvent"}""", {})
                                onFrame(
                                    """{"MessageType":"LibraryChanged","Data":{"CollectionFolders":["lib-9"]}}""",
                                    {},
                                )
                                throw IOException("transient drop")
                            }

                            2 -> throw handshake403

                            else -> {
                                onConnected {}
                                awaitCancellation()
                            }
                        }
                    }

                val listener =
                    JellyfinNotificationListener(
                        connector = connector,
                        scope = backgroundScope,
                        backoff = BackoffPolicy(initial = 1.seconds, max = 16.seconds),
                        random = Random(7),
                        timeSource = testScheduler.timeSource,
                    )
                val received = mutableListOf<MediaServerEvent>()
                val collector = launch { listener.events.take(1).toList(received) }

                val firstJob = listener.start()
                val secondJob = listener.start()
                firstJob shouldBe secondJob
                runCurrent()

                received shouldHaveSize 1
                received.single().shouldBeInstanceOf<MediaServerEvent.TimelineEvent>().sectionId shouldBe "lib-9"
                listener.connectionState.value.shouldBeInstanceOf<ConnectionState.Backoff>()

                // First retry hits WebSocketHandshakeException(403) -> backoff jumps directly to max (16s)
                advanceTimeBy(2.seconds)
                runCurrent()
                listener.connectionState.value shouldBe ConnectionState.Backoff(2, 16.seconds)

                advanceTimeBy(16.seconds)
                runCurrent()
                listener.connectionState.value shouldBe ConnectionState.Connected

                collector.cancel()
                listener.stop()
                listener.connectionState.value shouldBe ConnectionState.Stopped
            }
        }
    })
