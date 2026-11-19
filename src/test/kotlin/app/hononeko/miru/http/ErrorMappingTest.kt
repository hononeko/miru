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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

@Serializable
private data class SamplePayload(
    val id: String,
)

class ErrorMappingTest :
    FunSpec({
        val testConfig =
            AppConfig(
                server = ServerConfig(host = "127.0.0.1", port = 8080),
                database = DatabaseConfig.Sqlite(":memory:"),
                plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "test-token"),
                session = SessionConfig(),
                auth = AuthConfig(mode = AuthMode.NONE),
            )

        test("maps every MiruError variant to expected HTTP status code") {
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
                error.toHttpStatus() shouldBe status
                error.toProblemDetails("/test").status shouldBe status.value
            }
        }

        test("respondEither serializes Right payload and RFC 7807 ProblemDetails for Left") {
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
                okResponse.status shouldBe HttpStatusCode.Created
                MiruJson.decodeFromString<SamplePayload>(okResponse.bodyAsText()) shouldBe SamplePayload("abc")

                val validationResponse = client.get("/test/validation")
                validationResponse.status shouldBe HttpStatusCode.BadRequest
                validationResponse.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
                val validationProblem = MiruJson.decodeFromString<ProblemDetails>(validationResponse.bodyAsText())
                validationProblem.status shouldBe 400
                validationProblem.instance shouldBe "/test/validation"
                validationProblem.errors shouldBe mapOf("page" to "must be >= 1")

                val notFoundResponse = client.get("/test/not-found")
                notFoundResponse.status shouldBe HttpStatusCode.NotFound
                val notFoundProblem = MiruJson.decodeFromString<ProblemDetails>(notFoundResponse.bodyAsText())
                notFoundProblem.detail shouldBe "HistoryEntry '99' was not found"

                val dbResponse = client.get("/test/persistence")
                dbResponse.status shouldBe HttpStatusCode.InternalServerError
                val dbBody = dbResponse.bodyAsText()
                dbBody shouldNotContain "raw sql detail"
                dbBody shouldNotContain "internal jdbc detail"

                val badReqResponse = client.get("/test/bad-request")
                badReqResponse.status shouldBe HttpStatusCode.BadRequest
                badReqResponse.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                val crashResponse = client.get("/test/crash")
                crashResponse.status shouldBe HttpStatusCode.InternalServerError
                crashResponse.bodyAsText() shouldNotContain "super-secret-stack-detail"
            }
        }
    })
