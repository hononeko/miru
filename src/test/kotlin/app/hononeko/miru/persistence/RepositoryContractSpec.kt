package app.hononeko.miru.persistence

import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.JdbcActiveSessionRepository
import app.hononeko.miru.persistence.repository.JdbcLibraryRepository
import app.hononeko.miru.persistence.repository.JdbcMediaServerRepository
import app.hononeko.miru.persistence.repository.JdbcPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.JdbcUserRepository
import app.hononeko.miru.persistence.repository.LibraryRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.funSpec
import io.kotest.core.spec.style.scopes.FunSpecRootScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

private class ContractContext(
    val handle: DatabaseHandle,
) {
    val servers: MediaServerRepository = JdbcMediaServerRepository(handle)
    val users: UserRepository = JdbcUserRepository(handle)
    val sessions: ActiveSessionRepository = JdbcActiveSessionRepository(handle)
    val history: PlaybackHistoryRepository = JdbcPlaybackHistoryRepository(handle)
    val library: LibraryRepository = JdbcLibraryRepository(handle)
}

fun repositoryContractSpec(handleProvider: () -> DatabaseHandle): TestFactory =
    funSpec {
        lateinit var ctx: ContractContext

        beforeEach {
            val handle = handleProvider()
            handle.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM media_servers")
                }
            }
            ctx = ContractContext(handle)
        }

        mediaServerTests { ctx }
        userTests { ctx }
        activeSessionTests { ctx }
        playbackHistoryTests { ctx }
        libraryTests { ctx }
        cascadeTests { ctx }
    }

private fun FunSpecRootScope.mediaServerTests(ctxProvider: () -> ContractContext) {
    context("MediaServerRepository") {
        test("upserts, retrieves, updates without changing createdAt, and deletes servers") {
            val ctx = ctxProvider()
            val s1 = sampleServer(id = "srv-1", name = "Home Plex", machineId = "mach-1", createdAtMs = 100L, updatedAtMs = 100L)
            val s2 = sampleServer(id = "srv-2", name = "Backup Plex", machineId = "mach-2", createdAtMs = 200L, updatedAtMs = 200L)

            ctx.servers.upsert(s1).shouldBeRight() shouldBe s1
            ctx.servers.upsert(s2).shouldBeRight() shouldBe s2

            ctx.servers.findById("srv-1").shouldBeRight() shouldBe s1
            ctx.servers.findByMachineId("mach-2").shouldBeRight() shouldBe s2
            ctx.servers.findAll().shouldBeRight() shouldContainExactly listOf(s1, s2)

            val renamed = s1.copy(name = "Renamed Plex", baseUrl = "https://plex.internal:32400", updatedAtMs = 500L)
            val updated = ctx.servers.upsert(renamed).shouldBeRight()
            updated shouldBe renamed

            val duplicateMachine =
                sampleServer(
                    id = "srv-ignored",
                    name = "Updated via Machine",
                    machineId = "mach-1",
                    createdAtMs = 999L,
                    updatedAtMs = 600L,
                )
            val merged = ctx.servers.upsert(duplicateMachine).shouldBeRight()
            merged.id shouldBe "srv-1"
            merged.createdAtMs shouldBe 100L
            merged.name shouldBe "Updated via Machine"
            merged.updatedAtMs shouldBe 600L

            ctx.servers.delete("srv-1").shouldBeRight() shouldBe true
            ctx.servers.delete("srv-1").shouldBeRight() shouldBe false
            ctx.servers.findById("srv-1").shouldBeRight().shouldBeNull()
        }
    }
}

private fun FunSpecRootScope.userTests(ctxProvider: () -> ContractContext) {
    context("UserRepository") {
        test("upserts users idempotently by server and external id and updates last seen") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()

            val initial =
                UserRecord(
                    id = "user-1",
                    serverId = "srv-1",
                    externalUserId = "ext-42",
                    username = "alice",
                    displayName = "Alice",
                    email = "alice@example.com",
                    thumbUrl = "https://plex.tv/users/42/avatar",
                    isAdmin = true,
                    lastSeenAtMs = 1_000L,
                    createdAtMs = 500L,
                    updatedAtMs = 1_000L,
                )
            ctx.users.upsert(initial).shouldBeRight() shouldBe initial
            ctx.users.findById("user-1").shouldBeRight() shouldBe initial
            ctx.users.findByExternalId("srv-1", "ext-42").shouldBeRight() shouldBe initial

            val syncedWithoutLastSeen =
                initial.copy(
                    id = "user-new-uuid",
                    displayName = "Alice K.",
                    isAdmin = false,
                    lastSeenAtMs = null,
                    createdAtMs = 2_000L,
                    updatedAtMs = 2_500L,
                )
            val updated = ctx.users.upsert(syncedWithoutLastSeen).shouldBeRight()
            updated.id shouldBe "user-1"
            updated.createdAtMs shouldBe 500L
            updated.lastSeenAtMs shouldBe 1_000L
            updated.displayName shouldBe "Alice K."
            updated.isAdmin shouldBe false

            ctx.users.updateLastSeen("user-1", lastSeenAtMs = 3_000L, updatedAtMs = 3_000L).shouldBeRight() shouldBe true
            ctx.users.updateLastSeen("missing", lastSeenAtMs = 3_000L).shouldBeRight() shouldBe false

            val byServer = ctx.users.findByServer("srv-1").shouldBeRight()
            byServer shouldHaveSize 1
            byServer.first().lastSeenAtMs shouldBe 3_000L
        }
    }
}

