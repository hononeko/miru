package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val DEFAULT_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60

fun Route.authRoutes(
    authService: AuthService,
    authMode: AuthMode,
    sessionMaxAgeSeconds: Int = DEFAULT_COOKIE_MAX_AGE_SECONDS,
) {
    route("/api/v1/auth") {
        publicAuthRoutes(authService = authService, authMode = authMode, sessionMaxAgeSeconds = sessionMaxAgeSeconds)
        authenticated(authService = authService, authMode = authMode) {
            get("/me") {
                val result = call.requireMiruPrincipal().map { it.toResponse() }
                call.respondEither(result)
            }
        }
    }

    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/admin") {
            adminAccountRoutes(authService = authService)
            adminApiKeyRoutes(authService = authService)
        }
    }
}

private fun Route.publicAuthRoutes(
    authService: AuthService,
    authMode: AuthMode,
    sessionMaxAgeSeconds: Int,
) {
    get("/status") {
        val result =
            authService.isSetupRequired().map { setupRequired ->
                AuthStatusResponse(
                    authMode = authMode.name.lowercase(),
                    setupRequired = setupRequired,
                )
            }
        call.respondEither(result)
    }

    post("/setup") {
        val request = call.receive<SetupAdminRequest>()
        val result =
            authService.setupInitialAdmin(
                username = request.username,
                password = request.password,
                email = request.email,
            )
        result.fold(
            ifLeft = { call.respondError(it) },
            ifRight = { loginResult ->
                call.appendSessionCookie(loginResult.signedToken, sessionMaxAgeSeconds)
                call.respond(HttpStatusCode.Created, loginResult.principal.toResponse())
            },
        )
    }

    post("/login") {
        val request = call.receive<LoginRequest>()
        val result = authService.login(username = request.username, password = request.password)
        result.fold(
            ifLeft = { call.respondError(it) },
            ifRight = { loginResult ->
                call.appendSessionCookie(loginResult.signedToken, sessionMaxAgeSeconds)
                call.respond(HttpStatusCode.OK, loginResult.principal.toResponse())
            },
        )
    }

    post("/logout") {
        val signedToken = call.request.cookies[SESSION_COOKIE_NAME]
        authService.logout(signedToken).fold(
            ifLeft = { call.respondError(it) },
            ifRight = {
                call.clearSessionCookie()
                call.respond(HttpStatusCode.NoContent)
            },
        )
    }
}

private fun Route.adminAccountRoutes(authService: AuthService) {
    route("/accounts") {
        get {
            val result =
                authService.listAccounts().map { accounts ->
                    AccountListResponse(accounts = accounts.map { it.toResponse() })
                }
            call.respondEither(result)
        }

        post {
            val request = call.receive<CreateAccountRequest>()
            val result =
                authService
                    .createAccount(
                        username = request.username,
                        password = request.password,
                        email = request.email,
                        role = request.role,
                        linkedMediaUserIds = request.linkedMediaUserIds,
                    ).map { it.toResponse() }
            call.respondEither(result, HttpStatusCode.Created)
        }

        delete("/{id}") {
            val targetId = call.parameters["id"].orEmpty()
            val result: Either<MiruError, Unit> =
                either {
                    val acting = call.requireMiruPrincipal().bind()
                    authService.deleteAccount(id = targetId, actingAccountId = acting.accountId).bind()
                }
            result.fold(
                ifLeft = { call.respondError(it) },
                ifRight = { call.respond(HttpStatusCode.NoContent) },
            )
        }

        put("/{id}/media-users") {
            val targetId = call.parameters["id"].orEmpty()
            val request = call.receive<SetLinkedMediaUsersRequest>()
            val result =
                authService
                    .setLinkedMediaUsers(
                        accountId = targetId,
                        mediaUserIds = request.mediaUserIds,
                    ).map { it.toResponse() }
            call.respondEither(result)
        }
    }
}

private fun Route.adminApiKeyRoutes(authService: AuthService) {
    route("/api-keys") {
        get {
            val accountId = call.request.queryParameters["accountId"]?.takeIf { it.isNotBlank() }
            val result =
                authService.listApiKeys(accountId).map { keys ->
                    ApiKeyListResponse(apiKeys = keys.map { it.toResponse() })
                }
            call.respondEither(result)
        }

        post {
            val request = call.receive<CreateApiKeyRequest>()
            val result: Either<MiruError, CreateApiKeyResponse> =
                either {
                    val acting = call.requireMiruPrincipal().bind()
                    val targetAccountId =
                        request.accountId?.takeIf { it.isNotBlank() } ?: acting.accountId
                    val created =
                        authService
                            .createApiKey(
                                accountId = targetAccountId,
                                name = request.name,
                                role = request.role,
                                expiresAtMs = request.expiresAtMs,
                            ).bind()
                    CreateApiKeyResponse(
                        apiKey = created.record.toResponse(),
                        rawKey = created.rawKey,
                    )
                }
            call.respondEither(result, HttpStatusCode.Created)
        }

        delete("/{id}") {
            val targetId = call.parameters["id"].orEmpty()
            authService.revokeApiKey(targetId).fold(
                ifLeft = { call.respondError(it) },
                ifRight = { call.respond(HttpStatusCode.NoContent) },
            )
        }
    }
}

private fun ApplicationCall.appendSessionCookie(
    value: String,
    maxAgeSeconds: Int,
) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = value,
            maxAge = maxAgeSeconds,
            path = "/",
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.clearSessionCookie() {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = "",
            maxAge = 0,
            path = "/",
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}
