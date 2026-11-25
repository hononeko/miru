package app.hononeko.miru.http

import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaTechnicalSpecs
import app.hononeko.miru.adapter.SeasonDetailsInfo
import app.hononeko.miru.adapter.SeriesOverviewInfo
import app.hononeko.miru.persistence.model.MediaPlaySummary
import kotlinx.serialization.Serializable

private const val MILLIS_PER_SECOND = 1_000L

/**
 * Proxy URL for an item's background art. Plex points an episode's or season's `art` at its show's artwork
 * (`/library/metadata/<showKey>/art/<timestamp>`), so the rating key comes from the art path when it names one.
 */
internal fun buildArtUrl(
    ratingKey: String,
    artPath: String?,
): String? = buildMediaArtworkUrl(ratingKey = ratingKey, artworkPath = artPath, kind = "art")

@Serializable
data class MediaTechnicalSpecsDto(
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

@Serializable
data class SeriesOverviewDto(
    val ratingKey: String,
    val title: String,
    val year: Int? = null,
    val studio: String? = null,
    val contentRating: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val thumbUrl: String? = null,
)

@Serializable
data class SeasonDetailsDto(
    val ratingKey: String,
    val title: String,
    val seasonIndex: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val episodeCount: Int? = null,
    val currentEpisodeIndex: Int? = null,
    val thumbUrl: String? = null,
)

@Serializable
data class MediaPlaySummaryDto(
    val totalPlays: Long,
    val uniqueUsers: Int,
    val lastWatchedAtMs: Long? = null,
    val lastWatchedUsername: String? = null,
    val avgCompletionPercent: Double? = null,
    val directPlayCount: Long,
    val directStreamCount: Long,
    val transcodeCount: Long,
    val totalWatchDurationSec: Long,
)

@Serializable
data class MediaDetailsResponse(
    val ratingKey: String,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val originallyAvailableAt: String? = null,
    val durationMs: Long? = null,
    val contentRating: String? = null,
    val studio: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val librarySectionKey: String? = null,
    val librarySectionTitle: String? = null,
    val addedAtMs: Long? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentIndex: Int? = null,
    val index: Int? = null,
    val childCount: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val thumbUrl: String? = null,
    val artUrl: String? = null,
    val technical: MediaTechnicalSpecsDto? = null,
    val series: SeriesOverviewDto? = null,
    val season: SeasonDetailsDto? = null,
    val playSummary: MediaPlaySummaryDto,
    val seriesPlaySummary: MediaPlaySummaryDto? = null,
)

internal fun MediaTechnicalSpecs.toDto(): MediaTechnicalSpecsDto =
    MediaTechnicalSpecsDto(
        videoResolution = videoResolution,
        videoCodec = videoCodec,
        videoProfile = videoProfile,
        audioCodec = audioCodec,
        audioChannels = audioChannels,
        container = container,
        bitrateKbps = bitrateKbps,
        fileSizeBytes = fileSizeBytes,
        subtitleLanguages = subtitleLanguages,
    )

internal fun SeriesOverviewInfo.toDto(): SeriesOverviewDto =
    SeriesOverviewDto(
        ratingKey = ratingKey,
        title = title,
        year = year,
        studio = studio,
        contentRating = contentRating,
        summary = summary,
        genres = genres,
        seasonCount = seasonCount,
        episodeCount = episodeCount,
        thumbUrl = if (hasThumb && ratingKey.isNotBlank()) "/api/v1/media/$ratingKey/thumb" else null,
    )

internal fun SeasonDetailsInfo.toDto(): SeasonDetailsDto =
    SeasonDetailsDto(
        ratingKey = ratingKey,
        title = title,
        seasonIndex = seasonIndex,
        year = year,
        summary = summary,
        episodeCount = episodeCount,
        currentEpisodeIndex = currentEpisodeIndex,
        thumbUrl = if (hasThumb && ratingKey.isNotBlank()) "/api/v1/media/$ratingKey/thumb" else null,
    )

internal fun MediaPlaySummary.toDto(): MediaPlaySummaryDto =
    MediaPlaySummaryDto(
        totalPlays = totalPlays,
        uniqueUsers = uniqueUsers,
        lastWatchedAtMs = lastWatchedAtMs,
        lastWatchedUsername = lastWatchedUsername,
        avgCompletionPercent = avgCompletionPercent,
        directPlayCount = directPlayCount,
        directStreamCount = directStreamCount,
        transcodeCount = transcodeCount,
        totalWatchDurationSec = totalWatchDurationSec,
    )

internal fun MediaItemDetails.toResponse(
    playSummary: MediaPlaySummary,
    seriesPlaySummary: MediaPlaySummary? = null,
): MediaDetailsResponse =
    MediaDetailsResponse(
        ratingKey = ratingKey,
        mediaType = mediaType.name.lowercase(),
        title = title,
        year = year,
        originallyAvailableAt = originallyAvailableAt,
        durationMs = durationMs,
        contentRating = contentRating,
        studio = studio,
        summary = summary,
        genres = genres,
        directors = directors,
        librarySectionKey = librarySectionId,
        librarySectionTitle = librarySectionTitle,
        addedAtMs = addedAtEpochSeconds?.times(MILLIS_PER_SECOND),
        parentRatingKey = parentRatingKey,
        grandparentRatingKey = grandparentRatingKey,
        parentTitle = parentTitle,
        grandparentTitle = grandparentTitle,
        parentIndex = parentIndex,
        index = index,
        childCount = childCount,
        leafCount = leafCount,
        viewedLeafCount = viewedLeafCount,
        thumbUrl = buildThumbUrl(ratingKey, thumbPath),
        artUrl = buildArtUrl(ratingKey, artPath),
        technical = technical?.toDto(),
        series = series?.toDto(),
        season = season?.toDto(),
        playSummary = playSummary.toDto(),
        seriesPlaySummary = seriesPlaySummary?.toDto(),
    )
