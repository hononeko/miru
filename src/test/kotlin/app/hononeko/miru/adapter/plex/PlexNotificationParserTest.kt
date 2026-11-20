package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PlexNotificationParserTest :
    FunSpec({
        test("playing frame maps every known state and drops unknown ones") {
            val events = PlexNotificationParser.parse(PlexNotificationFixtures.playingFrame).shouldBeRight()

            events shouldContainExactly
                listOf(
                    MediaServerEvent.PlayingEvent(
                        sessionKey = "23",
                        ratingKey = "12345",
                        state = PlaybackState.PLAYING,
                        viewOffsetMs = 60_000L,
                        clientIdentifier = "client-abc",
                    ),
                    MediaServerEvent.PlayingEvent(
                        sessionKey = "24",
                        ratingKey = "222",
                        state = PlaybackState.PAUSED,
                        viewOffsetMs = 1_000L,
                    ),
                    MediaServerEvent.PlayingEvent(sessionKey = "25", state = PlaybackState.BUFFERING),
                    MediaServerEvent.PlayingEvent(
                        sessionKey = "26",
                        ratingKey = "333",
                        state = PlaybackState.STOPPED,
                        viewOffsetMs = 5_400_000L,
                    ),
                )
        }

        test("timeline frame accepts numeric and string identifiers") {
            val events = PlexNotificationParser.parse(PlexNotificationFixtures.timelineFrame).shouldBeRight()

            events shouldContainExactly
                listOf(
                    MediaServerEvent.TimelineEvent(
                        itemId = "12345",
                        sectionId = "1",
                        mediaTypeCode = 1,
                        title = "Spirited Away",
                        state = 5,
                        metadataState = "created",
                    ),
                    MediaServerEvent.TimelineEvent(itemId = "678", sectionId = "2", mediaTypeCode = 4, state = 9),
                )
        }

        test("activity frame maps scan progress") {
            val event =
                PlexNotificationParser
                    .parse(PlexNotificationFixtures.activityFrame)
                    .shouldBeRight()
                    .single()
                    .shouldBeInstanceOf<MediaServerEvent.ActivityEvent>()

            event.uuid shouldBe "a1b2c3"
            event.event shouldBe "updated"
            event.type shouldBe "library.update.section"
            event.title shouldBe "Scanning Movies"
            event.subtitle shouldBe "Processing files"
            event.progress shouldBe 42.0
        }

        test("unconsumed notification types yield no events") {
            PlexNotificationParser.parse(PlexNotificationFixtures.statusFrame).shouldBeRight().shouldBeEmpty()
            PlexNotificationParser.parse(PlexNotificationFixtures.transcodeFrame).shouldBeRight().shouldBeEmpty()
            PlexNotificationParser
                .parse("""{"NotificationContainer":{"type":"playing","size":0}}""")
                .shouldBeRight()
                .shouldBeEmpty()
        }

        test("malformed frames are reported as UpstreamUnavailable") {
            listOf("not json", "{}", """{"NotificationContainer":{"size":1}}""", "[1,2,3]").forEach { frame ->
                PlexNotificationParser
                    .parse(frame)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                    .provider shouldBe "plex"
            }
        }

        test("websocket url is derived from the Plex base url") {
            plexNotificationsUrl("http://plex.local:32400") shouldBe "ws://plex.local:32400/:/websockets/notifications"
            plexNotificationsUrl("https://plex.example.com/") shouldBe "wss://plex.example.com/:/websockets/notifications"
            plexNotificationsUrl("HTTPS://plex.example.com:443") shouldBe "wss://plex.example.com:443/:/websockets/notifications"
        }
    })
