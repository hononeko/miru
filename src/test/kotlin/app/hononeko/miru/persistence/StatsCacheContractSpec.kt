package app.hononeko.miru.persistence

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.importer.TautulliImportOptions
import app.hononeko.miru.importer.TautulliImporterService
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.DisplayNameMutation
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.OverviewStatsRow
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.StatsResultCache
import app.hononeko.miru.service.invalidatingStats
import app.hononeko.miru.session.ReconcileTrigger
import app.hononeko.miru.session.SessionStateEngine
import arrow.core.Either
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.funSpec
import io.kotest.core.spec.style.scopes.FunSpecRootScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val HISTORY_WRITE_METHODS = setOf("insert", "insertIfAbsentByImportKey", "insertBatchIfAbsentByImportKey", "updateMergedSession")
private val ADMIN = MiruPrincipal(accountId = "acc-admin", username = "admin", role = MiruRole.ADMIN)
private const val CONCURRENT_CALLERS = 8
private const val COALESCE_SETTLE_MS = 200L
private const val GATE_TIMEOUT_MS = 10_000L

/** Holds back the first overview scan until released, so concurrent callers pile up behind it. */
private class OverviewScanGate(
    private val delegate: PlaybackHistoryRepository,
) : PlaybackHistoryRepository by delegate {
    val reached = CompletableDeferred<Unit>()

    @Volatile
    var release: CompletableDeferred<Unit>? = null

    override suspend fun forEachOverviewStatsRow(
        serverId: String?,
        userIds: Set<String>?,
        fromMs: Long?,
        toMs: Long?,
        onRow: (OverviewStatsRow) -> Unit,
    ): Either<MiruError.PersistenceError, Unit> {
        release?.let { gate ->
            reached.complete(Unit)
            gate.await()
        }
        return delegate.forEachOverviewStatsRow(serverId, userIds, fromMs, toMs, onRow)
    }
}

