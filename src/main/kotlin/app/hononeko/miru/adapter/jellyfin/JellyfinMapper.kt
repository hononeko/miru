package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.LibraryItemInfo
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaTechnicalSpecs
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.MediaUserLibraryAccess
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.SeasonDetailsInfo
import app.hononeko.miru.adapter.SeriesOverviewInfo
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Instant

private const val TICKS_PER_MILLISECOND = 10_000L
private const val BITS_PER_KILOBIT = 1_000
private const val WIDTH_8K_THRESHOLD = 7_600
private const val HEIGHT_8K_THRESHOLD = 4_320
private const val WIDTH_4K_THRESHOLD = 3_800
private const val HEIGHT_4K_THRESHOLD = 2_160
private const val WIDTH_1080P_THRESHOLD = 1_900
private const val HEIGHT_1080P_THRESHOLD = 1_080
private const val WIDTH_720P_THRESHOLD = 1_200
private const val HEIGHT_720P_THRESHOLD = 720
private const val DATE_PREFIX_LENGTH = 10
private const val IPV4_PRIVATE_172_MIN = 16
private const val IPV4_PRIVATE_172_MAX = 31
private const val IPV4_CARRIER_NAT_MIN = 64
private const val IPV4_CARRIER_NAT_MAX = 127
private const val BYTE_MASK = 0xFF
private const val IPV6_ULA_MASK = 0xFE
private const val IPV6_ULA_PREFIX = 0xFC

object JellyfinMapper {
    fun mapServerIdentity(dto: JellyfinSystemInfoDto): ServerIdentity {
        val machineId = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        val friendlyName =
            dto.serverName?.trim()?.takeIf { it.isNotEmpty() }
                ?: machineId.takeIf { it != "unknown" }
                ?: "Jellyfin Media Server"
        val version = dto.version?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        val platform = dto.operatingSystem?.trim()?.takeIf { it.isNotEmpty() }
        return ServerIdentity(
            machineIdentifier = machineId,
            friendlyName = friendlyName,
            version = version,
            platform = platform,
        )
    }

    fun mapSessions(sessions: List<JellyfinSessionDto>): List<ActiveStreamSnapshot> =
        sessions.mapNotNull { session ->
            val item = session.nowPlayingItem ?: return@mapNotNull null
            mapSession(session, item)
        }

    fun mapSession(
        session: JellyfinSessionDto,
        item: JellyfinBaseItemDto,
    ): ActiveStreamSnapshot {
        val mediaType = mapMediaType(item)
        val playState = session.playState
        val selectedSource = selectMediaSource(item, playState?.mediaSourceId)
        val streams = selectedSource?.mediaStreams ?: item.mediaStreams.orEmpty()
        val videoStream = selectStream(streams, "Video", preferredIndex = null)
        val audioStream = selectStream(streams, "Audio", preferredIndex = playState?.audioStreamIndex)
        val sessionId = session.id?.trim()?.takeIf { it.isNotEmpty() }
        val isLocal = isLocalEndpoint(session.remoteEndPoint)
        val location = if (isLocal) StreamLocation.LAN else StreamLocation.WAN

        return ActiveStreamSnapshot(
            sessionId = sessionId,
            sessionKey = sessionId,
            state = if (playState?.isPaused == true) PlaybackState.PAUSED else PlaybackState.PLAYING,
            viewOffsetMs = ticksToMs(playState?.positionTicks) ?: 0L,
            media = mapStreamMedia(item, mediaType, selectedSource),
            user = mapStreamUser(session),
            player = mapStreamPlayer(session, location, isLocal),
            technical =
                mapStreamTechnicalInfo(
                    playState = playState,
                    transcoding = session.transcodingInfo,
                    mediaSource = selectedSource,
                    videoStream = videoStream,
                    audioStream = audioStream,
                ),
        )
    }

    fun mapUsers(users: List<JellyfinUserDto>): List<MediaUser> =
        users.mapNotNull { dto ->
            val id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = dto.name?.trim()?.takeIf { it.isNotEmpty() } ?: id
            val thumb =
                if (!dto.primaryImageTag.isNullOrBlank()) {
                    "/Users/$id/Images/Primary"
                } else {
                    null
                }
            MediaUser(
                externalId = id,
                username = name,
                displayName = name,
                email = null,
                thumbUrl = thumb,
                isAdmin = dto.policy?.isAdministrator == true,
            )
        }

