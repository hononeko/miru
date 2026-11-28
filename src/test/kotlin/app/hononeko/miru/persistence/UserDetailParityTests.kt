package app.hononeko.miru.persistence

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.DecisionBreakdownDto
import app.hononeko.miru.http.LibraryBreakdownDto
import app.hononeko.miru.http.UserDetailResponse
import app.hononeko.miru.http.UserRecentPlayDto
import app.hononeko.miru.http.UserUsageStatDto
import app.hononeko.miru.http.buildThumbUrl
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistoryPage
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.toPlaybackHistoryRecord
import app.hononeko.miru.persistence.table.PlaybackHistoryTable
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.service.ViewerIdentityResolver
import app.hononeko.miru.service.computeStatsPercent
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.scopes.FunSpecRootScope
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.random.Random

private const val LEGACY_RECENT_PLAYS_LIMIT = 10
private const val LEGACY_TOP_STATS_LIMIT = 10
private const val LEGACY_UNKNOWN_LABEL = "Unknown"
private const val PARITY_BASE_STOPPED_AT_MS = 1_760_000_000_000L
private const val PARITY_TIE_DURATION_SEC = 77

private val PARITY_ADMIN = MiruPrincipal(accountId = "acc-admin", username = "admin", role = MiruRole.ADMIN)

private data class ParitySeed(
    val seed: Int,
    val maxPlaysPerProfile: Int,
)

/** From fewer plays than the recent list holds to enough for every label pool to overflow the top ten. */
private val PARITY_SEEDS =
    listOf(
        ParitySeed(seed = 11, maxPlaysPerProfile = 3),
        ParitySeed(seed = 23, maxPlaysPerProfile = 12),
        ParitySeed(seed = 37, maxPlaysPerProfile = 40),
        ParitySeed(seed = 41, maxPlaysPerProfile = 90),
        ParitySeed(seed = 53, maxPlaysPerProfile = 160),
    )

/** An account linked on three servers, one of its profiles, a lone profile, and one with no plays, scoped and not. */
private val PARITY_VIEWERS: List<Pair<String, String?>> =
    listOf(
        "acc-elena" to null,
        "acc-elena" to "srv-jelly",
        "acc-elena" to "srv-empty",
        "u-elena-plex" to null,
        "u-elena-plex" to "srv-plex",
        "u-solo" to null,
        "u-solo" to "srv-jelly",
        "u-idle" to null,
    )

private val PARITY_HISTORY_PROFILES =
    listOf(
        "u-elena-plex" to "srv-plex",
        "u-elena-jelly" to "srv-jelly",
        "u-elena-other" to "srv-other",
        "u-solo" to "srv-plex",
        "u-noise" to "srv-other",
    )

// Labels that only differ by case or blankness, Unknown spelled out, and more than ten of each, so the
// folding, merging, tie-breaking, and truncation of every breakdown is exercised
private val PARITY_PRODUCTS =
    listOf(
        null, null, "", "   ", "Plex Web", "plex web", "Plex for Apple TV", "Unknown", "Infuse", "Jellyfin Web",
        "Swiftfin", "Findroid", "Kodi", "VLC", "Plex HTPC", "Plexamp",
    )
private val PARITY_PLAYER_NAMES = listOf(null, "", "Chrome", "Living Room TV", "Unknown")
private val PARITY_PLATFORMS =
    listOf(
        null, "", " ", "\t", " ", "Chrome", "chrome", "tvOS", "Android", "Unknown", "Windows", "macOS", "iOS",
        "Linux", "webOS", "Roku", "Tizen",
    )
private val PARITY_DECISIONS = listOf("direct_play", "DIRECT_PLAY", "Direct_Stream", "direct_stream", "transcode", "TRANSCODE", "copy", "")
private val PARITY_SECTIONS = listOf(null, "", "  ", "1", "2", "10", "sec-movies", "sec-tv", "Sec-tv")
private val PARITY_DURATIONS = listOf(0, 30, 60, 600, 3_600)

