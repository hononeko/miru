package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
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
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.library.LibraryAggregates
import app.hononeko.miru.library.LibrarySyncMode
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserLibraryAccessRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.LibrarySyncTrigger
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

private const val SECRET_PLEX_TOKEN = "secret-plex-token-never-expose"
private const val SECRET_PLEX_URL = "http://internal-plex.lan:32400"

private class LibraryRouteHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val appConfig: AppConfig,
    val authService: AuthService,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val libraryRepo: ExposedLibraryRepository,
    val queryService: LibraryQueryService,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createLibraryRouteHarness(): LibraryRouteHarness {
    val dir = Files.createTempDirectory("miru-lib-routes-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val authConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        )
    val userRepo = ExposedUserRepository(handle)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = SECRET_PLEX_URL, token = SECRET_PLEX_TOKEN),
            session = SessionConfig(),
            auth = authConfig,
        )
    val libraryRepo = ExposedLibraryRepository(handle)
    return LibraryRouteHarness(
        tempDir = dir,
        dbHandle = handle,
        appConfig = appConfig,
        authService = authService,
        serverRepo = ExposedMediaServerRepository(handle),
        userRepo = userRepo,
        libraryRepo = libraryRepo,
        queryService = LibraryQueryService(libraryRepo),
    )
}

private suspend fun seedSampleLibraryData(harness: LibraryRouteHarness) {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "KerrLab Plex",
                serverType = "plex",
                machineId = "mach-1",
                baseUrl = SECRET_PLEX_URL,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    seedSampleSections(harness)
    seedSampleSnapshots(harness)
    seedSampleMediaItems(harness)
}

private suspend fun seedSampleSections(harness: LibraryRouteHarness) {
    harness.libraryRepo
        .upsertSection(
            LibrarySectionRecord(
                serverId = "srv-1",
                sectionKey = "1",
                title = "Movies",
                sectionType = "movie",
                itemCount = 120,
                childCount = 0,
                leafCount = 120,
                totalSizeBytes = 1_200_000_000_000L,
                totalDurationMs = 720_000_000L,
                lastSyncedAtMs = 50_000L,
                updatedAtMs = 50_000L,
            ),
        ).shouldBeRight()

    harness.libraryRepo
        .upsertSection(
            LibrarySectionRecord(
                serverId = "srv-1",
                sectionKey = "2",
                title = "TV Shows",
                sectionType = "show",
                itemCount = 25,
                childCount = 70,
                leafCount = 850,
                totalSizeBytes = 900_000_000_000L,
                totalDurationMs = 1_500_000_000L,
                lastSyncedAtMs = 50_000L,
                updatedAtMs = 50_000L,
            ),
        ).shouldBeRight()
}

private suspend fun seedSampleSnapshots(harness: LibraryRouteHarness) {
    val aggOlder =
        LibraryAggregates(
            byResolution = mapOf("1080p" to 110),
            byVideoCodec = mapOf("H.264" to 110),
            byAudioCodec = mapOf("AAC" to 110),
        )
    val aggLatest =
        LibraryAggregates(
            byResolution = mapOf("4K" to 40, "1080p" to 80),
            byVideoCodec = mapOf("HEVC" to 40, "H.264" to 80),
            byAudioCodec = mapOf("EAC3" to 40, "AAC" to 80),
        )

    harness.libraryRepo
        .insertSnapshot(
            LibrarySnapshotRecord(
                id = "snap-1",
                serverId = "srv-1",
                sectionKey = "1",
                capturedAtMs = 10_000L,
                itemCount = 110,
                childCount = 0,
                leafCount = 110,
                totalSizeBytes = 1_000_000_000_000L,
                totalDurationMs = 660_000_000L,
                aggregatesJson = MiruJson.encodeToString(aggOlder),
            ),
        ).shouldBeRight()

    harness.libraryRepo
        .insertSnapshot(
            LibrarySnapshotRecord(
                id = "snap-2",
                serverId = "srv-1",
                sectionKey = "1",
                capturedAtMs = 50_000L,
                itemCount = 120,
                childCount = 0,
                leafCount = 120,
                totalSizeBytes = 1_200_000_000_000L,
                totalDurationMs = 720_000_000L,
                aggregatesJson = MiruJson.encodeToString(aggLatest),
            ),
        ).shouldBeRight()
}

