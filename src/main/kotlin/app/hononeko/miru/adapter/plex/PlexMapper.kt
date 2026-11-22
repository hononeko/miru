package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.LibraryItemInfo
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser

private const val STREAM_TYPE_VIDEO = 1
private const val STREAM_TYPE_AUDIO = 2
private const val LOCAL_OWNER_ACCOUNT_ID = 1L
private const val LOCAL_OWNER_USER_ID = "1"

object PlexMapper {
    fun mapServerIdentity(container: PlexServerInfoContainer): ServerIdentity {
        val machineId = container.machineIdentifier?.takeIf { it.isNotBlank() } ?: "unknown"
        val friendlyName =
            container.friendlyName?.takeIf { it.isNotBlank() }
                ?: container.machineIdentifier?.takeIf { it.isNotBlank() }
                ?: "Plex Media Server"
        val version = container.version?.takeIf { it.isNotBlank() } ?: "unknown"
        val platform = container.platform?.takeIf { it.isNotBlank() }

        return ServerIdentity(
            machineIdentifier = machineId,
            friendlyName = friendlyName,
            version = version,
            platform = platform,
        )
    }

    fun mapSessions(
        container: PlexSessionsContainer,
        ownerUser: MediaUser? = null,
    ): List<ActiveStreamSnapshot> =
        container.metadata
            .orEmpty()
            .map { metadata -> mapSession(metadata = metadata, ownerUser = ownerUser) }

    fun mapSession(
        metadata: PlexMetadataDto,
        ownerUser: MediaUser? = null,
    ): ActiveStreamSnapshot {
        val mediaType = mapMediaType(metadata)
        val selectedMedia = selectPrimaryMedia(metadata.media)
        val selectedPart = selectPrimaryPart(selectedMedia?.part)
        val videoStream = selectPrimaryStream(selectedPart?.stream, STREAM_TYPE_VIDEO)
        val audioStream = selectPrimaryStream(selectedPart?.stream, STREAM_TYPE_AUDIO)
        val location = mapStreamLocation(metadata.session?.location, metadata.player?.local)

        return ActiveStreamSnapshot(
            sessionId = resolveSessionId(metadata),
            sessionKey = metadata.sessionKey?.takeIf { it.isNotBlank() },
            state = mapPlaybackState(metadata.player?.state),
            viewOffsetMs = metadata.viewOffset ?: 0L,
            media = mapStreamMedia(metadata, mediaType, selectedMedia, selectedPart),
            user = mapStreamUser(metadata.user, ownerUser),
            player = mapStreamPlayer(metadata.player, location),
            technical =
                mapStreamTechnicalInfo(
                    metadata = metadata,
                    selectedMedia = selectedMedia,
                    selectedPart = selectedPart,
                    videoStream = videoStream,
                    audioStream = audioStream,
                ),
        )
    }

    fun mapUsers(
        accounts: List<PlexAccountDto>,
        plexTvUser: PlexTvUserDto? = null,
        plexTvFriends: List<PlexTvFriendDto> = emptyList(),
    ): List<MediaUser> {
        val friendsById = plexTvFriends.associateBy { it.id }
        val seenIds = linkedSetOf<String>()
        val users = mutableListOf<MediaUser>()

        accounts
            .filter { it.id > 0L }
            .forEach { account ->
                val mapped =
                    if (account.id == LOCAL_OWNER_ACCOUNT_ID) {
                        mergeOwnerAccount(account = account, plexTvUser = plexTvUser)
                    } else {
                        mergeFriendAccount(account = account, friend = friendsById[account.id])
                    }
                if (seenIds.add(mapped.externalId)) {
                    users += mapped
                }
            }

        if (plexTvUser != null && plexTvUser.id > 0L && seenIds.add(plexTvUser.id.toString())) {
            users += mergeOwnerAccount(account = null, plexTvUser = plexTvUser)
        }

        plexTvFriends
            .filter { it.id > 0L }
            .forEach { friend ->
                if (seenIds.add(friend.id.toString())) {
                    users += mergeFriendAccount(account = null, friend = friend)
                }
            }

        return users
    }