    fun mapUserLibraryAccess(users: List<JellyfinUserDto>): List<MediaUserLibraryAccess> =
        users.mapNotNull { dto ->
            val id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val policy = dto.policy
            val allLibraries = policy?.isAdministrator == true || policy?.enableAllFolders == true
            val sectionKeys =
                if (allLibraries) {
                    emptySet()
                } else {
                    policy?.enabledFolders
                        .orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                }
            MediaUserLibraryAccess(
                externalUserId = id,
                allLibraries = allLibraries,
                sectionKeys = sectionKeys,
            )
        }

    fun mapLibrarySections(folders: List<JellyfinVirtualFolderDto>): List<LibrarySectionInfo> =
        folders.mapNotNull { folder ->
            val key = folder.itemId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LibrarySectionInfo(
                sectionKey = key,
                title = folder.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Library $key",
                type = mapSectionType(folder.collectionType),
                count = null,
            )
        }

    fun mapLibraryItemsPage(
        sectionKey: String,
        requestedStart: Int,
        response: JellyfinItemsQueryResponse,
    ): LibraryItemsPage {
        val items = response.items.orEmpty().map { mapLibraryItem(sectionKey, it) }
        val totalSize = response.totalRecordCount ?: items.size
        val offset = response.startIndex ?: requestedStart
        return LibraryItemsPage(
            totalSize = totalSize,
            offset = offset,
            items = items,
        )
    }

    fun mapLibraryItem(
        sectionKey: String,
        item: JellyfinBaseItemDto,
    ): LibraryItemInfo {
        val mediaType = mapMediaType(item)
        val primarySource = selectMediaSource(item, null)
        val streams = primarySource?.mediaStreams ?: item.mediaStreams.orEmpty()
        val videoStream = selectStream(streams, "Video", null)
        val audioStream = selectStream(streams, "Audio", null)

        val grandparentTitle =
            when (mediaType) {
                MediaType.EPISODE -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
                MediaType.TRACK ->
                    item.albumArtist?.trim()?.takeIf { it.isNotEmpty() }
                        ?: item.artists?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                else -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
            }
        val parentTitle =
            when (mediaType) {
                MediaType.EPISODE -> item.seasonName?.trim()?.takeIf { it.isNotEmpty() }
                MediaType.TRACK -> item.album?.trim()?.takeIf { it.isNotEmpty() }
                else -> item.seasonName?.trim()?.takeIf { it.isNotEmpty() } ?: item.album?.trim()?.takeIf { it.isNotEmpty() }
            }
        val grandparentRatingKey =
            when (mediaType) {
                MediaType.EPISODE -> item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
                MediaType.TRACK ->
                    item.albumArtists?.firstOrNull { !it.id.isNullOrBlank() }?.id?.trim()
                        ?: item.artistItems?.firstOrNull { !it.id.isNullOrBlank() }?.id?.trim()
                        ?: item.albumId?.trim()?.takeIf { it.isNotEmpty() }
                else -> item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
            }

        return LibraryItemInfo(
            ratingKey = item.id?.trim().orEmpty(),
            sectionKey = sectionKey,
            mediaType = mediaType,
            title = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown",
            parentTitle = parentTitle,
            grandparentTitle = grandparentTitle,
            year = item.productionYear ?: parseYearFromIso(item.premiereDate),
            thumbPath = resolvePrimaryImagePath(item),
            durationMs = ticksToMs(item.runTimeTicks ?: primarySource?.runTimeTicks),
            fileSizeBytes = primarySource?.size?.takeIf { it > 0L },
            videoResolution = formatResolution(videoStream?.width, videoStream?.height),
            videoCodec = videoStream?.codec?.trim()?.takeIf { it.isNotEmpty() },
            audioCodec = audioStream?.codec?.trim()?.takeIf { it.isNotEmpty() },
            addedAtEpochSeconds = parseEpochSeconds(item.dateCreated),
            grandparentRatingKey = grandparentRatingKey,
        )
    }

