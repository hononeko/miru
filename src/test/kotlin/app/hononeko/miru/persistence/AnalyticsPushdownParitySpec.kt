package app.hononeko.miru.persistence

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.ClientStatsGroup
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HourlyPlayBucket
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.utcEpochDay
import app.hononeko.miru.service.utcHourOfDay
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.funSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.Random

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L
private val PARITY_NOW_MS = Instant.parse("2026-04-10T12:00:00Z").toEpochMilli()
private val TODAY = Math.floorDiv(PARITY_NOW_MS, DAY_MS)
private val PARITY_USERS = listOf("u-a1" to "srv-a", "u-a2" to "srv-a", "u-b1" to "srv-b")

/**
 * The repository as it aggregated before SQL did: every SQL aggregation falls back to the interface's default,
 * which folds the raw rows one by one.
 */
private class RawRowReference(
    delegate: PlaybackHistoryRepository,
) : PlaybackHistoryRepository by delegate {
    override suspend fun aggregatePlaysByUtcHour(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
    ): Either<MiruError.PersistenceError, List<HourlyPlayBucket>> =
        super<PlaybackHistoryRepository>.aggregatePlaysByUtcHour(serverId, userIds, fromMs, toMs)

    override suspend fun forEachClientStatsGroup(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
        onGroup: (ClientStatsGroup) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> =
        super<PlaybackHistoryRepository>.forEachClientStatsGroup(serverId, userIds, fromMs, toMs, onGroup)
}

/** Proves on SQLite and PostgreSQL that aggregations done in SQL answer exactly as folding the raw rows in Kotlin did. */
fun analyticsPushdownParitySpec(handleProvider: () -> DatabaseHandle): TestFactory =
    funSpec {
        beforeEach {
            handleProvider().dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM auth_accounts")
                    stmt.executeUpdate("DELETE FROM media_servers")
                }
            }
        }

        context("Analytics aggregated in SQL") {
            test("answer the heatmap and client breakdowns exactly as raw rows did") {
                val handle = handleProvider()
                val servers = ExposedMediaServerRepository(handle)
                val users = ExposedUserRepository(handle)
                val history = ExposedPlaybackHistoryRepository(handle)
                seedServersAndUsers(servers, users)
                seedAnalyticsHistory(history)

                val pushedDown = HistoryAndStatsService(history, users, clock = { PARITY_NOW_MS })
                val rawRows = HistoryAndStatsService(RawRowReference(history), users, clock = { PARITY_NOW_MS })
                val admin = MiruPrincipal(accountId = "acc-admin", username = "admin", role = MiruRole.ADMIN)
                val viewer =
                    MiruPrincipal(
                        accountId = "acc-alice",
                        username = "alice",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("u-a1", "u-b1"),
                    )
                val scopes = listOf(admin to null, admin to "u-a2", viewer to null, viewer to "u-b1")

                for ((principal, userId) in scopes) {
                    for (serverId in listOf(null, "srv-a", "srv-b")) {
                        for (metric in listOf("plays", "duration")) {
                            for (range in listOf("7d", "30d")) {
                                val heatmap = pushedDown.getHeatmapStats(principal, range, userId, metric, serverId).shouldBeRight()
                                heatmap shouldBe rawRows.getHeatmapStats(principal, range, userId, metric, serverId).shouldBeRight()
                            }
                            for (range in listOf("7d", "30d", "90d", "365d", "all")) {
                                val clients = pushedDown.getClientStats(principal, range, userId, metric, serverId).shouldBeRight()
                                clients shouldBe rawRows.getClientStats(principal, range, userId, metric, serverId).shouldBeRight()
                            }
                        }
                    }
                }

                assertHeatmapMatchesRawCells(history, pushedDown, admin)
                val clients = pushedDown.getClientStats(admin, "all").shouldBeRight()
                clients.totalPlays shouldBe history.query(HistoryFilter(limit = Int.MAX_VALUE)).shouldBeRight().totalCount
                clients.transcodeReasons.shouldNotBeEmpty()
            }
        }
    }

