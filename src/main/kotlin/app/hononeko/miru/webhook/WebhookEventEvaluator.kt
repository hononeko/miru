package app.hononeko.miru.webhook

import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.service.is4kResolution
import app.hononeko.miru.session.SessionEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_BUFFERING_RETENTION_MS = WebhookTargetRecord.MAX_BUFFERING_WINDOW_SECONDS * MILLIS_PER_SECOND
private const val MAX_COOLDOWN_CACHE_SIZE = 4_096

data class OutboundWebhookMatch(
    val target: WebhookTargetRecord,
    val payload: WebhookPayload,
)

internal fun isVideoTranscoding(session: ActiveSessionRecord): Boolean {
    if (session.state.equals("stopped", ignoreCase = true)) return false
    val videoDecision = session.streamTech.videoDecision?.trim()?.lowercase()
    if (videoDecision != null) {
        return videoDecision == "transcode"
    }
    val streamDecision = session.streamTech.streamDecision.trim().lowercase()
    val audioDecision = session.streamTech.audioDecision?.trim()?.lowercase()
    return streamDecision == "transcode" && audioDecision != "transcode"
}

/**
 * Stateful evaluator that translates session updates, stream terminations, and server reachability
 * signals into deduplicated, cooldown-gated outbound webhook payloads per subscribed target.
 */