    fun mapMediaItemDetails(
        item: JellyfinBaseItemDto,
        parentSeason: JellyfinBaseItemDto? = null,
        grandparentSeries: JellyfinBaseItemDto? = null,
        librarySectionId: String? = null,
        librarySectionTitle: String? = null,
    ): MediaItemDetails {
        val mediaType = mapMediaType(item)
        val primarySource = selectMediaSource(item, null)
        val streams = primarySource?.mediaStreams ?: item.mediaStreams.orEmpty()
        val videoStream = selectStream(streams, "Video", null)
        val audioStream = selectStream(streams, "Audio", null)

        return MediaItemDetails(
            ratingKey = item.id?.trim().orEmpty(),
            mediaType = mediaType,
            title = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown",
            year = item.productionYear ?: parseYearFromIso(item.premiereDate),
            originallyAvailableAt = item.premiereDate?.trim()?.takeIf { it.length >= DATE_PREFIX_LENGTH }?.substring(0, DATE_PREFIX_LENGTH),
            durationMs = ticksToMs(item.runTimeTicks ?: primarySource?.runTimeTicks),
            contentRating = item.officialRating?.trim()?.takeIf { it.isNotEmpty() },
            studio = firstNonBlankStudio(item),
            summary = item.overview?.trim()?.takeIf { it.isNotEmpty() },
            genres = item.genres.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            directors = extractDirectors(item),
            librarySectionId = librarySectionId?.trim()?.takeIf { it.isNotEmpty() },
            librarySectionTitle = librarySectionTitle?.trim()?.takeIf { it.isNotEmpty() },
            addedAtEpochSeconds = parseEpochSeconds(item.dateCreated),
            parentRatingKey =
                item.seasonId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: item.parentId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: item.albumId?.trim()?.takeIf { it.isNotEmpty() },
            grandparentRatingKey =
                item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: item.albumArtists?.firstOrNull { !it.id.isNullOrBlank() }?.id?.trim()
                    ?: item.artistItems?.firstOrNull { !it.id.isNullOrBlank() }?.id?.trim()
                    ?: item.albumId?.trim()?.takeIf { it.isNotEmpty() },
            parentTitle = resolveDetailsParentTitle(mediaType, item),
            grandparentTitle = resolveGrandparentTitle(mediaType, item),
            parentIndex = item.parentIndexNumber,
            index = item.indexNumber,
            childCount = item.childCount,
            leafCount = item.recursiveItemCount,
            viewedLeafCount = computeViewedLeafCount(item),
            thumbPath = resolvePrimaryImagePath(item),
            artPath = resolveBackdropImagePath(item),
            technical = mapTechnicalSpecs(primarySource, streams, videoStream, audioStream),
            series = resolveSeriesOverview(mediaType, item, grandparentSeries),
            season = resolveSeasonDetails(mediaType, item, parentSeason),
        )
    }

    fun mapMediaType(item: JellyfinBaseItemDto): MediaType {
        val rawType = item.type?.trim()?.lowercase()
        return when (rawType) {
            "movie" -> MediaType.MOVIE
            "episode" -> MediaType.EPISODE
            "season" -> MediaType.SEASON
            "series" -> MediaType.SHOW
            "audio" -> MediaType.TRACK
            "video", "musicvideo", "trailer" -> MediaType.CLIP
            "tvchannel", "livetvchannel", "program" -> MediaType.LIVE_TV
            else ->
                if (!item.channelName.isNullOrBlank() || !item.channelNumber.isNullOrBlank()) {
                    MediaType.LIVE_TV
                } else {
                    MediaType.UNKNOWN
                }
        }
    }

    internal fun mapSectionType(raw: String?): LibrarySectionType =
        when (raw?.trim()?.lowercase()) {
            "movies" -> LibrarySectionType.MOVIE
            "tvshows" -> LibrarySectionType.SHOW
            "music" -> LibrarySectionType.ARTIST
            "homevideos", "photos" -> LibrarySectionType.PHOTO
            else -> LibrarySectionType.OTHER
        }