    fun resolveOwnerUser(
        accounts: List<PlexAccountDto>,
        plexTvUser: PlexTvUserDto? = null,
    ): MediaUser? {
        val ownerAccount = accounts.firstOrNull { it.id == LOCAL_OWNER_ACCOUNT_ID }
        if (ownerAccount == null && plexTvUser == null) return null
        return mergeOwnerAccount(account = ownerAccount, plexTvUser = plexTvUser)
    }

    fun mapLibrarySections(container: PlexLibrarySectionsContainer): List<LibrarySectionInfo> =
        container.directory
            .orEmpty()
            .mapNotNull { directory ->
                val key = directory.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LibrarySectionInfo(
                    sectionKey = key,
                    title = directory.title?.takeIf { it.isNotBlank() } ?: "Library $key",
                    type = mapSectionType(directory.type),
                    count = directory.count,
                )
            }

    fun mapLibraryItemsPage(
        sectionKey: String,
        requestedStart: Int,
        container: PlexLibraryItemsContainer,
    ): LibraryItemsPage {
        val items = container.metadata.orEmpty().map { mapLibraryItem(sectionKey, it) }
        val totalSize = container.totalSize ?: container.size ?: items.size
        val offset = container.offset ?: requestedStart
        return LibraryItemsPage(
            totalSize = totalSize,
            offset = offset,
            items = items,
        )
    }

    fun mapLibraryItem(
        sectionKey: String,
        metadata: PlexMetadataDto,
    ): LibraryItemInfo {
        val primaryMedia = selectPrimaryMedia(metadata.media)
        val primaryPart = selectPrimaryPart(primaryMedia?.part)
        val videoStream = selectPrimaryStream(primaryPart?.stream, STREAM_TYPE_VIDEO)
        val audioStream = selectPrimaryStream(primaryPart?.stream, STREAM_TYPE_AUDIO)
        val fileSizeBytes =
            primaryMedia?.part
                ?.mapNotNull { it.size }
                ?.takeIf { it.isNotEmpty() }
                ?.sum()

        return LibraryItemInfo(
            ratingKey = metadata.ratingKey.orEmpty(),
            sectionKey = metadata.librarySectionID?.takeIf { it.isNotBlank() } ?: sectionKey,
            mediaType = mapMediaType(metadata),
            title = metadata.title?.takeIf { it.isNotBlank() } ?: "Unknown",
            parentTitle = metadata.parentTitle?.takeIf { it.isNotBlank() },
            grandparentTitle = metadata.grandparentTitle?.takeIf { it.isNotBlank() },
            year = metadata.year,
            thumbPath = metadata.thumb?.takeIf { it.isNotBlank() },
            durationMs = metadata.duration ?: primaryMedia?.duration ?: primaryPart?.duration,
            fileSizeBytes = fileSizeBytes,
            videoResolution = primaryMedia?.videoResolution?.takeIf { it.isNotBlank() },
            videoCodec = primaryMedia?.videoCodec?.takeIf { it.isNotBlank() } ?: videoStream?.codec?.takeIf { it.isNotBlank() },
            audioCodec = primaryMedia?.audioCodec?.takeIf { it.isNotBlank() } ?: audioStream?.codec?.takeIf { it.isNotBlank() },
            addedAtEpochSeconds = metadata.addedAt,
        )
    }
}

