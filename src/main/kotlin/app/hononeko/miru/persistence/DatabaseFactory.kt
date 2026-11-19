package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

enum class DatabaseDialect {
    SQLITE,
    POSTGRES,
}

class DatabaseHandle(
    val dataSource: HikariDataSource,
    val dialect: DatabaseDialect,
) : AutoCloseable {
    override fun close() {
        dataSource.close()
    }
}

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    private const val SQLITE_LOCATION = "classpath:db/migration/sqlite"
    private const val POSTGRES_LOCATION = "classpath:db/migration/postgres"
    private const val SQLITE_INIT_SQL = "PRAGMA foreign_keys = ON;"

    fun createAndMigrate(config: DatabaseConfig): Either<MiruError.PersistenceError, DatabaseHandle> {
        var dataSource: HikariDataSource? = null
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

            logger.info(
                "Database initialized (dialect={}, migrationsExecuted={})",
                dialect,
                result.migrationsExecuted,
            )

            Either.Right(DatabaseHandle(dataSource = dataSource, dialect = dialect))
        } catch (ex: Exception) {
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

    private fun ensureSqliteParentDirectory(path: String) {
        if (path != ":memory:" && !path.startsWith("file:")) {
            Path
                .of(path)
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
