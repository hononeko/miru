package app.hononeko.miru.persistence

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

suspend fun <T> DataSource.dbQuery(
    operation: String,
    block: (Connection) -> T,
): Either<MiruError.PersistenceError, T> =
    withContext(Dispatchers.IO) {
        try {
            connection.use { conn ->
                Either.Right(block(conn))
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: SQLException) {
            Either.Left(
                MiruError.PersistenceError(
                    operation = operation,
                    message = ex.message ?: "Database operation '$operation' failed",
                    cause = ex,
                ),
            )
        }
    }

suspend fun <T> DataSource.dbTransaction(
    operation: String,
    block: (Connection) -> T,
): Either<MiruError.PersistenceError, T> =
    withContext(Dispatchers.IO) {
        try {
            connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val result = block(conn)
                    conn.commit()
                    Either.Right(result)
                } catch (ex: Throwable) {
                    try {
                        conn.rollback()
                    } catch (rollbackEx: SQLException) {
                        ex.addSuppressed(rollbackEx)
                    }
                    throw ex
                } finally {
                    runCatching { conn.autoCommit = previousAutoCommit }
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: SQLException) {
            Either.Left(
                MiruError.PersistenceError(
                    operation = operation,
                    message = ex.message ?: "Database transaction '$operation' failed",
                    cause = ex,
                ),
            )
        }
    }

fun PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value != null) {
        setLong(index, value)
    } else {
        setNull(index, Types.BIGINT)
    }
}

fun PreparedStatement.setNullableInt(
    index: Int,
    value: Int?,
) {
    if (value != null) {
        setInt(index, value)
    } else {
        setNull(index, Types.INTEGER)
    }
}

fun PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value != null) {
        setString(index, value)
    } else {
        setNull(index, Types.VARCHAR)
    }
}

fun PreparedStatement.setNullableDouble(
    index: Int,
    value: Double?,
) {
    if (value != null) {
        setDouble(index, value)
    } else {
        setNull(index, Types.DOUBLE)
    }
}

fun PreparedStatement.setDialectBoolean(
    index: Int,
    value: Boolean,
    dialect: DatabaseDialect,
) {
    when (dialect) {
        DatabaseDialect.SQLITE -> setInt(index, if (value) 1 else 0)
        DatabaseDialect.POSTGRES -> setBoolean(index, value)
    }
}

fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

fun ResultSet.getNullableString(column: String): String? = getString(column)

fun ResultSet.getNullableDouble(column: String): Double? {
    val value = getDouble(column)
    return if (wasNull()) null else value
}

fun ResultSet.getDialectBoolean(
    column: String,
    dialect: DatabaseDialect,
): Boolean =
    when (dialect) {
        DatabaseDialect.SQLITE -> getInt(column) != 0
        DatabaseDialect.POSTGRES -> getBoolean(column)
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