private val PARITY_COVERAGE_CASES =
    listOf(
        "empty history",
        "fewer plays than the recent list",
        "more plays than the recent list",
        "plays from several linked profiles",
        "recent plays tied across profiles",
        "more than ten clients",
        "more than ten platforms",
        "clients tied on plays and watch time",
        "blank client or platform folded into Unknown",
        "null and blank libraries merged",
        "decisions outside the three counted",
    )

internal fun FunSpecRootScope.userDetailParityTests(handleProvider: () -> DatabaseHandle) {
    context("User detail history parity") {
        test("matches the full-history algorithm for linked, scoped, single, and empty viewers over seeded histories") {
            val fixture = ParityFixture(handleProvider())
            val covered = linkedSetOf<String>()
            for (seed in PARITY_SEEDS) {
                fixture.reset()
                fixture.seed(seed)
                for ((targetId, serverId) in PARITY_VIEWERS) {
                    withClue("seed=${seed.seed} target=$targetId serverId=$serverId") {
                        covered += fixture.assertMatchesLegacy(targetId, serverId)
                    }
                }
            }
            covered shouldContainAll PARITY_COVERAGE_CASES
        }

        test("reads full records only for the latest plays, however long the history") {
            val fixture = ParityFixture(handleProvider())
            fixture.seed(ParitySeed(seed = 97, maxPlaysPerProfile = 60))
            val recordReads = mutableListOf<String>()
            val spy =
                object : PlaybackHistoryRepository by fixture.history {
                    override suspend fun query(filter: HistoryFilter): Either<MiruError.PersistenceError, HistoryPage> =
                        fixture.history.query(filter).onRight { recordReads += "query(limit=${filter.limit})" }

                    override suspend fun listRecentByUser(
                        userId: String,
                        limit: Int,
                    ): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>> =
                        fixture.history.listRecentByUser(userId, limit).onRight { recordReads += "listRecentByUser(limit=$limit)" }

                    override suspend fun listRecentByUsers(
                        userIds: Set<String>,
                        serverId: String?,
                        limit: Int,
                    ): Either<MiruError.PersistenceError, List<PlaybackHistoryRecord>> =
                        fixture.history
                            .listRecentByUsers(userIds, serverId, limit)
                            .onRight { recordReads += "listRecentByUsers(limit=$limit) -> ${it.size}" }
                }

            val detail = fixture.serviceWith(spy).getUserDetail(PARITY_ADMIN, "acc-elena").shouldBeRight()

            fixture.legacyHistory(setOf("u-elena-plex", "u-elena-jelly", "u-elena-other"), null).size.toLong() shouldBe
                detail.totalPlays
            (detail.totalPlays > LEGACY_RECENT_PLAYS_LIMIT) shouldBe true
            recordReads shouldContainExactly listOf("listRecentByUsers(limit=10) -> 10")
        }
    }
}

