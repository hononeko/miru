package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory

sealed interface JellyfinWsParseResult {
    data object KeepAliveRequested : JellyfinWsParseResult

    data object KeepAliveAck : JellyfinWsParseResult

    data class SessionsUpdate(
        val sessions: List<JellyfinSessionDto>,
    ) : JellyfinWsParseResult

    data class LibraryUpdate(
        val events: List<MediaServerEvent.TimelineEvent>,
    ) : JellyfinWsParseResult

    data object Ignored : JellyfinWsParseResult
}

/**
 * Parses raw Jellyfin WebSocket frames into typed notifications.
 */
object JellyfinNotificationParser {
    private val logger = LoggerFactory.getLogger(JellyfinNotificationParser::class.java)

    private const val PROVIDER = "jellyfin"
    const val TYPE_SESSIONS = "Sessions"
    const val TYPE_FORCE_KEEP_ALIVE = "ForceKeepAlive"
    const val TYPE_KEEP_ALIVE = "KeepAlive"
    const val TYPE_LIBRARY_CHANGED = "LibraryChanged"

    fun parse(frame: String): Either<MiruError, JellyfinWsParseResult> {
        val envelope =
            try {
                MiruJson.decodeFromString<JellyfinWsInboundFrame>(frame)
            } catch (e: SerializationException) {
                return malformed(e)
            } catch (e: IllegalArgumentException) {
                return malformed(e)
            }

        return when (envelope.messageType) {
            TYPE_FORCE_KEEP_ALIVE -> JellyfinWsParseResult.KeepAliveRequested.right()
            TYPE_KEEP_ALIVE -> JellyfinWsParseResult.KeepAliveAck.right()
            TYPE_SESSIONS -> parseSessions(envelope)
            TYPE_LIBRARY_CHANGED -> parseLibraryChanged(envelope)
            else -> JellyfinWsParseResult.Ignored.right()
        }
    }

    fun toPlayingEvents(
        sessions: List<JellyfinSessionDto>,
        previouslyActiveSessions: MutableMap<String, String?>,
    ): List<MediaServerEvent.PlayingEvent> {
        val events = mutableListOf<MediaServerEvent.PlayingEvent>()
        val currentSessionKeys = linkedSetOf<String>()

        for (session in sessions) {
            val sessionKey = session.id?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val item = session.nowPlayingItem ?: continue
            val ratingKey = item.id?.trim()?.takeIf { it.isNotEmpty() }
            currentSessionKeys += sessionKey
            previouslyActiveSessions[sessionKey] = ratingKey
            val state =
                if (session.playState?.isPaused == true) {
                    PlaybackState.PAUSED
                } else {
                    PlaybackState.PLAYING
                }
            events +=
                MediaServerEvent.PlayingEvent(
                    sessionKey = sessionKey,
                    ratingKey = ratingKey,
                    state = state,
                    viewOffsetMs = JellyfinMapper.ticksToMs(session.playState?.positionTicks),
                    clientIdentifier = session.deviceId?.trim()?.takeIf { it.isNotEmpty() },
                )
        }

        val stoppedKeys = previouslyActiveSessions.keys.filter { it !in currentSessionKeys }
        for (stoppedKey in stoppedKeys) {
            val lastRatingKey = previouslyActiveSessions.remove(stoppedKey)
            events +=
                MediaServerEvent.PlayingEvent(
                    sessionKey = stoppedKey,
                    ratingKey = lastRatingKey,
                    state = PlaybackState.STOPPED,
                )
        }

        return events
    }

    private fun parseSessions(envelope: JellyfinWsInboundFrame): Either<MiruError, JellyfinWsParseResult> {
        val data = envelope.data ?: return JellyfinWsParseResult.SessionsUpdate(emptyList()).right()
        return try {
            val sessions = MiruJson.decodeFromJsonElement<List<JellyfinSessionDto>>(data)
            JellyfinWsParseResult.SessionsUpdate(sessions).right()
        } catch (e: SerializationException) {
            logger.debug("Malformed Jellyfin Sessions payload: {}", e.message)
            malformed(e)
        } catch (e: IllegalArgumentException) {
            logger.debug("Invalid Jellyfin Sessions payload: {}", e.message)
            malformed(e)
        }
    }

    private fun parseLibraryChanged(envelope: JellyfinWsInboundFrame): Either<MiruError, JellyfinWsParseResult> {
        val data = envelope.data ?: return JellyfinWsParseResult.Ignored.right()
        return try {
            val changed = MiruJson.decodeFromJsonElement<JellyfinLibraryChangedDto>(data)
            val sectionIds =
                (
                    changed.collectionFolders.orEmpty() +
                        changed.foldersAddedTo.orEmpty() +
                        changed.foldersRemovedFrom.orEmpty() +
                        changed.foldersUpdated.orEmpty()
                ).map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            val itemIds =
                (changed.itemsAdded.orEmpty() + changed.itemsUpdated.orEmpty() + changed.itemsRemoved.orEmpty())
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val events =
                if (sectionIds.isNotEmpty()) {
                    sectionIds.map { sectionId ->
                        MediaServerEvent.TimelineEvent(
                            itemId = itemIds.firstOrNull() ?: sectionId,
                            sectionId = sectionId,
                        )
                    }
                } else {
                    itemIds.map { itemId ->
                        MediaServerEvent.TimelineEvent(itemId = itemId, sectionId = null)
                    }
                }
            JellyfinWsParseResult.LibraryUpdate(events).right()
        } catch (e: SerializationException) {
            logger.debug("Malformed Jellyfin LibraryChanged payload: {}", e.message)
            JellyfinWsParseResult.Ignored.right()
        } catch (e: IllegalArgumentException) {
            logger.debug("Invalid Jellyfin LibraryChanged payload: {}", e.message)
            JellyfinWsParseResult.Ignored.right()
        }
    }

    private fun malformed(e: Exception): Either<MiruError, JellyfinWsParseResult> =
        MiruError
            .UpstreamUnavailable(
                provider = PROVIDER,
                message = "Malformed Jellyfin WebSocket frame",
                cause = e,
            ).left()
}