    /**
     * True for a "Mixed movies and shows" library, which Jellyfin lists without a CollectionType.
     * Its section type has to be worked out from what it contains.
     */
    internal fun isMixedCollection(raw: String?): Boolean = raw.isNullOrBlank() || raw.trim().equals("mixed", ignoreCase = true)

    /**
     * Jellyfin item types that are a library's titles: what Jellyfin itself counts for that library.
     * Used when a library's items are requested without a specific type. Folders (extras, samples),
     * seasons and episodes are never titles; box sets only in a collections library, because Jellyfin
     * returns every box set for any library.
     */
    internal fun titleItemTypes(collectionType: String?): String =
        when (collectionType?.trim()?.lowercase()) {
            "movies" -> "Movie"
            "tvshows" -> "Series"
            "music" -> "MusicAlbum"
            "musicvideos" -> "MusicVideo"
            "homevideos", "photos" -> "Video,Photo"
            "boxsets" -> "BoxSet"
            "books" -> "Book,AudioBook"
            else -> GENERIC_TITLE_ITEM_TYPES
        }

    /** Titles of a library whose content isn't known: everything a person would call a title */
    internal const val GENERIC_TITLE_ITEM_TYPES = "Movie,Series,Video,MusicVideo,MusicAlbum,Photo,Book,AudioBook"

    internal fun ticksToMs(ticks: Long?): Long? =
        ticks?.takeIf { it >= 0L }?.let { it / TICKS_PER_MILLISECOND }

    internal fun isLocalEndpoint(rawEndpoint: String?): Boolean {
        val cleaned = rawEndpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val host =
            if (cleaned.startsWith("[")) {
                cleaned.substringAfter('[').substringBefore(']')
            } else if (cleaned.count { it == ':' } == 1) {
                cleaned.substringBefore(':')
            } else {
                cleaned
            }
        val literal = host.substringBefore('%').trim()
        if (literal.equals("localhost", ignoreCase = true)) return true
        val address = runCatching { InetAddress.ofLiteral(literal) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            return isPrivateOrCarrierNatIpv4(bytes, offset = 0)
        }
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and BYTE_MASK
            if ((first and IPV6_ULA_MASK) == IPV6_ULA_PREFIX) return true
        }
        return false
    }
}

private fun isPrivateOrCarrierNatIpv4(
    bytes: ByteArray,
    offset: Int,
): Boolean {
    val first = bytes[offset].toInt() and BYTE_MASK
    val second = bytes[offset + 1].toInt() and BYTE_MASK
    return when (first) {
        10 -> true
        172 -> second in IPV4_PRIVATE_172_MIN..IPV4_PRIVATE_172_MAX
        192 -> second == 168
        100 -> second in IPV4_CARRIER_NAT_MIN..IPV4_CARRIER_NAT_MAX
        else -> false
    }
}

private fun firstNonBlankStudio(item: JellyfinBaseItemDto): String? =
    item.studios
        ?.firstOrNull { !it.name.isNullOrBlank() }
        ?.name
        ?.trim()

private fun extractDirectors(item: JellyfinBaseItemDto): List<String> =
    item.people
        .orEmpty()
        .filter { it.type.equals("Director", ignoreCase = true) }
        .mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotEmpty() } }
        .distinct()

private fun resolveDetailsParentTitle(
    mediaType: MediaType,
    item: JellyfinBaseItemDto,
): String? =
    when (mediaType) {
        MediaType.EPISODE -> item.seasonName?.trim()?.takeIf { it.isNotEmpty() }
        MediaType.SEASON -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
        MediaType.TRACK -> item.album?.trim()?.takeIf { it.isNotEmpty() }
        else -> item.seasonName?.trim()?.takeIf { it.isNotEmpty() } ?: item.album?.trim()?.takeIf { it.isNotEmpty() }
    }

private fun resolveGrandparentTitle(
    mediaType: MediaType,
    item: JellyfinBaseItemDto,
): String? =
    when (mediaType) {
        MediaType.EPISODE -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
        MediaType.TRACK ->
            item.albumArtist?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.artists?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        else -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
    }

