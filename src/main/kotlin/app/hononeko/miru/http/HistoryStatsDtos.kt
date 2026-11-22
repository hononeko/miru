package app.hononeko.miru.http

import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import kotlinx.serialization.Serializable

internal fun buildThumbUrl(
    ratingKey: String,
    thumbPath: String?,
): String? =
    thumbPath
        ?.takeIf { it.isNotBlank() }
        ?.let { "/api/v1/media/$ratingKey/thumb" }

@Serializable
data class HistoryItemDto(
    val id: String,
    val serverId: String,
    val userId: String,
    val username: String? = null,
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
    val startedAtMs: Long,
    val stoppedAtMs: Long,
)

@Serializable
data class HistoryListResponse(
    val items: List<HistoryItemDto>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Long,
    val totalPages: Int,
)

@Serializable
data class HistoryMediaDto(
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
data class HistoryStreamTechDto(
    val streamDecision: String,
    val videoDecision: String? = null,
    val audioDecision: String? = null,
    val hwTranscode: Boolean,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoResolution: String? = null,
    val bandwidthKbps: Int? = null,
)

@Serializable
data class HistoryPlayerDto(
    val playerName: String? = null,
    val playerPlatform: String? = null,
    val playerProduct: String? = null,
    val playerMachineId: String? = null,
    val ipAddress: String? = null,
    val location: String? = null,
)

@Serializable
data class HistoryDetailResponse(
    val id: String,
    val serverId: String,
    val userId: String,
    val username: String? = null,
    val externalImportKey: String? = null,
    val media: HistoryMediaDto,
    val viewOffsetMs: Long,
    val watchedDurationSec: Int,
    val pausedDurationSec: Int,
    val completionPercent: Double,
    val isWatched: Boolean,
    val streamTech: HistoryStreamTechDto,
    val player: HistoryPlayerDto,
    val startedAtMs: Long,
    val stoppedAtMs: Long,
)

@Serializable
data class DailyPlayStatDto(
    val date: String,
    val playCount: Long,
    val watchDurationSec: Long,
)

@Serializable
data class TopMediaStatDto(
    val ratingKey: String,
    val title: String,
    val playCount: Long,
    val watchDurationSec: Long,
    val thumbUrl: String? = null,
)

@Serializable
data class DecisionBreakdownStatDto(
    val directPlay: Long,
    val directStream: Long,
    val transcode: Long,
    val hwTranscode: Long,
    val directPlayPercent: Double,
    val directStreamPercent: Double,
    val transcodePercent: Double,
    val hwTranscodePercent: Double,
)

@Serializable
data class PlatformStatDto(
    val platform: String,
    val playCount: Long,
    val watchDurationSec: Long,
    val percent: Double,
)

@Serializable
data class LibraryBreakdownStatDto(
    val librarySectionKey: String,
    val playCount: Long,
    val watchDurationSec: Long,
    val percent: Double,
)

@Serializable
data class OverviewStatsResponse(
    val range: String,
    val fromMs: Long? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val activeUsers: Int,
    val playsPerDay: List<DailyPlayStatDto>,
    val topMovies: List<TopMediaStatDto>,
    val topShows: List<TopMediaStatDto>,
    val decisionBreakdown: DecisionBreakdownStatDto,
)

@Serializable
data class UserStatsResponse(
    val userId: String,
    val username: String,
    val displayName: String? = null,
    val range: String,
    val fromMs: Long? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val playsPerDay: List<DailyPlayStatDto>,
    val topMovies: List<TopMediaStatDto>,
    val topShows: List<TopMediaStatDto>,
    val decisionBreakdown: DecisionBreakdownStatDto,
    val topPlatforms: List<PlatformStatDto>,
    val libraryBreakdown: List<LibraryBreakdownStatDto>,
)

internal fun PlaybackHistoryRecord.toHistoryItemDto(username: String?): HistoryItemDto =
    HistoryItemDto(
        id = id,
        serverId = serverId,
        userId = userId,
        username = username,
        ratingKey = media.ratingKey,
        mediaType = media.mediaType,
        title = media.title,
        parentTitle = media.parentTitle,
        grandparentTitle = media.grandparentTitle,
        parentIndex = media.parentIndex,
        mediaIndex = media.mediaIndex,
        year = media.year,
        thumbUrl = buildThumbUrl(media.ratingKey, media.thumbPath),
        librarySectionKey = media.librarySectionKey,
        durationMs = media.durationMs,
        viewOffsetMs = viewOffsetMs,
        watchedDurationSec = watchedDurationSec,
        pausedDurationSec = pausedDurationSec,
        completionPercent = completionPercent,
        isWatched = isWatched,
        streamDecision = streamTech.streamDecision,
        hwTranscode = streamTech.hwTranscode,
        videoResolution = streamTech.videoResolution,
        playerName = player.playerName,
        playerPlatform = player.playerPlatform,
        playerProduct = player.playerProduct,
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
    )

internal fun PlaybackHistoryRecord.toHistoryDetailResponse(username: String?): HistoryDetailResponse =
    HistoryDetailResponse(
        id = id,
        serverId = serverId,
        userId = userId,
        username = username,
        externalImportKey = externalImportKey,
        media =
            HistoryMediaDto(
                ratingKey = media.ratingKey,
                mediaType = media.mediaType,
                title = media.title,
                parentTitle = media.parentTitle,
                grandparentTitle = media.grandparentTitle,
                parentIndex = media.parentIndex,
                mediaIndex = media.mediaIndex,
                year = media.year,
                thumbUrl = buildThumbUrl(media.ratingKey, media.thumbPath),
                librarySectionKey = media.librarySectionKey,
                durationMs = media.durationMs,
            ),
        viewOffsetMs = viewOffsetMs,
        watchedDurationSec = watchedDurationSec,
        pausedDurationSec = pausedDurationSec,
        completionPercent = completionPercent,
        isWatched = isWatched,
        streamTech =
            HistoryStreamTechDto(
                streamDecision = streamTech.streamDecision,
                videoDecision = streamTech.videoDecision,
                audioDecision = streamTech.audioDecision,
                hwTranscode = streamTech.hwTranscode,
                videoCodec = streamTech.videoCodec,
                audioCodec = streamTech.audioCodec,
                videoResolution = streamTech.videoResolution,
                bandwidthKbps = streamTech.bandwidthKbps,
            ),
        player =
            HistoryPlayerDto(
                playerName = player.playerName,
                playerPlatform = player.playerPlatform,
                playerProduct = player.playerProduct,
                playerMachineId = player.playerMachineId,
                ipAddress = player.ipAddress,
                location = player.location,
            ),
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
    )
