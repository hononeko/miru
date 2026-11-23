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
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
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
class StreamsAndUsersService(
    private val activeSessionRepository: ActiveSessionRepository,
    private val userRepository: UserRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val liveTechnicalProvider: suspend () -> Map<ActiveSessionKey, StreamTechnicalInfo> = { emptyMap() },
    private val serverNameProvider: suspend () -> String? = { null },
) {
    suspend fun listActiveStreams(principal: MiruPrincipal): Either<MiruError, ActiveStreamsResponse> =
        either {
            val serverName = serverNameProvider()?.trim()?.takeIf { it.isNotEmpty() }
            if (!principal.isAdmin() && principal.linkedMediaUserIds.isEmpty()) {
                return@either ActiveStreamsResponse(streams = emptyList(), totalCount = 0, serverName = serverName)
            }
            val sessions =
                activeSessionRepository
                    .findAll()
                    .bind()
                    .filter { principal.canAccessMediaUser(it.userId) }

            if (sessions.isEmpty()) {
                return@either ActiveStreamsResponse(streams = emptyList(), totalCount = 0, serverName = serverName)
            }

            val usersById = userRepository.findAll().bind().associateBy { it.id }
            val liveTechByKey = liveTechnicalProvider()
            val streams =
                sessions.map { session ->
                    toActiveStreamDto(
                        session = session,
                        user = usersById[session.userId],
                        liveTech = liveTechByKey[session.key],
                    )
                }
            ActiveStreamsResponse(streams = streams, totalCount = streams.size, serverName = serverName)
        }

    suspend fun toActiveStreamDto(session: ActiveSessionRecord): Either<MiruError, ActiveStreamDto> =
        either {
            val user = userRepository.findById(session.userId).bind()
            val liveTech = liveTechnicalProvider()[session.key]
            toActiveStreamDto(session = session, user = user, liveTech = liveTech)
        }

    fun toActiveStreamDto(
        session: ActiveSessionRecord,
        user: UserRecord?,
        liveTech: StreamTechnicalInfo?,
    ): ActiveStreamDto {
        val durationMs = session.media.durationMs
        val progressPercent =
            if (durationMs > 0L) {
                ((session.viewOffsetMs.toDouble() / durationMs.toDouble()) * PERCENT_SCALE).coerceIn(0.0, PERCENT_SCALE)
            } else {
                0.0
            }

        return ActiveStreamDto(
            serverId = session.serverId,
            sessionKey = session.sessionKey,
            sessionId = session.sessionId,
            user =
                StreamUserDto(
                    id = session.userId,
                    username = user?.username,
                    displayName = user?.displayName,
                    avatarUrl = user?.avatarProxyUrl(),
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
                    thumbUrl = session.media.thumbPath?.let { mediaThumbProxyUrl(session.media.ratingKey) },
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
                    container = liveTech?.container,
                    transcodeVideoCodec = liveTech?.transcodeVideoCodec,
                    transcodeAudioCodec = liveTech?.transcodeAudioCodec,
                    transcodeContainer = liveTech?.transcodeContainer,
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

    suspend fun listUsers(principal: MiruPrincipal): Either<MiruError, UsersListResponse> =
        either {
            principal.requireAdmin().bind()

            val users = userRepository.findAll().bind()

            if (users.isEmpty()) {
                return@either UsersListResponse(users = emptyList())
            }

            val activeCountByUser =
                activeSessionRepository
                    .findAll()
                    .bind()
                    .groupingBy { it.userId }
                    .eachCount()

            val userIds = users.mapTo(HashSet(users.size)) { it.id }
            val summariesByUser = playbackHistoryRepository.summarizeByUser(userIds).bind()

            val items =
                users.map { user ->
                    val summary = summariesByUser[user.id]
                    user.toSummaryDto(
                        activeStreamCount = activeCountByUser[user.id] ?: 0,
                        totalPlays = summary?.totalPlays ?: 0L,
                        totalWatchDurationSec = summary?.totalWatchDurationSec ?: 0L,
                    )
                }
            UsersListResponse(users = items)
        }

    suspend fun getUserDetail(
        principal: MiruPrincipal,
        userId: String,
    ): Either<MiruError, UserDetailResponse> =
        either {
            val trimmedUserId = userId.trim()
            ensure(trimmedUserId.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid user id",
                    fieldErrors = mapOf("id" to "must not be blank"),
                )
            }
            principal.requireMediaUserAccess(trimmedUserId).bind()

            val user = userRepository.findById(trimmedUserId).bind()
            ensure(user != null) {
                MiruError.NotFound(resource = "User", id = trimmedUserId)
            }

            val userSessions =
                activeSessionRepository
                    .findAll()
                    .bind()
                    .filter { it.userId == trimmedUserId }
            val liveTechByKey = if (userSessions.isEmpty()) emptyMap() else liveTechnicalProvider()
            val activeStreams =
                userSessions.map { session ->
                    toActiveStreamDto(
                        session = session,
                        user = user,
                        liveTech = liveTechByKey[session.key],
                    )
                }

            val history = playbackHistoryRepository.listByUser(trimmedUserId).bind()
            val totalPlays = history.size.toLong()
            val totalWatchDurationSec = history.sumOf { it.watchedDurationSec.toLong() }

            UserDetailResponse(
                user =
                    user.toSummaryDto(
                        activeStreamCount = activeStreams.size,
                        totalPlays = totalPlays,
                        totalWatchDurationSec = totalWatchDurationSec,
                    ),
                activeStreams = activeStreams,
                totalPlays = totalPlays,
                totalWatchDurationSec = totalWatchDurationSec,
                recentPlays = history.take(RECENT_PLAYS_LIMIT).map { it.toRecentPlayDto() },
                topClients = aggregateUsage(history) { it.player.playerProduct ?: it.player.playerName ?: UNKNOWN_LABEL },
                topPlatforms = aggregateUsage(history) { it.player.playerPlatform ?: UNKNOWN_LABEL },
                decisionBreakdown = computeDecisionBreakdown(history),
                libraryBreakdown = computeLibraryBreakdown(history),
            )
        }

    private fun UserRecord.toSummaryDto(
        activeStreamCount: Int,
        totalPlays: Long,
        totalWatchDurationSec: Long,
    ): UserSummaryDto =
        UserSummaryDto(
            id = id,
            serverId = serverId,
            externalUserId = externalUserId,
            username = username,
            displayName = displayName,
            isAdmin = isAdmin,
            avatarUrl = avatarProxyUrl(),
            isStreaming = activeStreamCount > 0,
            activeStreamCount = activeStreamCount,
            lastSeenAtMs = lastSeenAtMs,
            totalPlays = totalPlays,
            totalWatchDurationSec = totalWatchDurationSec,
        )

    private fun PlaybackHistoryRecord.toRecentPlayDto(): UserRecentPlayDto =
        UserRecentPlayDto(
            id = id,
            serverId = serverId,
            ratingKey = media.ratingKey,
            mediaType = media.mediaType,
            title = media.title,
            parentTitle = media.parentTitle,
            grandparentTitle = media.grandparentTitle,
            parentIndex = media.parentIndex,
            mediaIndex = media.mediaIndex,
            year = media.year,
            thumbUrl = media.thumbPath?.let { mediaThumbProxyUrl(media.ratingKey) },
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
        for (record in history) {
            when (record.streamTech.streamDecision.lowercase()) {
                "direct_play" -> directPlay += 1L
                "direct_stream" -> directStream += 1L
                "transcode" -> transcode += 1L
            }
        }
        val total = history.size.toLong()
        return DecisionBreakdownDto(
            directPlay = directPlay,
            directStream = directStream,
            transcode = transcode,
            total = total,
            directPlayPercent = percentage(directPlay, total),
            directStreamPercent = percentage(directStream, total),
            transcodePercent = percentage(transcode, total),
        )
    }

    private fun computeLibraryBreakdown(history: List<PlaybackHistoryRecord>): List<LibraryBreakdownDto> =
        history
            .groupBy { it.media.librarySectionKey to it.media.mediaType }
            .map { (key, records) ->
                LibraryBreakdownDto(
                    librarySectionKey = key.first,
                    mediaType = key.second,
                    playCount = records.size.toLong(),
                    watchDurationSec = records.sumOf { it.watchedDurationSec.toLong() },
                )
            }.sortedWith(
                compareByDescending<LibraryBreakdownDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.mediaType }
                    .thenBy { it.librarySectionKey.orEmpty() },
            )

    private fun percentage(
        count: Long,
        total: Long,
    ): Double =
        if (total > 0L) {
            (count.toDouble() / total.toDouble()) * PERCENT_SCALE
        } else {
            0.0
        }

    private fun UserRecord.avatarProxyUrl(): String? =
        thumbUrl?.let { "/api/v1/users/$id/avatar" }

    private fun mediaThumbProxyUrl(ratingKey: String): String =
        "/api/v1/media/$ratingKey/thumb"
}
