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
import app.hononeko.miru.config.TautulliCompatConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.TautulliCompatService
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private const val DAY_MS = 86_400_000L
private const val NOW_MS = 1_795_000_000_000L
private const val SERVER_ID = "srv-1"
private const val OTHER_SERVER_ID = "srv-2"

private class TautulliHarness(
    private val tempDir: Path,
    private val dbHandle: DatabaseHandle,
    val authService: AuthService,
    val adminKey: String,
    val userKey: String,
    val adminSession: String,
) : AutoCloseable {
    val history = ExposedPlaybackHistoryRepository(dbHandle)
    val library = ExposedLibraryRepository(dbHandle)
    val users = ExposedUserRepository(dbHandle)
    var serverId: Either<MiruError, String> = SERVER_ID.right()
    private val ids = AtomicInteger()

    val service =
        TautulliCompatService(
            playbackHistoryRepository = history,
            userRepository = users,
            libraryRepository = library,
            serverIdProvider = { serverId },
            clock = { NOW_MS },
        )

    @Suppress("LongParameterList")
    suspend fun play(
        ratingKey: String,
        title: String,
        mediaType: String = "movie",
        grandparentTitle: String? = null,
        year: Int? = null,
        sectionKey: String? = "1",
        userId: String = "u-1",
        daysAgo: Int = 1,
        watchedSec: Int = 600,
        completionPercent: Double = 100.0,
        isWatched: Boolean = true,
        serverId: String = SERVER_ID,
    ) {
        val id = ids.incrementAndGet()
        val stoppedAtMs = NOW_MS - daysAgo * DAY_MS + id
        history
            .insert(
                PlaybackHistoryRecord(
                    id = "play-$id",
                    serverId = serverId,
                    userId = userId,
                    media =
                        MediaMetadataRecord(
                            ratingKey = ratingKey,
                            mediaType = mediaType,
                            title = title,
                            parentTitle = grandparentTitle?.let { "Season 1" },
                            grandparentTitle = grandparentTitle,
                            parentIndex = grandparentTitle?.let { 1 },
                            mediaIndex = grandparentTitle?.let { 2 },
                            year = year,
                            thumbPath = "/library/metadata/$ratingKey/thumb/1",
                            librarySectionKey = sectionKey,
                            durationMs = 1_200_000L,
                        ),
                    viewOffsetMs = 1_200_000L,
                    watchedDurationSec = watchedSec,
                    pausedDurationSec = 30,
                    completionPercent = completionPercent,
                    isWatched = isWatched,
                    streamTech = StreamTechRecord(streamDecision = "direct_stream"),
                    player =
                        PlayerRecord(
                            playerName = "TV",
                            playerPlatform = "tvOS",
                            playerProduct = "Plex for Apple TV",
                            playerMachineId = "machine-1",
                            ipAddress = "192.0.2.7",
                            location = "lan",
                        ),
                    startedAtMs = stoppedAtMs - watchedSec * 1_000L,
                    stoppedAtMs = stoppedAtMs,
                ),
            ).shouldBeRight()
    }

    suspend fun cacheItem(
        ratingKey: String,
        mediaType: String,
        sectionKey: String,
        grandparentTitle: String,
        grandparentRatingKey: String?,
    ) {
        library
            .upsertMediaItems(
                listOf(
                    MediaItemCacheRecord(
                        serverId = SERVER_ID,
                        ratingKey = ratingKey,
                        sectionKey = sectionKey,
                        mediaType = mediaType,
                        title = "Item $ratingKey",
                        grandparentTitle = grandparentTitle,
                        addedAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                        grandparentRatingKey = grandparentRatingKey,
                    ),
                ),
            ).shouldBeRight()
    }

    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private suspend fun createTautulliHarness(): TautulliHarness {
    val dir = Files.createTempDirectory("miru-tautulli-test-")
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
    val servers = ExposedMediaServerRepository(handle)
    val users = ExposedUserRepository(handle)
    for (serverId in listOf(SERVER_ID, OTHER_SERVER_ID)) {
        servers
            .upsert(
                MediaServerRecord(
                    id = serverId,
                    name = serverId,
                    serverType = "plex",
                    machineId = "machine-$serverId",
                    baseUrl = "http://$serverId:32400",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
    }
    suspend fun user(
        id: String,
        externalId: String,
        username: String,
        serverId: String = SERVER_ID,
    ) = users
        .upsert(
            UserRecord(
                id = id,
                serverId = serverId,
                externalUserId = externalId,
                username = username,
                displayName = username.replaceFirstChar { it.uppercase() },
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    user("u-1", "1001", "alice")
    user("u-2", "1002", "bob")
    user("u-3", "1003", "carol")
    user("u-other", "1001", "alice", serverId = OTHER_SERVER_ID)

    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = users,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = "super-secret-session-signing-key-32-chars"),
        )
    val login = authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
    val admin = login.principal
    val adminKey = authService.createApiKey(admin.accountId, "kometa", MiruRole.ADMIN).shouldBeRight().rawKey
    val userKey = authService.createApiKey(admin.accountId, "viewer", MiruRole.USER).shouldBeRight().rawKey
    return TautulliHarness(dir, handle, authService, adminKey, userKey, login.signedToken)
}

private fun ApplicationTestBuilder.setupTautulliApp(
    harness: TautulliHarness,
    enabled: Boolean = true,
    authMode: AuthMode = AuthMode.LOCAL,
) {
    application {
        miruModule(
            config =
                AppConfig(
                    server = ServerConfig(host = "127.0.0.1", port = 8080),
                    database = DatabaseConfig.Sqlite(":memory:"),
                    plex = PlexConfig(baseUrl = "http://plex:32400", token = "test-token"),
                    session = SessionConfig(watchedThresholdPercent = 80),
                    auth = AuthConfig(mode = authMode, sessionSecret = "super-secret-session-signing-key-32-chars"),
                    tautulliCompat = TautulliCompatConfig(enabled = enabled),
                ),
            appVersion = "9.8.7",
            authService = harness.authService,
            tautulliCompatService = harness.service,
        )
    }
}

private suspend fun HttpClient.tautulli(vararg params: Pair<String, Any>): HttpResponse =
    get("/api/v2") {
        params.forEach { (name, value) -> parameter(name, value) }
    }

private suspend fun HttpResponse.envelope(): JsonObject =
    MiruJson.parseToJsonElement(bodyAsText()).jsonObject.getValue("response").jsonObject

private val JsonObject.result get() = getValue("result").jsonPrimitive.content
private val JsonObject.message get() = getValue("message").jsonPrimitive.content
private val JsonObject.data: JsonElement get() = getValue("data")

private class KometaFailed(
    message: String,
) : Exception(message)

/**
 * What `modules/tautulli.py` of Kometa 2.5.5 does with the answers, down to the types it relies on: a section ID only
 * equals another number, a missing key is an error, and an item has to be of the library's kind.
 */
private class KometaTautulli private constructor(
    private val client: HttpClient,
    private val apiKey: String,
    private val sectionKey: Int,
    private val libraryKind: String,
    private val plexItems: Map<Int, String>,
    private val hasSection: Boolean,
) {
    suspend fun ratingKeys(
        listType: String,
        days: Int = 30,
        size: Int = 10,
        minimum: Int = 0,
        allItems: Boolean = false,
    ): List<Int> {
        val params = mutableListOf<Pair<String, Any>>("time_range" to days, "stats_count" to size)
        if (hasSection && !allItems) params += "section_id" to sectionKey
        val statId = "${listType}_${if (libraryKind == "show") "tv" else "movies"}"
        val items = homeStatRows(statId, params)
        val countField = if (listType == "popular") "users_watched" else "total_plays"
        val keys = mutableListOf<Int>()
        for (item in items) {
            val inSection = allItems || item.getValue("section_id") == JsonPrimitive(sectionKey)
            if (!inSection || keys.size >= size) continue
            if (item.getValue(countField).jsonPrimitive.int < minimum) continue
            val ratingKey = item.getValue("rating_key").jsonPrimitive.int
            if (plexItems[ratingKey] == libraryKind) keys += ratingKey
        }
        return keys
    }

    suspend fun artistKeys(listType: String): List<Int> {
        val items = homeStatRows("${listType}_music", listOf("time_range" to 30, "stats_count" to 100, "section_id" to sectionKey))
        val countField = if (listType == "popular") "users_watched" else "total_plays"
        val counts = LinkedHashMap<Int, Int>()
        for (item in items) {
            if (item.getValue("media_type").jsonPrimitive.content != "track") continue
            if (item.getValue("section_id").jsonPrimitive.int != sectionKey) continue
            val key = item.getValue("grandparent_rating_key").jsonPrimitive.int
            counts[key] = (counts[key] ?: 0) + item.getValue(countField).jsonPrimitive.int
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }.filter { plexItems[it] == "artist" }
    }

    /** Playlists and collections of tracks, which read the history page by page. */
    suspend fun trackCounts(after: String): Map<Int, Int> {
        val counts = LinkedHashMap<Int, Int>()
        var start = 0
        while (true) {
            val history =
                request(
                    "get_history",
                    "after" to after,
                    "media_type" to "track",
                    "grouping" to 0,
                    "order_column" to "date",
                    "order_dir" to "desc",
                    "start" to start,
                    "length" to 1000,
                    "section_id" to sectionKey,
                ).data.jsonObject
            val rows = history.getValue("data").jsonArray.map { it.jsonObject }
            for (row in rows) {
                val key = row.getValue("rating_key").jsonPrimitive.int
                counts[key] = (counts[key] ?: 0) + (row["group_count"]?.jsonPrimitive?.intOrNull ?: 1)
            }
            start += rows.size
            if (rows.isEmpty() || start >= history.getValue("recordsFiltered").jsonPrimitive.int) return counts
        }
    }

    private suspend fun homeStatRows(
        statId: String,
        params: List<Pair<String, Any>>,
    ): List<JsonObject> {
        val stats = request("get_home_stats", *params.toTypedArray()).data as JsonArray
        val rows =
            stats
                .map { it.jsonObject }
                .firstOrNull { it.getValue("stat_id").jsonPrimitive.content == statId }
                ?.getValue("rows")
                ?.jsonArray
                .orEmpty()
        if (rows.isEmpty()) throw KometaFailed("Tautulli Error: No items found")
        return rows.map { it.jsonObject }
    }

    private suspend fun request(
        command: String,
        vararg params: Pair<String, Any>,
    ): JsonObject = client.tautulli("apikey" to apiKey, "cmd" to command, *params).envelope()

    companion object {
        suspend fun connect(
            client: HttpClient,
            apiKey: String,
            sectionKey: Int,
            libraryKind: String,
            plexItems: Map<Int, String>,
        ): KometaTautulli {
            val info = client.tautulli("apikey" to apiKey, "cmd" to "get_tautulli_info").envelope()
            if (info.result != "success") throw KometaFailed("Tautulli Error: ${info.message}")
            val version = info.data.jsonObject.getValue("tautulli_version").jsonPrimitive.content
            val hasSection = version.split(".")[1].toInt() > 11
            return KometaTautulli(client, apiKey, sectionKey, libraryKind, plexItems, hasSection)
        }
    }
}

class TautulliCompatRoutesTest :
    FunSpec({
        test("does not exist unless it is enabled") {
            createTautulliHarness().use { harness ->
                testApplication {
                    setupTautulliApp(harness, enabled = false)
                    client.tautulli("apikey" to harness.adminKey, "cmd" to "status").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("takes the API key of an administrator from the query or the header, and answers errors in Tautulli's format") {
            createTautulliHarness().use { harness ->
                testApplication {
                    setupTautulliApp(harness)

                    val missing = client.tautulli("cmd" to "status")
                    missing.status shouldBe HttpStatusCode.Unauthorized
                    missing.envelope().result shouldBe "error"
                    missing.envelope().message shouldBe "Parameter apikey is required or X-Api-Key header is required"
                    missing.envelope().data shouldBe JsonObject(emptyMap())

                    val invalid = client.tautulli("apikey" to "nope", "cmd" to "status")
                    invalid.status shouldBe HttpStatusCode.Unauthorized
                    invalid.envelope().message shouldBe "Invalid apikey"

                    val viewer = client.tautulli("apikey" to harness.userKey, "cmd" to "get_history")
                    viewer.status shouldBe HttpStatusCode.Forbidden
                    viewer.envelope().result shouldBe "error"

                    val query = client.tautulli("apikey" to harness.adminKey, "cmd" to "status")
                    query.status shouldBe HttpStatusCode.OK
                    query.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    query.envelope().result shouldBe "success"
                    query.envelope().message shouldBe "Ok"

                    client
                        .get("/api/v2?cmd=status") { header("X-Api-Key", harness.adminKey) }
                        .status shouldBe HttpStatusCode.OK

                    // A session is for the web app: other sites could make the browser send its cookie
                    client
                        .get("/api/v2?cmd=status") { cookie(SESSION_COOKIE_NAME, harness.adminSession) }
                        .status shouldBe HttpStatusCode.Unauthorized

                    val noCommand = client.tautulli("apikey" to harness.adminKey)
                    noCommand.status shouldBe HttpStatusCode.BadRequest
                    noCommand.envelope().message shouldContain "get_home_stats"
                    client
                        .tautulli("apikey" to harness.adminKey, "cmd" to "delete_history")
                        .status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("needs no API key without authentication") {
            createTautulliHarness().use { harness ->
                testApplication {
                    setupTautulliApp(harness, authMode = AuthMode.NONE)
                    client.tautulli("cmd" to "arnold").envelope().result shouldBe "success"
                }
            }
        }

        test("Kometa refuses a wrong API key with its message") {
            createTautulliHarness().use { harness ->
                testApplication {
                    setupTautulliApp(harness)
                    val failure = runCatching { KometaTautulli.connect(client, "nope", 1, "movie", emptyMap()) }.exceptionOrNull()
                    failure?.message shouldBe "Tautulli Error: Invalid apikey"
                }
            }
        }

        test("Kometa builds movie collections with tautulli_popular, tautulli_top and tautulli_watched") {
            createTautulliHarness().use { harness ->
                // 11: three plays by one viewer, 12: two plays by two viewers, 13: one play, 14: too long ago
                repeat(3) { harness.play("11", "Akira", year = 1988) }
                harness.play("12", "Heat", year = 1995, userId = "u-1")
                harness.play("12", "Heat", year = 1995, userId = "u-2")
                harness.play("13", "Ran", year = 1985, daysAgo = 10)
                repeat(9) { harness.play("14", "Old", year = 1950, daysAgo = 40) }
                // Another library, and another server whose keys and sections mean something else
                repeat(9) { harness.play("15", "Elsewhere", year = 2000, sectionKey = "7") }
                repeat(9) { harness.play("13", "Ran", year = 1985, userId = "u-other", serverId = OTHER_SERVER_ID) }

                val plex = mapOf(11 to "movie", 12 to "movie", 13 to "movie", 14 to "movie", 15 to "movie")
                testApplication {
                    setupTautulliApp(harness)
                    val kometa = KometaTautulli.connect(client, harness.adminKey, 1, "movie", plex)

                    kometa.ratingKeys("popular") shouldContainExactly listOf(12, 11, 13)
                    kometa.ratingKeys("top") shouldContainExactly listOf(11, 12, 13)
                    kometa.ratingKeys("top", days = 5) shouldContainExactly listOf(11, 12)
                    kometa.ratingKeys("top", days = 60) shouldContainExactly listOf(14, 11, 12, 13)
                    kometa.ratingKeys("top", size = 1) shouldContainExactly listOf(11)
                    kometa.ratingKeys("popular", minimum = 2) shouldContainExactly listOf(12)
                    kometa.ratingKeys("top", allItems = true) shouldContainExactly listOf(15, 11, 12, 13)

                    val empty = KometaTautulli.connect(client, harness.adminKey, 99, "movie", plex)
                    runCatching { empty.ratingKeys("top") }.exceptionOrNull()?.message shouldBe "Tautulli Error: No items found"
                }
            }
        }

        test("Kometa builds show collections from episodes, also of ones that are no longer in the library") {
            createTautulliHarness().use { harness ->
                harness.cacheItem("201", "episode", "2", "Severance", "200")
                harness.cacheItem("202", "episode", "2", "Severance", "200")
                harness.cacheItem("301", "episode", "2", "Archer", "300")
                // Two shows with the same title
                harness.cacheItem("401", "episode", "2", "Ghosts", "400")
                harness.cacheItem("451", "episode", "2", "Ghosts", "450")

                harness.play("201", "Ep 1", "episode", "Severance", sectionKey = "2", userId = "u-1")
                harness.play("202", "Ep 2", "episode", "Severance", sectionKey = "2", userId = "u-2")
                // Deleted or added again since: found by title
                harness.play("299", "Ep 9", "episode", "Severance", sectionKey = "2", userId = "u-3")
                repeat(5) { harness.play("301", "Pilot", "episode", "Archer", sectionKey = "2") }
                // Played before Miru knew the library: the section comes from the library cache
                harness.play("301", "Pilot", "episode", "Archer", sectionKey = null)
                repeat(2) { harness.play("401", "Ghost 1", "episode", "Ghosts", sectionKey = "2") }
                harness.play("451", "Ghost 2", "episode", "Ghosts", sectionKey = "2")
                // Cannot be told apart by title, and not in the library at all
                repeat(9) { harness.play("499", "Ghost 3", "episode", "Ghosts", sectionKey = "2") }
                repeat(9) { harness.play("901", "Gone", "episode", "Removed Show", sectionKey = "2") }

                val plex = mapOf(200 to "show", 300 to "show", 400 to "show", 450 to "show") +
                    listOf(201, 202, 301, 401, 451).associateWith { "episode" }
                testApplication {
                    setupTautulliApp(harness)
                    val kometa = KometaTautulli.connect(client, harness.adminKey, 2, "show", plex)
                    kometa.ratingKeys("popular") shouldContainExactly listOf(200, 300, 400, 450)
                    kometa.ratingKeys("top") shouldContainExactly listOf(300, 200, 400, 450)
                    kometa.ratingKeys("popular", minimum = 3) shouldContainExactly listOf(200)
                    // Plays without a show do not take up a place in the list
                    kometa.ratingKeys("top", size = 1) shouldContainExactly listOf(300)
                }
            }
        }

        test("Kometa builds artist collections and reads the plays of tracks") {
            createTautulliHarness().use { harness ->
                harness.cacheItem("601", "track", "3", "Boards of Canada", "600")
                harness.cacheItem("701", "track", "3", "Autechre", "700")
                repeat(3) { harness.play("601", "Roygbiv", "track", "Boards of Canada", sectionKey = "3") }
                harness.play("701", "Gantz Graf", "track", "Autechre", sectionKey = "3", userId = "u-1")
                harness.play("701", "Gantz Graf", "track", "Autechre", sectionKey = "3", userId = "u-2")
                harness.play("11", "Akira", year = 1988)

                testApplication {
                    setupTautulliApp(harness)
                    val kometa = KometaTautulli.connect(client, harness.adminKey, 3, "artist", mapOf(600 to "artist", 700 to "artist"))
                    kometa.artistKeys("top") shouldContainExactly listOf(600, 700)
                    kometa.artistKeys("popular") shouldContainExactly listOf(700, 600)
                    kometa.trackCounts(after = "2026-01-01") shouldBe mapOf(701 to 2, 601 to 3)
                }
            }
        }

        test("get_home_stats answers one statistic, ranked by duration, for some users, from a position on") {
            createTautulliHarness().use { harness ->
                repeat(2) { harness.play("11", "Akira", year = 1988, watchedSec = 100) }
                harness.play("12", "Heat", year = 1995, watchedSec = 900, userId = "u-2")
                // Same title, other film
                harness.play("21", "Dune", year = 1984)
                harness.play("22", "Dune", year = 2021)
                // Added to the library again: one film, with its newest key
                harness.play("31", "Ran", year = 1985, daysAgo = 3)
                harness.play("32", "Ran", year = 1985, daysAgo = 2)
                harness.play("41", "News", mediaType = "live_tv", sectionKey = null)

                testApplication {
                    setupTautulliApp(harness)

                    suspend fun movies(vararg params: Pair<String, Any>): List<Triple<String, Int, Int>> {
                        val answer =
                            client.tautulli("apikey" to harness.adminKey, "cmd" to "get_home_stats", "stat_id" to "top_movies", *params)
                        val stat = answer.envelope().data.jsonArray.single().jsonObject
                        stat.getValue("stat_id").jsonPrimitive.content shouldBe "top_movies"
                        return stat.getValue("rows").jsonArray.map { it.jsonObject }.map { row ->
                            Triple(
                                row.getValue("title").jsonPrimitive.content,
                                row.getValue("rating_key").jsonPrimitive.int,
                                row.getValue("total_plays").jsonPrimitive.int,
                            )
                        }
                    }

                    movies() shouldContainExactly
                        listOf(
                            Triple("Akira", 11, 2),
                            Triple("Ran", 32, 2),
                            Triple("Dune", 22, 1),
                            Triple("Dune", 21, 1),
                            Triple("Heat", 12, 1),
                        )
                    movies("stats_type" to "duration", "stats_count" to 2).map { it.first } shouldContainExactly listOf("Ran", "Heat")
                    movies("stats_start" to 1, "stats_count" to 2).map { it.second } shouldContainExactly listOf(32, 22)
                    movies("user_id" to "1002").map { it.first } shouldContainExactly listOf("Heat")
                    movies("user_id" to "1002,1001", "stats_count" to 1).map { it.first } shouldContainExactly listOf("Akira")
                    movies("user_id" to "4711").shouldBeEmpty()
                }
            }
        }

        test("get_home_stats rows have every field of Tautulli's") {
            createTautulliHarness().use { harness ->
                harness.play("11", "Akira", year = 1988)
                testApplication {
                    setupTautulliApp(harness)
                    val stats =
                        client
                            .tautulli("apikey" to harness.adminKey, "cmd" to "get_home_stats")
                            .envelope()
                            .data.jsonArray
                            .map { it.jsonObject }
                    stats.map { it.getValue("stat_id").jsonPrimitive.content } shouldContainExactly
                        listOf("top_movies", "popular_movies", "top_tv", "popular_tv", "top_music", "popular_music")

                    val (top, popular) = stats.take(2).map { it.getValue("rows").jsonArray.single().jsonObject }
                    val fields =
                        listOf(
                            "title", "year", "total_plays", "users_watched", "rating_key", "grandparent_rating_key", "last_play",
                            "grandparent_thumb", "thumb", "art", "section_id", "media_type", "content_rating", "rating", "labels",
                            "user", "friendly_name", "platform", "live", "guid", "row_id",
                        )
                    top.keys shouldContainAll fields + "total_duration"
                    popular.keys shouldContainAll fields
                    top.getValue("users_watched") shouldBe JsonPrimitive("")
                    popular.getValue("users_watched") shouldBe JsonPrimitive(1)
                    top.getValue("section_id") shouldBe JsonPrimitive(1)
                    top.getValue("total_duration") shouldBe JsonPrimitive(600)
                    top.getValue("thumb") shouldBe JsonPrimitive("/library/metadata/11/thumb/1")
                }
            }
        }

        test("get_history filters, sorts and pages, with rows like Tautulli's") {
            createTautulliHarness().use { harness ->
                harness.cacheItem("201", "episode", "2", "Severance", "200")
                harness.play("11", "Akira", year = 1988, daysAgo = 5)
                harness.play("12", "Heat", year = 1995, daysAgo = 4, userId = "u-2", watchedSec = 5_000)
                harness.play(
                    "201", "Good News", "episode", "Severance", year = 2022, sectionKey = "2", daysAgo = 3,
                    completionPercent = 45.0, isWatched = false,
                )
                harness.play("41", "News", mediaType = "live_tv", sectionKey = null, daysAgo = 2, isWatched = false)
                harness.play("11", "Akira", year = 1988, userId = "u-other", serverId = OTHER_SERVER_ID)

                testApplication {
                    setupTautulliApp(harness)

                    suspend fun history(vararg params: Pair<String, Any>): JsonObject {
                        val answer = client.tautulli("apikey" to harness.adminKey, "cmd" to "get_history", *params)
                        answer.status shouldBe HttpStatusCode.OK
                        return answer.envelope().data.jsonObject
                    }

                    suspend fun keys(vararg params: Pair<String, Any>): List<Int> =
                        history(*params).getValue("data").jsonArray.map { it.jsonObject.getValue("rating_key").jsonPrimitive.int }

                    keys() shouldContainExactly listOf(41, 201, 12, 11)
                    keys("order_dir" to "asc") shouldContainExactly listOf(11, 12, 201, 41)
                    keys("order_column" to "duration", "length" to 1) shouldContainExactly listOf(12)
                    keys("start" to 1, "length" to 2) shouldContainExactly listOf(201, 12)
                    keys("section_id" to 2) shouldContainExactly listOf(201)
                    keys("section_id" to "1,2") shouldContainExactly listOf(201, 12, 11)
                    keys("user_id" to 1002) shouldContainExactly listOf(12)
                    keys("user_id" to 4711).shouldBeEmpty()
                    keys("rating_key" to "11,12") shouldContainExactly listOf(12, 11)
                    keys("media_type" to "movie,episode") shouldContainExactly listOf(201, 12, 11)
                    keys("media_type" to "live") shouldContainExactly listOf(41)
                    keys("media_type" to "all").size shouldBe 4

                    val day = Instant.ofEpochMilli(NOW_MS - 4 * DAY_MS).atZone(ZoneOffset.UTC).toLocalDate()
                    keys("after" to day) shouldContainExactly listOf(41, 201, 12)
                    keys("before" to day) shouldContainExactly listOf(12, 11)
                    keys("after" to day, "before" to day) shouldContainExactly listOf(12)

                    val page = history("section_id" to 2, "draw" to 7)
                    page.getValue("recordsFiltered") shouldBe JsonPrimitive(1)
                    page.getValue("recordsTotal") shouldBe JsonPrimitive(4)
                    page.getValue("draw") shouldBe JsonPrimitive(7)

                    val row = page.getValue("data").jsonArray.single().jsonObject
                    row.keys shouldContainAll
                        listOf(
                            "reference_id", "row_id", "id", "date", "started", "stopped", "duration", "play_duration", "paused_counter",
                            "user_id", "user", "friendly_name", "user_thumb", "platform", "product", "player", "ip_address", "live",
                            "machine_id", "location", "secure", "relayed", "media_type", "rating_key", "parent_rating_key",
                            "grandparent_rating_key", "full_title", "title", "parent_title", "grandparent_title", "original_title",
                            "year", "media_index", "parent_media_index", "thumb", "originally_available_at", "guid",
                            "transcode_decision", "percent_complete", "watched_status", "group_count", "group_ids", "state",
                            "session_key",
                        )
                    row.getValue("user_id") shouldBe JsonPrimitive(1001)
                    row.getValue("user") shouldBe JsonPrimitive("alice")
                    row.getValue("friendly_name") shouldBe JsonPrimitive("Alice")
                    row.getValue("grandparent_rating_key") shouldBe JsonPrimitive(200)
                    row.getValue("full_title") shouldBe JsonPrimitive("Severance - Good News")
                    row.getValue("transcode_decision") shouldBe JsonPrimitive("copy")
                    row.getValue("percent_complete") shouldBe JsonPrimitive(45)
                    // 45 % of a threshold of 80 %: past the second quarter
                    row.getValue("watched_status") shouldBe JsonPrimitive(0.5)
                    row.getValue("state") shouldBe JsonNull

                    val live = history("media_type" to "live").getValue("data").jsonArray.single().jsonObject
                    live.getValue("live") shouldBe JsonPrimitive(1)
                    live.getValue("watched_status") shouldBe JsonPrimitive(1)
                }
            }
        }

        test("refuses filters that Miru does not support instead of answering more than asked for") {
            createTautulliHarness().use { harness ->
                harness.play("11", "Akira", year = 1988)
                testApplication {
                    setupTautulliApp(harness)
                    val refused =
                        listOf(
                            "get_history" to ("grandparent_rating_key" to "200"),
                            "get_history" to ("parent_rating_key" to "200"),
                            "get_history" to ("user" to "alice"),
                            "get_history" to ("search" to "Akira"),
                            "get_history" to ("guid" to "plex://movie/1"),
                            "get_history" to ("start_date" to "2026-01-01"),
                            "get_history" to ("transcode_decision" to "copy"),
                            "get_history" to ("media_type" to "collection"),
                            "get_history" to ("include_activity" to "1"),
                            "get_home_stats" to ("before" to "2026-01-01"),
                            "get_home_stats" to ("stat_id" to "top_users"),
                            "status" to ("check" to "database"),
                            "status" to ("out_type" to "xml"),
                        )
                    for ((command, param) in refused) {
                        val answer = client.tautulli("apikey" to harness.adminKey, "cmd" to command, param)
                        answer.status shouldBe HttpStatusCode.BadRequest
                        answer.envelope().result shouldBe "error"
                        answer.envelope().message shouldContain param.first
                        answer.envelope().data shouldBe JsonObject(emptyMap())
                    }
                    // Blank ones, as forms send them, and ones that do not change the answer
                    client
                        .tautulli(
                            "apikey" to harness.adminKey, "cmd" to "get_history", "grandparent_rating_key" to "",
                            "grouping" to "1", "include_activity" to "0", "whatever" to "x",
                        ).status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("refuses values it cannot read") {
            createTautulliHarness().use { harness ->
                testApplication {
                    setupTautulliApp(harness)
                    val invalid =
                        listOf(
                            "get_home_stats" to ("time_range" to "month"),
                            "get_home_stats" to ("time_range" to "-1"),
                            "get_home_stats" to ("stats_count" to "10001"),
                            "get_home_stats" to ("stats_type" to "bytes"),
                            "get_history" to ("length" to "0"),
                            "get_history" to ("length" to "1001"),
                            "get_history" to ("start" to "-1"),
                            "get_history" to ("after" to "yesterday"),
                            "get_history" to ("order_column" to "ip_address"),
                            "get_history" to ("order_dir" to "up"),
                        )
                    for ((command, param) in invalid) {
                        val answer = client.tautulli("apikey" to harness.adminKey, "cmd" to command, param)
                        answer.status shouldBe HttpStatusCode.BadRequest
                        answer.envelope().message shouldContain param.first
                    }
                }
            }
        }

        test("never logs the API key, and hides internal errors") {
            createTautulliHarness().use { harness ->
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                val appLogger = LoggerFactory.getLogger("app.hononeko.miru") as Logger
                val previousLevel = appLogger.level
                appLogger.level = Level.DEBUG
                rootLogger.addAppender(appender)
                try {
                    testApplication {
                        setupTautulliApp(harness)
                        client.tautulli("apikey" to harness.adminKey, "cmd" to "get_home_stats").status shouldBe HttpStatusCode.OK
                        client.tautulli("apikey" to harness.adminKey, "cmd" to "nope").status shouldBe HttpStatusCode.BadRequest
                        client.tautulli("apikey" to "wrong-key-4711", "cmd" to "status").status shouldBe HttpStatusCode.Unauthorized

                        harness.serverId = MiruError.UpstreamUnavailable("plex", "secret upstream detail").left()
                        val down = client.tautulli("apikey" to harness.adminKey, "cmd" to "get_home_stats")
                        down.status shouldBe HttpStatusCode.BadGateway
                        down.envelope().result shouldBe "error"
                        down.envelope().message shouldNotContain "secret upstream detail"
                    }
                } finally {
                    rootLogger.detachAppender(appender)
                    appLogger.level = previousLevel
                }
                val log = appender.list.joinToString("\n") { it.formattedMessage }
                log shouldContain "/api/v2"
                log shouldNotContain harness.adminKey
                log shouldNotContain "wrong-key-4711"
            }
        }
    })
