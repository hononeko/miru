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
)
