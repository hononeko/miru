package app.hononeko.miru.persistence

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.ReadinessCheck
import arrow.core.Either
import java.sql.SQLException
import javax.sql.DataSource

class DatabaseReadinessCheck(
    private val dataSource: DataSource,
) : ReadinessCheck {
    override suspend fun check(): Either<MiruError, Unit> =
        dataSource.dbQuery("readiness_check") { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1").use { rs ->
                    if (!rs.next()) {
                        throw SQLException("Database readiness probe returned no rows")
                    }
                }
            }
        }
}
