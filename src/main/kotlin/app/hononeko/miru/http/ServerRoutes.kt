package app.hononeko.miru.http

import app.hononeko.miru.adapter.MediaServerRegistry
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.service.MediaServerManagementService
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.serverRoutes(
    mediaServerRegistry: MediaServerRegistry,
    authService: AuthService,
    authMode: AuthMode,
    mediaServerManagementService: MediaServerManagementService? = null,
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

        post("/api/v1/admin/servers/{id}/disconnect") {
            val service =
                mediaServerManagementService
                    ?: return@post call.respondError(MiruError.InternalError("Server management service is not configured"))
            val result =
                either {
                    val principal = call.requireMiruPrincipal().bind()
                    service.disconnectServer(principal, call.parameters["id"].orEmpty()).bind()
                }
            call.respondEither(result)
        }

        delete("/api/v1/admin/servers/{id}") {
            val service =
                mediaServerManagementService
                    ?: return@delete call.respondError(MiruError.InternalError("Server management service is not configured"))
            val result =
                either {
                    val principal = call.requireMiruPrincipal().bind()
                    service.removeServer(principal, call.parameters["id"].orEmpty()).bind()
                }
            call.respondEither(result)
        }
    }
}