private fun resolveSeriesOverview(
    mediaType: MediaType,
    item: JellyfinBaseItemDto,
    grandparentSeries: JellyfinBaseItemDto?,
): SeriesOverviewInfo? =
    when (mediaType) {
        MediaType.SHOW -> mapSeriesOverview(item)
        MediaType.SEASON ->
            grandparentSeries?.let { mapSeriesOverview(it) }
                ?: fallbackSeriesOverview(item, item.seriesId?.trim()?.takeIf { it.isNotEmpty() } ?: item.parentId?.trim().orEmpty())
        MediaType.EPISODE ->
            grandparentSeries?.let { mapSeriesOverview(it) }
                ?: fallbackSeriesOverview(item, item.seriesId?.trim()?.takeIf { it.isNotEmpty() }.orEmpty())
        else -> null
    }

private fun fallbackSeriesOverview(
    item: JellyfinBaseItemDto,
    fallbackRatingKey: String,
): SeriesOverviewInfo? =
    item.seriesName?.trim()?.takeIf { it.isNotEmpty() }?.let { title ->
        SeriesOverviewInfo(
            ratingKey = fallbackRatingKey,
            title = title,
            hasThumb = !item.seriesPrimaryImageTag.isNullOrBlank(),
        )
    }

private fun resolveSeasonDetails(
    mediaType: MediaType,
    item: JellyfinBaseItemDto,
    parentSeason: JellyfinBaseItemDto?,
): SeasonDetailsInfo? =
    when (mediaType) {
        MediaType.SEASON -> mapSeasonDetails(item, currentEpisodeIndex = null)
        MediaType.EPISODE ->
            parentSeason?.let { mapSeasonDetails(it, currentEpisodeIndex = item.indexNumber) }
                ?: fallbackSeasonDetails(item)
        else -> null
    }

private fun mapSeriesOverview(series: JellyfinBaseItemDto): SeriesOverviewInfo =
    SeriesOverviewInfo(
        ratingKey = series.id?.trim().orEmpty(),
        title = series.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown",
        year = series.productionYear ?: parseYearFromIso(series.premiereDate),
        studio = firstNonBlankStudio(series),
        contentRating = series.officialRating?.trim()?.takeIf { it.isNotEmpty() },
        summary = series.overview?.trim()?.takeIf { it.isNotEmpty() },
        genres = series.genres.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        seasonCount = series.childCount,
        episodeCount = series.recursiveItemCount,
        hasThumb = series.imageTags?.containsKey("Primary") == true,
    )

private fun mapSeasonDetails(
    season: JellyfinBaseItemDto,
    currentEpisodeIndex: Int?,
): SeasonDetailsInfo =
    SeasonDetailsInfo(
        ratingKey = season.id?.trim().orEmpty(),
        title =
            season.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: season.indexNumber?.let { "Season $it" }
                ?: "Season",
        seasonIndex = season.indexNumber,
        year = season.productionYear ?: parseYearFromIso(season.premiereDate),
        summary = season.overview?.trim()?.takeIf { it.isNotEmpty() },
        episodeCount = season.recursiveItemCount ?: season.childCount,
        currentEpisodeIndex = currentEpisodeIndex,
        hasThumb = season.imageTags?.containsKey("Primary") == true,
    )

private fun fallbackSeasonDetails(episode: JellyfinBaseItemDto): SeasonDetailsInfo? {
    val fallbackTitle =
        episode.seasonName?.trim()?.takeIf { it.isNotEmpty() }
            ?: episode.parentIndexNumber?.let { "Season $it" }
            ?: return null
    return SeasonDetailsInfo(
        ratingKey = episode.seasonId?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
        title = fallbackTitle,
        seasonIndex = episode.parentIndexNumber,
        currentEpisodeIndex = episode.indexNumber,
        hasThumb = false,
    )
}

