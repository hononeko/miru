package app.hononeko.miru.http

import app.hononeko.miru.artwork.ArtworkKind
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.CachedArtwork
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val CACHE_CONTROL_VALUE = "private, max-age=3600"

fun Route.artworkRoutes(
    artworkProxyService: ArtworkProxyService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode) {
        route("/api/v1") {
            get("/media/{ratingKey}/thumb") {
                call.handleMediaArtwork(artworkProxyService, ArtworkKind.THUMB)
            }

            get("/media/{ratingKey}/art") {
                call.handleMediaArtwork(artworkProxyService, ArtworkKind.ART)
            }

            get("/users/{id}/avatar") {
                val userId = call.parameters["id"].orEmpty()
                val result: Either<MiruError, CachedArtwork> =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        artworkProxyService.getUserAvatar(principal = principal, userId = userId).bind()
                    }
                call.respondArtworkEither(result)
            }
        }
    }
}

private suspend fun ApplicationCall.handleMediaArtwork(
    artworkProxyService: ArtworkProxyService,
    kind: ArtworkKind,
) {
    val ratingKey = parameters["ratingKey"].orEmpty()
    val widthRaw = request.queryParameters["w"]
    val heightRaw = request.queryParameters["h"]

    val result: Either<MiruError, CachedArtwork> =
        either {
            val principal = requireMiruPrincipal().bind()
            val (width, height) = parseDimensions(widthRaw = widthRaw, heightRaw = heightRaw).bind()
            artworkProxyService
                .getMediaArtwork(
                    principal = principal,
                    ratingKey = ratingKey,
                    kind = kind,
                    width = width,
                    height = height,
                ).bind()
        }
    respondArtworkEither(result)
}

private fun parseDimensions(
    widthRaw: String?,
    heightRaw: String?,
): Either<MiruError.ValidationError, Pair<Int?, Int?>> =
    either {
        val errors = linkedMapOf<String, String>()
        val width =
            if (widthRaw != null) {
                widthRaw.toIntOrNull().also { parsed ->
                    if (parsed == null) errors["w"] = "must be a valid integer"
                }
            } else {
                null
            }
        val height =
            if (heightRaw != null) {
                heightRaw.toIntOrNull().also { parsed ->
                    if (parsed == null) errors["h"] = "must be a valid integer"
                }
            } else {
                null
            }
        ensure(errors.isEmpty()) {
            MiruError.ValidationError(
                message = "Invalid artwork dimensions",
                fieldErrors = errors,
            )
        }
        width to height
    }

private suspend fun ApplicationCall.respondArtworkEither(result: Either<MiruError, CachedArtwork>) {
    result.fold(
        ifLeft = { error -> respondError(error) },
        ifRight = { cached ->
            response.header(HttpHeaders.ETag, cached.etag)
            response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)

            val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
            if (ArtworkProxyService.matchesIfNoneMatch(ifNoneMatch, cached.etag)) {
                respond(HttpStatusCode.NotModified)
            } else {
                val parsedContentType =
                    runCatching { ContentType.parse(cached.contentType) }
                        .getOrDefault(ContentType.Application.OctetStream)
                respondBytes(
                    bytes = cached.bytes,
                    contentType = parsedContentType,
                    status = HttpStatusCode.OK,
                )
            }
        },
    )
}