class WebhookEventEvaluator(
    private val unreachableFailureThreshold: Int = DEFAULT_UNREACHABLE_FAILURE_THRESHOLD,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(unreachableFailureThreshold >= 1) { "unreachableFailureThreshold must be >= 1" }
    }

    private data class CooldownKey(
        val targetId: String,
        val eventKey: String,
        val scopeKey: String,
    )

    private val mutex = Mutex()
    private val transcodingSessions = hashSetOf<ActiveSessionKey>()
    private val bufferingTimestamps = linkedMapOf<ActiveSessionKey, ArrayDeque<Long>>()
    private val lastAlertedConcurrentCount = linkedMapOf<Pair<String, String>, Int>()
    private val cooldownLastFiredMs = linkedMapOf<CooldownKey, Long>()

    private var consecutiveReconcileFailures = 0
    private var firstFailureAtMs: Long? = null
    private var serverMarkedUnreachable = false
    private var outageStartedAtMs: Long? = null

    suspend fun onSessionEvent(
        event: SessionEvent,
        activeSessions: List<ActiveSessionRecord>,
        targets: List<WebhookTargetRecord>,
        server: WebhookServerPayload?,
        user: UserRecord?,
    ): List<OutboundWebhookMatch> =
        mutex.withLock {
            val enabledTargets = targets.filter { it.enabled }
            when (event) {
                is SessionEvent.SessionStopped -> {
                    cleanupStoppedSessionLocked(event.key, activeSessions)
                    emptyList()
                }

                is SessionEvent.SessionStarted -> {
                    val nowMs = event.timestampMs
                    val matches = mutableListOf<OutboundWebhookMatch>()
                    matches += evaluateTranscodeStartedLocked(event.session, enabledTargets, server, user, nowMs)
                    matches += evaluateConcurrentLimitLocked(event.session, activeSessions, enabledTargets, server, user, nowMs)
                    if (event.session.state.equals("buffering", ignoreCase = true)) {
                        matches += evaluateBufferingSpikeLocked(event.session, enabledTargets, server, user, nowMs)
                    }
                    matches
                }

                is SessionEvent.SessionUpdated -> {
                    val nowMs = event.timestampMs
                    val matches = mutableListOf<OutboundWebhookMatch>()
                    matches += evaluateTranscodeStartedLocked(event.session, enabledTargets, server, user, nowMs)
                    matches += evaluateConcurrentLimitLocked(event.session, activeSessions, enabledTargets, server, user, nowMs)
                    val transitionedToBuffering =
                        event.session.state.equals("buffering", ignoreCase = true) &&
                            !event.previousState.equals("buffering", ignoreCase = true)
                    if (transitionedToBuffering) {
                        matches += evaluateBufferingSpikeLocked(event.session, enabledTargets, server, user, nowMs)
                    }
                    matches
                }
            }
        }

    suspend fun onStreamTerminated(
        event: StreamTerminatedEvent,
        targets: List<WebhookTargetRecord>,
        server: WebhookServerPayload?,
        user: UserRecord?,
    ): List<OutboundWebhookMatch> =
        mutex.withLock {
            val enabledTargets = targets.filter { it.enabled && WebhookEventType.STREAM_TERMINATED in it.subscribedEvents }
            if (enabledTargets.isEmpty()) return@withLock emptyList()

            val session = event.session
            val scopeKey = "${session.sessionKey}:${session.media.ratingKey}"
            val matches = mutableListOf<OutboundWebhookMatch>()

            for (target in enabledTargets) {
                val cooldownKey = CooldownKey(target.id, WebhookEventType.STREAM_TERMINATED.eventKey, scopeKey)
                if (!tryAcquireCooldownLocked(cooldownKey, target.cooldownSeconds, event.timestampMs)) {
                    continue
                }
                matches +=
                    OutboundWebhookMatch(
                        target = target,
                        payload =
                            WebhookPayload(
                                event = WebhookEventType.STREAM_TERMINATED.eventKey,
                                eventId = idGenerator(),
                                timestamp = Instant.ofEpochMilli(event.timestampMs).toString(),
                                server = server,
                                user = user?.toWebhookUserPayload(),
                                session = session.toWebhookSessionPayload(),
                                details =
                                    buildJsonObject {
                                        put("reason", event.reason)
                                        put("source", event.source.name.lowercase())
                                        put("actorName", event.actorName)
                                        event.ruleType?.let { put("ruleType", it.name) }
                                    },
                            ),
                    )
            }
            matches
        }

    suspend fun onReconcileHealth(
        signal: ReconcileHealthSignal,
        targets: List<WebhookTargetRecord>,
        server: WebhookServerPayload?,
    ): List<OutboundWebhookMatch> =
        mutex.withLock {
            val scopeKey = server?.id ?: "plex"
            if (!signal.reachable) {
                consecutiveReconcileFailures++
                if (firstFailureAtMs == null) {
                    firstFailureAtMs = signal.timestampMs
                }
                if (serverMarkedUnreachable || consecutiveReconcileFailures < unreachableFailureThreshold) {
                    return@withLock emptyList()
                }
                serverMarkedUnreachable = true
                outageStartedAtMs = firstFailureAtMs ?: signal.timestampMs

                val subscribed = targets.filter { it.enabled && WebhookEventType.SERVER_UNREACHABLE in it.subscribedEvents }
                if (subscribed.isEmpty()) return@withLock emptyList()

                val sanitizedReason = WebhookUrlSafety.sanitizeErrorMessage(signal.errorMessage)
                val matches = mutableListOf<OutboundWebhookMatch>()
                for (target in subscribed) {
                    val cooldownKey = CooldownKey(target.id, WebhookEventType.SERVER_UNREACHABLE.eventKey, scopeKey)
                    if (!tryAcquireCooldownLocked(cooldownKey, target.cooldownSeconds, signal.timestampMs)) {
                        continue
                    }
                    matches +=
                        OutboundWebhookMatch(
                            target = target,
                            payload =
                                WebhookPayload(
                                    event = WebhookEventType.SERVER_UNREACHABLE.eventKey,
                                    eventId = idGenerator(),
                                    timestamp = Instant.ofEpochMilli(signal.timestampMs).toString(),
                                    server = server,
                                    details =
                                        buildJsonObject {
                                            put("consecutiveFailures", consecutiveReconcileFailures)
                                            put("reason", sanitizedReason)
                                        },
                                ),
                        )
                }
                return@withLock matches
            }

            // Reachable again
            val wasUnreachable = serverMarkedUnreachable
            val outageStart = outageStartedAtMs
            consecutiveReconcileFailures = 0
            firstFailureAtMs = null
            serverMarkedUnreachable = false
            outageStartedAtMs = null

            if (!wasUnreachable) {
                return@withLock emptyList()
            }

            val outageDurationSec =
                outageStart?.let { ((signal.timestampMs - it).coerceAtLeast(0L)) / MILLIS_PER_SECOND } ?: 0L
            val subscribed = targets.filter { it.enabled && WebhookEventType.SERVER_RECOVERED in it.subscribedEvents }
            if (subscribed.isEmpty()) return@withLock emptyList()

            val matches = mutableListOf<OutboundWebhookMatch>()
            for (target in subscribed) {
                val cooldownKey = CooldownKey(target.id, WebhookEventType.SERVER_RECOVERED.eventKey, scopeKey)
                if (!tryAcquireCooldownLocked(cooldownKey, target.cooldownSeconds, signal.timestampMs)) {
                    continue
                }
                matches +=
                    OutboundWebhookMatch(
                        target = target,
                        payload =
                            WebhookPayload(
                                event = WebhookEventType.SERVER_RECOVERED.eventKey,
                                eventId = idGenerator(),
                                timestamp = Instant.ofEpochMilli(signal.timestampMs).toString(),
                                server = server,
                                details =
                                    buildJsonObject {
                                        put("outageDurationSec", outageDurationSec)
                                    },
                            ),
                    )
            }
            matches
        }

    fun buildTestPayload(
        server: WebhookServerPayload?,
        nowMs: Long,
    ): WebhookPayload =
        WebhookPayload(
            event = WebhookEventType.TEST_EVENT_KEY,
            eventId = idGenerator(),
            timestamp = Instant.ofEpochMilli(nowMs).toString(),
            server = server,
            details =
                buildJsonObject {
                    put("message", "Test webhook delivery from Miru")
                },
        )

    private fun evaluateTranscodeStartedLocked(
        session: ActiveSessionRecord,
        enabledTargets: List<WebhookTargetRecord>,
        server: WebhookServerPayload?,
        user: UserRecord?,
        nowMs: Long,
    ): List<OutboundWebhookMatch> {
        val nowTranscoding = isVideoTranscoding(session)
        val wasTranscoding = session.key in transcodingSessions
        if (!nowTranscoding) {
            transcodingSessions.remove(session.key)
            return emptyList()
        }
        transcodingSessions.add(session.key)
        if (wasTranscoding) {
            return emptyList()
        }

        val is4k = is4kResolution(session.streamTech.videoResolution)
        val scopeKey = "${session.sessionKey}:${session.media.ratingKey}"
        val matches = mutableListOf<OutboundWebhookMatch>()

        for (target in enabledTargets) {
            if (WebhookEventType.TRANSCODE_STARTED !in target.subscribedEvents) continue
            if (target.transcode4kOnly && !is4k) continue

            val cooldownKey = CooldownKey(target.id, WebhookEventType.TRANSCODE_STARTED.eventKey, scopeKey)
            if (!tryAcquireCooldownLocked(cooldownKey, target.cooldownSeconds, nowMs)) continue

            matches +=
                OutboundWebhookMatch(
                    target = target,
                    payload =
                        WebhookPayload(
                            event = WebhookEventType.TRANSCODE_STARTED.eventKey,
                            eventId = idGenerator(),
                            timestamp = Instant.ofEpochMilli(nowMs).toString(),
                            server = server,
                            user = user?.toWebhookUserPayload(),
                            session = session.toWebhookSessionPayload(),
                            details =
                                buildJsonObject {
                                    put("hwTranscode", session.streamTech.hwTranscode)
                                    put("is4k", is4k)
                                    session.streamTech.videoResolution?.let { put("videoResolution", it) }
                                    session.streamTech.videoCodec?.let { put("videoCodec", it) }
                                    session.streamTech.transcodeVideoCodec?.let { put("transcodeVideoCodec", it) }
                                },
                        ),
                )
        }
        return matches
    }

    private fun evaluateConcurrentLimitLocked(
        session: ActiveSessionRecord,
        activeSessions: List<ActiveSessionRecord>,
        enabledTargets: List<WebhookTargetRecord>,
        server: WebhookServerPayload?,
        user: UserRecord?,
        nowMs: Long,
    ): List<OutboundWebhookMatch> {
        val activeUserStreams =
            activeSessions.count { active ->
                active.userId == session.userId && !active.state.equals("stopped", ignoreCase = true)
            }
        val matches = mutableListOf<OutboundWebhookMatch>()

        for (target in enabledTargets) {
            if (WebhookEventType.STREAM_CONCURRENT_LIMIT !in target.subscribedEvents) continue
            val pairKey = target.id to session.userId
            if (activeUserStreams <= target.concurrentStreamThreshold) {
                lastAlertedConcurrentCount.remove(pairKey)
                continue
            }

            val prevAlerted = lastAlertedConcurrentCount[pairKey]
            if (prevAlerted != null && activeUserStreams <= prevAlerted) {
                continue
            }

            val cooldownKey = CooldownKey(target.id, WebhookEventType.STREAM_CONCURRENT_LIMIT.eventKey, session.userId)
            if (!tryAcquireCooldownLocked(cooldownKey, target.cooldownSeconds, nowMs)) {
                continue
            }

            lastAlertedConcurrentCount[pairKey] = activeUserStreams
            matches +=
                OutboundWebhookMatch(
                    target = target,
                    payload =
                        WebhookPayload(
                            event = WebhookEventType.STREAM_CONCURRENT_LIMIT.eventKey,
                            eventId = idGenerator(),
                            timestamp = Instant.ofEpochMilli(nowMs).toString(),
                            server = server,
                            user = user?.toWebhookUserPayload(),
                            session = session.toWebhookSessionPayload(),
                            details =
                                buildJsonObject {
                                    put("activeStreamCount", activeUserStreams)
                                    put("threshold", target.concurrentStreamThreshold)
                                },
                        ),
                )
        }
        return matches
    }

    private fun evaluateBufferingSpikeLocked(
        session: ActiveSessionRecord,
        enabledTargets: List<WebhookTargetRecord>,
        server: WebhookServerPayload?,
        user: UserRecord?,
        nowMs: Long,
    ): List<OutboundWebhookMatch> {
        val timestamps = bufferingTimestamps.getOrPut(session.key) { ArrayDeque() }
        timestamps.addLast(nowMs)
        val retentionCutoff = nowMs - MAX_BUFFERING_RETENTION_MS
        while (timestamps.isNotEmpty() && timestamps.first() < retentionCutoff) {
            timestamps.removeFirst()
        }

        val scopeKey = "${session.sessionKey}:${session.media.ratingKey}"
        val matches = mutableListOf<OutboundWebhookMatch>()

        for (target in enabledTargets) {
            if (WebhookEventType.STREAM_BUFFERING_SPIKE !in target.subscribedEvents) continue
            val windowCutoff = nowMs - target.bufferingWindowSeconds.toLong() * MILLIS_PER_SECOND
            val recentCount = timestamps.count { it >= windowCutoff }
            if (recentCount < target.bufferingSpikeThreshold) continue

            val cooldownKey = CooldownKey(target.id, WebhookEventType.STREAM_BUFFERING_SPIKE.eventKey, scopeKey)
            if (!tryAcquireCooldownLocked(cooldownKey, target.cooldownSeconds, nowMs)) continue

            matches +=
                OutboundWebhookMatch(
                    target = target,
                    payload =
                        WebhookPayload(
                            event = WebhookEventType.STREAM_BUFFERING_SPIKE.eventKey,
                            eventId = idGenerator(),
                            timestamp = Instant.ofEpochMilli(nowMs).toString(),
                            server = server,
                            user = user?.toWebhookUserPayload(),
                            session = session.toWebhookSessionPayload(),
                            details =
                                buildJsonObject {
                                    put("bufferingCount", recentCount)
                                    put("threshold", target.bufferingSpikeThreshold)
                                    put("windowSeconds", target.bufferingWindowSeconds)
                                },
                        ),
                )
        }
        return matches
    }

    private fun cleanupStoppedSessionLocked(
        key: ActiveSessionKey,
        remainingActiveSessions: List<ActiveSessionRecord>,
    ) {
        transcodingSessions.remove(key)
        bufferingTimestamps.remove(key)
        val activeCountsByUser =
            remainingActiveSessions
                .filterNot { it.state.equals("stopped", ignoreCase = true) }
                .groupingBy { it.userId }
                .eachCount()
        val iterator = lastAlertedConcurrentCount.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val currentCount = activeCountsByUser[entry.key.second] ?: 0
            if (currentCount == 0 || currentCount < entry.value) {
                iterator.remove()
            }
        }
    }

    private fun tryAcquireCooldownLocked(
        key: CooldownKey,
        cooldownSeconds: Int,
        nowMs: Long,
    ): Boolean {
        val cooldownMs = cooldownSeconds.toLong() * MILLIS_PER_SECOND
        val lastFired = cooldownLastFiredMs[key]
        if (lastFired != null && cooldownMs > 0L && nowMs - lastFired < cooldownMs) {
            return false
        }
        cooldownLastFiredMs[key] = nowMs
        if (cooldownLastFiredMs.size > MAX_COOLDOWN_CACHE_SIZE) {
            val oldestKey = cooldownLastFiredMs.keys.firstOrNull()
            if (oldestKey != null) {
                cooldownLastFiredMs.remove(oldestKey)
            }
        }
        return true
    }

    companion object {
        const val DEFAULT_UNREACHABLE_FAILURE_THRESHOLD = 3
    }
}