private suspend fun seedSampleMediaItems(harness: LibraryRouteHarness) {
    harness.libraryRepo
        .upsertMediaItems(
            listOf(
                MediaItemCacheRecord(
                    serverId = "srv-1",
                    ratingKey = "rk-1",
                    sectionKey = "1",
                    mediaType = "movie",
                    title = "Spirited Away",
                    year = 2001,
                    thumbPath = "/library/metadata/rk-1/thumb/1700000000",
                    durationMs = 7_500_000L,
                    tech =
                        MediaItemTechRecord(
                            fileSizeBytes = 11_000_000_000L,
                            videoResolution = "1080",
                            videoCodec = "h264",
                            audioCodec = "dts",
                        ),
                    addedAtMs = 30_000L,
                    updatedAtMs = 50_000L,
                ),
                MediaItemCacheRecord(
                    serverId = "srv-1",
                    ratingKey = "rk-2",
                    sectionKey = "1",
                    mediaType = "movie",
                    title = "Akira",
                    year = 1988,
                    thumbPath = null,
                    durationMs = 7_440_000L,
                    tech =
                        MediaItemTechRecord(
                            fileSizeBytes = 15_000_000_000L,
                            videoResolution = "4k",
                            videoCodec = "hevc",
                            audioCodec = "aac",
                        ),
                    addedAtMs = 45_000L,
                    updatedAtMs = 50_000L,
                ),
            ),
        ).shouldBeRight()
}

private fun ApplicationTestBuilder.setupLibraryApp(
    harness: LibraryRouteHarness,
    syncTrigger: LibrarySyncTrigger? = null,
) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            libraryQueryService = harness.queryService,
            librarySyncTrigger = syncTrigger,
        )
    }
}

