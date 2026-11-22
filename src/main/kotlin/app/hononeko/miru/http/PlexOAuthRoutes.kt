package app.hononeko.miru.http

import app.hononeko.miru.adapter.plex.PlexOAuthService
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.plexOAuthRoutes(
    plexOAuthService: PlexOAuthService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/admin/plex") {
            get("/status") {
                call.respondEither(plexOAuthService.getConnectionStatus())
            }

            post("/oauth/start") {
                call.respondEither(plexOAuthService.startOAuth())
            }

            get("/oauth/poll/{pinId}") {
                val pinId = parsePositivePinId(call.parameters["pinId"], "pinId")
                val result =
                    pinId.fold(
                        ifLeft = { Either.Left(it) },
                        ifRight = { id -> plexOAuthService.pollOAuth(id) },
                    )
                call.respondEither(result)
            }

            get("/oauth/servers") {
                val rawPinId = call.request.queryParameters["pinId"]?.takeIf { it.isNotBlank() }
                val result =
                    if (rawPinId == null) {
                        plexOAuthService.discoverServers(pinId = null)
                    } else {
                        parsePositivePinId(rawPinId, "pinId").fold(
                            ifLeft = { Either.Left(it) },
                            ifRight = { id -> plexOAuthService.discoverServers(pinId = id) },
                        )
                    }
                call.respondEither(result)
            }

            post("/oauth/select") {
                val request = call.receive<PlexSelectServerRequest>()
                val result =
                    plexOAuthService.selectServer(
                        pinId = request.pinId,
                        machineId = request.machineId,
                        connectionUri = request.connectionUri,
                    )
                call.respondEither(result)
            }
        }
    }
}

private fun parsePositivePinId(
    raw: String?,
    field: String,
): Either<MiruError.ValidationError, Long> {
    val parsed = raw?.trim()?.toLongOrNull()
    return if (parsed != null && parsed > 0L) {
        Either.Right(parsed)
    } else {
        Either.Left(
            MiruError.ValidationError(
                message = "Invalid $field parameter",
                fieldErrors = mapOf(field to "must be a positive integer"),
            ),
        )
    }
}
