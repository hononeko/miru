package app.hononeko.miru.http

import kotlinx.serialization.Serializable

@Serializable
data class ActiveStreamsResponse(
    val streams: List<ActiveStreamDto>,
    val totalCount: Int,
)

@Serializable
data class ActiveStreamDto(
    val serverId: String,
    val sessionKey: String,
    val sessionId: String? = null,
    val user: StreamUserDto,
    val media: StreamMediaDto,
    val player: StreamPlayerDto,
    val technical: StreamTechnicalDto,
    val state: String,
    val viewOffsetMs: Long,
    val progressPercent: Double,
    val startedAtMs: Long,
    val lastSeenAtMs: Long,
    val pausedDurationSec: Int,
)

@Serializable
data class StreamUserDto(
    val id: String,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class StreamMediaDto(
    val ratingKey: String,
    val mediaType: String,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentIndex: Int? = null,
    val mediaIndex: Int? = null,
    val year: Int? = null,
    val thumbUrl: String? = null,
    val librarySectionKey: String? = null,
    val durationMs: Long,
)

@Serializable
data class StreamPlayerDto(
    val name: String? = null,
    val platform: String? = null,
    val product: String? = null,
    val machineId: String? = null,
    val ipAddress: String? = null,
    val location: String? = null,
)

@Serializable
data class StreamTechnicalDto(
    val streamDecision: String,
    val videoDecision: String? = null,
    val audioDecision: String? = null,
    val hwTranscode: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val bandwidthKbps: Int? = null,
    val container: String? = null,
    val transcodeVideoCodec: String? = null,
    val transcodeAudioCodec: String? = null,
    val transcodeContainer: String? = null,
    val transcodeProgress: Double? = null,
    val transcodeSpeed: Double? = null,
    val throttled: Boolean = false,
)

@Serializable
data class SessionStartedEventDto(
    val timestampMs: Long,
    val stream: ActiveStreamDto,
)

@Serializable
data class SessionUpdatedEventDto(
    val timestampMs: Long,
    val stream: ActiveStreamDto,
    val previousState: String,
    val previousStreamDecision: String,
    val streamDecisionChanged: Boolean,
)

@Serializable
data class SessionStoppedEventDto(
    val timestampMs: Long,
    val serverId: String,
    val sessionKey: String,
    val ratingKey: String,
    val userId: String,
    val reason: String,
    val mergedIntoHistory: Boolean,
    val historyId: String? = null,
    val stream: ActiveStreamDto,
)

@Serializable
data class UsersListResponse(
    val users: List<UserSummaryDto>,
)

@Serializable
data class UserSummaryDto(
    val id: String,
    val serverId: String,
    val externalUserId: String,
    val username: String,
    val displayName: String? = null,
    val isAdmin: Boolean,
    val avatarUrl: String? = null,
    val isStreaming: Boolean,
    val activeStreamCount: Int,
    val lastSeenAtMs: Long? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
)

@Serializable
data class UserRecentPlayDto(
    val id: String,
    val serverId: String,
    val ratingKey: String,
    val mediaType: String,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentIndex: Int? = null,
    val mediaIndex: Int? = null,
    val year: Int? = null,
    val thumbUrl: String? = null,
    val librarySectionKey: String? = null,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val watchedDurationSec: Int,
    val pausedDurationSec: Int,
    val completionPercent: Double,
    val isWatched: Boolean,
    val streamDecision: String,
    val hwTranscode: Boolean,
    val videoResolution: String? = null,
    val playerName: String? = null,
    val playerPlatform: String? = null,
    val playerProduct: String? = null,
    val location: String? = null,
    val startedAtMs: Long,
    val stoppedAtMs: Long,
)

@Serializable
data class UserUsageStatDto(
    val name: String,
    val playCount: Long,
    val watchDurationSec: Long,
)

@Serializable
data class DecisionBreakdownDto(
    val directPlay: Long,
    val directStream: Long,
    val transcode: Long,
    val total: Long,
    val directPlayPercent: Double,
    val directStreamPercent: Double,
    val transcodePercent: Double,
)

@Serializable
data class LibraryBreakdownDto(
    val librarySectionKey: String? = null,
    val mediaType: String,
    val playCount: Long,
    val watchDurationSec: Long,
)

@Serializable
data class UserDetailResponse(
    val user: UserSummaryDto,
    val activeStreams: List<ActiveStreamDto>,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val recentPlays: List<UserRecentPlayDto>,
    val topClients: List<UserUsageStatDto>,
    val topPlatforms: List<UserUsageStatDto>,
    val decisionBreakdown: DecisionBreakdownDto,
    val libraryBreakdown: List<LibraryBreakdownDto>,
)
