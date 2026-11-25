package app.hononeko.miru.adapter

enum class MediaType {
    MOVIE,
    EPISODE,
    SEASON,
    SHOW,
    TRACK,
    CLIP,
    LIVE_TV,
    UNKNOWN,
}

enum class StreamDecision {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
}

enum class PlaybackState {
    PLAYING,
    PAUSED,
    BUFFERING,
    STOPPED,
}

enum class StreamLocation {
    LAN,
    WAN,
}

enum class LibrarySectionType {
    MOVIE,
    SHOW,
    ARTIST,
    PHOTO,
    OTHER,
}

data class ServerIdentity(
    val machineIdentifier: String,
    val friendlyName: String,
    val version: String,
    val platform: String? = null,
)

data class StreamMedia(
    val ratingKey: String,
    val mediaType: MediaType,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentIndex: Int? = null,
    val index: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val thumbPath: String? = null,
    val grandparentThumbPath: String? = null,
    val artPath: String? = null,
    val librarySectionId: String? = null,
)

data class StreamUser(
    val externalId: String,
    val username: String,
    val thumbUrl: String? = null,
)

data class StreamPlayer(
    val machineIdentifier: String,
    val product: String,
    val platform: String? = null,
    val device: String? = null,
    val title: String? = null,
    val address: String? = null,
    val location: StreamLocation,
    val isLocal: Boolean,
)

data class StreamTechnicalInfo(
    val decision: StreamDecision,
    val videoDecision: StreamDecision? = null,
    val audioDecision: StreamDecision? = null,
    val isHwTranscode: Boolean = false,
    val videoCodec: String? = null,
    val transcodeVideoCodec: String? = null,
    val audioCodec: String? = null,
    val transcodeAudioCodec: String? = null,
    val container: String? = null,
    val transcodeContainer: String? = null,
    val subtitleDecision: String? = null,
    val videoResolution: String? = null,
    val bitrateKbps: Int? = null,
    val transcodeProgress: Double? = null,
    val transcodeSpeed: Double? = null,
)

/**
 * A stream as currently listed by the media server.
 *
 * [sessionId] is the server's own ID of the playback session, the only one it accepts to terminate the stream.
 * It is null when the server does not report one; [sessionKey] is never a substitute for it.
 */
data class ActiveStreamSnapshot(
    val sessionId: String? = null,
    val sessionKey: String? = null,
    val state: PlaybackState,
    val viewOffsetMs: Long,
    val media: StreamMedia,
    val user: StreamUser,
    val player: StreamPlayer,
    val technical: StreamTechnicalInfo,
)

data class MediaUser(
    val externalId: String,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val thumbUrl: String? = null,
    val isAdmin: Boolean = false,
)

data class MediaUserLibraryAccess(
    val externalUserId: String,
    val allLibraries: Boolean,
    val sectionKeys: Set<String> = emptySet(),
)

data class LibrarySectionInfo(
    val sectionKey: String,
    val title: String,
    val type: LibrarySectionType,
    val count: Int? = null,
)

data class LibraryItemInfo(
    val ratingKey: String,
    val sectionKey: String,
    val mediaType: MediaType,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val year: Int? = null,
    val thumbPath: String? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null,
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val addedAtEpochSeconds: Long? = null,
    /** The show of an episode, or the artist of a track. */
    val grandparentRatingKey: String? = null,
)

data class LibraryItemsPage(
    val totalSize: Int,
    val offset: Int,
    val items: List<LibraryItemInfo>,
)

data class MediaTechnicalSpecs(
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val container: String? = null,
    val bitrateKbps: Int? = null,
    val fileSizeBytes: Long? = null,
    val subtitleLanguages: List<String> = emptyList(),
)

data class SeriesOverviewInfo(
    val ratingKey: String,
    val title: String,
    val year: Int? = null,
    val studio: String? = null,
    val contentRating: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val hasThumb: Boolean = false,
)

data class SeasonDetailsInfo(
    val ratingKey: String,
    val title: String,
    val seasonIndex: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val episodeCount: Int? = null,
    val currentEpisodeIndex: Int? = null,
    val hasThumb: Boolean = false,
)

data class MediaItemDetails(
    val ratingKey: String,
    val mediaType: MediaType,
    val title: String,
    val year: Int? = null,
    val originallyAvailableAt: String? = null,
    val durationMs: Long? = null,
    val contentRating: String? = null,
    val studio: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val librarySectionId: String? = null,
    val librarySectionTitle: String? = null,
    val addedAtEpochSeconds: Long? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentIndex: Int? = null,
    val index: Int? = null,
    val childCount: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val thumbPath: String? = null,
    val artPath: String? = null,
    val technical: MediaTechnicalSpecs? = null,
    val series: SeriesOverviewInfo? = null,
    val season: SeasonDetailsInfo? = null,
)

data class ArtworkData(
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArtworkData) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()

    override fun toString(): String = "ArtworkData(size=${bytes.size}, contentType=$contentType)"
}

sealed interface MediaServerEvent {
    data class PlayingEvent(
        val sessionKey: String,
        val ratingKey: String? = null,
        val state: PlaybackState,
        val viewOffsetMs: Long? = null,
        val clientIdentifier: String? = null,
    ) : MediaServerEvent

    data class TimelineEvent(
        val itemId: String,
        val sectionId: String? = null,
        val mediaTypeCode: Int? = null,
        val title: String? = null,
        val state: Int? = null,
        val metadataState: String? = null,
    ) : MediaServerEvent

    data class ActivityEvent(
        val uuid: String,
        val event: String,
        val type: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val progress: Double? = null,
    ) : MediaServerEvent
}
