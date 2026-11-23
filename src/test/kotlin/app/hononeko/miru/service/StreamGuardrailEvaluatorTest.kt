package app.hononeko.miru.service

import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun testSession(
    sessionKey: String,
    sessionId: String = "plex-$sessionKey",
    userId: String = "u-1",
    ratingKey: String = "mov-$sessionKey",
    state: String = "playing",
    streamDecision: String = "direct_play",
    videoDecision: String? = null,
    audioDecision: String? = null,
    videoResolution: String? = "1080p",
    startedAtMs: Long = 1_000L,
): ActiveSessionRecord =
    ActiveSessionRecord(
        serverId = "srv-1",
        sessionKey = sessionKey,
        sessionId = sessionId,
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = "movie",
                title = "Movie $sessionKey",
                durationMs = 5_000_000L,
            ),
        viewOffsetMs = 100_000L,
        state = state,
        streamTech =
            StreamTechRecord(
                streamDecision = streamDecision,
                videoDecision = videoDecision,
                audioDecision = audioDecision,
                videoResolution = videoResolution,
            ),
        player = PlayerRecord(playerName = "Apple TV", location = "lan"),
        startedAtMs = startedAtMs,
        lastSeenAtMs = startedAtMs,
    )

private fun rulesMap(
    transcode4k: GuardrailMode = GuardrailMode.DISABLED,
    pausedTimeout: Pair<GuardrailMode, Int> = GuardrailMode.DISABLED to 30,
    concurrentStreams: Pair<GuardrailMode, Int> = GuardrailMode.DISABLED to 2,
): Map<GuardrailRuleType, EffectiveGuardrailRule> =
    mapOf(
        GuardrailRuleType.TRANSCODE_4K to
            EffectiveGuardrailRule(
                ruleType = GuardrailRuleType.TRANSCODE_4K,
                mode = transcode4k,
                threshold = null,
                message = "No 4K transcoding",
            ),
        GuardrailRuleType.PAUSED_TIMEOUT to
            EffectiveGuardrailRule(
                ruleType = GuardrailRuleType.PAUSED_TIMEOUT,
                mode = pausedTimeout.first,
                threshold = pausedTimeout.second,
                message = "Paused too long",
            ),
        GuardrailRuleType.CONCURRENT_STREAMS to
            EffectiveGuardrailRule(
                ruleType = GuardrailRuleType.CONCURRENT_STREAMS,
                mode = concurrentStreams.first,
                threshold = concurrentStreams.second,
                message = "Too many streams",
            ),
    )

