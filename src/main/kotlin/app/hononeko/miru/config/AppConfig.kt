package app.hononeko.miru.config

import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.config.ApplicationConfig
import java.net.URI

enum class AuthMode {
    LOCAL,
    OIDC,
    NONE,
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val corsAllowedOrigins: List<String> = emptyList(),
    val staticWebDir: String? = null,
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
    val baseUrl: String = "",
    val token: String = "",
    val pollIntervalSeconds: Int = 20,
    val websocketEnabled: Boolean = true,
    val encryptionKey: String? = null,
    val clientIdentifier: String? = null,
) {
    val isStaticConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    override fun toString(): String =
        "PlexConfig(baseUrl=$baseUrl, token=***, pollIntervalSeconds=$pollIntervalSeconds, " +
            "websocketEnabled=$websocketEnabled, encryptionKey=${if (encryptionKey != null) "***" else "null"}, " +
            "clientIdentifier=$clientIdentifier)"
}

data class SessionConfig(
    val minPlayDurationSeconds: Int = 120,
    val resumeMergeWindowSeconds: Int = 120,
    val watchedThresholdPercent: Int = 85,
)

data class OidcConfig(
    val enabled: Boolean = false,
    val issuerUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val redirectUri: String = "",
    val displayName: String = "SSO",
    val scopes: List<String> = DEFAULT_SCOPES,
    val groupsClaim: String = "groups",
    val adminGroups: Set<String> = emptySet(),
    val userGroups: Set<String> = emptySet(),
    val autoLinkByEmail: Boolean = true,
    val postLogoutRedirectUri: String? = null,
) {
    override fun toString(): String =
        "OidcConfig(enabled=$enabled, issuerUrl=$issuerUrl, clientId=$clientId, " +
            "clientSecret=${if (clientSecret.isNotEmpty()) "***" else ""}, redirectUri=$redirectUri, " +
            "displayName=$displayName, scopes=$scopes, groupsClaim=$groupsClaim, " +
            "adminGroups=$adminGroups, userGroups=$userGroups, autoLinkByEmail=$autoLinkByEmail, " +
            "postLogoutRedirectUri=$postLogoutRedirectUri)"

    companion object {
        val DEFAULT_SCOPES = listOf("openid", "profile", "email", "groups")
    }
}

data class AuthConfig(
    val mode: AuthMode = AuthMode.LOCAL,
    val sessionSecret: String = "",
    val adminUsername: String? = null,
    val adminPassword: String? = null,
    val allowLocalLogin: Boolean = true,
    val oidc: OidcConfig = OidcConfig(),
) {
    val localLoginEnabled: Boolean
        get() = mode == AuthMode.LOCAL && allowLocalLogin

    val oidcEnabled: Boolean
        get() = mode != AuthMode.NONE && oidc.enabled

    override fun toString(): String =
        "AuthConfig(mode=$mode, sessionSecret=***, adminUsername=$adminUsername, " +
            "adminPassword=${if (adminPassword != null) "***" else "null"}, " +
            "allowLocalLogin=$allowLocalLogin, oidc=$oidc)"
}

data class AppConfig(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val plex: PlexConfig,
    val session: SessionConfig,
    val auth: AuthConfig,
)

