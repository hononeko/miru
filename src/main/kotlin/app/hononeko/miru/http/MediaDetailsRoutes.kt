package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.service.MediaDetailsService
import arrow.core.raise.either
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.mediaDetailsRoutes(
    mediaDetailsService: MediaDetailsService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode) {
        route("/api/v1/media") {
            get("/{ratingKey}") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val ratingKey = call.parameters["ratingKey"].orEmpty()
                        val serverId = call.request.queryParameters["serverId"]
                        mediaDetailsService
                            .getMediaDetails(
                                principal = principal,
                                ratingKey = ratingKey,
                                serverId = serverId,
                            ).bind()
                    }
                call.respondEither(result)
            }
        }
    }
}
