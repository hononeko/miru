package app.hononeko.miru.persistence

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.CachingMediaServerRepository
import app.hononeko.miru.persistence.repository.CachingWebhookTargetRepository
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.ExposedWebhookTargetRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.WebhookTargetRepository
import app.hononeko.miru.session.LiveActiveSessionRepository
import app.hononeko.miru.session.ReconcileTrigger
import app.hononeko.miru.session.SessionStateEngine
import app.hononeko.miru.session.StoppedPlayback
import arrow.core.Either
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.funSpec
import io.kotest.core.spec.style.scopes.FunSpecRootScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.atomic.AtomicInteger

private const val STORED_TOKEN = "enc-stored-media-server-token"

private class CountingServers(
    private val delegate: MediaServerRepository,
) : MediaServerRepository by delegate {
    val reads = AtomicInteger()

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<MediaServerRecord>> {
        reads.incrementAndGet()
        return delegate.findAll()
    }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?> {
        reads.incrementAndGet()
        return delegate.findById(id)
    }

    override suspend fun findByMachineId(machineId: String): Either<MiruError.PersistenceError, MediaServerRecord?> {
        reads.incrementAndGet()
        return delegate.findByMachineId(machineId)
    }
}

private class CountingTargets(
    private val delegate: WebhookTargetRepository,
) : WebhookTargetRepository by delegate {
    val reads = AtomicInteger()

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>> {
        reads.incrementAndGet()
        return delegate.findAll()
    }

    override suspend fun findEnabled(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>> {
        reads.incrementAndGet()
        return delegate.findEnabled()
    }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, WebhookTargetRecord?> {
        reads.incrementAndGet()
        return delegate.findById(id)
    }
}

private class CountingSessions(
    private val delegate: ActiveSessionRepository,
) : ActiveSessionRepository by delegate {
    val findAllCalls = AtomicInteger()

    // Runs once, inside the next findAll, to change the engines between the view's two reads.
    var onNextFindAll: (suspend () -> Unit)? = null

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<ActiveSessionRecord>> {
        findAllCalls.incrementAndGet()
        val result = delegate.findAll()
        onNextFindAll?.also { onNextFindAll = null }?.invoke()
        return result
    }
}

private fun snapshotServer(
    id: String,
    machineId: String = "mach-$id",
    name: String = "Server $id",
    serverType: String = "plex",
    encryptedToken: String? = null,
    createdAtMs: Long = 100L,
): MediaServerRecord =
    MediaServerRecord(
        id = id,
        name = name,
        serverType = serverType,
        machineId = machineId,
        baseUrl = "http://$id.local:32400",
        encryptedToken = encryptedToken,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )

private fun snapshotTarget(
    id: String,
    name: String = "Target $id",
    enabled: Boolean = true,
    createdAtMs: Long = 100L,
): WebhookTargetRecord =
    WebhookTargetRecord(
        id = id,
        name = name,
        url = "https://hooks.example.com/$id",
        enabled = enabled,
        subscribedEvents = listOf(WebhookEventType.TRANSCODE_STARTED),
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )

private fun persistedSession(
    serverId: String,
    sessionKey: String,
    userId: String,
    startedAtMs: Long,
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
        viewOffsetMs = 10_000L,
        state = "playing",
        streamTech = StreamTechRecord(streamDecision = "direct_play"),
        player = PlayerRecord(playerName = "Web", playerMachineId = "player-$sessionKey", location = "lan"),
        startedAtMs = startedAtMs,
        lastSeenAtMs = startedAtMs,
    )

private fun liveSnapshot(
    sessionKey: String,
    userExternalId: String,
    viewOffsetMs: Long = 0L,
    ratingKey: String = "rk-$sessionKey",
): ActiveStreamSnapshot =
    ActiveStreamSnapshot(
        sessionId = "sess-$sessionKey",
        sessionKey = sessionKey,
        state = PlaybackState.PLAYING,
        viewOffsetMs = viewOffsetMs,
        media = StreamMedia(ratingKey = ratingKey, mediaType = MediaType.MOVIE, title = "Movie $sessionKey", durationMs = 1_000_000L),
        user = StreamUser(externalId = userExternalId, username = "user-$userExternalId"),
        player =
            StreamPlayer(
                machineIdentifier = "player-$sessionKey",
                product = "Plex Web",
                location = StreamLocation.LAN,
                isLocal = true,
            ),
        technical = StreamTechnicalInfo(decision = StreamDecision.DIRECT_PLAY),
    )

/**
 * The write-invalidated reference snapshots and the memory-backed `active_sessions` view, checked against the
 * real repositories on whichever database [handleProvider] opens.
 */
fun referenceSnapshotContractSpec(handleProvider: () -> DatabaseHandle): TestFactory =
    funSpec {
        beforeEach {
            handleProvider().dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    // Cascades to users, active sessions, and history.
                    stmt.executeUpdate("DELETE FROM media_servers")
                    stmt.executeUpdate("DELETE FROM webhook_deliveries")
                    stmt.executeUpdate("DELETE FROM webhook_targets")
                }
            }
        }

        cachingMediaServerTests(handleProvider)
        cachingWebhookTargetTests(handleProvider)
        liveActiveSessionTests(handleProvider)
    }

