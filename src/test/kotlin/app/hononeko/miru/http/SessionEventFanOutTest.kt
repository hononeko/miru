package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.model.AvatarMutation
import app.hononeko.miru.persistence.model.DisplayNameMutation
import app.hononeko.miru.persistence.repository.CachingMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.StopReason
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.request.cookie
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val WARM_UP_TIMESTAMP_MS = 1_000L
private const val EVENT_TIMESTAMP_MS = 10_000L
private const val FENCE_TIMESTAMP_MS = 99_000L

/** Counts the user lookups a frame build makes, per requested user, and can fail lookups for chosen users. */
private class CountingUserRepository(
    private val delegate: UserRepository,
    private val failFor: Set<String> = emptySet(),
) : UserRepository by delegate {
    val findByIdsRequests = CopyOnWriteArrayList<Set<String>>()
    val findAllCalls = AtomicInteger()

    fun lookupsOf(userId: String): Int = findByIdsRequests.count { userId in it }

    override suspend fun findByIds(ids: Collection<String>): Either<MiruError.PersistenceError, List<UserRecord>> {
        findByIdsRequests += ids.toSet()
        check(ids.none { it in failFor }) { "lookup failed" }
        return delegate.findByIds(ids)
    }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<UserRecord>> {
        findAllCalls.incrementAndGet()
        return delegate.findAll()
    }
}

private class CountingServerRepository(
    private val delegate: MediaServerRepository,
) : MediaServerRepository by delegate {
    val findByIdCalls = AtomicInteger()

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?> {
        findByIdCalls.incrementAndGet()
        return delegate.findById(id)
    }
}

