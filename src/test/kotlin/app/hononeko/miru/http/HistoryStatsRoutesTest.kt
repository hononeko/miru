package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.service.HistoryAndStatsService
import arrow.core.Either
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private class HistoryTestHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val authService: AuthService,
    val historyService: HistoryAndStatsService,
    val appConfig: AppConfig,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private val FIXED_NOW_MS = Instant.parse("2026-04-15T12:00:00Z").toEpochMilli()

private fun createHistoryHarness(authMode: AuthMode = AuthMode.LOCAL): HistoryTestHarness {
    val dir = Files.createTempDirectory("miru-history-routes-test-")
    val dbPath = dir.resolve("miru-history.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val userRepo = ExposedUserRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val authConfig =
        AuthConfig(
            mode = authMode,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        )
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
            clock = { FIXED_NOW_MS },
        )
    val historyService =
        HistoryAndStatsService(
            playbackHistoryRepository = historyRepo,
            userRepository = userRepo,
            clock = { FIXED_NOW_MS },
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "plex-secret-token"),
            session = SessionConfig(),
            auth = authConfig,
        )
    return HistoryTestHarness(
        tempDir = dir,
        dbHandle = handle,
        serverRepo = ExposedMediaServerRepository(handle),
        userRepo = userRepo,
        historyRepo = historyRepo,
        authService = authService,
        historyService = historyService,
        appConfig = appConfig,
    )
}

private fun ApplicationTestBuilder.setupApp(harness: HistoryTestHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            historyAndStatsService = harness.historyService,
        )
    }
}

