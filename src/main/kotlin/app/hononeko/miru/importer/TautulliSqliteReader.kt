package app.hononeko.miru.importer

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteOpenMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

private val SQLITE_MAGIC_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
private val REQUIRED_TABLES =
    listOf(
        "users",
        "session_history",
        "session_history_metadata",
        "session_history_media_info",
    )

/**
 * Reads users and joined playback session history from a Tautulli / PlexPy SQLite database in read-only mode.
 */
object TautulliSqliteReader {
    internal fun read(file: Path): Either<MiruError.ValidationError, TautulliReadResult> =
        either {
            validateSqliteFile(file).bind()

            val config =
                SQLiteConfig().apply {
                    setReadOnly(true)
                    setOpenMode(SQLiteOpenMode.READONLY)
                }

            try {
                DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}", config.toProperties()).use { conn ->
                    val tableColumns = inspectRequiredTables(conn).bind()
                    val warnings = mutableListOf<String>()
                    var malformedRows = 0

                    val (users, userMalformed) = readUsers(conn, tableColumns.getValue("users"), warnings)
                    malformedRows += userMalformed

                    val historyOutcome = readHistory(conn, tableColumns, warnings)
                    malformedRows += historyOutcome.malformedRows

                    TautulliReadResult(
                        users = users,
                        history = historyOutcome.rows,
                        historyRowsRead = historyOutcome.totalRowsRead,
                        malformedRows = malformedRows,
                        warnings = warnings.toList(),
                    )
                }
            } catch (ex: SQLException) {
                raise(
                    MiruError.ValidationError(
                        message = "Failed to read Tautulli SQLite database: ${ex.message ?: "SQLite error"}",
                    ),
                )
            }
        }

    private fun validateSqliteFile(file: Path): Either<MiruError.ValidationError, Unit> =
        either {
            ensure(Files.exists(file) && Files.isRegularFile(file) && Files.isReadable(file)) {
                MiruError.ValidationError("Invalid SQLite database file")
            }
            val header =
                try {
                    Files.newInputStream(file).use { input ->
                        input.readNBytes(SQLITE_MAGIC_HEADER.size)
                    }
                } catch (_: Exception) {
                    raise(MiruError.ValidationError("Invalid SQLite database file"))
                }
            ensure(header.contentEquals(SQLITE_MAGIC_HEADER)) {
                MiruError.ValidationError("Invalid SQLite database file")
            }
        }

    private fun inspectRequiredTables(conn: Connection): Either<MiruError.ValidationError, Map<String, Set<String>>> =
        either {
            val presentTables = linkedSetOf<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                    while (rs.next()) {
                        rs.getString(1)?.lowercase()?.let { presentTables.add(it) }
                    }
                }
            }
            val missingTables = REQUIRED_TABLES.filterNot { it in presentTables }
            ensure(missingTables.isEmpty()) {
                MiruError.ValidationError(
                    message = "Missing required Tautulli table(s): ${missingTables.joinToString(", ")}",
                )
            }

            REQUIRED_TABLES.associateWith { tableName ->
                readTableColumns(conn, tableName)
            }
        }

    private fun readTableColumns(
        conn: Connection,
        tableName: String,
    ): Set<String> {
        val columns = linkedSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                while (rs.next()) {
                    rs.getString("name")?.lowercase()?.let { columns.add(it) }
                }
            }
        }
        return columns
    }

    private fun readUsers(
        conn: Connection,
        userCols: Set<String>,
        warnings: MutableList<String>,
    ): Pair<List<TautulliUserRow>, Int> {
        val selectColumns =
            listOf("user_id", "username", "friendly_name", "email", "thumb", "is_admin")
                .joinToString(", ") { col ->
                    if (col in userCols) col else "NULL AS $col"
                }
        val sql = "SELECT $selectColumns FROM users"
        val users = mutableListOf<TautulliUserRow>()
        var malformed = 0

        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                var rowNum = 0
                while (rs.next()) {
                    rowNum++
                    try {
                        users +=
                            TautulliUserRow(
                                userId = rs.readOptionalString("user_id"),
                                username = rs.readOptionalString("username"),
                                friendlyName = rs.readOptionalString("friendly_name"),
                                email = rs.readOptionalString("email"),
                                thumb = rs.readOptionalString("thumb"),
                                isAdmin = rs.readOptionalBooleanFlag("is_admin"),
                            )
                    } catch (ex: Exception) {
                        malformed++
                        warnings.addBoundedWarning("Malformed Tautulli user row #$rowNum: ${ex.message ?: "parse error"}")
                    }
                }
            }
        }
        return users to malformed
    }

    private data class HistoryReadOutcome(
        val rows: List<TautulliHistoryRow>,
        val totalRowsRead: Int,
        val malformedRows: Int,
    )

    private fun readHistory(
        conn: Connection,
        tableColumns: Map<String, Set<String>>,
        warnings: MutableList<String>,
    ): HistoryReadOutcome {
        val shCols = tableColumns.getValue("session_history")
        val shmCols = tableColumns.getValue("session_history_metadata")
        val shmiCols = tableColumns.getValue("session_history_media_info")
        val sources =
            listOf(
                "sh" to shCols,
                "shm" to shmCols,
                "shmi" to shmiCols,
            )

        val selectList = buildHistorySelectList(sources)
        val joinMetadata =
            if ("id" in shCols && "id" in shmCols) {
                "LEFT JOIN session_history_metadata shm ON shm.id = sh.id"
            } else {
                "LEFT JOIN session_history_metadata shm ON 1 = 0"
            }
        val joinMediaInfo =
            if ("id" in shCols && "id" in shmiCols) {
                "LEFT JOIN session_history_media_info shmi ON shmi.id = sh.id"
            } else {
                "LEFT JOIN session_history_media_info shmi ON 1 = 0"
            }
        val orderClause = if ("id" in shCols) "ORDER BY sh.id ASC" else ""
        val sql =
            """
            SELECT $selectList
            FROM session_history sh
            $joinMetadata
            $joinMediaInfo
            $orderClause
            """.trimIndent()

        val rows = mutableListOf<TautulliHistoryRow>()
        var totalRead = 0
        var malformed = 0

        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    totalRead++
                    try {
                        rows += parseHistoryRow(rs)
                    } catch (ex: Exception) {
                        malformed++
                        val idHint = runCatching { rs.readOptionalString("id") }.getOrNull() ?: "#$totalRead"
                        warnings.addBoundedWarning("Malformed Tautulli history row $idHint: ${ex.message ?: "parse error"}")
                    }
                }
            }
        }
        return HistoryReadOutcome(rows = rows, totalRowsRead = totalRead, malformedRows = malformed)
    }

    private fun buildHistorySelectList(sources: List<Pair<String, Set<String>>>): String {
        val sh = sources[0]
        val shm = sources[1]
        val shmi = sources[2]
        val expressions =
            listOf(
                selectExpr("id", listOf(sh)),
                selectExpr("reference_id", listOf(sh)),
                selectExpr("started", listOf(sh)),
                selectExpr("stopped", listOf(sh)),
                selectExpr("paused_counter", listOf(sh)),
                selectExpr("view_offset", listOf(sh, shm)),
                selectExpr("user_id", listOf(sh)),
                selectExpr("user", listOf(sh)),
                selectExpr("rating_key", listOf(sh, shm)),
                selectExpr("media_type", listOf(sh, shm)),
                selectExpr("section_id", listOf(sh, shm)),
                selectExpr("player", listOf(sh)),
                selectExpr("product", listOf(sh)),
                selectExpr("platform", listOf(sh)),
                selectExpr("machine_id", listOf(sh)),
                selectExpr("ip_address", listOf(sh)),
                selectExpr("location", listOf(sh, shmi)),
                selectExpr("title", listOf(shm)),
                selectExpr("parent_title", listOf(shm)),
                selectExpr("grandparent_title", listOf(shm)),
                selectExpr("parent_media_index", listOf(shm)),
                selectExpr("media_index", listOf(shm)),
                selectExpr("full_title", listOf(shm)),
                selectExpr("year", listOf(shm)),
                selectExpr("thumb", listOf(shm)),
                selectExpr("duration", listOf(shm, sh)),
                selectExpr("transcode_decision", listOf(shmi)),
                selectExpr("video_decision", listOf(shmi)),
                selectExpr("audio_decision", listOf(shmi)),
                selectExpr("transcode_hw_decoding", listOf(shmi)),
                selectExpr("transcode_hw_encoding", listOf(shmi)),
                selectExpr("video_codec", listOf(shmi)),
                selectExpr("audio_codec", listOf(shmi)),
                selectExpr("video_resolution", listOf(shmi)),
                selectExpr("stream_video_resolution", listOf(shmi)),
                selectExpr("stream_video_codec", listOf(shmi)),
                selectExpr("stream_audio_codec", listOf(shmi)),
                selectExpr("container", listOf(shmi)),
                selectExpr("stream_container", listOf(shmi)),
                selectExpr("transcode_container", listOf(shmi)),
                selectExpr("transcode_video_codec", listOf(shmi)),
                selectExpr("transcode_audio_codec", listOf(shmi)),
                selectExpr("stream_subtitle_decision", listOf(shmi)),
                selectExpr("bandwidth", listOf(shmi)),
                selectExpr("bitrate", listOf(shmi)),
            )
        return expressions.joinToString(", ")
    }

    private fun selectExpr(
        column: String,
        candidates: List<Pair<String, Set<String>>>,
    ): String {
        val matching = candidates.filter { (_, cols) -> column in cols }.map { (alias, _) -> "$alias.$column" }
        return when (matching.size) {
            0 -> "NULL AS $column"
            1 -> "${matching.first()} AS $column"
            else -> "COALESCE(${matching.joinToString(", ")}) AS $column"
        }
    }

    private fun parseHistoryRow(rs: ResultSet): TautulliHistoryRow {
        val id = rs.readRequiredLong("id")
        return TautulliHistoryRow(
            id = id,
            referenceId = rs.readOptionalLenientLong("reference_id"),
            started = rs.readOptionalStrictLong("started"),
            stopped = rs.readOptionalStrictLong("stopped"),
            pausedCounter = rs.readOptionalLenientLong("paused_counter"),
            viewOffset = rs.readOptionalLenientLong("view_offset"),
            userId = rs.readOptionalString("user_id"),
            user = rs.readOptionalString("user"),
            ratingKey = rs.readOptionalString("rating_key"),
            mediaType = rs.readOptionalString("media_type"),
            sectionId = rs.readOptionalString("section_id"),
            player = rs.readOptionalString("player"),
            product = rs.readOptionalString("product"),
            platform = rs.readOptionalString("platform"),
            machineId = rs.readOptionalString("machine_id"),
            ipAddress = rs.readOptionalString("ip_address"),
            location = rs.readOptionalString("location"),
            title = rs.readOptionalString("title"),
            parentTitle = rs.readOptionalString("parent_title"),
            grandparentTitle = rs.readOptionalString("grandparent_title"),
            parentMediaIndex = rs.readOptionalLenientInt("parent_media_index"),
            mediaIndex = rs.readOptionalLenientInt("media_index"),
            fullTitle = rs.readOptionalString("full_title"),
            year = rs.readOptionalLenientInt("year"),
            thumb = rs.readOptionalString("thumb"),
            duration = rs.readOptionalLenientLong("duration"),
            transcodeDecision = rs.readOptionalString("transcode_decision"),
            videoDecision = rs.readOptionalString("video_decision"),
            audioDecision = rs.readOptionalString("audio_decision"),
            transcodeHwDecoding = rs.readOptionalLenientInt("transcode_hw_decoding"),
            transcodeHwEncoding = rs.readOptionalLenientInt("transcode_hw_encoding"),
            videoCodec = rs.readOptionalString("video_codec"),
            audioCodec = rs.readOptionalString("audio_codec"),
            videoResolution = rs.readOptionalString("video_resolution"),
            streamVideoResolution = rs.readOptionalString("stream_video_resolution"),
            streamVideoCodec = rs.readOptionalString("stream_video_codec"),
            streamAudioCodec = rs.readOptionalString("stream_audio_codec"),
            container = rs.readOptionalString("container"),
            streamContainer = rs.readOptionalString("stream_container"),
            transcodeContainer = rs.readOptionalString("transcode_container"),
            transcodeVideoCodec = rs.readOptionalString("transcode_video_codec"),
            transcodeAudioCodec = rs.readOptionalString("transcode_audio_codec"),
            streamSubtitleDecision = rs.readOptionalString("stream_subtitle_decision"),
            bandwidth = rs.readOptionalLenientInt("bandwidth"),
            bitrate = rs.readOptionalLenientInt("bitrate"),
        )
    }

    private fun ResultSet.readOptionalString(column: String): String? {
        val raw = getObject(column) ?: return null
        return raw.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun ResultSet.readRequiredLong(column: String): Long {
        val raw = getObject(column) ?: error("Missing required '$column'")
        return parseNumericLong(raw, column) ?: error("Invalid integer '$column'")
    }

    private fun ResultSet.readOptionalStrictLong(column: String): Long? {
        val raw = getObject(column) ?: return null
        if (raw is String && raw.isBlank()) return null
        return parseNumericLong(raw, column) ?: error("Invalid timestamp '$column'")
    }

    private fun ResultSet.readOptionalLenientLong(column: String): Long? {
        val raw = getObject(column) ?: return null
        if (raw is String && raw.isBlank()) return null
        return parseNumericLong(raw, column)
    }

    private fun ResultSet.readOptionalLenientInt(column: String): Int? =
        readOptionalLenientLong(column)?.toInt()

    private fun ResultSet.readOptionalBooleanFlag(column: String): Boolean {
        val raw = getObject(column) ?: return false
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
            else -> false
        }
    }

    private fun parseNumericLong(
        raw: Any,
        column: String,
    ): Long? =
        when (raw) {
            is Number -> raw.toLong()
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    null
                } else {
                    trimmed.toLongOrNull() ?: trimmed.toBigDecimalOrNull()?.toLong()
                }
            }
            else -> error("Unsupported type for '$column': ${raw::class.simpleName}")
        }
}