private class StatsCacheEnv(
    val handle: DatabaseHandle,
) {
    var nowMs: Long = Instant.parse("2026-04-10T12:00:00Z").toEpochMilli()
    val cache = StatsResultCache()
    val historyReads = AtomicInteger()
    val gate = OverviewScanGate(ExposedPlaybackHistoryRepository(handle))
    val history: PlaybackHistoryRepository = countingReads(gate, historyReads).invalidatingStats(cache)
    val users: UserRepository = ExposedUserRepository(handle).invalidatingStats(cache)
    val servers: MediaServerRepository = ExposedMediaServerRepository(handle).invalidatingStats(cache)
    val accounts: AuthAccountRepository = ExposedAuthAccountRepository(handle).invalidatingStats(cache)
    val service =
        HistoryAndStatsService(
            playbackHistoryRepository = history,
            userRepository = users,
            clock = { nowMs },
            mediaServerRepository = servers,
            authAccountRepository = accounts,
            statsCache = cache,
        )

    /** Runs [block] and returns its result with the number of playback history reads it made. */
    suspend fun <T> reads(block: suspend () -> T): Pair<T, Int> {
        val before = historyReads.get()
        val result = block()
        return result to (historyReads.get() - before)
    }

    suspend fun seedServer(
        id: String,
        name: String = "Plex $id",
    ) {
        servers
            .upsert(
                MediaServerRecord(
                    id = id,
                    name = name,
                    serverType = "plex",
                    machineId = "machine-$id",
                    baseUrl = "http://$id.local:32400",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
    }

    suspend fun seedUser(
        id: String,
        serverId: String = "srv-1",
        username: String = id.removePrefix("u-"),
        displayName: String? = username.replaceFirstChar { it.uppercase() },
    ) {
        users.upsert(statsUser(id, serverId, username, displayName)).shouldBeRight()
    }

    suspend fun seedPlay(
        id: String,
        userId: String,
        stoppedAt: String,
        serverId: String = "srv-1",
        ratingKey: String = "mov-$id",
    ) {
        val stoppedAtMs = Instant.parse(stoppedAt).toEpochMilli()
        history.insert(statsPlay(id, serverId, userId, ratingKey, stoppedAtMs)).shouldBeRight()
    }
}

/** Counts every read of [delegate], whatever the stats code calls, so a warm call that reads at all shows up. */
@Suppress("SpreadOperator")
private fun countingReads(
    delegate: PlaybackHistoryRepository,
    reads: AtomicInteger,
): PlaybackHistoryRepository =
    Proxy.newProxyInstance(
        PlaybackHistoryRepository::class.java.classLoader,
        arrayOf(PlaybackHistoryRepository::class.java),
    ) { _, method, args ->
        if (method.declaringClass != Any::class.java && method.name !in HISTORY_WRITE_METHODS) {
            reads.incrementAndGet()
        }
        try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as PlaybackHistoryRepository

private fun statsUser(
    id: String,
    serverId: String,
    username: String,
    displayName: String?,
): UserRecord =
    UserRecord(
        id = id,
        serverId = serverId,
        externalUserId = "ext-$id",
        username = username,
        displayName = displayName,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun statsPlay(
    id: String,
    serverId: String,
    userId: String,
    ratingKey: String,
    stoppedAtMs: Long,
    externalImportKey: String? = null,
): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = id,
        serverId = serverId,
        userId = userId,
        externalImportKey = externalImportKey,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = "movie",
                title = "Movie $ratingKey",
                librarySectionKey = "sec-movies",
                durationMs = 6_000_000L,
            ),
        viewOffsetMs = 5_400_000L,
        watchedDurationSec = 600,
        completionPercent = 90.0,
        isWatched = true,
        streamTech = StreamTechRecord(streamDecision = "direct_play"),
        player = PlayerRecord(playerName = "Web", playerPlatform = "Chrome", playerProduct = "Plex Web", location = "lan"),
        startedAtMs = stoppedAtMs - 600_000L,
        stoppedAtMs = stoppedAtMs,
    )

private fun statsAccount(
    id: String,
    linkMode: String = "MANUAL",
): AuthAccountRecord =
    AuthAccountRecord(
        id = id,
        username = id,
        usernameNormalized = id,
        passwordHash = "hash-$id",
        role = "USER",
        mediaUserLinkMode = linkMode,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun restricted(
    accountId: String,
    vararg linkedMediaUserIds: String,
): MiruPrincipal =
    MiruPrincipal(
        accountId = accountId,
        username = accountId,
        role = MiruRole.USER,
        linkedMediaUserIds = linkedMediaUserIds.toSet(),
    )

private fun playingSnapshot(
    sessionKey: String,
    state: PlaybackState = PlaybackState.PLAYING,
    viewOffsetMs: Long,
): ActiveStreamSnapshot =
    ActiveStreamSnapshot(
        sessionId = "plex-sess-$sessionKey",
        sessionKey = sessionKey,
        state = state,
        viewOffsetMs = viewOffsetMs,
        media =
            StreamMedia(
                ratingKey = "movie-live",
                mediaType = MediaType.MOVIE,
                title = "Spirited Away",
                durationMs = 1_000_000L,
                librarySectionId = "sec-1",
            ),
        user = StreamUser(externalId = "ext-live", username = "alice"),
        player =
            StreamPlayer(
                machineIdentifier = "appletv-1",
                product = "Plex for Apple TV",
                platform = "tvOS",
                location = StreamLocation.LAN,
                isLocal = true,
            ),
        technical = StreamTechnicalInfo(decision = StreamDecision.DIRECT_PLAY),
    )

private fun createStatsCacheTautulliDb(path: Path): Path {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("CREATE TABLE users (user_id INTEGER, username TEXT, friendly_name TEXT)")
            stmt.executeUpdate(
                "CREATE TABLE session_history (id INTEGER PRIMARY KEY, reference_id INTEGER, started INTEGER, stopped INTEGER, " +
                    "rating_key TEXT, user_id INTEGER, user TEXT, paused_counter INTEGER, view_offset INTEGER, media_type TEXT)",
            )
            stmt.executeUpdate("CREATE TABLE session_history_metadata (id INTEGER PRIMARY KEY, title TEXT, duration INTEGER)")
            stmt.executeUpdate("CREATE TABLE session_history_media_info (id INTEGER PRIMARY KEY, transcode_decision TEXT)")
            stmt.executeUpdate("INSERT INTO users (user_id, username, friendly_name) VALUES (1, 'tautulli-alice', 'Alice')")
            stmt.executeUpdate(
                "INSERT INTO session_history (id, reference_id, started, stopped, rating_key, user_id, user, paused_counter, " +
                    "view_offset, media_type) VALUES (1, 10, 1700000000, 1700001000, 'rk-1', 1, 'tautulli-alice', 0, 950, 'movie')",
            )
            stmt.executeUpdate("INSERT INTO session_history_metadata (id, title, duration) VALUES (1, 'Movie One', 2000)")
            stmt.executeUpdate("INSERT INTO session_history_media_info (id, transcode_decision) VALUES (1, 'direct play')")
        }
    }
    return path
}

/** [HistoryAndStatsService] with its stats cache over real repositories, for SQLite and PostgreSQL alike. */
fun statsCacheContractSpec(handleProvider: () -> DatabaseHandle): TestFactory =
    funSpec {
        lateinit var env: StatsCacheEnv

        beforeEach {
            val handle = handleProvider()
            handle.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM auth_accounts")
                    stmt.executeUpdate("DELETE FROM media_servers")
                }
            }
            env = StatsCacheEnv(handle)
        }

        statsCacheHitTests { env }
        statsCachePlayWriteTests { env }
        statsCacheViewerWriteTests { env }
        statsCacheWriteCoverageTests { env }
        statsCacheClockTests { env }
        statsCacheIsolationTests { env }
        statsCacheConcurrencyTests { env }
    }

