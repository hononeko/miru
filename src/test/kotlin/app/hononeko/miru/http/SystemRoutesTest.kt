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
import app.hononeko.miru.health.HealthResponse
import app.hononeko.miru.health.NamedReadinessCheck
import app.hononeko.miru.health.ReadinessResponse
import app.hononeko.miru.miruModule
import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication

class SystemRoutesTest :
    FunSpec({
        val testConfig =
            AppConfig(
                server = ServerConfig(host = "127.0.0.1", port = 8080),
                database = DatabaseConfig.Sqlite(":memory:"),
                plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "test-token"),
                session = SessionConfig(),
                auth = AuthConfig(mode = AuthMode.NONE),
            )

        test("healthz returns 200 OK with service metadata") {
            testApplication {
                application {
                    miruModule(config = testConfig, appVersion = "0.1.0-test")
                }

                val response = client.get("/healthz")
                response.status shouldBe HttpStatusCode.OK
                val body = MiruJson.decodeFromString<HealthResponse>(response.bodyAsText())
                body.status shouldBe "ok"
                body.service shouldBe "miru"
                body.version shouldBe "0.1.0-test"
            }
        }

        test("readyz returns 200 OK when all registered checks pass") {
            testApplication {
                application {
                    miruModule(
                        config = testConfig,
                        readinessChecks =
                            listOf(
                                NamedReadinessCheck("database") { Either.Right(Unit) },
                                NamedReadinessCheck("plex") { Either.Right(Unit) },
                            ),
                    )
                }

                val response = client.get("/readyz")
                response.status shouldBe HttpStatusCode.OK
                val body = MiruJson.decodeFromString<ReadinessResponse>(response.bodyAsText())
                body.status shouldBe "ready"
                body.checks["database"]?.status shouldBe "healthy"
                body.checks["plex"]?.status shouldBe "healthy"
            }
        }

        test("readyz returns 503 when a dependency fails and sanitizes internal error details") {
            testApplication {
                application {
                    miruModule(
                        config = testConfig,
                        readinessChecks =
                            listOf(
                                NamedReadinessCheck("database") { Either.Right(Unit) },
                                NamedReadinessCheck("plex") {
                                    Either.Left(
                                        MiruError.UpstreamUnavailable(
                                            provider = "plex",
                                            message = "Connection refused with token=secret123",
                                        ),
                                    )
                                },
                                NamedReadinessCheck("exploding") {
                                    error("unexpected boom")
                                },
                            ),
                    )
                }

                val response = client.get("/readyz")
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                val raw = response.bodyAsText()
                raw shouldNotContain "secret123"
                raw shouldNotContain "unexpected boom"

                val body = MiruJson.decodeFromString<ReadinessResponse>(raw)
                body.status shouldBe "not_ready"
                body.checks["database"]?.status shouldBe "healthy"
                body.checks["plex"]?.status shouldBe "unhealthy"
                body.checks["exploding"]?.status shouldBe "unhealthy"
            }
        }

        test("serves OpenAPI 3.1 specification at /api/v1/openapi.yaml") {
            testApplication {
                application {
                    miruModule(config = testConfig)
                }

                val response = client.get("/api/v1/openapi.yaml")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters().toString() shouldBe "application/yaml"
                val yaml = response.bodyAsText()
                yaml shouldContain "openapi: 3.1.0"
                yaml shouldContain "/healthz:"
                yaml shouldContain "/readyz:"
                yaml shouldContain "ProblemDetails:"
            }
        }
    })
