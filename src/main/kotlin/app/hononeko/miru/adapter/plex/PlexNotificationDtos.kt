package app.hononeko.miru.adapter.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for frames received on Plex's `/:/websockets/notifications` endpoint.
 */
@Serializable
data class PlexNotificationEnvelope(
    @SerialName("NotificationContainer")
    val container: PlexNotificationContainer,
)

@Serializable
data class PlexNotificationContainer(
    val type: String,
    val size: Int? = null,
    @SerialName("PlaySessionStateNotification")
    val playing: List<PlaySessionStateNotificationDto>? = null,
    @SerialName("TimelineEntry")
    val timeline: List<TimelineEntryDto>? = null,
    @SerialName("ActivityNotification")
    val activity: List<ActivityNotificationDto>? = null,
)

@Serializable
data class PlaySessionStateNotificationDto(
    val sessionKey: FlexibleString,
    val clientIdentifier: String? = null,
    val guid: String? = null,
    val ratingKey: FlexibleString? = null,
    val url: String? = null,
    val key: String? = null,
    val viewOffset: Long? = null,
    val playQueueItemID: FlexibleString? = null,
    val state: String,
)

@Serializable
data class TimelineEntryDto(
    val identifier: String? = null,
    val sectionID: FlexibleString? = null,
    val itemID: FlexibleString,
    val type: Int? = null,
    val title: String? = null,
    val state: Int? = null,
    val metadataState: String? = null,
    val mediaState: String? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class ActivityNotificationDto(
    val event: String,
    val uuid: String,
    @SerialName("Activity")
    val activity: ActivityDto? = null,
)

@Serializable
data class ActivityDto(
    val uuid: String? = null,
    val type: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val progress: Double? = null,
)
