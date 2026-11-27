package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamTechnicalInfo
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
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.StopReason
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SECRET_PLEX_TOKEN = "plex-secret-token-xyz"
private const val INTERNAL_PLEX_URL = "http://plex.internal.local:32400"

private class StreamsUsersHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val serverRepo: ExposedMediaServerRepository,
    val userRepo: ExposedUserRepository,
    val activeSessionRepo: ExposedActiveSessionRepository,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val accountRepo: ExposedAuthAccountRepository,
    val authService: AuthService,
    val appConfig: AppConfig,
    val liveTechRef: AtomicReference<Map<ActiveSessionKey, StreamTechnicalInfo>> = AtomicReference(emptyMap()),
    val sessionEvents: MutableSharedFlow<SessionEvent> = MutableSharedFlow(extraBufferCapacity = 32),
) : AutoCloseable {
    val service =
        StreamsAndUsersService(
            activeSessionRepository = activeSessionRepo,
            userRepository = userRepo,
            playbackHistoryRepository = historyRepo,
            liveTechnicalProvider = { liveTechRef.get() },
            serverNameProvider = { "Main Plex" },
            mediaServerRepository = serverRepo,
            authAccountRepository = accountRepo,
        )

    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createStreamsUsersHarness(): StreamsUsersHarness {
    val dir = Files.createTempDirectory("miru-streams-users-test-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val authConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        )
    val userRepo = ExposedUserRepository(handle)
    val accountRepo = ExposedAuthAccountRepository(handle)
    val authService =
        AuthService(
            accountRepository = accountRepo,
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
            plex = PlexConfig(baseUrl = INTERNAL_PLEX_URL, token = SECRET_PLEX_TOKEN),
            session = SessionConfig(),
            auth = authConfig,
        )
    return StreamsUsersHarness(
        tempDir = dir,
        dbHandle = handle,
        serverRepo = ExposedMediaServerRepository(handle),
        userRepo = userRepo,
        activeSessionRepo = ExposedActiveSessionRepository(handle),
        historyRepo = ExposedPlaybackHistoryRepository(handle),
        accountRepo = accountRepo,
        authService = authService,
        appConfig = appConfig,
    )
}

private suspend fun seedTestFixtureData(harness: StreamsUsersHarness) {
    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "KerrLab Plex",
                serverType = "PLEX",
                machineId = "mach-1",
                baseUrl = INTERNAL_PLEX_URL,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()

    harness.userRepo
        .upsert(
            UserRecord(
                id = "mu-alice",
                serverId = "srv-1",
                externalUserId = "100",
                username = "alice",
                displayName = "Alice",
                thumbUrl = "https://plex.tv/users/100/avatar?X-Plex-Token=$SECRET_PLEX_TOKEN",
                isAdmin = true,
                lastSeenAtMs = 9_000L,
                createdAtMs = 1_000L,
                updatedAtMs = 9_000L,
            ),
        ).shouldBeRight()

    harness.userRepo
        .upsert(
            UserRecord(
                id = "mu-bob",
                serverId = "srv-1",
                externalUserId = "200",
                username = "bob",
                displayName = "Bob",
                thumbUrl = null,
                isAdmin = false,
                lastSeenAtMs = 8_000L,
                createdAtMs = 1_000L,
                updatedAtMs = 8_000L,
            ),
        ).shouldBeRight()
}

private fun ApplicationTestBuilder.setupStreamsApp(
    harness: StreamsUsersHarness,
    sseKeepaliveInterval: Duration = 15.seconds,
) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            streamsAndUsersService = harness.service,
            sessionEvents = harness.sessionEvents,
            sseKeepaliveInterval = sseKeepaliveInterval,
        )
    }
}

private data class ParsedSseFrame(
    val event: String? = null,
    val data: String? = null,
    val comment: String? = null,
)