private fun mapStreamMedia(
    metadata: PlexMetadataDto,
    mediaType: MediaType,
    selectedMedia: PlexMediaDto?,
    selectedPart: PlexPartDto?,
): StreamMedia {
    val title =
        metadata.title?.takeIf { it.isNotBlank() }
            ?: metadata.channelTitle?.takeIf { it.isNotBlank() }
            ?: metadata.channelCallSign?.takeIf { it.isNotBlank() }
            ?: "Unknown"

    val grandparentTitle =
        metadata.grandparentTitle?.takeIf { it.isNotBlank() }
            ?: if (mediaType == MediaType.LIVE_TV) {
                metadata.channelCallSign?.takeIf { it.isNotBlank() }
                    ?: metadata.channelTitle?.takeIf { it.isNotBlank() && it != title }
            } else {
                null
            }

    return StreamMedia(
        ratingKey = metadata.ratingKey.orEmpty(),
        mediaType = mediaType,
        title = title,
        parentTitle = metadata.parentTitle?.takeIf { it.isNotBlank() },
        grandparentTitle = grandparentTitle,
        parentIndex = metadata.parentIndex,
        index = metadata.index,
        year = metadata.year,
        durationMs = metadata.duration ?: selectedMedia?.duration ?: selectedPart?.duration,
        thumbPath = metadata.thumb?.takeIf { it.isNotBlank() },
        grandparentThumbPath = metadata.grandparentThumb?.takeIf { it.isNotBlank() },
        artPath = metadata.art?.takeIf { it.isNotBlank() },
        librarySectionId = metadata.librarySectionID?.takeIf { it.isNotBlank() },
    )
}

private fun mapStreamUser(
    user: PlexSessionUserDto?,
    ownerUser: MediaUser?,
): StreamUser {
    if (user == null) {
        return StreamUser(externalId = "unknown", username = "Unknown", thumbUrl = null)
    }

    val rawId = user.id?.takeIf { it.isNotBlank() }
    if (rawId == LOCAL_OWNER_USER_ID && ownerUser != null) {
        val resolvedUsername =
            ownerUser.username.ifBlank {
                user.title?.takeIf { it.isNotBlank() } ?: "admin"
            }
        return StreamUser(
            externalId = ownerUser.externalId,
            username = resolvedUsername,
            thumbUrl = user.thumb?.takeIf { it.isNotBlank() } ?: ownerUser.thumbUrl,
        )
    }

    val fallbackName = if (rawId == LOCAL_OWNER_USER_ID) "admin" else "Unknown"
    return StreamUser(
        externalId = rawId ?: "unknown",
        username = user.title?.takeIf { it.isNotBlank() } ?: fallbackName,
        thumbUrl = user.thumb?.takeIf { it.isNotBlank() },
    )
}

private fun mapStreamPlayer(
    player: PlexPlayerDto?,
    location: StreamLocation,
): StreamPlayer =
    StreamPlayer(
        machineIdentifier = player?.machineIdentifier?.takeIf { it.isNotBlank() } ?: "unknown",
        product = player?.product?.takeIf { it.isNotBlank() } ?: "Unknown",
        platform = player?.platform?.takeIf { it.isNotBlank() },
        device = player?.device?.takeIf { it.isNotBlank() },
        title = player?.title?.takeIf { it.isNotBlank() },
        address = player?.address?.takeIf { it.isNotBlank() },
        location = location,
        isLocal = player?.local ?: (location == StreamLocation.LAN),
    )

