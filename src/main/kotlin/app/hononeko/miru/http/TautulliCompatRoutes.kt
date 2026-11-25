package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.HistorySortDirection
import app.hononeko.miru.persistence.model.HistorySortField
import app.hononeko.miru.service.TautulliCompatService
import app.hononeko.miru.service.TautulliHistoryQuery
import app.hononeko.miru.service.TautulliHomeStatsQuery
import app.hononeko.miru.service.TautulliStat
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val tautulliLogger = LoggerFactory.getLogger("app.hononeko.miru.http.TautulliCompatRoutes")

private const val MAX_DAYS = 36_500
private const val MAX_STATS_COUNT = 10_000
private const val MAX_HISTORY_LENGTH = 1_000
private const val DEFAULT_HISTORY_LENGTH = 25

private val COMMANDS = listOf("arnold", "get_history", "get_home_stats", "get_tautulli_info", "status")

private val EMPTY_DATA = JsonObject(emptyMap())

/** Parameters that Tautulli knows and that would narrow or change the answer: ignoring them would answer something else. */
private val UNSUPPORTED_PARAMETERS =
    mapOf(
        "get_home_stats" to listOf("before", "after"),
        "get_history" to
            listOf(
                "user",
                "parent_rating_key",
                "grandparent_rating_key",
                "start_date",
                "transcode_decision",
                "guid",
                "reference_id",
                "search",
                "json_data",
            ),
        "status" to listOf("check"),
    )

private val HISTORY_SORT_FIELDS =
    mapOf(
        "date" to HistorySortField.STARTED_AT,
        "started" to HistorySortField.STARTED_AT,
        "stopped" to HistorySortField.STOPPED_AT,
        "duration" to HistorySortField.WATCHED_DURATION,
        "full_title" to HistorySortField.TITLE,
    )

private data class TautulliAnswer(
    val data: JsonElement,
    val message: String? = null,
)

/**
 * The part of Tautulli's API v2 that Kometa and similar tools use. Errors have Tautulli's format rather than Problem
 * Details, and the API key may be in the query, so nothing here may log the request URI.
 */
