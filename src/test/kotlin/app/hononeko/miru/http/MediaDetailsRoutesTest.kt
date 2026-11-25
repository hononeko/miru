package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.plex.PlexFixtures
import app.hononeko.miru.adapter.plex.PlexMediaServerAdapter
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserLibraryAccessRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.MediaDetailsService
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val PLEX_SECRET_URL = "http://plex-internal.secret:32400"
private const val PLEX_SECRET_TOKEN = "plex-secret-token-should-never-leak"
private val FIXED_NOW_MS = Instant.parse("2026-04-15T12:00:00Z").toEpochMilli()

private class MediaDetailsHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val serverRepo: ExposedMediaServerRepository,
    val libraryRepo: ExposedLibraryRepository,
    val userRepo: ExposedUserRepository,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val authService: AuthService,
    val mediaDetailsService: MediaDetailsService,
    val appConfig: AppConfig,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createHarness(
    authMode: AuthMode = AuthMode.LOCAL,
    plexHandler: suspend MockRequestHandleScope.(String) -> io.ktor.client.request.HttpResponseData = { path ->
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when (path) {
            "/library/metadata/12345" -> respond(PlexFixtures.movieMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
            "/library/metadata/54321" -> respond(PlexFixtures.episodeMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
            "/library/metadata/54300" -> respond(PlexFixtures.seasonMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
            "/library/metadata/54000" -> respond(PlexFixtures.showMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
            else -> respond("missing", HttpStatusCode.NotFound)
        }
    },
): MediaDetailsHarness {
    val dir = Files.createTempDirectory("miru-media-details-routes-")
    val dbPath = dir.resolve("miru-media-details.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val userRepo = ExposedUserRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val libraryRepo = ExposedLibraryRepository(handle)
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
    val plexConfig = PlexConfig(baseUrl = PLEX_SECRET_URL, token = PLEX_SECRET_TOKEN)
    val adapter =
        PlexMediaServerAdapter(
            config = plexConfig,
            httpClient =
                createPlexHttpClient(
                    engine =
                        MockEngine { req ->
                            plexHandler(req.url.encodedPath)
                        },
                ),
        )
    val mediaDetailsService =
        MediaDetailsService(
            mediaServerAdapter = adapter,
            playbackHistoryRepository = historyRepo,
            libraryRepository = libraryRepo,
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = plexConfig,
            session = SessionConfig(),
            auth = authConfig,
        )
    return MediaDetailsHarness(
        tempDir = dir,
        dbHandle = handle,
        serverRepo = ExposedMediaServerRepository(handle),
        libraryRepo = libraryRepo,
        userRepo = userRepo,
        historyRepo = historyRepo,
        authService = authService,
        mediaDetailsService = mediaDetailsService,
        appConfig = appConfig,
    )
}

private fun ApplicationTestBuilder.setupApp(harness: MediaDetailsHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            mediaDetailsService = harness.mediaDetailsService,
        )
    }
}

private suspend fun seedMediaHistory(harness: MediaDetailsHarness) {
    seedUsersAndServer(harness)
    val records =
        listOf(
            historyItem(
                id = "play-mov-1",
                userId = "u-alice",
                ratingKey = "12345",
                mediaType = "movie",
                title = "Spirited Away",
                year = 2001,
                durationMs = 7_500_000L,
                watchedDurationSec = 7_500,
                completionPercent = 100.0,
                streamDecision = "direct_play",
                startedAtMs = 10_000L,
                stoppedAtMs = 20_000L,
            ),
            historyItem(
                id = "play-ep-1",
                userId = "u-alice",
                ratingKey = "54321",
                mediaType = "episode",
                title = "Ozymandias",
                parentTitle = "Season 5",
                grandparentTitle = "Breaking Bad",
                parentIndex = 5,
                mediaIndex = 14,
                year = 2013,
                durationMs = 2_880_000L,
                watchedDurationSec = 2_880,
                completionPercent = 100.0,
                streamDecision = "direct_play",
                startedAtMs = 30_000L,
                stoppedAtMs = 40_000L,
            ),
            historyItem(
                id = "play-ep-2",
                userId = "u-bob",
                ratingKey = "54321",
                mediaType = "episode",
                title = "Ozymandias",
                parentTitle = "Season 5",
                grandparentTitle = "Breaking Bad",
                parentIndex = 5,
                mediaIndex = 14,
                year = 2013,
                durationMs = 2_880_000L,
                watchedDurationSec = 1_440,
                completionPercent = 50.0,
                streamDecision = "transcode",
                startedAtMs = 50_000L,
                stoppedAtMs = 60_000L,
            ),
            historyItem(
                id = "play-ep-other",
                userId = "u-bob",
                ratingKey = "54322",
                mediaType = "episode",
                title = "Felina",
                parentTitle = "Season 5",
                grandparentTitle = "Breaking Bad",
                parentIndex = 5,
                mediaIndex = 16,
                year = 2013,
                durationMs = 3_300_000L,
                watchedDurationSec = 3_300,
                completionPercent = 100.0,
                streamDecision = "direct_stream",
                startedAtMs = 70_000L,
                stoppedAtMs = 80_000L,
            ),
        )
    records.forEach { harness.historyRepo.insert(it).shouldBeRight() }
}

private suspend fun seedUsersAndServer(harness: MediaDetailsHarness) {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Main Plex",
                serverType = "PLEX",
                machineId = "mach-1",
                baseUrl = PLEX_SECRET_URL,
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
}

@Suppress("LongParameterList")
private fun historyItem(
    id: String,
    userId: String,
    ratingKey: String,
    mediaType: String,
    title: String,
    parentTitle: String? = null,
    grandparentTitle: String? = null,
    parentIndex: Int? = null,
    mediaIndex: Int? = null,
    year: Int? = null,
    durationMs: Long,
    watchedDurationSec: Int,
    completionPercent: Double,
    streamDecision: String,
    startedAtMs: Long,
    stoppedAtMs: Long,
): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = id,
        serverId = "srv-1",
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = mediaType,
                title = title,
                parentTitle = parentTitle,
                grandparentTitle = grandparentTitle,
                parentIndex = parentIndex,
                mediaIndex = mediaIndex,
                year = year,
                durationMs = durationMs,
            ),
        viewOffsetMs = durationMs,
        watchedDurationSec = watchedDurationSec,
        completionPercent = completionPercent,
        isWatched = completionPercent >= 90.0,
        streamTech = StreamTechRecord(streamDecision = streamDecision),
        player = PlayerRecord(playerName = "Apple TV"),
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
    )

class MediaDetailsRoutesTest :
    FunSpec({
        test("GET /api/v1/media/{ratingKey} requires authentication when auth mode is LOCAL") {
            createHarness().use { harness ->
                testApplication {
                    setupApp(harness)
                    val response = client.get("/api/v1/media/12345")
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
                }
            }
        }

        test("returns movie, episode with series+season, season, and show details using only proxied artwork URLs") {
            createHarness().use { harness ->
                val admin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedMediaHistory(harness)

                testApplication {
                    setupApp(harness)

                    val movieBody =
                        client
                            .get("/api/v1/media/12345") {
                                cookie(SESSION_COOKIE_NAME, admin.signedToken)
                            }.also { it.status shouldBe HttpStatusCode.OK }
                            .bodyAsText()
                    movieBody shouldNotContain PLEX_SECRET_URL
                    movieBody shouldNotContain PLEX_SECRET_TOKEN
                    movieBody shouldNotContain "/library/metadata/12345/thumb/1700000000"
                    movieBody shouldNotContain "/library/metadata/12345/art/1700000000"

                    val movie = MiruJson.decodeFromString<MediaDetailsResponse>(movieBody)
                    movie.ratingKey shouldBe "12345"
                    movie.mediaType shouldBe "movie"
                    movie.title shouldBe "Spirited Away"
                    movie.librarySectionKey shouldBe "1"
                    movie.thumbUrl shouldBe "/api/v1/media/12345/thumb"
                    movie.artUrl shouldBe "/api/v1/media/12345/art"
                    movie.addedAtMs shouldBe 1_700_000_000_000L
                    movie.series.shouldBeNull()
                    movie.season.shouldBeNull()
                    movie.seriesPlaySummary.shouldBeNull()
                    movie.technical.shouldNotBeNull().videoProfile shouldBe "high"
                    movie.playSummary.totalPlays shouldBe 1L
                    movie.playSummary.lastWatchedUsername shouldBe "Alice"
                    movie.playSummary.averageCompletionPercent shouldBe 100.0
                    movie.playSummary.directPlay shouldBe 1L
                    movie.playSummary.directStream shouldBe 0L
                    movie.playSummary.transcode shouldBe 0L

                    val episodeBody =
                        client
                            .get("/api/v1/media/54321") {
                                cookie(SESSION_COOKIE_NAME, admin.signedToken)
                            }.also { it.status shouldBe HttpStatusCode.OK }
                            .bodyAsText()
                    episodeBody shouldNotContain PLEX_SECRET_URL
                    episodeBody shouldNotContain PLEX_SECRET_TOKEN

                    val episode = MiruJson.decodeFromString<MediaDetailsResponse>(episodeBody)
                    episode.ratingKey shouldBe "54321"
                    episode.mediaType shouldBe "episode"
                    episode.parentIndex shouldBe 5
                    episode.mediaIndex shouldBe 14
                    episode.thumbUrl shouldBe "/api/v1/media/54321/thumb"
                    // An episode's art is its show's (/library/metadata/54000/art/...), so the URL names the show.
                    episode.artUrl shouldBe "/api/v1/media/54000/art"
                    episode.series.shouldNotBeNull().let { s ->
                        s.ratingKey shouldBe "54000"
                        s.title shouldBe "Breaking Bad"
                        s.seasonCount shouldBe 5
                        s.episodeCount shouldBe 62
                        s.thumbUrl shouldBe "/api/v1/media/54000/thumb"
                    }
                    episode.season.shouldNotBeNull().let { sn ->
                        sn.ratingKey shouldBe "54300"
                        sn.title shouldBe "Season 5"
                        sn.seasonIndex shouldBe 5
                        sn.episodeCount shouldBe 16
                        sn.currentEpisodeIndex shouldBe 14
                        sn.thumbUrl shouldBe "/api/v1/media/54300/thumb"
                    }
                    episode.playSummary.totalPlays shouldBe 2L
                    episode.playSummary.uniqueUsers shouldBe 2
                    episode.seriesPlaySummary.shouldNotBeNull().let { ss ->
                        ss.totalPlays shouldBe 3L
                        ss.uniqueUsers shouldBe 2
                        ss.lastWatchedUsername shouldBe "Bob"
                    }

                    val show =
                        MiruJson.decodeFromString<MediaDetailsResponse>(
                            client
                                .get("/api/v1/media/54000") {
                                    cookie(SESSION_COOKIE_NAME, admin.signedToken)
                                }.also { it.status shouldBe HttpStatusCode.OK }
                                .bodyAsText(),
                        )
                    show.mediaType shouldBe "show"
                    show.series.shouldNotBeNull().seasonCount shouldBe 5
                    show.season.shouldBeNull()
                    show.playSummary.totalPlays shouldBe 3L
                    show.playSummary.uniqueUsers shouldBe 2
                    show.playSummary.lastWatchedUsername shouldBe "Bob"
                    show.seriesPlaySummary.shouldNotBeNull().totalPlays shouldBe 3L
                }
            }
        }

        test("enforces USER library-section access control and RBAC scoping on playSummary and seriesPlaySummary") {
            var plexCallCount = 0
            createHarness(
                plexHandler = { path ->
                    plexCallCount += 1
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    when (path) {
                        "/library/metadata/12345" -> respond(PlexFixtures.movieMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
                        "/library/metadata/54321" -> respond(PlexFixtures.episodeMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
                        "/library/metadata/54300" -> respond(PlexFixtures.seasonMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
                        "/library/metadata/54000" -> respond(PlexFixtures.showMetadataDetailsJson, HttpStatusCode.OK, jsonHeaders)
                        else -> respond("missing", HttpStatusCode.NotFound)
                    }
                },
            ).use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()
                seedMediaHistory(harness)
                // Alice (externalUserId 101) has access only to TV Shows section "2", NOT Movies section "1".
                harness.libraryRepo
                    .replaceUserLibraryAccess(
                        serverId = "srv-1",
                        records =
                            listOf(
                                UserLibraryAccessRecord(
                                    serverId = "srv-1",
                                    externalUserId = "101",
                                    allLibraries = false,
                                    sectionKeys = setOf("2"),
                                    updatedAtMs = 1_000L,
                                ),
                            ),
                    ).shouldBeRight()

                val aliceAccount =
                    harness.authService
                        .createAccount(
                            username = "alice-user",
                            password = "Alice-strong-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("u-alice"),
                        ).shouldBeRight()
                val aliceLogin = harness.authService.login(aliceAccount.account.username, "Alice-strong-password-123").shouldBeRight()

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

                    // ADMIN access remains unrestricted across all library sections
                    client
                        .get("/api/v1/media/12345") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.OK

                    // Alice can fetch episode 54321 and show 54000 in shared section "2" with scoped play summary
                    val aliceEp =
                        MiruJson.decodeFromString<MediaDetailsResponse>(
                            client
                                .get("/api/v1/media/54321") {
                                    cookie(SESSION_COOKIE_NAME, aliceLogin.signedToken)
                                }.also { it.status shouldBe HttpStatusCode.OK }
                                .bodyAsText(),
                        )
                    aliceEp.playSummary.totalPlays shouldBe 1L
                    aliceEp.playSummary.uniqueUsers shouldBe 1
                    aliceEp.playSummary.lastWatchedUsername shouldBe "Alice"
                    aliceEp.seriesPlaySummary.shouldNotBeNull().totalPlays shouldBe 1L

                    val aliceShow =
                        MiruJson.decodeFromString<MediaDetailsResponse>(
                            client
                                .get("/api/v1/media/54000") {
                                    cookie(SESSION_COOKIE_NAME, aliceLogin.signedToken)
                                }.also { it.status shouldBe HttpStatusCode.OK }
                                .bodyAsText(),
                        )
                    aliceShow.playSummary.totalPlays shouldBe 1L

                    // Alice is forbidden from unshared section "1" movie 12345
                    val aliceForbidden =
                        client.get("/api/v1/media/12345") {
                            cookie(SESSION_COOKIE_NAME, aliceLogin.signedToken)
                        }
                    aliceForbidden.status shouldBe HttpStatusCode.Forbidden
                    aliceForbidden.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // Unlinked USER fails closed with 403 Forbidden without making any Plex requests
                    val callsBeforeUnlinked = plexCallCount
                    client
                        .get("/api/v1/media/54321") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/media/12345") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    plexCallCount shouldBe callsBeforeUnlinked
                }
            }
        }

        test("returns 400 for invalid ratingKey, 404 for missing media, and 502 without leaking secrets") {
            createHarness(
                plexHandler = { path ->
                    when (path) {
                        "/library/metadata/upstream-fail" ->
                            respond("upstream failed with $PLEX_SECRET_TOKEN", HttpStatusCode.InternalServerError)
                        else -> respond("missing", HttpStatusCode.NotFound)
                    }
                },
            ).use { harness ->
                val admin = harness.authService.setupInitialAdmin("admin", "Admin-strong-password-123").shouldBeRight()

                testApplication {
                    setupApp(harness)

                    val badResp =
                        client.get("/api/v1/media/invalid!key") {
                            cookie(SESSION_COOKIE_NAME, admin.signedToken)
                        }
                    badResp.status shouldBe HttpStatusCode.BadRequest
                    badResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
                    val badProblem = MiruJson.decodeFromString<ProblemDetails>(badResp.bodyAsText())
                    badProblem.errors.shouldNotBeNull() shouldContainKey "ratingKey"

                    val missingResp =
                        client.get("/api/v1/media/999999") {
                            cookie(SESSION_COOKIE_NAME, admin.signedToken)
                        }
                    missingResp.status shouldBe HttpStatusCode.NotFound
                    missingResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    val failResp =
                        client.get("/api/v1/media/upstream-fail") {
                            cookie(SESSION_COOKIE_NAME, admin.signedToken)
                        }
                    failResp.status shouldBe HttpStatusCode.BadGateway
                    val failBody = failResp.bodyAsText()
                    failBody shouldNotContain PLEX_SECRET_TOKEN
                    failBody shouldNotContain PLEX_SECRET_URL
                }
            }
        }
    })