private fun mapStreamTechnicalInfo(
    metadata: PlexMetadataDto,
    selectedMedia: PlexMediaDto?,
    selectedPart: PlexPartDto?,
    videoStream: PlexStreamDto?,
    audioStream: PlexStreamDto?,
): StreamTechnicalInfo {
    val ts = metadata.transcodeSession
    val videoDecision = parseStreamDecision(ts?.videoDecision ?: videoStream?.decision)
    val audioDecision = parseStreamDecision(ts?.audioDecision ?: audioStream?.decision)
    val overallDecision = resolveOverallDecision(ts, selectedPart, videoDecision, audioDecision)

    val isHwTranscode =
        ts != null &&
            overallDecision == StreamDecision.TRANSCODE &&
            (
                !ts.transcodeHwEncoding.isNullOrBlank() ||
                    ts.transcodeHwFullPipeline == true ||
                    (
                        ts.transcodeHwRequested == true &&
                            ts.transcodeHwFullPipeline != false &&
                            ts.transcodeHwEncoding == null
                    )
            )

    val resolution =
        selectedMedia?.videoResolution?.takeIf { it.isNotBlank() }
            ?: videoStream?.height?.let { "${it}p" }

    return StreamTechnicalInfo(
        decision = overallDecision,
        videoDecision = videoDecision,
        audioDecision = audioDecision,
        isHwTranscode = isHwTranscode,
        videoCodec = selectedMedia?.videoCodec?.takeIf { it.isNotBlank() } ?: videoStream?.codec?.takeIf { it.isNotBlank() },
        transcodeVideoCodec = ts?.videoCodec?.takeIf { it.isNotBlank() },
        audioCodec = selectedMedia?.audioCodec?.takeIf { it.isNotBlank() } ?: audioStream?.codec?.takeIf { it.isNotBlank() },
        transcodeAudioCodec = ts?.audioCodec?.takeIf { it.isNotBlank() },
        container = selectedMedia?.container?.takeIf { it.isNotBlank() } ?: selectedPart?.container?.takeIf { it.isNotBlank() },
        transcodeContainer = ts?.container?.takeIf { it.isNotBlank() },
        videoResolution = resolution,
        bitrateKbps = metadata.session?.bandwidth ?: selectedMedia?.bitrate ?: videoStream?.bitrate,
        transcodeProgress = ts?.progress,
        transcodeSpeed = ts?.speed,
    )
}

private fun resolveOverallDecision(
    transcodeSession: PlexTranscodeSessionDto?,
    selectedPart: PlexPartDto?,
    videoDecision: StreamDecision?,
    audioDecision: StreamDecision?,
): StreamDecision {
    val partDecision = parseStreamDecision(selectedPart?.decision)
    return when {
        videoDecision == StreamDecision.TRANSCODE || audioDecision == StreamDecision.TRANSCODE -> StreamDecision.TRANSCODE
        transcodeSession != null && videoDecision == null && audioDecision == null && partDecision == StreamDecision.TRANSCODE ->
            StreamDecision.TRANSCODE
        transcodeSession != null -> StreamDecision.DIRECT_STREAM
        videoDecision == StreamDecision.DIRECT_STREAM || audioDecision == StreamDecision.DIRECT_STREAM -> StreamDecision.DIRECT_STREAM
        partDecision != null -> partDecision
        else -> StreamDecision.DIRECT_PLAY
    }
}

private fun mergeOwnerAccount(
    account: PlexAccountDto?,
    plexTvUser: PlexTvUserDto?,
): MediaUser {
    val externalId = plexTvUser?.id?.toString() ?: account?.id?.toString() ?: LOCAL_OWNER_USER_ID
    val username =
        plexTvUser?.username?.takeIf { it.isNotBlank() }
            ?: account?.name?.takeIf { it.isNotBlank() }
            ?: plexTvUser?.title?.takeIf { it.isNotBlank() }
            ?: "admin"
    val displayName =
        plexTvUser?.title?.takeIf { it.isNotBlank() }
            ?: account?.name?.takeIf { it.isNotBlank() }
            ?: username
    val thumbUrl =
        plexTvUser?.thumb?.trim()?.takeIf { it.isNotEmpty() }
            ?: account?.thumb?.trim()?.takeIf { it.isNotEmpty() }
    return MediaUser(
        externalId = externalId,
        username = username,
        displayName = displayName,
        email = plexTvUser?.email?.trim()?.takeIf { it.isNotEmpty() },
        thumbUrl = thumbUrl,
        isAdmin = true,
    )
}