private fun FunSpecRootScope.statsCacheHitTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache hits") {
        test("serves warm overview, user, heatmap, clients and wrapped stats without reading playback history") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedUser("u-alice")
            env.seedUser("u-bob")
            env.seedPlay("p-1", "u-alice", "2026-04-09T20:00:00Z")
            env.seedPlay("p-2", "u-bob", "2026-04-08T20:00:00Z")
            env.seedPlay("p-3", "u-alice", "2025-12-01T20:00:00Z")
            val service = env.service

            val calls: List<Pair<String, suspend () -> Either<MiruError, Any>>> =
                listOf(
                    "overview 30d" to { service.getOverviewStats(ADMIN, "30d") },
                    "overview all by duration" to { service.getOverviewStats(ADMIN, "all", metricRaw = "duration") },
                    "user" to { service.getUserStats(ADMIN, "u-alice", "all") },
                    "heatmap" to { service.getHeatmapStats(ADMIN, "7d") },
                    "clients" to { service.getClientStats(ADMIN, "30d", userId = "u-alice") },
                    "wrapped" to { service.getWrappedStats(ADMIN, "2026") },
                    "wrapped for a viewer" to { service.getWrappedStats(ADMIN, "2025", userId = "u-alice") },
                )
            for ((name, call) in calls) {
                val (cold, coldReads) = env.reads { call().shouldBeRight() }
                withClue(name) { coldReads shouldBeGreaterThan 0 }
                val (warm, warmReads) = env.reads { call().shouldBeRight() }
                withClue(name) {
                    warmReads shouldBe 0
                    warm shouldBe cold
                }
            }

            env.service.getOverviewStats(ADMIN, "30d").shouldBeRight().totalPlays shouldBe 2L
            env.service.getWrappedStats(ADMIN, "2025", userId = "u-alice").shouldBeRight().totalPlays shouldBe 1L
        }
    }
}

