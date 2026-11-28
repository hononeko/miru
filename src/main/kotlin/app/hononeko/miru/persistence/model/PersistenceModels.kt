package app.hononeko.miru.persistence.model

data class MediaServerRecord(
    val id: String,
    val name: String,
    val serverType: String,
    val machineId: String,
    val baseUrl: String,
    val encryptedToken: String? = null,
    val clientIdentifier: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    override fun toString(): String =
        "MediaServerRecord(id=$id, name=$name, serverType=$serverType, machineId=$machineId, baseUrl=$baseUrl, " +
            "encryptedToken=${if (encryptedToken != null) "***" else "null"}, clientIdentifier=$clientIdentifier, " +
            "createdAtMs=$createdAtMs, updatedAtMs=$updatedAtMs)"
}

/**
 * Selects the active stored credential row for [serverType], preferring the most recently updated
 * row with a non-blank [MediaServerRecord.encryptedToken] when multiple historical rows exist.
 */
fun Iterable<MediaServerRecord>.findActiveStoredServer(serverType: String): MediaServerRecord? =
    filter { it.serverType.equals(serverType, ignoreCase = true) && !it.encryptedToken.isNullOrBlank() }
        .maxWithOrNull(
            compareBy<MediaServerRecord> { it.updatedAtMs }
                .thenBy { it.createdAtMs }
                .thenBy { it.id },
        )

