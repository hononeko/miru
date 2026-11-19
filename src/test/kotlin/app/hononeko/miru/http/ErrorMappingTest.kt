package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.miruModule
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ErrorMappingTest {
    @Serializable
    private data class SamplePayload(
        val id: String,
    )

    private val testConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(":memory:"),
            plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "test-token"),
            session = SessionConfig(),
            auth = AuthConfig(mode = AuthMode.NONE),
        )

    @Test
    fun `maps every MiruError variant to expected HTTP status code`() {
        val expected =
            listOf(
                MiruError.ValidationError("bad input") to HttpStatusCode.BadRequest,
                MiruError.Unauthorized() to HttpStatusCode.Unauthorized,
                MiruError.Forbidden() to HttpStatusCode.Forbidden,
                MiruError.NotFound("User", "42") to HttpStatusCode.NotFound,
                MiruError.Conflict("already exists") to HttpStatusCode.Conflict,
                MiruError.UpstreamAuthError("plex", "invalid token") to HttpStatusCode.BadGateway,
                MiruError.UpstreamUnavailable("plex", "connection refused") to HttpStatusCode.BadGateway,
                MiruError.PersistenceError("insert", "constraint violation") to HttpStatusCode.InternalServerError,
                MiruError.InternalError("boom") to HttpStatusCode.InternalServerError,
            )

        expected.forEach { (error, status) ->
            assertEquals(status, error.toHttpStatus(), "Unexpected status for ${error::class.simpleName}")
            assertEquals(status.value, error.toProblemDetails("/test").status)
        }
    }

    @Test
    fun `respondEither serializes Right payload and RFC 7807 ProblemDetails for Left`() =
        testApplication {
            application {
                miruModule(config = testConfig)
                routing {
                    get("/test/ok") {
                        call.respondEither(SamplePayload("abc").right(), HttpStatusCode.Created)
                    }
                    get("/test/validation") {
                        val err: Either<MiruError, SamplePayload> =
                            MiruError
                                .ValidationError(
                                    message = "Invalid query",
                                    fieldErrors = mapOf("page" to "must be >= 1"),
                                ).left()
                        call.respondEither(err)
                    }
                    get("/test/not-found") {
                        val err: Either<MiruError, SamplePayload> =
                            MiruError.NotFound(resource = "HistoryEntry", id = "99").left()
                        call.respondEither(err)
                    }
                    get("/test/persistence") {
                        val err: Either<MiruError, SamplePayload> =
                            MiruError
                                .PersistenceError(
                                    operation = "select_history",
                                    message = "raw sql detail with sensitive column",
                                    cause = IllegalStateException("internal jdbc detail"),
                                ).left()
                        call.respondEither(err)
                    }
                    get("/test/bad-request") {
                        throw BadRequestException("Invalid JSON body")
                    }
                    get("/test/crash") {
                        throw IllegalStateException("super-secret-stack-detail")
                    }
                }
            }

            val okResponse = client.get("/test/ok")
            assertEquals(HttpStatusCode.Created, okResponse.status)
            assertEquals(SamplePayload("abc"), MiruJson.decodeFromString<SamplePayload>(okResponse.bodyAsText()))

            val validationResponse = client.get("/test/validation")
            assertEquals(HttpStatusCode.BadRequest, validationResponse.status)
            assertEquals(ContentType.Application.ProblemJson, validationResponse.contentType()?.withoutParameters())
            val validationProblem = MiruJson.decodeFromString<ProblemDetails>(validationResponse.bodyAsText())
            assertEquals(400, validationProblem.status)
            assertEquals("/test/validation", validationProblem.instance)
            assertEquals(mapOf("page" to "must be >= 1"), validationProblem.errors)

            val notFoundResponse = client.get("/test/not-found")
            assertEquals(HttpStatusCode.NotFound, notFoundResponse.status)
            val notFoundProblem = MiruJson.decodeFromString<ProblemDetails>(notFoundResponse.bodyAsText())
            assertEquals("HistoryEntry '99' was not found", notFoundProblem.detail)

            val dbResponse = client.get("/test/persistence")
            assertEquals(HttpStatusCode.InternalServerError, dbResponse.status)
            val dbBody = dbResponse.bodyAsText()
            assertFalse(dbBody.contains("raw sql detail"))
            assertFalse(dbBody.contains("internal jdbc detail"))

            val badReqResponse = client.get("/test/bad-request")
            assertEquals(HttpStatusCode.BadRequest, badReqResponse.status)
            assertEquals(ContentType.Application.ProblemJson, badReqResponse.contentType()?.withoutParameters())

            val crashResponse = client.get("/test/crash")
            assertEquals(HttpStatusCode.InternalServerError, crashResponse.status)
            assertFalse(crashResponse.bodyAsText().contains("super-secret-stack-detail"))
        }
}
