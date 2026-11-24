package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.oidc.OidcAuthService
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.CookieSecureMode
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val DEFAULT_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60
private const val OIDC_TX_COOKIE_NAME = "miru_oidc_tx"
private const val OIDC_HINT_COOKIE_NAME = "miru_oidc_hint"
private const val OIDC_COOKIE_PATH = "/api/v1/auth/oidc"
private const val OIDC_TX_COOKIE_MAX_AGE_SECONDS = 600

fun Route.authRoutes(
    authService: AuthService,
    authMode: AuthMode,
    sessionMaxAgeSeconds: Int = DEFAULT_COOKIE_MAX_AGE_SECONDS,
    cookieSecureMode: CookieSecureMode = CookieSecureMode.AUTO,
    oidcAuthService: OidcAuthService? = null,
) {
    route("/api/v1/auth") {
        publicAuthRoutes(
            authService = authService,
            sessionMaxAgeSeconds = sessionMaxAgeSeconds,
            cookieSecureMode = cookieSecureMode,
            oidcAuthService = oidcAuthService,
        )
        authenticated(authService = authService, authMode = authMode) {
            get("/me") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val avatarUrl = authService.resolveAvatarUrl(principal).bind()
                        principal.toResponse(avatarUrl = avatarUrl)
                    }
                call.respondEither(result)
            }

            get("/me/preferences") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        authService.getPreferences(principal).bind()
                    }
                call.respondEither(result)
            }

            put("/me/preferences") {
                val request = call.receive<UpdateUserPreferencesRequest>()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        authService.updatePreferences(principal, request).bind()
                    }
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
    sessionMaxAgeSeconds: Int,
    cookieSecureMode: CookieSecureMode,
    oidcAuthService: OidcAuthService?,
) {
    get("/status") {
        val result =
            authService.getAuthProviders().map { providers ->
                AuthStatusResponse(
                    authMode = providers.authMode,
                    setupRequired = providers.setupRequired,
                    localEnabled = providers.localEnabled,
                    oidcEnabled = providers.oidcEnabled,
                    oidcDisplayName = if (providers.oidcEnabled) providers.oidcDisplayName else null,
                )
            }
        call.respondEither(result)
    }

    get("/providers") {
        val result = authService.getAuthProviders().map { it.toResponse() }
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
                val secure = call.shouldUseSecureCookies(cookieSecureMode)
                call.appendSessionCookie(loginResult.signedToken, sessionMaxAgeSeconds, secure = secure)
                call.respond(HttpStatusCode.Created, loginResult.principal.toResponse())
            },
        )
    }

    post("/login") {
        val request = call.receive<LoginRequest>()
        val result =
            either {
                val loginResult = authService.login(username = request.username, password = request.password).bind()
                val avatarUrl = authService.resolveAvatarUrl(loginResult.principal).bind()
                loginResult to loginResult.principal.toResponse(avatarUrl = avatarUrl)
            }
        result.fold(
            ifLeft = { call.respondError(it) },
            ifRight = { (loginResult, response) ->
                val secure = call.shouldUseSecureCookies(cookieSecureMode)
                call.appendSessionCookie(loginResult.signedToken, sessionMaxAgeSeconds, secure = secure)
                call.respond(HttpStatusCode.OK, response)
            },
        )
    }

    post("/logout") {
        val signedToken = call.request.cookies[SESSION_COOKIE_NAME]
        authService.logout(signedToken).fold(
            ifLeft = { call.respondError(it) },
            ifRight = {
                val secure = call.shouldUseSecureCookies(cookieSecureMode)
                call.clearSessionCookie(secure = secure)
                call.clearOidcHintCookie(secure = secure)
                call.respond(HttpStatusCode.NoContent)
            },
        )
    }

    oidcRoutes(
        authService = authService,
        oidcAuthService = oidcAuthService,
        sessionMaxAgeSeconds = sessionMaxAgeSeconds,
        cookieSecureMode = cookieSecureMode,
    )
}

