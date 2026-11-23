package app.hononeko.miru.service

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.DailyPlayStatDto
import app.hononeko.miru.http.DecisionBreakdownStatDto
import app.hononeko.miru.http.HistoryDetailResponse
import app.hononeko.miru.http.HistoryListResponse
import app.hononeko.miru.http.LibraryBreakdownStatDto
import app.hononeko.miru.http.OverviewStatsResponse
import app.hononeko.miru.http.PlatformStatDto
import app.hononeko.miru.http.TopMediaStatDto
import app.hononeko.miru.http.UserStatsResponse
import app.hononeko.miru.http.buildThumbUrl
import app.hononeko.miru.http.toHistoryDetailResponse
import app.hononeko.miru.http.toHistoryItemDto
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistorySortDirection
import app.hononeko.miru.persistence.model.HistorySortField
import app.hononeko.miru.persistence.model.PlaybackHistoryStatsRow
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 100
private const val MILLIS_PER_DAY = 86_400_000L
private const val TOP_MEDIA_LIMIT = 10
private const val PERCENT_MULTIPLIER = 100.0
private const val ROUNDING_SCALE = 100.0

private val VALID_MEDIA_TYPES = setOf("movie", "episode", "track", "clip", "live_tv")
private val VALID_DECISIONS = setOf("direct_play", "direct_stream", "transcode")

data class HistoryQueryParams(
    val userId: String? = null,
    val mediaType: String? = null,
    val libraryId: String? = null,
    val decision: String? = null,
    val from: String? = null,
    val to: String? = null,
    val q: String? = null,
    val sortBy: String? = null,
    val sortDir: String? = null,
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val pageRaw: String? = null,
    val pageSizeRaw: String? = null,
)

private data class ValidatedHistoryQuery(
    val userId: String?,
    val mediaType: String?,
    val librarySectionKey: String?,
    val streamDecision: String?,
    val fromMs: Long?,
    val toMs: Long?,
    val q: String?,
    val sortBy: HistorySortField,
    val sortDir: HistorySortDirection,
    val page: Int,
    val pageSize: Int,
    val offset: Int,
)

private data class StatsRange(
    val label: String,
    val fromMs: Long?,
)

private data class CommonStats(
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val activeUsers: Int,
    val playsPerDay: List<DailyPlayStatDto>,
    val topMovies: List<TopMediaStatDto>,
    val topShows: List<TopMediaStatDto>,
    val decisionBreakdown: DecisionBreakdownStatDto,
)

