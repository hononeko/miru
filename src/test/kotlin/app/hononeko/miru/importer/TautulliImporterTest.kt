package app.hononeko.miru.importer

import app.hononeko.miru.config.AppConfigLoader
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

private class ImporterTestHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val service: TautulliImporterService,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createImporterHarness(): ImporterTestHarness {
    val dir = Files.createTempDirectory("miru-tautulli-test-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val serverRepo = ExposedMediaServerRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val service =
        TautulliImporterService(
            mediaServerRepository = serverRepo,
            userRepository = userRepo,
            playbackHistoryRepository = historyRepo,
            fallbackBaseUrl = "http://plex.local:32400",
        )
    return ImporterTestHarness(
        tempDir = dir,
        dbHandle = handle,
        serverRepo = serverRepo,
        userRepo = userRepo,
        historyRepo = historyRepo,
        service = service,
    )
}

private fun createRichTautulliFixture(path: Path): Path {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { stmt ->
            createRichTautulliSchema(stmt)
            seedRichTautulliData(stmt)
        }
    }
    return path
}

private fun createRichTautulliSchema(stmt: java.sql.Statement) {
    stmt.executeUpdate(
        """
        CREATE TABLE users (
            user_id INTEGER PRIMARY KEY,
            username TEXT,
            friendly_name TEXT,
            email TEXT,
            thumb TEXT,
            is_admin INTEGER
        )
        """.trimIndent(),
    )
    stmt.executeUpdate(
        """
        CREATE TABLE session_history (
            id INTEGER PRIMARY KEY,
            reference_id INTEGER,
            started INTEGER,
            stopped INTEGER,
            rating_key TEXT,
            user_id INTEGER,
            user TEXT,
            ip_address TEXT,
            paused_counter INTEGER,
            player TEXT,
            product TEXT,
            platform TEXT,
            machine_id TEXT,
            location TEXT,
            media_type TEXT,
            view_offset INTEGER,
            section_id TEXT
        )
        """.trimIndent(),
    )
    stmt.executeUpdate(
        """
        CREATE TABLE session_history_metadata (
            id INTEGER PRIMARY KEY,
            rating_key TEXT,
            title TEXT,
            full_title TEXT,
            parent_title TEXT,
            grandparent_title TEXT,
            parent_media_index INTEGER,
            media_index INTEGER,
            year INTEGER,
            thumb TEXT,
            duration INTEGER
        )
        """.trimIndent(),
    )
    stmt.executeUpdate(
        """
        CREATE TABLE session_history_media_info (
            id INTEGER PRIMARY KEY,
            transcode_decision TEXT,
            video_decision TEXT,
            audio_decision TEXT,
            transcode_hw_decoding INTEGER,
            transcode_hw_encoding INTEGER,
            video_codec TEXT,
            audio_codec TEXT,
            video_resolution TEXT,
            stream_video_resolution TEXT,
            stream_video_codec TEXT,
            stream_audio_codec TEXT,
            bandwidth INTEGER,
            bitrate INTEGER,
            container TEXT,
            stream_container TEXT,
            stream_subtitle_decision TEXT,
            transcode_container TEXT,
            transcode_video_codec TEXT
        )
        """.trimIndent(),
    )
}

