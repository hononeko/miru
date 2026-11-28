package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

class SqliteRepositoryTest :
    FunSpec({
        var tempDir: Path? = null
        var handle: DatabaseHandle? = null

        fun requireHandle(): DatabaseHandle = checkNotNull(handle) { "SQLite handle not initialized" }

        beforeSpec {
            val dir = Files.createTempDirectory("miru-sqlite-test-")
            tempDir = dir
            val dbPath = dir.resolve("nested/miru.db")
            handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath.toString())).shouldBeRight()
        }

        afterSpec {
            handle?.close()
            tempDir?.toFile()?.deleteRecursively()
        }

        test("enables WAL mode, foreign keys, and passes DatabaseReadinessCheck") {
            val db = requireHandle()
            db.dialect shouldBe DatabaseDialect.SQLITE

            db.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1).lowercase() shouldBe "wal"
                    }
                    stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                    stmt.executeQuery("PRAGMA synchronous").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                    stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 5000
                    }
                }
            }

            DatabaseReadinessCheck(db.dataSource).check().shouldBeRight()
        }

        test("returns PersistenceError when readiness check runs against closed pool and on blank sqlite path") {
            val emptyPathResult = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite("   "))
            emptyPathResult.shouldBeLeft()

            val ephemeral = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(":memory:")).shouldBeRight()
            ephemeral.close()
            DatabaseReadinessCheck(ephemeral.dataSource).check().shouldBeLeft()
        }

        test("V8 marks existing SSO accounts AUTO only when their links are all email matches") {
            val dir = Files.createTempDirectory("miru-sqlite-link-mode-")
            try {
                assertMediaUserLinkModeBackfill(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString()))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("V16 and V17 index the plays and library items an existing database already has") {
            val dir = Files.createTempDirectory("miru-sqlite-query-indexes-")
            try {
                assertQueryIndexesBuildOverExistingRows(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString()))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("plans unscoped stats, library access checks and stale media plays on the query indexes") {
            val db = requireHandle()

            fun plan(sql: String): String =
                db.dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                            buildList { while (rs.next()) add(rs.getString("detail")) }.joinToString("\n")
                        }
                    }
                }

            // The shape of the default history page: read in order from the index, with no sort
            val historyPage = plan("SELECT * FROM playback_history WHERE 1 ORDER BY stopped_at_ms DESC, id ASC LIMIT 50 OFFSET 50")
            historyPage shouldContain "USING INDEX idx_playback_history_stopped_at"
            historyPage shouldNotContain "TEMP B-TREE"
            // The shapes of the unscoped overview, heatmap and client stats reads
            plan(
                "SELECT user_id, title FROM playback_history WHERE stopped_at_ms >= 1 ORDER BY stopped_at_ms ASC, id ASC",
            ) shouldContain "USING INDEX idx_playback_history_stopped_at"
            plan(
                "SELECT stopped_at_ms, watched_duration_sec FROM playback_history WHERE stopped_at_ms >= 1 AND stopped_at_ms <= 2",
            ) shouldContain "USING INDEX idx_playback_history_stopped_at"
            plan("SELECT server_id, section_key FROM media_items_cache WHERE rating_key = 'rk-1'") shouldContain
                "USING COVERING INDEX idx_media_items_cache_rating_key"
            // The plays that the stale media report aggregates per item, read in rating key order from the index alone
            val stalePlays =
                plan(
                    "SELECT rating_key, COUNT(*) AS play_count, MAX(stopped_at_ms) AS last_played_at_ms " +
                        "FROM playback_history WHERE server_id = 'srv-1' GROUP BY rating_key",
                )
            stalePlays shouldContain "USING COVERING INDEX idx_playback_history_server_rating_stopped"
            stalePlays shouldNotContain "TEMP B-TREE"
        }

        test("findSectionKeysByRatingKey unions cache, playback history, and active sessions") {
            val db = requireHandle()
            val servers = ExposedMediaServerRepository(db)
            val users = ExposedUserRepository(db)
            val library = ExposedLibraryRepository(db)
            val history = ExposedPlaybackHistoryRepository(db)
            val sessions = ExposedActiveSessionRepository(db)

            library.findSectionKeysByRatingKey("   ").shouldBeRight() shouldBe emptySet()

            servers
                .upsert(
                    MediaServerRecord(
                        id = "srv-rk",
                        name = "RK Plex",
                        serverType = "PLEX",
                        machineId = "mach-rk",
                        baseUrl = "http://plex.rk:32400",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ).shouldBeRight()
            users
                .upsert(
                    UserRecord(
                        id = "u-rk",
                        serverId = "srv-rk",
                        externalUserId = "rk-ext-1",
                        username = "rkuser",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ).shouldBeRight()

            library
                .upsertMediaItems(
                    listOf(
                        MediaItemCacheRecord(
                            serverId = "srv-rk",
                            ratingKey = "rk-shared",
                            sectionKey = "sec-cache",
                            mediaType = "movie",
                            title = "Cached Movie",
                            addedAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ),
                ).shouldBeRight()

            history
                .insert(
                    PlaybackHistoryRecord(
                        id = "hist-rk-1",
                        serverId = "srv-rk",
                        userId = "u-rk",
                        media =
                            MediaMetadataRecord(
                                ratingKey = "rk-shared",
                                mediaType = "movie",
                                title = "History Movie",
                                librarySectionKey = "sec-history",
                                durationMs = 120_000L,
                            ),
                        viewOffsetMs = 60_000L,
                        watchedDurationSec = 60,
                        completionPercent = 50.0,
                        isWatched = false,
                        streamTech = StreamTechRecord(streamDecision = "direct_play"),
                        player = PlayerRecord(playerName = "Web"),
                        startedAtMs = 1_000L,
                        stoppedAtMs = 61_000L,
                    ),
                ).shouldBeRight()

            sessions
                .upsert(
                    ActiveSessionRecord(
                        serverId = "srv-rk",
                        sessionKey = "sess-rk-1",
                        userId = "u-rk",
                        media =
                            MediaMetadataRecord(
                                ratingKey = "rk-shared",
                                mediaType = "movie",
                                title = "Active Movie",
                                librarySectionKey = "sec-session",
                                durationMs = 120_000L,
                            ),
                        viewOffsetMs = 10_000L,
                        state = "playing",
                        streamTech = StreamTechRecord(streamDecision = "direct_play"),
                        player = PlayerRecord(playerName = "Web"),
                        startedAtMs = 2_000L,
                        lastSeenAtMs = 2_500L,
                    ),
                ).shouldBeRight()

            val sections = library.findSectionKeysByRatingKey("rk-shared").shouldBeRight()
            sections shouldContainExactlyInAnyOrder
                listOf(
                    "srv-rk" to "sec-cache",
                    "srv-rk" to "sec-history",
                    "srv-rk" to "sec-session",
                )
        }

        include(repositoryContractSpec { requireHandle() })
    })
