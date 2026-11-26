package app.hononeko.miru.http

import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.cleanupOnStartupFailure
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.MetricsConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.createMetricsRuntime
import app.hononeko.miru.createSyncServices
import app.hononeko.miru.metrics.MiruMetricsService
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.registerLifecycleHandlers
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.ServerSocket
import java.nio.file.Files

class MetricsRoutesTest :
    FunSpec({
        fun baseConfig(metrics: MetricsConfig): AppConfig =
            AppConfig(
                server = ServerConfig(host = "127.0.0.1", port = 8080),
                database = DatabaseConfig.Sqlite(":memory:"),
                plex = PlexConfig(baseUrl = "", token = ""),
                session = SessionConfig(),
                auth = AuthConfig(mode = AuthMode.NONE),
                metrics = metrics,
            )

        test("mounts /metrics on main server only when enabled and METRICS_PORT equals MIRU_PORT") {
            val metricsService =
                MiruMetricsService(
                    websocketConnected = MutableStateFlow(true),
                    activeWebsocketServerLabelProvider = { "Test Plex" },
                )
            try {
                metricsService.refreshWebsocketState(true)

                // Same port -> mounted on main server
                testApplication {
                    application {
                        miruModule(
                            config = baseConfig(MetricsConfig(enabled = true, host = "127.0.0.1", port = 8080)),
                            metricsService = metricsService,
                        )
                    }

                    val res = client.get("/metrics")
                    res.status shouldBe HttpStatusCode.OK
                    res.headers[HttpHeaders.ContentType] shouldContain "text/plain"
                    res.headers[HttpHeaders.ContentType] shouldContain "version=0.0.4"
                    res.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    res.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    res.headers["Content-Security-Policy"] shouldBe API_CONTENT_SECURITY_POLICY
                    res.bodyAsText() shouldContain """miru_websocket_connected{server="Test Plex"} 1.0"""
                }

                // Different port -> NOT mounted on main server
                testApplication {
                    application {
                        miruModule(
                            config = baseConfig(MetricsConfig(enabled = true, host = "127.0.0.1", port = 9090)),
                            metricsService = metricsService,
                        )
                    }

                    client.get("/metrics").status shouldBe HttpStatusCode.NotFound
                }

                // Disabled -> NOT mounted on main server
                testApplication {
                    application {
                        miruModule(
                            config = baseConfig(MetricsConfig(enabled = false, host = "127.0.0.1", port = 8080)),
                            metricsService = metricsService,
                        )
                    }

                    client.get("/metrics").status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                metricsService.close()
            }
        }

        test("dedicated metrics listener serves only /metrics and shuts down cleanly with lifecycle handlers") {
            val dir = Files.createTempDirectory("miru-dedicated-metrics-")
            val dbPath = dir.resolve("metrics.db").toString()
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            val freePort = ServerSocket(0).use { it.localPort }
            val client = HttpClient(Java)
            try {
                val mediaServerRepo = ExposedMediaServerRepository(handle)
                val libraryRepo = ExposedLibraryRepository(handle)
                val userRepo = ExposedUserRepository(handle)
                val runtimeManager =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(baseUrl = "", token = ""),
                        databaseHandle = handle,
                        mediaServerRepository = mediaServerRepo,
                    )
                val syncServices = createSyncServices(runtimeManager, libraryRepo, userRepo)

                val disabledRuntime =
                    createMetricsRuntime(
                        appConfig = baseConfig(MetricsConfig(enabled = false)),
                        plexRuntimeManager = runtimeManager,
                        libraryRepository = libraryRepo,
                        mediaServerRepository = mediaServerRepo,
                        libraryStatsCollector = syncServices.libraryStatsCollector,
                    )
                disabledRuntime.shouldBeNull()

                val sharedPortRuntime =
                    createMetricsRuntime(
                        appConfig = baseConfig(MetricsConfig(enabled = true, host = "127.0.0.1", port = 8080)),
                        plexRuntimeManager = runtimeManager,
                        libraryRepository = libraryRepo,
                        mediaServerRepository = mediaServerRepo,
                        libraryStatsCollector = syncServices.libraryStatsCollector,
                    ).shouldNotBeNull()
                sharedPortRuntime.dedicatedServer.shouldBeNull()
                sharedPortRuntime.scope.cancel()
                sharedPortRuntime.service.close()

                val dedicatedRuntime =
                    createMetricsRuntime(
                        appConfig = baseConfig(MetricsConfig(enabled = true, host = "127.0.0.1", port = freePort)),
                        plexRuntimeManager = runtimeManager,
                        libraryRepository = libraryRepo,
                        mediaServerRepository = mediaServerRepo,
                        libraryStatsCollector = syncServices.libraryStatsCollector,
                    ).shouldNotBeNull()
                val dedicatedServer = dedicatedRuntime.dedicatedServer.shouldNotBeNull()

                dedicatedServer.start(wait = false)
                testApplication {
                    application {
                        registerLifecycleHandlers(
                            syncServices = syncServices,
                            databaseHandle = handle,
                            metricsRuntime = dedicatedRuntime,
                        )
                    }
                    startApplication()

                    val metricsRes = client.get("http://127.0.0.1:$freePort/metrics")
                    metricsRes.status shouldBe HttpStatusCode.OK
                    metricsRes.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    metricsRes.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    metricsRes.bodyAsText() shouldContain "miru_websocket_connected"

                    val healthRes = client.get("http://127.0.0.1:$freePort/healthz")
                    healthRes.status shouldBe HttpStatusCode.NotFound
                }

                // Also verify cleanupOnStartupFailure with metricsRuntime
                val handle2 =
                    DatabaseFactory
                        .createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("metrics2.db").toString()))
                        .shouldBeRight()
                val freePort2 = ServerSocket(0).use { it.localPort }
                val runtimeManager2 =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(baseUrl = "", token = ""),
                        databaseHandle = handle2,
                        mediaServerRepository = ExposedMediaServerRepository(handle2),
                    )
                val libraryRepo2 = ExposedLibraryRepository(handle2)
                val syncServices2 = createSyncServices(runtimeManager2, libraryRepo2, ExposedUserRepository(handle2))
                val metricsRuntime2 =
                    createMetricsRuntime(
                        appConfig = baseConfig(MetricsConfig(enabled = true, host = "127.0.0.1", port = freePort2)),
                        plexRuntimeManager = runtimeManager2,
                        libraryRepository = libraryRepo2,
                        mediaServerRepository = ExposedMediaServerRepository(handle2),
                        libraryStatsCollector = syncServices2.libraryStatsCollector,
                    ).shouldNotBeNull()
                metricsRuntime2.dedicatedServer.shouldNotBeNull().start(wait = false)
                cleanupOnStartupFailure(
                    syncServices = syncServices2,
                    plexRuntimeManager = runtimeManager2,
                    databaseHandle = handle2,
                    metricsRuntime = metricsRuntime2,
                )
            } finally {
                client.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
