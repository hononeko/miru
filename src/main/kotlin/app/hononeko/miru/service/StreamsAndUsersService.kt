package app.hononeko.miru.service

import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.ActiveStreamDto
import app.hononeko.miru.http.ActiveStreamsResponse
import app.hononeko.miru.http.DecisionBreakdownDto
import app.hononeko.miru.http.LibraryBreakdownDto
import app.hononeko.miru.http.StreamMediaDto
import app.hononeko.miru.http.StreamPlayerDto
import app.hononeko.miru.http.StreamTechnicalDto
import app.hononeko.miru.http.StreamUserDto
import app.hononeko.miru.http.UserDetailResponse
import app.hononeko.miru.http.UserRecentPlayDto
import app.hononeko.miru.http.UserSummaryDto
import app.hononeko.miru.http.UserUsageStatDto
import app.hononeko.miru.http.UsersListResponse
import app.hononeko.miru.http.buildThumbUrl
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.ViewerIdentityResolver.avatarProxyUrl
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

private const val PERCENT_SCALE = 100.0
private const val RECENT_PLAYS_LIMIT = 10
private const val TOP_STATS_LIMIT = 10
private const val UNKNOWN_LABEL = "Unknown"

/**
 * Application service backing the live streams and media users REST + SSE endpoints.
 */
