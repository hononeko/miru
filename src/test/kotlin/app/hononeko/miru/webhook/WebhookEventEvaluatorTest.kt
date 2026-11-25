package app.hononeko.miru.webhook

import app.hononeko.miru.MiruJson
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.TerminationAuditSource
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.StopReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private fun makeTarget(
    id: String = "wh-1",
    enabled: Boolean = true,
    events: List<WebhookEventType> = WebhookEventType.entries,
    cooldownSeconds: Int = 60,
    concurrentStreamThreshold: Int = 3,
    bufferingSpikeThreshold: Int = 3,
    bufferingWindowSeconds: Int = 120,
    transcode4kOnly: Boolean = false,
): WebhookTargetRecord =
    WebhookTargetRecord(
        id = id,
        name = "Notifier $id",
        url = "http://notifier.local:8080/hook",
        enabled = enabled,
        subscribedEvents = events,
        cooldownSeconds = cooldownSeconds,
        concurrentStreamThreshold = concurrentStreamThreshold,
        bufferingSpikeThreshold = bufferingSpikeThreshold,
        bufferingWindowSeconds = bufferingWindowSeconds,
        transcode4kOnly = transcode4kOnly,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun makeSession(
    sessionKey: String = "10",
    ratingKey: String = "rk-1",
    userId: String = "u-1",
    state: String = "playing",
    streamDecision: String = "direct_play",
    videoDecision: String? = "direct_play",
    audioDecision: String? = "direct_play",
    videoResolution: String? = "1080p",
    hwTranscode: Boolean = false,
): ActiveSessionRecord =
    ActiveSessionRecord(
        serverId = "srv-1",
        sessionKey = sessionKey,
        sessionId = "plex-$sessionKey",
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = "movie",
                title = "Dune: Part Two",
                durationMs = 9_960_000L,
            ),
        viewOffsetMs = 120_000L,
        state = state,
        streamTech =
            StreamTechRecord(
                streamDecision = streamDecision,
                videoDecision = videoDecision,
                audioDecision = audioDecision,
                hwTranscode = hwTranscode,
                videoCodec = "hevc",
                transcodeVideoCodec = if (videoDecision == "transcode") "h264" else null,
                videoResolution = videoResolution,
                bandwidthKbps = 15_000,
            ),
        player = PlayerRecord(playerName = "Apple TV", playerPlatform = "tvOS", location = "lan"),
        startedAtMs = 1_000L,
        lastSeenAtMs = 2_000L,
    )