private fun Route.oidcRoutes(
    authService: AuthService,
    oidcAuthService: OidcAuthService?,
    sessionMaxAgeSeconds: Int,
    cookieSecureMode: CookieSecureMode,
) {
    route("/oidc") {
        get("/login") {
            if (oidcAuthService == null) {
                call.respondError(MiruError.Conflict("OIDC authentication is disabled"))
                return@get
            }
            oidcAuthService.startAuthorization().fold(
                ifLeft = { call.respondError(it) },
                ifRight = { start ->
                    val secure = call.shouldUseSecureCookies(cookieSecureMode)
                    call.appendOidcTxCookie(start.txCookieValue, secure = secure)
                    call.respondRedirect(start.authorizationUrl, permanent = false)
                },
            )
        }

        get("/callback") {
            if (oidcAuthService == null) {
                call.respondOidcFailure(MiruError.Conflict("OIDC authentication is disabled"))
                return@get
            }

            val code = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]
            val errorParam = call.request.queryParameters["error"]
            val errorDescription = call.request.queryParameters["error_description"]
            val txCookie = call.request.cookies[OIDC_TX_COOKIE_NAME]
            val secure = call.shouldUseSecureCookies(cookieSecureMode)

            val result =
                oidcAuthService.handleCallback(
                    code = code,
                    state = state,
                    txCookie = txCookie,
                    errorParam = errorParam,
                    errorDescription = errorDescription,
                )
            call.clearOidcTxCookie(secure = secure)

            result.fold(
                ifLeft = { err -> call.respondOidcFailure(err) },
                ifRight = { callbackResult ->
                    call.appendSessionCookie(callbackResult.loginResult.signedToken, sessionMaxAgeSeconds, secure = secure)
                    val hint = callbackResult.idTokenHint
                    if (!hint.isNullOrBlank() && oidcAuthService.hasEndSessionEndpoint()) {
                        call.appendOidcHintCookie(hint, sessionMaxAgeSeconds, secure = secure)
                    }
                    call.respondRedirect("/", permanent = false)
                },
            )
        }

        get("/logout") {
            val signedToken = call.request.cookies[SESSION_COOKIE_NAME]
            val idTokenHint = call.request.cookies[OIDC_HINT_COOKIE_NAME]
            val secure = call.shouldUseSecureCookies(cookieSecureMode)
            authService.logout(signedToken)
            call.clearSessionCookie(secure = secure)
            call.clearOidcHintCookie(secure = secure)
            val endSessionUrl = oidcAuthService?.buildEndSessionUrl(idTokenHint)
            call.respondRedirect(endSessionUrl ?: "/login", permanent = false)
        }
    }
}

private fun Route.adminAccountRoutes(authService: AuthService) {
    route("/accounts") {
        get {
            val result =
                authService.listAccounts().map { accounts ->
                    AccountListResponse(
                        accounts = accounts.map { it.toResponse() },
                        emailLinkingEnabled = authService.emailLinkingEnabled,
                    )
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

        put("/{id}/media-user-link-mode") {
            val targetId = call.parameters["id"].orEmpty()
            val request = call.receive<SetMediaUserLinkModeRequest>()
            val result =
                authService
                    .setMediaUserLinkMode(
                        accountId = targetId,
                        mode = request.mode,
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
                                expiresIn = request.expiresIn,
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

internal fun ApplicationCall.shouldUseSecureCookies(mode: CookieSecureMode): Boolean =
    when (mode) {
        CookieSecureMode.ALWAYS -> true
        CookieSecureMode.NEVER -> false
        CookieSecureMode.AUTO ->
            request.local.scheme.equals("https", ignoreCase = true) || isForwardedHttps(this)
    }

private fun isForwardedHttps(call: ApplicationCall): Boolean {
    val xForwardedProto =
        call.request.headers["X-Forwarded-Proto"]
            ?.substringBefore(',')
            ?.trim()
    if (xForwardedProto.equals("https", ignoreCase = true)) {
        return true
    }

    val forwardedFirst =
        call.request.headers[HttpHeaders.Forwarded]
            ?.substringBefore(',')
            ?: return false
    return forwardedFirst
        .split(';')
        .any { param ->
            val parts = param.split('=', limit = 2)
            parts.size == 2 &&
                parts[0].trim().equals("proto", ignoreCase = true) &&
                parts[1].trim().trim('"').equals("https", ignoreCase = true)
        }
}

private fun ApplicationCall.appendSessionCookie(
    value: String,
    maxAgeSeconds: Int,
    secure: Boolean,
) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = value,
            maxAge = maxAgeSeconds,
            path = "/",
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.clearSessionCookie(secure: Boolean) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = "",
            maxAge = 0,
            path = "/",
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.appendOidcTxCookie(
    value: String,
    secure: Boolean,
) {
    response.cookies.append(
        Cookie(
            name = OIDC_TX_COOKIE_NAME,
            value = value,
            maxAge = OIDC_TX_COOKIE_MAX_AGE_SECONDS,
            path = OIDC_COOKIE_PATH,
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.clearOidcTxCookie(secure: Boolean) {
    response.cookies.append(
        Cookie(
            name = OIDC_TX_COOKIE_NAME,
            value = "",
            maxAge = 0,
            path = OIDC_COOKIE_PATH,
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.appendOidcHintCookie(
    value: String,
    maxAgeSeconds: Int,
    secure: Boolean,
) {
    response.cookies.append(
        Cookie(
            name = OIDC_HINT_COOKIE_NAME,
            value = value,
            maxAge = maxAgeSeconds,
            path = OIDC_COOKIE_PATH,
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private fun ApplicationCall.clearOidcHintCookie(secure: Boolean) {
    response.cookies.append(
        Cookie(
            name = OIDC_HINT_COOKIE_NAME,
            value = "",
            maxAge = 0,
            path = OIDC_COOKIE_PATH,
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private suspend fun ApplicationCall.respondOidcFailure(error: MiruError) {
    val accept = request.headers[HttpHeaders.Accept].orEmpty()
    if (accept.contains("text/html", ignoreCase = true)) {
        val code =
            when (error) {
                is MiruError.Forbidden -> "forbidden"
                is MiruError.Unauthorized ->
                    if (error.message.contains("disabled", ignoreCase = true)) {
                        "disabled"
                    } else {
                        "invalid_state"
                    }
                else -> "oidc_error"
            }
        respondRedirect("/login?error=$code", permanent = false)
    } else {
        respondError(error)
    }
}
