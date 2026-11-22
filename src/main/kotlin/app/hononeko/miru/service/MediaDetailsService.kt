package app.hononeko.miru.service

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.MediaDetailsResponse
import app.hononeko.miru.http.toResponse
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

/**
 * Application service serving rich media item details (including series and season context)
 * combined with RBAC-scoped Miru playback history summaries.
 */
class MediaDetailsService(
    private val mediaServerAdapter: MediaServerAdapter,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
) {
    suspend fun getMediaDetails(
        principal: MiruPrincipal,
        ratingKey: String,
    ): Either<MiruError, MediaDetailsResponse> =
        either {
            ensure(RATING_KEY_REGEX.matches(ratingKey)) {
                MiruError.ValidationError(
                    message = "Invalid media ratingKey",
                    fieldErrors = mapOf("ratingKey" to "must match ^[A-Za-z0-9_-]+$"),
                )
            }

            val details = mediaServerAdapter.fetchMediaDetails(ratingKey).bind()
            val allowedUserIds = principal.scopedMediaUserIds()

            val playSummary =
                playbackHistoryRepository
                    .summarizeByRatingKey(
                        ratingKey = details.ratingKey.ifBlank { ratingKey },
                        allowedUserIds = allowedUserIds,
                    ).bind()

            val seriesPlaySummary =
                details.series
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                    ?.let { seriesTitle ->
                        playbackHistoryRepository
                            .summarizeSeriesByTitle(
                                seriesTitle = seriesTitle,
                                allowedUserIds = allowedUserIds,
                            ).bind()
                    }

            val effectivePlaySummary =
                if (details.mediaType == MediaType.SHOW && playSummary.totalPlays == 0L && seriesPlaySummary != null) {
                    seriesPlaySummary
                } else {
                    playSummary
                }

            details.toResponse(
                playSummary = effectivePlaySummary,
                seriesPlaySummary = seriesPlaySummary,
            )
        }

    companion object {
        private val RATING_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }
}