private fun FunSpecRootScope.statsCachePlayWriteTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache invalidation by plays") {
        test("shows a finalized session, a resume merged into it and a Tautulli import at once") {
            val env = envProvider()
            env.seedServer("srv-1")
            var engineNowMs = 0L
            val engine =
                SessionStateEngine(
                    serverIdProvider = { "srv-1".right() },
                    userRepository = env.users,
                    activeSessionRepository = ExposedActiveSessionRepository(env.handle),
                    playbackHistoryRepository = env.history,
                    config = SessionConfig(minPlayDurationSeconds = 120, resumeMergeWindowSeconds = 120),
                    clock = { engineNowMs },
                )
            val overview = suspend { env.service.getOverviewStats(ADMIN, "all").shouldBeRight() }

            overview().totalPlays shouldBe 0L
            env.reads { overview() }.second shouldBe 0

            engine.reconcile(listOf(playingSnapshot("10", viewOffsetMs = 10_000L)), ReconcileTrigger.NOTIFICATION).shouldBeRight()
            // Playing writes no history, though seeing a viewer for the first time adds them as a user
            overview().totalPlays shouldBe 0L
            env.reads { overview() }.second shouldBe 0
            engineNowMs = 200_000L
            engine
                .reconcile(
                    listOf(playingSnapshot("10", PlaybackState.STOPPED, viewOffsetMs = 500_000L)),
                    ReconcileTrigger.NOTIFICATION,
                ).shouldBeRight()
            val afterFinalize = overview()
            afterFinalize.totalPlays shouldBe 1L
            afterFinalize.totalWatchDurationSec shouldBe 200L

            engineNowMs = 260_000L
            engine.reconcile(listOf(playingSnapshot("11", viewOffsetMs = 500_000L)), ReconcileTrigger.NOTIFICATION).shouldBeRight()
            overview().totalWatchDurationSec shouldBe 200L
            engineNowMs = 290_000L
            engine
                .reconcile(
                    listOf(playingSnapshot("11", PlaybackState.STOPPED, viewOffsetMs = 900_000L)),
                    ReconcileTrigger.NOTIFICATION,
                ).shouldBeRight()
            val afterMerge = overview()
            afterMerge.totalPlays shouldBe 1L
            afterMerge.totalWatchDurationSec shouldBe 230L

            val tempDir = Files.createTempDirectory("miru-stats-cache-tautulli-")
            try {
                val importer =
                    TautulliImporterService(
                        mediaServerRepository = env.servers,
                        userRepository = env.users,
                        playbackHistoryRepository = env.history,
                    )
                importer
                    .import(createStatsCacheTautulliDb(tempDir.resolve("tautulli.db")), TautulliImportOptions(serverId = "srv-1"))
                    .shouldBeRight()
                    .historyImported shouldBe 1
            } finally {
                tempDir.toFile().deleteRecursively()
            }
            overview().totalPlays shouldBe 2L
        }

        test("drops a removed server's plays at once") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedServer("srv-2")
            env.seedUser("u-alice")
            env.seedUser("u-jelly", serverId = "srv-2")
            env.seedPlay("p-1", "u-alice", "2026-04-09T20:00:00Z")
            env.seedPlay("p-2", "u-jelly", "2026-04-09T21:00:00Z", serverId = "srv-2")
            env.service.getOverviewStats(ADMIN, "all").shouldBeRight().totalPlays shouldBe 2L
            env.service.getUserStats(ADMIN, "u-jelly", "all").shouldBeRight().totalPlays shouldBe 1L

            env.servers.delete("srv-2").shouldBeRight() shouldBe true

            env.service.getOverviewStats(ADMIN, "all").shouldBeRight().totalPlays shouldBe 1L
            env.service
                .getUserStats(ADMIN, "u-jelly", "all")
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.NotFound>()
        }
    }
}

