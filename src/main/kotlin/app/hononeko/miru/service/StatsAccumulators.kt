package app.hononeko.miru.service

import app.hononeko.miru.http.ClientBreakdownItemDto
import app.hononeko.miru.http.DailyPlayStatDto
import app.hononeko.miru.http.DecisionBreakdownDto
import app.hononeko.miru.http.LibraryBreakdownDto
import app.hononeko.miru.http.PlatformStatDto
import app.hononeko.miru.http.RewatchedMediaStatDto
import app.hononeko.miru.http.TopMediaStatDto
import app.hononeko.miru.http.WrappedMonthStatDto
import app.hononeko.miru.http.buildThumbUrl
import app.hononeko.miru.library.LibraryNormalizer
import app.hononeko.miru.persistence.model.ClientStatsRow
import app.hononeko.miru.persistence.model.OverviewStatsRow
import app.hononeko.miru.persistence.model.PlaybackHistoryStatsRow
import app.hononeko.miru.persistence.model.UserRecord
import java.time.LocalDate
import kotlin.math.roundToLong

private const val MILLIS_PER_DAY_UTC = 86_400_000L
private const val MILLIS_PER_HOUR_UTC = 3_600_000L
private const val EPOCH_THURSDAY_OFFSET = 3L
private const val DAYS_PER_WEEK = 7
private const val TOP_MEDIA_CAP = 10
private const val PERCENT_SCALE_FACTOR = 100.0
private const val ROUND_SCALE_FACTOR = 100.0
private const val MIN_NONZERO_PERCENT = 0.01

private val UTC_DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val UTC_MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val COPY_STREAM_DECISIONS = setOf("copy", "direct_stream", "directstream")

internal fun utcEpochDay(epochMillis: Long): Long = Math.floorDiv(epochMillis, MILLIS_PER_DAY_UTC)

internal fun utcDayOfWeekIndex(epochDay: Long): Int =
    Math.floorMod(epochDay + EPOCH_THURSDAY_OFFSET, DAYS_PER_WEEK.toLong()).toInt()

internal fun utcHourOfDay(epochMillis: Long): Int =
    (Math.floorMod(epochMillis, MILLIS_PER_DAY_UTC) / MILLIS_PER_HOUR_UTC).toInt()

internal fun computeStatsPercent(
    part: Long,
    total: Long,
): Double {
    if (total <= 0L || part <= 0L) return 0.0
    val rounded = ((part * PERCENT_SCALE_FACTOR / total) * ROUND_SCALE_FACTOR).roundToLong() / ROUND_SCALE_FACTOR
    return rounded.coerceAtLeast(MIN_NONZERO_PERCENT)
}