private val testServer = WebhookServerPayload(id = "srv-1", name = "Main Plex")
private val testUser =
    UserRecord(
        id = "u-1",
        serverId = "srv-1",
        externalUserId = "100",
        username = "alice",
        displayName = "Alice",
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

class WebhookEventEvaluatorTest :
    FunSpec({
        test("transcode.started emits once on video transcode start or transition, honors transcode4kOnly and cooldown") {
            val seq = AtomicInteger(0)
            val evaluator = WebhookEventEvaluator(idGenerator = { "evt-${seq.incrementAndGet()}" })
            val allTarget = makeTarget(id = "wh-all", events = listOf(WebhookEventType.TRANSCODE_STARTED), cooldownSeconds = 60)
            val only4kTarget =
                makeTarget(
                    id = "wh-4k",
                    events = listOf(WebhookEventType.TRANSCODE_STARTED),
                    cooldownSeconds = 60,
                    transcode4kOnly = true,
                )
            val disabledTarget = makeTarget(id = "wh-off", enabled = false, events = listOf(WebhookEventType.TRANSCODE_STARTED))
            val targets = listOf(allTarget, only4kTarget, disabledTarget)

            // 1080p direct play -> nothing
            val directSession = makeSession(videoDecision = "direct_play", streamDecision = "direct_play", videoResolution = "1080p")
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", directSession.key, 10_000L, directSession),
                    listOf(directSession),
                    targets,
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Transition into 1080p video transcode -> fires only wh-all
            val transcode1080 =
                directSession.copy(
                    streamTech =
                        directSession.streamTech.copy(
                            streamDecision = "transcode",
                            videoDecision = "transcode",
                            transcodeVideoCodec = "h264",
                            hwTranscode = true,
                        ),
                )
            val firstMatches =
                evaluator.onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", transcode1080.key, 20_000L, transcode1080, "playing", "direct_play", true),
                    listOf(transcode1080),
                    targets,
                    testServer,
                    testUser,
                )
            firstMatches shouldHaveSize 1
            firstMatches[0].target.id shouldBe "wh-all"
            firstMatches[0].payload.event shouldBe "transcode.started"
            firstMatches[0].payload.details["hwTranscode"]?.jsonPrimitive?.boolean shouldBe true
            firstMatches[0].payload.details["is4k"]?.jsonPrimitive?.boolean shouldBe false

            // Subsequent update still transcoding -> deduplicated
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", transcode1080.key, 30_000L, transcode1080, "playing", "transcode", false),
                    listOf(transcode1080),
                    targets,
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Flaps back to direct play, then transcode within 60s cooldown -> suppressed
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", directSession.key, 40_000L, directSession, "playing", "transcode", true),
                    listOf(directSession),
                    targets,
                    testServer,
                    testUser,
                ).shouldBeEmpty()
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", transcode1080.key, 50_000L, transcode1080, "playing", "direct_play", true),
                    listOf(transcode1080),
                    targets,
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // 4K video transcode on a new session -> fires both wh-all and wh-4k
            val transcode4k =
                makeSession(
                    sessionKey = "11",
                    ratingKey = "rk-4k",
                    streamDecision = "transcode",
                    videoDecision = "transcode",
                    videoResolution = "4k",
                )
            val fourKMatches =
                evaluator.onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", transcode4k.key, 55_000L, transcode4k),
                    listOf(transcode1080, transcode4k),
                    targets,
                    testServer,
                    testUser,
                )
            fourKMatches.map { it.target.id } shouldBe listOf("wh-all", "wh-4k")
            fourKMatches[1].payload.details["is4k"]?.jsonPrimitive?.boolean shouldBe true
        }

        test("stream.concurrent_limit fires when active user streams exceed threshold and respects cooldown") {
            val evaluator = WebhookEventEvaluator()
            val target =
                makeTarget(
                    events = listOf(WebhookEventType.STREAM_CONCURRENT_LIMIT),
                    concurrentStreamThreshold = 2,
                    cooldownSeconds = 60,
                )
            val s1 = makeSession(sessionKey = "1", ratingKey = "m1")
            val s2 = makeSession(sessionKey = "2", ratingKey = "m2")
            val s3 = makeSession(sessionKey = "3", ratingKey = "m3")
            val s4 = makeSession(sessionKey = "4", ratingKey = "m4")

            // 2 active streams (equal to threshold) -> no event
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", s2.key, 10_000L, s2),
                    listOf(s1, s2),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // 3 active streams (> threshold 2) -> fires
            val fired =
                evaluator.onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", s3.key, 20_000L, s3),
                    listOf(s1, s2, s3),
                    listOf(target),
                    testServer,
                    testUser,
                )
            fired shouldHaveSize 1
            fired[0].payload.event shouldBe "stream.concurrent_limit"
            fired[0].payload.details["activeStreamCount"]?.jsonPrimitive?.int shouldBe 3
            fired[0].payload.details["threshold"]?.jsonPrimitive?.int shouldBe 2

            // Same 3 streams updated -> no repeat
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", s3.key, 30_000L, s3, "playing", "direct_play", false),
                    listOf(s1, s2, s3),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // 4th stream within 60s cooldown -> suppressed
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", s4.key, 50_000L, s4),
                    listOf(s1, s2, s3, s4),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Stop s3 and s4, then exceed again after cooldown -> fires again
            evaluator.onSessionEvent(
                SessionEvent.SessionStopped("srv-1", s4.key, 60_000L, s4, null, false, StopReason.EXPLICIT_STOP),
                listOf(s1, s2, s3),
                listOf(target),
                testServer,
                testUser,
            )
            evaluator.onSessionEvent(
                SessionEvent.SessionStopped("srv-1", s3.key, 65_000L, s3, null, false, StopReason.EXPLICIT_STOP),
                listOf(s1, s2),
                listOf(target),
                testServer,
                testUser,
            )

            val firedAgain =
                evaluator.onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", s3.key, 100_000L, s3),
                    listOf(s1, s2, s3),
                    listOf(target),
                    testServer,
                    testUser,
                )
            firedAgain shouldHaveSize 1
        }

        test("stream.concurrent_limit does not count a stream that Plex no longer lists, and does not repeat after a hiccup") {
            val evaluator = WebhookEventEvaluator()
            val target =
                makeTarget(
                    events = listOf(WebhookEventType.STREAM_CONCURRENT_LIMIT),
                    concurrentStreamThreshold = 2,
                    cooldownSeconds = 0,
                )
            val s1 = makeSession(sessionKey = "1", ratingKey = "m1")
            val s2 = makeSession(sessionKey = "2", ratingKey = "m2")
            val s3 = makeSession(sessionKey = "3", ratingKey = "m3")

            suspend fun onUpdate(
                session: ActiveSessionRecord,
                vararg activeSessions: ActiveSessionRecord,
            ) = evaluator.onSessionEvent(
                SessionEvent.SessionUpdated("srv-1", session.key, 10_000L, session, "playing", "direct_play", false),
                activeSessions.toList(),
                listOf(target),
                testServer,
                testUser,
            )

            // The viewer switched from the first to the third title: two streams, not three
            onUpdate(s3, s1.copy(missedPolls = 1), s2, s3).shouldBeEmpty()

            // It was only a hiccup: three streams after all
            onUpdate(s3, s1, s2, s3) shouldHaveSize 1

            // Another hiccup is not the end of the alerted streams, so it does not alert again
            onUpdate(s3, s1.copy(missedPolls = 1), s2, s3).shouldBeEmpty()
            onUpdate(s3, s1, s2, s3).shouldBeEmpty()

            // Once the first stream has ended, exceeding the limit alerts again
            onUpdate(s3, s2, s3).shouldBeEmpty()
            onUpdate(s3, s1, s2, s3) shouldHaveSize 1
        }

        test("stream.buffering_spike triggers when buffering transitions reach threshold inside window") {
            val evaluator = WebhookEventEvaluator()
            val target =
                makeTarget(
                    events = listOf(WebhookEventType.STREAM_BUFFERING_SPIKE),
                    bufferingSpikeThreshold = 3,
                    bufferingWindowSeconds = 60,
                    cooldownSeconds = 30,
                )
            val playing = makeSession(state = "playing")
            val buffering = playing.copy(state = "buffering")

            // Buffer #1 at t=10s (outside later 60s window at t=100s)
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, 10_000L, buffering, "playing", "direct_play", false),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Duplicate buffering update (already buffering) -> must not increment
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, 15_000L, buffering, "buffering", "direct_play", false),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Buffer #2 at t=80s, #3 at t=90s -> only 2 inside [30s..90s], so no spike yet
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, 80_000L, buffering, "playing", "direct_play", false),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, 90_000L, buffering, "playing", "direct_play", false),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Buffer #4 at t=100s -> 3 transitions inside [40s..100s] -> fires!
            val spiked =
                evaluator.onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, 100_000L, buffering, "playing", "direct_play", false),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                )
            spiked shouldHaveSize 1
            spiked[0].payload.event shouldBe "stream.buffering_spike"
            spiked[0].payload.details["bufferingCount"]?.jsonPrimitive?.int shouldBe 3
            spiked[0].payload.details["windowSeconds"]?.jsonPrimitive?.int shouldBe 60

            // Within 30s cooldown -> suppressed
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, 110_000L, buffering, "playing", "direct_play", false),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()

            // Session stop clears buffering history
            evaluator.onSessionEvent(
                SessionEvent.SessionStopped("srv-1", buffering.key, 120_000L, buffering, null, false, StopReason.EXPLICIT_STOP),
                emptyList(),
                listOf(target),
                testServer,
                testUser,
            )
            evaluator
                .onSessionEvent(
                    SessionEvent.SessionStarted("srv-1", buffering.key, 150_000L, buffering),
                    listOf(buffering),
                    listOf(target),
                    testServer,
                    testUser,
                ).shouldBeEmpty()
        }

        test("stream.terminated emits structured details for manual and guardrail terminations") {
            val evaluator = WebhookEventEvaluator()
            val target = makeTarget(events = listOf(WebhookEventType.STREAM_TERMINATED), cooldownSeconds = 60)
            val session = makeSession(streamDecision = "transcode", videoDecision = "transcode", videoResolution = "4k")

            val matches =
                evaluator.onStreamTerminated(
                    event =
                        StreamTerminatedEvent(
                            serverId = "srv-1",
                            session = session,
                            reason = "4K transcode blocked",
                            source = TerminationAuditSource.GUARDRAIL,
                            ruleType = GuardrailRuleType.TRANSCODE_4K,
                            actorName = "Guardrail: 4K Transcode",
                            timestampMs = 50_000L,
                        ),
                    targets = listOf(target),
                    server = testServer,
                    user = testUser,
                )
            matches shouldHaveSize 1
            val payload = matches[0].payload
            payload.event shouldBe "stream.terminated"
            payload.details["reason"]?.jsonPrimitive?.content shouldBe "4K transcode blocked"
            payload.details["source"]?.jsonPrimitive?.content shouldBe "GUARDRAIL"
            payload.details["ruleType"]?.jsonPrimitive?.content shouldBe "TRANSCODE_4K"
            payload.details["actorName"]?.jsonPrimitive?.content shouldBe "Guardrail: 4K Transcode"
            payload.user.shouldNotBeNull().username shouldBe "alice"
            payload.session.shouldNotBeNull().title shouldBe "Dune: Part Two"
        }

        test("server.unreachable suppresses transient blips and server.recovered reports outageDurationSec") {
            val evaluator = WebhookEventEvaluator(unreachableFailureThreshold = 3)
            val target =
                makeTarget(
                    events = listOf(WebhookEventType.SERVER_UNREACHABLE, WebhookEventType.SERVER_RECOVERED),
                    cooldownSeconds = 60,
                )

            // 2 failures then recovery -> transient blip, zero webhooks
            evaluator.onReconcileHealth(ReconcileHealthSignal(false, "timeout", 1_000L), listOf(target), testServer).shouldBeEmpty()
            evaluator.onReconcileHealth(ReconcileHealthSignal(false, "timeout", 21_000L), listOf(target), testServer).shouldBeEmpty()
            evaluator.onReconcileHealth(ReconcileHealthSignal(true, null, 41_000L), listOf(target), testServer).shouldBeEmpty()

            // 3 consecutive failures -> unreachable emitted on #3, not on #4
            evaluator.onReconcileHealth(ReconcileHealthSignal(false, "conn refused", 100_000L), listOf(target), testServer).shouldBeEmpty()
            evaluator.onReconcileHealth(ReconcileHealthSignal(false, "conn refused", 120_000L), listOf(target), testServer).shouldBeEmpty()
            val unreachable =
                evaluator.onReconcileHealth(ReconcileHealthSignal(false, "conn refused", 140_000L), listOf(target), testServer)
            unreachable shouldHaveSize 1
            unreachable[0].payload.event shouldBe "server.unreachable"
            unreachable[0].payload.details["consecutiveFailures"]?.jsonPrimitive?.int shouldBe 3
            unreachable[0].payload.details["reason"]?.jsonPrimitive?.content shouldBe "conn refused"

            evaluator.onReconcileHealth(ReconcileHealthSignal(false, "conn refused", 160_000L), listOf(target), testServer).shouldBeEmpty()

            // Recovery at t=220s -> outageDurationSec = (220_000 - 100_000) / 1000 = 120s
            val recovered =
                evaluator.onReconcileHealth(ReconcileHealthSignal(true, null, 220_000L), listOf(target), testServer)
            recovered shouldHaveSize 1
            recovered[0].payload.event shouldBe "server.recovered"
            recovered[0].payload.details["outageDurationSec"]?.jsonPrimitive?.long shouldBe 120L

            // Subsequent healthy polls emit nothing
            evaluator.onReconcileHealth(ReconcileHealthSignal(true, null, 240_000L), listOf(target), testServer).shouldBeEmpty()
        }

        test("buildTestPayload emits schemaVersion 1 webhook.test payload") {
            val evaluator = WebhookEventEvaluator(idGenerator = { "evt-test" })
            val payload = evaluator.buildTestPayload(testServer, 1_700_000_000_000L)
            payload.schemaVersion shouldBe 1
            payload.event shouldBe "webhook.test"
            payload.eventId shouldBe "evt-test"
            payload.server shouldBe testServer
            payload.details["message"]?.jsonPrimitive?.content shouldBe "Test webhook delivery from Miru"
        }

        test("docs/webhooks.md describes the payloads Miru sends") {
            val docs = File("docs/webhooks.md").readText()
            val sent = samplePayloadOfEveryEvent()
            sent.keys shouldBe WebhookEventType.allKeys.toSet() + WebhookEventType.TEST_EVENT_KEY

            val documentedDetails =
                Regex("""(?m)^\| `([a-z]+\.[a-z_]+)` \| .* \| (.*) \|$""")
                    .findAll(docs)
                    .associate { row ->
                        row.groupValues[1] to Regex("""`(\w+)`""").findAll(row.groupValues[2]).map { it.groupValues[1] }.toSet()
                    }
            documentedDetails shouldBe sent.mapValues { (_, payload) -> payload.details.keys }

            // Every field has a row with its type, and no row is about a field that is not sent
            val documentedTypes =
                Regex("""(?m)^\| `(\w+)` \| (string|integer|boolean|object) \| .* \|$""")
                    .findAll(docs)
                    .map { it.groupValues[1] to it.groupValues[2] }
                    .toSet()
            documentedTypes shouldBe sent.values.flatMap { typesOf(MiruJson.encodeToJsonElement(it).jsonObject) }.toSet()

            val (signature, body, secret) =
                Regex("""X-Miru-Signature-256: (sha256=\w+)\n```\n\nThis is the signature of the body `(.*)` with the secret `(\w+)`""")
                    .find(docs)
                    .shouldNotBeNull()
                    .destructured
            WebhookUrlSafety.signPayload(secret, body.toByteArray()) shouldBe signature

            // The examples are complete: nothing unknown, and all that Miru sends for that event
            val examples =
                Regex("(?s)```json\n(.*?)```")
                    .findAll(docs)
                    .map { MiruJson.parseToJsonElement(it.groupValues[1]).jsonObject }
                    .toList()
            examples shouldHaveSize 2
            for (example in examples) {
                val event = example.getValue("event").jsonPrimitive.content
                val actual = MiruJson.encodeToJsonElement(sent.getValue(event)).jsonObject
                example.keys shouldBe actual.keys
                listOf("server", "user", "session", "details").filter { it in actual }.forEach { part ->
                    example.getValue(part).jsonObject.keys shouldBe actual.getValue(part).jsonObject.keys
                }
            }
            sent.values.forEach { it.schemaVersion shouldBe 1 }

            // Without optional values, only the fields the docs call permanent are left
            val bareSession =
                WebhookSessionPayload(
                    sessionKey = "1",
                    state = "playing",
                    mediaType = "movie",
                    title = "t",
                    streamDecision = "direct_play",
                )
            MiruJson.encodeToJsonElement(bareSession).jsonObject.keys shouldBe
                setOf("sessionKey", "state", "mediaType", "title", "streamDecision", "hwTranscode")
        }
    })

