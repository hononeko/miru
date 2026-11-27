package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.table.MediaServersTable
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import java.nio.file.Files
import java.sql.Connection

class SqliteReadPoolTest :
    FunSpec({
        test("file-backed SQLite separates writer and reader pools with expected sizes and pragmas") {
            val dir = Files.createTempDirectory("miru-sqlite-readpool-")
            try {
                val dbPath = dir.resolve("miru.db").toString()
                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath, readPoolSize = 3)).shouldBeRight().use { handle ->
                    handle.readDataSource shouldNotBeSameInstanceAs handle.dataSource
                    handle.readDatabase shouldNotBeSameInstanceAs handle.database
                    handle.dataSource.maximumPoolSize shouldBe 1
                    handle.readDataSource.maximumPoolSize shouldBe 3

                    DatabaseReadinessCheck(handle).check().shouldBeRight()

                    // Hold all 3 reader connections simultaneously to verify multi-connection pool behavior and PRAGMAs
                    val connections = mutableListOf<Connection>()
                    try {
                        repeat(3) {
                            connections += handle.readDataSource.connection
                        }
                        for (conn in connections) {
                            conn.createStatement().use { stmt ->
                                stmt.executeQuery("PRAGMA query_only").use { rs ->
                                    rs.next() shouldBe true
                                    rs.getInt(1) shouldBe 1
                                }
                                stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                                    rs.next() shouldBe true
                                    rs.getInt(1) shouldBe 1
                                }
                                stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                                    rs.next() shouldBe true
                                    rs.getInt(1) shouldBe 5000
                                }
                                stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                                    rs.next() shouldBe true
                                    rs.getString(1).lowercase() shouldBe "wal"
                                }
                            }
                        }
                    } finally {
                        connections.forEach { runCatching { it.close() } }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("accidental writes on readDatabase fail loudly due to PRAGMA query_only = ON") {
            val dir = Files.createTempDirectory("miru-sqlite-readonly-")
            try {
                val dbPath = dir.resolve("miru.db").toString()
                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight().use { handle ->
                    val rawWriteError =
                        handle.readDatabase
                            .exposedQuery("accidental_raw_write") {
                                MediaServersTable.insert {
                                    it[id] = "srv-ro"
                                    it[name] = "ReadOnlyFail"
                                    it[serverType] = "PLEX"
                                    it[machineId] = "mach-ro"
                                    it[baseUrl] = "http://plex.local:32400"
                                    it[createdAtMs] = 1_000L
                                    it[updatedAtMs] = 1_000L
                                }
                            }.shouldBeLeft()
                    rawWriteError.message shouldContainIgnoringCase "readonly"

                    val miswiredRepo = ExposedMediaServerRepository(database = handle.readDatabase)
                    val repoWriteError =
                        miswiredRepo
                            .upsert(
                                MediaServerRecord(
                                    id = "srv-ro-2",
                                    name = "Miswired",
                                    serverType = "PLEX",
                                    machineId = "mach-ro-2",
                                    baseUrl = "http://plex.local:32400",
                                    createdAtMs = 1_000L,
                                    updatedAtMs = 1_000L,
                                ),
                            ).shouldBeLeft()
                    repoWriteError.message shouldContainIgnoringCase "readonly"
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("concurrent writes and heavy reads succeed without SQLITE_BUSY and preserve read-after-write consistency") {
            val dir = Files.createTempDirectory("miru-sqlite-concurrency-")
            try {
                val dbPath = dir.resolve("miru.db").toString()
                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight().use { handle ->
                    val serverRepo = ExposedMediaServerRepository(handle)
                    val userRepo = ExposedUserRepository(handle)
                    val sessionRepo = ExposedActiveSessionRepository(handle)
                    val historyRepo = ExposedPlaybackHistoryRepository(handle)

                    serverRepo
                        .upsert(
                            MediaServerRecord(
                                id = "srv-conc",
                                name = "Concurrent Plex",
                                serverType = "PLEX",
                                machineId = "mach-conc",
                                baseUrl = "http://plex.conc:32400",
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()
                    userRepo
                        .upsert(
                            UserRecord(
                                id = "u-conc",
                                serverId = "srv-conc",
                                externalUserId = "ext-conc",
                                username = "concuser",
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()

                    val totalWrites = 80
                    val readerWorkers = 12
                    val readsPerWorker = 25

                    coroutineScope {
                        val writers =
                            (0 until 4).map { worker ->
                                async(Dispatchers.IO) {
                                    for (idx in 0 until (totalWrites / 4)) {
                                        val globalIdx = worker * (totalWrites / 4) + idx
                                        val stoppedAt = 100_000L + globalIdx
                                        historyRepo
                                            .insert(
                                                PlaybackHistoryRecord(
                                                    id = "hist-conc-$globalIdx",
                                                    serverId = "srv-conc",
                                                    userId = "u-conc",
                                                    media =
                                                        MediaMetadataRecord(
                                                            ratingKey = "rk-${globalIdx % 10}",
                                                            mediaType = "movie",
                                                            title = "Movie $globalIdx",
                                                            durationMs = 120_000L,
                                                        ),
                                                    viewOffsetMs = 60_000L,
                                                    watchedDurationSec = 60,
                                                    completionPercent = 50.0,
                                                    isWatched = false,
                                                    streamTech = StreamTechRecord(streamDecision = "direct_play"),
                                                    player = PlayerRecord(playerName = "Web"),
                                                    startedAtMs = stoppedAt - 60_000L,
                                                    stoppedAtMs = stoppedAt,
                                                ),
                                            ).shouldBeRight()
                                        sessionRepo
                                            .upsert(
                                                ActiveSessionRecord(
                                                    serverId = "srv-conc",
                                                    sessionKey = "sess-$worker",
                                                    userId = "u-conc",
                                                    media =
                                                        MediaMetadataRecord(
                                                            ratingKey = "rk-$globalIdx",
                                                            mediaType = "movie",
                                                            title = "Active $globalIdx",
                                                            durationMs = 120_000L,
                                                        ),
                                                    viewOffsetMs = 10_000L,
                                                    state = "playing",
                                                    streamTech = StreamTechRecord(streamDecision = "direct_play"),
                                                    player = PlayerRecord(playerName = "Web"),
                                                    startedAtMs = 2_000L,
                                                    lastSeenAtMs = stoppedAt,
                                                ),
                                            ).shouldBeRight()
                                    }
                                }
                            }

                        val readers =
                            (0 until readerWorkers).map {
                                async(Dispatchers.IO) {
                                    repeat(readsPerWorker) {
                                        historyRepo.query(HistoryFilter(limit = 20, offset = 0)).shouldBeRight()
                                        historyRepo
                                            .forEachOverviewStatsRow(
                                                serverId = "srv-conc",
                                                userIds = null,
                                                fromMs = null,
                                                toMs = null,
                                            ) { }
                                            .shouldBeRight()
                                        sessionRepo.findAll().shouldBeRight()
                                        userRepo.findById("u-conc").shouldBeRight()
                                    }
                                }
                            }

                        (writers + readers).awaitAll()
                    }

                    // Immediate read-after-write consistency via reader pool once writers commit
                    val finalPage = historyRepo.query(HistoryFilter(limit = 100, offset = 0)).shouldBeRight()
                    finalPage.totalCount shouldBe totalWrites.toLong()
                    finalPage.items.size shouldBe totalWrites
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("in-memory SQLite falls back to shared pool while file URI uses separate reader pool") {
            DatabaseFactory.isInMemorySqlite(":memory:") shouldBe true
            DatabaseFactory.isInMemorySqlite(":MEMORY:") shouldBe true
            DatabaseFactory.isInMemorySqlite("  :Memory: ") shouldBe true
            DatabaseFactory.isInMemorySqlite("file:mru69_mem?mode=memory&cache=shared") shouldBe true
            DatabaseFactory.isInMemorySqlite("FILE:MRU69_MEM?MODE=MEMORY&CACHE=SHARED") shouldBe true
            DatabaseFactory.isInMemorySqlite("file::memory:?cache=shared") shouldBe true
            DatabaseFactory.isInMemorySqlite("/tmp/memory/miru.db") shouldBe false
            DatabaseFactory.isInMemorySqlite("file:/tmp/miru.db?cache=shared") shouldBe false

            val memoryVariants =
                listOf(
                    ":memory:",
                    "file:mru69_mem_lower?mode=memory&cache=shared",
                    "file::memory:?cache=shared",
                )
            for (path in memoryVariants) {
                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(path)).shouldBeRight().use { handle ->
                    handle.readDataSource shouldBeSameInstanceAs handle.dataSource
                    handle.readDatabase shouldBeSameInstanceAs handle.database
                    handle.dataSource.maximumPoolSize shouldBe 1

                    val serverRepo = ExposedMediaServerRepository(handle)
                    serverRepo
                        .upsert(
                            MediaServerRecord(
                                id = "srv-mem",
                                name = "Mem Plex",
                                serverType = "PLEX",
                                machineId = "mach-mem",
                                baseUrl = "http://plex.mem:32400",
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()
                    serverRepo.findById("srv-mem").shouldBeRight()?.name shouldBe "Mem Plex"
                }
            }

            val dir = Files.createTempDirectory("miru-sqlite-file-uri-")
            try {
                val fileUri = "file:${dir.resolve("uri.db")}?cache=shared"
                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(fileUri)).shouldBeRight().use { handle ->
                    handle.readDataSource shouldNotBeSameInstanceAs handle.dataSource
                    handle.readDatabase shouldNotBeSameInstanceAs handle.database
                    handle.readDataSource.maximumPoolSize shouldBe DatabaseConfig.Sqlite.DEFAULT_READ_POOL_SIZE
                }

                DatabaseFactory
                    .createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("bad-pool.db").toString(), readPoolSize = 0))
                    .shouldBeLeft()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("DatabaseReadinessCheck fails when reader pool closes and DatabaseHandle.close closes both pools safely") {
            val dir = Files.createTempDirectory("miru-sqlite-close-")
            try {
                val dbPath = dir.resolve("miru.db").toString()
                val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
                handle.readDataSource.close()
                DatabaseReadinessCheck(handle).check().shouldBeLeft()
                handle.close()
                handle.dataSource.isClosed shouldBe true
                handle.readDataSource.isClosed shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }

            val writerDs = mockk<HikariDataSource>(relaxed = true)
            val readerDs = mockk<HikariDataSource>(relaxed = true)
            val db = mockk<Database>(relaxed = true)
            every { readerDs.close() } throws IllegalStateException("reader close failure")
            every { writerDs.close() } throws IllegalStateException("writer close failure")

            val bothThrowHandle =
                DatabaseHandle(
                    dataSource = writerDs,
                    database = db,
                    dialect = DatabaseDialect.SQLITE,
                    readDataSource = readerDs,
                    readDatabase = db,
                )
            val thrown = shouldThrow<IllegalStateException> { bothThrowHandle.close() }
            thrown.message shouldBe "reader close failure"
            thrown.suppressed.single().message shouldBe "writer close failure"
            verify(exactly = 1) { readerDs.close() }
            verify(exactly = 1) { writerDs.close() }

            val singleDs = mockk<HikariDataSource>(relaxed = true)
            DatabaseHandle(dataSource = singleDs, database = db, dialect = DatabaseDialect.POSTGRES).close()
            verify(exactly = 1) { singleDs.close() }
        }
    })