internal fun computeLongestStreakFromEpochDays(epochDays: Set<Long>): Int {
    if (epochDays.isEmpty()) return 0
    val sorted = epochDays.sorted()
    var longest = 1
    var current = 1
    for (idx in 1 until sorted.size) {
        if (sorted[idx] == sorted[idx - 1] + 1L) {
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

internal class CountDuration(
    var playCount: Long = 0L,
    var watchDurationSec: Long = 0L,
) {
    fun add(durationSec: Long) {
        playCount++
        watchDurationSec += durationSec
    }
}

internal data class CommonStatsResult(
    val totalPlays: Long,
    val totalWatchDurationSec: Long,
    val activeUsers: Int,
    val playsPerDay: List<DailyPlayStatDto>,
    val topMovies: List<TopMediaStatDto>,
    val topShows: List<TopMediaStatDto>,
    val decisionBreakdown: DecisionBreakdownDto,
)

internal class CommonStatsAccumulator(
    includeDailyBreakdowns: Boolean = true,
) {
    var totalPlays: Long = 0L
        private set
    var totalWatchDurationSec: Long = 0L
        private set

    val activeUserIds: HashSet<String> = HashSet()
    val daily: DailyStatsAccumulator = DailyStatsAccumulator(includeBreakdowns = includeDailyBreakdowns)

    private var directPlay = 0L
    private var directStream = 0L
    private var transcode = 0L
    private var hwTranscode = 0L
    private var directPlayDurationSec = 0L
    private var directStreamDurationSec = 0L
    private var transcodeDurationSec = 0L
    private var hwTranscodeDurationSec = 0L

    private val movies = HashMap<String, MovieBucket>()
    private val shows = HashMap<String, ShowBucket>()

    val uniqueMoviesCount: Int
        get() = movies.size

    fun record(row: OverviewStatsRow) {
        recordFields(
            userId = row.userId,
            mediaType = row.mediaType,
            ratingKey = row.ratingKey,
            title = row.title,
            grandparentTitle = row.grandparentTitle,
            thumbPath = row.thumbPath,
            watchedDurationSec = row.watchedDurationSec.toLong(),
            streamDecision = row.streamDecision,
            hwTranscode = row.hwTranscode,
            stoppedAtMs = row.stoppedAtMs,
        )
    }

    fun record(row: PlaybackHistoryStatsRow): Long {
        val duration = row.watchedDurationSec.toLong()
        return recordFields(
            userId = row.userId,
            mediaType = row.mediaType,
            ratingKey = row.ratingKey,
            title = row.title,
            grandparentTitle = row.grandparentTitle,
            thumbPath = row.thumbPath,
            watchedDurationSec = duration,
            streamDecision = row.streamDecision,
            hwTranscode = row.hwTranscode,
            stoppedAtMs = row.stoppedAtMs,
        )
    }

    @Suppress("LongParameterList")
    private fun recordFields(
        userId: String,
        mediaType: String,
        ratingKey: String,
        title: String,
        grandparentTitle: String?,
        thumbPath: String?,
        watchedDurationSec: Long,
        streamDecision: String,
        hwTranscode: Boolean,
        stoppedAtMs: Long,
    ): Long {
        totalPlays++
        totalWatchDurationSec += watchedDurationSec
        activeUserIds += userId

        when (streamDecision.lowercase()) {
            "direct_play" -> {
                directPlay++
                directPlayDurationSec += watchedDurationSec
            }
            "direct_stream" -> {
                directStream++
                directStreamDurationSec += watchedDurationSec
            }
            "transcode" -> {
                transcode++
                transcodeDurationSec += watchedDurationSec
            }
        }
        if (hwTranscode) {
            this.hwTranscode++
            hwTranscodeDurationSec += watchedDurationSec
        }

        val epochDay = utcEpochDay(stoppedAtMs)
        daily.record(
            epochDay = epochDay,
            userId = userId,
            mediaType = mediaType,
            ratingKey = ratingKey,
            title = title,
            grandparentTitle = grandparentTitle,
            thumbPath = thumbPath,
            watchedDurationSec = watchedDurationSec,
        )

        if (mediaType.equals("movie", ignoreCase = true)) {
            val bucket = movies.getOrPut(ratingKey) { MovieBucket(ratingKey = ratingKey, title = title) }
            bucket.title = title
            bucket.playCount++
            bucket.watchDurationSec += watchedDurationSec
            if (bucket.thumbUrl == null) {
                bucket.thumbUrl = buildThumbUrl(ratingKey, thumbPath)
            }
        } else if (mediaType.equals("episode", ignoreCase = true)) {
            val showTitle = grandparentTitle?.takeIf { it.isNotBlank() } ?: title
            val bucket = shows.getOrPut(showTitle) { ShowBucket(title = showTitle, lastRatingKey = ratingKey) }
            bucket.playCount++
            bucket.watchDurationSec += watchedDurationSec
            bucket.lastRatingKey = ratingKey
            bucket.lastThumbPath = thumbPath
            if (bucket.firstThumbRatingKey == null && !thumbPath.isNullOrBlank()) {
                bucket.firstThumbRatingKey = ratingKey
                bucket.firstThumbPath = thumbPath
            }
        }
        return epochDay
    }

    fun build(
        metric: AnalyticsMetric,
        usersById: Map<String, UserRecord> = emptyMap(),
    ): CommonStatsResult {
        val decisionBreakdown =
            when (metric) {
                AnalyticsMetric.PLAYS ->
                    DecisionBreakdownDto(
                        directPlay = directPlay,
                        directStream = directStream,
                        transcode = transcode,
                        hwTranscode = hwTranscode,
                        total = totalPlays,
                        directPlayPercent = computeStatsPercent(directPlay, totalPlays),
                        directStreamPercent = computeStatsPercent(directStream, totalPlays),
                        transcodePercent = computeStatsPercent(transcode, totalPlays),
                        hwTranscodePercent = computeStatsPercent(hwTranscode, totalPlays),
                    )
                AnalyticsMetric.DURATION ->
                    DecisionBreakdownDto(
                        directPlay = directPlay,
                        directStream = directStream,
                        transcode = transcode,
                        hwTranscode = hwTranscode,
                        total = totalPlays,
                        directPlayPercent = computeStatsPercent(directPlayDurationSec, totalWatchDurationSec),
                        directStreamPercent = computeStatsPercent(directStreamDurationSec, totalWatchDurationSec),
                        transcodePercent = computeStatsPercent(transcodeDurationSec, totalWatchDurationSec),
                        hwTranscodePercent = computeStatsPercent(hwTranscodeDurationSec, totalWatchDurationSec),
                    )
            }

        val comparator = topMediaComparator(metric)
        val topMovies =
            movies.values
                .map { bucket ->
                    TopMediaStatDto(
                        ratingKey = bucket.ratingKey,
                        title = bucket.title,
                        playCount = bucket.playCount,
                        watchDurationSec = bucket.watchDurationSec,
                        thumbUrl = bucket.thumbUrl,
                    )
                }.sortedWith(comparator)
                .take(TOP_MEDIA_CAP)

        val topShows =
            shows.values
                .map { bucket ->
                    val rk = bucket.firstThumbRatingKey ?: bucket.lastRatingKey
                    val path = if (bucket.firstThumbRatingKey != null) bucket.firstThumbPath else bucket.lastThumbPath
                    TopMediaStatDto(
                        ratingKey = rk,
                        title = bucket.title,
                        playCount = bucket.playCount,
                        watchDurationSec = bucket.watchDurationSec,
                        thumbUrl = buildThumbUrl(rk, path),
                    )
                }.sortedWith(comparator)
                .take(TOP_MEDIA_CAP)

        return CommonStatsResult(
            totalPlays = totalPlays,
            totalWatchDurationSec = totalWatchDurationSec,
            activeUsers = activeUserIds.size,
            playsPerDay = daily.toDailyPlayStats(usersById = usersById, metric = metric),
            topMovies = topMovies,
            topShows = topShows,
            decisionBreakdown = decisionBreakdown,
        )
    }

    private class MovieBucket(
        val ratingKey: String,
        var title: String,
    ) {
        var thumbUrl: String? = null
        var playCount: Long = 0L
        var watchDurationSec: Long = 0L
    }

    private class ShowBucket(
        val title: String,
        var lastRatingKey: String,
    ) {
        var lastThumbPath: String? = null
        var firstThumbRatingKey: String? = null
        var firstThumbPath: String? = null
        var playCount: Long = 0L
        var watchDurationSec: Long = 0L
    }
}

internal class RewatchAndBreakdownAccumulator(
    trackLibraries: Boolean = false,
    trackClients: Boolean = false,
) {
    var completedPlays: Long = 0L
        private set
    private var completionSum: Double = 0.0
    private var rowCount: Long = 0L

    private val rewatched = HashMap<String, RewatchedBucket>()
    private val platforms = HashMap<String, CountDuration>()
    private val libraries = if (trackLibraries) HashMap<String?, CountDuration>() else null
    private val clients = if (trackClients) HashMap<String, CountDuration>() else null

    fun record(row: PlaybackHistoryStatsRow) {
        rowCount++
        completionSum += row.completionPercent
        if (row.isWatched) completedPlays++

        val duration = row.watchedDurationSec.toLong()
        val rewatchedBucket =
            rewatched.getOrPut(row.ratingKey) {
                RewatchedBucket(
                    ratingKey = row.ratingKey,
                    mediaType = row.mediaType,
                    title = row.title,
                    grandparentTitle = row.grandparentTitle,
                )
            }
        rewatchedBucket.playCount++
        rewatchedBucket.watchDurationSec += duration
        rewatchedBucket.mediaType = row.mediaType
        rewatchedBucket.title = row.title
        rewatchedBucket.grandparentTitle = row.grandparentTitle
        if (rewatchedBucket.thumbUrl == null) {
            rewatchedBucket.thumbUrl = buildThumbUrl(row.ratingKey, row.thumbPath)
        }

        val platformKey =
            row.playerPlatform?.takeIf { it.isNotBlank() }
                ?: row.playerProduct?.takeIf { it.isNotBlank() }
                ?: "Unknown"
        platforms.getOrPut(platformKey) { CountDuration() }.add(duration)

        if (libraries != null) {
            val libraryKey = row.librarySectionKey?.takeIf { it.isNotBlank() }
            libraries.getOrPut(libraryKey) { CountDuration() }.add(duration)
        }
        if (clients != null) {
            val clientKey =
                (row.playerProduct?.takeIf { it.isNotBlank() } ?: row.playerName?.takeIf { it.isNotBlank() } ?: "Unknown")
                    .ifBlank { "Unknown" }
            clients.getOrPut(clientKey) { CountDuration() }.add(duration)
        }
    }

    fun averageCompletionPercent(): Double {
        if (rowCount == 0L) return 0.0
        val avg = completionSum / rowCount
        return (avg * ROUND_SCALE_FACTOR).roundToLong() / ROUND_SCALE_FACTOR
    }

    fun buildMostRewatched(metric: AnalyticsMetric): List<RewatchedMediaStatDto> =
        rewatched.values
            .asSequence()
            .filter { it.playCount >= 2L }
            .map { bucket ->
                RewatchedMediaStatDto(
                    ratingKey = bucket.ratingKey,
                    mediaType = bucket.mediaType,
                    title = bucket.title,
                    grandparentTitle = bucket.grandparentTitle,
                    playCount = bucket.playCount,
                    watchDurationSec = bucket.watchDurationSec,
                    thumbUrl = bucket.thumbUrl,
                )
            }.sortedWith(rewatchedComparator(metric))
            .take(TOP_MEDIA_CAP)
            .toList()

    fun buildTopPlatforms(
        totalPlays: Long,
        totalWatchDurationSec: Long,
        metric: AnalyticsMetric,
    ): List<PlatformStatDto> =
        platforms.entries
            .map { (platform, stats) ->
                val pct =
                    when (metric) {
                        AnalyticsMetric.PLAYS -> computeStatsPercent(stats.playCount, totalPlays)
                        AnalyticsMetric.DURATION -> computeStatsPercent(stats.watchDurationSec, totalWatchDurationSec)
                    }
                PlatformStatDto(
                    platform = platform,
                    playCount = stats.playCount,
                    watchDurationSec = stats.watchDurationSec,
                    percent = pct,
                )
            }.sortedWith(platformComparator(metric))
            .take(TOP_MEDIA_CAP)

    fun buildLibraryBreakdown(
        totalPlays: Long,
        totalWatchDurationSec: Long,
        metric: AnalyticsMetric,
    ): List<LibraryBreakdownDto> =
        libraries
            .orEmpty()
            .entries
            .map { (librarySectionKey, stats) ->
                val pct =
                    when (metric) {
                        AnalyticsMetric.PLAYS -> computeStatsPercent(stats.playCount, totalPlays)
                        AnalyticsMetric.DURATION -> computeStatsPercent(stats.watchDurationSec, totalWatchDurationSec)
                    }
                LibraryBreakdownDto(
                    librarySectionKey = librarySectionKey,
                    playCount = stats.playCount,
                    watchDurationSec = stats.watchDurationSec,
                    percent = pct,
                )
            }.sortedWith(libraryComparator(metric))

    fun buildTopClients(
        totalPlays: Long,
        totalWatchDurationSec: Long,
        metric: AnalyticsMetric,
    ): List<ClientBreakdownItemDto> = buildClientBreakdownList(clients.orEmpty(), totalPlays, totalWatchDurationSec, metric)

    private class RewatchedBucket(
        val ratingKey: String,
        var mediaType: String,
        var title: String,
        var grandparentTitle: String?,
    ) {
        var thumbUrl: String? = null
        var playCount: Long = 0L
        var watchDurationSec: Long = 0L
    }
}

internal class WrappedCalendarAccumulator {
    private val monthCounts = LongArray(UTC_MONTH_NAMES.size)
    private val monthDurations = LongArray(UTC_MONTH_NAMES.size)
    private val dowCounts = LongArray(DAYS_PER_WEEK)
    private val dowDurations = LongArray(DAYS_PER_WEEK)
    private val uniqueShowsLower = HashSet<String>()

    private var lastEpochDay = Long.MIN_VALUE
    private var lastMonthIdx = 0

    val uniqueShowsCount: Int
        get() = uniqueShowsLower.size

    fun record(
        row: PlaybackHistoryStatsRow,
        epochDay: Long,
    ) {
        val duration = row.watchedDurationSec.toLong()
        if (epochDay != lastEpochDay) {
            lastEpochDay = epochDay
            lastMonthIdx = (LocalDate.ofEpochDay(epochDay).monthValue - 1).coerceIn(0, 11)
        }
        monthCounts[lastMonthIdx] += 1L
        monthDurations[lastMonthIdx] += duration

        val dayIdx = utcDayOfWeekIndex(epochDay)
        dowCounts[dayIdx] += 1L
        dowDurations[dayIdx] += duration

        if (row.mediaType.equals("episode", ignoreCase = true)) {
            uniqueShowsLower += (row.grandparentTitle?.takeIf { it.isNotBlank() } ?: row.title).lowercase()
        }
    }

    fun buildPlaysByMonth(): List<WrappedMonthStatDto> =
        UTC_MONTH_NAMES.mapIndexed { idx, name ->
            WrappedMonthStatDto(
                month = idx + 1,
                monthName = name,
                playCount = monthCounts[idx],
                watchDurationSec = monthDurations[idx],
            )
        }

    fun peakDayOfWeek(metric: AnalyticsMetric): String? {
        var bestIdx = 0
        for (idx in 1 until DAYS_PER_WEEK) {
            val isHigher =
                when (metric) {
                    AnalyticsMetric.PLAYS ->
                        dowCounts[idx] > dowCounts[bestIdx] ||
                            (dowCounts[idx] == dowCounts[bestIdx] && dowDurations[idx] > dowDurations[bestIdx])
                    AnalyticsMetric.DURATION ->
                        dowDurations[idx] > dowDurations[bestIdx] ||
                            (dowDurations[idx] == dowDurations[bestIdx] && dowCounts[idx] > dowCounts[bestIdx])
                }
            if (isHigher) {
                bestIdx = idx
            }
        }
        return if (dowCounts[bestIdx] > 0L) UTC_DAY_NAMES[bestIdx] else null
    }
}

internal class ClientStatsAccumulator {
    var totalPlays: Long = 0L
        private set
    var totalWatchDurationSec: Long = 0L
        private set
    var nonDirectTotalPlays: Long = 0L
        private set
    var nonDirectTotalDuration: Long = 0L
        private set

    private val players = HashMap<String, CountDuration>()
    private val platforms = HashMap<String, CountDuration>()
    private val videoCodecs = HashMap<String, CountDuration>()
    private val audioCodecs = HashMap<String, CountDuration>()
    private val transcodeReasons = HashMap<String, CountDuration>()

    fun record(row: ClientStatsRow) {
        val duration = row.watchedDurationSec.toLong()
        totalPlays++
        totalWatchDurationSec += duration

        val playerKey =
            (row.playerProduct?.takeIf { it.isNotBlank() } ?: row.playerName?.takeIf { it.isNotBlank() } ?: "Unknown")
                .ifBlank { "Unknown" }
        players.getOrPut(playerKey) { CountDuration() }.add(duration)

        val platformKey = (row.playerPlatform?.takeIf { it.isNotBlank() } ?: "Unknown").ifBlank { "Unknown" }
        platforms.getOrPut(platformKey) { CountDuration() }.add(duration)

        val videoCodecKey = LibraryNormalizer.normalizeCodec(row.videoCodec).ifBlank { "Unknown" }
        videoCodecs.getOrPut(videoCodecKey) { CountDuration() }.add(duration)

        val audioCodecKey = LibraryNormalizer.normalizeCodec(row.audioCodec).ifBlank { "Unknown" }
        audioCodecs.getOrPut(audioCodecKey) { CountDuration() }.add(duration)

        val reasons = describeConversions(row)
        if (reasons.isNotEmpty()) {
            nonDirectTotalPlays++
            nonDirectTotalDuration += duration
            for (reason in reasons) {
                transcodeReasons.getOrPut(reason) { CountDuration() }.add(duration)
            }
        }
    }

    fun buildPlayers(metric: AnalyticsMetric): List<ClientBreakdownItemDto> =
        buildClientBreakdownList(players, totalPlays, totalWatchDurationSec, metric)

    fun buildPlatforms(metric: AnalyticsMetric): List<ClientBreakdownItemDto> =
        buildClientBreakdownList(platforms, totalPlays, totalWatchDurationSec, metric)

    fun buildVideoCodecs(metric: AnalyticsMetric): List<ClientBreakdownItemDto> =
        buildClientBreakdownList(videoCodecs, totalPlays, totalWatchDurationSec, metric)

    fun buildAudioCodecs(metric: AnalyticsMetric): List<ClientBreakdownItemDto> =
        buildClientBreakdownList(audioCodecs, totalPlays, totalWatchDurationSec, metric)

    fun buildTranscodeReasons(metric: AnalyticsMetric): List<ClientBreakdownItemDto> =
        buildClientBreakdownList(transcodeReasons, nonDirectTotalPlays, nonDirectTotalDuration, metric)
}

internal fun buildClientBreakdownList(
    buckets: Map<String, CountDuration>,
    totalPlays: Long,
    totalWatchDurationSec: Long,
    metric: AnalyticsMetric,
): List<ClientBreakdownItemDto> =
    buckets.entries
        .map { (name, stats) ->
            val pct =
                when (metric) {
                    AnalyticsMetric.PLAYS -> computeStatsPercent(stats.playCount, totalPlays)
                    AnalyticsMetric.DURATION -> computeStatsPercent(stats.watchDurationSec, totalWatchDurationSec)
                }
            ClientBreakdownItemDto(
                name = name,
                playCount = stats.playCount,
                watchDurationSec = stats.watchDurationSec,
                percent = pct,
            )
        }.sortedWith(clientBreakdownComparator(metric))
        .take(TOP_MEDIA_CAP)

/**
 * Names what a non-Direct-Play session changed, such as "Subtitle Burn-in", "Video HEVC → H.264",
 * "Audio TRUEHD → AAC" or "Container MKV → MPEGTS". Returns an empty list for Direct Play.
 */
private fun describeConversions(row: ClientStatsRow): List<String> {
    val streamDec = row.streamDecision.trim().lowercase()
    val videoDec = row.videoDecision?.trim()?.lowercase()
    val audioDec = row.audioDecision?.trim()?.lowercase()
    val subtitleDec = row.subtitleDecision?.trim()?.lowercase()

    val isVideoTranscode = videoDec == "transcode"
    val isAudioTranscode = audioDec == "transcode"
    val isVideoCopy = videoDec in COPY_STREAM_DECISIONS
    val isAudioCopy = audioDec in COPY_STREAM_DECISIONS
    val burnsSubtitles = subtitleDec == "burn" && (isVideoTranscode || streamDec == "transcode")
    val transcodesSubtitles = subtitleDec == "transcode" && streamDec != "direct_play"

    val labels = mutableListOf<String>()
    if (burnsSubtitles) {
        labels += "Subtitle Burn-in"
    } else if (transcodesSubtitles) {
        labels += "Subtitle Transcode"
    }
    if (isVideoTranscode) {
        val from = knownCodec(row.videoCodec)
        val to = knownCodec(row.transcodeVideoCodec)
        val codecChanged = from != null && to != null && from != to
        if (!burnsSubtitles || codecChanged) {
            labels += describeCodecChange("Video", from, to)
        }
    }
    if (isAudioTranscode) {
        labels += describeCodecChange("Audio", knownCodec(row.audioCodec), knownCodec(row.transcodeAudioCodec))
    }
    if (labels.isEmpty()) {
        when {
            streamDec == "direct_stream" || isVideoCopy || isAudioCopy ->
                labels += describeContainerChange(row.container, row.transcodeContainer)
            streamDec == "transcode" -> labels += "Video Transcode"
        }
    }
    return labels
}

private fun describeCodecChange(
    kind: String,
    from: String?,
    to: String?,
): String =
    when {
        from != null && to != null && from != to -> "$kind $from → $to"
        from != null && to != null -> "$kind Re-encode ($to)"
        to != null -> "$kind → $to"
        else -> "$kind Transcode"
    }

private fun knownCodec(raw: String?): String? =
    raw
        ?.takeIf { it.isNotBlank() && it.trim() != "*" }
        ?.let(LibraryNormalizer::normalizeCodec)
        ?.takeIf { !it.equals("unknown", ignoreCase = true) }

private fun describeContainerChange(
    sourceContainer: String?,
    targetContainer: String?,
): String {
    val from = sourceContainer?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val to = targetContainer?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    return if (from != null && to != null && from != to) "Container $from → $to" else "Container Remux"
}

private fun topMediaComparator(metric: AnalyticsMetric): Comparator<TopMediaStatDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<TopMediaStatDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { it.title.lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<TopMediaStatDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { it.title.lowercase() }
    }

private fun clientBreakdownComparator(metric: AnalyticsMetric): Comparator<ClientBreakdownItemDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<ClientBreakdownItemDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { it.name.lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<ClientBreakdownItemDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { it.name.lowercase() }
    }

private fun platformComparator(metric: AnalyticsMetric): Comparator<PlatformStatDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<PlatformStatDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { it.platform.lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<PlatformStatDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { it.platform.lowercase() }
    }

private fun libraryComparator(metric: AnalyticsMetric): Comparator<LibraryBreakdownDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<LibraryBreakdownDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { it.librarySectionKey.orEmpty().lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<LibraryBreakdownDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { it.librarySectionKey.orEmpty().lowercase() }
    }

private fun rewatchedComparator(metric: AnalyticsMetric): Comparator<RewatchedMediaStatDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<RewatchedMediaStatDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { it.title.lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<RewatchedMediaStatDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { it.title.lowercase() }
    }