private fun mapTechnicalSpecs(
    primarySource: JellyfinMediaSourceDto?,
    streams: List<JellyfinMediaStreamDto>,
    videoStream: JellyfinMediaStreamDto?,
    audioStream: JellyfinMediaStreamDto?,
): MediaTechnicalSpecs? {
    if (primarySource == null && streams.isEmpty()) return null
    val subtitleLanguages =
        streams
            .filter { it.type.equals("Subtitle", ignoreCase = true) }
            .mapNotNull { stream ->
                stream.language?.trim()?.takeIf { it.isNotEmpty() }
                    ?: stream.displayTitle?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct()

    return MediaTechnicalSpecs(
        videoResolution = formatResolution(videoStream?.width, videoStream?.height),
        videoCodec = videoStream?.codec?.trim()?.takeIf { it.isNotEmpty() },
        videoProfile = videoStream?.profile?.trim()?.takeIf { it.isNotEmpty() },
        audioCodec = audioStream?.codec?.trim()?.takeIf { it.isNotEmpty() },
        audioChannels = audioStream?.channels,
        container = primarySource?.container?.trim()?.takeIf { it.isNotEmpty() },
        bitrateKbps = (primarySource?.bitrate ?: videoStream?.bitRate)?.let { it / BITS_PER_KILOBIT },
        fileSizeBytes = primarySource?.size?.takeIf { it > 0L },
        subtitleLanguages = subtitleLanguages,
    )
}

private fun mapStreamMedia(
    item: JellyfinBaseItemDto,
    mediaType: MediaType,
    selectedSource: JellyfinMediaSourceDto?,
): StreamMedia {
    val title =
        item.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.channelName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

    val grandparentTitle =
        when (mediaType) {
            MediaType.EPISODE -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
            MediaType.TRACK ->
                item.albumArtist?.trim()?.takeIf { it.isNotEmpty() }
                    ?: item.artists?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            MediaType.LIVE_TV -> item.channelName?.trim()?.takeIf { it.isNotEmpty() && it != title }
            else -> item.seriesName?.trim()?.takeIf { it.isNotEmpty() }
        }

    val parentTitle =
        when (mediaType) {
            MediaType.EPISODE -> item.seasonName?.trim()?.takeIf { it.isNotEmpty() }
            MediaType.TRACK -> item.album?.trim()?.takeIf { it.isNotEmpty() }
            else -> item.seasonName?.trim()?.takeIf { it.isNotEmpty() } ?: item.album?.trim()?.takeIf { it.isNotEmpty() }
        }

    val seriesThumbPath =
        if (!item.seriesId.isNullOrBlank() && !item.seriesPrimaryImageTag.isNullOrBlank()) {
            "/Items/${item.seriesId.trim()}/Images/Primary"
        } else {
            null
        }

    return StreamMedia(
        ratingKey = item.id?.trim().orEmpty(),
        mediaType = mediaType,
        title = title,
        parentTitle = parentTitle,
        grandparentTitle = grandparentTitle,
        parentIndex = item.parentIndexNumber,
        index = item.indexNumber,
        year = item.productionYear ?: parseYearFromIso(item.premiereDate),
        durationMs = JellyfinMapper.ticksToMs(item.runTimeTicks ?: selectedSource?.runTimeTicks),
        thumbPath = resolvePrimaryImagePath(item),
        grandparentThumbPath = seriesThumbPath,
        artPath = resolveBackdropImagePath(item),
        librarySectionId = null,
    )
}

private fun mapStreamUser(session: JellyfinSessionDto): StreamUser {
    val userId = session.userId?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
    val userName = session.userName?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"
    val thumbUrl =
        if (userId != "unknown" && !session.userPrimaryImageTag.isNullOrBlank()) {
            "/Users/$userId/Images/Primary"
        } else {
            null
        }
    return StreamUser(
        externalId = userId,
        username = userName,
        thumbUrl = thumbUrl,
    )
}

private fun mapStreamPlayer(
    session: JellyfinSessionDto,
    location: StreamLocation,
    isLocal: Boolean,
): StreamPlayer =
    StreamPlayer(
        machineIdentifier = session.deviceId?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.id?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown",
        product = session.client?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown",
        platform = session.client?.trim()?.takeIf { it.isNotEmpty() },
        device = session.deviceName?.trim()?.takeIf { it.isNotEmpty() },
        title = session.deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.client?.trim()?.takeIf { it.isNotEmpty() },
        address = session.remoteEndPoint?.trim()?.takeIf { it.isNotEmpty() },
        location = location,
        isLocal = isLocal,
    )

private fun mapStreamTechnicalInfo(
    playState: JellyfinPlayStateDto?,
    transcoding: JellyfinTranscodingInfoDto?,
    mediaSource: JellyfinMediaSourceDto?,
    videoStream: JellyfinMediaStreamDto?,
    audioStream: JellyfinMediaStreamDto?,
): StreamTechnicalInfo {
    val overallDecision = resolveDecision(playState?.playMethod, transcoding)
    val videoDecision =
        when {
            videoStream == null && transcoding?.videoCodec.isNullOrBlank() -> null
            overallDecision == StreamDecision.DIRECT_PLAY -> StreamDecision.DIRECT_PLAY
            transcoding?.isVideoDirect == true -> StreamDecision.DIRECT_STREAM
            transcoding?.isVideoDirect == false -> StreamDecision.TRANSCODE
            overallDecision == StreamDecision.DIRECT_STREAM -> StreamDecision.DIRECT_STREAM
            else -> StreamDecision.TRANSCODE
        }
    val audioDecision =
        when {
            audioStream == null && transcoding?.audioCodec.isNullOrBlank() -> null
            overallDecision == StreamDecision.DIRECT_PLAY -> StreamDecision.DIRECT_PLAY
            transcoding?.isAudioDirect == true -> StreamDecision.DIRECT_STREAM
            transcoding?.isAudioDirect == false -> StreamDecision.TRANSCODE
            overallDecision == StreamDecision.DIRECT_STREAM -> StreamDecision.DIRECT_STREAM
            else -> StreamDecision.TRANSCODE
        }

    val hwAccel = transcoding?.hardwareAccelerationType?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("none", ignoreCase = true)
    }
    val isHwTranscode = overallDecision == StreamDecision.TRANSCODE && hwAccel != null
    val srcVideoCodec = videoStream?.codec?.trim()?.takeIf { it.isNotEmpty() }
    val srcAudioCodec = audioStream?.codec?.trim()?.takeIf { it.isNotEmpty() }
    val srcContainer = mediaSource?.container?.trim()?.takeIf { it.isNotEmpty() }
    val subtitleDecision = resolveSubtitleDecision(playState, transcoding)

    val rawBitrate = transcoding?.bitrate ?: mediaSource?.bitrate ?: videoStream?.bitRate
    val bitrateKbps = rawBitrate?.takeIf { it > 0 }?.let { it / BITS_PER_KILOBIT }

    return StreamTechnicalInfo(
        decision = overallDecision,
        videoDecision = videoDecision,
        audioDecision = audioDecision,
        isHwTranscode = isHwTranscode,
        videoCodec = srcVideoCodec,
        transcodeVideoCodec = transcoding?.videoCodec?.trim()?.takeIf {
            it.isNotEmpty() && videoDecision == StreamDecision.TRANSCODE
        },
        audioCodec = srcAudioCodec,
        transcodeAudioCodec = transcoding?.audioCodec?.trim()?.takeIf {
            it.isNotEmpty() && audioDecision == StreamDecision.TRANSCODE
        },
        container = srcContainer,
        transcodeContainer = transcoding?.container?.trim()?.takeIf {
            it.isNotEmpty() && overallDecision != StreamDecision.DIRECT_PLAY
        },
        subtitleDecision = subtitleDecision,
        videoResolution = formatResolution(videoStream?.width, videoStream?.height),
        bitrateKbps = bitrateKbps,
        transcodeProgress = transcoding?.completionPercentage,
        transcodeSpeed = null,
    )
}

