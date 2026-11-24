package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.WebhookDeliveryStatus
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookDeliveryRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookTargetRepository
import app.hononeko.miru.service.WebhookService
import app.hononeko.miru.webhook.WebhookDispatcher
import app.hononeko.miru.webhook.WebhookEventEvaluator
import app.hononeko.miru.webhook.WebhookServerPayload
import app.hononeko.miru.webhook.createWebhookHttpClient
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

private const val ROUTES_TEST_ENC_KEY = "webhook-routes-test-encryption-key-32-chars"

private class WebhookRoutesHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val scope: CoroutineScope,
    val httpClient: io.ktor.client.HttpClient,
    val authService: AuthService,
    val targetRepo: ExposedWebhookTargetRepository,
    val deliveryRepo: ExposedWebhookDeliveryRepository,
    val webhookService: WebhookService,
    val appConfig: AppConfig,
    val capturedUrls: MutableList<String>,
    val capturedAuthHeaders: MutableList<String?>,
) : AutoCloseable {
    override fun close() {
        httpClient.close()
        scope.cancel()
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createWebhookRoutesHarness(encryptionKey: String? = ROUTES_TEST_ENC_KEY): WebhookRoutesHarness {
    val dir = Files.createTempDirectory("miru-webhook-routes-test-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val authConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        )
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = ExposedUserRepository(handle),
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    val capturedUrls = mutableListOf<String>()
    val capturedAuthHeaders = mutableListOf<String?>()
    val httpClient =
        createWebhookHttpClient(
            engine =
                MockEngine { request ->
                    capturedUrls += request.url.toString()
                    capturedAuthHeaders += request.headers["Authorization"]
                    respond(content = """{"received":true}""", status = HttpStatusCode.OK)
                },
        )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val targetRepo = ExposedWebhookTargetRepository(handle)
    val deliveryRepo = ExposedWebhookDeliveryRepository(handle)
    val evaluator = WebhookEventEvaluator()
    val dispatcher =
        WebhookDispatcher(
            httpClientProvider = { httpClient },
            deliveryRepository = deliveryRepo,
            encryptionKey = encryptionKey,
            appVersion = "1.0.0-test",
            scope = scope,
            dnsResolver = DnsResolver { listOf(InetAddress.getByName("192.168.1.99")) },
            delayFn = {},
        )
    val webhookService =
        WebhookService(
            targetRepository = targetRepo,
            deliveryRepository = deliveryRepo,
            dispatcher = dispatcher,
            evaluator = evaluator,
            encryptionKey = encryptionKey,
            serverInfoProvider = { WebhookServerPayload("srv-1", "Test Plex") },
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(encryptionKey = encryptionKey),
            session = SessionConfig(),
            auth = authConfig,
        )
    return WebhookRoutesHarness(
        tempDir = dir,
        dbHandle = handle,
        scope = scope,
        httpClient = httpClient,
        authService = authService,
        targetRepo = targetRepo,
        deliveryRepo = deliveryRepo,
        webhookService = webhookService,
        appConfig = appConfig,
        capturedUrls = capturedUrls,
        capturedAuthHeaders = capturedAuthHeaders,
    )
}

class WebhookRoutesTest :
    FunSpec({
        test("enforces ADMIN RBAC and supports full CRUD, secret redaction, test delivery, filtering, and delete") {
            createWebhookRoutesHarness().use { h ->
                val adminLogin = h.authService.setupInitialAdmin("admin", "AdminPass1234").shouldBeRight()
                h.authService
                    .createAccount(username = "viewer", password = "ViewerPass1234", role = MiruRole.USER)
                    .shouldBeRight()
                val viewerLogin = h.authService.login("viewer", "ViewerPass1234").shouldBeRight()

                testApplication {
                    application {
                        miruModule(
                            config = h.appConfig,
                            authService = h.authService,
                            webhookService = h.webhookService,
                        )
                    }

                    // Unauthenticated -> 401, non-admin -> 403
                    client.get("/api/v1/admin/webhooks").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("/api/v1/admin/webhooks") { cookie(SESSION_COOKIE_NAME, viewerLogin.signedToken) }
                        .status shouldBe HttpStatusCode.Forbidden

                    val emptyRes =
                        client.get("/api/v1/admin/webhooks") { cookie(SESSION_COOKIE_NAME, adminLogin.signedToken) }
                    emptyRes.status shouldBe HttpStatusCode.OK
                    val emptyList = MiruJson.decodeFromString<WebhookTargetsResponse>(emptyRes.bodyAsText())
                    emptyList.targets.shouldBeEmpty()
                    emptyList.encryptionKeyConfigured shouldBe true

                    val createReq =
                        CreateWebhookTargetRequest(
                            name = "Homelab Notifier",
                            url = "http://notifier.media.svc.cluster.local:8080/hook?token=secret-query-param",
                            enabled = false,
                            subscribedEvents = listOf("stream.terminated", "transcode.started"),
                            signingSecret = "top-secret-hmac-key",
                            authHeaderName = "Authorization",
                            authHeaderValue = "Bearer secret-bearer-token",
                            cooldownSeconds = 45,
                            concurrentStreamThreshold = 4,
                            bufferingSpikeThreshold = 4,
                            bufferingWindowSeconds = 90,
                            transcode4kOnly = true,
                        )
                    createReq.toString() shouldNotContain "top-secret-hmac-key"
                    createReq.toString() shouldNotContain "secret-bearer-token"
                    createReq.toString() shouldNotContain "secret-query-param"

                    val createdRes =
                        client.post("/api/v1/admin/webhooks") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(CreateWebhookTargetRequest.serializer(), createReq))
                        }
                    createdRes.status shouldBe HttpStatusCode.Created
                    val createdRawBody = createdRes.bodyAsText()
                    createdRawBody shouldNotContain "top-secret-hmac-key"
                    createdRawBody shouldNotContain "secret-bearer-token"
                    createdRawBody shouldNotContain "secret-query-param"

                    val created = MiruJson.decodeFromString<WebhookTargetDto>(createdRawBody)
                    created.name shouldBe "Homelab Notifier"
                    created.url shouldBe "http://notifier.media.svc.cluster.local:8080/hook?***"
                    created.enabled shouldBe false
                    created.subscribedEvents shouldContainExactly listOf("transcode.started", "stream.terminated")
                    created.hasSigningSecret shouldBe true
                    created.authHeaderName shouldBe "Authorization"
                    created.hasAuthHeader shouldBe true
                    created.transcode4kOnly shouldBe true

                    // Test endpoint works synchronously even on disabled target
                    val testRes =
                        client.post("/api/v1/admin/webhooks/${created.id}/test") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    testRes.status shouldBe HttpStatusCode.OK
                    val delivery = MiruJson.decodeFromString<WebhookDeliveryDto>(testRes.bodyAsText())
                    delivery.eventType shouldBe "webhook.test"
                    delivery.status shouldBe WebhookDeliveryStatus.DELIVERED
                    delivery.httpStatus shouldBe 200
                    delivery.attemptCount shouldBe 1
                    h.capturedUrls.last() shouldContain "token=secret-query-param"
                    h.capturedAuthHeaders.last() shouldBe "Bearer secret-bearer-token"

                    // PATCH without url preserves original query-parameter URL; clearing secrets works
                    val patchReq =
                        UpdateWebhookTargetRequest(
                            enabled = true,
                            clearSigningSecret = true,
                            clearAuthHeader = true,
                            cooldownSeconds = 120,
                        )
                    patchReq.toString() shouldNotContain "secret"
                    val patchedRes =
                        client.patch("/api/v1/admin/webhooks/${created.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(UpdateWebhookTargetRequest.serializer(), patchReq))
                        }
                    patchedRes.status shouldBe HttpStatusCode.OK
                    val patched = MiruJson.decodeFromString<WebhookTargetDto>(patchedRes.bodyAsText())
                    patched.enabled shouldBe true
                    patched.hasSigningSecret shouldBe false
                    patched.hasAuthHeader shouldBe false
                    patched.authHeaderName.shouldBeNull()
                    patched.url shouldBe "http://notifier.media.svc.cluster.local:8080/hook?***"
                    patched.cooldownSeconds shouldBe 120

                    val deliveriesRes =
                        client.get("/api/v1/admin/webhooks/deliveries?targetId=${created.id}&eventType=webhook.test&status=DELIVERED") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    deliveriesRes.status shouldBe HttpStatusCode.OK
                    val deliveriesPage = MiruJson.decodeFromString<WebhookDeliveriesResponse>(deliveriesRes.bodyAsText())
                    deliveriesPage.totalCount shouldBe 1L
                    deliveriesPage.items shouldHaveSize 1

                    client
                        .get("/api/v1/admin/webhooks/deliveries?status=BOGUS") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/admin/webhooks/deliveries?eventType=bogus.event") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/admin/webhooks/deliveries?limit=500") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest

                    client
                        .delete("/api/v1/admin/webhooks/${created.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NoContent
                    client
                        .delete("/api/v1/admin/webhooks/${created.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NotFound
                    client
                        .post("/api/v1/admin/webhooks/missing/test") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("requires MIRU_ENCRYPTION_KEY for secrets/auth headers while allowing plain targets without it, and validates inputs") {
            createWebhookRoutesHarness(encryptionKey = null).use { h ->
                val adminLogin = h.authService.setupInitialAdmin("admin", "AdminPass1234").shouldBeRight()

                testApplication {
                    application {
                        miruModule(
                            config = h.appConfig,
                            authService = h.authService,
                            webhookService = h.webhookService,
                        )
                    }

                    // Plain target without secrets works cleanly without MIRU_ENCRYPTION_KEY
                    val plainRes =
                        client.post("/api/v1/admin/webhooks") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateWebhookTargetRequest.serializer(),
                                    CreateWebhookTargetRequest(
                                        name = "Plain Hook",
                                        url = "http://192.168.1.50:8080/hook",
                                        subscribedEvents = listOf("server.unreachable"),
                                    ),
                                ),
                            )
                        }
                    plainRes.status shouldBe HttpStatusCode.Created

                    // Target with signingSecret fails with clear MIRU_ENCRYPTION_KEY error
                    val secretRes =
                        client.post("/api/v1/admin/webhooks") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateWebhookTargetRequest.serializer(),
                                    CreateWebhookTargetRequest(
                                        name = "Signed Hook",
                                        url = "http://192.168.1.50:8080/hook",
                                        subscribedEvents = listOf("transcode.started"),
                                        signingSecret = "my-secret",
                                    ),
                                ),
                            )
                        }
                    secretRes.status shouldBe HttpStatusCode.BadRequest
                    secretRes.bodyAsText() shouldContain "MIRU_ENCRYPTION_KEY"

                    // Blocked SSRF metadata URL -> 400
                    val ssrfRes =
                        client.post("/api/v1/admin/webhooks") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateWebhookTargetRequest.serializer(),
                                    CreateWebhookTargetRequest(
                                        name = "IMDS",
                                        url = "http://169.254.169.254/latest/meta-data/",
                                        subscribedEvents = listOf("transcode.started"),
                                    ),
                                ),
                            )
                        }
                    ssrfRes.status shouldBe HttpStatusCode.BadRequest

                    // Out-of-range thresholds -> 400
                    val badThresholdRes =
                        client.post("/api/v1/admin/webhooks") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    CreateWebhookTargetRequest.serializer(),
                                    CreateWebhookTargetRequest(
                                        name = "Bad Thresholds",
                                        url = "https://hooks.example.com/miru",
                                        subscribedEvents = listOf("transcode.started"),
                                        cooldownSeconds = -1,
                                        concurrentStreamThreshold = 1,
                                        bufferingSpikeThreshold = 99,
                                        bufferingWindowSeconds = 5,
                                    ),
                                ),
                            )
                        }
                    badThresholdRes.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("validates auth header pairing, conflicting secret flags, event names, and delivery query params") {
            createWebhookRoutesHarness().use { h ->
                val adminLogin = h.authService.setupInitialAdmin("admin", "AdminPass1234").shouldBeRight()

                testApplication {
                    application {
                        miruModule(
                            config = h.appConfig,
                            authService = h.authService,
                            webhookService = h.webhookService,
                        )
                    }

                    val invalidCreateBodies =
                        listOf(
                            CreateWebhookTargetRequest(
                                name = "   ",
                                url = "http://192.168.1.50/h",
                                subscribedEvents = listOf("transcode.started"),
                            ),
                            CreateWebhookTargetRequest(
                                name = "NoEvents",
                                url = "http://192.168.1.50/h",
                                subscribedEvents = emptyList(),
                            ),
                            CreateWebhookTargetRequest(
                                name = "BadEvent",
                                url = "http://192.168.1.50/h",
                                subscribedEvents = listOf("unknown.evt"),
                            ),
                            CreateWebhookTargetRequest(
                                name = "MissingHeaderVal",
                                url = "http://192.168.1.50/h",
                                subscribedEvents = listOf("transcode.started"),
                                authHeaderName = "X-Api-Key",
                            ),
                            CreateWebhookTargetRequest(
                                name = "MissingHeaderName",
                                url = "http://192.168.1.50/h",
                                subscribedEvents = listOf("transcode.started"),
                                authHeaderValue = "secret",
                            ),
                        )
                    for (req in invalidCreateBodies) {
                        client
                            .post("/api/v1/admin/webhooks") {
                                cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                contentType(ContentType.Application.Json)
                                setBody(MiruJson.encodeToString(CreateWebhookTargetRequest.serializer(), req))
                            }.status shouldBe HttpStatusCode.BadRequest
                    }

                    val created =
                        MiruJson.decodeFromString<WebhookTargetDto>(
                            client
                                .post("/api/v1/admin/webhooks") {
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        MiruJson.encodeToString(
                                            CreateWebhookTargetRequest.serializer(),
                                            CreateWebhookTargetRequest(
                                                name = "Base",
                                                url = "http://192.168.1.50/h",
                                                subscribedEvents = listOf("transcode.started"),
                                            ),
                                        ),
                                    )
                                }.bodyAsText(),
                        )

                    val invalidPatches =
                        listOf(
                            UpdateWebhookTargetRequest(signingSecret = "abc", clearSigningSecret = true),
                            UpdateWebhookTargetRequest(authHeaderName = "X-Api-Key", clearAuthHeader = true),
                            UpdateWebhookTargetRequest(authHeaderName = "X-Api-Key"),
                            UpdateWebhookTargetRequest(authHeaderValue = "val-only"),
                        )
                    for (req in invalidPatches) {
                        client
                            .patch("/api/v1/admin/webhooks/${created.id}") {
                                cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                contentType(ContentType.Application.Json)
                                setBody(MiruJson.encodeToString(UpdateWebhookTargetRequest.serializer(), req))
                            }.status shouldBe HttpStatusCode.BadRequest
                    }

                    // Valid secret & auth header rotation on PATCH
                    client
                        .patch("/api/v1/admin/webhooks/${created.id}") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(
                                MiruJson.encodeToString(
                                    UpdateWebhookTargetRequest.serializer(),
                                    UpdateWebhookTargetRequest(
                                        signingSecret = "rotated-secret",
                                        authHeaderName = "X-Api-Key",
                                        authHeaderValue = "rotated-key",
                                    ),
                                ),
                            )
                        }.status shouldBe HttpStatusCode.OK

                    // Invalid non-numeric query parameters -> 400
                    client
                        .get("/api/v1/admin/webhooks/deliveries?limit=abc") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/admin/webhooks/deliveries?offset=xyz") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                    client
                        .get("/api/v1/admin/webhooks/deliveries?offset=-1") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    })
