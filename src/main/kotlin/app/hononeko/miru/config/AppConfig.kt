package app.hononeko.miru.config

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.config.ApplicationConfig
import java.net.URI

enum class AuthMode {
    LOCAL,
    NONE,
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val corsAllowedOrigins: List<String> = emptyList(),
)

sealed interface DatabaseConfig {
    data class Sqlite(
        val path: String,
    ) : DatabaseConfig

    data class Postgres(
        val url: String,
        val user: String,
        val password: String,
        val maxPoolSize: Int = 10,
    ) : DatabaseConfig {
        override fun toString(): String =
            "Postgres(url=$url, user=$user, password=***, maxPoolSize=$maxPoolSize)"
    }
}

data class PlexConfig(
    val baseUrl: String,
    val token: String,
    val pollIntervalSeconds: Int = 20,
    val websocketEnabled: Boolean = true,
) {
    override fun toString(): String =
        "PlexConfig(baseUrl=$baseUrl, token=***, pollIntervalSeconds=$pollIntervalSeconds, websocketEnabled=$websocketEnabled)"
}

data class SessionConfig(
    val minPlayDurationSeconds: Int = 120,
    val resumeMergeWindowSeconds: Int = 120,
    val watchedThresholdPercent: Int = 85,
)

data class AuthConfig(
    val mode: AuthMode = AuthMode.LOCAL,
    val sessionSecret: String = "",
    val adminUsername: String? = null,
    val adminPassword: String? = null,
) {
    override fun toString(): String =
        "AuthConfig(mode=$mode, sessionSecret=***, adminUsername=$adminUsername, adminPassword=${if (adminPassword != null) "***" else "null"})"
}

data class AppConfig(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val plex: PlexConfig,
    val session: SessionConfig,
    val auth: AuthConfig,
)

object AppConfigLoader {
    private const val MIN_SESSION_SECRET_LENGTH = 32
    private const val MIN_ADMIN_PASSWORD_LENGTH = 12
    private const val MIN_POLL_INTERVAL_SECONDS = 5
    private const val MAX_PORT = 65_535
    private const val MAX_PERCENT = 100

