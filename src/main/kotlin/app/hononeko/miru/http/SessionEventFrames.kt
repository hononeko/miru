package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.SessionEvent
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

// Matches the session event flows upstream: a slow SSE client skips its oldest frames rather than stalling the rest.
private const val FRAME_BUFFER_CAPACITY = 256

private val logger = LoggerFactory.getLogger(SessionEventFrames::class.java)

/**
 * A session event enriched and encoded once, shared by every SSE subscriber. [event] is the same for every viewer
 * (stream DTOs carry no per-principal fields); only [isVisibleTo] differs, and each subscriber checks it before
 * sending.
 */
internal class SessionEventFrame(
    val serverId: String,
    val userId: String,
    val event: ServerSentEvent,
) {
    /** The filter each subscriber applied to raw events before frames were shared. */
    fun isVisibleTo(
        principal: MiruPrincipal,
        serverId: String?,
    ): Boolean = principal.canAccessMediaUser(userId) && (serverId == null || this.serverId == serverId)
}

/**
 * Turns [sessionEvents] into [SessionEventFrame]s while at least one SSE client listens, so each event costs one
 * set of lookups and one JSON encoding however many clients are connected.
 */
internal class SessionEventFrames(
    sessionEvents: SharedFlow<SessionEvent>,
    private val streamsAndUsersService: StreamsAndUsersService,
    scope: CoroutineScope,
) {
    val frames: SharedFlow<SessionEventFrame> =
        sessionEvents
            .mapNotNull { event -> buildFrame(event) }
            .buffer(FRAME_BUFFER_CAPACITY, BufferOverflow.DROP_OLDEST)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    // One bad event must not end the shared collector, which would silence every connected client.
    private suspend fun buildFrame(event: SessionEvent): SessionEventFrame? =
        try {
            buildSessionEventFrame(event, streamsAndUsersService)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to build SSE frame for session event '{}': {}", event.key, e.message)
            null
        }
}

private suspend fun buildSessionEventFrame(
    event: SessionEvent,
    streamsAndUsersService: StreamsAndUsersService,
): SessionEventFrame? {
    val session =
        when (event) {
            is SessionEvent.SessionStarted -> event.session
            is SessionEvent.SessionUpdated -> event.session
            is SessionEvent.SessionStopped -> event.session
        }
    val streamDto =
        streamsAndUsersService.toActiveStreamDto(session).fold(
            ifLeft = { error ->
                logger.warn("Failed to map SSE session event '{}': {}", event.key, error.message)
                return null
            },
            ifRight = { it },
        )
    val sseEvent =
        when (event) {
            is SessionEvent.SessionStarted ->
                ServerSentEvent(
                    event = "session_started",
                    data =
                        MiruJson.encodeToString(
                            SessionStartedEventDto(
                                timestampMs = event.timestampMs,
                                stream = streamDto,
                            ),
                        ),
                )

            is SessionEvent.SessionUpdated ->
                ServerSentEvent(
                    event = "session_updated",
                    data =
                        MiruJson.encodeToString(
                            SessionUpdatedEventDto(
                                timestampMs = event.timestampMs,
                                stream = streamDto,
                                previousState = event.previousState,
                                previousStreamDecision = event.previousStreamDecision,
                                streamDecisionChanged = event.streamDecisionChanged,
                            ),
                        ),
                )

            is SessionEvent.SessionStopped ->
                ServerSentEvent(
                    event = "session_stopped",
                    data =
                        MiruJson.encodeToString(
                            SessionStoppedEventDto(
                                timestampMs = event.timestampMs,
                                serverId = event.serverId,
                                sessionKey = event.key.sessionKey,
                                ratingKey = event.key.ratingKey,
                                userId = event.session.userId,
                                reason = event.reason.name.lowercase(),
                                mergedIntoHistory = event.mergedIntoHistory,
                                historyId = event.historyRecord?.id,
                                stream = streamDto,
                            ),
                        ),
                )
        }
    return SessionEventFrame(serverId = event.serverId, userId = session.userId, event = sseEvent)
}