private fun mergeFriendAccount(
    account: PlexAccountDto?,
    friend: PlexTvFriendDto?,
): MediaUser {
    val externalId = account?.id?.toString() ?: friend?.id?.toString() ?: "unknown"
    val username =
        friend?.username?.takeIf { it.isNotBlank() }
            ?: account?.name?.takeIf { it.isNotBlank() }
            ?: friend?.title?.takeIf { it.isNotBlank() }
            ?: externalId
    val displayName =
        friend?.title?.takeIf { it.isNotBlank() }
            ?: account?.name?.takeIf { it.isNotBlank() }
            ?: username
    val thumbUrl =
        friend?.thumb?.trim()?.takeIf { it.isNotEmpty() }
            ?: account?.thumb?.trim()?.takeIf { it.isNotEmpty() }
    return MediaUser(
        externalId = externalId,
        username = username,
        displayName = displayName,
        email = friend?.email?.trim()?.takeIf { it.isNotEmpty() },
        thumbUrl = thumbUrl,
        isAdmin = false,
    )
}

private fun mapMediaType(metadata: PlexMetadataDto): MediaType {
    val isLive =
        metadata.live == true ||
            !metadata.channelIdentifier.isNullOrBlank() ||
            !metadata.channelCallSign.isNullOrBlank() ||
            !metadata.channelTitle.isNullOrBlank()
    if (isLive) return MediaType.LIVE_TV

    return when (metadata.type?.trim()?.lowercase()) {
        "movie" -> MediaType.MOVIE
        "episode" -> MediaType.EPISODE
        "track" -> MediaType.TRACK
        "clip" -> MediaType.CLIP
        "live", "channel" -> MediaType.LIVE_TV
        else -> MediaType.UNKNOWN
    }
}

private fun mapSectionType(raw: String?): LibrarySectionType =
    when (raw?.trim()?.lowercase()) {
        "movie" -> LibrarySectionType.MOVIE
        "show" -> LibrarySectionType.SHOW
        "artist" -> LibrarySectionType.ARTIST
        "photo" -> LibrarySectionType.PHOTO
        else -> LibrarySectionType.OTHER
    }

private fun mapPlaybackState(raw: String?): PlaybackState =
    when (raw?.trim()?.lowercase()) {
        "playing" -> PlaybackState.PLAYING
        "paused" -> PlaybackState.PAUSED
        "buffering" -> PlaybackState.BUFFERING
        "stopped" -> PlaybackState.STOPPED
        else -> PlaybackState.PLAYING
    }

private fun mapStreamLocation(
    rawLocation: String?,
    playerLocal: Boolean?,
): StreamLocation =
    when (rawLocation?.trim()?.lowercase()) {
        "lan" -> StreamLocation.LAN
        "wan" -> StreamLocation.WAN
        else -> if (playerLocal == true) StreamLocation.LAN else StreamLocation.WAN
    }

private fun parseStreamDecision(raw: String?): StreamDecision? =
    when (raw?.trim()?.lowercase()) {
        "directplay", "direct play", "direct_play" -> StreamDecision.DIRECT_PLAY
        "copy", "directstream", "direct stream", "direct_stream" -> StreamDecision.DIRECT_STREAM
        "transcode" -> StreamDecision.TRANSCODE
        else -> null
    }

private fun resolveSessionId(metadata: PlexMetadataDto): String =
    metadata.session?.id?.takeIf { it.isNotBlank() }
        ?: metadata.sessionKey?.takeIf { it.isNotBlank() }
        ?: "${metadata.ratingKey.orEmpty()}:${metadata.player?.machineIdentifier.orEmpty()}"

private fun selectPrimaryMedia(media: List<PlexMediaDto>?): PlexMediaDto? =
    media?.firstOrNull { it.selected == true } ?: media?.firstOrNull()

private fun selectPrimaryPart(parts: List<PlexPartDto>?): PlexPartDto? =
    parts?.firstOrNull { it.selected == true } ?: parts?.firstOrNull()

private fun selectPrimaryStream(
    streams: List<PlexStreamDto>?,
    streamType: Int,
): PlexStreamDto? =
    streams?.firstOrNull { it.streamType == streamType && it.selected == true }
        ?: streams?.firstOrNull { it.streamType == streamType }
