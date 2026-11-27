package app.hononeko.miru.http

import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.UserRecord
import kotlinx.serialization.Serializable

private val LIBRARY_ARTWORK_PATH =
    Regex("^/library/metadata/([A-Za-z0-9_-]+)/(thumb|art)(?:/[^/?#]+)?/?(?:\\?.*)?$")
private val JELLYFIN_ARTWORK_PATH =
    Regex("^/Items/([A-Za-z0-9_-]+)/Images/(Primary|Backdrop)(?:/[^/?#]+)?/?(?:\\?.*)?$")

/**
 * Proxy URL for a media item's artwork (`thumb` or `art`). Media servers often point child items
 * (episodes, seasons, music tracks) at their parent's artwork path (`/library/metadata/<parentKey>/<kind>/...`
 * for Plex or `/Items/<parentKey>/Images/(Primary|Backdrop)` for Jellyfin), so the rating key comes
 * from the path when it names one for the requested kind.
 */
internal fun buildMediaArtworkUrl(
    ratingKey: String,
    artworkPath: String?,
    kind: String,
): String? {
    val path = artworkPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val plexMatch = LIBRARY_ARTWORK_PATH.matchEntire(path)
    val targetKey =
        if (plexMatch != null && plexMatch.groupValues[2] == kind) {
            plexMatch.groupValues[1]
        } else {
            val jfMatch = JELLYFIN_ARTWORK_PATH.matchEntire(path)
            val jfKind = jfMatch?.groupValues?.getOrNull(2)?.let { if (it == "Primary") "thumb" else "art" }
            if (jfMatch != null && jfKind == kind) {
                jfMatch.groupValues[1]
            } else {
                ratingKey
            }
        }
    return "/api/v1/media/$targetKey/$kind"
}

internal fun buildThumbUrl(
    ratingKey: String,
    thumbPath: String?,
): String? = buildMediaArtworkUrl(ratingKey = ratingKey, artworkPath = thumbPath, kind = "thumb")