private fun resolveDecision(
    playMethod: String?,
    transcoding: JellyfinTranscodingInfoDto?,
): StreamDecision =
    when (playMethod?.trim()?.lowercase()) {
        "directplay" -> StreamDecision.DIRECT_PLAY
        "directstream" -> StreamDecision.DIRECT_STREAM
        "transcode" -> StreamDecision.TRANSCODE
        else ->
            when {
                transcoding == null -> StreamDecision.DIRECT_PLAY
                transcoding.isVideoDirect == false || transcoding.isAudioDirect == false -> StreamDecision.TRANSCODE
                else -> StreamDecision.DIRECT_STREAM
            }
    }

private fun resolveSubtitleDecision(
    playState: JellyfinPlayStateDto?,
    transcoding: JellyfinTranscodingInfoDto?,
): String? {
    val subIdx = playState?.subtitleStreamIndex
    if (subIdx == null || subIdx < 0) return null
    val reasons = transcoding?.transcodeReasons.orEmpty()
    return when {
        reasons.any { it.contains("Subtitle", ignoreCase = true) } -> "burn"
        transcoding != null -> "copy"
        else -> "copy"
    }
}

private fun resolvePrimaryImagePath(item: JellyfinBaseItemDto): String? {
    val id = item.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (item.imageTags?.containsKey("Primary") == true) {
        return "/Items/$id/Images/Primary"
    }
    val seriesId = item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
    if (seriesId != null && !item.seriesPrimaryImageTag.isNullOrBlank()) {
        return "/Items/$seriesId/Images/Primary"
    }
    val albumId = item.albumId?.trim()?.takeIf { it.isNotEmpty() }
    if (albumId != null && !item.albumPrimaryImageTag.isNullOrBlank()) {
        return "/Items/$albumId/Images/Primary"
    }
    return null
}

