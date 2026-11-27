package app.hononeko.miru.http

import app.hononeko.miru.artwork.AvatarImageValidator
import app.hononeko.miru.auth.AuthRateLimiter
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.ProfileImportSources
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.oidc.OidcAuthService
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.CookieSecureMode
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

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
    authRateLimiter: AuthRateLimiter = AuthRateLimiter(),
    trustProxyHeaders: Boolean = false,
    oidcAuthService: OidcAuthService? = null,
    profileImport: ProfileImportSources? = null,
) {
    route("/api/v1/auth") {
        publicAuthRoutes(
            authService = authService,
            sessionMaxAgeSeconds = sessionMaxAgeSeconds,
            cookieSecureMode = cookieSecureMode,
            authRateLimiter = authRateLimiter,
            trustProxyHeaders = trustProxyHeaders,
            oidcAuthService = oidcAuthService,
        )
        authenticated(authService = authService, authMode = authMode) {
            get("/me") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        authService.resolvePrincipalResponse(principal).bind()
                    }
                call.respondEither(result)
            }

            patch("/me/profile") {
                val request = call.receive<UpdateProfileRequest>()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        authService.updateOwnProfile(principal = principal, request = request).bind()
                    }
                call.respondEither(result)
            }

            put("/me/avatar") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val bytes = call.receiveBoundedAvatarBytes().bind()
                        val declaredContentType = call.request.headers[HttpHeaders.ContentType]
                        authService
                            .uploadOwnAvatar(
                                principal = principal,
                                bytes = bytes,
                                declaredContentType = declaredContentType,
                            ).bind()
                    }
                call.respondEither(result)
            }

            delete("/me/avatar") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        authService.deleteOwnAvatar(principal).bind()
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

            post("/me/password") {
                val request = call.receive<ChangePasswordRequest>()
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        authService
                            .changeOwnPassword(
                                principal = principal,
                                currentPassword = request.currentPassword,
                                newPassword = request.newPassword,
                            ).bind()
                    }
                result.fold(
                    ifLeft = { call.respondError(it) },
                    ifRight = { rotatedLogin ->
                        if (rotatedLogin != null) {
                            val secure = call.shouldUseSecureCookies(cookieSecureMode)
                            call.appendSessionCookie(rotatedLogin.signedToken, sessionMaxAgeSeconds, secure = secure)
                        }
                        call.respond(HttpStatusCode.NoContent)
                    },
                )
            }
        }
    }

    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/admin") {
            adminAccountRoutes(authService = authService, profileImport = profileImport)
            adminApiKeyRoutes(authService = authService)
        }
    }
}