class HistoryAndStatsService(
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val userRepository: UserRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun queryHistory(
        principal: MiruPrincipal,
        params: HistoryQueryParams,
    ): Either<MiruError, HistoryListResponse> =
        either {
            val validated = validateHistoryQuery(params).bind()

            if (validated.userId != null) {
                principal.requireMediaUserAccess(validated.userId).bind()
            }
            val scopedUserIds =
                if (validated.userId == null && !principal.isAdmin()) {
                    principal.linkedMediaUserIds
                } else {
                    null
                }

            val filter =
                HistoryFilter(
                    userId = validated.userId,
                    userIds = scopedUserIds,
                    mediaType = validated.mediaType,
                    librarySectionKey = validated.librarySectionKey,
                    streamDecision = validated.streamDecision,
                    fromMs = validated.fromMs,
                    toMs = validated.toMs,
                    q = validated.q,
                    sortBy = validated.sortBy,
                    sortDir = validated.sortDir,
                    limit = validated.pageSize,
                    offset = validated.offset,
                )

            val resultPage = playbackHistoryRepository.query(filter).bind()
            val usernamesById = resolveUsernames(resultPage.items.map { it.userId }.distinct()).bind()

            val totalPages =
                if (resultPage.totalCount == 0L) {
                    0
                } else {
                    ((resultPage.totalCount + validated.pageSize - 1L) / validated.pageSize).toInt()
                }

            HistoryListResponse(
                items = resultPage.items.map { it.toHistoryItemDto(username = usernamesById[it.userId]) },
                page = validated.page,
                pageSize = validated.pageSize,
                totalCount = resultPage.totalCount,
                totalPages = totalPages,
            )
        }

    suspend fun getHistoryDetail(
        principal: MiruPrincipal,
        id: String,
    ): Either<MiruError, HistoryDetailResponse> =
        either {
            val trimmedId = id.trim()
            ensure(trimmedId.isNotEmpty()) {
                MiruError.ValidationError("Invalid history record id", mapOf("id" to "Must not be blank"))
            }
            if (!principal.isAdmin() && principal.linkedMediaUserIds.isEmpty()) {
                raise(MiruError.Forbidden("Access to playback history is forbidden"))
            }

            val record =
                playbackHistoryRepository.findById(trimmedId).bind()
                    ?: raise(MiruError.NotFound(resource = "Playback history", id = trimmedId))

            principal.requireMediaUserAccess(record.userId).bind()
            val user = userRepository.findById(record.userId).bind()
            record.toHistoryDetailResponse(username = user?.username)
        }

    suspend fun getOverviewStats(
        principal: MiruPrincipal,
        rangeRaw: String?,
    ): Either<MiruError, OverviewStatsResponse> =
        either {
            val range = parseRange(rangeRaw).bind()
            val scopedUserIds = if (principal.isAdmin()) null else principal.linkedMediaUserIds
            val rows = playbackHistoryRepository.listForStats(userIds = scopedUserIds, fromMs = range.fromMs).bind()
            val common = aggregateCommonStats(rows)

            OverviewStatsResponse(
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = common.totalPlays,
                totalWatchDurationSec = common.totalWatchDurationSec,
                activeUsers = common.activeUsers,
                playsPerDay = common.playsPerDay,
                topMovies = common.topMovies,
                topShows = common.topShows,
                decisionBreakdown = common.decisionBreakdown,
            )
        }

    suspend fun getUserStats(
        principal: MiruPrincipal,
        userId: String,
        rangeRaw: String?,
    ): Either<MiruError, UserStatsResponse> =
        either {
            val trimmedUserId = userId.trim()
            ensure(trimmedUserId.isNotEmpty()) {
                MiruError.ValidationError("Invalid user id", mapOf("id" to "Must not be blank"))
            }

            principal.requireMediaUserAccess(trimmedUserId).bind()
            val user =
                userRepository.findById(trimmedUserId).bind()
                    ?: raise(MiruError.NotFound(resource = "User", id = trimmedUserId))

            val range = parseRange(rangeRaw).bind()
            val rows = playbackHistoryRepository.listForStats(userIds = setOf(trimmedUserId), fromMs = range.fromMs).bind()
            val common = aggregateCommonStats(rows)

            UserStatsResponse(
                userId = user.id,
                username = user.username,
                displayName = user.displayName,
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = common.totalPlays,
                totalWatchDurationSec = common.totalWatchDurationSec,
                playsPerDay = common.playsPerDay,
                topMovies = common.topMovies,
                topShows = common.topShows,
                decisionBreakdown = common.decisionBreakdown,
                topPlatforms = aggregatePlatforms(rows, common.totalPlays),
                libraryBreakdown = aggregateLibraries(rows, common.totalPlays),
            )
        }

    private suspend fun resolveUsernames(userIds: List<String>): Either<MiruError, Map<String, String>> =
        either {
            if (userIds.isEmpty()) return@either emptyMap()
            val result = mutableMapOf<String, String>()
            for (uid in userIds) {
                val user = userRepository.findById(uid).bind()
                if (user != null) {
                    result[uid] = user.username
                }
            }
            result
        }

    private fun validateHistoryQuery(params: HistoryQueryParams): Either<MiruError.ValidationError, ValidatedHistoryQuery> =
        either {
            val fieldErrors = linkedMapOf<String, String>()

            val page = parsePositiveInt(params.pageRaw, params.page, "page", fieldErrors)
            if ("page" !in fieldErrors && page < 1) {
                fieldErrors["page"] = "Must be >= 1"
            }

            val pageSize = parsePositiveInt(params.pageSizeRaw, params.pageSize, "pageSize", fieldErrors)
            if ("pageSize" !in fieldErrors && pageSize !in 1..MAX_PAGE_SIZE) {
                fieldErrors["pageSize"] = "Must be between 1 and $MAX_PAGE_SIZE"
            }

            val mediaType =
                params.mediaType?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.also {
                    if (it !in VALID_MEDIA_TYPES) {
                        fieldErrors["mediaType"] = "Must be one of: ${VALID_MEDIA_TYPES.joinToString(", ")}"
                    }
                }

            val decision =
                params.decision?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.also {
                    if (it !in VALID_DECISIONS) {
                        fieldErrors["decision"] = "Must be one of: ${VALID_DECISIONS.joinToString(", ")}"
                    }
                }

            val fromMs = parseInstantMs(params.from, "from", fieldErrors)
            val toMs = parseInstantMs(params.to, "to", fieldErrors)
            if (fromMs != null && toMs != null && fromMs > toMs) {
                fieldErrors["to"] = "Must be greater than or equal to 'from'"
            }

            val sortBy =
                params.sortBy?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                    parseSortField(raw).also { parsed ->
                        if (parsed == null) {
                            fieldErrors["sortBy"] =
                                "Must be one of: stopped_at, started_at, watched_duration, completion_percent, title"
                        }
                    }
                } ?: HistorySortField.STOPPED_AT

            val sortDir =
                params.sortDir?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                    parseSortDirection(raw).also { parsed ->
                        if (parsed == null) {
                            fieldErrors["sortDir"] = "Must be one of: asc, desc"
                        }
                    }
                } ?: HistorySortDirection.DESC

            val offsetLong =
                if ("page" !in fieldErrors && "pageSize" !in fieldErrors) {
                    (page.toLong() - 1L) * pageSize.toLong()
                } else {
                    0L
                }
            if (offsetLong > Int.MAX_VALUE) {
                fieldErrors["page"] = "Page offset exceeds supported range"
            }

            ensure(fieldErrors.isEmpty()) {
                MiruError.ValidationError("Invalid history query parameters", fieldErrors)
            }

            ValidatedHistoryQuery(
                userId = params.userId?.trim()?.takeIf { it.isNotEmpty() },
                mediaType = mediaType,
                librarySectionKey = params.libraryId?.trim()?.takeIf { it.isNotEmpty() },
                streamDecision = decision,
                fromMs = fromMs,
                toMs = toMs,
                q = params.q?.trim()?.takeIf { it.isNotEmpty() },
                sortBy = sortBy,
                sortDir = sortDir,
                page = page,
                pageSize = pageSize,
                offset = offsetLong.toInt(),
            )
        }

    private fun parseRange(rangeRaw: String?): Either<MiruError.ValidationError, StatsRange> =
        either {
            val normalized = rangeRaw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: "30d"
            val nowMs = clock()
            val fromMs =
                when (normalized) {
                    "7d" -> nowMs - 7L * MILLIS_PER_DAY
                    "30d" -> nowMs - 30L * MILLIS_PER_DAY
                    "90d" -> nowMs - 90L * MILLIS_PER_DAY
                    "all" -> null
                    else ->
                        raise(
                            MiruError.ValidationError(
                                message = "Invalid stats range",
                                fieldErrors = mapOf("range" to "Must be one of: 7d, 30d, 90d, all"),
                            ),
                        )
                }
            StatsRange(label = normalized, fromMs = fromMs)
        }

    private fun parsePositiveInt(
        raw: String?,
        fallback: Int,
        field: String,
        fieldErrors: MutableMap<String, String>,
    ): Int {
        if (raw == null) return fallback
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null) {
            fieldErrors[field] = "Must be an integer"
            return fallback
        }
        return parsed
    }

    private fun parseInstantMs(
        raw: String?,
        field: String,
        fieldErrors: MutableMap<String, String>,
    ): Long? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrElse {
            fieldErrors[field] = "Must be a valid ISO-8601 timestamp"
            null
        }
    }

    private fun parseSortField(raw: String): HistorySortField? =
        when (raw.lowercase()) {
            "stopped_at", "stoppedat" -> HistorySortField.STOPPED_AT
            "started_at", "startedat" -> HistorySortField.STARTED_AT
            "watched_duration", "watchedduration" -> HistorySortField.WATCHED_DURATION
            "completion_percent", "completionpercent" -> HistorySortField.COMPLETION_PERCENT
            "title" -> HistorySortField.TITLE
            else -> null
        }

    private fun parseSortDirection(raw: String): HistorySortDirection? =
        when (raw.lowercase()) {
            "asc" -> HistorySortDirection.ASC
            "desc" -> HistorySortDirection.DESC
            else -> null
        }

    private fun aggregateCommonStats(rows: List<PlaybackHistoryStatsRow>): CommonStats {
        val totalPlays = rows.size.toLong()
        var totalWatchDurationSec = 0L
        val activeUserIds = hashSetOf<String>()
        val dailyAgg = linkedMapOf<String, Pair<Long, Long>>()
        var directPlay = 0L
        var directStream = 0L
        var transcode = 0L
        var hwTranscode = 0L

        for (row in rows) {
            totalWatchDurationSec += row.watchedDurationSec.toLong()
            activeUserIds += row.userId

            val day = Instant.ofEpochMilli(row.stoppedAtMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val prev = dailyAgg[day] ?: (0L to 0L)
            dailyAgg[day] = (prev.first + 1L) to (prev.second + row.watchedDurationSec.toLong())

            when (row.streamDecision.lowercase()) {
                "direct_play" -> directPlay++
                "direct_stream" -> directStream++
                "transcode" -> transcode++
            }
            if (row.hwTranscode) {
                hwTranscode++
            }
        }

        val playsPerDay =
            dailyAgg.entries
                .sortedBy { it.key }
                .map { (date, counts) ->
                    DailyPlayStatDto(
                        date = date,
                        playCount = counts.first,
                        watchDurationSec = counts.second,
                    )
                }

        val decisionBreakdown =
            DecisionBreakdownStatDto(
                directPlay = directPlay,
                directStream = directStream,
                transcode = transcode,
                hwTranscode = hwTranscode,
                directPlayPercent = toPercent(directPlay, totalPlays),
                directStreamPercent = toPercent(directStream, totalPlays),
                transcodePercent = toPercent(transcode, totalPlays),
                hwTranscodePercent = toPercent(hwTranscode, totalPlays),
            )

        return CommonStats(
            totalPlays = totalPlays,
            totalWatchDurationSec = totalWatchDurationSec,
            activeUsers = activeUserIds.size,
            playsPerDay = playsPerDay,
            topMovies = aggregateTopMovies(rows),
            topShows = aggregateTopShows(rows),
            decisionBreakdown = decisionBreakdown,
        )
    }

    private fun aggregateTopMovies(rows: List<PlaybackHistoryStatsRow>): List<TopMediaStatDto> =
        rows
            .filter { it.mediaType.equals("movie", ignoreCase = true) }
            .groupBy { it.ratingKey }
            .map { (ratingKey, group) ->
                val title = group.last().title
                val thumbUrl =
                    group.firstNotNullOfOrNull { item ->
                        buildThumbUrl(item.ratingKey, item.thumbPath)
                    }
                TopMediaStatDto(
                    ratingKey = ratingKey,
                    title = title,
                    playCount = group.size.toLong(),
                    watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                    thumbUrl = thumbUrl,
                )
            }.sortedWith(
                compareByDescending<TopMediaStatDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.title.lowercase() },
            ).take(TOP_MEDIA_LIMIT)

    private fun aggregateTopShows(rows: List<PlaybackHistoryStatsRow>): List<TopMediaStatDto> =
        rows
            .filter { it.mediaType.equals("episode", ignoreCase = true) }
            .groupBy { it.grandparentTitle?.takeIf { title -> title.isNotBlank() } ?: it.title }
            .map { (showTitle, group) ->
                val thumbRow = group.firstOrNull { !it.thumbPath.isNullOrBlank() } ?: group.last()
                TopMediaStatDto(
                    ratingKey = thumbRow.ratingKey,
                    title = showTitle,
                    playCount = group.size.toLong(),
                    watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                    thumbUrl = buildThumbUrl(thumbRow.ratingKey, thumbRow.thumbPath),
                )
            }.sortedWith(
                compareByDescending<TopMediaStatDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.title.lowercase() },
            ).take(TOP_MEDIA_LIMIT)

    private fun aggregatePlatforms(
        rows: List<PlaybackHistoryStatsRow>,
        totalPlays: Long,
    ): List<PlatformStatDto> =
        rows
            .groupBy { row ->
                row.playerPlatform?.takeIf { it.isNotBlank() }
                    ?: row.playerProduct?.takeIf { it.isNotBlank() }
                    ?: "Unknown"
            }.map { (platform, group) ->
                val count = group.size.toLong()
                PlatformStatDto(
                    platform = platform,
                    playCount = count,
                    watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                    percent = toPercent(count, totalPlays),
                )
            }.sortedWith(
                compareByDescending<PlatformStatDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.platform.lowercase() },
            ).take(TOP_MEDIA_LIMIT)

    private fun aggregateLibraries(
        rows: List<PlaybackHistoryStatsRow>,
        totalPlays: Long,
    ): List<LibraryBreakdownStatDto> =
        rows
            .groupBy { row -> row.librarySectionKey?.takeIf { it.isNotBlank() } ?: "unknown" }
            .map { (librarySectionKey, group) ->
                val count = group.size.toLong()
                LibraryBreakdownStatDto(
                    librarySectionKey = librarySectionKey,
                    playCount = count,
                    watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                    percent = toPercent(count, totalPlays),
                )
            }.sortedWith(
                compareByDescending<LibraryBreakdownStatDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.librarySectionKey.lowercase() },
            )

    private fun toPercent(
        part: Long,
        total: Long,
    ): Double {
        if (total <= 0L) return 0.0
        return ((part * PERCENT_MULTIPLIER / total) * ROUNDING_SCALE).roundToLong() / ROUNDING_SCALE
    }
}
