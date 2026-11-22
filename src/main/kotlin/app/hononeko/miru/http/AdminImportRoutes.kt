package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.importer.DEFAULT_TAUTULLI_WATCHED_THRESHOLD_PERCENT
import app.hononeko.miru.importer.TautulliImportOptions
import app.hononeko.miru.importer.TautulliImporterService
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_TAUTULLI_UPLOAD_BYTES: Long = 256L * 1024L * 1024L
private const val STREAM_BUFFER_SIZE = 16 * 1024

fun Route.adminImportRoutes(
    tautulliImporterService: TautulliImporterService,
    authService: AuthService,
    authMode: AuthMode,
    watchedThresholdPercent: Int = DEFAULT_TAUTULLI_WATCHED_THRESHOLD_PERCENT,
) {
    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/admin/imports") {
            post("/tautulli") {
                val query = call.request.queryParameters
                val queryDryRun =
                    parseOptionalBoolean(query, "dryRun").fold(
                        ifLeft = {
                            call.respondError(it)
                            return@post
                        },
                        ifRight = { it },
                    )
                val queryServerId = query["serverId"]?.trim()?.takeIf { it.isNotEmpty() }

                if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    call.respondError(
                        MiruError.ValidationError("Request must be multipart/form-data with a 'file' part"),
                    )
                    return@post
                }

                var tempFile: Path? = null
                try {
                    val upload =
                        readMultipartUpload(call.receiveMultipart()) {
                            Files.createTempFile("miru-tautulli-", ".db").also { tempFile = it }
                        }
                    val result =
                        either {
                            val parsedUpload = upload.bind()
                            val effectiveDryRun = queryDryRun ?: parsedUpload.formDryRun ?: false
                            val effectiveServerId = queryServerId ?: parsedUpload.formServerId
                            tautulliImporterService
                                .import(
                                    file = parsedUpload.file,
                                    options =
                                        TautulliImportOptions(
                                            dryRun = effectiveDryRun,
                                            serverId = effectiveServerId,
                                            watchedThresholdPercent = watchedThresholdPercent,
                                        ),
                                ).bind()
                        }
                    call.respondEither(result)
                } finally {
                    tempFile?.let { path ->
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }
    }
}

private data class ParsedMultipartUpload(
    val file: Path,
    val formDryRun: Boolean?,
    val formServerId: String?,
)

private suspend fun readMultipartUpload(
    multipart: io.ktor.http.content.MultiPartData,
    tempFileSupplier: () -> Path,
): Either<MiruError.ValidationError, ParsedMultipartUpload> {
    var tempFile: Path? = null
    var bytesWritten = 0L
    var hasFilePart = false
    var formDryRun: Boolean? = null
    var formServerId: String? = null
    var validationError: MiruError.ValidationError? = null

    multipart.forEachPart { part ->
        try {
            if (validationError == null) {
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "file" && !hasFilePart) {
                            hasFilePart = true
                            val target = tempFileSupplier()
                            tempFile = target
                            val copyResult = copyPartWithLimit(part, target)
                            copyResult.fold(
                                ifLeft = { validationError = it },
                                ifRight = { bytesWritten = it },
                            )
                        }
                    }

                    is PartData.FormItem -> {
                        when (part.name) {
                            "dryRun" -> {
                                parseBooleanString(part.value, "dryRun").fold(
                                    ifLeft = { validationError = it },
                                    ifRight = { formDryRun = it },
                                )
                            }

                            "serverId" -> {
                                formServerId = part.value.trim().takeIf { it.isNotEmpty() }
                            }
                        }
                    }

                    else -> Unit
                }
            }
        } finally {
            part.release()
        }
    }

    return either {
        validationError?.let { raise(it) }
        val file = tempFile
        ensure(hasFilePart && file != null && bytesWritten > 0L) {
            MiruError.ValidationError(
                message = "Missing or empty 'file' part in multipart upload",
                fieldErrors = mapOf("file" to "is required and must not be empty"),
            )
        }
        ParsedMultipartUpload(
            file = file,
            formDryRun = formDryRun,
            formServerId = formServerId,
        )
    }
}

private suspend fun copyPartWithLimit(
    part: PartData.FileItem,
    target: Path,
): Either<MiruError.ValidationError, Long> =
    either {
        var totalBytes = 0L
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        val channel = part.provider()

        Files.newOutputStream(target).use { output ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                totalBytes += read
                ensure(totalBytes <= MAX_TAUTULLI_UPLOAD_BYTES) {
                    MiruError.ValidationError(
                        message = "Uploaded file exceeds maximum allowed size of 256 MiB",
                        fieldErrors = mapOf("file" to "must be <= 256 MiB"),
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        totalBytes
    }

private fun parseOptionalBoolean(
    params: Parameters,
    name: String,
): Either<MiruError.ValidationError, Boolean?> {
    val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return Either.Right(null)
    return parseBooleanString(raw, name).map { it }
}

private fun parseBooleanString(
    raw: String,
    name: String,
): Either<MiruError.ValidationError, Boolean> =
    when (raw.trim().lowercase()) {
        "true" -> Either.Right(true)
        "false" -> Either.Right(false)
        else ->
            Either.Left(
                MiruError.ValidationError(
                    message = "Invalid parameter '$name'",
                    fieldErrors = mapOf(name to "must be 'true' or 'false'"),
                ),
            )
    }
