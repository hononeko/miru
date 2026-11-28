package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.DriverManager

private data class MigrationTarget(
    val url: String,
    val user: String?,
    val password: String?,
    val location: String,
)

/** The key columns of [indexName] in order, each with its direction, as the database catalog describes them. */
internal fun indexColumns(
    handle: DatabaseHandle,
    indexName: String,
): List<String> {
    val sql =
        when (handle.dialect) {
            DatabaseDialect.SQLITE ->
                "SELECT name, \"desc\" = 1 FROM pragma_index_xinfo(?) WHERE key = 1 ORDER BY seqno"
            DatabaseDialect.POSTGRES ->
                """
                SELECT pg_get_indexdef(c.oid, k.n, true), pg_index_column_has_property(c.oid, k.n, 'desc')
                FROM pg_class c
                JOIN pg_index i ON i.indexrelid = c.oid
                CROSS JOIN generate_series(1, i.indnkeyatts) AS k(n)
                WHERE c.relname = ?
                ORDER BY k.n
                """.trimIndent()
        }
    return handle.dataSource.connection.use { conn ->
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, indexName)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString(1) + if (rs.getBoolean(2)) " DESC" else " ASC")
                }
            }
        }
    }
}

/**
 * Migrates an empty database to V15, stores plays and library items as an existing install has them, and runs the
 * remaining migrations. V16 and V17 must index those rows, and the reads they serve must still find them.
 */
internal suspend fun assertQueryIndexesBuildOverExistingRows(config: DatabaseConfig) {
    val target =
        when (config) {
            is DatabaseConfig.Sqlite -> MigrationTarget("jdbc:sqlite:${config.path}", null, null, "classpath:db/migration/sqlite")
            is DatabaseConfig.Postgres -> MigrationTarget(config.url, config.user, config.password, "classpath:db/migration/postgres")
        }
    Flyway
        .configure()
        .dataSource(target.url, target.user, target.password)
        .locations(target.location)
        .target("15")
        .load()
        .migrate()

    DriverManager.getConnection(target.url, target.user, target.password).use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO media_servers (id, name, server_type, machine_id, base_url, created_at_ms, updated_at_ms) " +
                    "VALUES ('srv-1', 'Plex', 'PLEX', 'mach-1', 'http://plex.local:32400', 1, 1)",
            )
            stmt.executeUpdate(
                "INSERT INTO users (id, server_id, external_user_id, username, created_at_ms, updated_at_ms) " +
                    "VALUES ('u-1', 'srv-1', 'ext-1', 'alice', 1, 1)",
            )
            stmt.executeUpdate(
                "INSERT INTO media_items_cache (server_id, rating_key, section_key, media_type, title, file_size_bytes, " +
                    "added_at_ms, updated_at_ms) VALUES ('srv-1', 'rk-1', 'sec-1', 'movie', 'Movie', 1000, 1, 1)",
            )
            for ((id, stoppedAtMs) in listOf("h-1" to 2_000L, "h-2" to 3_000L)) {
                stmt.executeUpdate(
                    "INSERT INTO playback_history (id, server_id, user_id, rating_key, media_type, title, library_section_key, " +
                        "duration_ms, view_offset_ms, watched_duration_sec, completion_percent, stream_decision, " +
                        "started_at_ms, stopped_at_ms) VALUES ('$id', 'srv-1', 'u-1', 'rk-1', 'movie', 'Movie', 'sec-1', " +
                        "100000, 50000, 50, 50.0, 'direct_play', ${stoppedAtMs - 1_000L}, $stoppedAtMs)",
                )
            }
        }
    }

    val handle = DatabaseFactory.createAndMigrate(config).shouldBeRight()
    try {
        indexColumns(handle, "idx_playback_history_stopped_at") shouldContainExactly listOf("stopped_at_ms DESC", "id ASC")
        indexColumns(handle, "idx_media_items_cache_rating_key") shouldContainExactly
            listOf("rating_key ASC", "server_id ASC", "section_key ASC")
        indexColumns(handle, "idx_playback_history_server_rating_stopped") shouldContainExactly
            listOf("server_id ASC", "rating_key ASC", "stopped_at_ms DESC")

        val library = ExposedLibraryRepository(handle)
        library.findSectionKeysByRatingKey("rk-1").shouldBeRight() shouldBe setOf("srv-1" to "sec-1")
        val stale = library.findStaleMedia("srv-1", "sec-1", unwatchedCutoffMs = 5_000L).shouldBeRight()
        stale.items.map { Triple(it.item.ratingKey, it.playCount, it.lastPlayedAtMs) } shouldContainExactly
            listOf(Triple("rk-1", 2L, 3_000L))
        ExposedPlaybackHistoryRepository(handle)
            .query(HistoryFilter())
            .shouldBeRight()
            .items
            .map { it.id } shouldContainExactly listOf("h-2", "h-1")
    } finally {
        handle.close()
    }
}
