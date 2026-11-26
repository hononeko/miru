package app.hononeko.miru.http

import app.hononeko.miru.adapter.jellyfin.JellyfinQuickConnectService
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.config.AuthMode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.jellyfinQuickConnectRoutes(
    jellyfinQuickConnectService: JellyfinQuickConnectService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/admin/jellyfin") {
            get("/status") {
                call.respondEither(jellyfinQuickConnectService.getConnectionStatus())
            }

            post("/quick-connect") {
                val request = call.receive<JellyfinQuickConnectStartRequest>()
                call.respondEither(jellyfinQuickConnectService.startQuickConnect(request.baseUrl))
            }

            get("/quick-connect/{attemptId}") {
                val attemptId = call.parameters["attemptId"].orEmpty()
                call.respondEither(jellyfinQuickConnectService.pollQuickConnect(attemptId))
            }
        }
    }
}