private suspend fun seedServersAndUsers(
    servers: ExposedMediaServerRepository,
    users: ExposedUserRepository,
) {
    for ((id, type) in listOf("srv-a" to "PLEX", "srv-b" to "JELLYFIN")) {
        servers
            .upsert(
                MediaServerRecord(
                    id = id,
                    name = id,
                    serverType = type,
                    machineId = "machine-$id",
                    baseUrl = "http://$id.local",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
    }
    for ((id, serverId) in PARITY_USERS) {
        users
            .upsert(
                UserRecord(
                    id = id,
                    serverId = serverId,
                    externalUserId = "ext-$id",
                    username = id,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
    }
}

/** Plays over the last 40 days and beyond, with the boundaries of the heatmap's days and hours, some of them resumed. */
private suspend fun seedAnalyticsHistory(history: ExposedPlaybackHistoryRepository) {
    history.insertBatchIfAbsentByImportKey(randomPlays(count = 400, seed = 42L)).shouldBeRight()
    val firstHeatmapDay = TODAY - 29
    val edges =
        listOf(
            firstHeatmapDay * DAY_MS - 1,
            firstHeatmapDay * DAY_MS,
            (TODAY - 6) * DAY_MS - 1,
            (TODAY - 6) * DAY_MS,
            (TODAY - 1) * DAY_MS + 13 * HOUR_MS - 1,
            (TODAY - 1) * DAY_MS + 13 * HOUR_MS,
            PARITY_NOW_MS - 30 * DAY_MS,
            PARITY_NOW_MS - 30 * DAY_MS - 1,
            PARITY_NOW_MS,
            (TODAY + 1) * DAY_MS - 1,
            (TODAY + 1) * DAY_MS,
        )
    edges.forEachIndexed { idx, stoppedAtMs ->
        val (userId, serverId) = PARITY_USERS[idx % PARITY_USERS.size]
        history.insert(parityPlay("hm-edge-$idx", serverId, userId, stoppedAtMs, watchedSec = 100 + idx)).shouldBeRight()
    }
    // Resumed plays, some of them carried past midnight into the next day
    val merges = history.query(HistoryFilter(limit = 9, userIds = setOf("u-a1"))).shouldBeRight().items
    merges.forEachIndexed { idx, play ->
        val stoppedAtMs =
            if (idx % 3 == 0) (Math.floorDiv(play.stoppedAtMs, DAY_MS) + 1) * DAY_MS + 60_000L else play.stoppedAtMs + 300_000L
        val merged =
            play.copy(
                watchedDurationSec = play.watchedDurationSec + 300,
                stoppedAtMs = stoppedAtMs,
                streamTech = play.streamTech.copy(streamDecision = "transcode", hwTranscode = idx % 2 == 0, videoDecision = "transcode"),
            )
        history.updateMergedSession(merged).shouldBeRight() shouldBe true
    }
}

private suspend fun assertHeatmapMatchesRawCells(
    history: ExposedPlaybackHistoryRepository,
    service: HistoryAndStatsService,
    admin: MiruPrincipal,
) {
    val heatmap = service.getHeatmapStats(admin, "30d").shouldBeRight()
    val fromMs = (TODAY - 29) * DAY_MS
    val toMs = (TODAY + 1) * DAY_MS - 1
    val expected =
        history
            .query(HistoryFilter(fromMs = fromMs, toMs = toMs, limit = Int.MAX_VALUE))
            .shouldBeRight()
            .items
            .groupBy { LocalDate.ofEpochDay(utcEpochDay(it.stoppedAtMs)).toString() to utcHourOfDay(it.stoppedAtMs) }
            .mapValues { (_, plays) -> plays.size.toLong() to plays.sumOf { it.watchedDurationSec.toLong() } }
    heatmap.cells.filter { it.playCount > 0 }.associate { (it.date to it.hour) to (it.playCount to it.watchDurationSec) } shouldBe
        expected
    heatmap.totalPlays shouldBe expected.values.sumOf { it.first }
    heatmap.totalPlays shouldBeGreaterThan 100L
}

private val DECISIONS = listOf("direct_play", "direct_stream", "transcode", "DIRECT_PLAY", "Transcode", "copy")
private val SUB_DECISIONS = listOf("transcode", "copy", "direct_play", "directstream", null)
private val SUBTITLES = listOf("burn", "transcode", "copy", null)
private val VIDEO_CODECS = listOf("hevc", "h264", "HEVC", "*", "av1", null)
private val AUDIO_CODECS = listOf("truehd", "aac", "eac3", "AAC", null)
private val CONTAINERS = listOf("mkv", "mp4", " ", null)
private val PRODUCTS = listOf("Plex Web", "Plex for Android (TV)", "Infuse", "Jellyfin Web", "", "  ", null)
private val PLAYER_NAMES = listOf("Chrome", "Living Room", "Roku", "", null)
private val PLATFORMS = listOf("Chrome", "Android", "tvOS", "Roku", "", " ", null)
private val MEDIA_TYPES = listOf("movie", "episode", "episode", "track", "Movie")

/** Plays spread over the last 40 days and a few much older, mixing every column the analytics read. */
private fun randomPlays(
    count: Int,
    seed: Long,
): List<PlaybackHistoryRecord> {
    val random = Random(seed)
    fun <T> List<T>.pick(): T = this[random.nextInt(size)]
    return (1..count).map { idx ->
        val (userId, serverId) = PARITY_USERS.pick()
        val daysAgo = if (idx % 25 == 0) 100L + random.nextInt(400) else random.nextInt(40).toLong()
        val stoppedAtMs = (TODAY - daysAgo) * DAY_MS + random.nextLong(DAY_MS)
        val play = parityPlay("rnd-$idx", serverId, userId, stoppedAtMs, random.nextInt(7_200), DECISIONS.pick())
        play.copy(
            media = play.media.copy(mediaType = MEDIA_TYPES.pick(), ratingKey = "rk-${random.nextInt(30)}"),
            streamTech =
                play.streamTech.copy(
                    videoDecision = SUB_DECISIONS.pick(),
                    audioDecision = SUB_DECISIONS.pick(),
                    subtitleDecision = SUBTITLES.pick(),
                    hwTranscode = random.nextBoolean(),
                    videoCodec = VIDEO_CODECS.pick(),
                    audioCodec = AUDIO_CODECS.pick(),
                    transcodeVideoCodec = VIDEO_CODECS.pick(),
                    transcodeAudioCodec = AUDIO_CODECS.pick(),
                    container = CONTAINERS.pick(),
                    transcodeContainer = CONTAINERS.pick(),
                ),
            player = PlayerRecord(playerProduct = PRODUCTS.pick(), playerName = PLAYER_NAMES.pick(), playerPlatform = PLATFORMS.pick()),
        )
    }
}

private fun parityPlay(
    id: String,
    serverId: String,
    userId: String,
    stoppedAtMs: Long,
    watchedSec: Int = 120,
    decision: String = "direct_play",
): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = id,
        serverId = serverId,
        userId = userId,
        media = MediaMetadataRecord(ratingKey = "rk-$id", mediaType = "movie", title = "Title $id", durationMs = 7_200_000L),
        viewOffsetMs = watchedSec * 1_000L,
        watchedDurationSec = watchedSec,
        completionPercent = 42.5,
        isWatched = false,
        streamTech = StreamTechRecord(streamDecision = decision),
        player = PlayerRecord(playerName = "Web"),
        startedAtMs = stoppedAtMs - watchedSec * 1_000L,
        stoppedAtMs = stoppedAtMs,
    )
