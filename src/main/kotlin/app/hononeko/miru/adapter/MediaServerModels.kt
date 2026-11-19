package app.hononeko.miru.adapter

enum class MediaType {
    MOVIE,
    EPISODE,
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
    val videoResolution: String? = null,
    val bitrateKbps: Int? = null,
    val transcodeProgress: Double? = null,
    val transcodeSpeed: Double? = null,
)

data class ActiveStreamSnapshot(
    val sessionId: String,
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
)

data class LibraryItemsPage(
    val totalSize: Int,
    val offset: Int,
    val items: List<LibraryItemInfo>,
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