data class DatabaseAndSessionConfig(
    val database: DatabaseConfig,
    val session: SessionConfig,
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
            val get = configLookup(env, hocon)

            val server = parseServerConfig(get, errors)
            val database = parseDatabaseConfig(get, errors)
            val plex = parsePlexConfig(get, errors)
            val session = parseSessionConfig(get, errors)
            val auth = parseAuthConfig(get, errors)

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

    fun loadDatabaseAndSessionConfig(
        env: Map<String, String> = System.getenv(),
        hocon: ApplicationConfig? = null,
    ): Either<MiruError.ValidationError, DatabaseAndSessionConfig> =
        either {
            val errors = linkedMapOf<String, String>()
            val get = configLookup(env, hocon)

            val database = parseDatabaseConfig(get, errors)
            val session = parseSessionConfig(get, errors)

            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid application configuration (${errors.size} error(s))",
                    fieldErrors = errors.toMap(),
                )
            }

            DatabaseAndSessionConfig(
                database = database,
                session = session,
            )
        }

    private fun configLookup(
        env: Map<String, String>,
        hocon: ApplicationConfig?,
    ): (String, String, String?) -> String? =
        { envKey, hoconPath, fallbackEnvKey ->
            val fromEnv =
                env[envKey]?.takeIf { it.isNotBlank() }
                    ?: fallbackEnvKey?.let { env[it]?.takeIf { v -> v.isNotBlank() } }
            if (fromEnv != null) {
                fromEnv.trim()
            } else {
                hocon?.propertyOrNull(hoconPath)?.getString()?.trim()?.takeIf { it.isNotBlank() }
            }
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

        val staticWebDir = get("STATIC_WEB_DIR", "miru.server.staticWebDir", null)

        return ServerConfig(
            host = host,
            port = port ?: 8080,
            corsAllowedOrigins = origins,
            staticWebDir = staticWebDir,
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
        val token = get("PLEX_TOKEN", "miru.plex.token", null).orEmpty()

        if (baseUrl.isNotBlank() && !isValidHttpUrl(baseUrl)) {
            errors["PLEX_URL"] = "must be a valid http:// or https:// URL"
        }
        if (baseUrl.isNotBlank() && token.isBlank()) {
            errors["PLEX_TOKEN"] = "is required when PLEX_URL is set"
        } else if (baseUrl.isBlank() && token.isNotBlank()) {
            errors["PLEX_URL"] = "is required when PLEX_TOKEN is set"
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

        val encryptionKey = get("MIRU_ENCRYPTION_KEY", "miru.security.encryptionKey", null)
        if (encryptionKey != null && encryptionKey.length < CredentialCrypto.MIN_KEY_LENGTH) {
            errors["MIRU_ENCRYPTION_KEY"] = "must be at least ${CredentialCrypto.MIN_KEY_LENGTH} characters when set"
        }

        val clientIdentifier = get("PLEX_CLIENT_IDENTIFIER", "miru.plex.clientIdentifier", null)

        return PlexConfig(
            baseUrl = baseUrl,
            token = token,
            pollIntervalSeconds = pollSeconds ?: 20,
            websocketEnabled = wsEnabled ?: true,
            encryptionKey = encryptionKey,
            clientIdentifier = clientIdentifier,
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
                "oidc" -> AuthMode.OIDC
                "none" -> AuthMode.NONE
                else -> {
                    errors["AUTH_MODE"] = "must be 'local', 'oidc', or 'none', got '$modeRaw'"
                    AuthMode.LOCAL
                }
            }

        val allowLocalRaw = get("AUTH_ALLOW_LOCAL_LOGIN", "miru.auth.allowLocalLogin", null) ?: "true"
        val allowLocalLogin = allowLocalRaw.toBooleanStrictOrNull()
        if (allowLocalLogin == null) {
            errors["AUTH_ALLOW_LOCAL_LOGIN"] = "must be 'true' or 'false'"
        }

        val sessionSecret = get("AUTH_SESSION_SECRET", "miru.auth.sessionSecret", null).orEmpty()
        if (mode != AuthMode.NONE && sessionSecret.length < MIN_SESSION_SECRET_LENGTH) {
            errors["AUTH_SESSION_SECRET"] = "must be at least $MIN_SESSION_SECRET_LENGTH characters when AUTH_MODE != none"
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

        val oidc = parseOidcConfig(mode = mode, get = get, errors = errors)
        val effectiveAllowLocal = allowLocalLogin ?: true
        val localLoginEnabled = mode == AuthMode.LOCAL && effectiveAllowLocal
        val oidcEnabled = mode != AuthMode.NONE && oidc.enabled

        if (mode != AuthMode.NONE && !localLoginEnabled && !oidcEnabled) {
            errors["AUTH_ALLOW_LOCAL_LOGIN"] =
                "at least one authentication provider (local or OIDC) must be enabled when AUTH_MODE != none"
        }

        return AuthConfig(
            mode = mode,
            sessionSecret = sessionSecret,
            adminUsername = adminUsername,
            adminPassword = adminPassword,
            allowLocalLogin = effectiveAllowLocal,
            oidc = oidc,
        )
    }

    private fun parseOidcConfig(
        mode: AuthMode,
        get: (String, String, String?) -> String?,
        errors: MutableMap<String, String>,
    ): OidcConfig {
        val enabledRaw = get("OIDC_ENABLED", "miru.auth.oidc.enabled", null)
        val parsedEnabled =
            if (enabledRaw != null) {
                enabledRaw.toBooleanStrictOrNull().also { parsed ->
                    if (parsed == null) {
                        errors["OIDC_ENABLED"] = "must be 'true' or 'false'"
                    }
                }
            } else {
                mode == AuthMode.OIDC
            }

        if (mode == AuthMode.OIDC && parsedEnabled == false) {
            errors["OIDC_ENABLED"] = "must be 'true' when AUTH_MODE=oidc"
        }

        val enabled = parsedEnabled ?: (mode == AuthMode.OIDC)
        val issuerUrl = get("OIDC_ISSUER_URL", "miru.auth.oidc.issuerUrl", null).orEmpty().trimEnd('/')
        val clientId = get("OIDC_CLIENT_ID", "miru.auth.oidc.clientId", null).orEmpty()
        val clientSecret = get("OIDC_CLIENT_SECRET", "miru.auth.oidc.clientSecret", null).orEmpty()
        val redirectUri = get("OIDC_REDIRECT_URI", "miru.auth.oidc.redirectUri", null).orEmpty()
        val displayName = get("OIDC_DISPLAY_NAME", "miru.auth.oidc.displayName", null) ?: "SSO"
        val scopesRaw = get("OIDC_SCOPES", "miru.auth.oidc.scopes", null) ?: "openid,profile,email,groups"
        val scopes =
            scopesRaw
                .split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val groupsClaim = get("OIDC_GROUPS_CLAIM", "miru.auth.oidc.groupsClaim", null) ?: "groups"
        val adminGroups = parseCsvSet(get("OIDC_ADMIN_GROUPS", "miru.auth.oidc.adminGroups", null))
        val userGroups = parseCsvSet(get("OIDC_USER_GROUPS", "miru.auth.oidc.userGroups", null))

        val autoLinkRaw = get("OIDC_AUTO_LINK_BY_EMAIL", "miru.auth.oidc.autoLinkByEmail", null) ?: "true"
        val autoLinkByEmail = autoLinkRaw.toBooleanStrictOrNull()
        if (autoLinkByEmail == null) {
            errors["OIDC_AUTO_LINK_BY_EMAIL"] = "must be 'true' or 'false'"
        }

        val postLogoutRedirectUri = get("OIDC_POST_LOGOUT_REDIRECT_URI", "miru.auth.oidc.postLogoutRedirectUri", null)
        if (postLogoutRedirectUri != null && !isValidHttpUrl(postLogoutRedirectUri)) {
            errors["OIDC_POST_LOGOUT_REDIRECT_URI"] = "must be a valid http:// or https:// URL"
        }

        if (mode != AuthMode.NONE && enabled) {
            if (issuerUrl.isBlank() || !isValidHttpUrl(issuerUrl)) {
                errors["OIDC_ISSUER_URL"] = "must be a valid http:// or https:// URL when OIDC is enabled"
            }
            if (clientId.isBlank()) {
                errors["OIDC_CLIENT_ID"] = "is required when OIDC is enabled"
            }
            if (redirectUri.isBlank() || !isValidHttpUrl(redirectUri)) {
                errors["OIDC_REDIRECT_URI"] = "must be a valid http:// or https:// URL when OIDC is enabled"
            }
            if (adminGroups.isEmpty() && userGroups.isEmpty()) {
                errors["OIDC_ADMIN_GROUPS"] = "at least one group must be configured across OIDC_ADMIN_GROUPS or OIDC_USER_GROUPS"
            }
        }

        return OidcConfig(
            enabled = enabled,
            issuerUrl = issuerUrl,
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirectUri,
            displayName = displayName,
            scopes = scopes.ifEmpty { OidcConfig.DEFAULT_SCOPES },
            groupsClaim = groupsClaim,
            adminGroups = adminGroups,
            userGroups = userGroups,
            autoLinkByEmail = autoLinkByEmail ?: true,
            postLogoutRedirectUri = postLogoutRedirectUri,
        )
    }

    private fun parseCsvSet(raw: String?): Set<String> =
        raw
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun isValidHttpUrl(raw: String): Boolean =
        runCatching {
            val uri = URI(raw)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
}