private class FanOutHarness(
    val tempDir: Path,
    val handle: DatabaseHandle,
    failUserLookupsFor: Set<String> = emptySet(),
) : AutoCloseable {
    val rawServers = ExposedMediaServerRepository(handle)
    val cachedServers = CachingMediaServerRepository(rawServers)
    val servers = CountingServerRepository(cachedServers)
    val rawUsers = ExposedUserRepository(handle)
    val users = CountingUserRepository(rawUsers, failUserLookupsFor)
    val accounts = ExposedAuthAccountRepository(handle)
    val activeSessions = ExposedActiveSessionRepository(handle)
    val sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val authConfig = AuthConfig(mode = AuthMode.LOCAL, sessionSecret = "super-secret-session-signing-key-32-chars")
    val authService =
        AuthService(
            accountRepository = accounts,
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = rawUsers,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    val service =
        StreamsAndUsersService(
            activeSessionRepository = activeSessions,
            userRepository = users,
            playbackHistoryRepository = ExposedPlaybackHistoryRepository(handle),
            serverNameProvider = { "Main" },
            mediaServerRepository = servers,
            authAccountRepository = accounts,
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 0),
            database = DatabaseConfig.Sqlite(tempDir.resolve("miru.db").toString()),
            plex = PlexConfig(baseUrl = "http://plex.internal:32400", token = "plex-token"),
            session = SessionConfig(),
            auth = authConfig,
        )

    suspend fun seed() {
        for (serverId in listOf("srv-1", "srv-2")) {
            rawServers
                .upsert(
                    MediaServerRecord(
                        id = serverId,
                        name = "Server $serverId",
                        serverType = "plex",
                        machineId = "mach-$serverId",
                        baseUrl = "http://$serverId.local:32400",
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ).shouldBeRight()
        }
        for ((userId, serverId) in USERS) {
            rawUsers
                .upsert(
                    UserRecord(
                        id = userId,
                        serverId = serverId,
                        externalUserId = "ext-$userId",
                        username = userId.removePrefix("mu-"),
                        displayName = userId.removePrefix("mu-").replaceFirstChar { it.uppercase() },
                        createdAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ).shouldBeRight()
        }
    }

    override fun close() {
        handle.close()
        tempDir.toFile().deleteRecursively()
    }

    companion object {
        val USERS = listOf("mu-alice" to "srv-1", "mu-bob" to "srv-1", "mu-bob2" to "srv-2", "mu-carol" to "srv-2", "mu-boom" to "srv-1")
    }
}

private fun createFanOutHarness(failUserLookupsFor: Set<String> = emptySet()): FanOutHarness {
    val dir = Files.createTempDirectory("miru-sse-fanout-test-")
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
    return FanOutHarness(dir, handle, failUserLookupsFor)
}

private fun sessionFor(
    userId: String,
    serverId: String,
    sessionKey: String,
): ActiveSessionRecord =
    ActiveSessionRecord(
        serverId = serverId,
        sessionKey = sessionKey,
        sessionId = "sess-$sessionKey",
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = "rk-$sessionKey",
                mediaType = "movie",
                title = "Movie $sessionKey",
                durationMs = 1_000_000L,
            ),
        viewOffsetMs = 250_000L,
        state = "playing",
        streamTech = StreamTechRecord(streamDecision = "direct_play"),
        player = PlayerRecord(playerName = "Web", location = "lan"),
        startedAtMs = 500L,
        lastSeenAtMs = 500L,
    )

private fun started(
    session: ActiveSessionRecord,
    timestampMs: Long,
): SessionEvent =
    SessionEvent.SessionStarted(serverId = session.serverId, key = session.key, timestampMs = timestampMs, session = session)

private fun updated(
    session: ActiveSessionRecord,
    timestampMs: Long,
): SessionEvent =
    SessionEvent.SessionUpdated(
        serverId = session.serverId,
        key = session.key,
        timestampMs = timestampMs,
        session = session,
        previousState = "playing",
        previousStreamDecision = "direct_play",
        streamDecisionChanged = false,
    )

private fun stopped(
    session: ActiveSessionRecord,
    timestampMs: Long,
): SessionEvent =
    SessionEvent.SessionStopped(
        serverId = session.serverId,
        key = session.key,
        timestampMs = timestampMs,
        session = session,
        historyRecord = null,
        mergedIntoHistory = false,
        reason = StopReason.EXPLICIT_STOP,
    )

private data class ReceivedFrame(
    val event: String,
    val data: String,
) {
    val timestampMs: Long get() = MiruJson.parseToJsonElement(data).jsonObject.getValue("timestampMs").jsonPrimitive.long
    val userId: String
        get() =
            MiruJson
                .parseToJsonElement(data)
                .jsonObject
                .getValue("stream")
                .jsonObject
                .getValue("user")
                .jsonObject
                .getValue("id")
                .jsonPrimitive
                .content
}

private fun adminPrincipal(): MiruPrincipal = MiruPrincipal(accountId = "acc-admin", username = "admin", role = MiruRole.ADMIN)

class SessionEventFanOutTest :
    FunSpec({
        test("builds and encodes each event once, sharing the same frame with every subscriber") {
            createFanOutHarness().use { harness ->
                harness.seed()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val frames = SessionEventFrames(harness.sessionEvents, harness.service, scope)
                    val subscribed = List(5) { CompletableDeferred<Unit>() }
                    val received =
                        subscribed.map { ready ->
                            scope.async { frames.frames.onSubscription { ready.complete(Unit) }.take(2).toList() }
                        }
                    withTimeout(5.seconds) {
                        subscribed.awaitAll()
                        // However many clients listen, the session events are collected once.
                        while (harness.sessionEvents.subscriptionCount.value < 1) delay(5.milliseconds)
                        harness.sessionEvents.subscriptionCount.value shouldBe 1

                        harness.sessionEvents.emit(started(sessionFor("mu-alice", "srv-1", "1"), EVENT_TIMESTAMP_MS))
                        harness.sessionEvents.emit(stopped(sessionFor("mu-carol", "srv-2", "2"), EVENT_TIMESTAMP_MS + 1))
                        val perSubscriber = received.awaitAll()

                        for (frames in perSubscriber) {
                            frames shouldHaveSize 2
                            frames[0] shouldBeSameInstanceAs perSubscriber.first()[0]
                            frames[1] shouldBeSameInstanceAs perSubscriber.first()[1]
                            frames[0].event shouldBeSameInstanceAs perSubscriber.first()[0].event
                        }
                        perSubscriber.first().map { it.userId } shouldContainExactly listOf("mu-alice", "mu-carol")
                        perSubscriber.first().map { it.event.event } shouldContainExactly listOf("session_started", "session_stopped")
                    }
                    harness.users.lookupsOf("mu-alice") shouldBe 1
                    harness.users.lookupsOf("mu-carol") shouldBe 1
                    harness.servers.findByIdCalls.get() shouldBe 2
                    harness.users.findAllCalls.get() shouldBe 0

                    // The last subscriber leaving stops the shared collector.
                    withTimeout(5.seconds) {
                        while (harness.sessionEvents.subscriptionCount.value > 0) delay(5.milliseconds)
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("does no work while no client listens") {
            createFanOutHarness().use { harness ->
                harness.seed()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    SessionEventFrames(harness.sessionEvents, harness.service, scope)
                    delay(50.milliseconds)
                    harness.sessionEvents.subscriptionCount.value shouldBe 0
                    harness.sessionEvents.emit(started(sessionFor("mu-alice", "srv-1", "1"), EVENT_TIMESTAMP_MS))
                    delay(50.milliseconds)
                    harness.users.findByIdsRequests.shouldBeEmpty()
                    harness.servers.findByIdCalls.get() shouldBe 0
                } finally {
                    scope.cancel()
                }
            }
        }

        test("an event whose enrichment throws is skipped without silencing later events") {
            createFanOutHarness(failUserLookupsFor = setOf("mu-boom")).use { harness ->
                harness.seed()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val frames = SessionEventFrames(harness.sessionEvents, harness.service, scope)
                    val ready = CompletableDeferred<Unit>()
                    val first = scope.async { frames.frames.onSubscription { ready.complete(Unit) }.first() }
                    withTimeout(5.seconds) {
                        ready.await()
                        while (harness.sessionEvents.subscriptionCount.value < 1) delay(5.milliseconds)
                        harness.sessionEvents.emit(started(sessionFor("mu-boom", "srv-1", "1"), EVENT_TIMESTAMP_MS))
                        harness.sessionEvents.emit(started(sessionFor("mu-alice", "srv-1", "2"), EVENT_TIMESTAMP_MS + 1))
                        first.await().userId shouldBe "mu-alice"
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a client that stops reading does not hold back the others") {
            createFanOutHarness().use { harness ->
                harness.seed()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val frames = SessionEventFrames(harness.sessionEvents, harness.service, scope)
                    val stalledReady = CompletableDeferred<Unit>()
                    val activeReady = CompletableDeferred<Unit>()
                    scope.launch {
                        frames.frames.onSubscription { stalledReady.complete(Unit) }.collect { awaitCancellation() }
                    }
                    val eventCount = 300
                    val active = scope.async { frames.frames.onSubscription { activeReady.complete(Unit) }.take(eventCount).toList() }
                    withTimeout(30.seconds) {
                        stalledReady.await()
                        activeReady.await()
                        while (harness.sessionEvents.subscriptionCount.value < 1) delay(5.milliseconds)
                        val session = sessionFor("mu-alice", "srv-1", "1")
                        repeat(eventCount) { harness.sessionEvents.emit(updated(session, EVENT_TIMESTAMP_MS + it)) }
                        active.await().map { ReceivedFrame("frame", it.event.data.orEmpty()).timestampMs } shouldContainExactly
                            List(eventCount) { EVENT_TIMESTAMP_MS + it }
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("frame visibility is the per-principal filter that applied to raw events") {
            val frame =
                SessionEventFrame(serverId = "srv-1", userId = "mu-bob", event = ServerSentEvent(data = "{}"))
            val scoped = MiruPrincipal(accountId = "acc-bob", username = "bob", role = MiruRole.USER, linkedMediaUserIds = setOf("mu-bob"))
            val other = scoped.copy(accountId = "acc-eve", username = "eve", linkedMediaUserIds = setOf("mu-eve"))
            frame.isVisibleTo(adminPrincipal(), serverId = null) shouldBe true
            frame.isVisibleTo(adminPrincipal(), serverId = "srv-1") shouldBe true
            frame.isVisibleTo(adminPrincipal(), serverId = "srv-2") shouldBe false
            frame.isVisibleTo(scoped, serverId = null) shouldBe true
            frame.isVisibleTo(scoped, serverId = "srv-2") shouldBe false
            frame.isVisibleTo(other, serverId = null) shouldBe false
            frame.isVisibleTo(scoped.copy(linkedMediaUserIds = emptySet()), serverId = null) shouldBe false
        }

        test("listings and frames read only the streaming users, and pick up name, avatar, and server changes at once") {
            createFanOutHarness().use { harness ->
                harness.seed()
                val bobAccount =
                    harness.authService
                        .createAccount(
                            username = "bobuser",
                            password = "Bob-password-123",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("mu-bob", "mu-bob2"),
                            displayName = "Bobby",
                        ).shouldBeRight()
                        .account
                val bobStream = sessionFor("mu-bob", "srv-1", "21")
                harness.activeSessions.upsert(bobStream).shouldBeRight()
                harness.activeSessions.upsert(sessionFor("mu-carol", "srv-2", "22")).shouldBeRight()

                val first = harness.service.listActiveStreams(adminPrincipal()).shouldBeRight()
                first.streams.map { it.user.id to it.user.displayName } shouldContainExactly
                    listOf("mu-bob" to "Bobby", "mu-carol" to "Carol")
                harness.users.findAllCalls.get() shouldBe 0
                // The streaming users, plus bob's linked profile the resolver needs for his account's name.
                harness.users.findByIdsRequests
                    .flatten()
                    .toSet() shouldBe setOf("mu-bob", "mu-carol", "mu-bob2")

                harness.accounts
                    .updateProfile(
                        id = bobAccount.id,
                        displayName = DisplayNameMutation.Set("Robert"),
                        avatar = AvatarMutation.Set(byteArrayOf(1, 2, 3), "image/png", avatarUpdatedAtMs = 42L),
                        updatedAtMs = 42L,
                    ).shouldBeRight()
                harness.cachedServers
                    .upsert(harness.rawServers.findById("srv-1").shouldBeRight()!!.copy(name = "Renamed", updatedAtMs = 2_000L))
                    .shouldBeRight()

                val bobNow = harness.service.listActiveStreams(adminPrincipal()).shouldBeRight().streams.first()
                bobNow.user.displayName shouldBe "Robert"
                bobNow.user.avatarUrl shouldBe "/api/v1/users/${bobAccount.id}/avatar?v=42"
                bobNow.serverName shouldBe "Renamed"

                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val frames = SessionEventFrames(harness.sessionEvents, harness.service, scope)
                    val ready = CompletableDeferred<Unit>()
                    val next = scope.async { frames.frames.onSubscription { ready.complete(Unit) }.first() }
                    withTimeout(5.seconds) {
                        ready.await()
                        while (harness.sessionEvents.subscriptionCount.value < 1) delay(5.milliseconds)
                        harness.accounts
                            .updateProfile(id = bobAccount.id, displayName = DisplayNameMutation.Set("Rob"), updatedAtMs = 43L)
                            .shouldBeRight()
                        harness.sessionEvents.emit(updated(bobStream, EVENT_TIMESTAMP_MS))
                        val stream = MiruJson.decodeFromString<SessionUpdatedEventDto>(next.await().event.data.orEmpty()).stream
                        stream.user.displayName shouldBe "Rob"
                        stream.serverName shouldBe "Renamed"
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("GET /api/v1/streams/events keeps admins, scoped users, and serverId filters isolated across subscribers") {
            createFanOutHarness().use { harness ->
                harness.seed()
                val admin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService
                    .createAccount(
                        username = "bobuser",
                        password = "Bob-password-123",
                        role = MiruRole.USER,
                        linkedMediaUserIds = setOf("mu-bob", "mu-bob2"),
                    ).shouldBeRight()
                val bob = harness.authService.login("bobuser", "Bob-password-123").shouldBeRight()
                harness.authService
                    .createAccount(username = "nobody", password = "Nobody-password-123", role = MiruRole.USER)
                    .shouldBeRight()
                val nobody = harness.authService.login("nobody", "Nobody-password-123").shouldBeRight()

                val bobOnServer1 = sessionFor("mu-bob", "srv-1", "11")
                val bobOnServer2 = sessionFor("mu-bob2", "srv-2", "12")
                val events =
                    listOf(
                        started(sessionFor("mu-alice", "srv-1", "10"), EVENT_TIMESTAMP_MS),
                        updated(bobOnServer1, EVENT_TIMESTAMP_MS + 1),
                        started(bobOnServer2, EVENT_TIMESTAMP_MS + 2),
                        stopped(sessionFor("mu-carol", "srv-2", "13"), EVENT_TIMESTAMP_MS + 3),
                    )

                testApplication {
                    application {
                        miruModule(
                            config = harness.appConfig,
                            authService = harness.authService,
                            streamsAndUsersService = harness.service,
                            sessionEvents = harness.sessionEvents,
                            sseKeepaliveInterval = 100.milliseconds,
                        )
                    }

                    data class Subscriber(
                        val name: String,
                        val token: String,
                        val serverId: String?,
                    ) {
                        val frames = Channel<ReceivedFrame>(Channel.UNLIMITED)
                        val connected = CompletableDeferred<Unit>()
                    }

                    val subscribers =
                        listOf(
                            Subscriber("admin", admin.signedToken, null),
                            Subscriber("bob", bob.signedToken, null),
                            Subscriber("admin@srv-2", admin.signedToken, "srv-2"),
                            Subscriber("bob@srv-1", bob.signedToken, "srv-1"),
                            Subscriber("nobody", nobody.signedToken, null),
                        )
                    val jobs =
                        subscribers.map { subscriber ->
                            async {
                                val query = subscriber.serverId?.let { "?serverId=$it" }.orEmpty()
                                client
                                    .prepareGet("/api/v1/streams/events$query") {
                                        cookie(SESSION_COOKIE_NAME, subscriber.token)
                                    }.execute { response ->
                                        response.status shouldBe HttpStatusCode.OK
                                        val channel = response.bodyAsChannel()
                                        var event: String? = null
                                        val data = mutableListOf<String>()
                                        while (!channel.isClosedForRead) {
                                            val line = channel.readLine() ?: break
                                            when {
                                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                                line.startsWith("data:") -> data += line.removePrefix("data:").trim()
                                                line.isEmpty() && event != null -> {
                                                    if (event == "snapshot") {
                                                        subscriber.connected.complete(Unit)
                                                    } else {
                                                        subscriber.frames.trySend(ReceivedFrame(event, data.joinToString("\n")))
                                                    }
                                                    event = null
                                                    data.clear()
                                                }
                                            }
                                        }
                                    }
                            }
                        }

                    withTimeout(20.seconds) {
                        subscribers.forEach { it.connected.await() }
                        // A client subscribes just after its snapshot; probe until every one that can see a probe has.
                        val probes = listOf(updated(bobOnServer1, WARM_UP_TIMESTAMP_MS), updated(bobOnServer2, WARM_UP_TIMESTAMP_MS))
                        val warmed = subscribers.filter { it.name != "nobody" }
                        val pending = warmed.toMutableSet()
                        while (pending.isNotEmpty()) {
                            probes.forEach { harness.sessionEvents.emit(it) }
                            delay(50.milliseconds)
                            pending.removeAll { subscriber ->
                                generateSequence { subscriber.frames.tryReceive().getOrNull() }
                                    .any { it.timestampMs == WARM_UP_TIMESTAMP_MS }
                            }
                        }
                        // Drain probe frames still in flight before the real events.
                        delay(200.milliseconds)
                        warmed.forEach { subscriber -> generateSequence { subscriber.frames.tryReceive().getOrNull() }.count() }

                        events.forEach { harness.sessionEvents.emit(it) }
                        // Fences come after every real event, so a client that has a fence has had all it may see.
                        probes.forEach { harness.sessionEvents.emit(fence(it)) }

                        val seen =
                            warmed.associate { subscriber ->
                                val real = mutableListOf<ReceivedFrame>()
                                while (true) {
                                    val frame = subscriber.frames.receive()
                                    if (frame.timestampMs >= FENCE_TIMESTAMP_MS) break
                                    if (frame.timestampMs >= EVENT_TIMESTAMP_MS) real += frame
                                }
                                subscriber.name to real
                            }

                        seen.getValue("admin").map { it.userId } shouldContainExactly listOf("mu-alice", "mu-bob", "mu-bob2", "mu-carol")
                        seen.getValue("bob").map { it.userId } shouldContainExactly listOf("mu-bob", "mu-bob2")
                        seen.getValue("admin@srv-2").map { it.userId } shouldContainExactly listOf("mu-bob2", "mu-carol")
                        seen.getValue("bob@srv-1").map { it.userId } shouldContainExactly listOf("mu-bob")
                        seen.getValue("admin").map { it.event } shouldContainExactly
                            listOf("session_started", "session_updated", "session_started", "session_stopped")

                        // Every subscriber got the one encoding of each event.
                        val bob2Payloads = seen.values.flatten().filter { it.userId == "mu-bob2" }.map { it.data }
                        bob2Payloads shouldHaveSize 3
                        bob2Payloads.toSet() shouldHaveSize 1

                        // The unlinked user, filtered from every event, only ever sees keepalives.
                        withTimeoutOrNull(300.milliseconds) { subscribers.last().frames.receive() } shouldBe null
                    }

                    // One lookup per event, not one per subscriber who sees it.
                    harness.users.lookupsOf("mu-alice") shouldBe 1
                    harness.users.lookupsOf("mu-carol") shouldBe 1
                    harness.users.findAllCalls.get() shouldBe 0
                    jobs.forEach { it.cancel() }
                }
            }
        }
    })

private fun fence(probe: SessionEvent): SessionEvent =
    when (probe) {
        is SessionEvent.SessionUpdated -> probe.copy(timestampMs = FENCE_TIMESTAMP_MS)
        else -> error("fences are built from update probes")
    }