private fun FunSpecRootScope.cachingMediaServerTests(handleProvider: () -> DatabaseHandle) {
    context("CachingMediaServerRepository") {
        test("serves reads from one snapshot and reloads after every kind of write") {
            val handle = handleProvider()
            val raw = ExposedMediaServerRepository(handle)
            val counted = CountingServers(raw)
            val servers = CachingMediaServerRepository(counted)

            suspend fun shouldMatchDatabase() {
                val expected = raw.findAll().shouldBeRight()
                servers.findAll().shouldBeRight() shouldContainExactly expected
                for (record in expected) {
                    servers.findById(record.id).shouldBeRight() shouldBe record
                    servers.findByMachineId(record.machineId).shouldBeRight() shouldBe record
                }
            }

            suspend fun readsAfter(write: suspend () -> Unit): Int {
                write()
                val before = counted.reads.get()
                shouldMatchDatabase()
                shouldMatchDatabase()
                return counted.reads.get() - before
            }

            servers.findAll().shouldBeRight() shouldBe emptyList()
            readsAfter { servers.upsert(snapshotServer("srv-1", encryptedToken = STORED_TOKEN)).shouldBeRight() } shouldBe 1
            readsAfter { servers.upsert(snapshotServer("srv-2", createdAtMs = 200L)).shouldBeRight() } shouldBe 1
            // Rename through the upsert the runtimes run on every activation.
            readsAfter { servers.upsert(snapshotServer("srv-1", name = "Renamed", encryptedToken = null)).shouldBeRight() } shouldBe 1
            servers.findById("srv-1").shouldBeRight()?.name shouldBe "Renamed"
            readsAfter {
                servers.upsertExclusiveStoredCredentials(snapshotServer("srv-2", encryptedToken = "enc-other")).shouldBeRight()
            } shouldBe 1
            servers.findById("srv-1").shouldBeRight()?.encryptedToken.shouldBeNull()
            readsAfter {
                servers
                    .updateCredentials("srv-1", "Updated", "http://srv-1.lan:32400", STORED_TOKEN, "client-1", 300L)
                    .shouldBeRight()
            } shouldBe 1
            servers.findById("srv-1").shouldBeRight()?.baseUrl shouldBe "http://srv-1.lan:32400"
            readsAfter { servers.clearCredentials("srv-2", 400L).shouldBeRight() } shouldBe 1
            servers.findById("srv-2").shouldBeRight()?.encryptedToken.shouldBeNull()
            readsAfter { servers.delete("srv-2").shouldBeRight() shouldBe true } shouldBe 1
            servers.findById("srv-2").shouldBeRight().shouldBeNull()
            servers.findByMachineId("mach-srv-2").shouldBeRight().shouldBeNull()
            // A write that changes nothing still drops the snapshot rather than guessing.
            readsAfter { servers.delete("srv-missing").shouldBeRight() shouldBe false } shouldBe 1

            servers.toString() shouldNotContain STORED_TOKEN
        }
    }
}