private fun resolveBackdropImagePath(item: JellyfinBaseItemDto): String? {
    val id = item.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!item.backdropImageTags.isNullOrEmpty()) {
        return "/Items/$id/Images/Backdrop"
    }
    val parentBackdropId = item.parentBackdropItemId?.trim()?.takeIf { it.isNotEmpty() }
    if (parentBackdropId != null && !item.parentBackdropImageTags.isNullOrEmpty()) {
        return "/Items/$parentBackdropId/Images/Backdrop"
    }
    val seriesId = item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
    if (seriesId != null) {
        return "/Items/$seriesId/Images/Backdrop"
    }
    return null
}

private fun selectMediaSource(
    item: JellyfinBaseItemDto,
    mediaSourceId: String?,
): JellyfinMediaSourceDto? {
    val sources = item.mediaSources.orEmpty()
    if (sources.isEmpty()) return null
    val normalizedId = mediaSourceId?.trim()?.takeIf { it.isNotEmpty() }
    return normalizedId?.let { id -> sources.firstOrNull { it.id == id } } ?: sources.firstOrNull()
}

private fun selectStream(
    streams: List<JellyfinMediaStreamDto>,
    type: String,
    preferredIndex: Int?,
): JellyfinMediaStreamDto? {
    val matching = streams.filter { it.type.equals(type, ignoreCase = true) }
    if (matching.isEmpty()) return null
    if (preferredIndex != null) {
        matching.firstOrNull { it.index == preferredIndex }?.let { return it }
    }
    return matching.firstOrNull { it.isDefault == true } ?: matching.firstOrNull()
}

private fun computeViewedLeafCount(item: JellyfinBaseItemDto): Int? {
    val total = item.recursiveItemCount ?: return null
    val unplayed = item.userData?.unplayedItemCount ?: return null
    return (total - unplayed).coerceAtLeast(0)
}

private fun formatResolution(
    width: Int?,
    height: Int?,
): String? {
    val w = width ?: 0
    val h = height ?: 0
    if (w <= 0 && h <= 0) return null
    return when {
        w >= WIDTH_8K_THRESHOLD || h >= HEIGHT_8K_THRESHOLD -> "8k"
        w >= WIDTH_4K_THRESHOLD || h >= HEIGHT_4K_THRESHOLD -> "4k"
        w >= WIDTH_1080P_THRESHOLD || h >= HEIGHT_1080P_THRESHOLD -> "1080p"
        w >= WIDTH_720P_THRESHOLD || h >= HEIGHT_720P_THRESHOLD -> "720p"
        h > 0 -> "${h}p"
        else -> null
    }
}

private fun parseYearFromIso(raw: String?): Int? {
    val trimmed = raw?.trim()?.takeIf { it.length >= 4 } ?: return null
    return trimmed.substring(0, 4).toIntOrNull()
}

private fun parseEpochSeconds(raw: String?): Long? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(trimmed).epochSecond }.getOrNull()
}
