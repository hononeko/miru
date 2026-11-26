package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.TerminationAuditAction
import app.hononeko.miru.persistence.model.TerminationAuditFilter
import app.hononeko.miru.persistence.model.TerminationAuditSource
import app.hononeko.miru.service.StreamControlService
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException

private const val DEFAULT_AUDIT_LIMIT = 25

fun Route.streamControlRoutes(
    streamControlService: StreamControlService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        post("/api/v1/streams/{sessionId}/terminate") {
            val sessionId = call.parameters["sessionId"].orEmpty()
            val request =
                call.receiveOptionalTerminateRequest().fold(
                    ifLeft = { error ->
                        call.respondError(error)
                        return@post
                    },
                    ifRight = { it },
                )
            val serverId = call.request.queryParameters["serverId"]
            val result =
                either {
                    val principal = call.requireMiruPrincipal().bind()
                    streamControlService.terminateStream(principal, sessionId, request.reason, serverId = serverId).bind()
                }
            call.respondEither(result)
        }

        route("/api/v1/admin/guardrails") {
            get {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        streamControlService.listGuardrails(principal).bind()
                    }
                call.respondEither(result)
            }

            get("/audit") {
                val filter =
                    parseAuditFilter(call.request.queryParameters).fold(
                        ifLeft = { error ->
                            call.respondError(error)
                            return@get
                        },
                        ifRight = { it },
                    )
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        streamControlService.listAuditLog(principal, filter).bind()
                    }
                call.respondEither(result)
            }

            put("/{ruleType}") {
                val ruleType = call.parameters["ruleType"].orEmpty()
                val request = call.receive<UpdateGuardrailRuleRequest>()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        streamControlService.updateGuardrail(principal, ruleType, request).bind()
                    }
                call.respondEither(result)
            }
        }
    }
}

private suspend fun ApplicationCall.receiveOptionalTerminateRequest(): Either<MiruError.ValidationError, TerminateStreamRequest> {
    val raw = receiveText()
    if (raw.isBlank()) {
        return Either.Right(TerminateStreamRequest())
    }
    return try {
        Either.Right(MiruJson.decodeFromString<TerminateStreamRequest>(raw))
    } catch (_: SerializationException) {
        Either.Left(MiruError.ValidationError("Malformed request payload"))
    } catch (_: IllegalArgumentException) {
        Either.Left(MiruError.ValidationError("Malformed request payload"))
    }
}

private fun parseAuditFilter(params: Parameters): Either<MiruError.ValidationError, TerminationAuditFilter> =
    either {
        val sourceRaw = params["source"]?.trim()?.takeIf { it.isNotEmpty() }
        val source =
            if (sourceRaw == null) {
                null
            } else {
                TerminationAuditSource.entries.firstOrNull { it.name.equals(sourceRaw, ignoreCase = true) }
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid audit source filter '$sourceRaw'",
                            fieldErrors = mapOf("source" to "must be MANUAL or GUARDRAIL"),
                        ),
                    )
            }

        val actionRaw = params["action"]?.trim()?.takeIf { it.isNotEmpty() }
        val action =
            if (actionRaw == null) {
                null
            } else {
                TerminationAuditAction.entries.firstOrNull { it.name.equals(actionRaw, ignoreCase = true) }
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid audit action filter '$actionRaw'",
                            fieldErrors = mapOf("action" to "must be TERMINATED, WARNED, or TERMINATE_FAILED"),
                        ),
                    )
            }

        val limitRaw = params["limit"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit =
            if (limitRaw == null) {
                DEFAULT_AUDIT_LIMIT
            } else {
                limitRaw.toIntOrNull()
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid limit query parameter",
                            fieldErrors = mapOf("limit" to "must be an integer"),
                        ),
                    )
            }

        val offsetRaw = params["offset"]?.trim()?.takeIf { it.isNotEmpty() }
        val offset =
            if (offsetRaw == null) {
                0
            } else {
                offsetRaw.toIntOrNull()
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid offset query parameter",
                            fieldErrors = mapOf("offset" to "must be an integer"),
                        ),
                    )
            }

        TerminationAuditFilter(
            source = source,
            action = action,
            limit = limit,
            offset = offset,
        )
    }
