package app.hononeko.miru.config

import app.hononeko.miru.persistence.model.GuardrailMode
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
            config.plex.plexTvBaseUrl shouldBe "https://plex.tv"
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
                        "MIRU_ADMIN_PASSWORD" to "Very-strong-password-123",
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
            rendered shouldNotContain "Very-strong-password-123"
        }

        test("falls back to HOCON values when environment variables are absent") {
            val hocon =
                MapApplicationConfig(
                    "miru.server.port" to "9090",
                    "miru.plex.url" to "https://plex.example.com",
                    "miru.plex.token" to "hocon-token",
                    "miru.auth.mode" to "none",
                    "miru.auth.rateLimit.loginMaxAttempts" to "31",
                    "miru.auth.rateLimit.accountMaxFailures" to "6",
                    "miru.auth.rateLimit.setupMaxAttempts" to "5",
                )

            val config = AppConfigLoader.load(env = emptyMap(), hocon = hocon).shouldBeRight()
            config.server.port shouldBe 9090
            config.plex.baseUrl shouldBe "https://plex.example.com"
            config.auth.mode shouldBe AuthMode.NONE
            config.auth.rateLimit.loginMaxPerIp shouldBe 31
            config.auth.rateLimit.loginMaxFailuresPerAccount shouldBe 6
            config.auth.rateLimit.setupMaxPerIp shouldBe 5
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
            val weakPasswords =
                listOf(
                    "Short1a",
                    "alllowercase12",
                    "ALLUPPERCASE12",
                    "NoDigitsPassword!",
                )
            for (weak in weakPasswords) {
                val env =
                    baseSqliteEnv(
                        mapOf(
                            "MIRU_ADMIN_USERNAME" to "admin",
                            "MIRU_ADMIN_PASSWORD" to weak,
                        ),
                    )

                val error = AppConfigLoader.load(env = env).shouldBeLeft()
                error.fieldErrors.keys shouldBe setOf("MIRU_ADMIN_PASSWORD")
            }
        }

        test("loads OIDC-only and hybrid configurations and redacts OIDC_CLIENT_SECRET") {
            val oidcOnlyEnv =
                baseSqliteEnv(
                    mapOf(
                        "AUTH_MODE" to "oidc",
                        "AUTH_ALLOW_LOCAL_LOGIN" to "false",
                        "OIDC_ISSUER_URL" to "https://sso.example.com/application/o/miru/",
                        "OIDC_CLIENT_ID" to "miru-client",
                        "OIDC_CLIENT_SECRET" to "super-secret-oidc-client-secret",
                        "OIDC_REDIRECT_URI" to "https://miru.example.com/api/v1/auth/oidc/callback",
                        "OIDC_DISPLAY_NAME" to "Authentik",
                        "OIDC_SCOPES" to "openid,profile,email,groups",
                        "OIDC_GROUPS_CLAIM" to "roles",
                        "OIDC_ADMIN_GROUPS" to "miru-admins,infra",
                        "OIDC_USER_GROUPS" to "miru-users",
                        "OIDC_AUTO_LINK_BY_EMAIL" to "false",
                        "OIDC_POST_LOGOUT_REDIRECT_URI" to "https://miru.example.com/login",
                    ),
                )

            val config = AppConfigLoader.load(env = oidcOnlyEnv).shouldBeRight()
            config.auth.mode shouldBe AuthMode.OIDC
            config.auth.localLoginEnabled shouldBe false
            config.auth.oidcEnabled shouldBe true
            config.auth.oidc.issuerUrl shouldBe "https://sso.example.com/application/o/miru"
            config.auth.oidc.clientId shouldBe "miru-client"
            config.auth.oidc.displayName shouldBe "Authentik"
            config.auth.oidc.scopes shouldBe listOf("openid", "profile", "email", "groups")
            config.auth.oidc.groupsClaim shouldBe "roles"
            config.auth.oidc.adminGroups shouldBe setOf("miru-admins", "infra")
            config.auth.oidc.userGroups shouldBe setOf("miru-users")
            config.auth.oidc.autoLinkByEmail shouldBe false
            config.auth.oidc.postLogoutRedirectUri shouldBe "https://miru.example.com/login"

            config.toString() shouldNotContain "super-secret-oidc-client-secret"

            // Hybrid mode: AUTH_MODE=local + OIDC_ENABLED=true
            val hybridEnv =
                oidcOnlyEnv + mapOf(
                    "AUTH_MODE" to "local",
                    "AUTH_ALLOW_LOCAL_LOGIN" to "true",
                    "OIDC_ENABLED" to "true",
                )
            val hybridConfig = AppConfigLoader.load(env = hybridEnv).shouldBeRight()
            hybridConfig.auth.mode shouldBe AuthMode.LOCAL
            hybridConfig.auth.localLoginEnabled shouldBe true
            hybridConfig.auth.oidcEnabled shouldBe true
        }

        test("validates OIDC required fields and provider enablement rules") {
            val missingOidcFields =
                baseSqliteEnv(
                    mapOf(
                        "AUTH_MODE" to "oidc",
                        "OIDC_ISSUER_URL" to "ftp://invalid",
                        "OIDC_CLIENT_ID" to "  ",
                        "OIDC_REDIRECT_URI" to "not-a-url",
                        "OIDC_POST_LOGOUT_REDIRECT_URI" to "invalid-uri",
                    ),
                )
            val oidcErr = AppConfigLoader.load(env = missingOidcFields).shouldBeLeft()
            oidcErr.fieldErrors shouldContainKey "OIDC_ISSUER_URL"
            oidcErr.fieldErrors shouldContainKey "OIDC_CLIENT_ID"
            oidcErr.fieldErrors shouldContainKey "OIDC_REDIRECT_URI"
            oidcErr.fieldErrors shouldContainKey "OIDC_ADMIN_GROUPS"
            oidcErr.fieldErrors shouldContainKey "OIDC_POST_LOGOUT_REDIRECT_URI"

            // AUTH_MODE=local with AUTH_ALLOW_LOCAL_LOGIN=false and OIDC disabled fails
            val noProviderEnv =
                baseSqliteEnv(
                    mapOf(
                        "AUTH_MODE" to "local",
                        "AUTH_ALLOW_LOCAL_LOGIN" to "false",
                        "OIDC_ENABLED" to "false",
                    ),
                )
            val noProviderErr = AppConfigLoader.load(env = noProviderEnv).shouldBeLeft()
            noProviderErr.fieldErrors shouldContainKey "AUTH_ALLOW_LOCAL_LOGIN"

            // AUTH_MODE=oidc with OIDC_ENABLED=false fails
            val disabledOidcMode =
                baseSqliteEnv(
                    mapOf(
                        "AUTH_MODE" to "oidc",
                        "OIDC_ENABLED" to "false",
                    ),
                )
            val disabledErr = AppConfigLoader.load(env = disabledOidcMode).shouldBeLeft()
            disabledErr.fieldErrors shouldContainKey "OIDC_ENABLED"
        }

        test("allows zero-config startup when both PLEX_URL and PLEX_TOKEN are omitted") {
            val config =
                AppConfigLoader
                    .load(env = mapOf("AUTH_SESSION_SECRET" to validSecret))
                    .shouldBeRight()

            config.plex.baseUrl shouldBe ""
            config.plex.token shouldBe ""
            config.plex.isStaticConfigured shouldBe false
        }

        test("rejects partial Plex configuration when only one of PLEX_URL or PLEX_TOKEN is set") {
            val urlOnly =
                AppConfigLoader
                    .load(
                        env =
                            mapOf(
                                "PLEX_URL" to "http://plex.local:32400",
                                "AUTH_SESSION_SECRET" to validSecret,
                            ),
                    ).shouldBeLeft()
            urlOnly.fieldErrors.keys shouldBe setOf("PLEX_TOKEN")

            val tokenOnly =
                AppConfigLoader
                    .load(
                        env =
                            mapOf(
                                "PLEX_TOKEN" to "some-token",
                                "AUTH_SESSION_SECRET" to validSecret,
                            ),
                    ).shouldBeLeft()
            tokenOnly.fieldErrors.keys shouldBe setOf("PLEX_URL")
        }

        test("validates MIRU_ENCRYPTION_KEY length, loads PLEX_CLIENT_IDENTIFIER, and redacts encryptionKey") {
            val shortKeyErr =
                AppConfigLoader
                    .load(
                        env =
                            mapOf(
                                "AUTH_SESSION_SECRET" to validSecret,
                                "MIRU_ENCRYPTION_KEY" to "too-short",
                            ),
                    ).shouldBeLeft()
            shortKeyErr.fieldErrors.keys shouldBe setOf("MIRU_ENCRYPTION_KEY")

            val encKey = "encryption-key-at-least-32-characters-long"
            val config =
                AppConfigLoader
                    .load(
                        env =
                            mapOf(
                                "AUTH_SESSION_SECRET" to validSecret,
                                "MIRU_ENCRYPTION_KEY" to encKey,
                                "PLEX_CLIENT_IDENTIFIER" to "miru-custom-client-id",
                            ),
                    ).shouldBeRight()
            config.plex.encryptionKey shouldBe encKey
            config.plex.clientIdentifier shouldBe "miru-custom-client-id"
            config.toString() shouldNotContain encKey
        }

        test("loads and validates PLEX_TV_BASE_URL from env and HOCON") {
            val fromEnv =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf("PLEX_TV_BASE_URL" to "http://127.0.0.1:19876/"),
                            ),
                    ).shouldBeRight()
            fromEnv.plex.plexTvBaseUrl shouldBe "http://127.0.0.1:19876"
            fromEnv.plex.toString() shouldNotContain "super-secret-plex-token"

            val fromHocon =
                AppConfigLoader
                    .load(
                        env = mapOf("AUTH_SESSION_SECRET" to validSecret),
                        hocon = MapApplicationConfig("miru.plex.plexTvBaseUrl" to "http://plex-tv.internal:8080/"),
                    ).shouldBeRight()
            fromHocon.plex.plexTvBaseUrl shouldBe "http://plex-tv.internal:8080"

            val invalid =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf("PLEX_TV_BASE_URL" to "ftp://invalid.plex.tv"),
                            ),
                    ).shouldBeLeft()
            invalid.fieldErrors.keys shouldBe setOf("PLEX_TV_BASE_URL")
        }

        test("loads default disabled guardrails, env/HOCON overrides, and validates guardrail settings") {
            val defaults = AppConfigLoader.load(env = baseSqliteEnv()).shouldBeRight()
            defaults.guardrails.transcode4kMode shouldBe GuardrailMode.DISABLED
            defaults.guardrails.pausedTimeoutMode shouldBe GuardrailMode.DISABLED
            defaults.guardrails.pausedTimeoutMinutes shouldBe 30
            defaults.guardrails.concurrentStreamsMode shouldBe GuardrailMode.DISABLED
            defaults.guardrails.concurrentStreamsMax shouldBe 2

            val configured =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf(
                                    "GUARDRAIL_4K_TRANSCODE_MODE" to "warn",
                                    "GUARDRAIL_4K_TRANSCODE_MESSAGE" to "Use Direct Play",
                                    "GUARDRAIL_PAUSED_TIMEOUT_MODE" to "enforce",
                                    "GUARDRAIL_PAUSED_TIMEOUT_MINUTES" to "45",
                                    "GUARDRAIL_PAUSED_TIMEOUT_MESSAGE" to "Paused too long",
                                    "GUARDRAIL_CONCURRENT_STREAMS_MODE" to "WARN",
                                    "GUARDRAIL_CONCURRENT_STREAMS_MAX" to "3",
                                    "GUARDRAIL_CONCURRENT_STREAMS_MESSAGE" to "Too many streams",
                                ),
                            ),
                    ).shouldBeRight()
            configured.guardrails.transcode4kMode shouldBe GuardrailMode.WARN
            configured.guardrails.transcode4kMessage shouldBe "Use Direct Play"
            configured.guardrails.pausedTimeoutMode shouldBe GuardrailMode.ENFORCE
            configured.guardrails.pausedTimeoutMinutes shouldBe 45
            configured.guardrails.pausedTimeoutMessage shouldBe "Paused too long"
            configured.guardrails.concurrentStreamsMode shouldBe GuardrailMode.WARN
            configured.guardrails.concurrentStreamsMax shouldBe 3
            configured.guardrails.concurrentStreamsMessage shouldBe "Too many streams"

            val hoconConfigured =
                AppConfigLoader
                    .load(
                        env = mapOf("AUTH_SESSION_SECRET" to validSecret),
                        hocon =
                            MapApplicationConfig(
                                "miru.guardrails.transcode4k.mode" to "enforce",
                                "miru.guardrails.pausedTimeout.minutes" to "15",
                                "miru.guardrails.concurrentStreams.max" to "4",
                            ),
                    ).shouldBeRight()
            hoconConfigured.guardrails.transcode4kMode shouldBe GuardrailMode.ENFORCE
            hoconConfigured.guardrails.pausedTimeoutMinutes shouldBe 15
            hoconConfigured.guardrails.concurrentStreamsMax shouldBe 4

            val invalid =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf(
                                    "GUARDRAIL_4K_TRANSCODE_MODE" to "kill",
                                    "GUARDRAIL_4K_TRANSCODE_MESSAGE" to "x".repeat(501),
                                    "GUARDRAIL_PAUSED_TIMEOUT_MODE" to "maybe",
                                    "GUARDRAIL_PAUSED_TIMEOUT_MINUTES" to "0",
                                    "GUARDRAIL_PAUSED_TIMEOUT_MESSAGE" to "line1\nline2",
                                    "GUARDRAIL_CONCURRENT_STREAMS_MODE" to "alert",
                                    "GUARDRAIL_CONCURRENT_STREAMS_MAX" to "51",
                                ),
                            ),
                    ).shouldBeLeft()
            invalid.fieldErrors.keys shouldBe
                setOf(
                    "GUARDRAIL_4K_TRANSCODE_MODE",
                    "GUARDRAIL_4K_TRANSCODE_MESSAGE",
                    "GUARDRAIL_PAUSED_TIMEOUT_MODE",
                    "GUARDRAIL_PAUSED_TIMEOUT_MINUTES",
                    "GUARDRAIL_PAUSED_TIMEOUT_MESSAGE",
                    "GUARDRAIL_CONCURRENT_STREAMS_MODE",
                    "GUARDRAIL_CONCURRENT_STREAMS_MAX",
                )
        }

        test("turns on the Tautulli API only on request") {
            AppConfigLoader.load(env = baseSqliteEnv()).shouldBeRight().tautulliCompat.enabled shouldBe false
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("ENABLE_TAUTULLI_COMPAT_API" to "true")))
                .shouldBeRight()
                .tautulliCompat.enabled shouldBe true
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("ENABLE_TAUTULLI_COMPAT_API" to "yes")))
                .shouldBeLeft()
                .fieldErrors.keys shouldBe setOf("ENABLE_TAUTULLI_COMPAT_API")
        }

        test("loads and validates AUTH_COOKIE_SECURE") {
            AppConfigLoader.load(env = baseSqliteEnv()).shouldBeRight().auth.cookieSecure shouldBe CookieSecureMode.AUTO
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("AUTH_COOKIE_SECURE" to "true")))
                .shouldBeRight()
                .auth.cookieSecure shouldBe CookieSecureMode.ALWAYS
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("AUTH_COOKIE_SECURE" to "always")))
                .shouldBeRight()
                .auth.cookieSecure shouldBe CookieSecureMode.ALWAYS
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("AUTH_COOKIE_SECURE" to "false")))
                .shouldBeRight()
                .auth.cookieSecure shouldBe CookieSecureMode.NEVER
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("AUTH_COOKIE_SECURE" to "never")))
                .shouldBeRight()
                .auth.cookieSecure shouldBe CookieSecureMode.NEVER
            // No longer a fallback
            AppConfigLoader
                .load(env = baseSqliteEnv(mapOf("COOKIE_SECURE" to "never")))
                .shouldBeRight()
                .auth.cookieSecure shouldBe CookieSecureMode.AUTO
            AppConfigLoader
                .load(env = mapOf("AUTH_SESSION_SECRET" to validSecret), hocon = MapApplicationConfig("miru.auth.cookieSecure" to "always"))
                .shouldBeRight()
                .auth.cookieSecure shouldBe CookieSecureMode.ALWAYS

            val invalid =
                AppConfigLoader
                    .load(env = baseSqliteEnv(mapOf("AUTH_COOKIE_SECURE" to "sometimes")))
                    .shouldBeLeft()
            invalid.fieldErrors.keys shouldBe setOf("AUTH_COOKIE_SECURE")
        }

        test("loads and validates MIRU_TRUST_PROXY_HEADERS and AUTH_RATE_LIMIT_* settings") {
            val defaults = AppConfigLoader.load(env = baseSqliteEnv()).shouldBeRight()
            defaults.server.trustProxyHeaders shouldBe false
            defaults.auth.rateLimit shouldBe AuthRateLimitConfig()

            val configured =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf(
                                    "MIRU_TRUST_PROXY_HEADERS" to "true",
                                    "AUTH_RATE_LIMIT_ENABLED" to "false",
                                    "AUTH_RATE_LIMIT_WINDOW_SECONDS" to "120",
                                    "AUTH_RATE_LIMIT_LOGIN_MAX_ATTEMPTS" to "30",
                                    "AUTH_RATE_LIMIT_ACCOUNT_MAX_FAILURES" to "5",
                                    "AUTH_RATE_LIMIT_SETUP_MAX_ATTEMPTS" to "4",
                                ),
                            ),
                    ).shouldBeRight()
            configured.server.trustProxyHeaders shouldBe true
            configured.auth.rateLimit shouldBe
                AuthRateLimitConfig(
                    enabled = false,
                    windowSeconds = 120,
                    loginMaxPerIp = 30,
                    loginMaxFailuresPerAccount = 5,
                    setupMaxPerIp = 4,
                )

            val invalid =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf(
                                    "MIRU_TRUST_PROXY_HEADERS" to "yes",
                                    "AUTH_RATE_LIMIT_ENABLED" to "no",
                                    "AUTH_RATE_LIMIT_WINDOW_SECONDS" to "0",
                                    "AUTH_RATE_LIMIT_LOGIN_MAX_ATTEMPTS" to "0",
                                    "AUTH_RATE_LIMIT_ACCOUNT_MAX_FAILURES" to "10001",
                                    "AUTH_RATE_LIMIT_SETUP_MAX_ATTEMPTS" to "abc",
                                ),
                            ),
                    ).shouldBeLeft()
            invalid.fieldErrors.keys shouldBe
                setOf(
                    "MIRU_TRUST_PROXY_HEADERS",
                    "AUTH_RATE_LIMIT_ENABLED",
                    "AUTH_RATE_LIMIT_WINDOW_SECONDS",
                    "AUTH_RATE_LIMIT_LOGIN_MAX_ATTEMPTS",
                    "AUTH_RATE_LIMIT_ACCOUNT_MAX_FAILURES",
                    "AUTH_RATE_LIMIT_SETUP_MAX_ATTEMPTS",
                )
        }

        test("loads and validates METRICS_* settings from env and HOCON") {
            val defaults = AppConfigLoader.load(env = baseSqliteEnv()).shouldBeRight()
            defaults.metrics shouldBe MetricsConfig(enabled = false, host = "0.0.0.0", port = 9090)

            val fromEnv =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf(
                                    "METRICS_ENABLED" to "true",
                                    "METRICS_HOST" to "127.0.0.1",
                                    "METRICS_PORT" to "9100",
                                ),
                            ),
                    ).shouldBeRight()
            fromEnv.metrics shouldBe MetricsConfig(enabled = true, host = "127.0.0.1", port = 9100)

            val fromHocon =
                AppConfigLoader
                    .load(
                        env = mapOf("AUTH_SESSION_SECRET" to validSecret),
                        hocon =
                            MapApplicationConfig(
                                "miru.metrics.enabled" to "true",
                                "miru.metrics.host" to "10.0.0.5",
                                "miru.metrics.port" to "8080",
                            ),
                    ).shouldBeRight()
            fromHocon.metrics shouldBe MetricsConfig(enabled = true, host = "10.0.0.5", port = 8080)

            val invalid =
                AppConfigLoader
                    .load(
                        env =
                            baseSqliteEnv(
                                mapOf(
                                    "METRICS_ENABLED" to "maybe",
                                    "METRICS_PORT" to "70000",
                                ),
                            ),
                    ).shouldBeLeft()
            invalid.fieldErrors.keys shouldBe setOf("METRICS_ENABLED", "METRICS_PORT")

            val zeroPort =
                AppConfigLoader
                    .load(env = baseSqliteEnv(mapOf("METRICS_PORT" to "0")))
                    .shouldBeLeft()
            zeroPort.fieldErrors.keys shouldBe setOf("METRICS_PORT")
        }
    })