internal fun String?.normalizedDisplayName(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

@Serializable
data class HistoryItemDto(
    val id: String,
    val serverId: String,
    val serverName: String? = null,
    val serverType: String? = null,
    val userId: String,
    val username: String? = null,
    /** The user's name from the media server (e.g. a Plex friend's first name), when it has one. */
    val displayName: String? = null,
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
    val transcodeVideoCodec: String? = null,
    val transcodeAudioCodec: String? = null,
    val container: String? = null,
    val transcodeContainer: String? = null,
    val subtitleDecision: String? = null,
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
    val serverName: String? = null,
    val serverType: String? = null,
    val userId: String,
    val username: String? = null,
    /** The user's name from the media server (e.g. a Plex friend's first name), when it has one. */
    val displayName: String? = null,
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
data class DailyMediaPlayDto(
    val ratingKey: String,
    val mediaType: String,
    val title: String,
    val playCount: Long,
    val watchDurationSec: Long,
    val users: List<String> = emptyList(),
)

@Serializable
data class DailyUserPlayDto(
    val userId: String,
    val username: String,
    val displayName: String? = null,
    val playCount: Long,
    val watchDurationSec: Long,
)

@Serializable
data class DailyPlayStatDto(
    val date: String,
    val playCount: Long,
    val watchDurationSec: Long,
    val media: List<DailyMediaPlayDto> = emptyList(),
    val users: List<DailyUserPlayDto> = emptyList(),
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
data class DecisionBreakdownDto(
    val directPlay: Long,
    val directStream: Long,
    val transcode: Long,
    val hwTranscode: Long,
    val total: Long,
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
data class LibraryBreakdownDto(
    val librarySectionKey: String? = null,
    val playCount: Long,
    val watchDurationSec: Long,
    val percent: Double,
)

@Serializable
data class RewatchedMediaStatDto(
    val ratingKey: String,
    val mediaType: String,
    val title: String,
    val grandparentTitle: String? = null,
    val playCount: Long,
    val watchDurationSec: Long,
    val thumbUrl: String? = null,
)

@Serializable
data class HeatmapCellDto(
    /** UTC calendar date, ISO-8601. */
    val date: String,
    val dayOfWeek: Int,
    val dayName: String,
    val hour: Int,
    val playCount: Long,
    val watchDurationSec: Long,
)

@Serializable
data class HeatmapStatsResponse(
    val range: String,
    val fromMs: Long? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val maxCellPlays: Long,
    val maxCellWatchDurationSec: Long,
    val peakDate: String? = null,
    val peakDayName: String? = null,
    val peakHour: Int? = null,
    /** Every hour of every day in the range, oldest day first. */
    val cells: List<HeatmapCellDto>,
)

@Serializable
data class ClientBreakdownItemDto(
    val name: String,
    val playCount: Long,
    val watchDurationSec: Long,
    val percent: Double,
)

@Serializable
data class ClientStatsResponse(
    val range: String,
    val fromMs: Long? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val transcodeOrRemuxPlays: Long,
    val players: List<ClientBreakdownItemDto>,
    val platforms: List<ClientBreakdownItemDto>,
    val videoCodecs: List<ClientBreakdownItemDto>,
    val audioCodecs: List<ClientBreakdownItemDto>,
    val transcodeReasons: List<ClientBreakdownItemDto>,
)

@Serializable
data class WrappedMonthStatDto(
    val month: Int,
    val monthName: String,
    val playCount: Long,
    val watchDurationSec: Long,
)

@Serializable
data class WrappedStatsResponse(
    val year: Int,
    val userId: String? = null,
    val accountId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val completedPlays: Long,
    val averageCompletionPercent: Double,
    val longestBingeStreakDays: Int,
    val activeDaysCount: Int,
    val uniqueMoviesCount: Int,
    val uniqueShowsCount: Int,
    val peakMonth: String? = null,
    val peakDayOfWeek: String? = null,
    val topMovies: List<TopMediaStatDto>,
    val topShows: List<TopMediaStatDto>,
    val mostRewatched: List<RewatchedMediaStatDto>,
    val topPlatforms: List<PlatformStatDto>,
    val topClients: List<ClientBreakdownItemDto>,
    val decisionBreakdown: DecisionBreakdownDto,
    val playsByMonth: List<WrappedMonthStatDto>,
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
    val decisionBreakdown: DecisionBreakdownDto,
)

@Serializable
data class UserStatsResponse(
    val userId: String,
    val accountId: String? = null,
    val username: String,
    val displayName: String? = null,
    val range: String,
    val fromMs: Long? = null,
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val averageCompletionPercent: Double = 0.0,
    val completedPlays: Long = 0L,
    val longestBingeStreakDays: Int = 0,
    val maxDailyPlays: Int = 0,
    val playsPerDay: List<DailyPlayStatDto>,
    val topMovies: List<TopMediaStatDto>,
    val topShows: List<TopMediaStatDto>,
    val mostRewatched: List<RewatchedMediaStatDto> = emptyList(),
    val decisionBreakdown: DecisionBreakdownDto,
    val topPlatforms: List<PlatformStatDto>,
    val libraryBreakdown: List<LibraryBreakdownDto>,
)

internal fun PlaybackHistoryRecord.toHistoryItemDto(
    user: UserRecord?,
    server: MediaServerRecord? = null,
): HistoryItemDto =
    HistoryItemDto(
        id = id,
        serverId = serverId,
        serverName = server?.name?.normalizedDisplayName(),
        serverType = server?.serverType?.lowercase(),
        userId = userId,
        username = user?.username,
        displayName = user?.displayName.normalizedDisplayName(),
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

internal fun PlaybackHistoryRecord.toHistoryDetailResponse(
    user: UserRecord?,
    server: MediaServerRecord? = null,
): HistoryDetailResponse =
    HistoryDetailResponse(
        id = id,
        serverId = serverId,
        serverName = server?.name?.normalizedDisplayName(),
        serverType = server?.serverType?.lowercase(),
        userId = userId,
        username = user?.username,
        displayName = user?.displayName.normalizedDisplayName(),
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
                transcodeVideoCodec = streamTech.transcodeVideoCodec,
                transcodeAudioCodec = streamTech.transcodeAudioCodec,
                container = streamTech.container,
                transcodeContainer = streamTech.transcodeContainer,
                subtitleDecision = streamTech.subtitleDecision,
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
