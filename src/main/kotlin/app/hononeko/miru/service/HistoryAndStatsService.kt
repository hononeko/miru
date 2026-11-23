package app.hononeko.miru.service

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.ClientBreakdownItemDto
import app.hononeko.miru.http.ClientStatsResponse
import app.hononeko.miru.http.DailyPlayStatDto
import app.hononeko.miru.http.DecisionBreakdownStatDto
import app.hononeko.miru.http.HeatmapCellDto
import app.hononeko.miru.http.HeatmapStatsResponse
import app.hononeko.miru.http.HistoryDetailResponse
import app.hononeko.miru.http.HistoryListResponse
import app.hononeko.miru.http.LibraryBreakdownStatDto
import app.hononeko.miru.http.OverviewStatsResponse
import app.hononeko.miru.http.PlatformStatDto
import app.hononeko.miru.http.RewatchedMediaStatDto
import app.hononeko.miru.http.TopMediaStatDto
import app.hononeko.miru.http.UserStatsResponse
import app.hononeko.miru.http.WrappedMonthStatDto
import app.hononeko.miru.http.WrappedStatsResponse
import app.hononeko.miru.http.buildThumbUrl
import app.hononeko.miru.http.toHistoryDetailResponse
import app.hononeko.miru.http.toHistoryItemDto
import app.hononeko.miru.library.LibraryNormalizer
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
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong

