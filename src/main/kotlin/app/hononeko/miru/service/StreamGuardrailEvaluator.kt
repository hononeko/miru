package app.hononeko.miru.service

import app.hononeko.miru.config.GuardrailsConfig
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MILLIS_PER_MINUTE = 60_000L
private const val MIN_4K_HEIGHT = 2160
private val RULE_EVALUATION_ORDER =
    listOf(
        GuardrailRuleType.TRANSCODE_4K,
        GuardrailRuleType.PAUSED_TIMEOUT,
        GuardrailRuleType.CONCURRENT_STREAMS,
    )

data class EffectiveGuardrailRule(
    val ruleType: GuardrailRuleType,
    val mode: GuardrailMode,
    val threshold: Int?,
    val message: String,
)

data class GuardrailDecision(
    val ruleType: GuardrailRuleType,
    val mode: GuardrailMode,
    val session: ActiveSessionRecord,
    val reason: String,
)

internal fun is4kResolution(videoResolution: String?): Boolean {
    val norm = videoResolution?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    if (norm == "4k" || norm == "uhd" || norm == "8k") {
        return true
    }
    val numeric = norm.removeSuffix("p").removeSuffix("i").toIntOrNull()
    return numeric != null && numeric >= MIN_4K_HEIGHT
}

internal fun is4kVideoTranscode(session: ActiveSessionRecord): Boolean {
    if (session.state.equals("stopped", ignoreCase = true)) return false
    if (!is4kResolution(session.streamTech.videoResolution)) return false
    val videoDecision = session.streamTech.videoDecision?.trim()?.lowercase()
    if (videoDecision != null) {
        return videoDecision == "transcode"
    }
    val streamDecision = session.streamTech.streamDecision.trim().lowercase()
    val audioDecision = session.streamTech.audioDecision?.trim()?.lowercase()
    return streamDecision == "transcode" && audioDecision != "transcode"
}

/**
 * Stateful guardrail evaluator tracking continuous pause start timestamps and prior triggers per session/rule.
 *
 * Ensures dry-run `WARN` rules emit once per continuous violation and `ENFORCE` rules respect a retry cooldown
 * while Plex tears down a terminated stream.
 *
 * A session that Plex did not list in the last poll has most likely ended, so it neither violates a rule nor counts
 * towards a limit. Its pause start and prior triggers are kept in case it reappears.
 */
