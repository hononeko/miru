package app.hononeko.miru.http

import app.hononeko.miru.adapter.MediaServerRegistry
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.config.AuthMode
import arrow.core.Either
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.serverRoutes(
    mediaServerRegistry: MediaServerRegistry,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode) {
        get("/api/v1/servers") {
            call.respondEither(Either.Right(mediaServerRegistry.listPublicServers()))
        }
    }

    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        get("/api/v1/admin/servers") {
            call.respondEither(Either.Right(mediaServerRegistry.listAdminServers()))
        }
    }
}