private fun FunSpecRootScope.activeSessionTests(ctxProvider: () -> ContractContext) {
    context("ActiveSessionRepository") {
        test("keeps separate rows for same sessionKey with different ratingKey and prunes stale sessions") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1", machineId = "m-1")).shouldBeRight()
            ctx.servers.upsert(sampleServer(id = "srv-2", machineId = "m-2")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-1", serverId = "srv-1", externalUserId = "1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-2", serverId = "srv-2", externalUserId = "2")).shouldBeRight()

            val ep1 = sampleSession(serverId = "srv-1", sessionKey = "sess-1", ratingKey = "ep-101", userId = "u-1", startedAtMs = 1_000L)
            val ep2 = sampleSession(serverId = "srv-1", sessionKey = "sess-1", ratingKey = "ep-102", userId = "u-1", startedAtMs = 2_000L)
            val srv2Session =
                sampleSession(serverId = "srv-2", sessionKey = "sess-9", ratingKey = "mov-1", userId = "u-2", startedAtMs = 1_500L)

            ctx.sessions.upsert(ep1).shouldBeRight()
            ctx.sessions.upsert(ep2).shouldBeRight()
            ctx.sessions.upsert(srv2Session).shouldBeRight()

            ctx.sessions.findByKey(ep1.key).shouldBeRight() shouldBe ep1
            ctx.sessions.findByKey(ep2.key).shouldBeRight() shouldBe ep2
            ctx.sessions.findByServer("srv-1").shouldBeRight() shouldContainExactly listOf(ep2, ep1)
            ctx.sessions.findAll().shouldBeRight() shouldContainExactly listOf(ep2, srv2Session, ep1)

            val updatedEp2 = ep2.copy(viewOffsetMs = 120_000L, state = "paused", pausedDurationSec = 15, missedPolls = 1)
            ctx.sessions.upsert(updatedEp2).shouldBeRight()
            ctx.sessions.findByKey(ep2.key).shouldBeRight() shouldBe updatedEp2

            ctx.sessions.deleteStaleMissing("srv-1", setOf(ep2.key)).shouldBeRight() shouldBe 1
            ctx.sessions.findByKey(ep1.key).shouldBeRight().shouldBeNull()
            ctx.sessions.findByKey(ep2.key).shouldBeRight() shouldBe updatedEp2
            ctx.sessions.findByServer("srv-2").shouldBeRight() shouldContainExactly listOf(srv2Session)

            ctx.sessions.deleteByKey(ep2.key).shouldBeRight() shouldBe true
            ctx.sessions.deleteByKey(ep2.key).shouldBeRight() shouldBe false
            ctx.sessions.deleteStaleMissing("srv-2", emptySet()).shouldBeRight() shouldBe 1
            ctx.sessions.findAll().shouldBeRight() shouldHaveSize 0
        }
    }
}

