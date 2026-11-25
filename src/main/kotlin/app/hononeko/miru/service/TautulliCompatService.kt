package app.hononeko.miru.service

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.GrandparentItemRecord
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistorySortDirection
import app.hononeko.miru.persistence.model.HistorySortField
import app.hononeko.miru.persistence.model.MediaPlayRow
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.LibraryRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import java.time.LocalDate
import java.time.ZoneOffset

private const val MILLIS_PER_DAY = 86_400_000L

/** The home statistics of Tautulli that are about media, in Tautulli's order. */
enum class TautulliStat(
    val id: String,
    val title: String,
    val mediaType: String,
    val popular: Boolean,
) {
    TOP_MOVIES("top_movies", "Most Watched Movies", "movie", popular = false),
    POPULAR_MOVIES("popular_movies", "Most Popular Movies", "movie", popular = true),
    TOP_TV("top_tv", "Most Watched TV Shows", "episode", popular = false),
    POPULAR_TV("popular_tv", "Most Popular TV Shows", "episode", popular = true),
    TOP_MUSIC("top_music", "Most Played Artists", "track", popular = false),
    POPULAR_MUSIC("popular_music", "Most Popular Artists", "track", popular = true),
}

data class TautulliHomeStatsQuery(
    val stats: List<TautulliStat> = TautulliStat.entries,
    val timeRangeDays: Int = 30,
    val start: Int = 0,
    val count: Int = 10,
    val byDuration: Boolean = false,
    val sectionId: String? = null,
    /** Plex account IDs. */
    val userIds: Set<String>? = null,
)

data class TautulliHomeStat(
    val stat: TautulliStat,
    val rows: List<TautulliMediaStat>,
)

/** A movie, show or artist with its plays. [ratingKey] is the one of the movie, show or artist itself. */
data class TautulliMediaStat(
    val mediaType: String,
    val title: String,
    val year: Int?,
    val ratingKey: String,
    val sectionKey: String?,
    val thumbPath: String?,
    val plays: Int,
    val durationSec: Long,
    val users: Int,
    val lastStartedAtMs: Long,
)

data class TautulliHistoryQuery(
    /** Plex account IDs. */
    val userIds: Set<String>? = null,
    val ratingKeys: Set<String>? = null,
    val sectionIds: Set<String>? = null,
    val mediaTypes: Set<String>? = null,
    /** First and last day, in UTC. */
    val after: LocalDate? = null,
    val before: LocalDate? = null,
    val sortBy: HistorySortField = HistorySortField.STARTED_AT,
    val sortDir: HistorySortDirection = HistorySortDirection.DESC,
    val start: Int = 0,
    val length: Int = 25,
)

data class TautulliHistory(
    val rows: List<TautulliHistoryRow>,
    val filteredCount: Long,
    val totalCount: Long,
)

data class TautulliHistoryRow(
    val play: PlaybackHistoryRecord,
    val user: UserRecord?,
    val grandparentRatingKey: String?,
)

/**
 * Answers the questions of Tautulli's API that Kometa and similar tools ask, from Miru's own history.
 *
 * All answers are about the connected media server, because rating keys and sections only mean something there.
 */
