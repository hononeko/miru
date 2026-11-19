package app.hononeko.miru.config

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppConfigLoaderTest {
    private val validSecret = "0123456789abcdef0123456789abcdef"

    private fun baseSqliteEnv(overrides: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "PLEX_URL" to "http://plex.local:32400/",
            "PLEX_TOKEN" to "super-secret-plex-token",
            "AUTH_SESSION_SECRET" to validSecret,
        ) + overrides

    @Test
    fun `loads valid sqlite configuration with defaults`() {
        val result = AppConfigLoader.load(env = baseSqliteEnv())

        val config = assertInstanceOf(Either.Right::class.java, result).value as AppConfig
        assertEquals("0.0.0.0", config.server.host)
        assertEquals(8080, config.server.port)
        assertEquals(DatabaseConfig.Sqlite("./data/miru.db"), config.database)
        assertEquals("http://plex.local:32400", config.plex.baseUrl)
        assertEquals(20, config.plex.pollIntervalSeconds)
        assertTrue(config.plex.websocketEnabled)
        assertEquals(120, config.session.minPlayDurationSeconds)
        assertEquals(120, config.session.resumeMergeWindowSeconds)
        assertEquals(85, config.session.watchedThresholdPercent)
        assertEquals(AuthMode.LOCAL, config.auth.mode)
    }

    @Test
    fun `loads valid postgres configuration and redacts sensitive fields in toString`() {
        val env =
            baseSqliteEnv(
                mapOf(
                    "DB_ENGINE" to "postgres",
                    "DATABASE_URL" to "jdbc:postgresql://10.10.1.218:5432/miru",
                    "DATABASE_USER" to "miru_app",
                    "DATABASE_PASSWORD" to "db-super-secret",
                    "DATABASE_MAX_POOL_SIZE" to "15",
                    "MIRU_ADMIN_USERNAME" to "admin",
                    "MIRU_ADMIN_PASSWORD" to "very-strong-password-123",
                    "MIRU_CORS_ALLOWED_ORIGINS" to "http://localhost:5173, https://miru.hononeko.app",
                ),
            )

        val result = AppConfigLoader.load(env = env)

        val config = assertInstanceOf(Either.Right::class.java, result).value as AppConfig
        val pg = assertInstanceOf(DatabaseConfig.Postgres::class.java, config.database)
        assertEquals("jdbc:postgresql://10.10.1.218:5432/miru", pg.url)
        assertEquals(15, pg.maxPoolSize)
        assertEquals(listOf("http://localhost:5173", "https://miru.hononeko.app"), config.server.corsAllowedOrigins)

        val rendered = config.toString()
        assertFalse(rendered.contains("super-secret-plex-token"))
        assertFalse(rendered.contains("db-super-secret"))
        assertFalse(rendered.contains(validSecret))
        assertFalse(rendered.contains("very-strong-password-123"))
    }

    @Test
    fun `falls back to HOCON values when environment variables are absent`() {
        val hocon =
            MapApplicationConfig(
                "miru.server.port" to "9090",
                "miru.plex.baseUrl" to "https://plex.example.com",
                "miru.plex.token" to "hocon-token",
                "miru.auth.mode" to "none",
            )

        val result = AppConfigLoader.load(env = emptyMap(), hocon = hocon)

        val config = assertInstanceOf(Either.Right::class.java, result).value as AppConfig
        assertEquals(9090, config.server.port)
        assertEquals("https://plex.example.com", config.plex.baseUrl)
        assertEquals(AuthMode.NONE, config.auth.mode)
    }

    @Test
    fun `accumulates multiple startup validation errors in one ValidationError`() {
        val env =
            mapOf(
                "MIRU_PORT" to "99999",
                "DB_ENGINE" to "postgres",
                "DATABASE_URL" to "mysql://wrong",
                "DATABASE_MAX_POOL_SIZE" to "0",
                "PLEX_URL" to "ftp://not-http",
                "PLEX_TOKEN" to "   ",
                "PLEX_POLL_INTERVAL_SECONDS" to "2",
                "PLEX_WEBSOCKET_ENABLED" to "maybe",
                "MIN_PLAY_DURATION_SECONDS" to "-1",
                "RESUME_MERGE_WINDOW_SECONDS" to "-5",
                "WATCHED_THRESHOLD_PERCENT" to "101",
                "AUTH_MODE" to "local",
                "AUTH_SESSION_SECRET" to "too-short",
                "MIRU_ADMIN_USERNAME" to "admin",
            )

        val result = AppConfigLoader.load(env = env)

        val error = assertInstanceOf(Either.Left::class.java, result).value as MiruError.ValidationError
        val expectedKeys =
            setOf(
                "MIRU_PORT",
                "DATABASE_URL",
                "DATABASE_USER",
                "DATABASE_PASSWORD",
                "DATABASE_MAX_POOL_SIZE",
                "PLEX_URL",
                "PLEX_TOKEN",
                "PLEX_POLL_INTERVAL_SECONDS",
                "PLEX_WEBSOCKET_ENABLED",
                "MIN_PLAY_DURATION_SECONDS",
                "RESUME_MERGE_WINDOW_SECONDS",
                "WATCHED_THRESHOLD_PERCENT",
                "AUTH_SESSION_SECRET",
                "MIRU_ADMIN_PASSWORD",
            )
        assertEquals(expectedKeys, error.fieldErrors.keys)
    }

    @Test
    fun `rejects unknown database engine and auth mode`() {
        val env =
            baseSqliteEnv(
                mapOf(
                    "DB_ENGINE" to "oracle",
                    "AUTH_MODE" to "kerberos",
                ),
            )

        val result = AppConfigLoader.load(env = env)

        val error = assertInstanceOf(Either.Left::class.java, result).value as MiruError.ValidationError
        assertTrue(error.fieldErrors.containsKey("DB_ENGINE"))
        assertTrue(error.fieldErrors.containsKey("AUTH_MODE"))
    }

    @Test
    fun `rejects weak bootstrap admin password`() {
        val env =
            baseSqliteEnv(
                mapOf(
                    "MIRU_ADMIN_USERNAME" to "admin",
                    "MIRU_ADMIN_PASSWORD" to "short",
                ),
            )

        val result = AppConfigLoader.load(env = env)

        val error = assertInstanceOf(Either.Left::class.java, result).value as MiruError.ValidationError
        assertEquals(setOf("MIRU_ADMIN_PASSWORD"), error.fieldErrors.keys)
    }
}