private fun FunSpecRootScope.playbackHistoryTests(ctxProvider: () -> ContractContext) {
    context("PlaybackHistoryRepository") {
        test("supports idempotent external_import_key, merge candidates, filters, wildcards, and pagination") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-1", serverId = "srv-1", externalUserId = "1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-2", serverId = "srv-1", externalUserId = "2", username = "bob")).shouldBeRight()

            val r1 =
                sampleHistory(
                    id = "hist-1",
                    userId = "u-1",
                    ratingKey = "mov-1",
                    title = "100%_Match Movie",
                    mediaType = "movie",
                    sectionKey = "sec-movies",
                    streamDecision = "transcode",
                    hwTranscode = true,
                    startedAtMs = 1_000L,
                    stoppedAtMs = 2_000L,
                    externalImportKey = "tautulli:1001",
                )
            val r2 =
                sampleHistory(
                    id = "hist-2",
                    userId = "u-1",
                    ratingKey = "ep-1",
                    title = "Pilot Episode",
                    parentTitle = "Season 1",
                    grandparentTitle = "Frieren: Beyond Journey's End",
                    mediaType = "episode",
                    sectionKey = "sec-tv",
                    streamDecision = "direct_play",
                    startedAtMs = 3_000L,
                    stoppedAtMs = 4_000L,
                    externalImportKey = null,
                )
            val r3 =
                sampleHistory(
                    id = "hist-3",
                    userId = "u-2",
                    ratingKey = "ep-2",
                    title = "Second Episode",
                    parentTitle = "Season 1",
                    grandparentTitle = "Frieren: Beyond Journey's End",
                    mediaType = "episode",
                    sectionKey = "sec-tv",
                    streamDecision = "direct_play",
                    startedAtMs = 5_000L,
                    stoppedAtMs = 6_000L,
                    externalImportKey = null,
                )

            ctx.history.insertIfAbsentByImportKey(r1).shouldBeRight() shouldBe true
            ctx.history.insertIfAbsentByImportKey(r1.copy(id = "hist-dup")).shouldBeRight() shouldBe false
            ctx.history.findById("hist-1").shouldBeRight() shouldBe r1

            ctx.history.insert(r2).shouldBeRight() shouldBe r2
            ctx.history.insert(r3).shouldBeRight() shouldBe r3

            ctx.history.findLatestMergeCandidate("srv-1", "u-1", "mov-1", minStoppedAtMs = 2_500L).shouldBeRight().shouldBeNull()
            val mergeCandidate =
                ctx.history.findLatestMergeCandidate("srv-1", "u-1", "mov-1", minStoppedAtMs = 1_500L).shouldBeRight().shouldNotBeNull()
            mergeCandidate.id shouldBe "hist-1"

            val merged =
                mergeCandidate.copy(
                    viewOffsetMs = 95_000L,
                    watchedDurationSec = 300,
                    completionPercent = 92.5,
                    isWatched = true,
                    stoppedAtMs = 4_500L,
                )
            ctx.history.updateMergedSession(merged).shouldBeRight() shouldBe true
            ctx.history.findById("hist-1").shouldBeRight() shouldBe merged

            val wildcardPage = ctx.history.query(HistoryFilter(q = "100%_match")).shouldBeRight()
            wildcardPage.totalCount shouldBe 1L
            wildcardPage.items shouldContainExactly listOf(merged)

            val showPage =
                ctx.history
                    .query(
                        HistoryFilter(
                            mediaType = "episode",
                            librarySectionKey = "sec-tv",
                            streamDecision = "direct_play",
                            q = "FRIEREN",
                            fromMs = 3_500L,
                            toMs = 6_500L,
                            limit = 1,
                            offset = 1,
                        ),
                    ).shouldBeRight()
            showPage.totalCount shouldBe 2L
            showPage.items shouldContainExactly listOf(r2)

            val userParentPage =
                ctx.history
                    .query(
                        HistoryFilter(
                            userId = "u-2",
                            q = "season 1",
                        ),
                    ).shouldBeRight()
            userParentPage.totalCount shouldBe 1L
            userParentPage.items shouldContainExactly listOf(r3)
        }
    }
}