private suspend fun seedUsersAndHistory(harness: HistoryTestHarness) {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Main Plex",
                serverType = "PLEX",
                machineId = "mach-1",
                baseUrl = "http://plex.local:32400",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    harness.userRepo
        .upsert(
            UserRecord(
                id = "u-alice",
                serverId = "srv-1",
                externalUserId = "101",
                username = "alice",
                displayName = "Alice",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    harness.userRepo
        .upsert(
            UserRecord(
                id = "u-bob",
                serverId = "srv-1",
                externalUserId = "102",
                username = "bob",
                displayName = "Bob",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()

    buildSeedHistoryRecords().forEach { harness.historyRepo.insert(it).shouldBeRight() }
}

private fun buildSeedHistoryRecords(): List<PlaybackHistoryRecord> =
    buildSeedMovieRecords() + buildSeedEpisodeRecords()

private fun buildSeedMovieRecords(): List<PlaybackHistoryRecord> =
    listOf(
        historyMovieRecord(
            id = "hist-1",
            userId = "u-alice",
            ratingKey = "mov-1",
            title = "Spirited Away",
            year = 2001,
            thumbPath = "/library/metadata/mov-1/thumb",
            watchedDurationSec = 7_200,
            pausedDurationSec = 45,
            completionPercent = 96.0,
            streamTech =
                StreamTechRecord(
                    streamDecision = "direct_play",
                    videoDecision = "directplay",
                    audioDecision = "directplay",
                    hwTranscode = false,
                    videoCodec = "hevc",
                    audioCodec = "flac",
                    videoResolution = "1080p",
                    bandwidthKbps = 12_000,
                ),
            player =
                PlayerRecord(
                    playerName = "Living Room Apple TV",
                    playerPlatform = "tvOS",
                    playerProduct = "Plex for Apple TV",
                    playerMachineId = "atv-1",
                    ipAddress = "10.0.0.15",
                    location = "lan",
                ),
            startedAt = "2026-04-13T18:00:00Z",
            stoppedAt = "2026-04-13T20:05:00Z",
        ),
        historyMovieRecord(
            id = "hist-3",
            userId = "u-bob",
            ratingKey = "mov-1",
            title = "Spirited Away",
            year = 2001,
            thumbPath = null,
            watchedDurationSec = 3_000,
            pausedDurationSec = 0,
            completionPercent = 40.0,
            streamTech =
                StreamTechRecord(
                    streamDecision = "direct_stream",
                    videoDecision = "copy",
                    audioDecision = "transcode",
                    hwTranscode = false,
                    videoCodec = "hevc",
                    audioCodec = "aac",
                    videoResolution = "1080p",
                    bandwidthKbps = 8_000,
                ),
            player =
                PlayerRecord(
                    playerName = "Pixel 9",
                    playerPlatform = "Android",
                    playerProduct = "Plex for Android",
                    playerMachineId = "and-1",
                    ipAddress = "10.0.0.42",
                    location = "lan",
                ),
            startedAt = "2026-03-26T20:00:00Z",
            stoppedAt = "2026-03-26T20:50:00Z",
        ),
        historyMovieRecord(
            id = "hist-5",
            userId = "u-alice",
            ratingKey = "mov-2",
            title = "Akira",
            year = 1988,
            thumbPath = "/library/metadata/mov-2/thumb",
            watchedDurationSec = 7_440,
            pausedDurationSec = 0,
            completionPercent = 100.0,
            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
            player = PlayerRecord(playerName = "Chrome", playerPlatform = "Web", playerProduct = "Plex Web"),
            startedAt = "2025-12-16T18:00:00Z",
            stoppedAt = "2025-12-16T20:04:00Z",
        ),
    )

private fun buildSeedEpisodeRecords(): List<PlaybackHistoryRecord> =
    listOf(
        historyEpisodeRecord(
            id = "hist-2",
            userId = "u-alice",
            ratingKey = "ep-1",
            title = "The Journey Begins",
            grandparentTitle = "Frieren",
            year = 2023,
            thumbPath = "/library/metadata/ep-1/thumb",
            librarySectionKey = "sec-anime",
            durationMs = 1_440_000L,
            viewOffsetMs = 1_400_000L,
            watchedDurationSec = 1_400,
            pausedDurationSec = 10,
            completionPercent = 97.2,
            streamTech =
                StreamTechRecord(
                    streamDecision = "transcode",
                    videoDecision = "transcode",
                    audioDecision = "copy",
                    hwTranscode = true,
                    videoCodec = "h264",
                    audioCodec = "aac",
                    videoResolution = "720p",
                    bandwidthKbps = 4_000,
                ),
            player =
                PlayerRecord(
                    playerName = "Chrome",
                    playerPlatform = "Web",
                    playerProduct = "Plex Web",
                    playerMachineId = "web-1",
                    ipAddress = "203.0.113.10",
                    location = "wan",
                ),
            startedAt = "2026-04-14T19:00:00Z",
            stoppedAt = "2026-04-14T19:25:00Z",
        ),
        historyEpisodeRecord(
            id = "hist-4",
            userId = "u-bob",
            ratingKey = "ep-2",
            title = "Pilot",
            grandparentTitle = "Andor",
            year = 2022,
            thumbPath = "/library/metadata/ep-2/thumb",
            librarySectionKey = "sec-tv",
            durationMs = 2_400_000L,
            viewOffsetMs = 2_350_000L,
            watchedDurationSec = 2_350,
            pausedDurationSec = 30,
            completionPercent = 98.0,
            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
            player = PlayerRecord(playerName = "iPad", playerPlatform = "iOS", playerProduct = "Plex for iOS"),
            startedAt = "2026-02-14T21:00:00Z",
            stoppedAt = "2026-02-14T21:40:00Z",
        ),
    )

@Suppress("LongParameterList")
private fun historyMovieRecord(
    id: String,
    userId: String,
    ratingKey: String,
    title: String,
    year: Int,
    thumbPath: String?,
    watchedDurationSec: Int,
    pausedDurationSec: Int,
    completionPercent: Double,
    streamTech: StreamTechRecord,
    player: PlayerRecord,
    startedAt: String,
    stoppedAt: String,
): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = id,
        serverId = "srv-1",
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = "movie",
                title = title,
                year = year,
                thumbPath = thumbPath,
                librarySectionKey = "sec-movies",
                durationMs = 7_500_000L,
            ),
        viewOffsetMs = watchedDurationSec * 1_000L,
        watchedDurationSec = watchedDurationSec,
        pausedDurationSec = pausedDurationSec,
        completionPercent = completionPercent,
        isWatched = completionPercent >= 80.0,
        streamTech = streamTech,
        player = player,
        startedAtMs = Instant.parse(startedAt).toEpochMilli(),
        stoppedAtMs = Instant.parse(stoppedAt).toEpochMilli(),
    )

@Suppress("LongParameterList")
private fun historyEpisodeRecord(
    id: String,
    userId: String,
    ratingKey: String,
    title: String,
    grandparentTitle: String,
    year: Int,
    thumbPath: String?,
    librarySectionKey: String,
    durationMs: Long,
    viewOffsetMs: Long,
    watchedDurationSec: Int,
    pausedDurationSec: Int,
    completionPercent: Double,
    streamTech: StreamTechRecord,
    player: PlayerRecord,
    startedAt: String,
    stoppedAt: String,
): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = id,
        serverId = "srv-1",
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = "episode",
                title = title,
                parentTitle = "Season 1",
                grandparentTitle = grandparentTitle,
                parentIndex = 1,
                mediaIndex = 1,
                year = year,
                thumbPath = thumbPath,
                librarySectionKey = librarySectionKey,
                durationMs = durationMs,
            ),
        viewOffsetMs = viewOffsetMs,
        watchedDurationSec = watchedDurationSec,
        pausedDurationSec = pausedDurationSec,
        completionPercent = completionPercent,
        isWatched = completionPercent >= 80.0,
        streamTech = streamTech,
        player = player,
        startedAtMs = Instant.parse(startedAt).toEpochMilli(),
        stoppedAtMs = Instant.parse(stoppedAt).toEpochMilli(),
    )