    fun load(
        env: Map<String, String> = System.getenv(),
        hocon: ApplicationConfig? = null,
    ): Either<MiruError.ValidationError, AppConfig> =
        either {
            val errors = linkedMapOf<String, String>()

            fun get(
                envKey: String,
                hoconPath: String,
                fallbackEnvKey: String? = null,
            ): String? {
                val fromEnv =
                    env[envKey]?.takeIf { it.isNotBlank() }
                        ?: fallbackEnvKey?.let { env[it]?.takeIf { v -> v.isNotBlank() } }
                if (fromEnv != null) return fromEnv.trim()
                return hocon?.propertyOrNull(hoconPath)?.getString()?.trim()?.takeIf { it.isNotBlank() }
            }

            val server = parseServerConfig(::get, errors)
            val database = parseDatabaseConfig(::get, errors)
            val plex = parsePlexConfig(::get, errors)
            val session = parseSessionConfig(::get, errors)
            val auth = parseAuthConfig(::get, errors)

            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid application configuration (${errors.size} error(s))",
                    fieldErrors = errors.toMap(),
                )
            }

            AppConfig(
                server = server,
                database = database,
                plex = plex,
                session = session,
                auth = auth,
            )
        }

    private fun parseServerConfig(
        get: (String, String, String?) -> String?,
        errors: MutableMap<String, String>,
    ): ServerConfig {
        val host = get("MIRU_HOST", "miru.server.host", null) ?: "0.0.0.0"
        val portRaw = get("MIRU_PORT", "miru.server.port", "PORT") ?: "8080"
        val port = portRaw.toIntOrNull()
        if (port == null || port !in 1..MAX_PORT) {
            errors["MIRU_PORT"] = "must be an integer between 1 and $MAX_PORT"
        }

        val originsRaw = get("MIRU_CORS_ALLOWED_ORIGINS", "miru.server.corsAllowedOrigins", null).orEmpty()
        val origins =
            originsRaw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        return ServerConfig(
            host = host,
            port = port ?: 8080,
            corsAllowedOrigins = origins,
        )
    }

    private fun parseDatabaseConfig(
        get: (String, String, String?) -> String?,
        errors: MutableMap<String, String>,
    ): DatabaseConfig {
        val engine = (get("DB_ENGINE", "miru.database.engine", null) ?: "sqlite").lowercase()
        return when (engine) {
            "sqlite" -> {
                val path = get("SQLITE_PATH", "miru.database.sqlite.path", null) ?: "./data/miru.db"
                if (path.isBlank()) {
                    errors["SQLITE_PATH"] = "must not be blank when DB_ENGINE=sqlite"
                }
                DatabaseConfig.Sqlite(path = path)
            }

            "postgres", "postgresql" -> {
                val url = get("DATABASE_URL", "miru.database.postgres.url", null).orEmpty()
                val user = get("DATABASE_USER", "miru.database.postgres.user", null).orEmpty()
                val password = get("DATABASE_PASSWORD", "miru.database.postgres.password", null).orEmpty()
                val maxPoolRaw = get("DATABASE_MAX_POOL_SIZE", "miru.database.postgres.maxPoolSize", null) ?: "10"
                val maxPool = maxPoolRaw.toIntOrNull()

                if (url.isBlank() || !url.startsWith("jdbc:postgresql://")) {
                    errors["DATABASE_URL"] = "must be a valid jdbc:postgresql:// URL when DB_ENGINE=postgres"
                }
                if (user.isBlank()) {
                    errors["DATABASE_USER"] = "is required when DB_ENGINE=postgres"
                }
                if (password.isBlank()) {
                    errors["DATABASE_PASSWORD"] = "is required when DB_ENGINE=postgres"
                }
                if (maxPool == null || maxPool < 1) {
                    errors["DATABASE_MAX_POOL_SIZE"] = "must be an integer >= 1"
                }

                DatabaseConfig.Postgres(
                    url = url,
                    user = user,
                    password = password,
                    maxPoolSize = maxPool ?: 10,
                )
            }

            else -> {
                errors["DB_ENGINE"] = "must be 'sqlite' or 'postgres', got '$engine'"
                DatabaseConfig.Sqlite(path = "./data/miru.db")
            }
        }
    }

    private fun parsePlexConfig(
        get: (String, String, String?) -> String?,
        errors: MutableMap<String, String>,
    ): PlexConfig {
        val baseUrl = get("PLEX_URL", "miru.plex.baseUrl", null).orEmpty().trimEnd('/')
        if (baseUrl.isBlank() || !isValidHttpUrl(baseUrl)) {
            errors["PLEX_URL"] = "must be a valid http:// or https:// URL"
        }

        val token = get("PLEX_TOKEN", "miru.plex.token", null).orEmpty()
        if (token.isBlank()) {
            errors["PLEX_TOKEN"] = "is required"
        }

        val pollRaw = get("PLEX_POLL_INTERVAL_SECONDS", "miru.plex.pollIntervalSeconds", null) ?: "20"
        val pollSeconds = pollRaw.toIntOrNull()
        if (pollSeconds == null || pollSeconds < MIN_POLL_INTERVAL_SECONDS) {
            errors["PLEX_POLL_INTERVAL_SECONDS"] = "must be an integer >= $MIN_POLL_INTERVAL_SECONDS"
        }

        val wsRaw = get("PLEX_WEBSOCKET_ENABLED", "miru.plex.websocketEnabled", null) ?: "true"
        val wsEnabled = wsRaw.toBooleanStrictOrNull()
        if (wsEnabled == null) {
            errors["PLEX_WEBSOCKET_ENABLED"] = "must be 'true' or 'false'"
        }

        return PlexConfig(
            baseUrl = baseUrl,
            token = token,
            pollIntervalSeconds = pollSeconds ?: 20,
            websocketEnabled = wsEnabled ?: true,
        )
    }

    private fun parseSessionConfig(
        get: (String, String, String?) -> String?,
        errors: MutableMap<String, String>,
    ): SessionConfig {
        val minPlayRaw = get("MIN_PLAY_DURATION_SECONDS", "miru.session.minPlayDurationSeconds", null) ?: "120"
        val minPlay = minPlayRaw.toIntOrNull()
        if (minPlay == null || minPlay < 0) {
            errors["MIN_PLAY_DURATION_SECONDS"] = "must be an integer >= 0"
        }

        val mergeRaw = get("RESUME_MERGE_WINDOW_SECONDS", "miru.session.resumeMergeWindowSeconds", null) ?: "120"
        val mergeWindow = mergeRaw.toIntOrNull()
        if (mergeWindow == null || mergeWindow < 0) {
            errors["RESUME_MERGE_WINDOW_SECONDS"] = "must be an integer >= 0"
        }

        val thresholdRaw = get("WATCHED_THRESHOLD_PERCENT", "miru.session.watchedThresholdPercent", null) ?: "85"
        val threshold = thresholdRaw.toIntOrNull()
        if (threshold == null || threshold !in 1..MAX_PERCENT) {
            errors["WATCHED_THRESHOLD_PERCENT"] = "must be an integer between 1 and $MAX_PERCENT"
        }

        return SessionConfig(
            minPlayDurationSeconds = minPlay ?: 120,
            resumeMergeWindowSeconds = mergeWindow ?: 120,
            watchedThresholdPercent = threshold ?: 85,
        )
    }

    private fun parseAuthConfig(
        get: (String, String, String?) -> String?,
        errors: MutableMap<String, String>,
    ): AuthConfig {
        val modeRaw = (get("AUTH_MODE", "miru.auth.mode", null) ?: "local").lowercase()
        val mode =
            when (modeRaw) {
                "local" -> AuthMode.LOCAL
                "none" -> AuthMode.NONE
                else -> {
                    errors["AUTH_MODE"] = "must be 'local' or 'none', got '$modeRaw'"
                    AuthMode.LOCAL
                }
            }

        val sessionSecret = get("AUTH_SESSION_SECRET", "miru.auth.sessionSecret", null).orEmpty()
        if (mode == AuthMode.LOCAL && sessionSecret.length < MIN_SESSION_SECRET_LENGTH) {
            errors["AUTH_SESSION_SECRET"] = "must be at least $MIN_SESSION_SECRET_LENGTH characters when AUTH_MODE=local"
        }

        val adminUsername = get("MIRU_ADMIN_USERNAME", "miru.auth.adminUsername", null)
        val adminPassword = get("MIRU_ADMIN_PASSWORD", "miru.auth.adminPassword", null)

        if (adminUsername != null && adminPassword == null) {
            errors["MIRU_ADMIN_PASSWORD"] = "is required when MIRU_ADMIN_USERNAME is set"
        } else if (adminPassword != null && adminUsername == null) {
            errors["MIRU_ADMIN_USERNAME"] = "is required when MIRU_ADMIN_PASSWORD is set"
        } else if (adminPassword != null && adminPassword.length < MIN_ADMIN_PASSWORD_LENGTH) {
            errors["MIRU_ADMIN_PASSWORD"] = "must be at least $MIN_ADMIN_PASSWORD_LENGTH characters"
        }

        return AuthConfig(
            mode = mode,
            sessionSecret = sessionSecret,
            adminUsername = adminUsername,
            adminPassword = adminPassword,
        )
    }

    private fun isValidHttpUrl(raw: String): Boolean =
        runCatching {
            val uri = URI(raw)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
}
