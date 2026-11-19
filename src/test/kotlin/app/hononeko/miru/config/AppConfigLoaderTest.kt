package app.hononeko.miru.config

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.config.MapApplicationConfig

class AppConfigLoaderTest :
    FunSpec({
        val validSecret = "0123456789abcdef0123456789abcdef"

        fun baseSqliteEnv(overrides: Map<String, String> = emptyMap()): Map<String, String> =
            mapOf(
                "PLEX_URL" to "http://plex.local:32400/",
                "PLEX_TOKEN" to "super-secret-plex-token",
                "AUTH_SESSION_SECRET" to validSecret,
            ) + overrides

        test("loads valid sqlite configuration with defaults") {
            val config = AppConfigLoader.load(env = baseSqliteEnv()).shouldBeRight()

            config.server.host shouldBe "0.0.0.0"
            config.server.port shouldBe 8080
            config.database shouldBe DatabaseConfig.Sqlite("./data/miru.db")
            config.plex.baseUrl shouldBe "http://plex.local:32400"
            config.plex.pollIntervalSeconds shouldBe 20
            config.plex.websocketEnabled shouldBe true
            config.session.minPlayDurationSeconds shouldBe 120
            config.session.resumeMergeWindowSeconds shouldBe 120
            config.session.watchedThresholdPercent shouldBe 85
            config.auth.mode shouldBe AuthMode.LOCAL
        }

        test("loads valid postgres configuration and redacts sensitive fields in toString") {
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

            val config = AppConfigLoader.load(env = env).shouldBeRight()
            val pg = config.database.shouldBeInstanceOf<DatabaseConfig.Postgres>()
            pg.url shouldBe "jdbc:postgresql://10.10.1.218:5432/miru"
            pg.maxPoolSize shouldBe 15
            config.server.corsAllowedOrigins shouldBe listOf("http://localhost:5173", "https://miru.hononeko.app")

            val rendered = config.toString()
            rendered shouldNotContain "super-secret-plex-token"
            rendered shouldNotContain "db-super-secret"
            rendered shouldNotContain validSecret
            rendered shouldNotContain "very-strong-password-123"
        }

        test("falls back to HOCON values when environment variables are absent") {
            val hocon =
                MapApplicationConfig(
                    "miru.server.port" to "9090",
                    "miru.plex.baseUrl" to "https://plex.example.com",
                    "miru.plex.token" to "hocon-token",
                    "miru.auth.mode" to "none",
                )

            val config = AppConfigLoader.load(env = emptyMap(), hocon = hocon).shouldBeRight()
            config.server.port shouldBe 9090
            config.plex.baseUrl shouldBe "https://plex.example.com"
            config.auth.mode shouldBe AuthMode.NONE
        }

        test("accumulates multiple startup validation errors in one ValidationError") {
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

            val error = AppConfigLoader.load(env = env).shouldBeLeft()
            error.fieldErrors.keys shouldBe
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
        }

        test("rejects unknown database engine and auth mode") {
            val env =
                baseSqliteEnv(
                    mapOf(
                        "DB_ENGINE" to "oracle",
                        "AUTH_MODE" to "kerberos",
                    ),
                )

            val error = AppConfigLoader.load(env = env).shouldBeLeft()
            error.fieldErrors shouldContainKey "DB_ENGINE"
            error.fieldErrors shouldContainKey "AUTH_MODE"
        }

        test("rejects weak bootstrap admin password") {
            val env =
                baseSqliteEnv(
                    mapOf(
                        "MIRU_ADMIN_USERNAME" to "admin",
                        "MIRU_ADMIN_PASSWORD" to "short",
                    ),
                )

            val error = AppConfigLoader.load(env = env).shouldBeLeft()
            error.fieldErrors.keys shouldBe setOf("MIRU_ADMIN_PASSWORD")
        }
    })