data class UserRecord(
    val id: String,
    val serverId: String,
    val externalUserId: String,
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val thumbUrl: String? = null,
    val isAdmin: Boolean = false,
    val lastSeenAtMs: Long? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class MediaMetadataRecord(
    val ratingKey: String,
    val mediaType: String,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentIndex: Int? = null,
    val mediaIndex: Int? = null,
    val year: Int? = null,
    val thumbPath: String? = null,
    val librarySectionKey: String? = null,
    val durationMs: Long,
)

data class StreamTechRecord(
    val streamDecision: String,
    val videoDecision: String? = null,
    val audioDecision: String? = null,
    val hwTranscode: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val bandwidthKbps: Int? = null,
    /** Video codec the server transcoded to; null when video was not transcoded or not reported. */
    val transcodeVideoCodec: String? = null,
    /** Audio codec the server transcoded to; null when audio was not transcoded or not reported. */
    val transcodeAudioCodec: String? = null,
    /** The file's container; null when unknown. */
    val container: String? = null,
    /** Container the server streamed in when it remuxed or transcoded. */
    val transcodeContainer: String? = null,
    /** Subtitle handling reported by the server (`burn`, `copy`, `transcode`). */
    val subtitleDecision: String? = null,
)

data class PlayerRecord(
    val playerName: String? = null,
    val playerPlatform: String? = null,
    val playerProduct: String? = null,
    val playerMachineId: String? = null,
    val ipAddress: String? = null,
    val location: String? = null,
)

data class ActiveSessionKey(
    val serverId: String,
    val sessionKey: String,
    val ratingKey: String,
)

data class ActiveSessionRecord(
    val serverId: String,
    val sessionKey: String,
    val sessionId: String? = null,
    val userId: String,
    val media: MediaMetadataRecord,
    val viewOffsetMs: Long,
    val state: String,
    val streamTech: StreamTechRecord,
    val player: PlayerRecord,
    val startedAtMs: Long,
    val lastSeenAtMs: Long,
    val pausedDurationSec: Int = 0,
    val missedPolls: Int = 0,
) {
    val key: ActiveSessionKey
        get() = ActiveSessionKey(serverId = serverId, sessionKey = sessionKey, ratingKey = media.ratingKey)
}

data class PlaybackHistoryRecord(
    val id: String,
    val serverId: String,
    val userId: String,
    val externalImportKey: String? = null,
    val media: MediaMetadataRecord,
    val viewOffsetMs: Long,
    val watchedDurationSec: Int,
    val pausedDurationSec: Int = 0,
    val completionPercent: Double,
    val isWatched: Boolean,
    val streamTech: StreamTechRecord,
    val player: PlayerRecord,
    val startedAtMs: Long,
    val stoppedAtMs: Long,
)

enum class HistorySortField {
    STOPPED_AT,
    STARTED_AT,
    WATCHED_DURATION,
    COMPLETION_PERCENT,
    TITLE,
}

enum class HistorySortDirection {
    ASC,
    DESC,
}

data class HistoryFilter(
    val serverId: String? = null,
    val userId: String? = null,
    val userIds: Set<String>? = null,
    val mediaType: String? = null,
    val librarySectionKey: String? = null,
    val streamDecision: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val q: String? = null,
    val sortBy: HistorySortField = HistorySortField.STOPPED_AT,
    val sortDir: HistorySortDirection = HistorySortDirection.DESC,
    val limit: Int = 50,
    val offset: Int = 0,
    val ratingKeys: Set<String>? = null,
    val librarySectionKeys: Set<String>? = null,
    val mediaTypes: Set<String>? = null,
    /** Plays started at or after this time. */
    val startedFromMs: Long? = null,
    /** Plays started before this time. */
    val startedBeforeMs: Long? = null,
)

data class HistoryPage(
    val items: List<PlaybackHistoryRecord>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)

data class UserPlaybackSummary(
    val userId: String,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
)

/**
 * The plays of a user that share one combination of stored values, as recorded and before any labelling, so
 * callers can group them the way they need without loading the plays themselves.
 */
data class UserPlaybackBreakdownRow(
    /** The player product, or the player name when the product is unknown. */
    val clientName: String?,
    val playerPlatform: String?,
    val streamDecision: String,
    val hwTranscode: Boolean,
    val librarySectionKey: String?,
    val playCount: Long,
    val watchDurationSec: Long,
)

data class MediaPlaySummary(
    val totalPlays: Long,
    val uniqueUsers: Int,
    val lastWatchedAtMs: Long? = null,
    val lastWatchedUserId: String? = null,
    val lastWatchedUsername: String? = null,
    val avgCompletionPercent: Double? = null,
    val directPlayCount: Long,
    val directStreamCount: Long,
    val transcodeCount: Long,
    val totalWatchDurationSec: Long,
) {
    companion object {
        val EMPTY =
            MediaPlaySummary(
                totalPlays = 0L,
                uniqueUsers = 0,
                lastWatchedAtMs = null,
                lastWatchedUserId = null,
                lastWatchedUsername = null,
                avgCompletionPercent = null,
                directPlayCount = 0L,
                directStreamCount = 0L,
                transcodeCount = 0L,
                totalWatchDurationSec = 0L,
            )
    }
}

data class PlaybackHistoryStatsRow(
    val userId: String,
    val mediaType: String,
    val ratingKey: String,
    val title: String,
    val parentTitle: String?,
    val grandparentTitle: String?,
    val thumbPath: String?,
    val librarySectionKey: String?,
    val watchedDurationSec: Int,
    val streamDecision: String,
    val hwTranscode: Boolean,
    val playerPlatform: String?,
    val playerProduct: String?,
    val stoppedAtMs: Long,
    val completionPercent: Double = 0.0,
    val isWatched: Boolean = false,
    val videoDecision: String? = null,
    val audioDecision: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val playerName: String? = null,
    val startedAtMs: Long = 0L,
    val transcodeVideoCodec: String? = null,
    val transcodeAudioCodec: String? = null,
    val container: String? = null,
    val transcodeContainer: String? = null,
    val subtitleDecision: String? = null,
)

data class OverviewStatsRow(
    val userId: String,
    val mediaType: String,
    val ratingKey: String,
    val title: String,
    val grandparentTitle: String?,
    val thumbPath: String?,
    val watchedDurationSec: Int,
    val streamDecision: String,
    val hwTranscode: Boolean,
    val stoppedAtMs: Long,
)

/** A play with the library item it belongs to, as far as the library cache knows it. */
data class MediaPlayRow(
    val userId: String,
    val mediaType: String,
    val ratingKey: String,
    val title: String,
    val grandparentTitle: String?,
    val year: Int?,
    val thumbPath: String?,
    val sectionKey: String?,
    val grandparentRatingKey: String?,
    val watchedDurationSec: Int,
    val startedAtMs: Long,
)

data class ClientStatsRow(
    val watchedDurationSec: Int,
    val streamDecision: String,
    val videoDecision: String?,
    val audioDecision: String?,
    val subtitleDecision: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val transcodeVideoCodec: String?,
    val transcodeAudioCodec: String?,
    val container: String?,
    val transcodeContainer: String?,
    val playerProduct: String?,
    val playerName: String?,
    val playerPlatform: String?,
)

enum class StaleMediaSort {
    SIZE_DESC,
    SIZE_ASC,
    AGE_DESC,
    AGE_ASC,
}

data class StaleMediaItemRecord(
    val item: MediaItemCacheRecord,
    val playCount: Long,
    val lastPlayedAtMs: Long?,
)

data class StaleMediaReportRecord(
    val items: List<StaleMediaItemRecord>,
    val totalStaleCount: Int,
    val totalReclaimableBytes: Long,
    val neverPlayedCount: Int,
)

data class LibrarySectionRecord(
    val serverId: String,
    val sectionKey: String,
    val title: String,
    val sectionType: String,
    val itemCount: Int = 0,
    val childCount: Int = 0,
    val leafCount: Int = 0,
    val totalSizeBytes: Long = 0,
    val totalDurationMs: Long = 0,
    val lastSyncedAtMs: Long? = null,
    val updatedAtMs: Long,
)

data class LibrarySnapshotRecord(
    val id: String,
    val serverId: String,
    val sectionKey: String,
    val capturedAtMs: Long,
    val itemCount: Int,
    val childCount: Int,
    val leafCount: Int,
    val totalSizeBytes: Long,
    val totalDurationMs: Long,
    val aggregatesJson: String? = null,
)

/** Items added to and removed from a library section during one UTC day. */
data class LibraryGrowthDayRecord(
    val epochDay: Long,
    val added: Long,
    val removed: Long,
    val sizeDeltaBytes: Long,
)

data class MediaItemTechRecord(
    val fileSizeBytes: Long? = null,
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
)

data class MediaItemCacheRecord(
    val serverId: String,
    val ratingKey: String,
    val sectionKey: String,
    val mediaType: String,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val year: Int? = null,
    val thumbPath: String? = null,
    val durationMs: Long? = null,
    val tech: MediaItemTechRecord = MediaItemTechRecord(),
    val addedAtMs: Long,
    val updatedAtMs: Long,
    /** The show of an episode, or the artist of a track. Null until a deep scan has seen the item. */
    val grandparentRatingKey: String? = null,
)

/** A show or artist that has items in the library cache. */
data class GrandparentItemRecord(
    val sectionKey: String,
    val title: String,
    val ratingKey: String,
)

data class UserLibraryAccessRecord(
    val serverId: String,
    val externalUserId: String,
    val allLibraries: Boolean = false,
    val sectionKeys: Set<String> = emptySet(),
    val updatedAtMs: Long,
)

data class ResolvedLibraryAccess(
    val allLibrariesServerIds: Set<String> = emptySet(),
    val allowedSections: Set<Pair<String, String>> = emptySet(),
) {
    fun canAccess(
        serverId: String,
        sectionKey: String,
    ): Boolean = serverId in allLibrariesServerIds || (serverId to sectionKey) in allowedSections

    companion object {
        val EMPTY = ResolvedLibraryAccess()
    }
}
