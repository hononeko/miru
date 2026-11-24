package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
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
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.resolveAppVersion
import app.hononeko.miru.service.StreamsAndUsersService
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

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

        test("real-time pipeline starts with the application and stops on shutdown") {
            val realtime =
                object : RealtimeLifecycle {
                    val started = AtomicInteger()
                    val stopped = AtomicInteger()

                    override fun start() {
                        started.incrementAndGet()
                    }

                    override suspend fun stop() {
                        stopped.incrementAndGet()
                    }
                }

            testApplication {
                application {
                    miruModule(config = testConfig, appVersion = "0.1.0-test", realtime = realtime)
                }

                client.get("/healthz").status shouldBe HttpStatusCode.OK
                realtime.started.get() shouldBe 1
                realtime.stopped.get() shouldBe 0
            }

            realtime.started.get() shouldBe 1
            realtime.stopped.get() shouldBe 1
        }

        test("resolveAppVersion prioritizes MIRU_VERSION, then manifest implementation version, then dev") {
            resolveAppVersion(envVersion = " 1.2.3 ", manifestVersion = "0.9.0") shouldBe "1.2.3"
            resolveAppVersion(envVersion = "   ", manifestVersion = " 0.9.0 ") shouldBe "0.9.0"
            resolveAppVersion(envVersion = null, manifestVersion = "0.9.0") shouldBe "0.9.0"
            resolveAppVersion(envVersion = null, manifestVersion = "  ") shouldBe "dev"
            resolveAppVersion(envVersion = null, manifestVersion = null) shouldBe "dev"
            resolveAppVersion().isNotBlank() shouldBe true
        }

        test("HTTP compression gzips large API responses while leaving SSE streams and image payloads uncompressed") {
            val dir = Files.createTempDirectory("miru-compression-test-")
            val dbPath = dir.resolve("comp.db").toString()
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            try {
                val userRepo = ExposedUserRepository(handle)
                val authService =
                    AuthService(
                        accountRepository = ExposedAuthAccountRepository(handle),
                        sessionRepository = ExposedAuthSessionRepository(handle),
                        apiKeyRepository = ExposedApiKeyRepository(handle),
                        userRepository = userRepo,
                        passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
                        authConfig = AuthConfig(mode = AuthMode.NONE),
                    )
                val streamsService =
                    StreamsAndUsersService(
                        activeSessionRepository = ExposedActiveSessionRepository(handle),
                        userRepository = userRepo,
                        playbackHistoryRepository = ExposedPlaybackHistoryRepository(handle),
                    )
                val largeImageBytes = ByteArray(2048) { idx -> (idx % 251).toByte() }

                testApplication {
                    application {
                        miruModule(
                            config = testConfig,
                            authService = authService,
                            streamsAndUsersService = streamsService,
                        )
                        routing {
                            get("/api/v1/test-image") {
                                call.respondBytes(
                                    bytes = largeImageBytes,
                                    contentType = io.ktor.http.ContentType.Image.PNG,
                                    status = HttpStatusCode.OK,
                                )
                            }
                        }
                    }

                    val openapiCompressed =
                        client.get("/api/v1/openapi.yaml") {
                            header(HttpHeaders.AcceptEncoding, "gzip")
                        }
                    openapiCompressed.status shouldBe HttpStatusCode.OK
                    openapiCompressed.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
                    val gunzipped =
                        GZIPInputStream(ByteArrayInputStream(openapiCompressed.bodyAsBytes()))
                            .bufferedReader()
                            .use { it.readText() }
                    gunzipped shouldContain "openapi: 3.1.0"

                    val imageResp =
                        client.get("/api/v1/test-image") {
                            header(HttpHeaders.AcceptEncoding, "gzip, deflate")
                        }
                    imageResp.status shouldBe HttpStatusCode.OK
                    imageResp.headers[HttpHeaders.ContentEncoding].shouldBeNull()
                    imageResp.bodyAsBytes() shouldBe largeImageBytes

                    withTimeout(5_000L) {
                        client
                            .prepareGet("/api/v1/streams/events") {
                                header(HttpHeaders.AcceptEncoding, "gzip, deflate")
                            }.execute { response ->
                                response.status shouldBe HttpStatusCode.OK
                                response.headers[HttpHeaders.ContentEncoding].shouldBeNull()
                                val channel = response.bodyAsChannel()
                                val firstLine = channel.readLine()
                                firstLine shouldBe "event: snapshot"
                            }
                    }
                }
            } finally {
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
