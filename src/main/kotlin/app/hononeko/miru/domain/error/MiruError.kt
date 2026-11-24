package app.hononeko.miru.domain.error

/**
 * Typed domain error hierarchy for Arrow-kt [arrow.core.Either] / [arrow.core.raise.Raise] error handling.
 *
 * Adapters, repositories, and services return `Either<MiruError, T>` instead of throwing domain
 * exceptions, and HTTP routes map every variant to an RFC 7807 Problem Details response.
 */
sealed interface MiruError {
    val message: String

    data class ValidationError(
        override val message: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : MiruError

    data class Unauthorized(
        override val message: String = "Authentication required",
    ) : MiruError

    data class Forbidden(
        override val message: String = "Insufficient permissions",
    ) : MiruError

    data class NotFound(
        val resource: String,
        val id: String? = null,
        override val message: String = if (id != null) "$resource '$id' was not found" else "$resource was not found",
    ) : MiruError

    data class Conflict(
        override val message: String,
    ) : MiruError

    data class TooManyRequests(
        override val message: String = "Too many requests; please try again later",
        val retryAfterSeconds: Long = 60L,
    ) : MiruError

    data class UpstreamAuthError(
        val provider: String,
        override val message: String,
    ) : MiruError

    data class UpstreamUnavailable(
        val provider: String,
        override val message: String,
        val cause: Throwable? = null,
    ) : MiruError

    data class PersistenceError(
        val operation: String,
        override val message: String,
        val cause: Throwable? = null,
    ) : MiruError

    data class InternalError(
        override val message: String,
        val cause: Throwable? = null,
    ) : MiruError
}