class LibraryRoutesTest :
    FunSpec({
        test("enforces auth, lists libraries, returns stats with decoded aggregates and snapshots, and returns recent with artwork proxy") {
            createLibraryRouteHarness().use { harness ->
                seedSampleLibraryData(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService.createAccount("viewer", "Viewer-password-123", role = MiruRole.USER).shouldBeRight()
                val userLogin = harness.authService.login("viewer", "Viewer-password-123").shouldBeRight()

                testApplication {
                    setupLibraryApp(harness)

                    // Unauthenticated rejected
                    client.get("/api/v1/libraries").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/libraries/1").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/libraries/1/stats").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/libraries/1/recent").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/libraries/1/stale").status shouldBe HttpStatusCode.Unauthorized

                    // Unlinked USER sees empty library list and receives 403 on detail/stats/recent
                    val unlinkedListResp =
                        client.get("/api/v1/libraries") {
                            cookie(SESSION_COOKIE_NAME, userLogin.signedToken)
                        }
                    unlinkedListResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<LibraryListResponse>(unlinkedListResp.bodyAsText()).libraries.shouldBeEmpty()
                    client
                        .get("/api/v1/libraries/1") { cookie(SESSION_COOKIE_NAME, userLogin.signedToken) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/libraries/1/stats") { cookie(SESSION_COOKIE_NAME, userLogin.signedToken) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/libraries/1/recent") { cookie(SESSION_COOKIE_NAME, userLogin.signedToken) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/libraries/1/stale") { cookie(SESSION_COOKIE_NAME, userLogin.signedToken) }
                        .status shouldBe HttpStatusCode.Forbidden

                    // ADMIN reads full libraries list and single section without exposing secrets
                    val listResp =
                        client.get("/api/v1/libraries") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    listResp.status shouldBe HttpStatusCode.OK
                    val listBody = listResp.bodyAsText()
                    listBody shouldNotContain SECRET_PLEX_TOKEN
                    listBody shouldNotContain SECRET_PLEX_URL
                    val libraries = MiruJson.decodeFromString<LibraryListResponse>(listBody).libraries
                    libraries shouldHaveSize 2
                    libraries.map { it.sectionKey } shouldContainExactly listOf("1", "2")
                    libraries.first().totalSizeBytes shouldBe 1_200_000_000_000L

                    val sectionResp =
                        client.get("/api/v1/libraries/1") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    sectionResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<LibrarySectionResponse>(sectionResp.bodyAsText()).title shouldBe "Movies"

                    // Stats endpoint returns section, aggregates from latest snapshot, and snapshot history
                    val statsResp =
                        client.get("/api/v1/libraries/1/stats") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    statsResp.status shouldBe HttpStatusCode.OK
                    val stats = MiruJson.decodeFromString<LibraryStatsResponse>(statsResp.bodyAsText())
                    stats.section.title shouldBe "Movies"
                    stats.byResolution shouldContainExactly mapOf("4K" to 40, "1080p" to 80)
                    stats.byVideoCodec shouldContainExactly mapOf("HEVC" to 40, "H.264" to 80)
                    stats.byAudioCodec shouldContainExactly mapOf("EAC3" to 40, "AAC" to 80)
                    stats.snapshots.map { it.id } shouldContainExactly listOf("snap-2", "snap-1")
                    stats.growth shouldContainExactly
                        listOf(
                            LibraryGrowthDayResponse(
                                date = "1970-01-01",
                                added = 10L,
                                removed = 0L,
                                sizeDeltaBytes = 200_000_000_000L,
                            ),
                        )

                    // Filtered snapshot window preserves latest breakdown
                    val windowResp =
                        client.get("/api/v1/libraries/1/stats?from=5000&to=20000&limit=10") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    windowResp.status shouldBe HttpStatusCode.OK
                    val windowStats = MiruJson.decodeFromString<LibraryStatsResponse>(windowResp.bodyAsText())
                    windowStats.snapshots.map { it.id } shouldContainExactly listOf("snap-1")
                    windowStats.byResolution shouldContainExactly mapOf("4K" to 40, "1080p" to 80)

                    // Recent endpoint maps thumbPath to artwork proxy URL and omits internal Plex paths/tokens
                    val recentResp =
                        client.get("/api/v1/libraries/1/recent?limit=10") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    recentResp.status shouldBe HttpStatusCode.OK
                    val recentText = recentResp.bodyAsText()
                    recentText shouldNotContain SECRET_PLEX_TOKEN
                    recentText shouldNotContain SECRET_PLEX_URL
                    recentText shouldNotContain "/library/metadata/rk-1/thumb"
                    val recent = MiruJson.decodeFromString<RecentMediaListResponse>(recentText)
                    recent.sectionId shouldBe "1"
                    recent.items.map { it.ratingKey to it.thumbUrl } shouldContainExactly
                        listOf(
                            "rk-2" to null,
                            "rk-1" to "/api/v1/media/rk-1/thumb",
                        )

                    // Stale endpoint returns unwatched items sorted by size_desc or age_desc with proxied thumbUrl
                    val staleResp =
                        client.get("/api/v1/libraries/1/stale?unwatchedDays=180&sort=size_desc") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    staleResp.status shouldBe HttpStatusCode.OK
                    val staleText = staleResp.bodyAsText()
                    staleText shouldNotContain SECRET_PLEX_TOKEN
                    staleText shouldNotContain SECRET_PLEX_URL
                    staleText shouldNotContain "/library/metadata/rk-1/thumb"
                    val stale = MiruJson.decodeFromString<StaleMediaListResponse>(staleText)
                    stale.sectionId shouldBe "1"
                    stale.unwatchedDays shouldBe 180
                    stale.sort shouldBe "size_desc"
                    stale.totalStaleCount shouldBe 2
                    stale.neverPlayedCount shouldBe 2
                    stale.totalReclaimableBytes shouldBe 26_000_000_000L
                    stale.items.map { it.ratingKey to it.thumbUrl } shouldContainExactly
                        listOf(
                            "rk-2" to null,
                            "rk-1" to "/api/v1/media/rk-1/thumb",
                        )

                    val staleAgeResp =
                        client.get("/api/v1/libraries/1/stale?unwatchedDays=0&sort=age_desc") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    staleAgeResp.status shouldBe HttpStatusCode.OK
                    val staleAge = MiruJson.decodeFromString<StaleMediaListResponse>(staleAgeResp.bodyAsText())
                    staleAge.unwatchedDays shouldBe 0
                    staleAge.sort shouldBe "age_desc"
                    staleAge.items.map { it.ratingKey } shouldContainExactly listOf("rk-1", "rk-2")

                    // 404 on unknown section for ADMIN
                    val missingSection =
                        client.get("/api/v1/libraries/999") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    missingSection.status shouldBe HttpStatusCode.NotFound
                    missingSection.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    val missingStats =
                        client.get("/api/v1/libraries/999/stats") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    missingStats.status shouldBe HttpStatusCode.NotFound
                    missingStats.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    val missingRecent =
                        client.get("/api/v1/libraries/999/recent") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    missingRecent.status shouldBe HttpStatusCode.NotFound

                    // 400 on invalid query parameters
                    client
                        .get("/api/v1/libraries/1/stats?limit=0") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/stats?limit=abc") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/stats?from=200&to=100") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/stats?from=-1&to=100") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/recent?limit=101") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/stale?unwatchedDays=-1") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/stale?sort=invalid") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/1/stale?limit=0") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/libraries/999/stale") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("supports serverId filtering, 409 on ambiguous sectionKey, and malformed snapshot JSON fallback") {
            createLibraryRouteHarness().use { harness ->
                seedSampleLibraryData(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()

                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-2",
                            name = "Backup Plex",
                            serverType = "plex",
                            machineId = "mach-2",
                            baseUrl = "http://backup-plex.lan:32400",
                            createdAtMs = 2_000L,
                            updatedAtMs = 2_000L,
                        ),
                    ).shouldBeRight()
                harness.libraryRepo
                    .upsertSection(
                        LibrarySectionRecord(
                            serverId = "srv-2",
                            sectionKey = "1",
                            title = "Movies Backup",
                            sectionType = "movie",
                            itemCount = 10,
                            childCount = 0,
                            leafCount = 10,
                            updatedAtMs = 60_000L,
                        ),
                    ).shouldBeRight()
                harness.libraryRepo
                    .insertSnapshot(
                        LibrarySnapshotRecord(
                            id = "snap-bad-json",
                            serverId = "srv-2",
                            sectionKey = "1",
                            capturedAtMs = 60_000L,
                            itemCount = 10,
                            childCount = 0,
                            leafCount = 10,
                            totalSizeBytes = 0L,
                            totalDurationMs = 0L,
                            aggregatesJson = "{malformed-json",
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupLibraryApp(harness)

                    val filteredResp =
                        client.get("/api/v1/libraries?serverId=srv-2") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    filteredResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<LibraryListResponse>(filteredResp.bodyAsText()).libraries.single().title shouldBe
                        "Movies Backup"

                    client
                        .get("/api/v1/libraries/1/stats") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Conflict

                    val disambiguatedStats =
                        client.get("/api/v1/libraries/1/stats?serverId=srv-2") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    disambiguatedStats.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<LibraryStatsResponse>(disambiguatedStats.bodyAsText()).byResolution shouldBe emptyMap()

                    client
                        .get("/api/v1/libraries/999/recent?serverId=srv-2") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("enforces per-user Plex shared-library RBAC across list, detail, stats, recent, multi-server, and revocation") {
            createLibraryRouteHarness().use { harness ->
                seedSampleLibraryData(harness)
                harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()

                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-2",
                            name = "Backup Plex",
                            serverType = "plex",
                            machineId = "mach-2",
                            baseUrl = "http://backup-plex.lan:32400",
                            createdAtMs = 2_000L,
                            updatedAtMs = 2_000L,
                        ),
                    ).shouldBeRight()
                harness.libraryRepo
                    .upsertSection(
                        LibrarySectionRecord(
                            serverId = "srv-2",
                            sectionKey = "1",
                            title = "Movies Backup",
                            sectionType = "movie",
                            itemCount = 10,
                            childCount = 0,
                            leafCount = 10,
                            updatedAtMs = 60_000L,
                        ),
                    ).shouldBeRight()

                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "mu-owner",
                            serverId = "srv-1",
                            externalUserId = "1",
                            username = "owner",
                            isAdmin = true,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "mu-bob",
                            serverId = "srv-1",
                            externalUserId = "2002",
                            username = "bob",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "mu-carol",
                            serverId = "srv-1",
                            externalUserId = "3003",
                            username = "carol",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "mu-dave",
                            serverId = "srv-2",
                            externalUserId = "4004",
                            username = "dave",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                harness.libraryRepo
                    .replaceUserLibraryAccess(
                        serverId = "srv-1",
                        records =
                            listOf(
                                UserLibraryAccessRecord(
                                    serverId = "srv-1",
                                    externalUserId = "2002",
                                    allLibraries = false,
                                    sectionKeys = setOf("1"),
                                    updatedAtMs = 5_000L,
                                ),
                                UserLibraryAccessRecord(
                                    serverId = "srv-1",
                                    externalUserId = "3003",
                                    allLibraries = true,
                                    sectionKeys = emptySet(),
                                    updatedAtMs = 5_000L,
                                ),
                            ),
                    ).shouldBeRight()
                harness.libraryRepo
                    .replaceUserLibraryAccess(
                        serverId = "srv-2",
                        records =
                            listOf(
                                UserLibraryAccessRecord(
                                    serverId = "srv-2",
                                    externalUserId = "4004",
                                    allLibraries = false,
                                    sectionKeys = setOf("1"),
                                    updatedAtMs = 5_000L,
                                ),
                            ),
                    ).shouldBeRight()

                harness.authService
                    .createAccount("bob", "Bob-password-123", role = MiruRole.USER, linkedMediaUserIds = setOf("mu-bob"))
                    .shouldBeRight()
                val bobLogin = harness.authService.login("bob", "Bob-password-123").shouldBeRight()

                harness.authService
                    .createAccount("carol", "Carol-password-123", role = MiruRole.USER, linkedMediaUserIds = setOf("mu-carol"))
                    .shouldBeRight()
                val carolLogin = harness.authService.login("carol", "Carol-password-123").shouldBeRight()

                harness.authService
                    .createAccount("owner", "Owner-password-123", role = MiruRole.USER, linkedMediaUserIds = setOf("mu-owner"))
                    .shouldBeRight()
                val ownerLogin = harness.authService.login("owner", "Owner-password-123").shouldBeRight()

                harness.authService
                    .createAccount("dave", "Dave-password-123", role = MiruRole.USER, linkedMediaUserIds = setOf("mu-dave"))
                    .shouldBeRight()
                val daveLogin = harness.authService.login("dave", "Dave-password-123").shouldBeRight()

                testApplication {
                    setupLibraryApp(harness)

                    // Bob sees only section "1" (Movies), gets 403 for section "2" and unknown sections
                    val bobLibs = MiruJson.decodeFromString<LibraryListResponse>(
                        client.get("/api/v1/libraries") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.bodyAsText(),
                    )
                    bobLibs.libraries.map { it.title } shouldContainExactly listOf("Movies")

                    client.get("/api/v1/libraries/1") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.OK
                    client.get("/api/v1/libraries/1/stats") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.OK
                    client.get("/api/v1/libraries/1/recent") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.OK
                    // The stale report is ADMIN only, even for a section shared with Bob, and before any input is validated
                    for (path in listOf("1/stale", "1/stale?unwatchedDays=abc", "1/stale?limit=abc", "999/stale")) {
                        client
                            .get("/api/v1/libraries/$path") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }
                            .status shouldBe HttpStatusCode.Forbidden
                    }
                    client.get("/api/v1/libraries/2") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.get("/api/v1/libraries/2/stats") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.get("/api/v1/libraries/2/recent") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.get("/api/v1/libraries/999/stats") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.Forbidden

                    // Carol (allLibraries = true) sees both Movies and TV Shows on srv-1
                    val carolLibs = MiruJson.decodeFromString<LibraryListResponse>(
                        client.get("/api/v1/libraries") { cookie(SESSION_COOKIE_NAME, carolLogin.signedToken) }.bodyAsText(),
                    )
                    carolLibs.libraries.map { it.title } shouldContainExactly listOf("Movies", "TV Shows")

                    // Owner USER sees all sections on srv-1 even without user_library_access row
                    val ownerLibs = MiruJson.decodeFromString<LibraryListResponse>(
                        client.get("/api/v1/libraries") { cookie(SESSION_COOKIE_NAME, ownerLogin.signedToken) }.bodyAsText(),
                    )
                    ownerLibs.libraries.map { it.title } shouldContainExactly listOf("Movies", "TV Shows")

                    // Dave only has access to srv-2 section "1", so GET /api/v1/libraries/1/stats resolves srv-2 without 409 leak
                    val daveStats =
                        client.get("/api/v1/libraries/1/stats") {
                            cookie(SESSION_COOKIE_NAME, daveLogin.signedToken)
                        }
                    daveStats.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<LibraryStatsResponse>(daveStats.bodyAsText()).section.serverId shouldBe "srv-2"
                    client
                        .get("/api/v1/libraries/1/stats?serverId=srv-1") {
                            cookie(SESSION_COOKIE_NAME, daveLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // Revoking Bob's access hides all sections and returns 403 on detail
                    harness.libraryRepo.replaceUserLibraryAccess("srv-1", emptyList()).shouldBeRight()
                    MiruJson.decodeFromString<LibraryListResponse>(
                        client.get("/api/v1/libraries") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.bodyAsText(),
                    ).libraries.shouldBeEmpty()
                    client.get("/api/v1/libraries/1/stats") { cookie(SESSION_COOKIE_NAME, bobLogin.signedToken) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
            }
        }

        test("POST /api/v1/libraries/sync requires ADMIN role, validates params, and maps errors") {
            createLibraryRouteHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService.createAccount("viewer", "Viewer-password-123", role = MiruRole.USER).shouldBeRight()
                val userLogin = harness.authService.login("viewer", "Viewer-password-123").shouldBeRight()

                val recordedCalls = mutableListOf<Pair<Boolean, String?>>()
                var nextResult: Either<MiruError, LibrarySyncSummary>? = null
                val fakeTrigger =
                    LibrarySyncTrigger { deep, sectionKey ->
                        recordedCalls += deep to sectionKey
                        nextResult
                            ?: LibrarySyncSummary(
                                mode = if (deep) LibrarySyncMode.DEEP else LibrarySyncMode.FAST,
                                syncedSections = 2,
                                failedSections = 0,
                                totalItemsSynced = if (deep) 120 else 0,
                                snapshotsCreated = if (deep) 2 else 0,
                                prunedItems = 1,
                                syncedAtMs = 99_000L,
                            ).right()
                    }

                testApplication {
                    setupLibraryApp(harness, syncTrigger = fakeTrigger)

                    // 401 unauthenticated
                    client.post("/api/v1/libraries/sync").status shouldBe HttpStatusCode.Unauthorized

                    // 403 USER
                    val forbiddenResp =
                        client.post("/api/v1/libraries/sync") {
                            cookie(SESSION_COOKIE_NAME, userLogin.signedToken)
                        }
                    forbiddenResp.status shouldBe HttpStatusCode.Forbidden
                    forbiddenResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // 200 ADMIN default deep=true
                    val deepResp =
                        client.post("/api/v1/libraries/sync") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    deepResp.status shouldBe HttpStatusCode.OK
                    val deepBody = MiruJson.decodeFromString<LibrarySyncResponse>(deepResp.bodyAsText())
                    deepBody.mode shouldBe LibrarySyncMode.DEEP
                    deepBody.totalItemsSynced shouldBe 120
                    recordedCalls.last() shouldBe (true to null)

                    // 200 ADMIN fast + sectionKey
                    val fastResp =
                        client.post("/api/v1/libraries/sync?deep=false&sectionKey=2") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    fastResp.status shouldBe HttpStatusCode.OK
                    val fastBody = MiruJson.decodeFromString<LibrarySyncResponse>(fastResp.bodyAsText())
                    fastBody.mode shouldBe LibrarySyncMode.FAST
                    recordedCalls.last() shouldBe (false to "2")

                    // 400 invalid params
                    client
                        .post("/api/v1/libraries/sync?deep=maybe") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .post("/api/v1/libraries/sync?sectionKey=../bad") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest

                    // 502 upstream unavailable
                    nextResult = MiruError.UpstreamUnavailable("plex", "timeout").left()
                    client
                        .post("/api/v1/libraries/sync") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadGateway
                }
            }
        }
    })
