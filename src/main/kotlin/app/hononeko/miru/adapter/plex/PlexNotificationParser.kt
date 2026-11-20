package app.hononeko.miru.adapter.plex

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * Parses raw Plex `NotificationContainer` text frames into typed [MediaServerEvent]s.
 *
 * Notification types Miru does not consume (status, progress, transcodeSession.*, ...) yield an empty list.
 */
object PlexNotificationParser {
    private val logger = LoggerFactory.getLogger(PlexNotificationParser::class.java)

    private const val PROVIDER = "plex"
    const val TYPE_PLAYING = "playing"
    const val TYPE_TIMELINE = "timeline"
    const val TYPE_ACTIVITY = "activity"

    fun parse(frame: String): Either<MiruError, List<MediaServerEvent>> {
        val envelope =
            try {
                MiruJson.decodeFromString<PlexNotificationEnvelope>(frame)
            } catch (e: SerializationException) {
                return malformed(e)
            } catch (e: IllegalArgumentException) {
                return malformed(e)
            }

        val container = envelope.container
        val events: List<MediaServerEvent> =
            when (container.type) {
                TYPE_PLAYING -> container.playing.orEmpty().mapNotNull(::mapPlaying)
                TYPE_TIMELINE -> container.timeline.orEmpty().map(::mapTimeline)
                TYPE_ACTIVITY -> container.activity.orEmpty().map(::mapActivity)
                else -> emptyList()
            }
        return events.right()
    }

    internal fun parseState(raw: String): PlaybackState? =
        when (raw.lowercase()) {
            "playing" -> PlaybackState.PLAYING
            "paused" -> PlaybackState.PAUSED
            "buffering" -> PlaybackState.BUFFERING
            "stopped" -> PlaybackState.STOPPED
            else -> null
        }

    private fun mapPlaying(dto: PlaySessionStateNotificationDto): MediaServerEvent.PlayingEvent? {
        val state = parseState(dto.state)
        if (state == null) {
            logger.debug("Ignoring playing notification with unknown state '{}'", dto.state)
            return null
        }
        return MediaServerEvent.PlayingEvent(
            sessionKey = dto.sessionKey,
            ratingKey = dto.ratingKey,
            state = state,
            viewOffsetMs = dto.viewOffset,
            clientIdentifier = dto.clientIdentifier,
        )
    }

    private fun mapTimeline(dto: TimelineEntryDto): MediaServerEvent.TimelineEvent =
        MediaServerEvent.TimelineEvent(
            itemId = dto.itemID,
            sectionId = dto.sectionID,
            mediaTypeCode = dto.type,
            title = dto.title,
            state = dto.state,
            metadataState = dto.metadataState,
        )

    private fun mapActivity(dto: ActivityNotificationDto): MediaServerEvent.ActivityEvent =
        MediaServerEvent.ActivityEvent(
            uuid = dto.uuid,
            event = dto.event,
            type = dto.activity?.type,
            title = dto.activity?.title,
            subtitle = dto.activity?.subtitle,
            progress = dto.activity?.progress,
        )

    private fun malformed(e: Exception): Either<MiruError, List<MediaServerEvent>> =
        MiruError
            .UpstreamUnavailable(
                provider = PROVIDER,
                message = "Malformed Plex notification frame",
                cause = e,
            ).left()
}
