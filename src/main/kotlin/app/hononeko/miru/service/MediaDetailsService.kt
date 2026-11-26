package app.hononeko.miru.service

import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.MediaDetailsResponse
import app.hononeko.miru.http.toResponse
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.LibraryRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

/**
 * Application service serving rich media item details (including series and season context)
 * combined with RBAC-scoped Miru playback history summaries.
 */
@Suppress("LongParameterList")
class MediaDetailsService(
    private val mediaServerAdapter: MediaServerAdapter,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val libraryRepository: LibraryRepository,
    private val sectionAccessChecker: MediaSectionAccessChecker =
        MediaSectionAccessChecker(libraryRepository = libraryRepository, adapter = mediaServerAdapter),
    private val adapterForServer: (suspend (String?) -> MediaServerAdapter?)? = null,
    private val allServerAdaptersProvider: (suspend () -> List<Pair<String?, MediaServerAdapter>>)? = null,
    private val mediaServerRepository: MediaServerRepository? = null,
) {
    suspend fun getMediaDetails(
        principal: MiruPrincipal,
        ratingKey: String,
        serverId: String? = null,
    ): Either<MiruError, MediaDetailsResponse> =
        either {
            ensure(RATING_KEY_REGEX.matches(ratingKey)) {
                MiruError.ValidationError(
                    message = "Invalid media ratingKey",
                    fieldErrors = mapOf("ratingKey" to "must match ^[A-Za-z0-9_-]+$"),
                )
            }

            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val preloadedAccess =
                sectionAccessChecker.requireAnyLibraryAccess(principal = principal, ratingKey = ratingKey).bind()
            val (resolvedServerId, details) =
                fetchDetailsAcrossServers(ratingKey = ratingKey, requestedServerId = normalizedServerId).bind()

            sectionAccessChecker
                .requireAccessForKnownSection(
                    principal = principal,
                    ratingKey = ratingKey,
                    sectionKey = details.librarySectionId,
                    preloadedAccess = preloadedAccess,
                    serverId = resolvedServerId,
                ).bind()
            val allowedUserIds = principal.scopedMediaUserIds()

            val playSummary =
                playbackHistoryRepository
                    .summarizeByRatingKey(
                        ratingKey = details.ratingKey.ifBlank { ratingKey },
                        allowedUserIds = allowedUserIds,
                        serverId = resolvedServerId,
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
                                serverId = resolvedServerId,
                            ).bind()
                    }

            val effectivePlaySummary =
                if (details.mediaType == MediaType.SHOW && playSummary.totalPlays == 0L && seriesPlaySummary != null) {
                    seriesPlaySummary
                } else {
                    playSummary
                }

            val serverRecord = resolvedServerId?.let { mediaServerRepository?.findById(it)?.getOrNull() }
            details.toResponse(
                playSummary = effectivePlaySummary,
                seriesPlaySummary = seriesPlaySummary,
                server = serverRecord,
            )
        }

    private suspend fun fetchDetailsAcrossServers(
        ratingKey: String,
        requestedServerId: String?,
    ): Either<MiruError, Pair<String?, MediaItemDetails>> =
        either {
            if (requestedServerId != null) {
                val adapter =
                    if (adapterForServer != null) {
                        adapterForServer(requestedServerId)
                            ?: raise(MiruError.NotFound(resource = "Media server", id = requestedServerId))
                    } else {
                        mediaServerAdapter
                    }
                return@either requestedServerId to adapter.fetchMediaDetails(ratingKey).bind()
            }

            if (adapterForServer != null) {
                val dbServerIds =
                    libraryRepository
                        .findSectionKeysByRatingKey(ratingKey)
                        .getOrNull()
                        .orEmpty()
                        .map { it.first }
                        .distinct()
                for (dbServerId in dbServerIds) {
                    val candidateAdapter = adapterForServer(dbServerId) ?: continue
                    val result = candidateAdapter.fetchMediaDetails(ratingKey)
                    if (result is Either.Right) {
                        return@either dbServerId to result.value
                    }
                }
            }

            val candidates =
                allServerAdaptersProvider?.invoke()?.takeIf { it.isNotEmpty() }
                    ?: listOf(null to mediaServerAdapter)
            var lastError: MiruError? = null
            for ((candidateServerId, candidateAdapter) in candidates) {
                when (val result = candidateAdapter.fetchMediaDetails(ratingKey)) {
                    is Either.Right -> return@either candidateServerId to result.value
                    is Either.Left -> lastError = result.value
                }
            }
            raise(lastError ?: MiruError.NotFound(resource = "Media item", id = ratingKey))
        }

    companion object {
        private val RATING_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }
}