private fun Route.publicAuthRoutes(
    authService: AuthService,
    sessionMaxAgeSeconds: Int,
    cookieSecureMode: CookieSecureMode,
    authRateLimiter: AuthRateLimiter,
    trustProxyHeaders: Boolean,
    oidcAuthService: OidcAuthService?,
) {
    get("/providers") {
        val result = authService.getAuthProviders().map { it.toResponse() }
        call.respondEither(result)
    }

    post("/setup") {
        val clientIp = AuthRateLimiter.resolveClientIp(call, trustProxyHeaders)
        authRateLimiter.checkAndConsumeSetupAttempt(clientIp).onLeft { err ->
            call.respondError(err)
            return@post
        }

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
        val clientIp = AuthRateLimiter.resolveClientIp(call, trustProxyHeaders)
        authRateLimiter.checkAndConsumeLoginIpAttempt(clientIp).onLeft { err ->
            call.respondError(err)
            return@post
        }

        val request = call.receive<LoginRequest>()
        val normalizedUsername = request.username.trim().lowercase()
        authRateLimiter.checkLoginAccountAllowed(normalizedUsername).onLeft { err ->
            call.respondError(err)
            return@post
        }

        val result =
            either {
                val loginResult = authService.login(username = request.username, password = request.password).bind()
                val response = authService.resolvePrincipalResponse(loginResult.principal).bind()
                loginResult to response
            }
        result.fold(
            ifLeft = { err ->
                if (err is MiruError.Unauthorized) {
                    authRateLimiter.recordLoginFailure(normalizedUsername)
                }
                call.respondError(err)
            },
            ifRight = { (loginResult, response) ->
                authRateLimiter.recordLoginSuccess(normalizedUsername)
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

private fun Route.adminAccountRoutes(
    authService: AuthService,
    profileImport: ProfileImportSources? = null,
) {
    route("/accounts") {
        get {
            val result =
                authService.listAccountResponses().map { accounts ->
                    AccountListResponse(
                        accounts = accounts,
                        emailLinkingEnabled = authService.emailLinkingEnabled,
                    )
                }
            call.respondEither(result)
        }

        post {
            val request = call.receive<CreateAccountRequest>()
            val result =
                either {
                    val created =
                        authService
                            .createAccount(
                                username = request.username,
                                password = request.password,
                                email = request.email,
                                role = request.role,
                                linkedMediaUserIds = request.linkedMediaUserIds,
                                displayName = request.displayName,
                            ).bind()
                    authService.toAccountResponse(created).bind()
                }
            call.respondEither(result, HttpStatusCode.Created)
        }

        patch("/{id}") {
            val targetId = call.parameters["id"].orEmpty()
            val request = call.receive<UpdateAccountRequest>()
            val result =
                either {
                    val acting = call.requireMiruPrincipal().bind()
                    val updated =
                        authService
                            .updateAccount(
                                id = targetId,
                                actingAccountId = acting.accountId,
                                role = request.role,
                                roleMode = request.roleMode,
                                disabled = request.disabled,
                                password = request.password,
                                displayName = request.displayName,
                                clearDisplayName = request.clearDisplayName,
                                clearAvatar = request.clearAvatar,
                                importDisplayNameFromServerId = request.importDisplayNameFromServerId,
                                importAvatarFromServerId = request.importAvatarFromServerId,
                                profileImport = profileImport,
                            ).bind()
                    authService.toAccountResponse(updated).bind()
                }
            call.respondEither(result)
        }

        put("/{id}/avatar") {
            val targetId = call.parameters["id"].orEmpty()
            val result =
                either {
                    val bytes = call.receiveBoundedAvatarBytes().bind()
                    val declaredContentType = call.request.headers[HttpHeaders.ContentType]
                    val updated =
                        authService
                            .uploadAccountAvatar(
                                id = targetId,
                                bytes = bytes,
                                declaredContentType = declaredContentType,
                            ).bind()
                    authService.toAccountResponse(updated).bind()
                }
            call.respondEither(result)
        }

        delete("/{id}/avatar") {
            val targetId = call.parameters["id"].orEmpty()
            val result =
                either {
                    val updated = authService.deleteAccountAvatar(targetId).bind()
                    authService.toAccountResponse(updated).bind()
                }
            call.respondEither(result)
        }

        adminAccountLifecycleAndLinkRoutes(authService)
    }
}

private fun Route.adminAccountLifecycleAndLinkRoutes(authService: AuthService) {
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
            either {
                val updated =
                    authService
                        .setLinkedMediaUsers(
                            accountId = targetId,
                            mediaUserIds = request.linkedMediaUserIds,
                        ).bind()
                authService.toAccountResponse(updated).bind()
            }
        call.respondEither(result)
    }

    put("/{id}/media-user-link-mode") {
        val targetId = call.parameters["id"].orEmpty()
        val request = call.receive<SetMediaUserLinkModeRequest>()
        val result =
            either {
                val updated =
                    authService
                        .setMediaUserLinkMode(
                            accountId = targetId,
                            mode = request.mode,
                        ).bind()
                authService.toAccountResponse(updated).bind()
            }
        call.respondEither(result)
    }
}

private suspend fun ApplicationCall.receiveBoundedAvatarBytes(): Either<MiruError.ValidationError, ByteArray> =
    either {
        val declaredLength = request.contentLength()
        ensure(declaredLength == null || declaredLength <= AvatarImageValidator.MAX_AVATAR_BYTES) {
            MiruError.ValidationError(
                message = "Avatar image exceeds maximum allowed size of 512 KiB",
                fieldErrors = mapOf("avatar" to "must be at most ${AvatarImageValidator.MAX_AVATAR_BYTES} bytes"),
            )
        }
        val packet = receiveChannel().readBuffer((AvatarImageValidator.MAX_AVATAR_BYTES + 1).toLong())
        val bytes = packet.readByteArray()
        ensure(bytes.size <= AvatarImageValidator.MAX_AVATAR_BYTES) {
            MiruError.ValidationError(
                message = "Avatar image exceeds maximum allowed size of 512 KiB",
                fieldErrors = mapOf("avatar" to "must be at most ${AvatarImageValidator.MAX_AVATAR_BYTES} bytes"),
            )
        }
        bytes
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
