package app.hononeko.miru.webhook

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.WebhookDeliveryFilter
import app.hononeko.miru.persistence.model.WebhookDeliveryStatus
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedGuardrailRuleRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedStreamTerminationAuditRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookDeliveryRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookTargetRepository
import app.hononeko.miru.service.StreamControlService
import app.hononeko.miru.session.ActiveSessionsSink
import app.hononeko.miru.session.ReconcileTrigger
import app.hononeko.miru.session.SessionReconciler
import app.hononeko.miru.session.SessionStateEngine
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

private const val TEST_ENC_KEY = "webhook-dispatcher-test-encryption-key-32chars"
private val LAN_DNS = DnsResolver { listOf(InetAddress.getByName("192.168.1.50")) }

private data class CapturedWebhookRequest(
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

private class StubAdapter : MediaServerAdapter {
    override val serverType: String = "plex"
    var fetchResult: Either<MiruError, List<ActiveStreamSnapshot>> = emptyList<ActiveStreamSnapshot>().right()

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> = fetchResult

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> = Unit.right()

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> = error("unused")

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> = error("unused")

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> = error("unused")

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> = error("unused")

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> = error("unused")

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> = error("unused")

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> = error("unused")
}

class WebhookDispatcherAndRunnerTest :
    FunSpec({
        test("signs payload with HMAC-SHA256, sends custom auth header, and persists DELIVERED row") {
            val dir = Files.createTempDirectory("miru-wh-dispatch-")
            val db = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val captured = CopyOnWriteArrayList<CapturedWebhookRequest>()
            val httpClient =
                createWebhookHttpClient(
                    engine =
                        MockEngine { request ->
                            val body = (request.body as TextContent).text
                            captured +=
                                CapturedWebhookRequest(
                                    url = request.url.toString(),
                                    body = body,
                                    headers = request.headers.entries().associate { (k, v) -> k to v.joinToString(",") },
                                )
                            respond(
                                content = """{"ok":true}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            try {
                val targetRepo = ExposedWebhookTargetRepository(db)
                val deliveryRepo = ExposedWebhookDeliveryRepository(db)
                val encSecret = CredentialCrypto.encrypt("super-signing-secret", TEST_ENC_KEY).shouldBeRight()
                val encAuth = CredentialCrypto.encrypt("Bearer my-notifier-token", TEST_ENC_KEY).shouldBeRight()
                val target =
                    WebhookTargetRecord(
                        id = "wh-1",
                        name = "Notifier",
                        url = "http://notifier.local:8080/hook?token=url-secret",
                        subscribedEvents = listOf(WebhookEventType.TRANSCODE_STARTED),
                        encryptedSigningSecret = encSecret,
                        authHeaderName = "Authorization",
                        encryptedAuthHeaderValue = encAuth,
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                targetRepo.insert(target).shouldBeRight()

                val nowMs = AtomicLong(100_000L)
                val dispatcher =
                    WebhookDispatcher(
                        httpClientProvider = { httpClient },
                        deliveryRepository = deliveryRepo,
                        encryptionKey = TEST_ENC_KEY,
                        appVersion = "1.2.3",
                        scope = scope,
                        dnsResolver = LAN_DNS,
                        clock = { nowMs.addAndGet(15L) },
                        idGenerator = { "del-100" },
                    )
                val evaluator = WebhookEventEvaluator(idGenerator = { "evt-100" })
                val payload = evaluator.buildTestPayload(WebhookServerPayload("srv-1", "Main Plex"), 100_000L)

                val record = dispatcher.dispatchTest(target, payload).shouldBeRight()
                record.id shouldBe "del-100"
                record.status shouldBe WebhookDeliveryStatus.DELIVERED
                record.attemptCount shouldBe 1
                record.httpStatus shouldBe 200
                record.errorMessage.shouldBeNull()

                captured shouldHaveSize 1
                val req = captured[0]
                req.headers[HttpHeaders.UserAgent] shouldBe "Miru/1.2.3"
                req.headers["X-Miru-Event"] shouldBe "webhook.test"
                req.headers["X-Miru-Delivery"] shouldBe "del-100"
                req.headers["Authorization"] shouldBe "Bearer my-notifier-token"
                req.headers["X-Miru-Signature-256"] shouldBe
                    WebhookUrlSafety.signPayload("super-signing-secret", req.body.toByteArray(Charsets.UTF_8))

                val decoded = MiruJson.decodeFromString<WebhookPayload>(req.body)
                decoded.schemaVersion shouldBe 1
                decoded.event shouldBe "webhook.test"

                val saved = deliveryRepo.query(WebhookDeliveryFilter(targetId = "wh-1")).shouldBeRight()
                saved.totalCount shouldBe 1L
                saved.items[0].status shouldBe WebhookDeliveryStatus.DELIVERED
            } finally {
                httpClient.close()
                scope.cancel()
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("retries transient 503/network errors with exponential backoff and skips retries on 4xx/3xx/SSRF") {
            val dir = Files.createTempDirectory("miru-wh-retry-")
            val db = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val attemptCounter = AtomicInteger(0)
            var scenario = "retry-then-succeed"
            val httpClient =
                createWebhookHttpClient(
                    engine =
                        MockEngine {
                            val attempt = attemptCounter.incrementAndGet()
                            when (scenario) {
                                "retry-then-succeed" ->
                                    when (attempt) {
                                        1 -> respond(content = "busy", status = HttpStatusCode.ServiceUnavailable)
                                        2 -> throw IOException("Connection reset by peer")
                                        else -> respond(content = "ok", status = HttpStatusCode.NoContent)
                                    }

                                "400-no-retry" ->
                                    respond(
                                        content = "bad payload " + "z".repeat(4096),
                                        status = HttpStatusCode.BadRequest,
                                    )

                                "302-no-redirect" ->
                                    respond(
                                        content = "",
                                        status = HttpStatusCode.Found,
                                        headers = headersOf(HttpHeaders.Location, "http://169.254.169.254/latest/meta-data"),
                                    )

                                else ->
                                    respond(content = "rate limited", status = HttpStatusCode.TooManyRequests)
                            }
                        },
                )

            try {
                val targetRepo = ExposedWebhookTargetRepository(db)
                val deliveryRepo = ExposedWebhookDeliveryRepository(db)
                val target =
                    WebhookTargetRecord(
                        id = "wh-retry",
                        name = "Retry Hook",
                        url = "http://notifier.local:8080/hook",
                        subscribedEvents = listOf(WebhookEventType.TRANSCODE_STARTED),
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                targetRepo.insert(target).shouldBeRight()

                val observedDelays = mutableListOf<Long>()
                val observedStatusesDuringRetry = mutableListOf<WebhookDeliveryStatus>()
                val idSeq = AtomicInteger(0)
                val dispatcher =
                    WebhookDispatcher(
                        httpClientProvider = { httpClient },
                        deliveryRepository = deliveryRepo,
                        encryptionKey = TEST_ENC_KEY,
                        appVersion = "1.0.0",
                        scope = scope,
                        dnsResolver = LAN_DNS,
                        idGenerator = { "del-${idSeq.incrementAndGet()}" },
                        retryDelaysMs = listOf(2_000L, 6_000L),
                        delayFn = { ms ->
                            observedDelays += ms
                            val rows = deliveryRepo.query(WebhookDeliveryFilter(targetId = "wh-retry")).shouldBeRight().items
                            rows.firstOrNull()?.let { observedStatusesDuringRetry += it.status }
                        },
                        maxRetainedDeliveries = 3,
                    )
                val evaluator = WebhookEventEvaluator()

                // 1) 503 -> IOException -> 204 succeeds on attempt 3
                val delivered = dispatcher.deliverInternal(target, evaluator.buildTestPayload(null, 1_000L))
                delivered.status shouldBe WebhookDeliveryStatus.DELIVERED
                delivered.attemptCount shouldBe 3
                delivered.httpStatus shouldBe 204
                observedDelays shouldContainExactly listOf(2_000L, 6_000L)
                observedStatusesDuringRetry shouldContainExactly
                    listOf(WebhookDeliveryStatus.RETRYING, WebhookDeliveryStatus.RETRYING)

                // 2) 400 fails immediately without retries and truncates huge error body
                scenario = "400-no-retry"
                attemptCounter.set(0)
                observedDelays.clear()
                val badReq = dispatcher.deliverInternal(target, evaluator.buildTestPayload(null, 2_000L))
                badReq.status shouldBe WebhookDeliveryStatus.FAILED
                badReq.attemptCount shouldBe 1
                badReq.httpStatus shouldBe 400
                observedDelays shouldHaveSize 0
                badReq.errorMessage.shouldNotBeNull().length shouldBe 500

                // 3) 302 redirect fails immediately without retries
                scenario = "302-no-redirect"
                attemptCounter.set(0)
                val redirect = dispatcher.deliverInternal(target, evaluator.buildTestPayload(null, 3_000L))
                redirect.status shouldBe WebhookDeliveryStatus.FAILED
                redirect.attemptCount shouldBe 1
                redirect.httpStatus shouldBe 302
                redirect.errorMessage.shouldNotBeNull() shouldContain "redirects are disabled"

                // 4) 429 exhausts all 3 attempts and triggers retention pruning to cap 3 rows
                scenario = "429-exhaust"
                attemptCounter.set(0)
                val exhausted = dispatcher.deliverInternal(target, evaluator.buildTestPayload(null, 4_000L))
                exhausted.status shouldBe WebhookDeliveryStatus.FAILED
                exhausted.attemptCount shouldBe 3
                exhausted.httpStatus shouldBe 429
                deliveryRepo.query().shouldBeRight().totalCount shouldBe 3L

                // 5) SSRF DNS resolution to 169.254.169.254 blocks without hitting HTTP client
                attemptCounter.set(0)
                val imdsDispatcher =
                    WebhookDispatcher(
                        httpClientProvider = { httpClient },
                        deliveryRepository = deliveryRepo,
                        encryptionKey = TEST_ENC_KEY,
                        appVersion = "1.0.0",
                        scope = scope,
                        dnsResolver = DnsResolver { listOf(InetAddress.getByName("169.254.169.254")) },
                        delayFn = {},
                    )
                val ssrfBlocked = imdsDispatcher.deliverInternal(target, evaluator.buildTestPayload(null, 5_000L))
                ssrfBlocked.status shouldBe WebhookDeliveryStatus.FAILED
                ssrfBlocked.attemptCount shouldBe 1
                attemptCounter.get() shouldBe 0

                // 6) Decryption failure when encryption key is missing records clean FAILED row
                val encSecret = CredentialCrypto.encrypt("secret-value", TEST_ENC_KEY).shouldBeRight()
                val secretTarget = target.copy(encryptedSigningSecret = encSecret)
                val noKeyDispatcher =
                    WebhookDispatcher(
                        httpClientProvider = { httpClient },
                        deliveryRepository = deliveryRepo,
                        encryptionKey = null,
                        appVersion = "1.0.0",
                        scope = scope,
                        dnsResolver = LAN_DNS,
                    )
                val decryptFailed = noKeyDispatcher.deliverInternal(secretTarget, evaluator.buildTestPayload(null, 6_000L))
                decryptFailed.status shouldBe WebhookDeliveryStatus.FAILED
                decryptFailed.errorMessage.shouldNotBeNull() shouldNotContain "secret-value"
            } finally {
                httpClient.close()
                scope.cancel()
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("drops events while the queue is full and delivers what is in it once a slot is free") {
            val dir = Files.createTempDirectory("miru-wh-queue-")
            val db = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val gate = CompletableDeferred<Unit>()
            val received = CopyOnWriteArrayList<String>()
            val mostAtOnce = AtomicInteger(0)
            val inFlight = AtomicInteger(0)
            val httpClient =
                createWebhookHttpClient(
                    engine =
                        MockEngine { request ->
                            val now = inFlight.incrementAndGet()
                            mostAtOnce.updateAndGet { maxOf(it, now) }
                            received += (request.body as TextContent).text
                            gate.await()
                            inFlight.decrementAndGet()
                            respond(content = "", status = HttpStatusCode.OK)
                        },
                )

            try {
                val target =
                    WebhookTargetRecord(
                        id = "wh-1",
                        name = "Slow",
                        url = "http://notifier.local:8080/hook",
                        subscribedEvents = listOf(WebhookEventType.SERVER_RECOVERED),
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                ExposedWebhookTargetRepository(db).insert(target).shouldBeRight()
                val deliveryRepo = ExposedWebhookDeliveryRepository(db)
                val dispatcher =
                    WebhookDispatcher(
                        httpClientProvider = { httpClient },
                        deliveryRepository = deliveryRepo,
                        encryptionKey = TEST_ENC_KEY,
                        appVersion = "test",
                        scope = scope,
                        dnsResolver = LAN_DNS,
                        maxConcurrentDeliveries = 2,
                        queueCapacity = 3,
                    )

                fun payload(id: String) = WebhookPayload(event = "server.recovered", eventId = id, timestamp = "2026-11-25T00:00:00Z")

                dispatcher.start()
                dispatcher.enqueue(target, payload("sent-1")) shouldBe true
                dispatcher.enqueue(target, payload("sent-2")) shouldBe true
                withTimeout(5_000L) { while (received.size < 2) delay(10L) }

                // Both slots are taken, so three more wait and the rest are dropped
                (1..3).forEach { dispatcher.enqueue(target, payload("waiting-$it")) shouldBe true }
                delay(100L)
                dispatcher.enqueue(target, payload("dropped")) shouldBe false
                received shouldHaveSize 2

                gate.complete(Unit)
                withTimeout(5_000L) {
                    while (deliveryRepo.query(WebhookDeliveryFilter()).shouldBeRight().totalCount < 5L) delay(10L)
                }
                received shouldHaveSize 5
                received.none { "dropped" in it } shouldBe true
                mostAtOnce.get() shouldBe 2

                // The slots are free again, also after a stop and a start
                dispatcher.stop()
                dispatcher.start()
                (1..3).forEach { dispatcher.enqueue(target, payload("later-$it")) shouldBe true }
                withTimeout(5_000L) { while (received.size < 8) delay(10L) }
            } finally {
                scope.cancel()
                httpClient.close()
                db.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("WebhookRunner wires SessionStateEngine, StreamControlService, and SessionReconciler health signals end-to-end") {
            val dir = Files.createTempDirectory("miru-wh-runner-")
            val db = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val receivedEvents = CopyOnWriteArrayList<String>()
            val httpClient =
                createWebhookHttpClient(
                    engine =
                        MockEngine { request ->
                            receivedEvents += request.headers["X-Miru-Event"].orEmpty()
                            respond(content = "ok", status = HttpStatusCode.OK)
                        },
                )

            try {
                val serverRepo = ExposedMediaServerRepository(db)
                val userRepo = ExposedUserRepository(db)
                val activeRepo = ExposedActiveSessionRepository(db)
                val historyRepo = ExposedPlaybackHistoryRepository(db)
                val targetRepo = ExposedWebhookTargetRepository(db)
                val deliveryRepo = ExposedWebhookDeliveryRepository(db)

                serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-1",
                            name = "Homelab Plex",
                            serverType = "plex",
                            machineId = "mach-1",
                            baseUrl = "http://plex.local:32400",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                val target =
                    WebhookTargetRecord(
                        id = "wh-e2e",
                        name = "All Events",
                        url = "http://notifier.local:8080/events",
                        subscribedEvents = WebhookEventType.entries,
                        cooldownSeconds = 0,
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    )
                targetRepo.insert(target).shouldBeRight()

                val stubAdapter = StubAdapter()
                val sessionEngine =
                    SessionStateEngine(
                        serverIdProvider = { "srv-1".right() },
                        userRepository = userRepo,
                        activeSessionRepository = activeRepo,
                        playbackHistoryRepository = historyRepo,
                    )
                val streamControl =
                    StreamControlService(
                        adapter = stubAdapter,
                        activeSessionRepository = activeRepo,
                        userRepository = userRepo,
                        guardrailRuleRepository = ExposedGuardrailRuleRepository(db),
                        auditRepository = ExposedStreamTerminationAuditRepository(db),
                    )
                val healthSignals = MutableSharedFlow<ReconcileHealthSignal>(extraBufferCapacity = 16)
                val evaluator = WebhookEventEvaluator(unreachableFailureThreshold = 2)
                val dispatcher =
                    WebhookDispatcher(
                        httpClientProvider = { httpClient },
                        deliveryRepository = deliveryRepo,
                        encryptionKey = TEST_ENC_KEY,
                        appVersion = "1.0.0",
                        scope = scope,
                        dnsResolver = LAN_DNS,
                        delayFn = {},
                    )
                val runner =
                    WebhookRunner(
                        sessionEngine = sessionEngine,
                        terminatedEvents = streamControl.terminatedEvents,
                        reconcileHealthSignals = healthSignals,
                        targetRepository = targetRepo,
                        userRepository = userRepo,
                        serverInfoProvider = { WebhookServerPayload("srv-1", "Homelab Plex") },
                        evaluator = evaluator,
                        dispatcher = dispatcher,
                        scope = scope,
                    )

                dispatcher.start()
                runner.start()
                delay(50.milliseconds)

                // 1) Transcode started via SessionStateEngine
                val transcodeSnapshot =
                    ActiveStreamSnapshot(
                        sessionId = "plex-s1",
                        sessionKey = "10",
                        state = PlaybackState.PLAYING,
                        viewOffsetMs = 10_000L,
                        media = StreamMedia(ratingKey = "m1", mediaType = MediaType.MOVIE, title = "Akira"),
                        user = StreamUser(externalId = "42", username = "bob"),
                        player = StreamPlayer(machineIdentifier = "p1", product = "Web", location = StreamLocation.LAN, isLocal = true),
                        technical =
                            StreamTechnicalInfo(
                                decision = StreamDecision.TRANSCODE,
                                videoDecision = StreamDecision.TRANSCODE,
                                videoResolution = "1080p",
                            ),
                    )
                sessionEngine.reconcile(listOf(transcodeSnapshot), ReconcileTrigger.PERIODIC).shouldBeRight()

                // 2) Manual stream termination via StreamControlService
                val admin = MiruPrincipal("acc-1", "admin", null, MiruRole.ADMIN)
                streamControl.terminateStream(admin, "plex-s1", "Stop transcode").shouldBeRight()

                // 3) Reconciler failure/recovery hook emitting health signals
                val reconciler =
                    SessionReconciler(
                        adapter = stubAdapter,
                        sink = ActiveSessionsSink { _, _, _ -> },
                        scope = scope,
                        interval = 1.hours,
                        onReconcileOutcome = { outcome ->
                            healthSignals.tryEmit(
                                outcome.fold(
                                    ifLeft = { err -> ReconcileHealthSignal(false, err.message, System.currentTimeMillis()) },
                                    ifRight = { ReconcileHealthSignal(true, null, System.currentTimeMillis()) },
                                ),
                            )
                        },
                    )
                stubAdapter.fetchResult = MiruError.UpstreamUnavailable("plex", "Connection refused").left()
                reconciler.start()
                delay(50.milliseconds)
                reconciler.stop()

                // Emit second consecutive failure + recovery
                healthSignals.tryEmit(ReconcileHealthSignal(false, "Connection refused", System.currentTimeMillis() + 5_000L))
                healthSignals.tryEmit(ReconcileHealthSignal(true, null, System.currentTimeMillis() + 30_000L))

                withTimeout(5_000L) {
                    while (receivedEvents.size < 4) {
                        delay(25.milliseconds)
                    }
                }
                receivedEvents.toSet() shouldBe
                    setOf("transcode.started", "stream.terminated", "server.unreachable", "server.recovered")

                runner.stop()
                dispatcher.stop()
            } finally {
                httpClient.close()
                scope.cancel()
                db.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
