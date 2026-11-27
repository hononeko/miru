package app.hononeko.miru.persistence

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.ReadinessCheck
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import javax.sql.DataSource

class DatabaseReadinessCheck(
    private val dataSource: DataSource,
    private val readDataSource: DataSource = dataSource,
) : ReadinessCheck {
    constructor(handle: DatabaseHandle) : this(
        dataSource = handle.dataSource,
        readDataSource = handle.readDataSource,
    )

    override suspend fun check(): Either<MiruError, Unit> =
        withContext(Dispatchers.IO) {
            try {
                probeDataSource(dataSource)
                if (readDataSource !== dataSource) {
                    probeDataSource(readDataSource)
                }
                Either.Right(Unit)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: SQLException) {
                Either.Left(
                    MiruError.PersistenceError(
                        operation = "readiness_check",
                        message = ex.message ?: "Database readiness check failed",
                        cause = ex,
                    ),
                )
            } catch (ex: IllegalStateException) {
                Either.Left(
                    MiruError.PersistenceError(
                        operation = "readiness_check",
                        message = ex.message ?: "Database readiness check failed",
                        cause = ex,
                    ),
                )
            }
        }

    private fun probeDataSource(source: DataSource) {
        source.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1").use { rs ->
                    if (!rs.next()) {
                        throw SQLException("Database readiness probe returned no rows")
                    }
                }
            }
        }
    }
}
