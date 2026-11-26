package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.service.LibraryQueryService
import app.hononeko.miru.service.LibrarySyncTrigger
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val SECTION_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")

fun Route.libraryRoutes(
    libraryQueryService: LibraryQueryService,
    librarySyncTrigger: LibrarySyncTrigger?,
    authService: AuthService,
    authMode: AuthMode,
) {
    route("/api/v1/libraries") {
        authenticated(authService = authService, authMode = authMode) {
            registerLibraryReadRoutes(libraryQueryService)
        }

        authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
            registerLibraryAdminRoutes(libraryQueryService)

            if (librarySyncTrigger != null) {
                post("/sync") {
                    val query = call.request.queryParameters
                    val result =
                        either {
                            val deep = parseOptionalBooleanParam(query, "deep", defaultValue = true).bind()
                            val sectionKey = parseOptionalSectionKeyParam(query, "sectionKey").bind()
                            val serverId = query["serverId"]?.trim()?.takeIf { it.isNotEmpty() }
                            librarySyncTrigger
                                .triggerSync(deep = deep, sectionKey = sectionKey, serverId = serverId)
                                .bind()
                                .toResponse()
                        }
                    call.respondEither(result)
                }
            }
        }
    }
}

private fun Route.registerLibraryReadRoutes(libraryQueryService: LibraryQueryService) {
    get {
        val serverId = call.request.queryParameters["serverId"]
        val result =
            either {
                val principal = call.requireMiruPrincipal().bind()
                val sections =
                    libraryQueryService
                        .listLibraries(principal = principal, serverId = serverId)
                        .bind()
                val serversById = libraryQueryService.loadServersById()
                LibraryListResponse(libraries = sections.map { it.toResponse(serversById[it.serverId]) })
            }
        call.respondEither(result)
    }

    get("/{id}") {
        val sectionId = call.parameters["id"].orEmpty()
        val serverId = call.request.queryParameters["serverId"]
        val result =
            either {
                val principal = call.requireMiruPrincipal().bind()
                val section =
                    libraryQueryService
                        .getLibrarySection(
                            principal = principal,
                            sectionId = sectionId,
                            serverId = serverId,
                        ).bind()
                val serversById = libraryQueryService.loadServersById()
                section.toResponse(serversById[section.serverId])
            }
        call.respondEither(result)
    }

    get("/{id}/stats") {
        val sectionId = call.parameters["id"].orEmpty()
        val query = call.request.queryParameters
        val result =
            either {
                val principal = call.requireMiruPrincipal().bind()
                val fromMs = parseOptionalLongParam(query, "from").bind()
                val toMs = parseOptionalLongParam(query, "to").bind()
                val limit =
                    parseOptionalIntParam(query, "limit", LibraryQueryService.DEFAULT_STATS_LIMIT).bind()
                val serverId = query["serverId"]
                val stats =
                    libraryQueryService
                        .getLibraryStats(
                            principal = principal,
                            sectionId = sectionId,
                            serverId = serverId,
                            fromMs = fromMs,
                            toMs = toMs,
                            limit = limit,
                        ).bind()
                val serversById = libraryQueryService.loadServersById()
                stats.toResponse(serversById[stats.section.serverId])
            }
        call.respondEither(result)
    }

    get("/{id}/recent") {
        val sectionId = call.parameters["id"].orEmpty()
        val query = call.request.queryParameters
        val result =
            either {
                val principal = call.requireMiruPrincipal().bind()
                val limit =
                    parseOptionalIntParam(query, "limit", LibraryQueryService.DEFAULT_RECENT_LIMIT).bind()
                val serverId = query["serverId"]
                val recent =
                    libraryQueryService
                        .getRecentlyAdded(
                            principal = principal,
                            sectionId = sectionId,
                            serverId = serverId,
                            limit = limit,
                        ).bind()
                val serversById = libraryQueryService.loadServersById()
                recent.toResponse(serversById[recent.section.serverId])
            }
        call.respondEither(result)
    }
}

private fun Route.registerLibraryAdminRoutes(libraryQueryService: LibraryQueryService) {
    get("/{id}/stale") {
        val sectionId = call.parameters["id"].orEmpty()
        val query = call.request.queryParameters
        val result =
            either {
                val principal = call.requireMiruPrincipal().bind()
                val unwatchedDays =
                    parseOptionalIntParam(
                        query,
                        "unwatchedDays",
                        LibraryQueryService.DEFAULT_STALE_UNWATCHED_DAYS,
                    ).bind()
                val limit =
                    parseOptionalIntParam(query, "limit", LibraryQueryService.DEFAULT_STALE_LIMIT).bind()
                val sort = query["sort"]
                val serverId = query["serverId"]
                val stale =
                    libraryQueryService
                        .getStaleMedia(
                            principal = principal,
                            sectionId = sectionId,
                            serverId = serverId,
                            unwatchedDays = unwatchedDays,
                            sortRaw = sort,
                            limit = limit,
                        ).bind()
                val serversById = libraryQueryService.loadServersById()
                stale.toResponse(serversById[stale.section.serverId])
            }
        call.respondEither(result)
    }
}

private fun parseOptionalLongParam(
    params: Parameters,
    name: String,
): Either<MiruError.ValidationError, Long?> =
    either {
        val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return@either null
        raw.toLongOrNull()
            ?: raise(
                MiruError.ValidationError(
                    message = "Invalid query parameter '$name'",
                    fieldErrors = mapOf(name to "must be a valid 64-bit integer"),
                ),
            )
    }

private fun parseOptionalIntParam(
    params: Parameters,
    name: String,
    defaultValue: Int,
): Either<MiruError.ValidationError, Int> =
    either {
        val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return@either defaultValue
        raw.toIntOrNull()
            ?: raise(
                MiruError.ValidationError(
                    message = "Invalid query parameter '$name'",
                    fieldErrors = mapOf(name to "must be a valid integer"),
                ),
            )
    }

private fun parseOptionalBooleanParam(
    params: Parameters,
    name: String,
    defaultValue: Boolean,
): Either<MiruError.ValidationError, Boolean> =
    either {
        val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return@either defaultValue
        when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else ->
                raise(
                    MiruError.ValidationError(
                        message = "Invalid query parameter '$name'",
                        fieldErrors = mapOf(name to "must be 'true' or 'false'"),
                    ),
                )
        }
    }

private fun parseOptionalSectionKeyParam(
    params: Parameters,
    name: String,
): Either<MiruError.ValidationError, String?> =
    either {
        val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return@either null
        ensure(SECTION_KEY_REGEX.matches(raw)) {
            MiruError.ValidationError(
                message = "Invalid query parameter '$name'",
                fieldErrors = mapOf(name to "must be a non-empty alphanumeric section key"),
            )
        }
        raw
    }