@Suppress("LongParameterList")
class StreamsAndUsersService(
    private val activeSessionRepository: ActiveSessionRepository,
    private val userRepository: UserRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val liveTechnicalProvider: suspend () -> Map<ActiveSessionKey, StreamTechnicalInfo> = { emptyMap() },
    private val serverNameProvider: suspend () -> String? = { null },
    private val mediaServerRepository: MediaServerRepository? = null,
    private val authAccountRepository: AuthAccountRepository? = null,
) {
    suspend fun listActiveStreams(
        principal: MiruPrincipal,
        serverId: String? = null,
    ): Either<MiruError, ActiveStreamsResponse> =
        either {
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val serversById = loadServersById()
            val selectedServer = normalizedServerId?.let { serversById[it] }
            val serverName =
                selectedServer?.name?.normalizedDisplayName()
                    ?: serverNameProvider()?.trim()?.takeIf { it.isNotEmpty() }
            if (!principal.isAdmin() && principal.linkedMediaUserIds.isEmpty()) {
                return@either ActiveStreamsResponse(streams = emptyList(), totalCount = 0, serverName = serverName)
            }
            val sessions =
                activeSessionRepository
                    .findAll()
                    .bind()
                    .filter { session ->
                        principal.canAccessMediaUser(session.userId) &&
                            (normalizedServerId == null || session.serverId == normalizedServerId)
                    }

            if (sessions.isEmpty()) {
                return@either ActiveStreamsResponse(streams = emptyList(), totalCount = 0, serverName = serverName)
            }

            val usersById = userRepository.findAll().bind().associateBy { it.id }
            val identitiesByUserId =
                ViewerIdentityResolver
                    .resolveMediaUserIdentities(
                        userRepository = userRepository,
                        authAccountRepository = authAccountRepository,
                        mediaUserIds = sessions.map { it.userId },
                        preloadedUsersById = usersById,
                    ).bind()
            val liveTechByKey = liveTechnicalProvider()
            val streams =
                sessions.map { session ->
                    toActiveStreamDto(
                        session = session,
                        identity = identitiesByUserId[session.userId],
                        liveTech = liveTechByKey[session.key],
                        server = serversById[session.serverId],
                    )
                }
            ActiveStreamsResponse(streams = streams, totalCount = streams.size, serverName = serverName)
        }

    suspend fun toActiveStreamDto(session: ActiveSessionRecord): Either<MiruError, ActiveStreamDto> =
        either {
            val identity =
                ViewerIdentityResolver
                    .resolveMediaUserIdentities(
                        userRepository = userRepository,
                        authAccountRepository = authAccountRepository,
                        mediaUserIds = listOf(session.userId),
                    ).bind()[session.userId]
            val liveTech = liveTechnicalProvider()[session.key]
            val server = mediaServerRepository?.findById(session.serverId)?.getOrNull()
            toActiveStreamDto(session = session, identity = identity, liveTech = liveTech, server = server)
        }

    private fun toActiveStreamDto(
        session: ActiveSessionRecord,
        identity: ResolvedMediaUserIdentity?,
        liveTech: StreamTechnicalInfo?,
        server: MediaServerRecord? = null,
    ): ActiveStreamDto {
        val user = identity?.user
        val durationMs = session.media.durationMs
        val progressPercent =
            if (durationMs > 0L) {
                ((session.viewOffsetMs.toDouble() / durationMs.toDouble()) * PERCENT_SCALE).coerceIn(0.0, PERCENT_SCALE)
            } else {
                0.0
            }

        return ActiveStreamDto(
            serverId = session.serverId,
            serverName = server?.name?.normalizedDisplayName(),
            serverType = server?.serverType?.lowercase(),
            sessionKey = session.sessionKey,
            sessionId = session.sessionId,
            user =
                StreamUserDto(
                    id = session.userId,
                    username = user?.username,
                    displayName = identity?.displayName ?: user?.displayName.normalizedDisplayName(),
                    avatarUrl = identity?.avatarUrl ?: user?.avatarProxyUrl(),
                ),
            media =
                StreamMediaDto(
                    ratingKey = session.media.ratingKey,
                    mediaType = session.media.mediaType,
                    title = session.media.title,
                    parentTitle = session.media.parentTitle,
                    grandparentTitle = session.media.grandparentTitle,
                    parentIndex = session.media.parentIndex,
                    mediaIndex = session.media.mediaIndex,
                    year = session.media.year,
                    thumbUrl = buildThumbUrl(session.media.ratingKey, session.media.thumbPath),
                    librarySectionKey = session.media.librarySectionKey,
                    durationMs = durationMs,
                ),
            player =
                StreamPlayerDto(
                    name = session.player.playerName,
                    platform = session.player.playerPlatform,
                    product = session.player.playerProduct,
                    machineId = session.player.playerMachineId,
                    ipAddress = session.player.ipAddress,
                    location = session.player.location,
                ),
            technical =
                StreamTechnicalDto(
                    streamDecision = session.streamTech.streamDecision,
                    videoDecision = session.streamTech.videoDecision,
                    audioDecision = session.streamTech.audioDecision,
                    hwTranscode = session.streamTech.hwTranscode,
                    videoCodec = session.streamTech.videoCodec,
                    audioCodec = session.streamTech.audioCodec,
                    videoResolution = session.streamTech.videoResolution,
                    bandwidthKbps = session.streamTech.bandwidthKbps,
                    container = liveTech?.container ?: session.streamTech.container,
                    transcodeVideoCodec = liveTech?.transcodeVideoCodec ?: session.streamTech.transcodeVideoCodec,
                    transcodeAudioCodec = liveTech?.transcodeAudioCodec ?: session.streamTech.transcodeAudioCodec,
                    transcodeContainer = liveTech?.transcodeContainer ?: session.streamTech.transcodeContainer,
                    transcodeProgress = liveTech?.transcodeProgress,
                    transcodeSpeed = liveTech?.transcodeSpeed,
                    throttled = liveTech?.transcodeSpeed?.let { it < 1.0 } ?: false,
                ),
            state = session.state,
            viewOffsetMs = session.viewOffsetMs,
            progressPercent = progressPercent,
            startedAtMs = session.startedAtMs,
            lastSeenAtMs = session.lastSeenAtMs,
            pausedDurationSec = session.pausedDurationSec,
        )
    }

    suspend fun listUsers(
        principal: MiruPrincipal,
        serverId: String? = null,
        merged: Boolean = true,
    ): Either<MiruError, UsersListResponse> =
        either {
            principal.requireAdmin().bind()

            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val users =
                if (normalizedServerId != null) {
                    userRepository.findByServer(normalizedServerId).bind()
                } else {
                    userRepository.findAll().bind()
                }

            if (users.isEmpty()) {
                return@either UsersListResponse(users = emptyList())
            }

            val serversById = loadServersById()
            val linksByMediaUser = authAccountRepository?.findAllMediaUserLinks()?.bind().orEmpty()
            val accountProfilesById =
                if (merged && authAccountRepository != null) {
                    authAccountRepository.findAllAccountProfiles().bind()
                } else {
                    emptyMap()
                }
            val activeCountByUser =
                activeSessionRepository
                    .findAll()
                    .bind()
                    .filter { normalizedServerId == null || it.serverId == normalizedServerId }
                    .groupingBy { it.userId }
                    .eachCount()

            val userIds = users.mapTo(HashSet(users.size)) { it.id }
            val summariesByUser = playbackHistoryRepository.summarizeByUser(userIds).bind()

            val items =
                if (normalizedServerId == null && merged) {
                    users
                        .groupBy { user -> linksByMediaUser[user.id] ?: "unlinked:${user.id}" }
                        .values
                        .map { group ->
                            val ordered = group.sortedWith(ViewerIdentityResolver.stableProfileComparator)
                            val accountId = linksByMediaUser[ordered.first().id]
                            ViewerIdentityResolver.buildUserSummaryDto(
                                id = accountId ?: ordered.first().id,
                                accountId = accountId,
                                orderedProfiles = ordered,
                                serversById = serversById,
                                activeStreamCount = ordered.sumOf { activeCountByUser[it.id] ?: 0 },
                                totalPlays = ordered.sumOf { summariesByUser[it.id]?.totalPlays ?: 0L },
                                totalWatchDurationSec = ordered.sumOf { summariesByUser[it.id]?.totalWatchDurationSec ?: 0L },
                                accountProfile = accountId?.let { accountProfilesById[it] },
                            )
                        }.sortedWith(
                            compareBy<UserSummaryDto> { (it.displayName ?: it.username).lowercase() }
                                .thenBy { it.id },
                        )
                } else {
                    users.map { user ->
                        val summary = summariesByUser[user.id]
                        val accountId = linksByMediaUser[user.id]
                        ViewerIdentityResolver.buildUserSummaryDto(
                            id = user.id,
                            accountId = accountId,
                            orderedProfiles = listOf(user),
                            serversById = serversById,
                            activeStreamCount = activeCountByUser[user.id] ?: 0,
                            totalPlays = summary?.totalPlays ?: 0L,
                            totalWatchDurationSec = summary?.totalWatchDurationSec ?: 0L,
                            accountProfile = if (merged) accountId?.let { accountProfilesById[it] } else null,
                        )
                    }
                }
            UsersListResponse(users = items)
        }

    suspend fun getUserDetail(
        principal: MiruPrincipal,
        userId: String,
        serverId: String? = null,
    ): Either<MiruError, UserDetailResponse> =
        either {
            val trimmedUserId = userId.trim()
            ensure(trimmedUserId.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid user id",
                    fieldErrors = mapOf("id" to "must not be blank"),
                )
            }
            ViewerIdentityResolver.requireTargetViewerAccess(principal, trimmedUserId).bind()

            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val resolved =
                ViewerIdentityResolver
                    .resolveViewer(
                        userRepository = userRepository,
                        authAccountRepository = authAccountRepository,
                        targetId = trimmedUserId,
                        serverId = normalizedServerId,
                    ).bind()

            val serversById = loadServersById()
            val profilesById = resolved.allProfiles.associateBy { it.id }
            val userSessions =
                activeSessionRepository
                    .findAll()
                    .bind()
                    .filter { session ->
                        session.userId in resolved.targetMediaUserIds &&
                            (normalizedServerId == null || session.serverId == normalizedServerId)
                    }
            val liveTechByKey = if (userSessions.isEmpty()) emptyMap() else liveTechnicalProvider()
            val activeStreams =
                userSessions.map { session ->
                    val streamUser = profilesById[session.userId] ?: resolved.primaryProfile
                    toActiveStreamDto(
                        session = session,
                        identity =
                            ResolvedMediaUserIdentity(
                                user = streamUser,
                                displayName = resolved.primaryDisplayName,
                                avatarUrl = resolved.avatarUrl ?: streamUser.avatarProxyUrl(),
                            ),
                        liveTech = liveTechByKey[session.key],
                        server = serversById[session.serverId],
                    )
                }

            val history =
                playbackHistoryRepository
                    .listByUsers(resolved.targetMediaUserIds, serverId = normalizedServerId)
                    .bind()
            val totalPlays = history.size.toLong()
            val totalWatchDurationSec = history.sumOf { it.watchedDurationSec.toLong() }

            UserDetailResponse(
                user =
                    ViewerIdentityResolver.buildUserSummaryDto(
                        id = resolved.canonicalId,
                        accountId = resolved.accountId,
                        orderedProfiles = resolved.scopedProfiles.ifEmpty { resolved.allProfiles },
                        serversById = serversById,
                        activeStreamCount = activeStreams.size,
                        totalPlays = totalPlays,
                        totalWatchDurationSec = totalWatchDurationSec,
                        linkedProfileRecords = resolved.scopedProfiles,
                        accountProfile = resolved.accountProfile,
                    ),
                activeStreams = activeStreams,
                totalPlays = totalPlays,
                totalWatchDurationSec = totalWatchDurationSec,
                recentPlays = history.take(RECENT_PLAYS_LIMIT).map { it.toRecentPlayDto(serversById[it.serverId]) },
                topClients = aggregateUsage(history) { it.player.playerProduct ?: it.player.playerName ?: UNKNOWN_LABEL },
                topPlatforms = aggregateUsage(history) { it.player.playerPlatform ?: UNKNOWN_LABEL },
                decisionBreakdown = computeDecisionBreakdown(history),
                libraryBreakdown = computeLibraryBreakdown(history),
            )
        }

    private suspend fun loadServersById(): Map<String, MediaServerRecord> =
        mediaServerRepository
            ?.findAll()
            ?.getOrNull()
            .orEmpty()
            .associateBy { it.id }

    private fun PlaybackHistoryRecord.toRecentPlayDto(server: MediaServerRecord? = null): UserRecentPlayDto =
        UserRecentPlayDto(
            id = id,
            serverId = serverId,
            serverName = server?.name?.normalizedDisplayName(),
            serverType = server?.serverType?.lowercase(),
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
            location = player.location,
            startedAtMs = startedAtMs,
            stoppedAtMs = stoppedAtMs,
        )

    private fun aggregateUsage(
        history: List<PlaybackHistoryRecord>,
        keySelector: (PlaybackHistoryRecord) -> String,
    ): List<UserUsageStatDto> =
        history
            .groupBy { keySelector(it).ifBlank { UNKNOWN_LABEL } }
            .map { (name, records) ->
                UserUsageStatDto(
                    name = name,
                    playCount = records.size.toLong(),
                    watchDurationSec = records.sumOf { it.watchedDurationSec.toLong() },
                )
            }.sortedWith(
                compareByDescending<UserUsageStatDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.name },
            ).take(TOP_STATS_LIMIT)

    private fun computeDecisionBreakdown(history: List<PlaybackHistoryRecord>): DecisionBreakdownDto {
        var directPlay = 0L
        var directStream = 0L
        var transcode = 0L
        var hwTranscode = 0L
        for (record in history) {
            when (record.streamTech.streamDecision.lowercase()) {
                "direct_play" -> directPlay += 1L
                "direct_stream" -> directStream += 1L
                "transcode" -> transcode += 1L
            }
            if (record.streamTech.hwTranscode) hwTranscode += 1L
        }
        val total = history.size.toLong()
        return DecisionBreakdownDto(
            directPlay = directPlay,
            directStream = directStream,
            transcode = transcode,
            hwTranscode = hwTranscode,
            total = total,
            directPlayPercent = computeStatsPercent(directPlay, total),
            directStreamPercent = computeStatsPercent(directStream, total),
            transcodePercent = computeStatsPercent(transcode, total),
            hwTranscodePercent = computeStatsPercent(hwTranscode, total),
        )
    }

    private fun computeLibraryBreakdown(history: List<PlaybackHistoryRecord>): List<LibraryBreakdownDto> =
        history
            .groupBy { record -> record.media.librarySectionKey?.takeIf { it.isNotBlank() } }
            .map { (sectionKey, records) ->
                LibraryBreakdownDto(
                    librarySectionKey = sectionKey,
                    playCount = records.size.toLong(),
                    watchDurationSec = records.sumOf { it.watchedDurationSec.toLong() },
                    percent = computeStatsPercent(records.size.toLong(), history.size.toLong()),
                )
            }.sortedWith(
                compareByDescending<LibraryBreakdownDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.librarySectionKey.orEmpty() },
            )
}