private fun FunSpecRootScope.cachingWebhookTargetTests(handleProvider: () -> DatabaseHandle) {
    context("CachingWebhookTargetRepository") {
        test("serves the runner's enabled-target lookup from a snapshot and reloads after create, edit, toggle, and delete") {
            val handle = handleProvider()
            val raw = ExposedWebhookTargetRepository(handle)
            val counted = CountingTargets(raw)
            val targets = CachingWebhookTargetRepository(counted)

            suspend fun readsAfter(write: suspend () -> Unit): Int {
                write()
                val before = counted.reads.get()
                repeat(3) {
                    targets.findEnabled().shouldBeRight() shouldContainExactly raw.findEnabled().shouldBeRight()
                    targets.findAll().shouldBeRight() shouldContainExactly raw.findAll().shouldBeRight()
                    for (record in raw.findAll().shouldBeRight()) {
                        targets.findById(record.id).shouldBeRight() shouldBe record
                    }
                }
                return counted.reads.get() - before
            }

            readsAfter { targets.insert(snapshotTarget("t-1")).shouldBeRight() } shouldBe 1
            readsAfter { targets.insert(snapshotTarget("t-2", enabled = false, createdAtMs = 50L)).shouldBeRight() } shouldBe 1
            targets.findEnabled().shouldBeRight().map { it.id } shouldContainExactly listOf("t-1")
            readsAfter { targets.update(snapshotTarget("t-2", enabled = true, createdAtMs = 50L)).shouldBeRight() } shouldBe 1
            targets.findEnabled().shouldBeRight().map { it.id } shouldContainExactly listOf("t-2", "t-1")
            readsAfter { targets.update(snapshotTarget("t-1", name = "Renamed")).shouldBeRight() } shouldBe 1
            targets.findById("t-1").shouldBeRight()?.name shouldBe "Renamed"
            readsAfter { targets.update(snapshotTarget("t-1", name = "Renamed", enabled = false)).shouldBeRight() } shouldBe 1
            targets.findEnabled().shouldBeRight().map { it.id } shouldContainExactly listOf("t-2")
            readsAfter { targets.deleteById("t-2").shouldBeRight() shouldBe true } shouldBe 1
            targets.findById("t-2").shouldBeRight().shouldBeNull()
            targets.findEnabled().shouldBeRight() shouldBe emptyList()
        }
    }
}

