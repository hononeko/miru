package app.hononeko.miru.service

import app.hononeko.miru.http.DailyMediaPlayDto
import app.hononeko.miru.http.DailyPlayStatDto
import app.hononeko.miru.http.DailyUserPlayDto
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.UserRecord
import java.time.LocalDate

private const val DAILY_MEDIA_LIMIT = 10

internal class DailyStatsAccumulator(
    private val includeBreakdowns: Boolean = true,
) {
    private val bucketsByDay = HashMap<Long, DailyBucket>()

    val activeDaysCount: Int
        get() = bucketsByDay.size

    fun distinctEpochDays(): Set<Long> = bucketsByDay.keys

    fun record(
        epochDay: Long,
        userId: String,
        mediaType: String,
        ratingKey: String,
        title: String,
        grandparentTitle: String?,
        thumbPath: String?,
        watchedDurationSec: Long,
    ) {
        val day = bucketsByDay.getOrPut(epochDay) { DailyBucket(includeBreakdowns) }
        day.playCount++
        day.watchDurationSec += watchedDurationSec
        if (!includeBreakdowns) return

        val mediaMap = day.media ?: return
        val usersMap = day.users ?: return

        val isEpisode = mediaType.equals("episode", ignoreCase = true)
        val trimmedGrandparent = grandparentTitle?.trim()?.takeIf { it.isNotEmpty() }
        val mediaKey =
            if (isEpisode) {
                val showTitle = trimmedGrandparent ?: title
                "show:${showTitle.lowercase()}"
            } else {
                "${mediaType.lowercase()}:$ratingKey"
            }

        val mediaBucket =
            mediaMap.getOrPut(mediaKey) {
                DailyMediaBucket(
                    isEpisode = isEpisode,
                    mediaType = if (isEpisode) "show" else mediaType.lowercase(),
                    firstRatingKey = ratingKey,
                    firstGrandparentTitle = trimmedGrandparent,
                    lastRatingKey = ratingKey,
                    lastTitle = title,
                )
            }
        mediaBucket.playCount++
        mediaBucket.watchDurationSec += watchedDurationSec
        mediaBucket.lastRatingKey = ratingKey
        mediaBucket.lastTitle = title
        if (mediaBucket.firstThumbRatingKey == null && !thumbPath.isNullOrBlank()) {
            mediaBucket.firstThumbRatingKey = ratingKey
        }
        mediaBucket.userStats.getOrPut(userId) { CountDuration() }.add(watchedDurationSec)
        usersMap.getOrPut(userId) { CountDuration() }.add(watchedDurationSec)
    }

    fun toDailyPlayStats(
        usersById: Map<String, UserRecord>,
        metric: AnalyticsMetric,
    ): List<DailyPlayStatDto> {
        if (bucketsByDay.isEmpty()) return emptyList()
        val sortedDays = bucketsByDay.keys.sorted()
        return sortedDays.map { epochDay ->
            val day = bucketsByDay.getValue(epochDay)
            val date = LocalDate.ofEpochDay(epochDay).toString()
            if (!includeBreakdowns) {
                DailyPlayStatDto(
                    date = date,
                    playCount = day.playCount,
                    watchDurationSec = day.watchDurationSec,
                )
            } else {
                DailyPlayStatDto(
                    date = date,
                    playCount = day.playCount,
                    watchDurationSec = day.watchDurationSec,
                    media = buildDailyMedia(day.media.orEmpty(), usersById, metric),
                    users = buildDailyUsers(day.users.orEmpty(), usersById, metric),
                )
            }
        }
    }

    private fun buildDailyMedia(
        mediaBuckets: Map<String, DailyMediaBucket>,
        usersById: Map<String, UserRecord>,
        metric: AnalyticsMetric,
    ): List<DailyMediaPlayDto> =
        mediaBuckets.values
            .map { bucket ->
                val resolvedTitle =
                    if (bucket.isEpisode) {
                        bucket.firstGrandparentTitle ?: bucket.lastTitle
                    } else {
                        bucket.lastTitle
                    }
                val resolvedRatingKey =
                    if (bucket.isEpisode) {
                        bucket.firstThumbRatingKey ?: bucket.lastRatingKey
                    } else {
                        bucket.firstRatingKey
                    }
                val viewers =
                    bucket.userStats.entries
                        .map { (userId, stats) ->
                            Triple(resolveViewerLabel(usersById, userId), stats.playCount, stats.watchDurationSec)
                        }.sortedWith(viewerComparator(metric))
                        .map { it.first }
                        .distinct()
                DailyMediaPlayDto(
                    ratingKey = resolvedRatingKey,
                    mediaType = bucket.mediaType,
                    title = resolvedTitle,
                    playCount = bucket.playCount,
                    watchDurationSec = bucket.watchDurationSec,
                    users = viewers,
                )
            }.sortedWith(dailyMediaComparator(metric))
            .take(DAILY_MEDIA_LIMIT)

    private fun buildDailyUsers(
        userBuckets: Map<String, CountDuration>,
        usersById: Map<String, UserRecord>,
        metric: AnalyticsMetric,
    ): List<DailyUserPlayDto> =
        userBuckets.entries
            .map { (userId, stats) ->
                val user = usersById[userId]
                DailyUserPlayDto(
                    userId = userId,
                    username = user?.username ?: userId,
                    displayName = user?.displayName.normalizedDisplayName(),
                    playCount = stats.playCount,
                    watchDurationSec = stats.watchDurationSec,
                )
            }.sortedWith(dailyUserComparator(metric))

    private class DailyBucket(
        includeBreakdowns: Boolean,
    ) {
        var playCount: Long = 0L
        var watchDurationSec: Long = 0L
        val media: HashMap<String, DailyMediaBucket>? = if (includeBreakdowns) HashMap() else null
        val users: HashMap<String, CountDuration>? = if (includeBreakdowns) HashMap() else null
    }

    private class DailyMediaBucket(
        val isEpisode: Boolean,
        val mediaType: String,
        val firstRatingKey: String,
        val firstGrandparentTitle: String?,
        var lastRatingKey: String,
        var lastTitle: String,
    ) {
        var firstThumbRatingKey: String? = null
        var playCount: Long = 0L
        var watchDurationSec: Long = 0L
        val userStats: HashMap<String, CountDuration> = HashMap()
    }
}

private fun viewerComparator(metric: AnalyticsMetric): Comparator<Triple<String, Long, Long>> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<Triple<String, Long, Long>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<Triple<String, Long, Long>> { it.third }
                .thenByDescending { it.second }
                .thenBy { it.first.lowercase() }
    }

private fun dailyMediaComparator(metric: AnalyticsMetric): Comparator<DailyMediaPlayDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<DailyMediaPlayDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { it.title.lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<DailyMediaPlayDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { it.title.lowercase() }
    }

private fun dailyUserComparator(metric: AnalyticsMetric): Comparator<DailyUserPlayDto> =
    when (metric) {
        AnalyticsMetric.PLAYS ->
            compareByDescending<DailyUserPlayDto> { it.playCount }
                .thenByDescending { it.watchDurationSec }
                .thenBy { (it.displayName ?: it.username).lowercase() }
        AnalyticsMetric.DURATION ->
            compareByDescending<DailyUserPlayDto> { it.watchDurationSec }
                .thenByDescending { it.playCount }
                .thenBy { (it.displayName ?: it.username).lowercase() }
    }

private fun resolveViewerLabel(
    usersById: Map<String, UserRecord>,
    userId: String,
): String {
    val user = usersById[userId]
    return user?.displayName.normalizedDisplayName() ?: user?.username ?: userId
}
