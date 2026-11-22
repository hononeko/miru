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
                    bobBody.streams.single().user.id shouldBe "mu-bob"
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

                    val bobSummary = usersList.first { it.id == "mu-bob" }
                    bobSummary.avatarUrl.shouldBeNull()
                    bobSummary.isStreaming shouldBe false
                    bobSummary.activeStreamCount shouldBe 0
                    bobSummary.totalPlays shouldBe 0L
                    bobSummary.totalWatchDurationSec shouldBe 0L

                    // USER bob only sees mu-bob in list
                    val bobUsersResp =
                        client.get("/api/v1/users") {
                            cookie(SESSION_COOKIE_NAME, bobLogin.signedToken)
                        }
                    bobUsersResp.status shouldBe HttpStatusCode.OK
                    MiruJson.decodeFromString<UsersListResponse>(bobUsersResp.bodyAsText()).users.map { it.id } shouldContainExactly
                        listOf("mu-bob")

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
                    detail.decisionBreakdown.total shouldBe 12L
                    detail.decisionBreakdown.directPlayPercent.shouldBeWithinPercentageOf(50.0, 0.01)
                    detail.decisionBreakdown.directStreamPercent.shouldBeWithinPercentageOf(25.0, 0.01)
                    detail.decisionBreakdown.transcodePercent.shouldBeWithinPercentageOf(25.0, 0.01)

                    detail.libraryBreakdown shouldHaveSize 2
                    detail.libraryBreakdown[0] shouldBe
                        LibraryBreakdownDto(
                            librarySectionKey = "sec-movies",
                            mediaType = "movie",
                            playCount = 7L,
                            watchDurationSec = 2_800L,
                        )
                    detail.libraryBreakdown[1] shouldBe
                        LibraryBreakdownDto(
                            librarySectionKey = "sec-tv",
                            mediaType = "episode",
                            playCount = 5L,
                            watchDurationSec = 5_000L,
                        )
                }
            }
        }
    })