class HistoryStatsRoutesTest :
    FunSpec({
        test("rejects unauthenticated requests with 401 when auth mode is LOCAL") {
            createHistoryHarness().use { harness ->
                harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                testApplication {
                    setupApp(harness)
                    client.get("/api/v1/history").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/history/hist-1").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/stats/overview").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/stats/heatmap").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/stats/clients").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/stats/wrapped/2026").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/users/u-alice/stats").status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        test("returns empty collections and zero metrics on empty dataset") {
            createHistoryHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                testApplication {
                    setupApp(harness)

                    val historyResp =
                        client.get("/api/v1/history") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    historyResp.status shouldBe HttpStatusCode.OK
                    val historyBody = MiruJson.decodeFromString<HistoryListResponse>(historyResp.bodyAsText())
                    historyBody.items.shouldBeEmpty()
                    historyBody.totalCount shouldBe 0L
                    historyBody.totalPages shouldBe 0

                    val overviewResp =
                        client.get("/api/v1/stats/overview?range=all") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    overviewResp.status shouldBe HttpStatusCode.OK
                    val overview = MiruJson.decodeFromString<OverviewStatsResponse>(overviewResp.bodyAsText())
                    overview.totalPlays shouldBe 0L
                    overview.totalWatchDurationSec shouldBe 0L
                    overview.activeUsers shouldBe 0
                    overview.playsPerDay.shouldBeEmpty()
                    overview.topMovies.shouldBeEmpty()
                    overview.topShows.shouldBeEmpty()
                    overview.decisionBreakdown.directPlayPercent shouldBe 0.0
                }
            }
        }

        test("supports all filters, sorting, pagination, and detail endpoint for ADMIN") {
            createHistoryHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedUsersAndHistory(harness)

                testApplication {
                    setupApp(harness)

                    // Filter by userId, mediaType, libraryId, decision, from/to, q
                    val filteredResp =
                        client.get(
                            "/api/v1/history?userId=u-alice&mediaType=episode&libraryId=sec-anime" +
                                "&decision=transcode&from=2026-04-14T00:00:00Z&to=2026-04-15T00:00:00Z&q=frieren",
                        ) {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    filteredResp.status shouldBe HttpStatusCode.OK
                    val filtered = MiruJson.decodeFromString<HistoryListResponse>(filteredResp.bodyAsText())
                    filtered.totalCount shouldBe 1L
                    filtered.totalPages shouldBe 1
                    filtered.items.single().let { item ->
                        item.id shouldBe "hist-2"
                        item.username shouldBe "alice"
                        item.displayName shouldBe "Alice"
                        item.thumbUrl shouldBe "/api/v1/media/ep-1/thumb"
                        item.streamDecision shouldBe "transcode"
                        item.hwTranscode shouldBe true
                    }

                    // Sorting by title ascending + pagination
                    val sortedPage1Resp =
                        client.get("/api/v1/history?sortBy=title&sortDir=asc&page=1&pageSize=2") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    sortedPage1Resp.status shouldBe HttpStatusCode.OK
                    val sortedPage1 = MiruJson.decodeFromString<HistoryListResponse>(sortedPage1Resp.bodyAsText())
                    sortedPage1.totalCount shouldBe 5L
                    sortedPage1.totalPages shouldBe 3
                    sortedPage1.items.map { it.title } shouldContainExactly listOf("Akira", "Pilot")

                    // Sorting by watched_duration desc, completion_percent asc, started_at asc
                    val durationDesc =
                        MiruJson.decodeFromString<HistoryListResponse>(
                            client
                                .get("/api/v1/history?sortBy=watched_duration&sortDir=desc&pageSize=2") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    durationDesc.items.map { it.id } shouldContainExactly listOf("hist-5", "hist-1")

                    val completionAsc =
                        MiruJson.decodeFromString<HistoryListResponse>(
                            client
                                .get("/api/v1/history?sortBy=completion_percent&sortDir=asc&pageSize=2") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    completionAsc.items.map { it.id } shouldContainExactly listOf("hist-3", "hist-1")

                    val startedAsc =
                        MiruJson.decodeFromString<HistoryListResponse>(
                            client
                                .get("/api/v1/history?sortBy=started_at&sortDir=asc&pageSize=2") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    startedAsc.items.map { it.id } shouldContainExactly listOf("hist-5", "hist-4")

                    // History detail endpoint
                    val detailResp =
                        client.get("/api/v1/history/hist-1") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    detailResp.status shouldBe HttpStatusCode.OK
                    val detail = MiruJson.decodeFromString<HistoryDetailResponse>(detailResp.bodyAsText())
                    detail.id shouldBe "hist-1"
                    detail.username shouldBe "alice"
                    detail.displayName shouldBe "Alice"
                    detail.media.thumbUrl shouldBe "/api/v1/media/mov-1/thumb"
                    detail.streamTech.videoCodec shouldBe "hevc"
                    detail.streamTech.audioCodec shouldBe "flac"
                    detail.player.ipAddress shouldBe "10.0.0.15"
                    detail.pausedDurationSec shouldBe 45
                    detail.completionPercent shouldBe 96.0

                    // Missing record -> 404
                    val missingDetail =
                        client.get("/api/v1/history/does-not-exist") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    missingDetail.status shouldBe HttpStatusCode.NotFound
                    missingDetail.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
                }
            }
        }

        test("computes overview and user stats across 7d, 30d, 90d, and all ranges") {
            createHistoryHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedUsersAndHistory(harness)

                testApplication {
                    setupApp(harness)

                    val overview7d =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=7d") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    overview7d.range shouldBe "7d"
                    overview7d.totalPlays shouldBe 2L
                    overview7d.totalWatchDurationSec shouldBe 8_600L
                    overview7d.activeUsers shouldBe 1
                    overview7d.playsPerDay.map { it.date to it.playCount } shouldContainExactly
                        listOf("2026-04-13" to 1L, "2026-04-14" to 1L)
                    overview7d.decisionBreakdown.directPlay shouldBe 1L
                    overview7d.decisionBreakdown.transcode shouldBe 1L
                    overview7d.decisionBreakdown.hwTranscode shouldBe 1L
                    overview7d.decisionBreakdown.directPlayPercent shouldBe 50.0
                    overview7d.decisionBreakdown.transcodePercent shouldBe 50.0
                    overview7d.decisionBreakdown.hwTranscodePercent shouldBe 50.0

                    val overview30d =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    overview30d.range shouldBe "30d"
                    overview30d.totalPlays shouldBe 3L
                    overview30d.activeUsers shouldBe 2
                    overview30d.topMovies.single().let { movie ->
                        movie.ratingKey shouldBe "mov-1"
                        movie.title shouldBe "Spirited Away"
                        movie.playCount shouldBe 2L
                        movie.watchDurationSec shouldBe 10_200L
                        movie.thumbUrl shouldBe "/api/v1/media/mov-1/thumb"
                    }
                    overview30d.topShows.single().let { show ->
                        show.title shouldBe "Frieren"
                        show.playCount shouldBe 1L
                        show.thumbUrl shouldBe "/api/v1/media/ep-1/thumb"
                    }

                    val overview90d =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=90d") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    overview90d.totalPlays shouldBe 4L

                    val overviewAll =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=all") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    overviewAll.fromMs.shouldBeNull()
                    overviewAll.totalPlays shouldBe 5L

                    // User stats
                    val aliceStatsResp =
                        client.get("/api/v1/users/u-alice/stats?range=all") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    aliceStatsResp.status shouldBe HttpStatusCode.OK
                    val aliceStats = MiruJson.decodeFromString<UserStatsResponse>(aliceStatsResp.bodyAsText())
                    aliceStats.userId shouldBe "u-alice"
                    aliceStats.username shouldBe "alice"
                    aliceStats.totalPlays shouldBe 3L
                    aliceStats.topMovies shouldHaveSize 2
                    aliceStats.topShows shouldHaveSize 1
                    aliceStats.topPlatforms.map { it.platform to it.playCount } shouldContainExactly
                        listOf("Web" to 2L, "tvOS" to 1L)
                    aliceStats.libraryBreakdown.map { it.librarySectionKey to it.playCount } shouldContainExactly
                        listOf("sec-movies" to 2L, "sec-anime" to 1L)

                    // Missing user -> 404
                    val missingUserResp =
                        client.get("/api/v1/users/u-missing/stats") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    missingUserResp.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("enforces USER RBAC scoping on history, detail, overview stats, and user stats") {
            createHistoryHarness().use { harness ->
                harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedUsersAndHistory(harness)

                val aliceAccount =
                    harness.authService
                        .createAccount(
                            username = "alice-user",
                            password = "Alice-strong-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("u-alice"),
                        ).shouldBeRight()
                val aliceApiKey =
                    harness.authService
                        .createApiKey(
                            accountId = aliceAccount.account.id,
                            name = "Alice Key",
                            role = MiruRole.USER,
                        ).shouldBeRight()

                val unlinkedAccount =
                    harness.authService
                        .createAccount(
                            username = "unlinked-user",
                            password = "Unlinked-strong-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = emptySet(),
                        ).shouldBeRight()
                val unlinkedLogin =
                    harness.authService.login(unlinkedAccount.account.username, "Unlinked-strong-password-123").shouldBeRight()

                testApplication {
                    setupApp(harness)

                    // Scoped history without userId only returns alice's plays
                    val aliceHistoryResp =
                        client.get("/api/v1/history") {
                            header("X-Api-Key", aliceApiKey.rawKey)
                        }
                    aliceHistoryResp.status shouldBe HttpStatusCode.OK
                    val aliceHistory = MiruJson.decodeFromString<HistoryListResponse>(aliceHistoryResp.bodyAsText())
                    aliceHistory.totalCount shouldBe 3L
                    aliceHistory.items.all { it.userId == "u-alice" } shouldBe true

                    // Querying another user's history returns 403
                    client
                        .get("/api/v1/history?userId=u-bob") {
                            header("X-Api-Key", aliceApiKey.rawKey)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // Detail of own play succeeds, other user's play returns 403
                    client
                        .get("/api/v1/history/hist-1") {
                            header("X-Api-Key", aliceApiKey.rawKey)
                        }.status shouldBe HttpStatusCode.OK
                    client
                        .get("/api/v1/history/hist-3") {
                            header("X-Api-Key", aliceApiKey.rawKey)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // Overview stats for USER only counts alice's plays
                    val aliceOverview =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=30d") {
                                    header("X-Api-Key", aliceApiKey.rawKey)
                                }.bodyAsText(),
                        )
                    aliceOverview.totalPlays shouldBe 2L
                    aliceOverview.activeUsers shouldBe 1

                    // User stats allowed for own linked user, forbidden for bob
                    client
                        .get("/api/v1/users/u-alice/stats") {
                            header("X-Api-Key", aliceApiKey.rawKey)
                        }.status shouldBe HttpStatusCode.OK
                    client
                        .get("/api/v1/users/u-bob/stats") {
                            header("X-Api-Key", aliceApiKey.rawKey)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // Unlinked USER gets empty history & zero overview stats
                    val unlinkedHistory =
                        MiruJson.decodeFromString<HistoryListResponse>(
                            client
                                .get("/api/v1/history") {
                                    cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                                }.bodyAsText(),
                        )
                    unlinkedHistory.totalCount shouldBe 0L
                    unlinkedHistory.items.shouldBeEmpty()

                    val unlinkedOverview =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=all") {
                                    cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                                }.bodyAsText(),
                        )
                    unlinkedOverview.totalPlays shouldBe 0L
                    unlinkedOverview.totalWatchDurationSec shouldBe 0L
                    unlinkedOverview.activeUsers shouldBe 0
                    unlinkedOverview.playsPerDay.shouldBeEmpty()
                    unlinkedOverview.topMovies.shouldBeEmpty()
                    unlinkedOverview.topShows.shouldBeEmpty()
                    unlinkedOverview.decisionBreakdown.directPlay shouldBe 0L
                    unlinkedOverview.decisionBreakdown.directStream shouldBe 0L
                    unlinkedOverview.decisionBreakdown.transcode shouldBe 0L
                    unlinkedOverview.decisionBreakdown.directPlayPercent shouldBe 0.0

                    // Unlinked USER gets 403 on other user's filter, history details, and user stats
                    client
                        .get("/api/v1/history?userId=u-alice") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/history/hist-1") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/history/does-not-exist") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/users/u-alice/stats") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("returns 400 ProblemDetails with field errors for invalid query parameters") {
            createHistoryHarness(authMode = AuthMode.NONE).use { harness ->
                seedUsersAndHistory(harness)
                testApplication {
                    setupApp(harness)

                    val invalidHistoryResp =
                        client.get(
                            "/api/v1/history?page=0&pageSize=500&mediaType=podcast" +
                                "&decision=invalid&from=not-a-date&to=bad-date&sortBy=unknown&sortDir=sideways",
                        )
                    invalidHistoryResp.status shouldBe HttpStatusCode.BadRequest
                    invalidHistoryResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
                    val problem = MiruJson.decodeFromString<ProblemDetails>(invalidHistoryResp.bodyAsText())
                    val errors = problem.errors.shouldNotBeNull()
                    errors shouldContainKey "page"
                    errors shouldContainKey "pageSize"
                    errors shouldContainKey "mediaType"
                    errors shouldContainKey "decision"
                    errors shouldContainKey "from"
                    errors shouldContainKey "to"
                    errors shouldContainKey "sortBy"
                    errors shouldContainKey "sortDir"

                    // Non-integer page/pageSize and from > to
                    val badTypesResp =
                        client.get(
                            "/api/v1/history?page=abc&pageSize=xyz" +
                                "&from=2026-04-15T00:00:00Z&to=2026-04-01T00:00:00Z",
                        )
                    badTypesResp.status shouldBe HttpStatusCode.BadRequest
                    val badTypesErrors = MiruJson.decodeFromString<ProblemDetails>(badTypesResp.bodyAsText()).errors.shouldNotBeNull()
                    badTypesErrors shouldContainKey "page"
                    badTypesErrors shouldContainKey "pageSize"
                    badTypesErrors shouldContainKey "to"

                    // Invalid range on overview and user stats
                    val badOverviewRange = client.get("/api/v1/stats/overview?range=1y")
                    badOverviewRange.status shouldBe HttpStatusCode.BadRequest
                    val badOverviewErrors =
                        MiruJson.decodeFromString<ProblemDetails>(badOverviewRange.bodyAsText()).errors.shouldNotBeNull()
                    badOverviewErrors shouldContainKey "range"

                    val badUserRange = client.get("/api/v1/users/u-alice/stats?range=bad")
                    badUserRange.status shouldBe HttpStatusCode.BadRequest
                    val badUserErrors =
                        MiruJson.decodeFromString<ProblemDetails>(badUserRange.bodyAsText()).errors.shouldNotBeNull()
                    badUserErrors shouldContainKey "range"

                    for (unsupportedRange in listOf("invalid", "90d", "365d", "all")) {
                        val badHeatmapRange = client.get("/api/v1/stats/heatmap?range=$unsupportedRange")
                        badHeatmapRange.status shouldBe HttpStatusCode.BadRequest
                        MiruJson.decodeFromString<ProblemDetails>(badHeatmapRange.bodyAsText()).errors.shouldNotBeNull() shouldContainKey
                            "range"
                    }

                    val badClientsRange = client.get("/api/v1/stats/clients?range=invalid")
                    badClientsRange.status shouldBe HttpStatusCode.BadRequest
                    MiruJson.decodeFromString<ProblemDetails>(badClientsRange.bodyAsText()).errors.shouldNotBeNull() shouldContainKey
                        "range"

                    val badWrappedYear = client.get("/api/v1/stats/wrapped/not-a-year")
                    badWrappedYear.status shouldBe HttpStatusCode.BadRequest
                    MiruJson.decodeFromString<ProblemDetails>(badWrappedYear.bodyAsText()).errors.shouldNotBeNull() shouldContainKey
                        "year"

                    val badMetric = client.get("/api/v1/stats/clients?metric=invalid")
                    badMetric.status shouldBe HttpStatusCode.BadRequest
                    MiruJson.decodeFromString<ProblemDetails>(badMetric.bodyAsText()).errors.shouldNotBeNull() shouldContainKey
                        "metric"
                }
            }
        }

        test("leaves heatmap samples outside its days out, whatever the repository returns") {
            createHistoryHarness().use { harness ->
                val dayMs = 86_400_000L
                val firstDayMs = Instant.parse("2026-04-09T00:00:00Z").toEpochMilli()
                val endMs = firstDayMs + 7 * dayMs
                val unfiltered =
                    object : PlaybackHistoryRepository by harness.historyRepo {
                        override suspend fun forEachHeatmapSample(
                            serverId: String?,
                            userIds: Set<String>?,
                            fromMs: Long?,
                            toMs: Long?,
                            onRow: (stoppedAtMs: Long, watchedDurationSec: Int) -> Unit,
                        ): Either<MiruError.PersistenceError, Unit> {
                            // 2^32 days away is day 0 again when truncated to an Int
                            listOf(Long.MIN_VALUE, firstDayMs - 1, endMs, firstDayMs + (1L shl 32) * dayMs, Long.MAX_VALUE)
                                .forEach { onRow(it, 999) }
                            onRow(firstDayMs, 60)
                            onRow(endMs - 1, 30)
                            return Unit.right()
                        }
                    }
                val service = HistoryAndStatsService(unfiltered, harness.userRepo, clock = { FIXED_NOW_MS })
                val admin = MiruPrincipal(accountId = "acc-admin", username = "admin", role = MiruRole.ADMIN)

                val heatmap = service.getHeatmapStats(principal = admin, rangeRaw = "7d").shouldBeRight()
                heatmap.totalPlays shouldBe 2L
                heatmap.totalWatchDurationSec shouldBe 90L
                heatmap.cells.filter { it.playCount > 0 }.map { Triple(it.date, it.hour, it.playCount) } shouldContainExactly
                    listOf(Triple("2026-04-09", 0, 1L), Triple("2026-04-15", 23, 1L))
            }
        }

        test("computes heatmap, client breakdowns, user completion/binge/rewatched metrics, and annual wrapped stats with RBAC") {
            createHistoryHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedUsersAndHistory(harness)

                // Add a rewatch for Alice on Spirited Away (mov-1) so mostRewatched is populated
                harness.historyRepo
                    .insert(
                        historyMovieRecord(
                            id = "hist-6",
                            userId = "u-alice",
                            ratingKey = "mov-1",
                            title = "Spirited Away",
                            year = 2001,
                            thumbPath = "/library/metadata/mov-1/thumb",
                            watchedDurationSec = 7_500,
                            pausedDurationSec = 0,
                            completionPercent = 100.0,
                            streamTech =
                                StreamTechRecord(
                                    streamDecision = "direct_play",
                                    videoDecision = "directplay",
                                    audioDecision = "directplay",
                                    hwTranscode = false,
                                    videoCodec = "hevc",
                                    audioCodec = "flac",
                                    videoResolution = "1080p",
                                    bandwidthKbps = 12_000,
                                ),
                            player =
                                PlayerRecord(
                                    playerName = "Living Room Apple TV",
                                    playerPlatform = "tvOS",
                                    playerProduct = "Plex for Apple TV",
                                    playerMachineId = "atv-1",
                                ),
                            startedAt = "2026-04-12T18:00:00Z",
                            stoppedAt = "2026-04-12T20:05:00Z",
                        ),
                    ).shouldBeRight()

                val aliceAccount =
                    harness.authService
                        .createAccount(
                            username = "alice-viewer",
                            password = "Alice-strong-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("u-alice"),
                        ).shouldBeRight()
                val aliceKey =
                    harness.authService
                        .createApiKey(
                            accountId = aliceAccount.account.id,
                            name = "Alice Viewer Key",
                            role = MiruRole.USER,
                        ).shouldBeRight()

                testApplication {
                    setupApp(harness)

                    // 1. Heatmap endpoint (default range = 7d, 30d, RBAC)
                    val heatmapResp =
                        client.get("/api/v1/stats/heatmap") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    heatmapResp.status shouldBe HttpStatusCode.OK
                    val heatmap = MiruJson.decodeFromString<HeatmapStatsResponse>(heatmapResp.bodyAsText())
                    heatmap.range shouldBe "7d"
                    heatmap.totalPlays shouldBe 3L
                    heatmap.cells shouldHaveSize 7 * 24
                    heatmap.cells.first().date shouldBe "2026-04-09"
                    heatmap.cells.last().date shouldBe "2026-04-15"
                    heatmap.maxCellPlays shouldBe 1L
                    heatmap.peakDate shouldBe "2026-04-12"
                    heatmap.peakDayName shouldBe "Sun"
                    heatmap.peakHour shouldBe 20

                    val heatmap30 =
                        MiruJson.decodeFromString<HeatmapStatsResponse>(
                            client
                                .get("/api/v1/stats/heatmap?range=30d") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    heatmap30.range shouldBe "30d"
                    heatmap30.totalPlays shouldBe 4L
                    heatmap30.cells shouldHaveSize 30 * 24

                    val aliceHeatmap =
                        MiruJson.decodeFromString<HeatmapStatsResponse>(
                            client
                                .get("/api/v1/stats/heatmap?range=30d") {
                                    header("X-Api-Key", aliceKey.rawKey)
                                }.bodyAsText(),
                        )
                    aliceHeatmap.totalPlays shouldBe 3L
                    client
                        .get("/api/v1/stats/heatmap?userId=u-bob") {
                            header("X-Api-Key", aliceKey.rawKey)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // 2. Clients & codec breakdown endpoint
                    val clientsResp =
                        client.get("/api/v1/stats/clients?range=90d") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    clientsResp.status shouldBe HttpStatusCode.OK
                    val clients = MiruJson.decodeFromString<ClientStatsResponse>(clientsResp.bodyAsText())
                    clients.range shouldBe "90d"
                    clients.totalPlays shouldBe 5L
                    clients.transcodeOrRemuxPlays shouldBe 2L
                    clients.players.first().name shouldBe "Plex for Apple TV"
                    clients.players.first().playCount shouldBe 2L
                    clients.platforms.first().name shouldBe "tvOS"
                    clients.videoCodecs.first { it.name == "HEVC" }.playCount shouldBe 3L
                    clients.audioCodecs.map { it.name }.toSet() shouldBe setOf("AAC", "FLAC", "Unknown")
                    clients.transcodeReasons.map { it.name to it.playCount } shouldContainExactly
                        listOf("Audio Transcode" to 1L, "Video Transcode" to 1L)

                    val clientsDuration =
                        MiruJson.decodeFromString<ClientStatsResponse>(
                            client
                                .get("/api/v1/stats/clients?range=90d&metric=duration") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    clientsDuration.players.first().name shouldBe "Plex for Apple TV"
                    clientsDuration.players.first().watchDurationSec shouldBe 14_700L
                    clientsDuration.players.first().percent shouldBe 68.53

                    client
                        .get("/api/v1/stats/clients?userId=u-bob") {
                            header("X-Api-Key", aliceKey.rawKey)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // 3. User stats completion, binge streak, and mostRewatched
                    val aliceUserStats =
                        MiruJson.decodeFromString<UserStatsResponse>(
                            client
                                .get("/api/v1/users/u-alice/stats?range=90d") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    aliceUserStats.totalPlays shouldBe 3L
                    aliceUserStats.completedPlays shouldBe 3L
                    aliceUserStats.averageCompletionPercent shouldBe 97.73
                    aliceUserStats.longestBingeStreakDays shouldBe 3
                    aliceUserStats.maxDailyPlays shouldBe 1
                    aliceUserStats.mostRewatched.single().let { item ->
                        item.ratingKey shouldBe "mov-1"
                        item.title shouldBe "Spirited Away"
                        item.playCount shouldBe 2L
                        item.watchDurationSec shouldBe 14_700L
                        item.thumbUrl shouldBe "/api/v1/media/mov-1/thumb"
                    }

                    // 4. Wrapped endpoint
                    val wrappedResp =
                        client.get("/api/v1/stats/wrapped/2026?userId=u-alice") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    wrappedResp.status shouldBe HttpStatusCode.OK
                    val wrapped = MiruJson.decodeFromString<WrappedStatsResponse>(wrappedResp.bodyAsText())
                    wrapped.year shouldBe 2026
                    wrapped.userId shouldBe "u-alice"
                    wrapped.username shouldBe "alice"
                    wrapped.totalPlays shouldBe 3L
                    wrapped.completedPlays shouldBe 3L
                    wrapped.longestBingeStreakDays shouldBe 3
                    wrapped.activeDaysCount shouldBe 3
                    wrapped.uniqueMoviesCount shouldBe 1
                    wrapped.uniqueShowsCount shouldBe 1
                    wrapped.peakMonth shouldBe "Apr"
                    wrapped.mostRewatched.single().ratingKey shouldBe "mov-1"
                    wrapped.playsByMonth shouldHaveSize 12
                    wrapped.playsByMonth.first { it.month == 4 }.playCount shouldBe 3L

                    client
                        .get("/api/v1/stats/wrapped/2026?userId=u-bob") {
                            header("X-Api-Key", aliceKey.rawKey)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/stats/wrapped/2026?userId=u-missing") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("exposes stream conversion fields in history details and normalizes blank displayName to null") {
            createHistoryHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedUsersAndHistory(harness)
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "u-blank",
                            serverId = "srv-1",
                            externalUserId = "300",
                            username = "blankuser",
                            displayName = "   ",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.historyRepo
                    .insert(
                        historyMovieRecord(
                            id = "hist-conv",
                            userId = "u-blank",
                            ratingKey = "77001",
                            title = "Resonance",
                            year = 2014,
                            thumbPath = "/library/metadata/77000/thumb/1700000200",
                            watchedDurationSec = 200,
                            pausedDurationSec = 0,
                            completionPercent = 95.0,
                            streamTech =
                                StreamTechRecord(
                                    streamDecision = "transcode",
                                    videoDecision = "transcode",
                                    audioDecision = "transcode",
                                    hwTranscode = true,
                                    videoCodec = "hevc",
                                    audioCodec = "truehd",
                                    transcodeVideoCodec = "h264",
                                    transcodeAudioCodec = "aac",
                                    container = "mkv",
                                    transcodeContainer = "mpegts",
                                    subtitleDecision = "burn",
                                    videoResolution = "4k",
                                    bandwidthKbps = 15_000,
                                ),
                            player = PlayerRecord(playerProduct = "Plex Web"),
                            startedAt = "2026-04-14T20:00:00Z",
                            stoppedAt = "2026-04-14T20:05:00Z",
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupApp(harness)
                    val detailResp =
                        client.get("/api/v1/history/hist-conv") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    detailResp.status shouldBe HttpStatusCode.OK
                    val detail = MiruJson.decodeFromString<HistoryDetailResponse>(detailResp.bodyAsText())
                    detail.username shouldBe "blankuser"
                    detail.displayName.shouldBeNull()
                    detail.media.thumbUrl shouldBe "/api/v1/media/77000/thumb"
                    detail.streamTech.transcodeVideoCodec shouldBe "h264"
                    detail.streamTech.transcodeAudioCodec shouldBe "aac"
                    detail.streamTech.container shouldBe "mkv"
                    detail.streamTech.transcodeContainer shouldBe "mpegts"
                    detail.streamTech.subtitleDecision shouldBe "burn"

                    val userStatsResp =
                        client.get("/api/v1/users/u-blank/stats?range=30d") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    userStatsResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<UserStatsResponse>(userStatsResp.bodyAsText()).displayName.shouldBeNull()

                    val wrappedResp =
                        client.get("/api/v1/stats/wrapped/2026?userId=u-blank") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    wrappedResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<WrappedStatsResponse>(wrappedResp.bodyAsText()).displayName.shouldBeNull()
                }
            }
        }

        test("includes per-day media and viewer breakdowns sorted by metric and scoped by user access") {
            createHistoryHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedUsersAndHistory(harness)

                val aliceAccount =
                    harness.authService
                        .createAccount(
                            username = "alice-daily",
                            password = "Alice-strong-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("u-alice"),
                        ).shouldBeRight()
                val aliceLogin = harness.authService.login("alice-daily", "Alice-strong-password-123").shouldBeRight()
                aliceAccount.account.id.shouldNotBeNull()

                val extraRecords =
                    listOf(
                        historyEpisodeRecord(
                            id = "hist-day-frieren-2",
                            userId = "u-alice",
                            ratingKey = "ep-1b",
                            title = "Killing the Aura",
                            grandparentTitle = "Frieren",
                            year = 2023,
                            thumbPath = "/library/metadata/ep-1b/thumb",
                            librarySectionKey = "sec-anime",
                            durationMs = 4_200_000L,
                            viewOffsetMs = 4_100_000L,
                            watchedDurationSec = 4_100,
                            pausedDurationSec = 0,
                            completionPercent = 97.6,
                            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
                            player = PlayerRecord(playerName = "Apple TV", playerPlatform = "tvOS"),
                            startedAt = "2026-04-14T20:00:00Z",
                            stoppedAt = "2026-04-14T21:10:00Z",
                        ),
                        historyMovieRecord(
                            id = "hist-day-short-1",
                            userId = "u-bob",
                            ratingKey = "mov-short",
                            title = "Short Clip",
                            year = 2024,
                            thumbPath = null,
                            watchedDurationSec = 100,
                            pausedDurationSec = 0,
                            completionPercent = 100.0,
                            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
                            player = PlayerRecord(playerName = "Pixel", playerPlatform = "Android"),
                            startedAt = "2026-04-14T08:00:00Z",
                            stoppedAt = "2026-04-14T08:02:00Z",
                        ),
                        historyMovieRecord(
                            id = "hist-day-short-2",
                            userId = "u-bob",
                            ratingKey = "mov-short",
                            title = "Short Clip",
                            year = 2024,
                            thumbPath = null,
                            watchedDurationSec = 100,
                            pausedDurationSec = 0,
                            completionPercent = 100.0,
                            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
                            player = PlayerRecord(playerName = "Pixel", playerPlatform = "Android"),
                            startedAt = "2026-04-14T09:00:00Z",
                            stoppedAt = "2026-04-14T09:02:00Z",
                        ),
                        historyMovieRecord(
                            id = "hist-day-short-3",
                            userId = "u-bob",
                            ratingKey = "mov-short",
                            title = "Short Clip",
                            year = 2024,
                            thumbPath = null,
                            watchedDurationSec = 100,
                            pausedDurationSec = 0,
                            completionPercent = 100.0,
                            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
                            player = PlayerRecord(playerName = "Pixel", playerPlatform = "Android"),
                            startedAt = "2026-04-14T10:00:00Z",
                            stoppedAt = "2026-04-14T10:02:00Z",
                        ),
                        historyMovieRecord(
                            id = "hist-day-long-1",
                            userId = "u-bob",
                            ratingKey = "mov-long",
                            title = "Long Epic",
                            year = 2020,
                            thumbPath = null,
                            watchedDurationSec = 5_000,
                            pausedDurationSec = 0,
                            completionPercent = 90.0,
                            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
                            player = PlayerRecord(playerName = "Pixel", playerPlatform = "Android"),
                            startedAt = "2026-04-14T11:00:00Z",
                            stoppedAt = "2026-04-14T12:25:00Z",
                        ),
                    )
                extraRecords.forEach { harness.historyRepo.insert(it).shouldBeRight() }

                testApplication {
                    setupApp(harness)

                    val playsOverview =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=7d&metric=plays") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    val april14Plays = playsOverview.playsPerDay.first { it.date == "2026-04-14" }
                    april14Plays.playCount shouldBe 6L
                    april14Plays.watchDurationSec shouldBe 10_800L
                    april14Plays.media.map { it.title to it.playCount } shouldContainExactly
                        listOf("Short Clip" to 3L, "Frieren" to 2L, "Long Epic" to 1L)
                    april14Plays.media.first { it.title == "Frieren" }.let { frieren ->
                        frieren.mediaType shouldBe "show"
                        frieren.users shouldContainExactly listOf("Alice")
                    }
                    april14Plays.users.map { (it.displayName ?: it.username) to it.playCount } shouldContainExactly
                        listOf("Bob" to 4L, "Alice" to 2L)

                    val durationOverview =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=7d&metric=duration") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    val april14Duration = durationOverview.playsPerDay.first { it.date == "2026-04-14" }
                    april14Duration.media.map { it.title to it.watchDurationSec } shouldContainExactly
                        listOf("Frieren" to 5_500L, "Long Epic" to 5_000L, "Short Clip" to 300L)
                    april14Duration.users.map { (it.displayName ?: it.username) to it.watchDurationSec } shouldContainExactly
                        listOf("Alice" to 5_500L, "Bob" to 5_300L)

                    val scopedOverview =
                        MiruJson.decodeFromString<OverviewStatsResponse>(
                            client
                                .get("/api/v1/stats/overview?range=7d") {
                                    cookie(SESSION_COOKIE_NAME, aliceLogin.signedToken)
                                }.bodyAsText(),
                        )
                    val scopedApril14 = scopedOverview.playsPerDay.first { it.date == "2026-04-14" }
                    scopedApril14.playCount shouldBe 2L
                    scopedApril14.media.map { it.title } shouldContainExactly listOf("Frieren")
                    scopedApril14.users.map { it.userId } shouldContainExactly listOf("u-alice")

                    val bobStats =
                        MiruJson.decodeFromString<UserStatsResponse>(
                            client
                                .get("/api/v1/users/u-bob/stats?range=7d") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.bodyAsText(),
                        )
                    val bobApril14 = bobStats.playsPerDay.first { it.date == "2026-04-14" }
                    bobApril14.playCount shouldBe 4L
                    bobApril14.media.map { it.title to it.playCount } shouldContainExactly
                        listOf("Short Clip" to 3L, "Long Epic" to 1L)
                    bobApril14.users.map { it.displayName } shouldContainExactly listOf("Bob")
                }
            }
        }
    })
