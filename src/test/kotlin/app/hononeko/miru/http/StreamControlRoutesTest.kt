package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.GuardrailsConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.TerminationAuditAction
import app.hononeko.miru.persistence.model.TerminationAuditFilter
import app.hononeko.miru.persistence.model.TerminationAuditSource
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedGuardrailRuleRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedStreamTerminationAuditRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.StreamControlService
import app.hononeko.miru.service.StreamGuardrailEvaluator
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private const val TEST_PLEX_TOKEN = "plex-secret-token-control-test"

private class RecordingMediaServerAdapter : MediaServerAdapter {
    override val serverType: String = "plex"
    val terminatedCalls = mutableListOf<Pair<String, String>>()
    var terminateResponse: (sessionId: String, reason: String) -> Either<MiruError, Unit> = { _, _ -> Unit.right() }

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> {
        terminatedCalls += sessionId to reason
        return terminateResponse(sessionId, reason)
    }

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> = error("unused")

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> = error("unused")

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

private class StreamControlHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val adapter: RecordingMediaServerAdapter,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val activeSessionRepo: ExposedActiveSessionRepository,
    val guardrailRepo: ExposedGuardrailRuleRepository,
    val auditRepo: ExposedStreamTerminationAuditRepository,
    val authService: AuthService,
    val appConfig: AppConfig,
) : AutoCloseable {
    var nowMs: Long = 1_000_000L
    private val seq = AtomicInteger(0)

    val service =
        StreamControlService(
            adapter = adapter,
            activeSessionRepository = activeSessionRepo,
            userRepository = userRepo,
            guardrailRuleRepository = guardrailRepo,
            auditRepository = auditRepo,
            guardrailsConfig = appConfig.guardrails,
            evaluator = StreamGuardrailEvaluator(),
            clock = { nowMs },
            idGenerator = { "aud-${seq.incrementAndGet()}" },
        )

    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createHarness(guardrailsConfig: GuardrailsConfig = GuardrailsConfig()): StreamControlHarness {
    val dir = Files.createTempDirectory("miru-stream-control-test-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val authConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        )
    val userRepo = ExposedUserRepository(handle)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = "http://plex.internal:32400", token = TEST_PLEX_TOKEN),
            session = SessionConfig(),
            auth = authConfig,
            guardrails = guardrailsConfig,
        )
    return StreamControlHarness(
        tempDir = dir,
        dbHandle = handle,
        adapter = RecordingMediaServerAdapter(),
        serverRepo = ExposedMediaServerRepository(handle),
        userRepo = userRepo,
        activeSessionRepo = ExposedActiveSessionRepository(handle),
        guardrailRepo = ExposedGuardrailRuleRepository(handle),
        auditRepo = ExposedStreamTerminationAuditRepository(handle),
        authService = authService,
        appConfig = appConfig,
    )
}

private suspend fun seedStreamFixture(harness: StreamControlHarness): ActiveSessionRecord {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Main Plex",
                serverType = "plex",
                machineId = "mach-1",
                baseUrl = "http://plex.internal:32400",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    harness.userRepo
        .upsert(
            UserRecord(
                id = "mu-bob",
                serverId = "srv-1",
                externalUserId = "200",
                username = "bob",
                displayName = "Bob Viewer",
                isAdmin = false,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    val session =
        ActiveSessionRecord(
            serverId = "srv-1",
            sessionKey = "10",
            sessionId = "plex-sess-10",
            userId = "mu-bob",
            media =
                MediaMetadataRecord(
                    ratingKey = "ep-1",
                    mediaType = "episode",
                    title = "Good News About Hell",
                    parentTitle = "Season 1",
                    grandparentTitle = "Severance",
                    parentIndex = 1,
                    mediaIndex = 1,
                    durationMs = 3_420_000L,
                ),
            viewOffsetMs = 500_000L,
            state = "playing",
            streamTech =
                StreamTechRecord(
                    streamDecision = "transcode",
                    videoDecision = "transcode",
                    audioDecision = "copy",
                    videoResolution = "4k",
                ),
            player = PlayerRecord(playerName = "Living Room TV", location = "wan"),
            startedAtMs = 5_000L,
            lastSeenAtMs = 10_000L,
        )
    harness.activeSessionRepo.upsert(session).shouldBeRight()
    return session
}

private fun ApplicationTestBuilder.setupControlApp(harness: StreamControlHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            streamControlService = harness.service,
        )
    }
}