private fun FunSpecRootScope.statsCacheViewerWriteTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache invalidation by viewers") {
        test("shows renamed viewers, account links and account display names at once") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedServer("srv-2")
            env.seedUser("u-alice")
            env.seedUser("u-alice-jf", serverId = "srv-2", username = "alice")
            env.seedUser("u-bob")
            env.seedPlay("p-1", "u-alice", "2026-04-09T20:00:00Z")
            env.seedPlay("p-2", "u-alice-jf", "2026-04-09T21:00:00Z", serverId = "srv-2")
            env.seedPlay("p-3", "u-bob", "2026-04-09T22:00:00Z")
            val aliceStats = suspend { env.service.getUserStats(ADMIN, "u-alice", "all").shouldBeRight() }
            val viewersOnApril9 = suspend {
                env.service
                    .getOverviewStats(ADMIN, "30d")
                    .shouldBeRight()
                    .playsPerDay
                    .single { it.date == "2026-04-09" }
                    .users
                    .map { it.displayName ?: it.username }
                    .sorted()
            }

            aliceStats().displayName shouldBe "Alice"
            viewersOnApril9() shouldContainExactly listOf("Alice", "Alice", "Bob")

            env.users.upsert(statsUser("u-alice", "srv-1", "alice", "Alice Liddell")).shouldBeRight()
            aliceStats().displayName shouldBe "Alice Liddell"

            env.accounts.insert(statsAccount("acc-alice", linkMode = "AUTO")).shouldBeRight()
            env.accounts.replaceLinkedMediaUserIds("acc-alice", setOf("u-alice", "u-alice-jf"), createdAtMs = 2_000L).shouldBeRight()
            val linked = aliceStats()
            linked.userId shouldBe "acc-alice"
            linked.totalPlays shouldBe 2L
            env.service.getOverviewStats(ADMIN, "all").shouldBeRight().activeUsers shouldBe 2

            env.accounts
                .updateProfile("acc-alice", displayName = DisplayNameMutation.Set("Ally"), updatedAtMs = 3_000L)
                .shouldBeRight()
            aliceStats().displayName shouldBe "Ally"
            viewersOnApril9() shouldContainExactly listOf("Ally", "Bob")

            env.accounts.replaceLinkedMediaUserIdsIfAuto("acc-alice", setOf("u-alice"), createdAtMs = 4_000L).shouldBeRight() shouldBe true
            aliceStats().totalPlays shouldBe 1L

            env.accounts.delete("acc-alice").shouldBeRight() shouldBe true
            val unlinked = aliceStats()
            unlinked.userId shouldBe "u-alice"
            unlinked.displayName shouldBe "Alice Liddell"
            env.service.getOverviewStats(ADMIN, "all").shouldBeRight().activeUsers shouldBe 3
        }
    }
}

private fun FunSpecRootScope.statsCacheWriteCoverageTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache invalidating writes") {
        test("invalidates on each write that changes stats, and only on those") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedUser("u-alice")
            env.accounts.insert(statsAccount("acc-alice")).shouldBeRight()
            val play = statsPlay("p-1", "srv-1", "u-alice", "mov-1", stoppedAtMs = 5_000L, externalImportKey = "import-1")

            suspend fun generationsMovedBy(write: suspend () -> Unit): Long {
                val before = env.cache.currentGeneration()
                write()
                return env.cache.currentGeneration() - before
            }

            val invalidating: List<Pair<String, suspend () -> Unit>> =
                listOf(
                    "history insert" to { env.history.insert(play).shouldBeRight() },
                    "history merge" to { env.history.updateMergedSession(play.copy(watchedDurationSec = 900)).shouldBeRight() },
                    "history import" to {
                        env.history.insertIfAbsentByImportKey(play.copy(id = "p-2", externalImportKey = "import-2")).shouldBeRight()
                    },
                    "history import batch" to {
                        env.history
                            .insertBatchIfAbsentByImportKey(listOf(play.copy(id = "p-3", externalImportKey = "import-3")))
                            .shouldBeRight()
                    },
                    "user upsert" to { env.users.upsert(statsUser("u-alice", "srv-1", "alice", "Renamed")).shouldBeRight() },
                    "server upsert" to { env.seedServer("srv-2") },
                    "server rename" to {
                        env.servers
                            .updateCredentials(
                                id = "srv-2",
                                name = "Renamed",
                                baseUrl = "http://srv-2.local:32400",
                                encryptedToken = null,
                                clientIdentifier = null,
                                updatedAtMs = 6_000L,
                            )
                            .shouldBeRight()
                    },
                    "server credentials" to {
                        env.servers
                            .upsertExclusiveStoredCredentials(
                                MediaServerRecord(
                                    id = "srv-2",
                                    name = "Renamed",
                                    serverType = "plex",
                                    machineId = "machine-srv-2",
                                    baseUrl = "http://srv-2.local:32400",
                                    encryptedToken = "encrypted",
                                    createdAtMs = 1_000L,
                                    updatedAtMs = 7_000L,
                                ),
                            ).shouldBeRight()
                    },
                    "server disconnect" to { env.servers.clearCredentials("srv-2", 8_000L).shouldBeRight() },
                    "server removal" to { env.servers.delete("srv-2").shouldBeRight() },
                    "account insert" to { env.accounts.insert(statsAccount("acc-bob")).shouldBeRight() },
                    "account links" to {
                        env.accounts.replaceLinkedMediaUserIds("acc-alice", setOf("u-alice"), 9_000L).shouldBeRight()
                    },
                    "account auto links" to {
                        env.accounts.replaceLinkedMediaUserIdsIfAuto("acc-alice", setOf("u-alice"), 9_500L).shouldBeRight()
                    },
                    "account profile" to {
                        env.accounts.updateProfile("acc-alice", DisplayNameMutation.Set("Ally"), updatedAtMs = 10_000L).shouldBeRight()
                    },
                    "account removal" to { env.accounts.delete("acc-bob").shouldBeRight() },
                )
            for ((name, write) in invalidating) {
                withClue(name) { generationsMovedBy(write) shouldBe 1L }
            }

            val passive: List<Pair<String, suspend () -> Unit>> =
                listOf(
                    "history reads" to { env.history.findById("p-1").shouldBeRight() },
                    "last seen" to { env.users.updateLastSeen("u-alice", lastSeenAtMs = 11_000L).shouldBeRight() },
                    "server reads" to { env.servers.findAll().shouldBeRight() },
                    "account link mode" to { env.accounts.updateMediaUserLinkMode("acc-alice", "MANUAL", 12_000L).shouldBeRight() },
                    "account password" to { env.accounts.updatePasswordHash("acc-alice", "hash-2", 13_000L).shouldBeRight() },
                )
            for ((name, write) in passive) {
                withClue(name) { generationsMovedBy(write) shouldBe 0L }
            }
        }
    }
}