/** The names and types of the fields in [json] and in the objects it contains. */
private fun typesOf(json: JsonObject): List<Pair<String, String>> =
    json.flatMap { (name, value) ->
        when {
            value is JsonObject -> listOf(name to "object") + typesOf(value)
            value.jsonPrimitive.isString -> listOf(name to "string")
            value.jsonPrimitive.booleanOrNull != null -> listOf(name to "boolean")
            else -> listOf(name to "integer")
        }
    }

private suspend fun samplePayloadOfEveryEvent(): Map<String, WebhookPayload> {
    val evaluator = WebhookEventEvaluator()
    val targets = listOf(makeTarget(cooldownSeconds = 0, concurrentStreamThreshold = 2, bufferingSpikeThreshold = 2))
    val base = makeSession(streamDecision = "transcode", videoDecision = "transcode", videoResolution = "4k", hwTranscode = true)
    val session = base.copy(media = base.media.copy(grandparentTitle = "Severance"))
    val others = listOf(makeSession(sessionKey = "11"), makeSession(sessionKey = "12"))
    val buffering = session.copy(state = "buffering")
    val terminated =
        StreamTerminatedEvent(
            serverId = "srv-1",
            session = session,
            reason = "4K",
            source = TerminationAuditSource.GUARDRAIL,
            ruleType = GuardrailRuleType.TRANSCODE_4K,
            actorName = "guardrail",
            timestampMs = 40_000L,
        )

    val matches =
        evaluator.onSessionEvent(
            SessionEvent.SessionStarted("srv-1", session.key, 10_000L, session),
            others + session,
            targets,
            testServer,
            testUser,
        ) +
            listOf(20_000L, 30_000L).flatMap { atMs ->
                evaluator.onSessionEvent(
                    SessionEvent.SessionUpdated("srv-1", buffering.key, atMs, buffering, "playing", "transcode", false),
                    listOf(buffering),
                    targets,
                    testServer,
                    testUser,
                )
            } +
            evaluator.onStreamTerminated(terminated, targets, testServer, testUser) +
            listOf(50_000L, 60_000L, 70_000L).flatMap { atMs ->
                val signal = ReconcileHealthSignal(reachable = false, errorMessage = "down", timestampMs = atMs)
                evaluator.onReconcileHealth(signal, targets, testServer)
            } +
            evaluator.onReconcileHealth(ReconcileHealthSignal(reachable = true, timestampMs = 80_000L), targets, testServer)

    return (matches.map { it.payload } + evaluator.buildTestPayload(testServer, 90_000L)).associateBy { it.event }
}
