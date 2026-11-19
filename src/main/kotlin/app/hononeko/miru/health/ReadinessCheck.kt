package app.hononeko.miru.health

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

fun interface ReadinessCheck {
    suspend fun check(): Either<MiruError, Unit>
}

data class NamedReadinessCheck(
    val name: String,
    val check: ReadinessCheck,
)

@Serializable
data class DependencyCheckStatus(
    val status: String,
    val detail: String? = null,
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val checks: Map<String, DependencyCheckStatus>,
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
)

class ReadinessService(
    private val checks: List<NamedReadinessCheck>,
) {
    suspend fun evaluate(): Pair<Boolean, ReadinessResponse> =
        coroutineScope {
            val results =
                checks
                    .map { named ->
                        async {
                            val outcome =
                                runCatching { named.check.check() }
                                    .getOrElse { ex ->
                                        Either.Left(
                                            MiruError.InternalError(
                                                message = "Readiness probe '${named.name}' failed unexpectedly",
                                                cause = ex,
                                            ),
                                        )
                                    }
                            named.name to outcome
                        }
                    }.awaitAll()

            val checkStatuses =
                results.associate { (name, outcome) ->
                    name to
                        outcome.fold(
                            ifLeft = { error ->
                                DependencyCheckStatus(
                                    status = "unhealthy",
                                    detail = sanitizeDetail(error),
                                )
                            },
                            ifRight = {
                                DependencyCheckStatus(status = "healthy")
                            },
                        )
                }

            val allReady = results.all { (_, outcome) -> outcome.isRight() }
            allReady to
                ReadinessResponse(
                    status = if (allReady) "ready" else "not_ready",
                    checks = checkStatuses,
                )
        }

    private fun sanitizeDetail(error: MiruError): String =
        when (error) {
            is MiruError.UpstreamUnavailable -> "Upstream '${error.provider}' is unavailable"
            is MiruError.UpstreamAuthError -> "Upstream '${error.provider}' authentication failed"
            is MiruError.PersistenceError -> "Database check '${error.operation}' failed"
            is MiruError.InternalError -> "Internal dependency check failed"
            else -> error.message
        }
}
