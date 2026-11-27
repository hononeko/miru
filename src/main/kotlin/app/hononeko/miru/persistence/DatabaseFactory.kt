package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

enum class DatabaseDialect {
    SQLITE,
    POSTGRES,
}

class DatabaseHandle(
    val dataSource: HikariDataSource,
    val database: Database,
    val dialect: DatabaseDialect,
    val readDataSource: HikariDataSource = dataSource,
    val readDatabase: Database = database,
) : AutoCloseable {
    override fun close() {
        var firstError: Throwable? = null
        if (readDataSource !== dataSource) {
            try {
                readDataSource.close()
            } catch (ex: Throwable) {
                firstError = ex
            }
        }
        try {
            dataSource.close()
        } catch (ex: Throwable) {
            if (firstError == null) {
                firstError = ex
            } else {
                firstError.addSuppressed(ex)
            }
        }
        if (firstError != null) {
            throw firstError
        }
    }
}

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    private const val SQLITE_LOCATION = "classpath:db/migration/sqlite"
    private const val POSTGRES_LOCATION = "classpath:db/migration/postgres"
    private const val SQLITE_INIT_SQL = "PRAGMA foreign_keys = ON;"
    private const val SQLITE_READ_INIT_SQL = "PRAGMA query_only = ON; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;"

    fun createAndMigrate(config: DatabaseConfig): Either<MiruError.PersistenceError, DatabaseHandle> {
        var dataSource: HikariDataSource? = null
        var readDataSource: HikariDataSource? = null
        return try {
            val dialect =
                when (config) {
                    is DatabaseConfig.Sqlite -> DatabaseDialect.SQLITE
                    is DatabaseConfig.Postgres -> DatabaseDialect.POSTGRES
                }

            val hikariConfig = buildHikariConfig(config)
            dataSource = HikariDataSource(hikariConfig)

            val migrationLocation =
                when (dialect) {
                    DatabaseDialect.SQLITE -> SQLITE_LOCATION
                    DatabaseDialect.POSTGRES -> POSTGRES_LOCATION
                }

            val result =
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations(migrationLocation)
                    .validateMigrationNaming(true)
                    .load()
                    .migrate()

            val database = Database.connect(datasource = dataSource)
            val separateReadPool = config is DatabaseConfig.Sqlite && !isInMemorySqlite(config.path)
            val resolvedReadDataSource: HikariDataSource
            val resolvedReadDatabase: Database
            if (separateReadPool) {
                val reader = HikariDataSource(buildSqliteReadHikariConfig(config))
                readDataSource = reader
                resolvedReadDataSource = reader
                resolvedReadDatabase = Database.connect(datasource = reader)
            } else {
                resolvedReadDataSource = dataSource
                resolvedReadDatabase = database
            }

            logger.info(
                "Database initialized (dialect={}, migrationsExecuted={}, readPoolSize={})",
                dialect,
                result.migrationsExecuted,
                if (separateReadPool) resolvedReadDataSource.maximumPoolSize else dataSource.maximumPoolSize,
            )

            Either.Right(
                DatabaseHandle(
                    dataSource = dataSource,
                    database = database,
                    dialect = dialect,
                    readDataSource = resolvedReadDataSource,
                    readDatabase = resolvedReadDatabase,
                ),
            )
        } catch (ex: Exception) {
            if (readDataSource != null && readDataSource !== dataSource) {
                runCatching { readDataSource.close() }
            }
            runCatching { dataSource?.close() }
            val secretToRedact = (config as? DatabaseConfig.Postgres)?.password
            val sanitizedMessage = sanitizeErrorMessage(ex.message, secretToRedact)
            Either.Left(
                MiruError.PersistenceError(
                    operation = "database_migrate",
                    message = "Failed to initialize or migrate database: $sanitizedMessage",
                    cause = ex,
                ),
            )
        }
    }

    private fun buildHikariConfig(config: DatabaseConfig): HikariConfig =
        when (config) {
            is DatabaseConfig.Sqlite -> {
                require(config.path.isNotBlank()) { "SQLite path must not be blank" }
                require(config.readPoolSize >= 1) { "SQLite read pool size must be >= 1" }
                ensureSqliteParentDirectory(config.path)
                HikariConfig().apply {
                    poolName = "miru-sqlite"
                    driverClassName = "org.sqlite.JDBC"
                    jdbcUrl = "jdbc:sqlite:${config.path}"
                    maximumPoolSize = 1
                    connectionInitSql = SQLITE_INIT_SQL
                    addDataSourceProperty("journal_mode", "WAL")
                    addDataSourceProperty("busy_timeout", "5000")
                    addDataSourceProperty("foreign_keys", "true")
                    addDataSourceProperty("synchronous", "NORMAL")
                }
            }

            is DatabaseConfig.Postgres -> {
                HikariConfig().apply {
                    poolName = "miru-postgres"
                    driverClassName = "org.postgresql.Driver"
                    jdbcUrl = config.url
                    username = config.user
                    password = config.password
                    maximumPoolSize = config.maxPoolSize
                }
            }
        }

    private fun buildSqliteReadHikariConfig(config: DatabaseConfig.Sqlite): HikariConfig =
        HikariConfig().apply {
            poolName = "miru-sqlite-reader"
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:${config.path}"
            maximumPoolSize = config.readPoolSize
            connectionInitSql = SQLITE_READ_INIT_SQL
            addDataSourceProperty("busy_timeout", "5000")
            addDataSourceProperty("foreign_keys", "true")
        }

    internal fun isInMemorySqlite(path: String): Boolean {
        val normalized = path.trim().lowercase()
        return normalized == ":memory:" ||
            (normalized.startsWith("file:") && (normalized.contains("mode=memory") || normalized.contains(":memory:")))
    }

    private fun ensureSqliteParentDirectory(path: String) {
        val trimmed = path.trim()
        if (!isInMemorySqlite(trimmed) && !trimmed.lowercase().startsWith("file:")) {
            Path
                .of(trimmed)
                .toAbsolutePath()
                .normalize()
                .parent
                ?.let { Files.createDirectories(it) }
        }
    }

    private fun sanitizeErrorMessage(
        message: String?,
        password: String?,
    ): String {
        var sanitized = message ?: "Unexpected database error"
        if (!password.isNullOrEmpty()) {
            sanitized = sanitized.replace(password, "***")
        }
        return sanitized.replace(Regex("""(?i)(password=)[^&\s;]+"""), "$1***")
    }
}
