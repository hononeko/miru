package app.hononeko.miru.service

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.ClientStatsResponse
import app.hononeko.miru.http.HeatmapCellDto
import app.hononeko.miru.http.HeatmapStatsResponse
import app.hononeko.miru.http.HistoryDetailResponse
import app.hononeko.miru.http.HistoryListResponse
import app.hononeko.miru.http.OverviewStatsResponse
import app.hononeko.miru.http.UserStatsResponse
import app.hononeko.miru.http.WrappedMonthStatDto
import app.hononeko.miru.http.WrappedStatsResponse
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.http.toHistoryDetailResponse
import app.hononeko.miru.http.toHistoryItemDto
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistorySortDirection
import app.hononeko.miru.persistence.model.HistorySortField
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 100
private const val MILLIS_PER_DAY = 86_400_000L
private const val HOURS_IN_DAY = 24
private const val DEFAULT_HEATMAP_RANGE = "7d"
private const val MIN_WRAPPED_YEAR = 1970
private const val MAX_WRAPPED_YEAR = 2100

private val HEATMAP_RANGE_DAYS = mapOf("7d" to 7, "30d" to 30)
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val VALID_MEDIA_TYPES = setOf("movie", "episode", "track", "clip", "live_tv")
private val VALID_DECISIONS = setOf("direct_play", "direct_stream", "transcode")