fun Route.tautulliCompatRoutes(
    tautulliCompatService: TautulliCompatService,
    authService: AuthService,
    authMode: AuthMode,
    watchedThresholdPercent: Int,
    appVersion: String,
) {
    get("/api/v2") {
        val params = call.request.queryParameters
        val outcome: Either<MiruError, TautulliAnswer> =
            try {
                either<MiruError, TautulliAnswer> {
                    authenticate(call, params, authService, authMode)
                    val command = requireCommand(params)
                    rejectUnsupported(command, params)
                    when (command) {
                        "get_tautulli_info" -> TautulliAnswer(tautulliInfoJson(appVersion))
                        "get_home_stats" -> homeStats(params, tautulliCompatService)
                        "get_history" -> history(params, tautulliCompatService, watchedThresholdPercent)
                        "status" -> TautulliAnswer(EMPTY_DATA, message = "Ok")
                        else -> TautulliAnswer(JsonPrimitive("Get to the chopper!"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Either.Left(MiruError.InternalError(message = e.message ?: "Unhandled server exception", cause = e))
            }

        outcome.fold(
            ifLeft = { error -> call.respondTautulliError(error) },
            ifRight = { answer -> call.respondTautulli(HttpStatusCode.OK, "success", answer.message, answer.data) },
        )
    }
}

private suspend fun Raise<MiruError>.authenticate(
    call: ApplicationCall,
    params: Parameters,
    authService: AuthService,
    authMode: AuthMode,
) {
    if (authMode == AuthMode.NONE) return
    val apiKey =
        ensureNotNull(params.text("apikey") ?: call.request.headers["X-Api-Key"]?.trim()?.takeIf { it.isNotEmpty() }) {
            MiruError.Unauthorized("Parameter apikey is required or X-Api-Key header is required")
        }
    val principal =
        authService
            .authenticateApiKey(apiKey)
            .mapLeft { error -> if (error is MiruError.Unauthorized) MiruError.Unauthorized("Invalid apikey") else error }
            .bind()
    // Tautulli's key always sees everything, and tools take the answer for all plays
    ensure(principal.isAdmin()) {
        MiruError.Forbidden("The apikey must belong to an administrator")
    }
}

private fun Raise<MiruError>.requireCommand(params: Parameters): String {
    val possible = "Possible commands are: ${COMMANDS.joinToString(", ")}"
    val command = ensureNotNull(params.text("cmd")) { MiruError.ValidationError("Parameter cmd is required. $possible") }
    ensure(command in COMMANDS) { MiruError.ValidationError("Unknown command. $possible") }
    val outType = params.text("out_type")
    ensure(outType == null || outType == "json") { unsupported("out_type") }
    ensure(params.text("callback") == null) { unsupported("callback") }
    return command
}

private fun Raise<MiruError>.rejectUnsupported(
    command: String,
    params: Parameters,
) {
    UNSUPPORTED_PARAMETERS[command].orEmpty().forEach { name ->
        ensure(params.text(name) == null) { unsupported(name) }
    }
}

private suspend fun Raise<MiruError>.homeStats(
    params: Parameters,
    service: TautulliCompatService,
): TautulliAnswer {
    val statId = params.text("stat_id")
    val stats =
        if (statId == null) {
            TautulliStat.entries
        } else {
            val stat =
                ensureNotNull(TautulliStat.entries.firstOrNull { it.id == statId }) {
                    MiruError.ValidationError("stat_id must be one of ${TautulliStat.entries.joinToString(", ") { it.id }}")
                }
            listOf(stat)
        }
    val byDuration =
        when (params.text("stats_type") ?: "plays") {
            "plays", "0" -> false
            "duration", "1" -> true
            else -> raise(MiruError.ValidationError("stats_type must be plays or duration"))
        }
    val query =
        TautulliHomeStatsQuery(
            stats = stats,
            timeRangeDays = int(params, "time_range", default = 30, range = 0..MAX_DAYS),
            start = int(params, "stats_start", default = 0, range = 0..MAX_STATS_COUNT),
            count = int(params, "stats_count", default = 10, range = 0..MAX_STATS_COUNT),
            byDuration = byDuration,
            sectionId = params.text("section_id"),
            userIds = params.list("user_id"),
        )
    return TautulliAnswer(service.homeStats(query).bind().toTautulliJson(byDuration))
}

private suspend fun Raise<MiruError>.history(
    params: Parameters,
    service: TautulliCompatService,
    watchedThresholdPercent: Int,
): TautulliAnswer {
    ensure((params.text("include_activity") ?: "0") in setOf("0", "false")) { unsupported("include_activity") }
    val sortBy =
        ensureNotNull(HISTORY_SORT_FIELDS[params.text("order_column") ?: "date"]) {
            MiruError.ValidationError("order_column must be one of ${HISTORY_SORT_FIELDS.keys.joinToString(", ")}")
        }
    val sortDir =
        when ((params.text("order_dir") ?: "desc").lowercase()) {
            "asc" -> HistorySortDirection.ASC
            "desc" -> HistorySortDirection.DESC
            else -> raise(MiruError.ValidationError("order_dir must be asc or desc"))
        }
    val mediaTypes = params.list("media_type")?.takeUnless { "all" in it }
    ensure(mediaTypes == null || mediaTypes.none { it == "collection" || it == "playlist" }) { unsupported("media_type") }
    val query =
        TautulliHistoryQuery(
            userIds = params.list("user_id"),
            ratingKeys = params.list("rating_key"),
            sectionIds = params.list("section_id"),
            mediaTypes = mediaTypes?.mapTo(LinkedHashSet()) { if (it == "live") "live_tv" else it },
            after = date(params, "after"),
            before = date(params, "before"),
            sortBy = sortBy,
            sortDir = sortDir,
            start = int(params, "start", default = 0, range = 0..Int.MAX_VALUE),
            length = int(params, "length", default = DEFAULT_HISTORY_LENGTH, range = 1..MAX_HISTORY_LENGTH),
        )
    val draw = int(params, "draw", default = 1, range = 0..Int.MAX_VALUE)
    return TautulliAnswer(service.history(query).bind().toTautulliJson(draw, watchedThresholdPercent))
}

private fun Parameters.text(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

private fun Parameters.list(name: String): Set<String>? =
    text(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }

private fun Raise<MiruError>.int(
    params: Parameters,
    name: String,
    default: Int,
    range: IntRange,
): Int {
    val raw = params.text(name) ?: return default
    val value = raw.toIntOrNull()
    ensure(value != null && value in range) {
        val limits = if (range.last == Int.MAX_VALUE) "at least ${range.first}" else "from ${range.first} to ${range.last}"
        MiruError.ValidationError("$name must be a number $limits")
    }
    return value
}

private fun Raise<MiruError>.date(
    params: Parameters,
    name: String,
): LocalDate? {
    val raw = params.text(name) ?: return null
    return try {
        LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        raise(MiruError.ValidationError("$name must be a date like 2026-01-31"))
    }
}

private fun unsupported(parameter: String): MiruError =
    MiruError.ValidationError("Parameter $parameter is not supported by Miru, or not with this value")

private suspend fun ApplicationCall.respondTautulliError(error: MiruError) {
    val message =
        when (error) {
            is MiruError.ValidationError, is MiruError.Unauthorized, is MiruError.Forbidden -> error.message
            else -> {
                tautulliLogger.error("Tautulli API request failed: {}", error.message, (error as? MiruError.InternalError)?.cause)
                error.toProblemDetails().detail
            }
        }
    respondTautulli(error.toHttpStatus(), "error", message, EMPTY_DATA)
}

private suspend fun ApplicationCall.respondTautulli(
    status: HttpStatusCode,
    result: String,
    message: String?,
    data: JsonElement,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(tautulliEnvelope(result, message, data).toString(), ContentType.Application.Json, status)
}