private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 100
private const val MILLIS_PER_DAY = 86_400_000L
private const val TOP_MEDIA_LIMIT = 10
private const val PERCENT_MULTIPLIER = 100.0
private const val ROUNDING_SCALE = 100.0
private const val DAYS_IN_WEEK = 7
private const val HOURS_IN_DAY = 24
private const val MIN_WRAPPED_YEAR = 1970
private const val MAX_WRAPPED_YEAR = 2100

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

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
            val avgCompletion = computeAverageCompletionPercent(rows)
            val completedPlays = rows.count { it.isWatched }.toLong()
            val longestStreak = computeLongestBingeStreakDays(rows)
            val maxDailyPlays = common.playsPerDay.maxOfOrNull { it.playCount.toInt() } ?: 0

            UserStatsResponse(
                userId = user.id,
                username = user.username,
                displayName = user.displayName,
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = common.totalPlays,
                totalWatchDurationSec = common.totalWatchDurationSec,
                averageCompletionPercent = avgCompletion,
                completedPlays = completedPlays,
                longestBingeStreakDays = longestStreak,
                maxDailyPlays = maxDailyPlays,
                playsPerDay = common.playsPerDay,
                topMovies = common.topMovies,
                topShows = common.topShows,
                mostRewatched = aggregateMostRewatched(rows),
                decisionBreakdown = common.decisionBreakdown,
                topPlatforms = aggregatePlatforms(rows, common.totalPlays),
                libraryBreakdown = aggregateLibraries(rows, common.totalPlays),
            )
        }

    suspend fun getHeatmapStats(
        principal: MiruPrincipal,
        rangeRaw: String?,
        userId: String? = null,
    ): Either<MiruError, HeatmapStatsResponse> =
        either {
            val range = parseRange(rangeRaw, defaultRange = "90d").bind()
            val scopedUserIds = resolveScopedStatsUserIds(principal, userId).bind()
            val rows = playbackHistoryRepository.listForStats(userIds = scopedUserIds, fromMs = range.fromMs).bind()

            val playMatrix = Array(DAYS_IN_WEEK) { LongArray(HOURS_IN_DAY) }
            val durationMatrix = Array(DAYS_IN_WEEK) { LongArray(HOURS_IN_DAY) }
            var totalWatchDurationSec = 0L

            for (row in rows) {
                val zdt = Instant.ofEpochMilli(row.stoppedAtMs).atZone(ZoneOffset.UTC)
                val dayIdx = (zdt.dayOfWeek.value - 1).coerceIn(0, DAYS_IN_WEEK - 1)
                val hourIdx = zdt.hour.coerceIn(0, HOURS_IN_DAY - 1)
                val duration = row.watchedDurationSec.toLong()
                playMatrix[dayIdx][hourIdx] += 1L
                durationMatrix[dayIdx][hourIdx] += duration
                totalWatchDurationSec += duration
            }

            val cells = ArrayList<HeatmapCellDto>(DAYS_IN_WEEK * HOURS_IN_DAY)
            var maxCellPlays = 0L
            var maxCellWatchDurationSec = 0L
            var peakCell: HeatmapCellDto? = null

            for (day in 0 until DAYS_IN_WEEK) {
                val dayName = DAY_NAMES[day]
                for (hour in 0 until HOURS_IN_DAY) {
                    val cellPlays = playMatrix[day][hour]
                    val cellDuration = durationMatrix[day][hour]
                    val cell =
                        HeatmapCellDto(
                            dayOfWeek = day,
                            dayName = dayName,
                            hour = hour,
                            playCount = cellPlays,
                            watchDurationSec = cellDuration,
                        )
                    cells += cell
                    if (cellPlays > maxCellPlays) {
                        maxCellPlays = cellPlays
                    }
                    if (cellDuration > maxCellWatchDurationSec) {
                        maxCellWatchDurationSec = cellDuration
                    }
                    if (isHigherHeatmapPeak(cell, peakCell)) {
                        peakCell = cell
                    }
                }
            }

            HeatmapStatsResponse(
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = rows.size.toLong(),
                totalWatchDurationSec = totalWatchDurationSec,
                maxCellPlays = maxCellPlays,
                maxCellWatchDurationSec = maxCellWatchDurationSec,
                peakDayName = peakCell?.dayName,
                peakHour = peakCell?.hour,
                cells = cells,
            )
        }

    suspend fun getClientStats(
        principal: MiruPrincipal,
        rangeRaw: String?,
        userId: String? = null,
    ): Either<MiruError, ClientStatsResponse> =
        either {
            val range = parseRange(rangeRaw, defaultRange = "30d").bind()
            val scopedUserIds = resolveScopedStatsUserIds(principal, userId).bind()
            val rows = playbackHistoryRepository.listForStats(userIds = scopedUserIds, fromMs = range.fromMs).bind()
            val totalPlays = rows.size.toLong()
            val totalWatchDurationSec = rows.sumOf { it.watchedDurationSec.toLong() }

            val players =
                aggregateClientBreakdown(rows, totalPlays) { row ->
                    row.playerProduct?.takeIf { it.isNotBlank() }
                        ?: row.playerName?.takeIf { it.isNotBlank() }
                        ?: "Unknown"
                }
            val platforms =
                aggregateClientBreakdown(rows, totalPlays) { row ->
                    row.playerPlatform?.takeIf { it.isNotBlank() } ?: "Unknown"
                }
            val videoCodecs =
                aggregateClientBreakdown(rows, totalPlays) { row ->
                    LibraryNormalizer.normalizeCodec(row.videoCodec)
                }
            val audioCodecs =
                aggregateClientBreakdown(rows, totalPlays) { row ->
                    LibraryNormalizer.normalizeCodec(row.audioCodec)
                }

            val nonDirectRows =
                rows.mapNotNull { row ->
                    classifyTranscodeReason(row)?.let { reason -> reason to row }
                }
            val nonDirectTotal = nonDirectRows.size.toLong()
            val transcodeReasons =
                nonDirectRows
                    .groupBy({ it.first }, { it.second })
                    .map { (reason, group) ->
                        val count = group.size.toLong()
                        ClientBreakdownItemDto(
                            name = reason,
                            playCount = count,
                            watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                            percent = toPercent(count, nonDirectTotal),
                        )
                    }.sortedWith(
                        compareByDescending<ClientBreakdownItemDto> { it.playCount }
                            .thenByDescending { it.watchDurationSec }
                            .thenBy { it.name.lowercase() },
                    ).take(TOP_MEDIA_LIMIT)

            ClientStatsResponse(
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = totalPlays,
                totalWatchDurationSec = totalWatchDurationSec,
                transcodeOrRemuxPlays = nonDirectTotal,
                players = players,
                platforms = platforms,
                videoCodecs = videoCodecs,
                audioCodecs = audioCodecs,
                transcodeReasons = transcodeReasons,
            )
        }

    suspend fun getWrappedStats(
        principal: MiruPrincipal,
        yearRaw: String?,
        userId: String? = null,
    ): Either<MiruError, WrappedStatsResponse> =
        either {
            val year =
                yearRaw?.trim()?.toIntOrNull()?.takeIf { it in MIN_WRAPPED_YEAR..MAX_WRAPPED_YEAR }
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid wrapped year",
                            fieldErrors = mapOf("year" to "Must be a valid year between $MIN_WRAPPED_YEAR and $MAX_WRAPPED_YEAR"),
                        ),
                    )
            val trimmedUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
            val resolvedUser =
                if (trimmedUserId != null) {
                    principal.requireMediaUserAccess(trimmedUserId).bind()
                    userRepository.findById(trimmedUserId).bind()
                        ?: raise(MiruError.NotFound(resource = "User", id = trimmedUserId))
                } else {
                    null
                }
            val scopedUserIds =
                when {
                    resolvedUser != null -> setOf(resolvedUser.id)
                    principal.isAdmin() -> null
                    else -> principal.linkedMediaUserIds
                }

            val fromMs = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val toMs = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1L
            val rows =
                playbackHistoryRepository
                    .listForStats(
                        userIds = scopedUserIds,
                        fromMs = fromMs,
                        toMs = toMs,
                    ).bind()

            val common = aggregateCommonStats(rows)
            val playsByMonth = aggregatePlaysByMonth(rows)
            val peakMonth =
                playsByMonth
                    .filter { it.playCount > 0L }
                    .maxWithOrNull(compareBy<WrappedMonthStatDto> { it.playCount }.thenBy { it.watchDurationSec })
                    ?.monthName
            val peakDayOfWeek = computePeakDayOfWeek(rows)
            val uniqueMoviesCount =
                rows
                    .asSequence()
                    .filter { it.mediaType.equals("movie", ignoreCase = true) }
                    .map { it.ratingKey }
                    .distinct()
                    .count()
            val uniqueShowsCount =
                rows
                    .asSequence()
                    .filter { it.mediaType.equals("episode", ignoreCase = true) }
                    .map { (it.grandparentTitle?.takeIf { t -> t.isNotBlank() } ?: it.title).lowercase() }
                    .distinct()
                    .count()

            WrappedStatsResponse(
                year = year,
                userId = resolvedUser?.id,
                username = resolvedUser?.username,
                displayName = resolvedUser?.displayName,
                totalPlays = common.totalPlays,
                totalWatchDurationSec = common.totalWatchDurationSec,
                completedPlays = rows.count { it.isWatched }.toLong(),
                averageCompletionPercent = computeAverageCompletionPercent(rows),
                longestBingeStreakDays = computeLongestBingeStreakDays(rows),
                activeDaysCount = common.playsPerDay.size,
                uniqueMoviesCount = uniqueMoviesCount,
                uniqueShowsCount = uniqueShowsCount,
                peakMonth = peakMonth,
                peakDayOfWeek = peakDayOfWeek,
                topMovies = common.topMovies,
                topShows = common.topShows,
                mostRewatched = aggregateMostRewatched(rows),
                topPlatforms = aggregatePlatforms(rows, common.totalPlays),
                topClients =
                    aggregateClientBreakdown(rows, common.totalPlays) { row ->
                        row.playerProduct?.takeIf { it.isNotBlank() }
                            ?: row.playerName?.takeIf { it.isNotBlank() }
                            ?: "Unknown"
                    },
                decisionBreakdown = common.decisionBreakdown,
                playsByMonth = playsByMonth,
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

    private fun resolveScopedStatsUserIds(
        principal: MiruPrincipal,
        userId: String?,
    ): Either<MiruError, Set<String>?> =
        either {
            val trimmed = userId?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed != null) {
                principal.requireMediaUserAccess(trimmed).bind()
                setOf(trimmed)
            } else if (principal.isAdmin()) {
                null
            } else {
                principal.linkedMediaUserIds
            }
        }

    private fun parseRange(
        rangeRaw: String?,
        defaultRange: String = "30d",
    ): Either<MiruError.ValidationError, StatsRange> =
        either {
            val normalized = rangeRaw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: defaultRange
            val nowMs = clock()
            val fromMs =
                when (normalized) {
                    "7d" -> nowMs - 7L * MILLIS_PER_DAY
                    "30d" -> nowMs - 30L * MILLIS_PER_DAY
                    "90d" -> nowMs - 90L * MILLIS_PER_DAY
                    "365d" -> nowMs - 365L * MILLIS_PER_DAY
                    "all" -> null
                    else ->
                        raise(
                            MiruError.ValidationError(
                                message = "Invalid stats range",
                                fieldErrors = mapOf("range" to "Must be one of: 7d, 30d, 90d, 365d, all"),
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

    private fun aggregateMostRewatched(rows: List<PlaybackHistoryStatsRow>): List<RewatchedMediaStatDto> =
        rows
            .groupBy { it.ratingKey }
            .filter { (_, group) -> group.size >= 2 }
            .map { (ratingKey, group) ->
                val latest = group.last()
                val thumbUrl =
                    group.firstNotNullOfOrNull { item ->
                        buildThumbUrl(item.ratingKey, item.thumbPath)
                    }
                RewatchedMediaStatDto(
                    ratingKey = ratingKey,
                    mediaType = latest.mediaType,
                    title = latest.title,
                    grandparentTitle = latest.grandparentTitle,
                    playCount = group.size.toLong(),
                    watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                    thumbUrl = thumbUrl,
                )
            }.sortedWith(
                compareByDescending<RewatchedMediaStatDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.title.lowercase() },
            ).take(TOP_MEDIA_LIMIT)

    private fun aggregateClientBreakdown(
        rows: List<PlaybackHistoryStatsRow>,
        totalPlays: Long,
        keySelector: (PlaybackHistoryStatsRow) -> String,
    ): List<ClientBreakdownItemDto> =
        rows
            .groupBy { keySelector(it).ifBlank { "Unknown" } }
            .map { (name, group) ->
                val count = group.size.toLong()
                ClientBreakdownItemDto(
                    name = name,
                    playCount = count,
                    watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                    percent = toPercent(count, totalPlays),
                )
            }.sortedWith(
                compareByDescending<ClientBreakdownItemDto> { it.playCount }
                    .thenByDescending { it.watchDurationSec }
                    .thenBy { it.name.lowercase() },
            ).take(TOP_MEDIA_LIMIT)

    private fun classifyTranscodeReason(row: PlaybackHistoryStatsRow): String? {
        val streamDec = row.streamDecision.trim().lowercase()
        val videoDec = row.videoDecision?.trim()?.lowercase()
        val audioDec = row.audioDecision?.trim()?.lowercase()

        val isVideoTranscode = videoDec == "transcode"
        val isAudioTranscode = audioDec == "transcode"
        val isVideoCopy = videoDec == "copy" || videoDec == "direct_stream" || videoDec == "directstream"
        val isAudioCopy = audioDec == "copy" || audioDec == "direct_stream" || audioDec == "directstream"

        return when {
            isVideoTranscode && isAudioTranscode -> "Video & Audio Transcode"
            isVideoTranscode -> "Video Transcode"
            isAudioTranscode -> "Audio Transcode"
            streamDec == "direct_stream" || isVideoCopy || isAudioCopy -> "Container Remux"
            streamDec == "transcode" -> "Video Transcode"
            else -> null
        }
    }

    private fun computeAverageCompletionPercent(rows: List<PlaybackHistoryStatsRow>): Double {
        if (rows.isEmpty()) return 0.0
        val avg = rows.sumOf { it.completionPercent } / rows.size
        return (avg * ROUNDING_SCALE).roundToLong() / ROUNDING_SCALE
    }

    private fun computeLongestBingeStreakDays(rows: List<PlaybackHistoryStatsRow>): Int {
        if (rows.isEmpty()) return 0
        val distinctDays =
            rows
                .map { Instant.ofEpochMilli(it.stoppedAtMs).atZone(ZoneOffset.UTC).toLocalDate() }
                .distinct()
                .sorted()
        var longest = 1
        var current = 1
        for (idx in 1 until distinctDays.size) {
            if (distinctDays[idx] == distinctDays[idx - 1].plusDays(1)) {
                current++
                if (current > longest) {
                    longest = current
                }
            } else {
                current = 1
            }
        }
        return longest
    }

    private fun aggregatePlaysByMonth(rows: List<PlaybackHistoryStatsRow>): List<WrappedMonthStatDto> {
        val counts = LongArray(MONTH_NAMES.size)
        val durations = LongArray(MONTH_NAMES.size)
        for (row in rows) {
            val monthIdx = (Instant.ofEpochMilli(row.stoppedAtMs).atZone(ZoneOffset.UTC).monthValue - 1).coerceIn(0, 11)
            counts[monthIdx] += 1L
            durations[monthIdx] += row.watchedDurationSec.toLong()
        }
        return MONTH_NAMES.mapIndexed { idx, name ->
            WrappedMonthStatDto(
                month = idx + 1,
                monthName = name,
                playCount = counts[idx],
                watchDurationSec = durations[idx],
            )
        }
    }

    private fun computePeakDayOfWeek(rows: List<PlaybackHistoryStatsRow>): String? {
        if (rows.isEmpty()) return null
        val counts = LongArray(DAYS_IN_WEEK)
        val durations = LongArray(DAYS_IN_WEEK)
        for (row in rows) {
            val dayIdx = (Instant.ofEpochMilli(row.stoppedAtMs).atZone(ZoneOffset.UTC).dayOfWeek.value - 1).coerceIn(0, 6)
            counts[dayIdx] += 1L
            durations[dayIdx] += row.watchedDurationSec.toLong()
        }
        var bestIdx = 0
        for (idx in 1 until DAYS_IN_WEEK) {
            if (counts[idx] > counts[bestIdx] ||
                (counts[idx] == counts[bestIdx] && durations[idx] > durations[bestIdx])
            ) {
                bestIdx = idx
            }
        }
        return if (counts[bestIdx] > 0L) DAY_NAMES[bestIdx] else null
    }

    private fun isHigherHeatmapPeak(
        candidate: HeatmapCellDto,
        current: HeatmapCellDto?,
    ): Boolean {
        if (candidate.playCount <= 0L) return false
        if (current == null) return true
        return if (candidate.playCount != current.playCount) {
            candidate.playCount > current.playCount
        } else {
            candidate.watchDurationSec > current.watchDurationSec
        }
    }

    private fun toPercent(
        part: Long,
        total: Long,
    ): Double {
        if (total <= 0L) return 0.0
        return ((part * PERCENT_MULTIPLIER / total) * ROUNDING_SCALE).roundToLong() / ROUNDING_SCALE
    }
}