class StreamControlRoutesTest :
    FunSpec({
        test(
            "POST /api/v1/streams/{sessionId}/terminate enforces admin RBAC, validates inputs, " +
                "handles upstream errors, and writes audit log",
        ) {
            createHarness().use { harness ->
                seedStreamFixture(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "bobuser",
                        password = "Bob-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("mu-bob"),
                    ).shouldBeRight()
                val bobLogin = harness.authService.login("bobuser", "Bob-password-123").shouldBeRight()

                testApplication {
                    setupControlApp(harness)

                    // 401 when unauthenticated
                    val unauth = client.post("/api/v1/streams/plex-sess-10/terminate")
                    unauth.status shouldBe HttpStatusCode.Unauthorized

                    // 403 for non-admin USER even when linked to stream's media user
                    val forbidden =
                        client.post("/api/v1/streams/plex-sess-10/terminate") {
                            cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody("""{"reason":"nope"}""")
                        }
                    forbidden.status shouldBe HttpStatusCode.Forbidden
                    harness.adapter.terminatedCalls.shouldBeEmpty()

                    // 404 when session is unknown
                    val notFound =
                        client.post("/api/v1/streams/unknown-sess/terminate") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody("""{"reason":"Maintenance"}""")
                        }
                    notFound.status shouldBe HttpStatusCode.NotFound

                    // 400 on overly long reason
                    val tooLong =
                        client.post("/api/v1/streams/plex-sess-10/terminate") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody(MiruJson.encodeToString(TerminateStreamRequest("x".repeat(501))))
                        }
                    tooLong.status shouldBe HttpStatusCode.BadRequest

                    // 502 when upstream Plex fails without writing a TERMINATED audit row or leaking token
                    harness.adapter.terminateResponse = { _, _ ->
                        MiruError.UpstreamUnavailable("plex", "Connection refused $TEST_PLEX_TOKEN").left()
                    }
                    val upstreamFail =
                        client.post("/api/v1/streams/plex-sess-10/terminate") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody("""{"reason":"Maintenance"}""")
                        }
                    upstreamFail.status shouldBe HttpStatusCode.BadGateway
                    upstreamFail.bodyAsText() shouldNotContain TEST_PLEX_TOKEN
                    harness.auditRepo.query(TerminationAuditFilter()).shouldBeRight().totalCount shouldBe 0L

                    // 200 with custom reason
                    harness.adapter.terminateResponse = { _, _ -> Unit.right() }
                    val customSuccess =
                        client.post("/api/v1/streams/plex-sess-10/terminate") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody("""{"reason":"Please switch to 1080p"}""")
                        }
                    customSuccess.status shouldBe HttpStatusCode.OK
                    val customDto = MiruJson.decodeFromString<TerminateStreamResponse>(customSuccess.bodyAsText())
                    customDto.sessionId shouldBe "plex-sess-10"
                    customDto.sessionKey shouldBe "10"
                    customDto.reason shouldBe "Please switch to 1080p"

                    // 200 by sessionKey with empty body -> uses default message
                    val defaultSuccess =
                        client.post("/api/v1/streams/10/terminate") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    defaultSuccess.status shouldBe HttpStatusCode.OK
                    val defaultDto = MiruJson.decodeFromString<TerminateStreamResponse>(defaultSuccess.bodyAsText())
                    defaultDto.reason shouldBe StreamControlService.DEFAULT_MANUAL_TERMINATION_REASON

                    harness.adapter.terminatedCalls shouldContainExactly
                        listOf(
                            "plex-sess-10" to "Maintenance",
                            "plex-sess-10" to "Please switch to 1080p",
                            "plex-sess-10" to StreamControlService.DEFAULT_MANUAL_TERMINATION_REASON,
                        )

                    val auditPage = harness.auditRepo.query(TerminationAuditFilter()).shouldBeRight()
                    auditPage.totalCount shouldBe 2L
                    val latestAudit = auditPage.items.first()
                    latestAudit.source shouldBe TerminationAuditSource.MANUAL
                    latestAudit.action shouldBe TerminationAuditAction.TERMINATED
                    latestAudit.actorName shouldBe "admin"
                    latestAudit.targetUsername shouldBe "Bob Viewer"
                    latestAudit.mediaTitle shouldBe "Severance — S01E01 · Good News About Hell"
                    latestAudit.ruleType.shouldBeNull()
                }
            }
        }

        test("guardrail configuration and audit endpoints enforce RBAC, validate rules, and persist changes") {
            createHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService
                    .createAccount("viewer", "Viewer-password-123", role = MiruRole.USER)
                    .shouldBeRight()
                val viewerLogin = harness.authService.login("viewer", "Viewer-password-123").shouldBeRight()

                testApplication {
                    setupControlApp(harness)

                    client.get("/api/v1/admin/guardrails").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/admin/guardrails") {
                        cookie(SESSION_COOKIE_NAME, viewerLogin.signedToken)
                    }.status shouldBe HttpStatusCode.Forbidden
                    client.put("/api/v1/admin/guardrails/TRANSCODE_4K") {
                        cookie(SESSION_COOKIE_NAME, viewerLogin.signedToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"mode":"WARN"}""")
                    }.status shouldBe HttpStatusCode.Forbidden
                    client.get("/api/v1/admin/guardrails/audit") {
                        cookie(SESSION_COOKIE_NAME, viewerLogin.signedToken)
                    }.status shouldBe HttpStatusCode.Forbidden

                    val initialList =
                        client.get("/api/v1/admin/guardrails") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    initialList.status shouldBe HttpStatusCode.OK
                    val initialRules = MiruJson.decodeFromString<GuardrailRulesResponse>(initialList.bodyAsText()).rules
                    initialRules.map { it.ruleType } shouldContainExactly
                        listOf(
                            GuardrailRuleType.TRANSCODE_4K,
                            GuardrailRuleType.PAUSED_TIMEOUT,
                            GuardrailRuleType.CONCURRENT_STREAMS,
                        )
                    initialRules.all { it.mode == GuardrailMode.DISABLED && !it.isOverridden } shouldBe true

                    // Validation failures
                    client.put("/api/v1/admin/guardrails/UNKNOWN") {
                        cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"mode":"WARN"}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    client.put("/api/v1/admin/guardrails/TRANSCODE_4K") {
                        cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"mode":"WARN","threshold":10}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    client.put("/api/v1/admin/guardrails/PAUSED_TIMEOUT") {
                        cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"mode":"ENFORCE","threshold":0}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    client.get("/api/v1/admin/guardrails/audit?source=INVALID") {
                        cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                    }.status shouldBe HttpStatusCode.BadRequest

                    // Valid update persists
                    val updatedResp =
                        client.put("/api/v1/admin/guardrails/PAUSED_TIMEOUT") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            contentType(ContentType.Application.Json)
                            setBody("""{"mode":"WARN","threshold":45,"message":"Paused >45m warning"}""")
                        }
                    updatedResp.status shouldBe HttpStatusCode.OK
                    val updatedRule = MiruJson.decodeFromString<GuardrailRuleDto>(updatedResp.bodyAsText())
                    updatedRule.mode shouldBe GuardrailMode.WARN
                    updatedRule.threshold shouldBe 45
                    updatedRule.thresholdUnit shouldBe "minutes"
                    updatedRule.message shouldBe "Paused >45m warning"
                    updatedRule.isOverridden shouldBe true
                    updatedRule.updatedBy shouldBe "admin"
                    updatedRule.updatedAtMs.shouldNotBeNull()

                    val auditResp =
                        client.get("/api/v1/admin/guardrails/audit?limit=10&offset=0") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    auditResp.status shouldBe HttpStatusCode.OK
                    val auditList = MiruJson.decodeFromString<TerminationAuditListResponse>(auditResp.bodyAsText())
                    auditList.totalCount shouldBe 0L
                    auditList.items.shouldBeEmpty()
                }
            }
        }

        test("StreamControlService.evaluateActiveSessions performs dry-run WARN without terminating and records ENFORCE/TERMINATE_FAILED") {
            createHarness(
                guardrailsConfig =
                    GuardrailsConfig(
                        transcode4kMode = GuardrailMode.WARN,
                        transcode4kMessage = "Dry-run 4K warning",
                    ),
            ).use { harness ->
                val session = seedStreamFixture(harness)

                // Dry-run WARN records WARNED without calling Plex terminate
                harness.service.evaluateActiveSessions(listOf(session))
                harness.adapter.terminatedCalls.shouldBeEmpty()

                val afterWarn = harness.auditRepo.query(TerminationAuditFilter()).shouldBeRight()
                afterWarn.totalCount shouldBe 1L
                val warnEntry = afterWarn.items.single()
                warnEntry.source shouldBe TerminationAuditSource.GUARDRAIL
                warnEntry.action shouldBe TerminationAuditAction.WARNED
                warnEntry.ruleType shouldBe GuardrailRuleType.TRANSCODE_4K
                warnEntry.reason shouldBe "Dry-run 4K warning"

                // Switch rule to ENFORCE and simulate upstream failure -> records TERMINATE_FAILED
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.service
                    .updateGuardrail(
                        adminLogin.principal,
                        "TRANSCODE_4K",
                        UpdateGuardrailRuleRequest(mode = GuardrailMode.ENFORCE, message = "Enforced 4K limit"),
                    ).shouldBeRight()

                harness.adapter.terminateResponse = { _, _ -> MiruError.UpstreamUnavailable("plex", "Plex unreachable").left() }
                harness.nowMs += 5_000L
                harness.service.evaluateActiveSessions(listOf(session))
                harness.adapter.terminatedCalls shouldContainExactly listOf("plex-sess-10" to "Enforced 4K limit")

                val afterFail = harness.auditRepo.query(TerminationAuditFilter()).shouldBeRight()
                afterFail.totalCount shouldBe 2L
                afterFail.items.first().action shouldBe TerminationAuditAction.TERMINATE_FAILED
                afterFail.items.first().errorDetail shouldBe "Plex unreachable"

                // After cooldown and recovery, ENFORCE succeeds and records TERMINATED
                harness.adapter.terminateResponse = { _, _ -> Unit.right() }
                harness.nowMs += StreamGuardrailEvaluator.DEFAULT_ENFORCE_RETRY_COOLDOWN_MS + 1_000L
                harness.service.evaluateActiveSessions(listOf(session))

                val afterSuccess = harness.auditRepo.query(TerminationAuditFilter()).shouldBeRight()
                afterSuccess.totalCount shouldBe 3L
                afterSuccess.items.first().action shouldBe TerminationAuditAction.TERMINATED
                afterSuccess.items.first().errorDetail.shouldBeNull()
            }
        }
    })
