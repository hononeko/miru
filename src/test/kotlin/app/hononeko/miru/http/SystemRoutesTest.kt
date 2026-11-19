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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemRoutesTest {
    private val testConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(":memory:"),
            plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "test-token"),
            session = SessionConfig(),
            auth = AuthConfig(mode = AuthMode.NONE),
        )

    @Test
    fun `healthz returns 200 OK with service metadata`() =
        testApplication {
            application {
                miruModule(config = testConfig, appVersion = "0.1.0-test")
            }

            val response = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = MiruJson.decodeFromString<HealthResponse>(response.bodyAsText())
            assertEquals("ok", body.status)
            assertEquals("miru", body.service)
            assertEquals("0.1.0-test", body.version)
        }

    @Test
    fun `readyz returns 200 OK when all registered checks pass`() =
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
            assertEquals(HttpStatusCode.OK, response.status)
            val body = MiruJson.decodeFromString<ReadinessResponse>(response.bodyAsText())
            assertEquals("ready", body.status)
            assertEquals("healthy", body.checks["database"]?.status)
            assertEquals("healthy", body.checks["plex"]?.status)
        }

    @Test
    fun `readyz returns 503 when a dependency fails and sanitizes internal error details`() =
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
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val raw = response.bodyAsText()
            assertFalse(raw.contains("secret123"))
            assertFalse(raw.contains("unexpected boom"))

            val body = MiruJson.decodeFromString<ReadinessResponse>(raw)
            assertEquals("not_ready", body.status)
            assertEquals("healthy", body.checks["database"]?.status)
            assertEquals("unhealthy", body.checks["plex"]?.status)
            assertEquals("unhealthy", body.checks["exploding"]?.status)
        }

    @Test
    fun `serves OpenAPI 3_1 specification at api v1 openapi yaml`() =
        testApplication {
            application {
                miruModule(config = testConfig)
            }

            val response = client.get("/api/v1/openapi.yaml")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("application/yaml", response.contentType()?.withoutParameters().toString())
            val yaml = response.bodyAsText()
            assertTrue(yaml.contains("openapi: 3.1.0"))
            assertTrue(yaml.contains("/healthz:"))
            assertTrue(yaml.contains("/readyz:"))
            assertTrue(yaml.contains("ProblemDetails:"))
        }
}