private fun FunSpecRootScope.liveActiveSessionTests(handleProvider: () -> DatabaseHandle) {
    context("LiveActiveSessionRepository") {
        test("returns exactly what active_sessions holds through restore, start, update, stop, and server removal") {
            val handle = handleProvider()
            val rawServers = ExposedMediaServerRepository(handle)
            val servers = CachingMediaServerRepository(rawServers)
            val users = ExposedUserRepository(handle)
            val rawSessions = ExposedActiveSessionRepository(handle)
            servers.upsert(snapshotServer("srv-live")).shouldBeRight()
            servers.upsert(snapshotServer("srv-gone", serverType = "jellyfin", createdAtMs = 200L)).shouldBeRight()
            for ((id, serverId) in listOf("mu-restored" to "srv-live", "mu-ghost" to "srv-gone")) {
                users
                    .upsert(
                        UserRecord(
                            id = id,
                            serverId = serverId,
                            externalUserId = "ext-$id",
                            username = id,
                            createdAtMs = 100L,
                            updatedAtMs = 100L,
                        ),
                    ).shouldBeRight()
            }
            // Left by an earlier run: one the live engine will restore, one for a server no runtime points at.
            rawSessions.upsert(persistedSession("srv-live", "7", "mu-restored", startedAtMs = 5_000L)).shouldBeRight()
            rawSessions.upsert(persistedSession("srv-gone", "3", "mu-ghost", startedAtMs = 6_000L)).shouldBeRight()

            var nowMs = 10_000L
            val ids = AtomicInteger()
            val engine =
                SessionStateEngine(
                    serverIdProvider = { "srv-live".right() },
                    userRepository = users,
                    activeSessionRepository = rawSessions,
                    playbackHistoryRepository = ExposedPlaybackHistoryRepository(handle),
                    clock = { nowMs },
                    idGenerator = { "id-${ids.incrementAndGet()}" },
                )
            val counted = CountingSessions(rawSessions)
            val view = LiveActiveSessionRepository(delegate = counted, engines = listOf(engine), mediaServerRepository = servers)

            suspend fun shouldMatchTable() {
                view.findAll().shouldBeRight() shouldContainExactly rawSessions.findAll().shouldBeRight()
            }

            // Before the engine's first reconcile its memory is empty; the restored rows still show.
            shouldMatchTable()
            view.findAll().shouldBeRight().map { it.sessionKey } shouldContainExactly listOf("3", "7")

            // Three sessions starting in one poll share startedAtMs, so the tie-break order is what is compared.
            val restored = liveSnapshot("7", "ext-mu-restored", viewOffsetMs = 20_000L)
            val started =
                listOf(liveSnapshot("9", "ext-a"), liveSnapshot("10", "ext-b"), liveSnapshot("11", "ext-a", ratingKey = "rk-0"))
            engine.reconcile(listOf(restored) + started, ReconcileTrigger.PERIODIC).shouldBeRight()
            shouldMatchTable()
            view.findAll().shouldBeRight().map { it.sessionKey } shouldContainExactly listOf("10", "11", "9", "3", "7")

            // Progress-only polls change no session set: served from memory, fresh, with no table read.
            val readsBefore = counted.findAllCalls.get()
            nowMs = 20_000L
            engine
                .reconcile(listOf(restored.copy(viewOffsetMs = 90_000L)) + started, ReconcileTrigger.PERIODIC)
                .shouldBeRight()
            repeat(3) { shouldMatchTable() }
            view.findAll().shouldBeRight().single { it.sessionKey == "7" }.viewOffsetMs shouldBe 90_000L
            counted.findAllCalls.get() shouldBe readsBefore

            // A stop is visible on the next read.
            nowMs = 30_000L
            engine
                .reconcile(listOf(restored) + started.drop(1), ReconcileTrigger.NOTIFICATION, setOf(StoppedPlayback("9")))
                .shouldBeRight()
            shouldMatchTable()
            view.findAll().shouldBeRight().map { it.sessionKey } shouldContainExactly listOf("10", "11", "3", "7")

            // Removing the ghost's server cascades its row away without any engine noticing.
            servers.delete("srv-gone").shouldBeRight() shouldBe true
            shouldMatchTable()
            view.findAll().shouldBeRight().map { it.sessionKey } shouldContainExactly listOf("10", "11", "7")
        }

        test("rereads when a session starts between its table read and its memory read") {
            val handle = handleProvider()
            val servers = ExposedMediaServerRepository(handle)
            val users = ExposedUserRepository(handle)
            val rawSessions = ExposedActiveSessionRepository(handle)
            servers.upsert(snapshotServer("srv-live")).shouldBeRight()
            val engine =
                SessionStateEngine(
                    serverIdProvider = { "srv-live".right() },
                    userRepository = users,
                    activeSessionRepository = rawSessions,
                    playbackHistoryRepository = ExposedPlaybackHistoryRepository(handle),
                    clock = { 10_000L },
                )
            engine.reconcile(listOf(liveSnapshot("1", "ext-a")), ReconcileTrigger.PERIODIC).shouldBeRight()
            val counted = CountingSessions(rawSessions)
            val view = LiveActiveSessionRepository(delegate = counted, engines = listOf(engine), mediaServerRepository = servers)
            counted.onNextFindAll = {
                engine
                    .reconcile(listOf(liveSnapshot("1", "ext-a"), liveSnapshot("2", "ext-b")), ReconcileTrigger.NOTIFICATION)
                    .shouldBeRight()
            }

            view.findAll().shouldBeRight() shouldContainExactly rawSessions.findAll().shouldBeRight()
            view.findAll().shouldBeRight().map { it.sessionKey } shouldContainExactly listOf("1", "2")
            // The first attempt's two reads disagreed, so it read the table again before trusting it.
            counted.findAllCalls.get() shouldBe 2
        }
    }
}
