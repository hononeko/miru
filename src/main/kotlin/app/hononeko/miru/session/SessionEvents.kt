package app.hononeko.miru.session

import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord

/** Why an active playback session was finalized. */
enum class StopReason {
    EXPLICIT_STOP,
    VANISHED,
    CRASH_RECOVERY,
}

/** Domain events emitted by [SessionStateEngine] as active playback sessions start, update, and stop. */
sealed interface SessionEvent {
    val serverId: String
    val key: ActiveSessionKey
    val timestampMs: Long

    data class SessionStarted(
        override val serverId: String,
        override val key: ActiveSessionKey,
        override val timestampMs: Long,
        val session: ActiveSessionRecord,
    ) : SessionEvent

    data class SessionUpdated(
        override val serverId: String,
        override val key: ActiveSessionKey,
        override val timestampMs: Long,
        val session: ActiveSessionRecord,
        val previousState: String,
        val previousStreamDecision: String,
        val streamDecisionChanged: Boolean,
    ) : SessionEvent

    data class SessionStopped(
        override val serverId: String,
        override val key: ActiveSessionKey,
        override val timestampMs: Long,
        val session: ActiveSessionRecord,
        val historyRecord: PlaybackHistoryRecord?,
        val mergedIntoHistory: Boolean,
        val reason: StopReason,
    ) : SessionEvent
}