private fun seedRichTautulliData(stmt: java.sql.Statement) {
    stmt.executeUpdate("INSERT INTO users VALUES (101, 'alice', 'Alice K', 'alice@example.com', '/thumb/alice', 1)")
    stmt.executeUpdate("INSERT INTO users VALUES (102, 'bob', NULL, NULL, NULL, 0)")

    // Grouped session reference_id=500 across rows 1 and 2
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (1, 500, 1700000000, 1700000600, 'rk-ep1', 101, 'alice', " +
            "'10.0.0.10', 30, 'Living Room TV', 'Plex for Apple TV', 'tvOS', 'atv-1', 'LAN', 'episode', 400000, '2')",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (2, 500, 1700000700, 1700001800, 'rk-ep1', 101, 'alice', " +
            "'10.0.0.10', 20, 'Living Room TV', 'Plex for Apple TV', 'tvOS', 'atv-1', 'LAN', 'episode', 1350000, '2')",
    )
    // Single direct stream row (missing from users table -> fallback stub user 205)
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (3, NULL, 1700005000, 1700006800, 'rk-mov1', 205, 'charlie', " +
            "'203.0.113.5', 0, 'Chrome', 'Plex Web', 'Web', 'web-1', 'WAN', 'movie', 1750000, '1')",
    )
    // Row with stopped = 0 (falls back to started) and direct play
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (4, 600, 1700008000, 0, 'rk-mov2', 102, 'bob', " +
            "'10.0.0.11', 0, 'Shield', 'Plex for Android', 'Android', 'shield-1', 'LAN', 'movie', 500000, '1')",
    )
    // Malformed rows: missing rating_key, stopped < started, invalid started timestamp
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (5, 700, 1700009000, 1700009100, NULL, 102, 'bob', " +
            "NULL, 0, NULL, NULL, NULL, NULL, NULL, 'movie', 0, '1')",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (6, 701, 1700010000, 1700009000, 'rk-bad', 102, 'bob', " +
            "NULL, 0, NULL, NULL, NULL, NULL, NULL, 'movie', 0, '1')",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history VALUES (7, 702, 'not-a-timestamp', 1700010000, 'rk-bad2', 102, 'bob', " +
            "NULL, 0, NULL, NULL, NULL, NULL, NULL, 'movie', 0, '1')",
    )

    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(1, 'rk-ep1', 'Pilot', 'Show - Pilot', 'Season 1', 'Frieren', 1, 1, 2023, '/thumb/ep1', 1500000)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(2, 'rk-ep1', 'Pilot', 'Show - Pilot', 'Season 1', 'Frieren', 1, 1, 2023, '/thumb/ep1-v2', 1500000)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(3, 'rk-mov1', NULL, 'Spirited Away', NULL, NULL, NULL, NULL, 2001, '/thumb/mov1', 2000000)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(4, 'rk-mov2', 'Akira', 'Akira', NULL, NULL, NULL, NULL, 1988, NULL, 2000000)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(5, NULL, 'Missing Key', NULL, NULL, NULL, NULL, NULL, 2020, NULL, 1000000)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(6, 'rk-bad', 'Bad Times', NULL, NULL, NULL, NULL, NULL, 2020, NULL, 1000000)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_metadata VALUES " +
            "(7, 'rk-bad2', 'Bad Ts', NULL, NULL, NULL, NULL, NULL, 2020, NULL, 1000000)",
    )

    stmt.executeUpdate(
        "INSERT INTO session_history_media_info VALUES " +
            "(1, 'direct play', 'direct play', 'direct play', 0, 0, 'h264', 'aac', '1080', NULL, NULL, NULL, 8000, 7500, " +
            "'mp4', 'mp4', NULL, NULL, NULL)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_media_info VALUES " +
            "(2, 'transcode', 'transcode', 'copy', 1, 0, 'hevc', 'aac', '4k', '1080', 'h264', 'aac', 12000, 10000, " +
            "'mkv', 'mpegts', 'Burn', 'mp4', 'hevc')",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_media_info VALUES " +
            "(3, 'copy', 'copy', 'copy', 0, 0, 'h264', 'ac3', '1080', NULL, NULL, NULL, NULL, 6000, " +
            "'mkv', 'mp4', NULL, NULL, NULL)",
    )
    stmt.executeUpdate(
        "INSERT INTO session_history_media_info VALUES " +
            "(4, 'direct play', NULL, NULL, 0, 0, 'h264', 'dts', '1080', NULL, NULL, NULL, 5000, NULL, " +
            "'mkv', 'mkv', NULL, NULL, NULL)",
    )
}

private fun createPlexPyMinimalDb(path: Path): Path {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("CREATE TABLE users (user_id INTEGER, username TEXT)")
            stmt.executeUpdate(
                "CREATE TABLE session_history (id INTEGER PRIMARY KEY, started INTEGER, stopped INTEGER, " +
                    "rating_key TEXT, user_id INTEGER, user TEXT, media_type TEXT)",
            )
            stmt.executeUpdate(
                "CREATE TABLE session_history_metadata (id INTEGER PRIMARY KEY, title TEXT)",
            )
            stmt.executeUpdate(
                "CREATE TABLE session_history_media_info (id INTEGER PRIMARY KEY, video_decision TEXT)",
            )
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'plexpy_user')")
            stmt.executeUpdate(
                "INSERT INTO session_history VALUES (42, 1700000000, 1700000900, 'rk-pp', 1, 'plexpy_user', 'movie')",
            )
            stmt.executeUpdate("INSERT INTO session_history_metadata VALUES (42, 'Legacy Movie')")
            stmt.executeUpdate("INSERT INTO session_history_media_info VALUES (42, 'direct stream')")
        }
    }
    return path
}