private fun FunSpecRootScope.libraryTests(ctxProvider: () -> ContractContext) {
    context("LibraryRepository") {
        test("upserts sections with 64-bit sizes, orders snapshots descending, and upserts/prunes cached items") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()

            val movies =
                LibrarySectionRecord(
                    serverId = "srv-1",
                    sectionKey = "sec-1",
                    title = "Movies",
                    sectionType = "movie",
                    itemCount = 120,
                    childCount = 120,
                    leafCount = 120,
                    totalSizeBytes = 12_000_000_000L,
                    totalDurationMs = 720_000_000L,
                    lastSyncedAtMs = 10_000L,
                    updatedAtMs = 10_000L,
                )
            ctx.library.upsertSection(movies).shouldBeRight()
            ctx.library.upsertSection(movies.copy(itemCount = 125, updatedAtMs = 11_000L)).shouldBeRight()

            val sections = ctx.library.findSectionsByServer("srv-1").shouldBeRight()
            sections shouldHaveSize 1
            sections.first().itemCount shouldBe 125
            sections.first().totalSizeBytes shouldBe 12_000_000_000L

            val snap1 = sampleSnapshot(id = "sn-1", sectionKey = "sec-1", capturedAtMs = 1_000L, itemCount = 100)
            val snap2 = sampleSnapshot(id = "sn-2", sectionKey = "sec-1", capturedAtMs = 3_000L, itemCount = 125)
            val snap3 = sampleSnapshot(id = "sn-3", sectionKey = "sec-1", capturedAtMs = 2_000L, itemCount = 110)
            ctx.library.insertSnapshot(snap1).shouldBeRight()
            ctx.library.insertSnapshot(snap2).shouldBeRight()
            ctx.library.insertSnapshot(snap3).shouldBeRight()

            ctx.library.findSnapshots("srv-1", "sec-1", limit = 2).shouldBeRight() shouldContainExactly listOf(snap2, snap3)
            ctx.library.findSnapshots("srv-1", "sec-1", fromMs = 1_500L, toMs = 2_500L).shouldBeRight() shouldContainExactly listOf(snap3)

            val items =
                (1..5).map { idx ->
                    MediaItemCacheRecord(
                        serverId = "srv-1",
                        ratingKey = "item-$idx",
                        sectionKey = if (idx <= 3) "sec-1" else "sec-2",
                        mediaType = "movie",
                        title = "Movie $idx",
                        year = 2020 + idx,
                        durationMs = 5_400_000L,
                        tech = MediaItemTechRecord(fileSizeBytes = 8_000_000_000L, videoResolution = "4k", videoCodec = "hevc"),
                        addedAtMs = idx * 1_000L,
                        updatedAtMs = idx * 1_000L,
                    )
                }
            ctx.library.upsertMediaItems(items).shouldBeRight() shouldBe 5
            ctx.library
                .upsertMediaItems(
                    listOf(items[1].copy(title = "Movie 2 Remastered", updatedAtMs = 9_999L)),
                ).shouldBeRight() shouldBe 1
            ctx.library.findRecentlyAdded("srv-1", sectionKey = "sec-1", limit = 2).shouldBeRight()
                .map { it.ratingKey } shouldContainExactly listOf("item-3", "item-2")

            ctx.library.deleteItemsNotIn("srv-1", "sec-1", setOf("item-2")).shouldBeRight() shouldBe 2
            val remainingSec1 = ctx.library.findRecentlyAdded("srv-1", sectionKey = "sec-1").shouldBeRight()
            remainingSec1.map { it.ratingKey to it.title } shouldContainExactly listOf("item-2" to "Movie 2 Remastered")
            ctx.library.findRecentlyAdded("srv-1", sectionKey = "sec-2").shouldBeRight() shouldHaveSize 2

            ctx.library.deleteItemsNotIn("srv-1", "sec-1", emptySet()).shouldBeRight() shouldBe 1
            ctx.library.findRecentlyAdded("srv-1", sectionKey = "sec-1").shouldBeRight() shouldHaveSize 0
        }
    }
}

