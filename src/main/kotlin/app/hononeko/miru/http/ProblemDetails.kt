package app.hononeko.miru.http

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val errorLogger = LoggerFactory.getLogger("app.hononeko.miru.http.ErrorMapper")

private val problemJsonFormat =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * RFC 7807 / RFC 9457 Problem Details payload returned for all API errors.
 */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String? = null,
    val errors: Map<String, String>? = null,
)

fun MiruError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is MiruError.ValidationError -> HttpStatusCode.BadRequest
        is MiruError.Unauthorized -> HttpStatusCode.Unauthorized
        is MiruError.Forbidden -> HttpStatusCode.Forbidden
        is MiruError.NotFound -> HttpStatusCode.NotFound
        is MiruError.Conflict -> HttpStatusCode.Conflict
        is MiruError.TooManyRequests -> HttpStatusCode.TooManyRequests
        is MiruError.UpstreamAuthError -> HttpStatusCode.BadGateway
        is MiruError.UpstreamUnavailable -> HttpStatusCode.BadGateway
        is MiruError.PersistenceError -> HttpStatusCode.InternalServerError
        is MiruError.InternalError -> HttpStatusCode.InternalServerError
    }

fun MiruError.toProblemDetails(instance: String? = null): ProblemDetails {
    val status = toHttpStatus()
    return when (this) {
        is MiruError.ValidationError ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/validation",
                title = "Validation Error",
                status = status.value,
                detail = message,
                instance = instance,
                errors = fieldErrors.takeIf { it.isNotEmpty() },
            )

        is MiruError.Unauthorized ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/unauthorized",
                title = "Unauthorized",
                status = status.value,
                detail = message,
                instance = instance,
            )

        is MiruError.Forbidden ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/forbidden",
                title = "Forbidden",
                status = status.value,
                detail = message,
                instance = instance,
            )

        is MiruError.NotFound ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/not-found",
                title = "Not Found",
                status = status.value,
                detail = message,
                instance = instance,
            )

        is MiruError.Conflict ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/conflict",
                title = "Conflict",
                status = status.value,
                detail = message,
                instance = instance,
            )

        is MiruError.TooManyRequests ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/too-many-requests",
                title = "Too Many Requests",
                status = status.value,
                detail = message,
                instance = instance,
            )

        is MiruError.UpstreamAuthError ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/upstream-auth",
                title = "Upstream Authentication Failed",
                status = status.value,
                detail = detail ?: "Authentication with upstream provider '$provider' failed",
                instance = instance,
            )

        is MiruError.UpstreamUnavailable ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/upstream-unavailable",
                title = "Upstream Unavailable",
                status = status.value,
                detail = "Upstream provider '$provider' is currently unavailable",
                instance = instance,
            )

        is MiruError.PersistenceError ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/persistence",
                title = "Internal Server Error",
                status = status.value,
                detail = "A database error occurred while processing the request",
                instance = instance,
            )

        is MiruError.InternalError ->
            ProblemDetails(
                type = "https://miru.hononeko.app/errors/internal",
                title = "Internal Server Error",
                status = status.value,
                detail = "An unexpected internal error occurred",
                instance = instance,
            )
    }
}

suspend fun ApplicationCall.respondError(error: MiruError) {
    val status = error.toHttpStatus()
    when (error) {
        is MiruError.PersistenceError ->
            errorLogger.error("Persistence error during '{}' at {}: {}", error.operation, request.uri, error.message, error.cause)

        is MiruError.InternalError ->
            errorLogger.error("Internal error at {}: {}", request.uri, error.message, error.cause)

        is MiruError.UpstreamUnavailable ->
            errorLogger.warn("Upstream '{}' unavailable at {}: {}", error.provider, request.uri, error.message, error.cause)

        is MiruError.UpstreamAuthError ->
            errorLogger.warn("Upstream '{}' auth failure at {}: {}", error.provider, request.uri, error.message)

        else ->
            errorLogger.debug("Request error ({}) at {}: {}", status.value, request.uri, error.message)
    }

    if (error is MiruError.TooManyRequests) {
        response.header(HttpHeaders.RetryAfter, error.retryAfterSeconds.coerceAtLeast(1L).toString())
    }

    val problem = error.toProblemDetails(instance = request.uri)
    respondText(
        text = problemJsonFormat.encodeToString(problem),
        contentType = ContentType.Application.ProblemJson,
        status = status,
    )
}

suspend inline fun <reified T : Any> ApplicationCall.respondEither(
    result: Either<MiruError, T>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    result.fold(
        ifLeft = { error -> respondError(error) },
        ifRight = { value -> respond(successStatus, value) },
    )
}