class TautulliCompatService(
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val userRepository: UserRepository,
    private val libraryRepository: LibraryRepository,
    private val serverIdProvider: suspend () -> Either<MiruError, String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun homeStats(query: TautulliHomeStatsQuery): Either<MiruError, List<TautulliHomeStat>> =
        either {
            val serverId = serverIdProvider().bind()
            val mediaTypes = query.stats.mapTo(HashSet()) { it.mediaType }
            val accumulator = MediaStatAccumulator(libraryRepository.findGrandparents(serverId).bind())

            playbackHistoryRepository
                .forEachMediaPlay(
                    serverId = serverId,
                    mediaTypes = mediaTypes,
                    fromMs = clock() - query.timeRangeDays * MILLIS_PER_DAY,
                    userIds = query.userIds?.let { resolveUserIds(serverId, it) },
                ) { row ->
                    if (query.sectionId == null || row.sectionKey == query.sectionId) {
                        accumulator.record(row)
                    }
                }.bind()

            query.stats.map { stat ->
                TautulliHomeStat(
                    stat = stat,
                    rows =
                        accumulator
                            .stats(stat.mediaType)
                            .sortedWith(ranking(stat, query.byDuration))
                            .drop(query.start)
                            .take(query.count),
                )
            }
        }

    suspend fun history(query: TautulliHistoryQuery): Either<MiruError, TautulliHistory> =
        either {
            val serverId = serverIdProvider().bind()
            val filter =
                HistoryFilter(
                    serverId = serverId,
                    userIds = query.userIds?.let { resolveUserIds(serverId, it) },
                    ratingKeys = query.ratingKeys,
                    librarySectionKeys = query.sectionIds,
                    mediaTypes = query.mediaTypes,
                    startedFromMs = query.after?.startOfDayMs(),
                    startedBeforeMs = query.before?.plusDays(1)?.startOfDayMs(),
                    sortBy = query.sortBy,
                    sortDir = query.sortDir,
                    limit = query.length,
                    offset = query.start,
                )
            val page = playbackHistoryRepository.query(filter).bind()
            val total = playbackHistoryRepository.query(HistoryFilter(serverId = serverId, limit = 0)).bind().totalCount
            val users = userRepository.findByIds(page.items.map { it.userId }.distinct()).bind().associateBy { it.id }
            val grandparentKeys =
                libraryRepository
                    .findGrandparentKeys(serverId, page.items.mapTo(HashSet()) { it.media.ratingKey })
                    .bind()

            TautulliHistory(
                rows =
                    page.items.map { play ->
                        TautulliHistoryRow(
                            play = play,
                            user = users[play.userId],
                            grandparentRatingKey = grandparentKeys[play.media.ratingKey],
                        )
                    },
                filteredCount = page.totalCount,
                totalCount = total,
            )
        }

    private suspend fun Raise<MiruError>.resolveUserIds(
        serverId: String,
        externalUserIds: Set<String>,
    ): Set<String> =
        externalUserIds.mapNotNullTo(LinkedHashSet()) { externalId ->
            userRepository.findByExternalId(serverId, externalId).bind()?.id
        }

    private fun ranking(
        stat: TautulliStat,
        byDuration: Boolean,
    ): Comparator<TautulliMediaStat> {
        val byAmount =
            if (byDuration) {
                compareByDescending<TautulliMediaStat> { it.durationSec }
            } else {
                compareByDescending { it.plays }
            }
        val ranking =
            if (stat.popular) {
                compareByDescending<TautulliMediaStat> { it.users }.then(byAmount)
            } else {
                byAmount
            }
        return ranking.thenByDescending { it.lastStartedAtMs }.thenBy { it.ratingKey }
    }

    private fun LocalDate.startOfDayMs(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/**
 * Sums up plays per movie, show and artist. Plays have to be recorded oldest first.
 *
 * The history only has the rating key of the episode or track. Its show or artist comes from the library cache: by that
 * key, or by title when the item is no longer in the library. Plays of shows and artists that are not in the library,
 * or whose title is ambiguous there, are left out, as there is no rating key to report them with.
 */
private class MediaStatAccumulator(
    grandparents: List<GrandparentItemRecord>,
) {
    private data class GroupKey(
        val mediaType: String,
        val identity: String,
        val year: Int?,
    )

    private class Group(
        val mediaType: String,
        val ratingKeyOfGroup: String?,
    ) {
        var latest: MediaPlayRow? = null
        var plays = 0
        var durationSec = 0L
        val users = HashSet<String>()
    }

    private val groups = LinkedHashMap<GroupKey, Group>()
    private val keysBySectionAndTitle = grandparents.groupBy({ it.sectionKey to it.title }, { it.ratingKey })
    private val keysByTitle = grandparents.groupBy({ it.title }, { it.ratingKey })

    fun record(row: MediaPlayRow) {
        val group =
            if (row.mediaType == MOVIE) {
                groups.getOrPut(GroupKey(MOVIE, row.title, row.year)) { Group(MOVIE, ratingKeyOfGroup = null) }
            } else {
                val ratingKey = resolveGrandparentKey(row) ?: return
                groups.getOrPut(GroupKey(row.mediaType, ratingKey, year = null)) { Group(row.mediaType, ratingKey) }
            }
        group.latest = row
        group.plays++
        group.durationSec += row.watchedDurationSec
        group.users += row.userId
    }

    fun stats(mediaType: String): List<TautulliMediaStat> =
        groups.values
            .filter { it.mediaType == mediaType }
            .mapNotNull { group ->
                val latest = group.latest ?: return@mapNotNull null
                TautulliMediaStat(
                    mediaType = mediaType,
                    title = if (mediaType == MOVIE) latest.title else latest.grandparentTitle ?: latest.title,
                    year = latest.year,
                    ratingKey = group.ratingKeyOfGroup ?: latest.ratingKey,
                    sectionKey = latest.sectionKey,
                    // Of an episode or track it would not be the picture of the show or artist
                    thumbPath = latest.thumbPath.takeIf { mediaType == MOVIE },
                    plays = group.plays,
                    durationSec = group.durationSec,
                    users = group.users.size,
                    lastStartedAtMs = latest.startedAtMs,
                )
            }

    private fun resolveGrandparentKey(row: MediaPlayRow): String? {
        row.grandparentRatingKey?.let { return it }
        val title = row.grandparentTitle ?: return null
        val candidates =
            if (row.sectionKey != null) {
                keysBySectionAndTitle[row.sectionKey to title]
            } else {
                keysByTitle[title]
            }
        return candidates?.distinct()?.singleOrNull()
    }

    private companion object {
        const val MOVIE = "movie"
    }
}