class StreamsAndUsersRoutesTest :
    FunSpec({
        test("GET /api/v1/streams enforces auth, scopes RBAC, overlays transcode metadata, and proxies artwork URLs") {
            createStreamsUsersHarness().use { harness ->
                seedTestFixtureData(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "bobuser",
                        password = "Bob-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("mu-bob"),
                    ).shouldBeRight()
                val bobLogin = harness.authService.login("bobuser", "Bob-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "unlinkeduser",
                        password = "Unlinked-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = emptySet(),
                    ).shouldBeRight()
                val unlinkedLogin = harness.authService.login("unlinkeduser", "Unlinked-password-123").shouldBeRight()

                val movieSession =
                    ActiveSessionRecord(
                        serverId = "srv-1",
                        sessionKey = "10",
                        sessionId = "plex-sess-10",
                        userId = "mu-alice",
                        media =
                            MediaMetadataRecord(
                                ratingKey = "mov-55",
                                mediaType = "movie",
                                title = "Spirited Away",
                                year = 2001,
                                thumbPath = "/library/metadata/mov-55/thumb/1?X-Plex-Token=$SECRET_PLEX_TOKEN",
                                librarySectionKey = "sec-movies",
                                durationMs = 7_500_000L,
                            ),
                        viewOffsetMs = 1_875_000L,
                        state = "playing",
                        streamTech =
                            StreamTechRecord(
                                streamDecision = "transcode",
                                videoDecision = "transcode",
                                audioDecision = "copy",
                                hwTranscode = true,
                                videoCodec = "hevc",
                                audioCodec = "dts",
                                videoResolution = "4k",
                                bandwidthKbps = 18_500,
                            ),
                        player =
                            PlayerRecord(
                                playerName = "Living Room TV",
                                playerPlatform = "tvOS",
                                playerProduct = "Plex for Apple TV",
                                playerMachineId = "atv-1",
                                ipAddress = "10.10.1.25",
                                location = "lan",
                            ),
                        startedAtMs = 10_000L,
                        lastSeenAtMs = 20_000L,
                        pausedDurationSec = 0,
                    )

                val episodeSession =
                    ActiveSessionRecord(
                        serverId = "srv-1",
                        sessionKey = "11",
                        sessionId = "plex-sess-11",
                        userId = "mu-bob",
                        media =
                            MediaMetadataRecord(
                                ratingKey = "ep-99",
                                mediaType = "episode",
                                title = "Beyond Journey's End",
                                parentTitle = "Season 1",
                                grandparentTitle = "Frieren",
                                parentIndex = 1,
                                mediaIndex = 1,
                                year = 2023,
                                thumbPath = "/library/metadata/ep-99/thumb/1",
                                librarySectionKey = "sec-tv",
                                durationMs = 1_440_000L,
                            ),
                        viewOffsetMs = 720_000L,
                        state = "paused",
                        streamTech =
                            StreamTechRecord(
                                streamDecision = "direct_play",
                                videoDecision = "direct_play",
                                audioDecision = "direct_play",
                                hwTranscode = false,
                                videoCodec = "h264",
                                audioCodec = "aac",
                                videoResolution = "1080p",
                                bandwidthKbps = 8_200,
                            ),
                        player =
                            PlayerRecord(
                                playerName = "Bob Phone",
                                playerPlatform = "iOS",
                                playerProduct = "Plex for iOS",
                                playerMachineId = "ios-1",
                                ipAddress = "198.51.100.42",
                                location = "wan",
                            ),
                        startedAtMs = 12_000L,
                        lastSeenAtMs = 22_000L,
                        pausedDurationSec = 15,
                    )

                harness.activeSessionRepo.upsert(movieSession).shouldBeRight()
                harness.activeSessionRepo.upsert(episodeSession).shouldBeRight()
                harness.liveTechRef.set(
                    mapOf(
                        movieSession.key to
                            StreamTechnicalInfo(
                                decision = StreamDecision.TRANSCODE,
                                videoDecision = StreamDecision.TRANSCODE,
                                audioDecision = StreamDecision.DIRECT_STREAM,
                                isHwTranscode = true,
                                videoCodec = "hevc",
                                transcodeVideoCodec = "h264",
                                audioCodec = "dts",
                                transcodeAudioCodec = "aac",
                                container = "mkv",
                                transcodeContainer = "mp4",
                                videoResolution = "4k",
                                bitrateKbps = 18_500,
                                transcodeProgress = 34.5,
                                transcodeSpeed = 0.75,
                            ),
                        episodeSession.key to
                            StreamTechnicalInfo(
                                decision = StreamDecision.DIRECT_PLAY,
                                container = "mkv",
                                transcodeSpeed = 1.5,
                            ),
                    ),
                )

                testApplication {
                    setupStreamsApp(harness)

                    // Unauthenticated rejected
                    val unauth = client.get("/api/v1/streams")
                    unauth.status shouldBe HttpStatusCode.Unauthorized
                    unauth.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // Admin sees both streams with full metadata + overlay + proxy URLs + no secrets
                    val adminResp =
                        client.get("/api/v1/streams") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    adminResp.status shouldBe HttpStatusCode.OK
                    val adminText = adminResp.bodyAsText()
                    adminText shouldNotContain SECRET_PLEX_TOKEN
                    adminText shouldNotContain INTERNAL_PLEX_URL
                    adminText shouldNotContain "plex.tv"

                    val adminBody = MiruJson.decodeFromString<ActiveStreamsResponse>(adminText)
                    adminBody.totalCount shouldBe 2
                    adminBody.serverName shouldBe "Main Plex"
                    adminBody.streams shouldHaveSize 2

                    val epDto = adminBody.streams[0] // startedAtMs=12_000 > 10_000
                    epDto.media.mediaType shouldBe "episode"
                    epDto.media.grandparentTitle shouldBe "Frieren"
                    epDto.media.parentIndex shouldBe 1
                    epDto.media.mediaIndex shouldBe 1
                    epDto.media.thumbUrl shouldBe "/api/v1/media/ep-99/thumb"
                    epDto.user.id shouldBe "mu-bob"
                    epDto.user.avatarUrl.shouldBeNull()
                    epDto.player.ipAddress shouldBe "198.51.100.42"
                    epDto.player.location shouldBe "wan"
                    epDto.technical.bandwidthKbps shouldBe 8_200
                    epDto.technical.throttled shouldBe false
                    epDto.progressPercent.shouldBeWithinPercentageOf(50.0, 0.01)

                    val movDto = adminBody.streams[1]
                    movDto.media.mediaType shouldBe "movie"
                    movDto.media.thumbUrl shouldBe "/api/v1/media/mov-55/thumb"
                    movDto.user.id shouldBe "mu-alice"
                    movDto.user.avatarUrl shouldBe "/api/v1/users/mu-alice/avatar"
                    movDto.player.ipAddress shouldBe "10.10.1.25"
                    movDto.player.location shouldBe "lan"
                    movDto.progressPercent.shouldBeWithinPercentageOf(25.0, 0.01)
                    movDto.technical.transcodeProgress shouldBe 34.5
                    movDto.technical.transcodeSpeed shouldBe 0.75
                    movDto.technical.transcodeVideoCodec shouldBe "h264"
                    movDto.technical.transcodeAudioCodec shouldBe "aac"
                    movDto.technical.container shouldBe "mkv"
                    movDto.technical.transcodeContainer shouldBe "mp4"
                    movDto.technical.throttled shouldBe true

                    // USER bob only sees Bob's stream
                    val bobResp =
                        client.get("/api/v1/streams") {
                            cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                        }
                    bobResp.status shouldBe HttpStatusCode.OK
                    val bobBody = MiruJson.decodeFromString<ActiveStreamsResponse>(bobResp.bodyAsText())
                    bobBody.totalCount shouldBe 1
                    bobBody.serverName shouldBe "Main Plex"
                    bobBody.streams.single().user.id shouldBe "mu-bob"

                    // Unlinked USER sees zero streams
                    val unlinkedResp =
                        client.get("/api/v1/streams") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }
                    unlinkedResp.status shouldBe HttpStatusCode.OK
                    val unlinkedBody = MiruJson.decodeFromString<ActiveStreamsResponse>(unlinkedResp.bodyAsText())
                    unlinkedBody.totalCount shouldBe 0
                    unlinkedBody.streams.shouldBeEmpty()
                }
            }
        }

        test("GET /api/v1/streams/events delivers snapshot, scoped session events, ping keepalive, and cleans up on disconnect") {
            createStreamsUsersHarness().use { harness ->
                seedTestFixtureData(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "bobuser",
                        password = "Bob-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("mu-bob"),
                    ).shouldBeRight()
                val bobLogin = harness.authService.login("bobuser", "Bob-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "unlinkeduser",
                        password = "Unlinked-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = emptySet(),
                    ).shouldBeRight()
                val unlinkedLogin = harness.authService.login("unlinkeduser", "Unlinked-password-123").shouldBeRight()

                val aliceSession =
                    ActiveSessionRecord(
                        serverId = "srv-1",
                        sessionKey = "20",
                        sessionId = "plex-20",
                        userId = "mu-alice",
                        media = MediaMetadataRecord("m-1", "movie", "Your Name", thumbPath = "/thumb/1", durationMs = 1_000_000L),
                        viewOffsetMs = 100_000L,
                        state = "playing",
                        streamTech = StreamTechRecord(streamDecision = "direct_play"),
                        player = PlayerRecord(playerName = "Web", location = "lan"),
                        startedAtMs = 1_000L,
                        lastSeenAtMs = 1_000L,
                    )
                val bobSession =
                    ActiveSessionRecord(
                        serverId = "srv-1",
                        sessionKey = "21",
                        sessionId = "plex-21",
                        userId = "mu-bob",
                        media = MediaMetadataRecord("m-2", "movie", "Akira", durationMs = 1_000_000L),
                        viewOffsetMs = 200_000L,
                        state = "playing",
                        streamTech = StreamTechRecord(streamDecision = "transcode"),
                        player = PlayerRecord(playerName = "iOS", location = "wan"),
                        startedAtMs = 2_000L,
                        lastSeenAtMs = 2_000L,
                    )
                harness.activeSessionRepo.upsert(aliceSession).shouldBeRight()

                testApplication {
                    setupStreamsApp(harness, sseKeepaliveInterval = 50.milliseconds)

                    // Unauthenticated SSE rejected with 401 before streaming
                    val unauthSse = client.get("/api/v1/streams/events")
                    unauthSse.status shouldBe HttpStatusCode.Unauthorized

                    // Connect as USER bob and collect frames until we observe snapshot, ping, start/update/stop for bob
                    val bobFrames = Channel<ParsedSseFrame>(capacity = Channel.UNLIMITED)
                    val bobConnected = CompletableDeferred<Unit>()
                    val bobJob =
                        async {
                            client
                                .prepareGet("/api/v1/streams/events") {
                                    cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                                }.execute { response ->
                                    response.status shouldBe HttpStatusCode.OK
                                    val channel = response.bodyAsChannel()
                                    bobConnected.complete(Unit)
                                    var currentEvent: String? = null
                                    val dataLines = mutableListOf<String>()
                                    while (!channel.isClosedForRead) {
                                        val line = channel.readLine() ?: break
                                        when {
                                            line.isEmpty() -> {
                                                if (currentEvent != null || dataLines.isNotEmpty()) {
                                                    bobFrames.trySend(
                                                        ParsedSseFrame(
                                                            event = currentEvent,
                                                            data = dataLines.joinToString("\n").ifEmpty { null },
                                                        ),
                                                    )
                                                    currentEvent = null
                                                    dataLines.clear()
                                                }
                                            }
                                            line.startsWith(":") -> {
                                                bobFrames.trySend(ParsedSseFrame(comment = line.removePrefix(":").trim()))
                                            }
                                            line.startsWith("event:") -> {
                                                currentEvent = line.removePrefix("event:").trim()
                                            }
                                            line.startsWith("data:") -> {
                                                dataLines += line.removePrefix("data:").trim()
                                            }
                                        }
                                    }
                                }
                        }

                    withTimeout(5_000L) {
                        bobConnected.await()
                        // 1. Initial snapshot for Bob should be empty (Alice's stream filtered out)
                        val snapshotFrame = bobFrames.receive()
                        snapshotFrame.event shouldBe "snapshot"
                        val snapshotPayload = MiruJson.decodeFromString<ActiveStreamsResponse>(snapshotFrame.data.shouldNotBeNull())
                        snapshotPayload.totalCount shouldBe 0
                        snapshotPayload.serverName shouldBe "Main Plex"

                        // Wait for subscription to be registered
                        while (harness.sessionEvents.subscriptionCount.value < 1) {
                            delay(10.milliseconds)
                        }

                        // 2. Emit Alice event (filtered out for Bob) + Bob started/updated/stopped
                        harness.liveTechRef.set(
                            mapOf(
                                bobSession.key to
                                    StreamTechnicalInfo(
                                        decision = StreamDecision.TRANSCODE,
                                        transcodeSpeed = 0.6,
                                        transcodeProgress = 12.0,
                                    ),
                            ),
                        )
                        harness.sessionEvents.emit(
                            SessionEvent.SessionStarted(
                                serverId = "srv-1",
                                key = aliceSession.key,
                                timestampMs = 3_000L,
                                session = aliceSession,
                            ),
                        )
                        harness.sessionEvents.emit(
                            SessionEvent.SessionStarted(
                                serverId = "srv-1",
                                key = bobSession.key,
                                timestampMs = 3_100L,
                                session = bobSession,
                            ),
                        )
                        harness.sessionEvents.emit(
                            SessionEvent.SessionUpdated(
                                serverId = "srv-1",
                                key = bobSession.key,
                                timestampMs = 3_200L,
                                session = bobSession.copy(viewOffsetMs = 500_000L, state = PlaybackState.PAUSED.name.lowercase()),
                                previousState = "playing",
                                previousStreamDecision = "transcode",
                                streamDecisionChanged = false,
                            ),
                        )
                        harness.sessionEvents.emit(
                            SessionEvent.SessionStopped(
                                serverId = "srv-1",
                                key = bobSession.key,
                                timestampMs = 3_300L,
                                session = bobSession.copy(state = "stopped"),
                                historyRecord = null,
                                mergedIntoHistory = false,
                                reason = StopReason.EXPLICIT_STOP,
                            ),
                        )

                        val collectedEvents = mutableListOf<ParsedSseFrame>()
                        var sawPing = false
                        while (!sawPing || collectedEvents.size < 3) {
                            val frame = bobFrames.receive()
                            if (frame.comment == "ping") {
                                sawPing = true
                            } else if (frame.event != null) {
                                collectedEvents += frame
                            }
                        }

                        collectedEvents.map { it.event } shouldContainExactly
                            listOf("session_started", "session_updated", "session_stopped")

                        val startedDto =
                            MiruJson.decodeFromString<SessionStartedEventDto>(collectedEvents[0].data.shouldNotBeNull())
                        startedDto.stream.user.id shouldBe "mu-bob"
                        startedDto.stream.technical.throttled shouldBe true
                        startedDto.stream.technical.transcodeProgress shouldBe 12.0

                        val updatedDto =
                            MiruJson.decodeFromString<SessionUpdatedEventDto>(collectedEvents[1].data.shouldNotBeNull())
                        updatedDto.previousState shouldBe "playing"
                        updatedDto.stream.state shouldBe "paused"
                        updatedDto.stream.progressPercent.shouldBeWithinPercentageOf(50.0, 0.01)

                        val stoppedDto =
                            MiruJson.decodeFromString<SessionStoppedEventDto>(collectedEvents[2].data.shouldNotBeNull())
                        stoppedDto.reason shouldBe "explicit_stop"
                        stoppedDto.userId shouldBe "mu-bob"
                    }

                    // Disconnect client
                    bobJob.cancel()

                    // Also verify unlinked USER receives empty snapshot, ping, and zero stream events
                    val unlinkedFrames = Channel<ParsedSseFrame>(capacity = Channel.UNLIMITED)
                    val unlinkedConnected = CompletableDeferred<Unit>()
                    val unlinkedJob =
                        async {
                            client
                                .prepareGet("/api/v1/streams/events") {
                                    cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                                }.execute { response ->
                                    response.status shouldBe HttpStatusCode.OK
                                    val channel = response.bodyAsChannel()
                                    unlinkedConnected.complete(Unit)
                                    var currentEvent: String? = null
                                    val dataLines = mutableListOf<String>()
                                    while (!channel.isClosedForRead) {
                                        val line = channel.readLine() ?: break
                                        when {
                                            line.isEmpty() -> {
                                                if (currentEvent != null || dataLines.isNotEmpty()) {
                                                    unlinkedFrames.trySend(
                                                        ParsedSseFrame(
                                                            event = currentEvent,
                                                            data = dataLines.joinToString("\n").ifEmpty { null },
                                                        ),
                                                    )
                                                    currentEvent = null
                                                    dataLines.clear()
                                                }
                                            }
                                            line.startsWith(":") -> {
                                                unlinkedFrames.trySend(ParsedSseFrame(comment = line.removePrefix(":").trim()))
                                            }
                                            line.startsWith("event:") -> {
                                                currentEvent = line.removePrefix("event:").trim()
                                            }
                                            line.startsWith("data:") -> {
                                                dataLines += line.removePrefix("data:").trim()
                                            }
                                        }
                                    }
                                }
                        }
                    withTimeout(5_000L) {
                        unlinkedConnected.await()
                        val initFrame = unlinkedFrames.receive()
                        initFrame.event shouldBe "snapshot"
                        MiruJson.decodeFromString<ActiveStreamsResponse>(initFrame.data.shouldNotBeNull()).totalCount shouldBe 0

                        while (harness.sessionEvents.subscriptionCount.value < 1) {
                            delay(10.milliseconds)
                        }
                        harness.sessionEvents.emit(
                            SessionEvent.SessionStarted(
                                serverId = "srv-1",
                                key = bobSession.key,
                                timestampMs = 4_000L,
                                session = bobSession,
                            ),
                        )
                        val nextFrame = unlinkedFrames.receive()
                        nextFrame.comment shouldBe "ping"
                        nextFrame.event.shouldBeNull()
                    }
                    unlinkedJob.cancel()

                    // Also verify ADMIN receives Alice's stream in initial snapshot
                    val adminSnapshot = CompletableDeferred<ActiveStreamsResponse>()
                    val adminJob =
                        async {
                            client
                                .prepareGet("/api/v1/streams/events") {
                                    header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                                    cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                                }.execute { response ->
                                    val channel = response.bodyAsChannel()
                                    while (!channel.isClosedForRead) {
                                        val line = channel.readLine() ?: break
                                        if (line.startsWith("data:")) {
                                            val json = line.removePrefix("data:").trim()
                                            val payload = MiruJson.decodeFromString<ActiveStreamsResponse>(json)
                                            adminSnapshot.complete(payload)
                                            break
                                        }
                                    }
                                }
                        }
                    withTimeout(5_000L) {
                        val snap = adminSnapshot.await()
                        snap.totalCount shouldBe 1
                        snap.serverName shouldBe "Main Plex"
                        snap.streams.single().user.id shouldBe "mu-alice"
                    }
                    adminJob.cancel()
                }

                // Server shutdown cancels SSE handler coroutines cleanly with zero lingering subscribers
                withTimeout(5_000L) {
                    while (harness.sessionEvents.subscriptionCount.value > 0) {
                        delay(10.milliseconds)
                    }
                }
            }
        }

        test("GET /api/v1/users and GET /api/v1/users/{id} return stats, breakdowns, recent plays, and enforce RBAC and 404") {
            createStreamsUsersHarness().use { harness ->
                seedTestFixtureData(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "bobuser",
                        password = "Bob-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("mu-bob"),
                    ).shouldBeRight()
                val bobLogin = harness.authService.login("bobuser", "Bob-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "unlinkeduser",
                        password = "Unlinked-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = emptySet(),
                    ).shouldBeRight()
                val unlinkedLogin = harness.authService.login("unlinkeduser", "Unlinked-password-123").shouldBeRight()

                // Seed 12 history plays for Alice across movies & TV + different clients/decisions
                for (i in 1..12) {
                    val isMovie = i <= 7
                    val decision =
                        when {
                            i <= 6 -> "direct_play"
                            i <= 9 -> "direct_stream"
                            else -> "transcode"
                        }
                    val product = if (i % 2 == 0) "Plex for Apple TV" else "Plex Web"
                    val platform = if (i % 2 == 0) "tvOS" else "Chrome"
                    harness.historyRepo
                        .insert(
                            PlaybackHistoryRecord(
                                id = "hist-$i",
                                serverId = "srv-1",
                                userId = "mu-alice",
                                media =
                                    MediaMetadataRecord(
                                        ratingKey = "item-$i",
                                        mediaType = if (isMovie) "movie" else "episode",
                                        title = "Item $i",
                                        parentTitle = if (isMovie) null else "Season 1",
                                        grandparentTitle = if (isMovie) null else "Show A",
                                        thumbPath = "/library/metadata/item-$i/thumb",
                                        librarySectionKey = if (isMovie) "sec-movies" else "sec-tv",
                                        durationMs = 1_000_000L,
                                    ),
                                viewOffsetMs = 900_000L,
                                watchedDurationSec = 100 * i,
                                pausedDurationSec = 10,
                                completionPercent = 90.0,
                                isWatched = true,
                                streamTech = StreamTechRecord(streamDecision = decision, hwTranscode = decision == "transcode"),
                                player =
                                    PlayerRecord(
                                        playerName = product,
                                        playerPlatform = platform,
                                        playerProduct = product,
                                        location = "lan",
                                    ),
                                startedAtMs = i * 1_000L,
                                stoppedAtMs = i * 1_000L + 500L,
                            ),
                        ).shouldBeRight()
                }

                // Active session for Alice
                harness.activeSessionRepo
                    .upsert(
                        ActiveSessionRecord(
                            serverId = "srv-1",
                            sessionKey = "50",
                            sessionId = "plex-50",
                            userId = "mu-alice",
                            media = MediaMetadataRecord("mov-live", "movie", "Live Movie", durationMs = 2_000_000L),
                            viewOffsetMs = 500_000L,
                            state = "playing",
                            streamTech = StreamTechRecord(streamDecision = "direct_play"),
                            player = PlayerRecord(playerName = "Apple TV", location = "lan"),
                            startedAtMs = 50_000L,
                            lastSeenAtMs = 55_000L,
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupStreamsApp(harness)

                    // Unauthenticated rejected
                    client.get("/api/v1/users").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/api/v1/users/mu-alice").status shouldBe HttpStatusCode.Unauthorized

                    // Admin lists all users
                    val usersResp =
                        client.get("/api/v1/users") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    usersResp.status shouldBe HttpStatusCode.OK
                    val usersText = usersResp.bodyAsText()
                    usersText shouldNotContain SECRET_PLEX_TOKEN
                    usersText shouldNotContain "plex.tv"

                    val usersList = MiruJson.decodeFromString<UsersListResponse>(usersText).users
                    usersList shouldHaveSize 2
                    val aliceSummary = usersList.first { it.id == "mu-alice" }
                    aliceSummary.avatarUrl shouldBe "/api/v1/users/mu-alice/avatar"
                    aliceSummary.isStreaming shouldBe true
                    aliceSummary.activeStreamCount shouldBe 1
                    aliceSummary.totalPlays shouldBe 12L
                    aliceSummary.totalWatchDurationSec shouldBe 7_800L

                    val bobSummary = usersList.first { it.id == bobLogin.principal.accountId }
                    bobSummary.accountId shouldBe bobLogin.principal.accountId
                    bobSummary.linkedProfiles.map { it.userId } shouldContainExactly listOf("mu-bob")
                    bobSummary.avatarUrl.shouldBeNull()
                    bobSummary.isStreaming shouldBe false
                    bobSummary.activeStreamCount shouldBe 0
                    bobSummary.totalPlays shouldBe 0L
                    bobSummary.totalWatchDurationSec shouldBe 0L

                    // USER bob gets 403 on GET /api/v1/users (ADMIN only)
                    val bobUsersResp =
                        client.get("/api/v1/users") {
                            cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                        }
                    bobUsersResp.status shouldBe HttpStatusCode.Forbidden
                    bobUsersResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // Unlinked USER gets 403 on GET /api/v1/users and any /api/v1/users/{id}
                    client
                        .get("/api/v1/users") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/users/mu-alice") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/users/mu-bob") {
                            cookie(SESSION_COOKIE_NAME, unlinkedLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // USER bob gets 403 accessing Alice or unknown user
                    client
                        .get("/api/v1/users/mu-alice") {
                            cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                        }.status shouldBe HttpStatusCode.Forbidden

                    // USER bob gets 200 accessing own linked user
                    val bobDetailResp =
                        client.get("/api/v1/users/mu-bob") {
                            cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                        }
                    bobDetailResp.status shouldBe HttpStatusCode.OK
                    val bobDetail = MiruJson.decodeFromString<UserDetailResponse>(bobDetailResp.bodyAsText())
                    bobDetail.totalPlays shouldBe 0L
                    bobDetail.decisionBreakdown.total shouldBe 0L
                    bobDetail.decisionBreakdown.directPlayPercent shouldBe 0.0

                    // Admin gets 404 for unknown user
                    val missingResp =
                        client.get("/api/v1/users/does-not-exist") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    missingResp.status shouldBe HttpStatusCode.NotFound

                    // Admin gets Alice's detailed summary
                    val aliceDetailResp =
                        client.get("/api/v1/users/mu-alice") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    aliceDetailResp.status shouldBe HttpStatusCode.OK
                    val aliceDetailText = aliceDetailResp.bodyAsText()
                    aliceDetailText shouldNotContain SECRET_PLEX_TOKEN
                    aliceDetailText shouldNotContain "plex.tv"

                    val detail = MiruJson.decodeFromString<UserDetailResponse>(aliceDetailText)
                    detail.user.id shouldBe "mu-alice"
                    detail.user.isStreaming shouldBe true
                    detail.activeStreams shouldHaveSize 1
                    detail.totalPlays shouldBe 12L
                    detail.totalWatchDurationSec shouldBe 7_800L
                    detail.recentPlays shouldHaveSize 10
                    detail.recentPlays.first().id shouldBe "hist-12"
                    detail.recentPlays.first().thumbUrl shouldBe "/api/v1/media/item-12/thumb"
                    detail.recentPlays.last().id shouldBe "hist-3"

                    detail.topClients.map { it.name } shouldContain "Plex for Apple TV"
                    detail.topClients.map { it.name } shouldContain "Plex Web"
                    detail.topPlatforms.map { it.name } shouldContain "tvOS"
                    detail.topPlatforms.map { it.name } shouldContain "Chrome"

                    detail.decisionBreakdown.directPlay shouldBe 6L
                    detail.decisionBreakdown.directStream shouldBe 3L
                    detail.decisionBreakdown.transcode shouldBe 3L
                    detail.decisionBreakdown.hwTranscode shouldBe 3L
                    detail.decisionBreakdown.hwTranscodePercent shouldBe 25.0
                    detail.decisionBreakdown.total shouldBe 12L
                    detail.decisionBreakdown.directPlayPercent.shouldBeWithinPercentageOf(50.0, 0.01)
                    detail.decisionBreakdown.directStreamPercent.shouldBeWithinPercentageOf(25.0, 0.01)
                    detail.decisionBreakdown.transcodePercent.shouldBeWithinPercentageOf(25.0, 0.01)

                    detail.libraryBreakdown shouldHaveSize 2
                    detail.libraryBreakdown[0] shouldBe
                        LibraryBreakdownDto(
                            librarySectionKey = "sec-movies",
                            playCount = 7L,
                            watchDurationSec = 2_800L,
                            percent = 58.33,
                        )
                    detail.libraryBreakdown[1] shouldBe
                        LibraryBreakdownDto(
                            librarySectionKey = "sec-tv",
                            playCount = 5L,
                            watchDurationSec = 5_000L,
                            percent = 41.67,
                        )
                }
            }
        }

        test("falls back to persisted active-session conversion details, parent thumb key, and trimmed displayName") {
            createStreamsUsersHarness().use { harness ->
                seedTestFixtureData(harness)
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "mu-blank",
                            serverId = "srv-1",
                            externalUserId = "300",
                            username = "blankname",
                            displayName = "   ",
                            thumbUrl = null,
                            isAdmin = false,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                val recoveredSession =
                    ActiveSessionRecord(
                        serverId = "srv-1",
                        sessionKey = "99",
                        sessionId = "plex-sess-99",
                        userId = "mu-blank",
                        media =
                            MediaMetadataRecord(
                                ratingKey = "77001",
                                mediaType = "track",
                                title = "Resonance",
                                thumbPath = "/library/metadata/77000/thumb/1700000200",
                                durationMs = 212_000L,
                            ),
                        viewOffsetMs = 50_000L,
                        state = "playing",
                        streamTech =
                            StreamTechRecord(
                                streamDecision = "transcode",
                                videoDecision = "transcode",
                                audioDecision = "transcode",
                                hwTranscode = false,
                                videoCodec = "hevc",
                                audioCodec = "flac",
                                transcodeVideoCodec = "h264",
                                transcodeAudioCodec = "aac",
                                container = "mkv",
                                transcodeContainer = "mp4",
                                subtitleDecision = "burn",
                            ),
                        player = PlayerRecord(playerProduct = "Plexamp"),
                        startedAtMs = 15_000L,
                        lastSeenAtMs = 20_000L,
                    )
                harness.activeSessionRepo.upsert(recoveredSession).shouldBeRight()
                // Empty liveTechRef simulates crash recovery after restart.
                harness.liveTechRef.set(emptyMap())

                testApplication {
                    setupStreamsApp(harness)
                    val resp =
                        client.get("/api/v1/streams") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                        }
                    resp.status shouldBe HttpStatusCode.OK
                    val stream = MiruJson.decodeFromString<ActiveStreamsResponse>(resp.bodyAsText()).streams.single()
                    stream.user.displayName.shouldBeNull()
                    stream.media.thumbUrl shouldBe "/api/v1/media/77000/thumb"
                    stream.technical.container shouldBe "mkv"
                    stream.technical.transcodeVideoCodec shouldBe "h264"
                    stream.technical.transcodeAudioCodec shouldBe "aac"
                    stream.technical.transcodeContainer shouldBe "mp4"
                }
            }
        }

        test(
            "merges linked media users across servers in /api/v1/users and /api/v1/users/{id}, " +
                "narrows by serverId, and unmerges on unlink",
        ) {
            createStreamsUsersHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-plex",
                            name = "Home Plex",
                            serverType = "PLEX",
                            machineId = "mach-plex",
                            baseUrl = "http://plex.local:32400",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-jf",
                            name = "Studio Jellyfin",
                            serverType = "JELLYFIN",
                            machineId = "mach-jf",
                            baseUrl = "http://jf.local:8096",
                            createdAtMs = 2_000L,
                            updatedAtMs = 2_000L,
                        ),
                    ).shouldBeRight()

                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "u-plex-elena",
                            serverId = "srv-plex",
                            externalUserId = "10",
                            username = "elena",
                            displayName = "Elena",
                            thumbUrl = "https://plex.tv/users/elena/avatar",
                            lastSeenAtMs = 5_000L,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "u-jf-elena",
                            serverId = "srv-jf",
                            externalUserId = "jf-10",
                            username = "elena_jf",
                            displayName = null,
                            thumbUrl = null,
                            lastSeenAtMs = 9_000L,
                            createdAtMs = 2_000L,
                            updatedAtMs = 2_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "u-plex-bob",
                            serverId = "srv-plex",
                            externalUserId = "20",
                            username = "bob",
                            displayName = "Bob",
                            lastSeenAtMs = 3_000L,
                            createdAtMs = 1_500L,
                            updatedAtMs = 1_500L,
                        ),
                    ).shouldBeRight()

                val elenaAcc =
                    harness.authService
                        .createAccount(
                            username = "elena-account",
                            password = "Elena-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("u-plex-elena", "u-jf-elena"),
                        ).shouldBeRight()
                val elenaLogin = harness.authService.login("elena-account", "Elena-password-123").shouldBeRight()

                harness.historyRepo
                    .insert(
                        PlaybackHistoryRecord(
                            id = "h-plex-1",
                            serverId = "srv-plex",
                            userId = "u-plex-elena",
                            media =
                                MediaMetadataRecord(
                                    ratingKey = "m-1",
                                    mediaType = "movie",
                                    title = "Spirited Away",
                                    librarySectionKey = "sec-p",
                                    durationMs = 1_000_000L,
                                ),
                            viewOffsetMs = 1_000_000L,
                            watchedDurationSec = 1_200,
                            pausedDurationSec = 0,
                            completionPercent = 100.0,
                            isWatched = true,
                            streamTech = StreamTechRecord(streamDecision = "direct_play", hwTranscode = false),
                            player = PlayerRecord(playerName = "Apple TV", playerPlatform = "tvOS", playerProduct = "Plex for Apple TV"),
                            startedAtMs = 4_000L,
                            stoppedAtMs = 5_000L,
                        ),
                    ).shouldBeRight()
                harness.historyRepo
                    .insert(
                        PlaybackHistoryRecord(
                            id = "h-jf-1",
                            serverId = "srv-jf",
                            userId = "u-jf-elena",
                            media =
                                MediaMetadataRecord(
                                    ratingKey = "jf-1",
                                    mediaType = "episode",
                                    title = "Dulcinea",
                                    grandparentTitle = "The Expanse",
                                    librarySectionKey = "sec-jf",
                                    durationMs = 1_000_000L,
                                ),
                            viewOffsetMs = 900_000L,
                            watchedDurationSec = 2_400,
                            pausedDurationSec = 0,
                            completionPercent = 90.0,
                            isWatched = true,
                            streamTech = StreamTechRecord(streamDecision = "transcode", hwTranscode = true),
                            player = PlayerRecord(playerName = "Firefox", playerPlatform = "Web", playerProduct = "Jellyfin Web"),
                            startedAtMs = 8_000L,
                            stoppedAtMs = 9_000L,
                        ),
                    ).shouldBeRight()
                harness.activeSessionRepo
                    .upsert(
                        ActiveSessionRecord(
                            serverId = "srv-jf",
                            sessionKey = "jf-s1",
                            sessionId = "jf-s1",
                            userId = "u-jf-elena",
                            media = MediaMetadataRecord("jf-live", "movie", "Arrival", durationMs = 2_000_000L),
                            viewOffsetMs = 500_000L,
                            state = "playing",
                            streamTech = StreamTechRecord(streamDecision = "direct_play"),
                            player = PlayerRecord(playerName = "Jellyfin Web"),
                            startedAtMs = 10_000L,
                            lastSeenAtMs = 11_000L,
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupStreamsApp(harness)

                    // 1. Default merged /api/v1/users returns 1 unified entry for Elena + 1 standalone Bob
                    val mergedResp =
                        MiruJson.decodeFromString<UsersListResponse>(
                            client.get("/api/v1/users") { cookie(SESSION_COOKIE_NAME, adminLogin.signedToken) }.bodyAsText(),
                        )
                    mergedResp.users shouldHaveSize 2
                    val elenaSummary = mergedResp.users.first { it.id == elenaAcc.account.id }
                    elenaSummary.accountId shouldBe elenaAcc.account.id
                    elenaSummary.displayName shouldBe "Elena"
                    elenaSummary.avatarUrl shouldBe "/api/v1/users/u-plex-elena/avatar"
                    elenaSummary.isStreaming shouldBe true
                    elenaSummary.activeStreamCount shouldBe 1
                    elenaSummary.lastSeenAtMs shouldBe 9_000L
                    elenaSummary.totalPlays shouldBe 2L
                    elenaSummary.totalWatchDurationSec shouldBe 3_600L
                    elenaSummary.linkedProfiles.map { it.userId to it.serverId } shouldContainExactly
                        listOf("u-plex-elena" to "srv-plex", "u-jf-elena" to "srv-jf")

                    // 2. merged=false returns unmerged per-server profiles (for Admin linking)
                    val unmergedResp =
                        MiruJson.decodeFromString<UsersListResponse>(
                            client.get("/api/v1/users?merged=false") { cookie(SESSION_COOKIE_NAME, adminLogin.signedToken) }.bodyAsText(),
                        )
                    unmergedResp.users shouldHaveSize 3

                    // 3. Filtering by serverId narrows /api/v1/users
                    val jfUsersResp =
                        MiruJson.decodeFromString<UsersListResponse>(
                            client.get("/api/v1/users?serverId=srv-jf") {
                                cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            }.bodyAsText(),
                        )
                    jfUsersResp.users.single().let { jf ->
                        jf.id shouldBe "u-jf-elena"
                        jf.accountId shouldBe elenaAcc.account.id
                        jf.totalPlays shouldBe 1L
                        jf.totalWatchDurationSec shouldBe 2_400L
                    }

                    // 4. Lookup by canonical accountId AND by either linked users.id all return unified UserDetailResponse
                    for (lookupId in listOf(elenaAcc.account.id, "u-plex-elena", "u-jf-elena")) {
                        val detail =
                            MiruJson.decodeFromString<UserDetailResponse>(
                                client.get("/api/v1/users/$lookupId") { cookie(SESSION_COOKIE_NAME, adminLogin.signedToken) }.bodyAsText(),
                            )
                        detail.user.id shouldBe elenaAcc.account.id
                        detail.totalPlays shouldBe 2L
                        detail.totalWatchDurationSec shouldBe 3_600L
                        detail.activeStreams shouldHaveSize 1
                        detail.recentPlays.map { it.id to it.serverType } shouldContainExactly
                            listOf("h-jf-1" to "jellyfin", "h-plex-1" to "plex")
                        detail.decisionBreakdown.directPlay shouldBe 1L
                        detail.decisionBreakdown.transcode shouldBe 1L
                        detail.decisionBreakdown.hwTranscode shouldBe 1L
                        detail.libraryBreakdown.map { it.librarySectionKey } shouldContainExactly listOf("sec-jf", "sec-p")
                    }

                    // 5. Narrowing detail by serverId restricts streams, plays, and breakdowns
                    val plexOnlyDetail =
                        MiruJson.decodeFromString<UserDetailResponse>(
                            client.get("/api/v1/users/${elenaAcc.account.id}?serverId=srv-plex") {
                                cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            }.bodyAsText(),
                        )
                    plexOnlyDetail.user.id shouldBe "u-plex-elena"
                    plexOnlyDetail.activeStreams.shouldBeEmpty()
                    plexOnlyDetail.totalPlays shouldBe 1L
                    plexOnlyDetail.totalWatchDurationSec shouldBe 1_200L
                    plexOnlyDetail.recentPlays.map { it.id } shouldContainExactly listOf("h-plex-1")

                    // 6. USER RBAC allows own canonical accountId and own linked users.id, rejects others
                    client.get("/api/v1/users/${elenaAcc.account.id}") {
                        cookie(SESSION_COOKIE_NAME, elenaLogin.signedToken)
                    }.status shouldBe HttpStatusCode.OK
                    client.get("/api/v1/users/u-jf-elena") {
                        cookie(SESSION_COOKIE_NAME, elenaLogin.signedToken)
                    }.status shouldBe HttpStatusCode.OK
                    client.get("/api/v1/users/u-plex-bob") {
                        cookie(SESSION_COOKIE_NAME, elenaLogin.signedToken)
                    }.status shouldBe HttpStatusCode.Forbidden

                    // 7. Unlinking Jellyfin user immediately separates its stats without modifying playback_history
                    harness.authService.setLinkedMediaUsers(elenaAcc.account.id, setOf("u-plex-elena")).shouldBeRight()
                    val afterUnlink =
                        MiruJson.decodeFromString<UsersListResponse>(
                            client.get("/api/v1/users") { cookie(SESSION_COOKIE_NAME, adminLogin.signedToken) }.bodyAsText(),
                        )
                    afterUnlink.users shouldHaveSize 3
                    afterUnlink.users.first { it.id == elenaAcc.account.id }.totalPlays shouldBe 1L
                    afterUnlink.users.first { it.id == "u-jf-elena" }.totalPlays shouldBe 1L
                }
            }
        }
    })