enum class AnalyticsMetric(
    val code: String,
) {
    PLAYS("plays"),
    DURATION("duration"),
}

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
            val usersById = resolveUsers(resultPage.items.map { it.userId }.distinct()).bind()

            val totalPages =
                if (resultPage.totalCount == 0L) {
                    0
                } else {
                    ((resultPage.totalCount + validated.pageSize - 1L) / validated.pageSize).toInt()
                }

            HistoryListResponse(
                items = resultPage.items.map { it.toHistoryItemDto(user = usersById[it.userId]) },
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
            record.toHistoryDetailResponse(user = user)
        }

    suspend fun getOverviewStats(
        principal: MiruPrincipal,
        rangeRaw: String?,
        metricRaw: String? = null,
    ): Either<MiruError, OverviewStatsResponse> =
        either {
            val range = parseRange(rangeRaw).bind()
            val metric = parseMetric(metricRaw).bind()
            val scopedUserIds = if (principal.isAdmin()) null else principal.linkedMediaUserIds
            val accumulator = CommonStatsAccumulator(includeDailyBreakdowns = true)

            playbackHistoryRepository
                .forEachOverviewStatsRow(userIds = scopedUserIds, fromMs = range.fromMs) { row ->
                    accumulator.record(row)
                }.bind()

            val usersById = resolveUsers(accumulator.activeUserIds.toList()).bind()
            val common = accumulator.build(metric = metric, usersById = usersById)

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
        metricRaw: String? = null,
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
            val metric = parseMetric(metricRaw).bind()
            val commonAcc = CommonStatsAccumulator(includeDailyBreakdowns = true)
            val detailsAcc = RewatchAndBreakdownAccumulator(trackLibraries = true)

            playbackHistoryRepository
                .forEachStatsRow(userIds = setOf(trimmedUserId), fromMs = range.fromMs) { row ->
                    commonAcc.record(row)
                    detailsAcc.record(row)
                }.bind()

            val common = commonAcc.build(metric = metric, usersById = mapOf(user.id to user))
            val longestStreak = computeLongestStreakFromEpochDays(commonAcc.daily.distinctEpochDays())
            val maxDailyPlays = common.playsPerDay.maxOfOrNull { it.playCount.toInt() } ?: 0

            UserStatsResponse(
                userId = user.id,
                username = user.username,
                displayName = user.displayName.normalizedDisplayName(),
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = common.totalPlays,
                totalWatchDurationSec = common.totalWatchDurationSec,
                averageCompletionPercent = detailsAcc.averageCompletionPercent(),
                completedPlays = detailsAcc.completedPlays,
                longestBingeStreakDays = longestStreak,
                maxDailyPlays = maxDailyPlays,
                playsPerDay = common.playsPerDay,
                topMovies = common.topMovies,
                topShows = common.topShows,
                mostRewatched = detailsAcc.buildMostRewatched(metric),
                decisionBreakdown = common.decisionBreakdown,
                topPlatforms = detailsAcc.buildTopPlatforms(common.totalPlays, common.totalWatchDurationSec, metric),
                libraryBreakdown = detailsAcc.buildLibraryBreakdown(common.totalPlays, common.totalWatchDurationSec, metric),
            )
        }

    suspend fun getHeatmapStats(
        principal: MiruPrincipal,
        rangeRaw: String?,
        userId: String? = null,
        metricRaw: String? = null,
    ): Either<MiruError, HeatmapStatsResponse> =
        either {
            val (rangeLabel, days) = parseHeatmapRange(rangeRaw).bind()
            val metric = parseMetric(metricRaw).bind()
            val scopedUserIds = resolveScopedStatsUserIds(principal, userId).bind()

            // Whole UTC days up to and including today, so every row of the grid is a calendar date
            val today = utcEpochDay(clock())
            val firstDay = today - (days - 1)
            val fromMs = firstDay * MILLIS_PER_DAY
            val toMs = (today + 1) * MILLIS_PER_DAY - 1L

            val playMatrix = Array(days) { LongArray(HOURS_IN_DAY) }
            val durationMatrix = Array(days) { LongArray(HOURS_IN_DAY) }
            var totalPlays = 0L
            var totalWatchDurationSec = 0L

            playbackHistoryRepository
                .forEachHeatmapSample(userIds = scopedUserIds, fromMs = fromMs, toMs = toMs) { stoppedAtMs, watchedDurationSec ->
                    val dayIdx = (utcEpochDay(stoppedAtMs) - firstDay).toInt()
                    val hourIdx = utcHourOfDay(stoppedAtMs)
                    val duration = watchedDurationSec.toLong()
                    playMatrix[dayIdx][hourIdx] += 1L
                    durationMatrix[dayIdx][hourIdx] += duration
                    totalPlays += 1L
                    totalWatchDurationSec += duration
                }.bind()

            val cells = ArrayList<HeatmapCellDto>(days * HOURS_IN_DAY)
            var maxCellPlays = 0L
            var maxCellWatchDurationSec = 0L
            var peakCell: HeatmapCellDto? = null

            for (day in 0 until days) {
                val epochDay = firstDay + day
                val date = LocalDate.ofEpochDay(epochDay).toString()
                val dayOfWeek = utcDayOfWeekIndex(epochDay)
                for (hour in 0 until HOURS_IN_DAY) {
                    val cellPlays = playMatrix[day][hour]
                    val cellDuration = durationMatrix[day][hour]
                    val cell =
                        HeatmapCellDto(
                            date = date,
                            dayOfWeek = dayOfWeek,
                            dayName = DAY_NAMES[dayOfWeek],
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
                    if (isHigherHeatmapPeak(cell, peakCell, metric)) {
                        peakCell = cell
                    }
                }
            }

            HeatmapStatsResponse(
                range = rangeLabel,
                fromMs = fromMs,
                totalPlays = totalPlays,
                totalWatchDurationSec = totalWatchDurationSec,
                maxCellPlays = maxCellPlays,
                maxCellWatchDurationSec = maxCellWatchDurationSec,
                peakDate = peakCell?.date,
                peakDayName = peakCell?.dayName,
                peakHour = peakCell?.hour,
                cells = cells,
            )
        }

    suspend fun getClientStats(
        principal: MiruPrincipal,
        rangeRaw: String?,
        userId: String? = null,
        metricRaw: String? = null,
    ): Either<MiruError, ClientStatsResponse> =
        either {
            val range = parseRange(rangeRaw, defaultRange = "30d").bind()
            val metric = parseMetric(metricRaw).bind()
            val scopedUserIds = resolveScopedStatsUserIds(principal, userId).bind()
            val acc = ClientStatsAccumulator()

            playbackHistoryRepository
                .forEachClientStatsRow(userIds = scopedUserIds, fromMs = range.fromMs) { row ->
                    acc.record(row)
                }.bind()

            ClientStatsResponse(
                range = range.label,
                fromMs = range.fromMs,
                totalPlays = acc.totalPlays,
                totalWatchDurationSec = acc.totalWatchDurationSec,
                transcodeOrRemuxPlays = acc.nonDirectTotalPlays,
                players = acc.buildPlayers(metric),
                platforms = acc.buildPlatforms(metric),
                videoCodecs = acc.buildVideoCodecs(metric),
                audioCodecs = acc.buildAudioCodecs(metric),
                transcodeReasons = acc.buildTranscodeReasons(metric),
            )
        }

    suspend fun getWrappedStats(
        principal: MiruPrincipal,
        yearRaw: String?,
        userId: String? = null,
        metricRaw: String? = null,
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
            val metric = parseMetric(metricRaw).bind()
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
            val commonAcc = CommonStatsAccumulator(includeDailyBreakdowns = false)
            val detailsAcc = RewatchAndBreakdownAccumulator(trackClients = true)
            val calendarAcc = WrappedCalendarAccumulator()

            playbackHistoryRepository
                .forEachStatsRow(userIds = scopedUserIds, fromMs = fromMs, toMs = toMs) { row ->
                    val epochDay = commonAcc.record(row)
                    detailsAcc.record(row)
                    calendarAcc.record(row, epochDay)
                }.bind()

            val common = commonAcc.build(metric = metric)
            val playsByMonth = calendarAcc.buildPlaysByMonth()
            val peakMonthComparator =
                when (metric) {
                    AnalyticsMetric.PLAYS -> compareBy<WrappedMonthStatDto> { it.playCount }.thenBy { it.watchDurationSec }
                    AnalyticsMetric.DURATION -> compareBy<WrappedMonthStatDto> { it.watchDurationSec }.thenBy { it.playCount }
                }
            val peakMonth =
                playsByMonth
                    .filter { it.playCount > 0L }
                    .maxWithOrNull(peakMonthComparator)
                    ?.monthName

            WrappedStatsResponse(
                year = year,
                userId = resolvedUser?.id,
                username = resolvedUser?.username,
                displayName = resolvedUser?.displayName.normalizedDisplayName(),
                totalPlays = common.totalPlays,
                totalWatchDurationSec = common.totalWatchDurationSec,
                completedPlays = detailsAcc.completedPlays,
                averageCompletionPercent = detailsAcc.averageCompletionPercent(),
                longestBingeStreakDays = computeLongestStreakFromEpochDays(commonAcc.daily.distinctEpochDays()),
                activeDaysCount = commonAcc.daily.activeDaysCount,
                uniqueMoviesCount = commonAcc.uniqueMoviesCount,
                uniqueShowsCount = calendarAcc.uniqueShowsCount,
                peakMonth = peakMonth,
                peakDayOfWeek = calendarAcc.peakDayOfWeek(metric),
                topMovies = common.topMovies,
                topShows = common.topShows,
                mostRewatched = detailsAcc.buildMostRewatched(metric),
                topPlatforms = detailsAcc.buildTopPlatforms(common.totalPlays, common.totalWatchDurationSec, metric),
                topClients = detailsAcc.buildTopClients(common.totalPlays, common.totalWatchDurationSec, metric),
                decisionBreakdown = common.decisionBreakdown,
                playsByMonth = playsByMonth,
            )
        }

    private suspend fun resolveUsers(userIds: List<String>): Either<MiruError, Map<String, UserRecord>> =
        either {
            if (userIds.isEmpty()) return@either emptyMap()
            userRepository.findByIds(userIds).bind().associateBy { it.id }
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

    private fun parseHeatmapRange(rangeRaw: String?): Either<MiruError.ValidationError, Pair<String, Int>> =
        either {
            val normalized = rangeRaw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: DEFAULT_HEATMAP_RANGE
            val days =
                HEATMAP_RANGE_DAYS[normalized]
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid heatmap range",
                            fieldErrors = mapOf("range" to "Must be one of: ${HEATMAP_RANGE_DAYS.keys.joinToString(", ")}"),
                        ),
                    )
            normalized to days
        }

    private fun parseMetric(metricRaw: String?): Either<MiruError.ValidationError, AnalyticsMetric> =
        either {
            val normalized = metricRaw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: AnalyticsMetric.PLAYS.code
            when (normalized) {
                AnalyticsMetric.PLAYS.code -> AnalyticsMetric.PLAYS
                AnalyticsMetric.DURATION.code -> AnalyticsMetric.DURATION
                else ->
                    raise(
                        MiruError.ValidationError(
                            message = "Invalid analytics metric",
                            fieldErrors = mapOf("metric" to "Must be one of: plays, duration"),
                        ),
                    )
            }
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

    private fun isHigherHeatmapPeak(
        candidate: HeatmapCellDto,
        current: HeatmapCellDto?,
        metric: AnalyticsMetric,
    ): Boolean {
        if (candidate.playCount <= 0L) return false
        if (current == null) return true
        return when (metric) {
            AnalyticsMetric.PLAYS ->
                if (candidate.playCount != current.playCount) {
                    candidate.playCount > current.playCount
                } else {
                    candidate.watchDurationSec > current.watchDurationSec
                }
            AnalyticsMetric.DURATION ->
                if (candidate.watchDurationSec != current.watchDurationSec) {
                    candidate.watchDurationSec > current.watchDurationSec
                } else {
                    candidate.playCount > current.playCount
                }
        }
    }
}