private fun FunSpecRootScope.cascadeTests(ctxProvider: () -> ContractContext) {
    context("Foreign key cascades") {
        test("deleting a media server cascades across all child tables") {
            val ctx = ctxProvider()
            val server = sampleServer(id = "srv-cascade")
            val user = sampleUser(id = "u-cascade", serverId = server.id)
            ctx.servers.upsert(server).shouldBeRight()
            ctx.users.upsert(user).shouldBeRight()
            ctx.sessions.upsert(sampleSession(serverId = server.id, sessionKey = "s1", ratingKey = "r1", userId = user.id)).shouldBeRight()
            ctx.history.insert(sampleHistory(id = "h1", serverId = server.id, userId = user.id, ratingKey = "r1")).shouldBeRight()
            ctx.library.upsertSection(
                LibrarySectionRecord(
                    serverId = server.id,
                    sectionKey = "sec-1",
                    title = "Anime",
                    sectionType = "show",
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
            ctx.library.insertSnapshot(sampleSnapshot(id = "sn-1", serverId = server.id, sectionKey = "sec-1", capturedAtMs = 1_000L))
                .shouldBeRight()
            ctx.library.upsertMediaItems(
                listOf(
                    MediaItemCacheRecord(
                        serverId = server.id,
                        ratingKey = "r1",
                        sectionKey = "sec-1",
                        mediaType = "show",
                        title = "Spy x Family",
                        addedAtMs = 1_000L,
                        updatedAtMs = 1_000L,
                    ),
                ),
            ).shouldBeRight()

            ctx.servers.delete(server.id).shouldBeRight() shouldBe true

            ctx.users.findByServer(server.id).shouldBeRight() shouldHaveSize 0
            ctx.sessions.findByServer(server.id).shouldBeRight() shouldHaveSize 0
            ctx.history.query(HistoryFilter(serverId = server.id)).shouldBeRight().totalCount shouldBe 0L
            ctx.library.findSectionsByServer(server.id).shouldBeRight() shouldHaveSize 0
            ctx.library.findSnapshots(server.id, "sec-1").shouldBeRight() shouldHaveSize 0
            ctx.library.findRecentlyAdded(server.id).shouldBeRight() shouldHaveSize 0
        }
    }
}

private fun sampleServer(
    id: String = UUID.randomUUID().toString(),
    name: String = "Primary Plex",
    machineId: String = "machine-$id",
    createdAtMs: Long = 1_000L,
    updatedAtMs: Long = 1_000L,
): MediaServerRecord =
    MediaServerRecord(
        id = id,
        name = name,
        serverType = "PLEX",
        machineId = machineId,
        baseUrl = "http://plex.local:32400",
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun sampleUser(
    id: String,
    serverId: String,
    externalUserId: String = "ext-$id",
    username: String = "alice",
): UserRecord =
    UserRecord(
        id = id,
        serverId = serverId,
        externalUserId = externalUserId,
        username = username,
        displayName = username.replaceFirstChar { it.uppercase() },
        isAdmin = false,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun sampleSession(
    serverId: String,
    sessionKey: String,
    ratingKey: String,
    userId: String,
    startedAtMs: Long = 1_000L,
): ActiveSessionRecord =
    ActiveSessionRecord(
        serverId = serverId,
        sessionKey = sessionKey,
        sessionId = "plex-sess-$sessionKey",
        userId = userId,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = "episode",
                title = "Episode $ratingKey",
                parentTitle = "Season 1",
                grandparentTitle = "Frieren",
                parentIndex = 1,
                mediaIndex = 1,
                year = 2023,
                thumbPath = "/library/metadata/$ratingKey/thumb",
                librarySectionKey = "sec-1",
                durationMs = 1_440_000L,
            ),
        viewOffsetMs = 60_000L,
        state = "playing",
        streamTech =
            StreamTechRecord(
                streamDecision = "transcode",
                videoDecision = "transcode",
                audioDecision = "copy",
                hwTranscode = true,
                videoCodec = "h264",
                audioCodec = "aac",
                videoResolution = "1080p",
                bandwidthKbps = 8_500,
            ),
        player =
            PlayerRecord(
                playerName = "Living Room TV",
                playerPlatform = "Apple TV",
                playerProduct = "Plex for Apple TV",
                playerMachineId = "appletv-1",
                ipAddress = "10.10.2.55",
                location = "lan",
            ),
        startedAtMs = startedAtMs,
        lastSeenAtMs = startedAtMs + 20_000L,
        pausedDurationSec = 0,
        missedPolls = 0,
    )

@Suppress("LongParameterList")
private fun sampleHistory(
    id: String,
    serverId: String = "srv-1",
    userId: String,
    ratingKey: String,
    title: String = "Sample Title",
    parentTitle: String? = null,
    grandparentTitle: String? = null,
    mediaType: String = "movie",
    sectionKey: String = "sec-1",
    streamDecision: String = "direct_play",
    hwTranscode: Boolean = false,
    startedAtMs: Long = 1_000L,
    stoppedAtMs: Long = 2_000L,
    externalImportKey: String? = null,
): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = id,
        serverId = serverId,
        userId = userId,
        externalImportKey = externalImportKey,
        media =
            MediaMetadataRecord(
                ratingKey = ratingKey,
                mediaType = mediaType,
                title = title,
                parentTitle = parentTitle,
                grandparentTitle = grandparentTitle,
                librarySectionKey = sectionKey,
                durationMs = 100_000L,
            ),
        viewOffsetMs = 50_000L,
        watchedDurationSec = 100,
        pausedDurationSec = 5,
        completionPercent = 50.0,
        isWatched = false,
        streamTech = StreamTechRecord(streamDecision = streamDecision, hwTranscode = hwTranscode),
        player = PlayerRecord(playerName = "Web", location = "lan"),
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
    )

private fun sampleSnapshot(
    id: String,
    serverId: String = "srv-1",
    sectionKey: String,
    capturedAtMs: Long,
    itemCount: Int = 10,
): LibrarySnapshotRecord =
    LibrarySnapshotRecord(
        id = id,
        serverId = serverId,
        sectionKey = sectionKey,
        capturedAtMs = capturedAtMs,
        itemCount = itemCount,
        childCount = itemCount,
        leafCount = itemCount,
        totalSizeBytes = 5_000_000_000L,
        totalDurationMs = 120_000_000L,
        aggregatesJson = """{"4k":$itemCount}""",
    )