class StreamGuardrailEvaluatorTest :
    FunSpec({
        test("is4kResolution and is4kVideoTranscode accurately classify 4K video transcodes vs audio-only or 1080p") {
            is4kResolution("4k") shouldBe true
            is4kResolution("UHD") shouldBe true
            is4kResolution("2160p") shouldBe true
            is4kResolution("8K") shouldBe true
            is4kResolution("1080p") shouldBe false
            is4kResolution("720") shouldBe false
            is4kResolution(null) shouldBe false

            is4kVideoTranscode(
                testSession("1", streamDecision = "transcode", videoDecision = "transcode", videoResolution = "4k"),
            ) shouldBe true
            is4kVideoTranscode(
                testSession("2", streamDecision = "transcode", videoDecision = "copy", audioDecision = "transcode", videoResolution = "4k"),
            ) shouldBe false
            is4kVideoTranscode(
                testSession("3", streamDecision = "direct_play", videoDecision = "direct_play", videoResolution = "4k"),
            ) shouldBe false
            is4kVideoTranscode(
                testSession("4", streamDecision = "transcode", videoDecision = "transcode", videoResolution = "1080p"),
            ) shouldBe false
            is4kVideoTranscode(
                testSession("5", streamDecision = "transcode", videoDecision = null, audioDecision = "copy", videoResolution = "2160p"),
            ) shouldBe true
            is4kVideoTranscode(
                testSession("6", state = "stopped", streamDecision = "transcode", videoDecision = "transcode", videoResolution = "4k"),
            ) shouldBe false
        }

        test("emits zero decisions when all rules are disabled") {
            val evaluator = StreamGuardrailEvaluator()
            val sessions =
                listOf(
                    testSession("1", state = "paused", streamDecision = "transcode", videoDecision = "transcode", videoResolution = "4k"),
                    testSession("2", startedAtMs = 2_000L),
                    testSession("3", startedAtMs = 3_000L),
                )
            evaluator.evaluate(sessions, rulesMap(), nowMs = 10_000_000L).shouldBeEmpty()
        }

        test("TRANSCODE_4K supports dry-run WARN without spam and promotes immediately to ENFORCE with retry cooldown") {
            val evaluator = StreamGuardrailEvaluator(enforceRetryCooldownMs = 60_000L)
            val s4k = testSession("1", streamDecision = "transcode", videoDecision = "transcode", videoResolution = "4k")
            val s1080 = testSession("2", streamDecision = "transcode", videoDecision = "transcode", videoResolution = "1080p")

            // First WARN triggers once
            val firstWarn =
                evaluator.evaluate(listOf(s4k, s1080), rulesMap(transcode4k = GuardrailMode.WARN), nowMs = 1_000L)
            firstWarn shouldHaveSize 1
            firstWarn.single().ruleType shouldBe GuardrailRuleType.TRANSCODE_4K
            firstWarn.single().mode shouldBe GuardrailMode.WARN
            firstWarn.single().session.sessionKey shouldBe "1"

            // Subsequent polls in WARN do not emit duplicates
            evaluator.evaluate(listOf(s4k, s1080), rulesMap(transcode4k = GuardrailMode.WARN), nowMs = 120_000L).shouldBeEmpty()

            // Promoting rule from WARN -> ENFORCE fires immediately
            val promoted =
                evaluator.evaluate(listOf(s4k, s1080), rulesMap(transcode4k = GuardrailMode.ENFORCE), nowMs = 130_000L)
            promoted shouldHaveSize 1
            promoted.single().mode shouldBe GuardrailMode.ENFORCE

            // Within cooldown, ENFORCE does not spam
            evaluator.evaluate(listOf(s4k, s1080), rulesMap(transcode4k = GuardrailMode.ENFORCE), nowMs = 150_000L).shouldBeEmpty()

            // After cooldown, ENFORCE retries if stream still alive
            val retried =
                evaluator.evaluate(listOf(s4k, s1080), rulesMap(transcode4k = GuardrailMode.ENFORCE), nowMs = 200_000L)
            retried shouldHaveSize 1

            // Stopping clears tracked state so a future session can warn again
            evaluator.onSessionStopped(s4k.key)
            val afterStop =
                evaluator.evaluate(listOf(s4k), rulesMap(transcode4k = GuardrailMode.WARN), nowMs = 210_000L)
            afterStop shouldHaveSize 1
        }

        test("PAUSED_TIMEOUT tracks continuous pause duration and resets timer when resumed") {
            val evaluator = StreamGuardrailEvaluator()
            val rules = rulesMap(pausedTimeout = GuardrailMode.ENFORCE to 30)
            val paused = testSession("1", state = "paused")
            val playing = testSession("1", state = "playing")

            // Pause begins at t=0
            evaluator.evaluate(listOf(paused), rules, nowMs = 0L).shouldBeEmpty()
            // 20 minutes paused -> still below 30m
            evaluator.evaluate(listOf(paused), rules, nowMs = 20 * 60_000L).shouldBeEmpty()
            // User resumes at 25m -> resets pause clock
            evaluator.evaluate(listOf(playing), rules, nowMs = 25 * 60_000L).shouldBeEmpty()
            // Pauses again at 30m -> continuous timer restarts from 30m
            evaluator.evaluate(listOf(paused), rules, nowMs = 30 * 60_000L).shouldBeEmpty()
            // At 55m (only 25m of new pause) -> still below 30m
            evaluator.evaluate(listOf(paused), rules, nowMs = 55 * 60_000L).shouldBeEmpty()
            // At 60m (30m continuous pause) -> triggers
            val decisions = evaluator.evaluate(listOf(paused), rules, nowMs = 60 * 60_000L)
            decisions shouldHaveSize 1
            decisions.single().ruleType shouldBe GuardrailRuleType.PAUSED_TIMEOUT
            decisions.single().mode shouldBe GuardrailMode.ENFORCE
        }

        test("CONCURRENT_STREAMS targets only newest excess streams per user and deduplicates multi-rule enforcement") {
            val evaluator = StreamGuardrailEvaluator()
            val aliceOld = testSession("1", userId = "u-alice", startedAtMs = 1_000L)
            val aliceMid = testSession("2", userId = "u-alice", startedAtMs = 2_000L)
            val aliceNew =
                testSession(
                    "3",
                    userId = "u-alice",
                    startedAtMs = 3_000L,
                    streamDecision = "transcode",
                    videoDecision = "transcode",
                    videoResolution = "4k",
                )
            val bobOnly = testSession("4", userId = "u-bob", startedAtMs = 500L)

            val warnRules = rulesMap(concurrentStreams = GuardrailMode.WARN to 2)
            val warnDecisions = evaluator.evaluate(listOf(aliceNew, aliceOld, aliceMid, bobOnly), warnRules, nowMs = 5_000L)
            warnDecisions.map { it.session.sessionKey } shouldContainExactly listOf("3")
            warnDecisions.single().mode shouldBe GuardrailMode.WARN

            // Both TRANSCODE_4K and CONCURRENT_STREAMS match aliceNew in ENFORCE, but only one termination emits per session
            val multiEnforce =
                rulesMap(
                    transcode4k = GuardrailMode.ENFORCE,
                    concurrentStreams = GuardrailMode.ENFORCE to 2,
                )
            val enforceDecisions =
                evaluator.evaluate(listOf(aliceOld, aliceMid, aliceNew, bobOnly), multiEnforce, nowMs = 6_000L)
            enforceDecisions shouldHaveSize 1
            enforceDecisions.single().session.sessionKey shouldBe "3"
            enforceDecisions.single().ruleType shouldBe GuardrailRuleType.TRANSCODE_4K
        }
    })
