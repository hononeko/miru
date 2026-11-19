package app.hononeko.miru.persistence

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException

suspend fun <T> Database.exposedQuery(
    operation: String,
    block: JdbcTransaction.() -> T,
): Either<MiruError.PersistenceError, T> =
    withContext(Dispatchers.IO) {
        try {
            Either.Right(transaction(db = this@exposedQuery) { block() })
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: ExposedSQLException) {
            Either.Left(
                MiruError.PersistenceError(
                    operation = operation,
                    message = sanitizeDatabaseErrorMessage(ex.message, operation),
                    cause = ex,
                ),
            )
        } catch (ex: SQLException) {
            Either.Left(
                MiruError.PersistenceError(
                    operation = operation,
                    message = sanitizeDatabaseErrorMessage(ex.message, operation),
                    cause = ex,
                ),
            )
        } catch (ex: IllegalStateException) {
            Either.Left(
                MiruError.PersistenceError(
                    operation = operation,
                    message = sanitizeDatabaseErrorMessage(ex.message, operation),
                    cause = ex,
                ),
            )
        }
    }

fun escapeLikePattern(raw: String): String =
    buildString(raw.length) {
        for (ch in raw) {
            if (ch == '\\' || ch == '%' || ch == '_') {
                append('\\')
            }
            append(ch)
        }
    }

private fun sanitizeDatabaseErrorMessage(
    message: String?,
    operation: String,
): String {
    val raw = message ?: "Database operation '$operation' failed"
    return raw.replace(Regex("""(?i)(password=)[^&\s;]+"""), "$1***")
}