private class ParityFixture(
    private val handle: DatabaseHandle,
) {
    val servers = ExposedMediaServerRepository(handle)
    val users = ExposedUserRepository(handle)
    val history = ExposedPlaybackHistoryRepository(handle)
    val authAccounts = ExposedAuthAccountRepository(handle)
    private val service = serviceWith(history)

    fun serviceWith(historyRepository: PlaybackHistoryRepository): StreamsAndUsersService =
        StreamsAndUsersService(
            activeSessionRepository = ExposedActiveSessionRepository(handle),
            userRepository = users,
            playbackHistoryRepository = historyRepository,
            mediaServerRepository = servers,
            authAccountRepository = authAccounts,
        )

    fun reset() {
        handle.dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM auth_accounts")
                stmt.executeUpdate("DELETE FROM media_servers")
            }
        }
    }

    suspend fun seed(seed: ParitySeed) {
        listOf(
            parityServer("srv-plex", "Plex", "PLEX"),
            parityServer("srv-jelly", " Jellyfin ", "JELLYFIN"),
            parityServer("srv-other", "Other Plex", "PLEX"),
            parityServer("srv-empty", "Empty", "PLEX"),
        ).forEach { servers.upsert(it).shouldBeRight() }
        PARITY_HISTORY_PROFILES.plus("u-idle" to "srv-jelly").forEach { (userId, serverId) ->
            users.upsert(parityUser(userId, serverId)).shouldBeRight()
        }
        authAccounts
            .insert(
                AuthAccountRecord(
                    id = "acc-elena",
                    username = "elena",
                    usernameNormalized = "elena",
                    passwordHash = "hash-elena",
                    role = "USER",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
        authAccounts
            .replaceLinkedMediaUserIds("acc-elena", setOf("u-elena-plex", "u-elena-jelly", "u-elena-other"), createdAtMs = 1_000L)
            .shouldBeRight()

        val records = generateParityHistory(seed)
        history.insertBatchIfAbsentByImportKey(records).shouldBeRight() shouldBe records.size
    }

    suspend fun assertMatchesLegacy(
        targetId: String,
        serverId: String?,
    ): Set<String> {
        val actual = service.getUserDetail(PARITY_ADMIN, targetId, serverId = serverId).shouldBeRight()
        val resolved = ViewerIdentityResolver.resolveViewer(users, authAccounts, targetId, serverId).shouldBeRight()
        val serversById = servers.findAll().shouldBeRight().associateBy { it.id }
        val fullHistory = legacyHistory(resolved.targetMediaUserIds, serverId)
        val legacy = LegacyUserHistoryView(fullHistory, serversById)

        val expected =
            actual.copy(
                user = actual.user.copy(totalPlays = legacy.totalPlays, totalWatchDurationSec = legacy.totalWatchDurationSec),
                totalPlays = legacy.totalPlays,
                totalWatchDurationSec = legacy.totalWatchDurationSec,
                recentPlays = legacy.recentPlays,
                topClients = legacy.topClients,
                topPlatforms = legacy.topPlatforms,
                decisionBreakdown = legacy.decisionBreakdown,
                libraryBreakdown = legacy.libraryBreakdown,
            )
        actual shouldBe expected
        MiruJson.encodeToString(UserDetailResponse.serializer(), actual) shouldBe
            MiruJson.encodeToString(UserDetailResponse.serializer(), expected)
        return legacy.coverage(fullHistory)
    }

    /** The query getUserDetail ran before it stopped loading every play: all of them, as full records. */
    suspend fun legacyHistory(
        userIds: Set<String>,
        serverId: String?,
    ): List<PlaybackHistoryRecord> {
        if (userIds.isEmpty()) return emptyList()
        return handle.readDatabase
            .exposedReadQuery("parity_legacy_list_by_users") {
                val byUsers: Op<Boolean> = PlaybackHistoryTable.userId inList userIds
                val condition = if (serverId != null) byUsers and (PlaybackHistoryTable.serverId eq serverId) else byUsers
                PlaybackHistoryTable
                    .selectAll()
                    .where { condition }
                    .orderBy(PlaybackHistoryTable.stoppedAtMs to SortOrder.DESC, PlaybackHistoryTable.id to SortOrder.ASC)
                    .map { it.toPlaybackHistoryRecord() }
            }.shouldBeRight()
    }
}

/** getUserDetail's history fields exactly as it derived them from the full history before the breakdown query. */
private class LegacyUserHistoryView(
    history: List<PlaybackHistoryRecord>,
    serversById: Map<String, MediaServerRecord>,
) {
    val totalPlays = history.size.toLong()
    val totalWatchDurationSec = history.sumOf { it.watchedDurationSec.toLong() }
    val recentPlays = history.take(LEGACY_RECENT_PLAYS_LIMIT).map { it.toLegacyRecentPlayDto(serversById[it.serverId]) }
    val allClients = legacyUsage(history) { it.player.playerProduct ?: it.player.playerName ?: LEGACY_UNKNOWN_LABEL }
    val allPlatforms = legacyUsage(history) { it.player.playerPlatform ?: LEGACY_UNKNOWN_LABEL }
    val topClients = allClients.take(LEGACY_TOP_STATS_LIMIT)
    val topPlatforms = allPlatforms.take(LEGACY_TOP_STATS_LIMIT)
    val decisionBreakdown = legacyDecisionBreakdown(history)
    val libraryBreakdown = legacyLibraryBreakdown(history)

    /** Which of [PARITY_COVERAGE_CASES] this history exercises, so the seeds are known to reach every edge. */
    fun coverage(history: List<PlaybackHistoryRecord>): Set<String> =
        buildSet {
            if (history.isEmpty()) add("empty history")
            if (history.size in 1 until LEGACY_RECENT_PLAYS_LIMIT) add("fewer plays than the recent list")
            if (history.size > LEGACY_RECENT_PLAYS_LIMIT) add("more plays than the recent list")
            if (history.map { it.userId }.toSet().size > 1) add("plays from several linked profiles")
            if (recentPlays.zipWithNext().any { (a, b) -> a.stoppedAtMs == b.stoppedAtMs && a.serverId != b.serverId }) {
                add("recent plays tied across profiles")
            }
            if (allClients.size > LEGACY_TOP_STATS_LIMIT) add("more than ten clients")
            if (allPlatforms.size > LEGACY_TOP_STATS_LIMIT) add("more than ten platforms")
            if (topClients.zipWithNext().any { (a, b) -> a.playCount == b.playCount && a.watchDurationSec == b.watchDurationSec }) {
                add("clients tied on plays and watch time")
            }
            if (history.any { it.player.playerProduct?.isBlank() == true || it.player.playerPlatform?.isBlank() == true }) {
                add("blank client or platform folded into Unknown")
            }
            val sections = history.map { it.media.librarySectionKey }
            if (null in sections && sections.any { it != null && it.isBlank() }) add("null and blank libraries merged")
            if (decisionBreakdown.directPlay + decisionBreakdown.directStream + decisionBreakdown.transcode < totalPlays) {
                add("decisions outside the three counted")
            }
        }
}

private fun legacyUsage(
    history: List<PlaybackHistoryRecord>,
    keySelector: (PlaybackHistoryRecord) -> String,
): List<UserUsageStatDto> =
    history
        .groupBy { keySelector(it).ifBlank { LEGACY_UNKNOWN_LABEL } }
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
        )