class StreamGuardrailEvaluator(
    private val enforceRetryCooldownMs: Long = DEFAULT_ENFORCE_RETRY_COOLDOWN_MS,
) {
    private data class TriggerState(
        val mode: GuardrailMode,
        val triggeredAtMs: Long,
    )

    private val mutex = Mutex()
    private val pausedSinceMs = linkedMapOf<ActiveSessionKey, Long>()
    private val triggered = linkedMapOf<Pair<ActiveSessionKey, GuardrailRuleType>, TriggerState>()

    suspend fun evaluate(
        sessions: List<ActiveSessionRecord>,
        rules: Map<GuardrailRuleType, EffectiveGuardrailRule>,
        nowMs: Long,
    ): List<GuardrailDecision> =
        mutex.withLock {
            val activeSessions = sessions.filterNot { it.state.equals("stopped", ignoreCase = true) }
            syncPauseTrackingLocked(activeSessions, nowMs)

            val (listedSessions, missingSessions) = activeSessions.partition { it.missedPolls == 0 }
            val missingKeys = missingSessions.mapTo(HashSet(missingSessions.size)) { it.key }
            val violations = collectViolationsLocked(listedSessions, rules, nowMs)
            triggered.keys.removeAll { it !in violations.keys && it.first !in missingKeys }

            val enforcedSessions = hashSetOf<ActiveSessionKey>()
            val decisions = mutableListOf<GuardrailDecision>()

            for (ruleType in RULE_EVALUATION_ORDER) {
                for ((violationKey, ruleAndSession) in violations) {
                    if (violationKey.second != ruleType) continue
                    val (sessionKey, _) = violationKey
                    val (rule, session) = ruleAndSession
                    if (rule.mode == GuardrailMode.ENFORCE && sessionKey in enforcedSessions) {
                        continue
                    }

                    val prev = triggered[violationKey]
                    val shouldEmit =
                        prev == null ||
                            prev.mode != rule.mode ||
                            (rule.mode == GuardrailMode.ENFORCE && nowMs - prev.triggeredAtMs >= enforceRetryCooldownMs)
                    if (shouldEmit) {
                        triggered[violationKey] = TriggerState(mode = rule.mode, triggeredAtMs = nowMs)
                        if (rule.mode == GuardrailMode.ENFORCE) {
                            enforcedSessions += sessionKey
                        }
                        decisions +=
                            GuardrailDecision(
                                ruleType = ruleType,
                                mode = rule.mode,
                                session = session,
                                reason = rule.message,
                            )
                    }
                }
            }

            decisions
        }

    suspend fun onSessionStopped(key: ActiveSessionKey) {
        mutex.withLock {
            pausedSinceMs.remove(key)
            triggered.keys.removeAll { it.first == key }
        }
    }

    private fun syncPauseTrackingLocked(
        activeSessions: List<ActiveSessionRecord>,
        nowMs: Long,
    ) {
        val activeKeys = activeSessions.mapTo(HashSet(activeSessions.size)) { it.key }
        pausedSinceMs.keys.retainAll(activeKeys)
        triggered.keys.removeAll { it.first !in activeKeys }

        for (session in activeSessions) {
            if (session.state.equals("paused", ignoreCase = true)) {
                pausedSinceMs.putIfAbsent(session.key, nowMs)
            } else {
                pausedSinceMs.remove(session.key)
            }
        }
    }

    private fun collectViolationsLocked(
        activeSessions: List<ActiveSessionRecord>,
        rules: Map<GuardrailRuleType, EffectiveGuardrailRule>,
        nowMs: Long,
    ): LinkedHashMap<Pair<ActiveSessionKey, GuardrailRuleType>, Pair<EffectiveGuardrailRule, ActiveSessionRecord>> {
        val violations = linkedMapOf<Pair<ActiveSessionKey, GuardrailRuleType>, Pair<EffectiveGuardrailRule, ActiveSessionRecord>>()

        val transcodeRule = rules[GuardrailRuleType.TRANSCODE_4K]
        if (transcodeRule != null && transcodeRule.mode != GuardrailMode.DISABLED) {
            for (session in activeSessions) {
                if (is4kVideoTranscode(session)) {
                    violations[session.key to GuardrailRuleType.TRANSCODE_4K] = transcodeRule to session
                }
            }
        }

        val pausedRule = rules[GuardrailRuleType.PAUSED_TIMEOUT]
        if (pausedRule != null && pausedRule.mode != GuardrailMode.DISABLED) {
            val thresholdMinutes = pausedRule.threshold ?: GuardrailsConfig.DEFAULT_PAUSED_TIMEOUT_MINUTES
            val thresholdMs = thresholdMinutes.toLong() * MILLIS_PER_MINUTE
            for (session in activeSessions) {
                if (session.state.equals("paused", ignoreCase = true)) {
                    val pausedAt = pausedSinceMs[session.key] ?: nowMs
                    if (nowMs - pausedAt >= thresholdMs) {
                        violations[session.key to GuardrailRuleType.PAUSED_TIMEOUT] = pausedRule to session
                    }
                }
            }
        }

        val concurrentRule = rules[GuardrailRuleType.CONCURRENT_STREAMS]
        if (concurrentRule != null && concurrentRule.mode != GuardrailMode.DISABLED) {
            val maxStreams = concurrentRule.threshold ?: GuardrailsConfig.DEFAULT_CONCURRENT_STREAMS_MAX
            val comparator =
                compareBy<ActiveSessionRecord> { it.startedAtMs }
                    .thenBy { it.sessionKey }
                    .thenBy { it.media.ratingKey }
            activeSessions
                .groupBy { it.userId }
                .values
                .filter { it.size > maxStreams }
                .forEach { userSessions ->
                    userSessions
                        .sortedWith(comparator)
                        .drop(maxStreams)
                        .forEach { excess ->
                            violations[excess.key to GuardrailRuleType.CONCURRENT_STREAMS] = concurrentRule to excess
                        }
                }
        }

        return violations
    }

    companion object {
        const val DEFAULT_ENFORCE_RETRY_COOLDOWN_MS = 60_000L
    }
}
