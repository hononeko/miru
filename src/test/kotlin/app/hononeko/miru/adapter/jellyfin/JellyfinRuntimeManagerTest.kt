package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.plex.ConnectionState
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val RUNTIME_JF_KEY = "jf-runtime-secret-key-555"

class JellyfinRuntimeManagerTest :
    FunSpec({
        test("supports dormant zero-config startup, configured initialization, sync callback failure isolation, and DB fallback") {
            val tempDir = Files.createTempDirectory("miru-jf-runtime-test-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(tempDir.resolve("jf.db").toString())).shouldBeRight()
            val serverRepo = ExposedMediaServerRepository(handle)
            try {
                // 1. Dormant when unconfigured
                val dormant = JellyfinRuntimeManager(JellyfinConfig(), handle, mediaServerRepository = serverRepo)
                dormant.initialize().shouldBeRight() shouldBe false
                dormant.isConfigured() shouldBe false
                dormant.currentBaseUrl().shouldBeNull()
                dormant.currentMachineId().shouldBeNull()
                dormant.cachedServerIdOrNull().shouldBeNull()
                dormant.currentServerSummary().shouldBeNull()
                dormant.connectionState() shouldBe ConnectionState.Disconnected
                dormant.serverIdProvider().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                dormant.stop()

                // 2. Configured manager activates, invokes onReloadLibrarySync even if it throws, and resolves/caches serverId
                val reachable = AtomicBoolean(true)
                val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                val mockEngine =
                    MockEngine { req ->
                        if (!reachable.get()) {
                            respond("Down", HttpStatusCode.ServiceUnavailable)
                        } else {
                            when (req.url.encodedPath) {
                                "/System/Info" ->
                                    respond(
                                        """{"Id":"jf-mach-1","ServerName":"Lab Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                                        headers = jsonHeaders,
                                    )

                                "/Sessions" -> respond("[]", headers = jsonHeaders)
                                else -> respond("Not found", HttpStatusCode.NotFound)
                            }
                        }
                    }

                val manager =
                    JellyfinRuntimeManager(
                        jellyfinConfig =
                            JellyfinConfig(
                                baseUrl = "http://jellyfin.local:8096/",
                                apiKey = RUNTIME_JF_KEY,
                                pollIntervalSeconds = 5,
                                websocketEnabled = false,
                            ),
                        databaseHandle = handle,
                        sessionConfig = SessionConfig(minPlayDurationSeconds = 0, resumeMergeWindowSeconds = 0),
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { mockEngine },
                    )
                val syncCalled = CompletableDeferred<Unit>()
                manager.setOnReloadLibrarySync {
                    syncCalled.complete(Unit)
                    error("simulated sync failure")
                }

                manager.initialize().shouldBeRight() shouldBe true
                withTimeout(3_000) { syncCalled.await() }
                manager.isConfigured() shouldBe true
                manager.currentBaseUrl() shouldBe "http://jellyfin.local:8096"
                manager.websocketEnabled() shouldBe false

                val resolvedId = manager.serverIdProvider().shouldBeRight()
                manager.cachedServerIdOrNull() shouldBe resolvedId
                manager.serverIdProvider().shouldBeRight() shouldBe resolvedId
                manager.currentMachineId() shouldBe "jf-mach-1"
                val summary = manager.currentServerSummary().shouldNotBeNull()
                summary.id shouldBe resolvedId
                summary.name shouldBe "Lab Jellyfin"

                // Wait for reconciler health signal to confirm reachable = true
                withTimeout(5_000) {
                    val health = manager.lastReconcileHealth() ?: manager.reconcileHealth.first()
                    health.reachable shouldBe true
                }

                manager.stop()
                manager.isConfigured() shouldBe false
                manager.currentBaseUrl().shouldBeNull()
                manager.currentMachineId().shouldBeNull()
                manager.cachedServerIdOrNull().shouldBeNull()
                manager.lastReconcileHealth().shouldBeNull()

                // 3. Unreachable Jellyfin falls back to persisted MediaServerRecord matching serverType + baseUrl
                reachable.set(false)
                val fallbackManager =
                    JellyfinRuntimeManager(
                        jellyfinConfig =
                            JellyfinConfig(
                                baseUrl = "http://jellyfin.local:8096",
                                apiKey = RUNTIME_JF_KEY,
                                websocketEnabled = false,
                            ),
                        databaseHandle = handle,
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { mockEngine },
                    )
                fallbackManager.initialize().shouldBeRight() shouldBe true
                fallbackManager.serverIdProvider().shouldBeRight() shouldBe resolvedId
                fallbackManager.stop()

                // 4. Unreachable Jellyfin with no matching DB record returns Left health error
                serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "other-plex",
                            name = "Other",
                            serverType = "plex",
                            machineId = "p1",
                            baseUrl = "http://other.local:8096",
                            createdAtMs = 1L,
                            updatedAtMs = 1L,
                        ),
                    ).shouldBeRight()
                val unknownManager =
                    JellyfinRuntimeManager(
                        jellyfinConfig =
                            JellyfinConfig(
                                baseUrl = "http://unknown-jf.local:8096",
                                apiKey = RUNTIME_JF_KEY,
                                websocketEnabled = false,
                            ),
                        databaseHandle = handle,
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { mockEngine },
                    )
                unknownManager.initialize().shouldBeRight() shouldBe true
                unknownManager.serverIdProvider().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                unknownManager.stop()
            } finally {
                handle.close()
                tempDir.toFile().deleteRecursively()
            }
        }

        test("forwards live Netty WebSocket events, KeepAlive, reconnect, and updates sessionEngine over real sockets") {
            val tempDir = Files.createTempDirectory("miru-jf-runtime-ws-test-")
            val connections = AtomicInteger(0)
            val keepAliveReceived = CompletableDeferred<Unit>()
            val sessionsStartReceived = CompletableDeferred<String>()
            val liveSessionsJson =
                """
                [
                  {
                    "Id": "jf-live-1",
                    "UserId": "jf-u1",
                    "UserName": "alice",
                    "Client": "Jellyfin Web",
                    "DeviceName": "Chrome",
                    "DeviceId": "dev-ws-1",
                    "RemoteEndPoint": "127.0.0.1",
                    "PlayState": { "PositionTicks": 600000000, "IsPaused": false, "PlayMethod": "DirectPlay" },
                    "NowPlayingItem": { "Id": "jf-m1", "Name": "Arrival", "Type": "Movie", "RunTimeTicks": 69000000000 }
                  }
                ]
                """.trimIndent()

            val server =
                embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        get("/System/Info") {
                            call.respondText(
                                """{"Id":"jf-ws-mach","ServerName":"WS Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                                ContentType.Application.Json,
                            )
                        }
                        get("/Sessions") {
                            call.respondText(liveSessionsJson, ContentType.Application.Json)
                        }
                        webSocket("/socket") {
                            val attempt = connections.incrementAndGet()
                            call.request.queryParameters["api_key"] shouldBe RUNTIME_JF_KEY
                            call.request.queryParameters["deviceId"] shouldBe "miru-ws-dev"

                            val startFrame = (incoming.receive() as Frame.Text).readText()
                            sessionsStartReceived.complete(startFrame)

                            if (attempt == 1) {
                                // Drop first connection to verify reconnect path
                                close(CloseReason(CloseReason.Codes.GOING_AWAY, "restart"))
                            } else {
                                send(Frame.Text("""{"MessageType":"ForceKeepAlive","Data":30}"""))
                                val ka = (incoming.receive() as Frame.Text).readText()
                                if (ka.contains("KeepAlive")) {
                                    keepAliveReceived.complete(Unit)
                                }
                                send(Frame.Text("""{"MessageType":"Sessions","Data":$liveSessionsJson}"""))
                                do {
                                    val next = incoming.receiveCatching()
                                } while (next.isSuccess)
                            }
                        }
                    }
                }.start(wait = false)

            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(tempDir.resolve("ws.db").toString())).shouldBeRight()
            try {
                val port = server.engine.resolvedConnectors().first().port
                val manager =
                    JellyfinRuntimeManager(
                        jellyfinConfig =
                            JellyfinConfig(
                                baseUrl = "http://127.0.0.1:$port",
                                apiKey = RUNTIME_JF_KEY,
                                clientIdentifier = "miru-ws-dev",
                                pollIntervalSeconds = 5,
                                websocketEnabled = true,
                            ),
                        databaseHandle = handle,
                        sessionConfig = SessionConfig(minPlayDurationSeconds = 0, resumeMergeWindowSeconds = 0),
                    )
                val forwardedEvent = async { manager.adapter.events().first { it is MediaServerEvent.PlayingEvent } }

                manager.start()

                withTimeout(15_000) {
                    sessionsStartReceived.await() shouldContain "SessionsStart"
                    keepAliveReceived.await()
                    val event = forwardedEvent.await().shouldBeInstanceOf<MediaServerEvent.PlayingEvent>()
                    event.sessionKey shouldBe "jf-live-1"
                    event.ratingKey shouldBe "jf-m1"
                    event.state shouldBe PlaybackState.PLAYING
                }

                (connections.get() >= 2) shouldBe true
                manager.connectionState() shouldBe ConnectionState.Connected

                withTimeout(5_000) {
                    while (manager.sessionEngine.currentSessions().isEmpty()) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                val active = manager.sessionEngine.currentSessions().single()
                active.sessionKey shouldBe "jf-live-1"
                active.media.title shouldBe "Arrival"

                manager.stop()
                manager.connectionState() shouldBe ConnectionState.Disconnected
                manager.isConfigured() shouldBe false
            } finally {
                handle.close()
                server.stop(0, 1_000)
                tempDir.toFile().deleteRecursively()
            }
        }
    })
