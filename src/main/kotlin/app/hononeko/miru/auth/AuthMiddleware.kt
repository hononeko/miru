package app.hononeko.miru.auth

import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.respondError
import arrow.core.Either
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

const val SESSION_COOKIE_NAME = "miru_session"

val MiruPrincipalKey = AttributeKey<MiruPrincipal>("MiruPrincipal")

fun ApplicationCall.miruPrincipalOrNull(): MiruPrincipal? =
    attributes.getOrNull(MiruPrincipalKey)

fun ApplicationCall.requireMiruPrincipal(): Either<MiruError.Unauthorized, MiruPrincipal> =
    miruPrincipalOrNull()?.let { Either.Right(it) }
        ?: Either.Left(MiruError.Unauthorized("Authentication required"))

class MiruAuthPluginConfig {
    var authService: AuthService? = null
    var authMode: AuthMode = AuthMode.LOCAL
    var requiredRole: MiruRole? = null
}

private val MiruAuthPlugin =
    createRouteScopedPlugin(
        name = "MiruAuthPlugin",
        createConfiguration = ::MiruAuthPluginConfig,
    ) {
        val authService = requireNotNull(pluginConfig.authService) { "authService must be configured" }
        val authMode = pluginConfig.authMode
        val requiredRole = pluginConfig.requiredRole

        onCall { call ->
            if (authMode == AuthMode.NONE) {
                call.attributes.put(
                    MiruPrincipalKey,
                    MiruPrincipal(
                        accountId = "dev-admin",
                        username = "local-dev",
                        role = MiruRole.ADMIN,
                        authSource = AuthSource.DEV_BYPASS,
                        authProvider = "NONE",
                    ),
                )
                return@onCall
            }

            val principal =
                resolvePrincipal(call, authService).fold(
                    ifLeft = { error ->
                        call.respondError(error)
                        return@onCall
                    },
                    ifRight = { it },
                )

            if (requiredRole == MiruRole.ADMIN && !principal.isAdmin()) {
                call.respondError(MiruError.Forbidden("Administrator role is required"))
                return@onCall
            }

            call.attributes.put(MiruPrincipalKey, principal)
        }
    }

private class TransparentAuthRouteSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
}

fun Route.authenticated(
    authService: AuthService,
    authMode: AuthMode,
    requiredRole: MiruRole? = null,
    build: Route.() -> Unit,
): Route {
    val protectedRoute = createChild(TransparentAuthRouteSelector())
    protectedRoute.install(MiruAuthPlugin) {
        this.authService = authService
        this.authMode = authMode
        this.requiredRole = requiredRole
    }
    protectedRoute.build()
    return protectedRoute
}

private suspend fun resolvePrincipal(
    call: ApplicationCall,
    authService: AuthService,
): Either<MiruError, MiruPrincipal> {
    val apiKeyHeader = call.request.headers["X-Api-Key"]?.trim()?.takeIf { it.isNotEmpty() }
    if (apiKeyHeader != null) {
        return authService.authenticateApiKey(apiKeyHeader)
    }

    val authHeader = call.request.headers[HttpHeaders.Authorization]?.trim()?.takeIf { it.isNotEmpty() }
    if (authHeader != null) {
        val bearerToken = extractBearerToken(authHeader)
            ?: return Either.Left(MiruError.Unauthorized("Invalid Authorization header"))
        return authService.authenticateApiKey(bearerToken)
    }

    val sessionCookie = call.request.cookies[SESSION_COOKIE_NAME]?.trim()?.takeIf { it.isNotEmpty() }
    if (sessionCookie != null) {
        return authService.authenticateCookie(sessionCookie)
    }

    return Either.Left(MiruError.Unauthorized("Authentication required"))
}

private fun extractBearerToken(headerValue: String): String? {
    val prefix = "Bearer "
    if (!headerValue.startsWith(prefix, ignoreCase = true)) {
        return null
    }
    return headerValue.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}
