package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.WebhookDeliveryFilter
import app.hononeko.miru.persistence.model.WebhookDeliveryStatus
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.service.WebhookService
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val VALID_DELIVERY_EVENT_TYPES: Set<String> =
    (WebhookEventType.allKeys + WebhookEventType.TEST_EVENT_KEY).toSet()

fun Route.webhookRoutes(
    webhookService: WebhookService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/admin/webhooks") {
            get {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        webhookService.listTargets(principal).bind()
                    }
                call.respondEither(result)
            }

            post {
                val request = call.receive<CreateWebhookTargetRequest>()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        webhookService.createTarget(principal, request).bind()
                    }
                call.respondEither(result, successStatus = HttpStatusCode.Created)
            }

            get("/deliveries") {
                val filter =
                    parseDeliveryFilter(call.request.queryParameters).fold(
                        ifLeft = { error ->
                            call.respondError(error)
                            return@get
                        },
                        ifRight = { it },
                    )
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        webhookService.listDeliveries(principal, filter).bind()
                    }
                call.respondEither(result)
            }

            patch("/{id}") {
                val id = call.parameters["id"].orEmpty()
                val request = call.receive<UpdateWebhookTargetRequest>()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        webhookService.updateTarget(principal, id, request).bind()
                    }
                call.respondEither(result)
            }

            delete("/{id}") {
                val id = call.parameters["id"].orEmpty()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        webhookService.deleteTarget(principal, id).bind()
                    }
                result.fold(
                    ifLeft = { error -> call.respondError(error) },
                    ifRight = { call.respond(HttpStatusCode.NoContent) },
                )
            }

            post("/{id}/test") {
                val id = call.parameters["id"].orEmpty()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        webhookService.testTarget(principal, id).bind()
                    }
                call.respondEither(result)
            }
        }
    }
}

private fun parseDeliveryFilter(params: Parameters): Either<MiruError.ValidationError, WebhookDeliveryFilter> =
    either {
        val targetId = params["targetId"]?.trim()?.takeIf { it.isNotEmpty() }

        val eventTypeRaw = params["eventType"]?.trim()?.takeIf { it.isNotEmpty() }
        val eventType =
            if (eventTypeRaw == null) {
                null
            } else {
                val normalized = eventTypeRaw.lowercase()
                if (normalized in VALID_DELIVERY_EVENT_TYPES) {
                    normalized
                } else {
                    raise(
                        MiruError.ValidationError(
                            message = "Invalid delivery eventType filter '$eventTypeRaw'",
                            fieldErrors =
                                mapOf(
                                    "eventType" to "must be one of: ${VALID_DELIVERY_EVENT_TYPES.sorted().joinToString()}",
                                ),
                        ),
                    )
                }
            }

        val statusRaw = params["status"]?.trim()?.takeIf { it.isNotEmpty() }
        val status =
            if (statusRaw == null) {
                null
            } else {
                WebhookDeliveryStatus.entries.firstOrNull { it.name.equals(statusRaw, ignoreCase = true) }
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Invalid delivery status filter '$statusRaw'",
                            fieldErrors = mapOf("status" to "must be DELIVERED, RETRYING, or FAILED"),
                        ),
                    )
            }

        val limitRaw = params["limit"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit =
            if (limitRaw == null) {
                WebhookDeliveryFilter.DEFAULT_LIMIT
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

        WebhookDeliveryFilter(
            targetId = targetId,
            eventType = eventType,
            status = status,
            limit = limit,
            offset = offset,
        )
    }