private fun FunSpecRootScope.statsCacheClockTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache over time") {
        test("moves rolling windows and the heatmap on at UTC midnight without a write") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedUser("u-alice")
            env.nowMs = Instant.parse("2026-04-10T23:59:00Z").toEpochMilli()
            env.seedPlay("p-1", "u-alice", "2026-04-03T23:59:30Z")
            val service = env.service

            service.getOverviewStats(ADMIN, "7d").shouldBeRight().totalPlays shouldBe 1L
            service.getHeatmapStats(ADMIN, "7d").shouldBeRight().cells.last().date shouldBe "2026-04-10"
            service.getOverviewStats(ADMIN, "all").shouldBeRight().totalPlays shouldBe 1L
            env.reads { service.getOverviewStats(ADMIN, "7d").shouldBeRight() }.second shouldBe 0

            env.nowMs = Instant.parse("2026-04-11T00:00:30Z").toEpochMilli()
            val (rolled, rolledReads) = env.reads { service.getOverviewStats(ADMIN, "7d").shouldBeRight() }
            rolledReads shouldBeGreaterThan 0
            rolled.totalPlays shouldBe 0L
            val (heatmap, heatmapReads) = env.reads { service.getHeatmapStats(ADMIN, "7d").shouldBeRight() }
            heatmapReads shouldBeGreaterThan 0
            heatmap.cells.last().date shouldBe "2026-04-11"
            // A fixed period does not depend on the day, so it is still cached
            env.reads { service.getOverviewStats(ADMIN, "all").shouldBeRight() }.second shouldBe 0

            // Until its time to live runs out
            env.nowMs += 5 * 60 * 1000L
            env.reads { service.getOverviewStats(ADMIN, "all").shouldBeRight() }.second shouldBeGreaterThan 0
        }
    }
}