private fun legacyDecisionBreakdown(history: List<PlaybackHistoryRecord>): DecisionBreakdownDto {
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

private fun legacyLibraryBreakdown(history: List<PlaybackHistoryRecord>): List<LibraryBreakdownDto> =
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

private fun PlaybackHistoryRecord.toLegacyRecentPlayDto(server: MediaServerRecord?): UserRecentPlayDto =
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

private fun parityServer(
    id: String,
    name: String,
    serverType: String,
): MediaServerRecord =
    MediaServerRecord(
        id = id,
        name = name,
        serverType = serverType,
        machineId = "machine-$id",
        baseUrl = "http://$id.local:32400",
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun parityUser(
    id: String,
    serverId: String,
): UserRecord =
    UserRecord(
        id = id,
        serverId = serverId,
        externalUserId = "ext-$id",
        username = id.removePrefix("u-"),
        displayName = id.removePrefix("u-").replaceFirstChar { it.uppercase() },
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun generateParityHistory(seed: ParitySeed): List<PlaybackHistoryRecord> {
    val random = Random(seed.seed)
    // Few distinct stop times, so plays tie on recency within and across profiles
    val stopSlots = maxOf(4, seed.maxPlaysPerProfile / 2)
    var sequence = 0
    val generated =
        PARITY_HISTORY_PROFILES.flatMap { (userId, serverId) ->
            List(random.nextInt(0, seed.maxPlaysPerProfile + 1)) {
                sequence += 1
                val stoppedAtMs = PARITY_BASE_STOPPED_AT_MS + random.nextInt(0, stopSlots) * 60_000L
                randomParityPlay(random, parityHistoryId(random, sequence), serverId, userId, stoppedAtMs)
            }
        }
    // Two profiles' latest plays at the same moment, with clients, platforms, and libraries tied on every count
    val latestMs = PARITY_BASE_STOPPED_AT_MS + stopSlots * 60_000L
    val tied =
        listOf(
            Triple("Tie-1", "u-elena-plex", "srv-plex") to "B",
            Triple("tie-0", "u-elena-jelly", "srv-jelly") to "A",
        ).map { (target, suffix) ->
            val (id, userId, serverId) = target
            randomParityPlay(random, id, serverId, userId, latestMs).let { play ->
                play.copy(
                    watchedDurationSec = PARITY_TIE_DURATION_SEC,
                    media = play.media.copy(librarySectionKey = "tie-$suffix"),
                    player = play.player.copy(playerProduct = "Tie $suffix", playerPlatform = "Tie Platform $suffix"),
                )
            }
        }
    return generated + tied
}

/** Ids of differing shapes and case, so tied plays fall in the database's collation rather than any one format's. */
private fun parityHistoryId(
    random: Random,
    sequence: Int,
): String =
    when (random.nextInt(4)) {
        0 -> "h-$sequence"
        1 -> "H-$sequence"
        2 -> UUID(random.nextLong(), random.nextLong()).toString()
        else -> "h_${sequence}x"
    }

private fun randomParityPlay(
    random: Random,
    id: String,
    serverId: String,
    userId: String,
    stoppedAtMs: Long,
): PlaybackHistoryRecord {
    val mediaType = listOf("movie", "episode", "track").random(random)
    val isEpisode = mediaType == "episode"
    val ratingKey = "rk-${random.nextInt(30)}"
    val durationMs = random.nextLong(60_000L, 7_200_000L)
    return PlaybackHistoryRecord(
        id = id,
        serverId = serverId,
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = mediaType,
                title = "Title ${random.nextInt(50)}",
                parentTitle = if (isEpisode) "Season ${random.nextInt(1, 4)}" else null,
                grandparentTitle = if (isEpisode) "Show ${random.nextInt(5)}" else null,
                parentIndex = if (isEpisode) random.nextInt(1, 4) else null,
                mediaIndex = if (isEpisode) random.nextInt(1, 13) else null,
                year = if (random.nextBoolean()) random.nextInt(1990, 2027) else null,
                thumbPath = if (random.nextInt(4) != 0) "/library/metadata/$ratingKey/thumb/${random.nextInt(1_000)}" else null,
                librarySectionKey = PARITY_SECTIONS.random(random),
                durationMs = durationMs,
            ),
        viewOffsetMs = random.nextLong(0L, durationMs),
        watchedDurationSec = if (random.nextBoolean()) PARITY_DURATIONS.random(random) else random.nextInt(0, 7_200),
        pausedDurationSec = random.nextInt(0, 300),
        completionPercent = random.nextInt(0, 10_001) / 100.0,
        isWatched = random.nextBoolean(),
        streamTech =
            StreamTechRecord(
                streamDecision = PARITY_DECISIONS.random(random),
                hwTranscode = random.nextInt(3) == 0,
                videoResolution = listOf(null, "1080", "4k").random(random),
            ),
        player =
            PlayerRecord(
                playerName = PARITY_PLAYER_NAMES.random(random),
                playerPlatform = PARITY_PLATFORMS.random(random),
                playerProduct = PARITY_PRODUCTS.random(random),
                location = listOf(null, "lan", "wan").random(random),
            ),
        startedAtMs = stoppedAtMs - random.nextLong(0L, 7_200_000L),
        stoppedAtMs = stoppedAtMs,
    )
}