class TautulliImporterTest :
    FunSpec({
        test("dry-run previews counts with zero DB mutations and real import merges grouped sessions idempotently") {
            createImporterHarness().use { harness ->
                val fixture = createRichTautulliFixture(harness.tempDir.resolve("tautulli.db"))

                val dryReport = harness.service.import(fixture, TautulliImportOptions(dryRun = true)).shouldBeRight()
                dryReport.dryRun shouldBe true
                dryReport.serverId shouldBe "dry-run-server"
                dryReport.usersDiscovered shouldBe 3
                dryReport.usersUpserted shouldBe 0
                dryReport.historyRowsRead shouldBe 7
                dryReport.historyImported shouldBe 3
                dryReport.historySkippedDuplicate shouldBe 0
                dryReport.malformedRows shouldBe 3
                dryReport.warnings shouldHaveSize 3

                harness.serverRepo.findAll().shouldBeRight().shouldBeEmpty()
                harness.userRepo.findAll().shouldBeRight().shouldBeEmpty()
                harness.historyRepo.query(HistoryFilter()).shouldBeRight().totalCount shouldBe 0L

                val realReport = harness.service.import(fixture, TautulliImportOptions(dryRun = false, batchSize = 2)).shouldBeRight()
                realReport.dryRun shouldBe false
                realReport.usersDiscovered shouldBe 3
                realReport.usersUpserted shouldBe 3
                realReport.historyRowsRead shouldBe 7
                realReport.historyImported shouldBe 3
                realReport.historySkippedDuplicate shouldBe 0
                realReport.malformedRows shouldBe 3

                val servers = harness.serverRepo.findAll().shouldBeRight()
                servers shouldHaveSize 1
                servers.first().machineId shouldBe "tautulli-import"
                realReport.serverId shouldBe servers.first().id

                val users = harness.userRepo.findAll().shouldBeRight()
                users shouldHaveSize 3
                val alice = users.first { it.externalUserId == "101" }
                alice.displayName shouldBe "Alice K"
                alice.isAdmin shouldBe true
                val charlie = users.first { it.externalUserId == "205" }
                charlie.username shouldBe "charlie"

                val historyPage = harness.historyRepo.query(HistoryFilter()).shouldBeRight()
                historyPage.totalCount shouldBe 3L

                val grouped = historyPage.items.first { it.externalImportKey == "tautulli:500" }
                grouped.startedAtMs shouldBe 1_700_000_000_000L
                grouped.stoppedAtMs shouldBe 1_700_001_800_000L
                grouped.pausedDurationSec shouldBe 50
                grouped.watchedDurationSec shouldBe 1_650
                grouped.viewOffsetMs shouldBe 1_350_000L
                grouped.media.durationMs shouldBe 1_500_000L
                grouped.completionPercent shouldBe 90.0
                grouped.isWatched shouldBe true
                grouped.streamTech.streamDecision shouldBe "transcode"
                grouped.streamTech.hwTranscode shouldBe true
                grouped.streamTech.bandwidthKbps shouldBe 12_000
                // Only the transcoded video stream gets a target codec; audio was copied.
                // transcode_* columns win over stream_* ones when both are present.
                grouped.streamTech.transcodeVideoCodec shouldBe "hevc"
                grouped.streamTech.transcodeAudioCodec shouldBe null
                grouped.streamTech.container shouldBe "mkv"
                grouped.streamTech.transcodeContainer shouldBe "mp4"
                grouped.streamTech.subtitleDecision shouldBe "burn"
                grouped.player.location shouldBe "lan"
                grouped.media.thumbPath shouldBe "/thumb/ep1-v2"

                val directStream = historyPage.items.first { it.externalImportKey == "tautulli:3" }
                directStream.streamTech.streamDecision shouldBe "direct_stream"
                directStream.streamTech.bandwidthKbps shouldBe 6_000
                directStream.streamTech.container shouldBe "mkv"
                directStream.streamTech.transcodeContainer shouldBe "mp4"
                directStream.streamTech.transcodeVideoCodec shouldBe null
                directStream.player.location shouldBe "wan"
                directStream.isWatched shouldBe true

                val postDryRun = harness.service.import(fixture, TautulliImportOptions(dryRun = true)).shouldBeRight()
                postDryRun.historyImported shouldBe 0
                postDryRun.historySkippedDuplicate shouldBe 3

                val secondReal = harness.service.import(fixture, TautulliImportOptions(dryRun = false)).shouldBeRight()
                secondReal.historyImported shouldBe 0
                secondReal.historySkippedDuplicate shouldBe 3
                harness.historyRepo.query(HistoryFilter()).shouldBeRight().totalCount shouldBe 3L
            }
        }

        test("handles minimal PlexPy schema without optional columns") {
            createImporterHarness().use { harness ->
                val legacyDb = createPlexPyMinimalDb(harness.tempDir.resolve("plexpy.db"))
                val report = harness.service.import(legacyDb).shouldBeRight()

                report.usersDiscovered shouldBe 1
                report.historyImported shouldBe 1
                report.malformedRows shouldBe 0

                val record = harness.historyRepo.query(HistoryFilter()).shouldBeRight().items.single()
                record.externalImportKey shouldBe "tautulli:42"
                record.streamTech.streamDecision shouldBe "direct_stream"
                record.media.title shouldBe "Legacy Movie"
            }
        }

        test("keeps transcode source codecs separate from output, ignores wildcard codecs, and merges grouped rows by conversion") {
            createImporterHarness().use { harness ->
                val dbFile = harness.tempDir.resolve("tautulli-conversion-edges.db")
                DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        createRichTautulliSchema(stmt)
                        stmt.executeUpdate(
                            "ALTER TABLE session_history_media_info ADD COLUMN transcode_audio_codec TEXT",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO users VALUES (101, 'alice', 'Alice', 'alice@example.com', NULL, 1)",
                        )
                        // Group ref=900: segment 1 transcoded with null source codecs & wildcard transcode_*,
                        // segment 2 resumed as direct play. Grouped record must keep the transcode segment.
                        stmt.executeUpdate(
                            "INSERT INTO session_history VALUES " +
                                "(1, 900, 1700000000, 1700000600, 'rk-900', 101, 'alice', '10.0.0.1', 0, " +
                                "'Chrome', 'Plex Web', 'Web', 'm1', 'lan', 'movie', 600000, '1')",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history VALUES " +
                                "(2, 900, 1700000700, 1700001200, 'rk-900', 101, 'alice', '10.0.0.1', 0, " +
                                "'Chrome', 'Plex Web', 'Web', 'm1', 'lan', 'movie', 1200000, '1')",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_metadata VALUES " +
                                "(1, 'rk-900', 'Edge Movie', 'Edge Movie', NULL, NULL, NULL, NULL, 2024, NULL, 1200000)",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_metadata VALUES " +
                                "(2, 'rk-900', 'Edge Movie', 'Edge Movie', NULL, NULL, NULL, NULL, 2024, NULL, 1200000)",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_media_info VALUES " +
                                "(1, 'transcode', 'transcode', 'transcode', 1, 0, NULL, NULL, '1080', '1080', 'h264', 'aac', " +
                                "8000, 7000, 'mkv', 'mp4', 'burn', '*', '*', '*')",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_media_info VALUES " +
                                "(2, 'direct play', 'direct play', 'direct play', 0, 0, 'hevc', 'truehd', '1080', NULL, NULL, NULL, " +
                                "8000, 7000, 'mkv', 'mkv', NULL, NULL, NULL, NULL)",
                        )
                    }
                }

                harness.service.import(dbFile).shouldBeRight().historyImported shouldBe 1
                val imported = harness.historyRepo.query(HistoryFilter()).shouldBeRight().items.single()
                imported.streamTech.streamDecision shouldBe "transcode"
                imported.streamTech.videoCodec shouldBe null
                imported.streamTech.audioCodec shouldBe null
                imported.streamTech.transcodeVideoCodec shouldBe "h264"
                imported.streamTech.transcodeAudioCodec shouldBe "aac"
                imported.streamTech.container shouldBe "mkv"
                imported.streamTech.transcodeContainer shouldBe "mp4"
                imported.streamTech.subtitleDecision shouldBe "burn"
            }
        }

        test("rejects missing file, non-SQLite file, missing Tautulli tables, and unknown serverId") {
            createImporterHarness().use { harness ->
                val missing = harness.tempDir.resolve("missing.db")
                harness.service.import(missing).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()

                val textFile = Files.writeString(harness.tempDir.resolve("not-sqlite.db"), "plain text content")
                harness.service.import(textFile).shouldBeLeft().message shouldContain "Invalid SQLite"

                val emptySqlite = harness.tempDir.resolve("empty.db")
                DriverManager.getConnection("jdbc:sqlite:${emptySqlite.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { it.executeUpdate("CREATE TABLE other (id INTEGER)") }
                }
                val tableErr = harness.service.import(emptySqlite).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
                tableErr.message shouldContain "Missing required Tautulli table"

                val validDb = createPlexPyMinimalDb(harness.tempDir.resolve("valid.db"))
                harness.service
                    .import(validDb, TautulliImportOptions(serverId = "does-not-exist"))
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.NotFound>()
            }
        }

        test("caps warnings at 50 items when many malformed rows exist") {
            createImporterHarness().use { harness ->
                val dbFile = harness.tempDir.resolve("many-malformed.db")
                DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("CREATE TABLE users (user_id INTEGER, username TEXT)")
                        stmt.executeUpdate(
                            "CREATE TABLE session_history (id INTEGER PRIMARY KEY, started INTEGER, stopped INTEGER, " +
                                "rating_key TEXT, user_id INTEGER)",
                        )
                        stmt.executeUpdate("CREATE TABLE session_history_metadata (id INTEGER PRIMARY KEY, title TEXT)")
                        stmt.executeUpdate("CREATE TABLE session_history_media_info (id INTEGER PRIMARY KEY, transcode_decision TEXT)")
                        for (i in 1..60) {
                            stmt.executeUpdate("INSERT INTO session_history VALUES ($i, 1700000000, 1700000100, NULL, 1)")
                            stmt.executeUpdate("INSERT INTO session_history_metadata VALUES ($i, 'Title $i')")
                            stmt.executeUpdate("INSERT INTO session_history_media_info VALUES ($i, 'direct play')")
                        }
                    }
                }
                val report = harness.service.import(dbFile).shouldBeRight()
                report.malformedRows shouldBe 60
                report.warnings shouldHaveSize MAX_TAUTULLI_IMPORT_WARNINGS
            }
        }

        test("AppConfigLoader.loadDatabaseAndSessionConfig succeeds without PLEX_TOKEN or AUTH_SESSION_SECRET") {
            val config =
                AppConfigLoader
                    .loadDatabaseAndSessionConfig(
                        env = mapOf("SQLITE_PATH" to "/tmp/miru-cli-test.db", "WATCHED_THRESHOLD_PERCENT" to "90"),
                        hocon = null,
                    ).shouldBeRight()
            config.database shouldBe DatabaseConfig.Sqlite("/tmp/miru-cli-test.db")
            config.session.watchedThresholdPercent shouldBe 90
        }

        test("TautulliCli runs dry-run and real import without PLEX_TOKEN and rejects invalid args") {
            val dir = Files.createTempDirectory("miru-cli-test-")
            try {
                val targetDbPath = dir.resolve("miru-cli.db").toString()
                val sourceDb = createRichTautulliFixture(dir.resolve("tautulli.db"))
                val cliEnv = mapOf("DB_ENGINE" to "sqlite", "SQLITE_PATH" to targetDbPath)

                val dryOut = ByteArrayOutputStream()
                val dryErr = ByteArrayOutputStream()
                val dryCode =
                    TautulliCli.run(
                        args = listOf("tautulli", "--file", sourceDb.toString(), "--dry-run"),
                        out = PrintStream(dryOut, true, StandardCharsets.UTF_8),
                        err = PrintStream(dryErr, true, StandardCharsets.UTF_8),
                        env = cliEnv,
                        hocon = null,
                    )
                dryCode shouldBe 0
                dryOut.toString(StandardCharsets.UTF_8) shouldContain "Tautulli DRY-RUN complete"

                // Seed an explicit server and target it via --server-id=
                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(targetDbPath)).shouldBeRight().use { handle ->
                    kotlinx.coroutines.runBlocking {
                        ExposedMediaServerRepository(handle)
                            .upsert(
                                MediaServerRecord(
                                    id = "srv-cli",
                                    name = "CLI Plex",
                                    serverType = "plex",
                                    machineId = "mach-cli",
                                    baseUrl = "http://plex.local:32400",
                                    createdAtMs = 1_000L,
                                    updatedAtMs = 1_000L,
                                ),
                            ).shouldBeRight()
                    }
                }

                val realOut = ByteArrayOutputStream()
                val realErr = ByteArrayOutputStream()
                val realCode =
                    TautulliCli.run(
                        args = listOf("tautulli", "-f", sourceDb.toString(), "--server-id=srv-cli"),
                        out = PrintStream(realOut, true, StandardCharsets.UTF_8),
                        err = PrintStream(realErr, true, StandardCharsets.UTF_8),
                        env = cliEnv,
                        hocon = null,
                    )
                realCode shouldBe 0
                val realText = realOut.toString(StandardCharsets.UTF_8)
                realText shouldContain "serverId:                srv-cli"
                realText shouldContain "historyImported:         3"

                DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(targetDbPath)).shouldBeRight().use { handle ->
                    kotlinx.coroutines.runBlocking {
                        ExposedPlaybackHistoryRepository(handle)
                            .query(HistoryFilter(serverId = "srv-cli"))
                            .shouldBeRight()
                            .totalCount shouldBe 3L
                    }
                }

                val badOut = ByteArrayOutputStream()
                val badErr = ByteArrayOutputStream()
                TautulliCli.run(
                    args = listOf("tautulli", "--unknown-flag"),
                    out = PrintStream(badOut, true, StandardCharsets.UTF_8),
                    err = PrintStream(badErr, true, StandardCharsets.UTF_8),
                    env = cliEnv,
                    hocon = null,
                ) shouldBe 1
                badErr.toString(StandardCharsets.UTF_8) shouldContain "Unknown option"

                TautulliCli.run(
                    args = listOf("other"),
                    out = PrintStream(badOut, true, StandardCharsets.UTF_8),
                    err = PrintStream(badErr, true, StandardCharsets.UTF_8),
                    env = cliEnv,
                    hocon = null,
                ) shouldBe 1

                val missingFileErr = ByteArrayOutputStream()
                TautulliCli.run(
                    args = listOf("tautulli"),
                    out = PrintStream(badOut, true, StandardCharsets.UTF_8),
                    err = PrintStream(missingFileErr, true, StandardCharsets.UTF_8),
                    env = cliEnv,
                    hocon = null,
                ) shouldBe 1
                missingFileErr.toString(StandardCharsets.UTF_8).shouldNotBeNull()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("excludes multi-day idle gaps when merging reference_id chunks, keeps millisecond units, and skips unused Local user") {
            createImporterHarness().use { harness ->
                val dbFile = harness.tempDir.resolve("multiday-gap.db")
                DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        createRichTautulliSchema(stmt)
                        // Unused Tautulli dummy user_id=0 ("Local") should be skipped; active user kept
                        stmt.executeUpdate("INSERT INTO users VALUES (0, 'Local', 'Local', NULL, NULL, 0)")
                        stmt.executeUpdate("INSERT INTO users VALUES (18475316, 'Vehkiya', 'Vehkiya', 'v@example.com', NULL, 1)")

                        // Same reference_id resumed 13 days later: two 600s chunks with 30s paused each => 1140s watched
                        stmt.executeUpdate(
                            "INSERT INTO session_history VALUES (1, 1440, 1792952215, 1792952815, 'rk-abbott', 18475316, " +
                                "'Vehkiya', '10.0.0.10', 30, 'Apple TV', 'Plex', 'tvOS', 'atv-1', 'LAN', 'episode', 645024, '2')",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history VALUES (2, 1440, 1794065842, 1794066442, 'rk-abbott', 18475316, " +
                                "'Vehkiya', '10.0.0.10', 30, 'Apple TV', 'Plex', 'tvOS', 'atv-1', 'LAN', 'episode', 1290048, '2')",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_metadata VALUES (1, 'rk-abbott', 'Check-Ins', " +
                                "'Abbott Elementary - Check-Ins', 'Season 4', 'Abbott Elementary', 4, 1, 2024, " +
                                "'/thumb/1', 1290048)",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_metadata VALUES (2, 'rk-abbott', 'Check-Ins', " +
                                "'Abbott Elementary - Check-Ins', 'Season 4', 'Abbott Elementary', 4, 1, 2024, " +
                                "'/thumb/1', 1290048)",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_media_info VALUES " +
                                "(1, 'direct play', NULL, NULL, 0, 0, 'h264', 'aac', '1080', NULL, NULL, NULL, 8000, 8000, " +
                                "NULL, NULL, NULL, NULL, NULL)",
                        )
                        stmt.executeUpdate(
                            "INSERT INTO session_history_media_info VALUES " +
                                "(2, 'direct play', NULL, NULL, 0, 0, 'h264', 'aac', '1080', NULL, NULL, NULL, 8000, 8000, " +
                                "NULL, NULL, NULL, NULL, NULL)",
                        )
                    }
                }

                val report = harness.service.import(dbFile).shouldBeRight()
                report.usersDiscovered shouldBe 1
                report.usersUpserted shouldBe 1
                report.historyImported shouldBe 1

                val users = harness.userRepo.findAll().shouldBeRight()
                users.map { it.username } shouldBe listOf("Vehkiya")

                val item = harness.historyRepo.query(HistoryFilter()).shouldBeRight().items.single()
                item.watchedDurationSec shouldBe 1_140
                item.pausedDurationSec shouldBe 60
                item.media.durationMs shouldBe 1_290_048L
                item.viewOffsetMs shouldBe 1_290_048L
                item.completionPercent shouldBe 100.0
                item.isWatched shouldBe true
            }
        }

        test("streams interleaved non-contiguous reference_id groups accurately across multiple small batches") {
            createImporterHarness().use { harness ->
                val dbFile = harness.tempDir.resolve("interleaved-stream.db")
                DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        createRichTautulliSchema(stmt)
                        stmt.executeUpdate("INSERT INTO users VALUES (101, 'alice', 'Alice', NULL, NULL, 1)")
                        // Interleaved rows in session_history:
                        // id=1 (ref=10), id=2 (ref=20), id=3 (ref=10 resumed), id=4 (ref=NULL -> id 4), id=5 (ref=20 resumed)
                        val rows =
                            listOf(
                                Triple(1, "10", 1_700_000_000L to 1_700_000_300L),
                                Triple(2, "20", 1_700_001_000L to 1_700_001_200L),
                                Triple(3, "10", 1_700_000_400L to 1_700_000_900L),
                                Triple(4, "NULL", 1_700_002_000L to 1_700_002_600L),
                                Triple(5, "20", 1_700_001_300L to 1_700_001_700L),
                            )
                        for ((id, refExpr, times) in rows) {
                            stmt.executeUpdate(
                                "INSERT INTO session_history VALUES ($id, $refExpr, ${times.first}, ${times.second}, " +
                                    "'rk-$refExpr', 101, 'alice', '10.0.0.1', 0, 'TV', 'Plex', 'tvOS', 'm1', 'lan', 'movie', 600000, '1')",
                            )
                            stmt.executeUpdate(
                                "INSERT INTO session_history_metadata VALUES ($id, 'rk-$refExpr', 'Title $id', 'Title $id', " +
                                    "NULL, NULL, NULL, NULL, 2024, NULL, 1000000)",
                            )
                            stmt.executeUpdate(
                                "INSERT INTO session_history_media_info VALUES ($id, 'direct play', 'direct play', 'direct play', " +
                                    "0, 0, 'h264', 'aac', '1080', NULL, NULL, NULL, 8000, 8000, 'mkv', 'mkv', NULL, NULL, NULL)",
                            )
                        }
                    }
                }

                val report = harness.service.import(dbFile, TautulliImportOptions(batchSize = 2)).shouldBeRight()
                report.historyRowsRead shouldBe 5
                report.historyImported shouldBe 3
                report.malformedRows shouldBe 0

                val items = harness.historyRepo.query(HistoryFilter()).shouldBeRight().items.associateBy { it.externalImportKey }
                items.keys shouldBe setOf("tautulli:4", "tautulli:10", "tautulli:20")
                items.getValue("tautulli:10").watchedDurationSec shouldBe 800
                items.getValue("tautulli:10").media.title shouldBe "Title 3"
                items.getValue("tautulli:20").watchedDurationSec shouldBe 600
                items.getValue("tautulli:20").media.title shouldBe "Title 5"
                items.getValue("tautulli:4").watchedDurationSec shouldBe 600
            }
        }
    })
