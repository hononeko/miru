package app.hononeko.miru.session

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.util.UUID

private const val MILLIS_PER_SECOND = 1_000L
private const val PERCENT_SCALE = 100.0
private const val SESSION_EVENT_BUFFER_CAPACITY = 256
private val PAUSED_STATE = PlaybackState.PAUSED.name.lowercase()
private val STOPPED_STATE = PlaybackState.STOPPED.name.lowercase()

/**
 * Reconciles active stream snapshots into persisted `active_sessions` and finalized `playback_history` records.
 */
class SessionStateEngine(
    private val serverIdProvider: suspend () -> Either<MiruError, String>,
    private val userRepository: UserRepository,
    private val activeSessionRepository: ActiveSessionRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val config: SessionConfig = SessionConfig(),
    private val missedPollsThreshold: Int = DEFAULT_MISSED_POLLS_THRESHOLD,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ActiveSessionsSink {
    init {
        require(missedPollsThreshold >= 1) { "missedPollsThreshold must be >= 1" }
    }

    private data class TrackedSession(
        val record: ActiveSessionRecord,
        val pausedDurationMs: Long = record.pausedDurationSec * MILLIS_PER_SECOND,
        val fromCrashRecovery: Boolean = false,
    )

    private val logger = LoggerFactory.getLogger(SessionStateEngine::class.java)
    private val mutex = Mutex()
    private val tracked = linkedMapOf<ActiveSessionKey, TrackedSession>()
    private var recovered = false

    private val _sessionEvents =
        MutableSharedFlow<SessionEvent>(
            extraBufferCapacity = SESSION_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    suspend fun currentSessions(): List<ActiveSessionRecord> =
        mutex.withLock {
            tracked.values.map { it.record }
        }

    suspend fun recoverFromPersistence(): Either<MiruError, Unit> =
        mutex.withLock {
            either {
                ensureRecoveredLocked()
            }
        }

    override suspend fun onSnapshots(
        snapshots: List<ActiveStreamSnapshot>,
        trigger: ReconcileTrigger,
    ) {
        reconcile(snapshots, trigger).fold(
            ifLeft = { error ->
                logger.warn(
                    "Session reconciliation failed {} {}",
                    kv("trigger", trigger.name),
                    kv("reason", error.message),
                )
            },
            ifRight = { /* Events emitted after persistence succeeds */ },
        )
    }

    suspend fun reconcile(
        snapshots: List<ActiveStreamSnapshot>,
        trigger: ReconcileTrigger,
    ): Either<MiruError, List<SessionEvent>> {
        val result =
            mutex.withLock {
                either {
                    val serverId = ensureRecoveredLocked()
                    val nowMs = clock()
                    val events = mutableListOf<SessionEvent>()

                    val incomingKeys = HashSet<ActiveSessionKey>(snapshots.size)
                    val incomingSessionKeys = HashSet<String>(snapshots.size)
                    for (snapshot in snapshots) {
                        val key = snapshotKey(serverId, snapshot)
                        incomingKeys += key
                        if (snapshot.state != PlaybackState.STOPPED) {
                            incomingSessionKeys += key.sessionKey
                        }
                    }

                    for (snapshot in snapshots) {
                        val key = snapshotKey(serverId, snapshot)
                        val existing = tracked[key]
                        val event =
                            if (existing == null) {
                                handleNewSnapshot(serverId, key, snapshot, nowMs)
                            } else {
                                handleOngoingSnapshot(serverId, existing, snapshot, nowMs)
                            }
                        if (event != null) {
                            events += event
                        }
                    }

                    for ((key, prev) in tracked.entries.toList()) {
                        if (key in incomingKeys) continue
                        val event = handleMissingSession(serverId, prev, incomingSessionKeys, nowMs)
                        if (event != null) {
                            events += event
                        }
                    }

                    logger.debug(
                        "Reconciled session state {} {} {} {}",
                        kv("trigger", trigger.name),
                        kv("snapshots", snapshots.size),
                        kv("active", tracked.size),
                        kv("events", events.size),
                    )
                    events.toList()
                }
            }

        result.onRight { events ->
            for (event in events) {
                _sessionEvents.emit(event)
            }
        }
        return result
    }

    private suspend fun Raise<MiruError>.ensureRecoveredLocked(): String {
        val serverId = serverIdProvider().bind()
        if (!recovered) {
            val existing = activeSessionRepository.findByServer(serverId).bind()
            tracked.clear()
            for (record in existing) {
                tracked[record.key] =
                    TrackedSession(
                        record = record,
                        pausedDurationMs = record.pausedDurationSec * MILLIS_PER_SECOND,
                        fromCrashRecovery = true,
                    )
            }
            recovered = true
        }
        return serverId
    }

    private suspend fun Raise<MiruError>.handleNewSnapshot(
        serverId: String,
        key: ActiveSessionKey,
        snapshot: ActiveStreamSnapshot,
        nowMs: Long,
    ): SessionEvent? {
        if (snapshot.state == PlaybackState.STOPPED) {
            return null
        }

        val userId = resolveUserId(serverId, snapshot.user, nowMs)
        val record =
            buildActiveSessionRecord(
                serverId = serverId,
                key = key,
                userId = userId,
                snapshot = snapshot,
                startedAtMs = nowMs,
                lastSeenAtMs = nowMs,
                pausedDurationSec = 0,
                fallbackDurationMs = 0L,
            )
        activeSessionRepository.upsert(record).bind()
        tracked[key] = TrackedSession(record = record, pausedDurationMs = 0L, fromCrashRecovery = false)

        return SessionEvent.SessionStarted(
            serverId = serverId,
            key = key,
            timestampMs = nowMs,
            session = record,
        )
    }

    private suspend fun Raise<MiruError>.handleOngoingSnapshot(
        serverId: String,
        prev: TrackedSession,
        snapshot: ActiveStreamSnapshot,
        nowMs: Long,
    ): SessionEvent {
        val totalPausedMs = accumulatePauseDurationMs(prev, nowMs)

        if (snapshot.state == PlaybackState.STOPPED) {
            val effectiveViewOffsetMs =
                if (snapshot.viewOffsetMs > 0L) snapshot.viewOffsetMs else prev.record.viewOffsetMs
            val stoppedRecord =
                prev.record.copy(
                    viewOffsetMs = effectiveViewOffsetMs,
                    state = STOPPED_STATE,
                    lastSeenAtMs = nowMs,
                    pausedDurationSec = (totalPausedMs / MILLIS_PER_SECOND).toInt(),
                )
            return finalizeSession(
                serverId = serverId,
                record = stoppedRecord,
                pausedDurationMs = totalPausedMs,
                stoppedAtMs = nowMs,
                reason = StopReason.EXPLICIT_STOP,
                eventTimestampMs = nowMs,
            )
        }

        userRepository.updateLastSeen(prev.record.userId, lastSeenAtMs = nowMs).bind()

        val updated =
            buildActiveSessionRecord(
                serverId = serverId,
                key = prev.record.key,
                userId = prev.record.userId,
                snapshot = snapshot,
                startedAtMs = prev.record.startedAtMs,
                lastSeenAtMs = nowMs,
                pausedDurationSec = (totalPausedMs / MILLIS_PER_SECOND).toInt(),
                fallbackDurationMs = prev.record.media.durationMs,
            )
        activeSessionRepository.upsert(updated).bind()
        tracked[prev.record.key] =
            TrackedSession(
                record = updated,
                pausedDurationMs = totalPausedMs,
                fromCrashRecovery = false,
            )

        return SessionEvent.SessionUpdated(
            serverId = serverId,
            key = prev.record.key,
            timestampMs = nowMs,
            session = updated,
            previousState = prev.record.state,
            previousStreamDecision = prev.record.streamTech.streamDecision,
            streamDecisionChanged = prev.record.streamTech.streamDecision != updated.streamTech.streamDecision,
        )
    }

    private suspend fun Raise<MiruError>.handleMissingSession(
        serverId: String,
        prev: TrackedSession,
        incomingSessionKeys: Set<String>,
        nowMs: Long,
    ): SessionEvent? {
        if (prev.fromCrashRecovery) {
            return finalizeSession(
                serverId = serverId,
                record = prev.record,
                pausedDurationMs = prev.pausedDurationMs,
                stoppedAtMs = prev.record.lastSeenAtMs,
                reason = StopReason.CRASH_RECOVERY,
                eventTimestampMs = nowMs,
            )
        }

        if (prev.record.sessionKey in incomingSessionKeys) {
            val totalPausedMs = accumulatePauseDurationMs(prev, nowMs)
            val finalizedRecord =
                prev.record.copy(
                    pausedDurationSec = (totalPausedMs / MILLIS_PER_SECOND).toInt(),
                )
            return finalizeSession(
                serverId = serverId,
                record = finalizedRecord,
                pausedDurationMs = totalPausedMs,
                stoppedAtMs = nowMs,
                reason = StopReason.VANISHED,
                eventTimestampMs = nowMs,
            )
        }

        val nextMissedPolls = prev.record.missedPolls + 1
        if (nextMissedPolls >= missedPollsThreshold) {
            return finalizeSession(
                serverId = serverId,
                record = prev.record,
                pausedDurationMs = prev.pausedDurationMs,
                stoppedAtMs = prev.record.lastSeenAtMs,
                reason = StopReason.VANISHED,
                eventTimestampMs = nowMs,
            )
        }

        val updated = prev.record.copy(missedPolls = nextMissedPolls)
        activeSessionRepository.upsert(updated).bind()
        tracked[prev.record.key] = prev.copy(record = updated)
        return null
    }

    private suspend fun Raise<MiruError>.finalizeSession(
        serverId: String,
        record: ActiveSessionRecord,
        pausedDurationMs: Long,
        stoppedAtMs: Long,
        reason: StopReason,
        eventTimestampMs: Long,
    ): SessionEvent.SessionStopped {
        activeSessionRepository.deleteByKey(record.key).bind()
        tracked.remove(record.key)

        val wallSec = ((stoppedAtMs - record.startedAtMs).coerceAtLeast(0L) / MILLIS_PER_SECOND).toInt()
        val pausedSec = (pausedDurationMs / MILLIS_PER_SECOND).toInt().coerceAtMost(wallSec)
        val watchedSec = (wallSec - pausedSec).coerceAtLeast(0)
        val completionPercent = calculateCompletionPercent(record.viewOffsetMs, record.media.durationMs)
        val isWatched = completionPercent >= config.watchedThresholdPercent.toDouble()

        val mergedHistory =
            tryMergeIntoHistory(
                serverId = serverId,
                record = record,
                watchedSec = watchedSec,
                pausedSec = pausedSec,
                stoppedAtMs = stoppedAtMs,
            )
        if (mergedHistory != null) {
            return SessionEvent.SessionStopped(
                serverId = serverId,
                key = record.key,
                timestampMs = eventTimestampMs,
                session = record,
                historyRecord = mergedHistory,
                mergedIntoHistory = true,
                reason = reason,
            )
        }

        if (watchedSec < config.minPlayDurationSeconds) {
            return SessionEvent.SessionStopped(
                serverId = serverId,
                key = record.key,
                timestampMs = eventTimestampMs,
                session = record,
                historyRecord = null,
                mergedIntoHistory = false,
                reason = reason,
            )
        }

        val historyRecord =
            PlaybackHistoryRecord(
                id = idGenerator(),
                serverId = serverId,
                userId = record.userId,
                externalImportKey = null,
                media = record.media,
                viewOffsetMs = record.viewOffsetMs,
                watchedDurationSec = watchedSec,
                pausedDurationSec = pausedSec,
                completionPercent = completionPercent,
                isWatched = isWatched,
                streamTech = record.streamTech,
                player = record.player,
                startedAtMs = record.startedAtMs,
                stoppedAtMs = stoppedAtMs,
            )
        playbackHistoryRepository.insert(historyRecord).bind()

        return SessionEvent.SessionStopped(
            serverId = serverId,
            key = record.key,
            timestampMs = eventTimestampMs,
            session = record,
            historyRecord = historyRecord,
            mergedIntoHistory = false,
            reason = reason,
        )
    }

    private suspend fun Raise<MiruError>.tryMergeIntoHistory(
        serverId: String,
        record: ActiveSessionRecord,
        watchedSec: Int,
        pausedSec: Int,
        stoppedAtMs: Long,
    ): PlaybackHistoryRecord? {
        if (config.resumeMergeWindowSeconds <= 0) {
            return null
        }

        val minStoppedAtMs = record.startedAtMs - config.resumeMergeWindowSeconds * MILLIS_PER_SECOND
        val candidate =
            playbackHistoryRepository
                .findLatestMergeCandidate(
                    serverId = serverId,
                    userId = record.userId,
                    ratingKey = record.media.ratingKey,
                    minStoppedAtMs = minStoppedAtMs,
                    playerMachineId = record.player.playerMachineId,
                ).bind()
                ?: return null

        val matchesWindowAndPlayer =
            candidate.stoppedAtMs <= record.startedAtMs &&
                candidate.player.playerMachineId == record.player.playerMachineId
        if (!matchesWindowAndPlayer) {
            return null
        }

        val mergedViewOffsetMs =
            if (record.viewOffsetMs > 0L) {
                maxOf(candidate.viewOffsetMs, record.viewOffsetMs)
            } else {
                candidate.viewOffsetMs
            }
        val mergedMedia = if (record.media.durationMs > 0L) record.media else candidate.media
        val mergedCompletion =
            if (mergedMedia.durationMs > 0L) {
                calculateCompletionPercent(mergedViewOffsetMs, mergedMedia.durationMs)
            } else {
                candidate.completionPercent
            }
        val merged =
            candidate.copy(
                media = mergedMedia,
                viewOffsetMs = mergedViewOffsetMs,
                watchedDurationSec = candidate.watchedDurationSec + watchedSec,
                pausedDurationSec = candidate.pausedDurationSec + pausedSec,
                completionPercent = mergedCompletion,
                isWatched = candidate.isWatched || mergedCompletion >= config.watchedThresholdPercent.toDouble(),
                streamTech = record.streamTech,
                player = record.player,
                startedAtMs = minOf(candidate.startedAtMs, record.startedAtMs),
                stoppedAtMs = maxOf(candidate.stoppedAtMs, stoppedAtMs),
            )
        playbackHistoryRepository.updateMergedSession(merged).bind()
        return merged
    }

    private suspend fun Raise<MiruError>.resolveUserId(
        serverId: String,
        user: StreamUser,
        nowMs: Long,
    ): String {
        val existing = userRepository.findByExternalId(serverId, user.externalId).bind()
        if (existing != null) {
            userRepository.updateLastSeen(existing.id, lastSeenAtMs = nowMs).bind()
            return existing.id
        }

        val created =
            userRepository
                .upsert(
                    UserRecord(
                        id = idGenerator(),
                        serverId = serverId,
                        externalUserId = user.externalId,
                        username = user.username,
                        displayName = user.username,
                        thumbUrl = user.thumbUrl,
                        isAdmin = false,
                        lastSeenAtMs = nowMs,
                        createdAtMs = nowMs,
                        updatedAtMs = nowMs,
                    ),
                ).bind()
        return created.id
    }

    private fun accumulatePauseDurationMs(
        prev: TrackedSession,
        nowMs: Long,
    ): Long =
        if (prev.record.state == PAUSED_STATE) {
            prev.pausedDurationMs + (nowMs - prev.record.lastSeenAtMs).coerceAtLeast(0L)
        } else {
            prev.pausedDurationMs
        }

    private fun snapshotKey(
        serverId: String,
        snapshot: ActiveStreamSnapshot,
    ): ActiveSessionKey =
        ActiveSessionKey(
            serverId = serverId,
            sessionKey = snapshot.sessionKey ?: snapshot.sessionId,
            ratingKey = snapshot.media.ratingKey,
        )

    private fun calculateCompletionPercent(
        viewOffsetMs: Long,
        durationMs: Long,
    ): Double =
        if (durationMs > 0L) {
            ((viewOffsetMs.toDouble() / durationMs.toDouble()) * PERCENT_SCALE).coerceIn(0.0, PERCENT_SCALE)
        } else {
            0.0
        }

    @Suppress("LongParameterList")
    private fun buildActiveSessionRecord(
        serverId: String,
        key: ActiveSessionKey,
        userId: String,
        snapshot: ActiveStreamSnapshot,
        startedAtMs: Long,
        lastSeenAtMs: Long,
        pausedDurationSec: Int,
        fallbackDurationMs: Long,
    ): ActiveSessionRecord =
        ActiveSessionRecord(
            serverId = serverId,
            sessionKey = key.sessionKey,
            sessionId = snapshot.sessionId,
            userId = userId,
            media = snapshot.media.toMetadataRecord(fallbackDurationMs),
            viewOffsetMs = snapshot.viewOffsetMs,
            state = snapshot.state.name.lowercase(),
            streamTech = snapshot.technical.toStreamTechRecord(),
            player = snapshot.player.toPlayerRecord(),
            startedAtMs = startedAtMs,
            lastSeenAtMs = lastSeenAtMs,
            pausedDurationSec = pausedDurationSec,
            missedPolls = 0,
        )

    private fun StreamMedia.toMetadataRecord(fallbackDurationMs: Long): MediaMetadataRecord =
        MediaMetadataRecord(
            ratingKey = ratingKey,
            mediaType = mediaType.name.lowercase(),
            title = title,
            parentTitle = parentTitle,
            grandparentTitle = grandparentTitle,
            parentIndex = parentIndex,
            mediaIndex = index,
            year = year,
            thumbPath = thumbPath,
            librarySectionKey = librarySectionId,
            durationMs = durationMs?.takeIf { it > 0L } ?: fallbackDurationMs,
        )

    private fun StreamTechnicalInfo.toStreamTechRecord(): StreamTechRecord =
        StreamTechRecord(
            streamDecision = decision.name.lowercase(),
            videoDecision = videoDecision?.name?.lowercase(),
            audioDecision = audioDecision?.name?.lowercase(),
            hwTranscode = isHwTranscode,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            videoResolution = videoResolution,
            bandwidthKbps = bitrateKbps,
        )

    private fun StreamPlayer.toPlayerRecord(): PlayerRecord =
        PlayerRecord(
            playerName = title ?: device ?: product,
            playerPlatform = platform,
            playerProduct = product,
            playerMachineId = machineIdentifier,
            ipAddress = address,
            location = location.name.lowercase(),
        )

    companion object {
        const val DEFAULT_MISSED_POLLS_THRESHOLD = 2
    }
}