private fun FunSpecRootScope.statsCacheIsolationTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache access isolation") {
        test("never serves a restricted caller an admin's payload or another viewer's") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedUser("u-alice")
            env.seedUser("u-bob")
            env.seedPlay("p-1", "u-alice", "2026-04-09T20:00:00Z")
            env.seedPlay("p-2", "u-alice", "2026-04-08T20:00:00Z")
            env.seedPlay("p-3", "u-bob", "2026-04-09T21:00:00Z")
            val service = env.service
            val alice = restricted("acc-alice", "u-alice")
            val bob = restricted("acc-bob", "u-bob")
            val unlinked = restricted("acc-carol")

            service.getOverviewStats(ADMIN, "all").shouldBeRight().totalPlays shouldBe 3L
            service.getHeatmapStats(ADMIN, "7d").shouldBeRight().totalPlays shouldBe 3L
            service.getClientStats(ADMIN, "30d").shouldBeRight().totalPlays shouldBe 3L
            service.getWrappedStats(ADMIN, "2026").shouldBeRight().totalPlays shouldBe 3L
            service.getUserStats(ADMIN, "u-bob", "all").shouldBeRight().totalPlays shouldBe 1L
            service.getWrappedStats(ADMIN, "2026", userId = "u-bob").shouldBeRight().totalPlays shouldBe 1L

            for (round in 1..2) {
                withClue("round $round") {
                    service.getOverviewStats(alice, "all").shouldBeRight().totalPlays shouldBe 2L
                    service.getOverviewStats(bob, "all").shouldBeRight().totalPlays shouldBe 1L
                    service.getOverviewStats(unlinked, "all").shouldBeRight().totalPlays shouldBe 0L
                    service.getHeatmapStats(alice, "7d").shouldBeRight().totalPlays shouldBe 2L
                    service.getClientStats(bob, "30d").shouldBeRight().totalPlays shouldBe 1L
                    service.getWrappedStats(alice, "2026").shouldBeRight().totalPlays shouldBe 2L
                    service.getWrappedStats(unlinked, "2026").shouldBeRight().totalPlays shouldBe 0L

                    service.getUserStats(alice, "u-bob", "all").shouldBeLeft().shouldBeInstanceOf<MiruError.Forbidden>()
                    service.getHeatmapStats(alice, "7d", userId = "u-bob").shouldBeLeft().shouldBeInstanceOf<MiruError.Forbidden>()
                    service.getClientStats(alice, "30d", userId = "u-bob").shouldBeLeft().shouldBeInstanceOf<MiruError.Forbidden>()
                    service
                        .getWrappedStats(alice, "2026", userId = "u-bob")
                        .shouldBeLeft()
                        .shouldBeInstanceOf<MiruError.Forbidden>()
                    service.getUserStats(unlinked, "u-alice", "all").shouldBeLeft().shouldBeInstanceOf<MiruError.Forbidden>()
                }
            }
            env.reads { service.getOverviewStats(alice, "all").shouldBeRight() }.second shouldBe 0

            // A viewer's own stats are the same whoever asks, so the admin's copy may serve them
            val adminView = service.getUserStats(ADMIN, "u-alice", "all").shouldBeRight()
            val (ownView, ownReads) = env.reads { service.getUserStats(alice, "u-alice", "all").shouldBeRight() }
            ownReads shouldBe 0
            ownView shouldBe adminView
        }
    }
}

private fun FunSpecRootScope.statsCacheConcurrencyTests(envProvider: () -> StatsCacheEnv) {
    context("Stats cache concurrency") {
        test("runs concurrent cold requests for the same stats as one scan") {
            val env = envProvider()
            env.seedServer("srv-1")
            env.seedUser("u-alice")
            env.seedPlay("p-1", "u-alice", "2026-04-09T20:00:00Z")
            val scansPerRequest = env.reads { env.service.getOverviewStats(ADMIN, "30d").shouldBeRight() }.second
            scansPerRequest shouldBeGreaterThan 0

            val release = CompletableDeferred<Unit>()
            env.gate.release = release
            val (results, reads) =
                env.reads {
                    coroutineScope {
                        val callers =
                            (1..CONCURRENT_CALLERS).map {
                                async(Dispatchers.Default) { env.service.getOverviewStats(ADMIN, "all") }
                            }
                        withTimeout(GATE_TIMEOUT_MS) { env.gate.reached.await() }
                        // Let the other callers queue behind the running scan before it finishes
                        delay(COALESCE_SETTLE_MS)
                        release.complete(Unit)
                        callers.awaitAll()
                    }
                }

            results.map { it.shouldBeRight().totalPlays } shouldContainExactly List(CONCURRENT_CALLERS) { 1L }
            reads shouldBe scansPerRequest
        }
    }
}
