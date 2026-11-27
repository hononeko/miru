package app.hononeko.miru.adapter

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Canonical contract for communicating with an upstream media server (e.g. Plex).
 *
 * All operations return [Either] with typed [MiruError] failures rather than throwing exceptions.
 */
interface MediaServerAdapter {
    val serverType: String

    suspend fun checkHealth(): Either<MiruError, ServerIdentity>

    suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>>

    suspend fun fetchUsers(): Either<MiruError, List<MediaUser>>

    suspend fun fetchUserLibraryAccess(): Either<MiruError, List<MediaUserLibraryAccess>> =
        Either.Right(emptyList())

    suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>>

    suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int? = null,
    ): Either<MiruError, Int>

    suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int = 0,
        size: Int = 100,
        mediaTypeCode: Int? = null,
    ): Either<MiruError, LibraryItemsPage>

    suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails>

    suspend fun fetchMediaSectionKey(ratingKey: String): Either<MiruError, String?> =
        fetchMediaDetails(ratingKey).map { it.librarySectionId }

    suspend fun fetchArtwork(
        path: String,
        width: Int? = null,
        height: Int? = null,
    ): Either<MiruError, ArtworkData>

    /**
     * Has the media server scale the image at [source] (a path on the server, or an absolute HTTPS URL
     * the caller has already allowlisted, which the server fetches itself) to fit within [maxDimension]
     * pixels, as a PNG. Servers that cannot scale such images on request refuse with
     * [MiruError.UpstreamUnavailable]: Jellyfin's user image API (`GET /UserImage`) has no size parameters.
     */
    suspend fun fetchScaledImage(
        source: String,
        maxDimension: Int,
    ): Either<MiruError, ArtworkData> =
        Either.Left(
            MiruError.UpstreamUnavailable(
                provider = serverType,
                message = "This media server cannot scale images on request",
            ),
        )

    fun mediaArtworkPath(
        ratingKey: String,
        isBackdrop: Boolean,
    ): String = "/library/metadata/$ratingKey/${if (isBackdrop) "art" else "thumb"}"

    suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit>

    fun events(): Flow<MediaServerEvent> = emptyFlow()
}
