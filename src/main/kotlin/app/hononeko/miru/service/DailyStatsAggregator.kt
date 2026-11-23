package app.hononeko.miru.service

import app.hononeko.miru.http.DailyMediaPlayDto
import app.hononeko.miru.http.DailyPlayStatDto
import app.hononeko.miru.http.DailyUserPlayDto
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.PlaybackHistoryStatsRow
import app.hononeko.miru.persistence.model.UserRecord
import java.time.Instant
import java.time.ZoneOffset

private const val DAILY_MEDIA_LIMIT = 10

internal fun aggregatePlaysPerDay(
    rows: List<PlaybackHistoryStatsRow>,
    usersById: Map<String, UserRecord>,
    metric: AnalyticsMetric,
    includeBreakdowns: Boolean = true,
): List<DailyPlayStatDto> {
    if (rows.isEmpty()) return emptyList()
    return rows
        .groupBy { row -> Instant.ofEpochMilli(row.stoppedAtMs).atZone(ZoneOffset.UTC).toLocalDate().toString() }
        .entries
        .sortedBy { it.key }
        .map { (date, dayRows) ->
            val playCount = dayRows.size.toLong()
            val watchDurationSec = dayRows.sumOf { it.watchedDurationSec.toLong() }
            if (!includeBreakdowns) {
                DailyPlayStatDto(
                    date = date,
                    playCount = playCount,
                    watchDurationSec = watchDurationSec,
                )
            } else {
                DailyPlayStatDto(
                    date = date,
                    playCount = playCount,
                    watchDurationSec = watchDurationSec,
                    media = aggregateDailyMedia(dayRows, usersById, metric),
                    users = aggregateDailyUsers(dayRows, usersById, metric),
                )
            }
        }
}

private fun aggregateDailyMedia(
    dayRows: List<PlaybackHistoryStatsRow>,
    usersById: Map<String, UserRecord>,
    metric: AnalyticsMetric,
): List<DailyMediaPlayDto> =
    dayRows
        .groupBy { row ->
            if (row.mediaType.equals("episode", ignoreCase = true)) {
                val showTitle = row.grandparentTitle?.trim()?.takeIf { it.isNotEmpty() } ?: row.title
                "show:${showTitle.lowercase()}"
            } else {
                "${row.mediaType.lowercase()}:${row.ratingKey}"
            }
        }.values
        .map { group ->
            val first = group.first()
            val isEpisode = first.mediaType.equals("episode", ignoreCase = true)
            val mediaType = if (isEpisode) "show" else first.mediaType.lowercase()
            val title =
                if (isEpisode) {
                    first.grandparentTitle?.trim()?.takeIf { it.isNotEmpty() } ?: group.last().title
                } else {
                    group.last().title
                }
            val ratingKey =
                if (isEpisode) {
                    (group.firstOrNull { !it.thumbPath.isNullOrBlank() } ?: group.last()).ratingKey
                } else {
                    first.ratingKey
                }
            val viewers =
                group
                    .groupBy { it.userId }
                    .entries
                    .map { (userId, userRows) ->
                        Triple(
                            resolveViewerLabel(usersById, userId),
                            userRows.size.toLong(),
                            userRows.sumOf { it.watchedDurationSec.toLong() },
                        )
                    }.sortedWith(
                        when (metric) {
                            AnalyticsMetric.PLAYS ->
                                compareByDescending<Triple<String, Long, Long>> { it.second }
                                    .thenByDescending { it.third }
                                    .thenBy { it.first.lowercase() }
                            AnalyticsMetric.DURATION ->
                                compareByDescending<Triple<String, Long, Long>> { it.third }
                                    .thenByDescending { it.second }
                                    .thenBy { it.first.lowercase() }
                        },
                    ).map { it.first }
                    .distinct()
            DailyMediaPlayDto(
                ratingKey = ratingKey,
                mediaType = mediaType,
                title = title,
                playCount = group.size.toLong(),
                watchDurationSec = group.sumOf { it.watchedDurationSec.toLong() },
                users = viewers,
            )
        }.sortedWith(
            when (metric) {
                AnalyticsMetric.PLAYS ->
                    compareByDescending<DailyMediaPlayDto> { it.playCount }
                        .thenByDescending { it.watchDurationSec }
                        .thenBy { it.title.lowercase() }
                AnalyticsMetric.DURATION ->
                    compareByDescending<DailyMediaPlayDto> { it.watchDurationSec }
                        .thenByDescending { it.playCount }
                        .thenBy { it.title.lowercase() }
            },
        ).take(DAILY_MEDIA_LIMIT)

private fun aggregateDailyUsers(
    dayRows: List<PlaybackHistoryStatsRow>,
    usersById: Map<String, UserRecord>,
    metric: AnalyticsMetric,
): List<DailyUserPlayDto> =
    dayRows
        .groupBy { it.userId }
        .map { (userId, userRows) ->
            val user = usersById[userId]
            DailyUserPlayDto(
                userId = userId,
                username = user?.username ?: userId,
                displayName = user?.displayName.normalizedDisplayName(),
                playCount = userRows.size.toLong(),
                watchDurationSec = userRows.sumOf { it.watchedDurationSec.toLong() },
            )
        }.sortedWith(
            when (metric) {
                AnalyticsMetric.PLAYS ->
                    compareByDescending<DailyUserPlayDto> { it.playCount }
                        .thenByDescending { it.watchDurationSec }
                        .thenBy { (it.displayName ?: it.username).lowercase() }
                AnalyticsMetric.DURATION ->
                    compareByDescending<DailyUserPlayDto> { it.watchDurationSec }
                        .thenByDescending { it.playCount }
                        .thenBy { (it.displayName ?: it.username).lowercase() }
            },
        )

private fun resolveViewerLabel(
    usersById: Map<String, UserRecord>,
    userId: String,
): String {
    val user = usersById[userId]
    return user?.displayName.normalizedDisplayName() ?: user?.username ?: userId
}
