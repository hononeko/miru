package app.hononeko.miru.persistence

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.importer.TautulliImportOptions
import app.hononeko.miru.importer.TautulliImporterService
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.HistorySortDirection
import app.hononeko.miru.persistence.model.HistorySortField
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserPlaybackSummary
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.ApiKeyRepository
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.LibraryRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.HistoryQueryParams
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.funSpec
import io.kotest.core.spec.style.scopes.FunSpecRootScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

private class ContractContext(
    val handle: DatabaseHandle,
) {
    val servers: MediaServerRepository = ExposedMediaServerRepository(handle)
    val users: UserRepository = ExposedUserRepository(handle)
    val sessions: ActiveSessionRepository = ExposedActiveSessionRepository(handle)
    val history: PlaybackHistoryRepository = ExposedPlaybackHistoryRepository(handle)
    val library: LibraryRepository = ExposedLibraryRepository(handle)
    val authAccounts: AuthAccountRepository = ExposedAuthAccountRepository(handle)
    val authSessions: AuthSessionRepository = ExposedAuthSessionRepository(handle)
    val apiKeys: ApiKeyRepository = ExposedApiKeyRepository(handle)
}

fun repositoryContractSpec(handleProvider: () -> DatabaseHandle): TestFactory =
    funSpec {
        lateinit var ctx: ContractContext

        beforeEach {
            val handle = handleProvider()
            handle.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM auth_accounts")
                    stmt.executeUpdate("DELETE FROM media_servers")
                }
            }
            ctx = ContractContext(handle)
        }

        mediaServerTests { ctx }
        userTests { ctx }
        activeSessionTests { ctx }
        playbackHistoryTests { ctx }
        playbackHistorySummaryTests { ctx }
        playbackHistorySortAndStatsTests { ctx }
        libraryTests { ctx }
        cascadeTests { ctx }
        authAccountTests { ctx }
        authSessionTests { ctx }
        apiKeyTests { ctx }
        historyAndStatsServiceTests { ctx }
        tautulliBatchAndImporterTests { ctx }
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

            ctx.servers.upsert(sampleServer(id = "srv-2", machineId = "mach-2")).shouldBeRight()
            val bob = sampleUser(id = "user-2", serverId = "srv-2", externalUserId = "ext-99", username = "bob")
            val anotherAlice = sampleUser(id = "user-0", serverId = "srv-2", externalUserId = "ext-01", username = "alice")
            ctx.users.upsert(bob).shouldBeRight()
            ctx.users.upsert(anotherAlice).shouldBeRight()

            ctx.users.findAll().shouldBeRight().map { it.id } shouldContainExactly listOf("user-0", "user-1", "user-2")
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
                    playerMachineId = "player-1",
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
            ctx.history
                .findLatestMergeCandidate("srv-1", "u-1", "mov-1", minStoppedAtMs = 1_500L, playerMachineId = "player-other")
                .shouldBeRight()
                .shouldBeNull()
            val mergeCandidateByPlayer =
                ctx.history
                    .findLatestMergeCandidate("srv-1", "u-1", "mov-1", minStoppedAtMs = 1_500L, playerMachineId = "player-1")
                    .shouldBeRight()
                    .shouldNotBeNull()
            mergeCandidateByPlayer.id shouldBe "hist-1"
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

private fun FunSpecRootScope.playbackHistorySummaryTests(ctxProvider: () -> ContractContext) {
    context("PlaybackHistoryRepository aggregations") {
        test("summarizes and lists playback history per user") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-1", serverId = "srv-1", externalUserId = "1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-2", serverId = "srv-1", externalUserId = "2", username = "bob")).shouldBeRight()

            val h1 = sampleHistory(id = "hist-1", userId = "u-1", ratingKey = "mov-1", startedAtMs = 1_000L, stoppedAtMs = 2_000L)
            val h2 = sampleHistory(id = "hist-2", userId = "u-1", ratingKey = "mov-2", startedAtMs = 3_000L, stoppedAtMs = 4_000L)
            val h3 = sampleHistory(id = "hist-3", userId = "u-2", ratingKey = "mov-3", startedAtMs = 5_000L, stoppedAtMs = 6_000L)
            ctx.history.insert(h1.copy(watchedDurationSec = 300)).shouldBeRight()
            ctx.history.insert(h2.copy(watchedDurationSec = 100)).shouldBeRight()
            ctx.history.insert(h3.copy(watchedDurationSec = 150)).shouldBeRight()

            ctx.history.summarizeByUser(emptySet()).shouldBeRight() shouldBe emptyMap()
            val allSummaries = ctx.history.summarizeByUser().shouldBeRight()
            allSummaries["u-1"] shouldBe UserPlaybackSummary(userId = "u-1", totalPlays = 2L, totalWatchDurationSec = 400L)
            allSummaries["u-2"] shouldBe UserPlaybackSummary(userId = "u-2", totalPlays = 1L, totalWatchDurationSec = 150L)

            val filteredSummaries = ctx.history.summarizeByUser(setOf("u-2", "u-missing")).shouldBeRight()
            filteredSummaries.keys shouldContainExactly listOf("u-2")
            filteredSummaries["u-2"] shouldBe UserPlaybackSummary(userId = "u-2", totalPlays = 1L, totalWatchDurationSec = 150L)

            ctx.history.listByUser("u-1").shouldBeRight().map { it.id } shouldContainExactly listOf("hist-2", "hist-1")
            ctx.history.listRecentByUser("u-1", limit = 1).shouldBeRight().map { it.id } shouldContainExactly listOf("hist-2")
            ctx.history.listRecentByUser("u-1", limit = 0).shouldBeRight() shouldHaveSize 0
        }
    }
}

private fun FunSpecRootScope.playbackHistorySortAndStatsTests(ctxProvider: () -> ContractContext) {
    context("PlaybackHistoryRepository sorting and stats") {
        test("supports userIds filtering, all sortBy/sortDir combinations, date ranges, empty datasets, and listForStats") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-1", serverId = "srv-1", externalUserId = "1", username = "alice")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-2", serverId = "srv-1", externalUserId = "2", username = "bob")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-3", serverId = "srv-1", externalUserId = "3", username = "carol")).shouldBeRight()

            // Empty dataset behavior
            val emptyQuery = ctx.history.query(HistoryFilter()).shouldBeRight()
            emptyQuery.totalCount shouldBe 0L
            emptyQuery.items.shouldBeEmpty()
            ctx.history.listForStats().shouldBeRight().shouldBeEmpty()

            seedSortAndStatsRecords(ctx)

            // userIds empty set returns empty immediately
            val emptyUsersPage = ctx.history.query(HistoryFilter(userIds = emptySet())).shouldBeRight()
            emptyUsersPage.totalCount shouldBe 0L
            emptyUsersPage.items.shouldBeEmpty()
            ctx.history.listForStats(userIds = emptySet()).shouldBeRight().shouldBeEmpty()

            // userIds non-empty filters properly
            val scopedPage = ctx.history.query(HistoryFilter(userIds = setOf("u-1", "u-3"))).shouldBeRight()
            scopedPage.totalCount shouldBe 2L
            scopedPage.items.map { it.id } shouldContainExactly listOf("h-3", "h-1")

            // All sortBy / sortDir combinations
            val expectedOrders =
                listOf(
                    HistorySortField.STOPPED_AT to HistorySortDirection.ASC to listOf("h-1", "h-3", "h-2"),
                    HistorySortField.STOPPED_AT to HistorySortDirection.DESC to listOf("h-2", "h-3", "h-1"),
                    HistorySortField.STARTED_AT to HistorySortDirection.ASC to listOf("h-2", "h-1", "h-3"),
                    HistorySortField.STARTED_AT to HistorySortDirection.DESC to listOf("h-3", "h-1", "h-2"),
                    HistorySortField.WATCHED_DURATION to HistorySortDirection.ASC to listOf("h-3", "h-1", "h-2"),
                    HistorySortField.WATCHED_DURATION to HistorySortDirection.DESC to listOf("h-2", "h-1", "h-3"),
                    HistorySortField.COMPLETION_PERCENT to HistorySortDirection.ASC to listOf("h-2", "h-3", "h-1"),
                    HistorySortField.COMPLETION_PERCENT to HistorySortDirection.DESC to listOf("h-1", "h-3", "h-2"),
                    HistorySortField.TITLE to HistorySortDirection.ASC to listOf("h-2", "h-3", "h-1"),
                    HistorySortField.TITLE to HistorySortDirection.DESC to listOf("h-1", "h-3", "h-2"),
                )
            for ((sortSpec, expectedIds) in expectedOrders) {
                val (sortBy, sortDir) = sortSpec
                val page = ctx.history.query(HistoryFilter(sortBy = sortBy, sortDir = sortDir)).shouldBeRight()
                page.items.map { it.id } shouldContainExactly expectedIds
            }

            // Date range filtering
            val rangedPage = ctx.history.query(HistoryFilter(fromMs = 12_000L, toMs = 18_000L)).shouldBeRight()
            rangedPage.totalCount shouldBe 1L
            rangedPage.items.map { it.id } shouldContainExactly listOf("h-3")

            // listForStats filters & projection
            val allStats = ctx.history.listForStats(serverId = "srv-1").shouldBeRight()
            allStats.map { it.ratingKey } shouldContainExactly listOf("m-1", "e-1", "m-2")
            allStats.first().thumbPath shouldBe "/library/metadata/m-1/thumb"
            allStats.last().hwTranscode shouldBe true
            allStats.last().playerPlatform shouldBe "iOS"

            val filteredStats =
                ctx.history
                    .listForStats(
                        serverId = "srv-1",
                        userIds = setOf("u-2", "u-3"),
                        fromMs = 15_000L,
                        toMs = 19_000L,
                    ).shouldBeRight()
            filteredStats.map { it.ratingKey } shouldContainExactly listOf("e-1")
        }
    }
}

private suspend fun seedSortAndStatsRecords(ctx: ContractContext) {
    val h1 =
        sampleHistory(
            id = "h-1",
            userId = "u-1",
            ratingKey = "m-1",
            title = "Zeta Movie",
            mediaType = "movie",
            sectionKey = "sec-movies",
            streamDecision = "direct_play",
            hwTranscode = false,
            startedAtMs = 5_000L,
            stoppedAtMs = 10_000L,
        ).copy(
            watchedDurationSec = 300,
            completionPercent = 90.0,
            media =
                MediaMetadataRecord(
                    ratingKey = "m-1",
                    mediaType = "movie",
                    title = "Zeta Movie",
                    thumbPath = "/library/metadata/m-1/thumb",
                    librarySectionKey = "sec-movies",
                    durationMs = 100_000L,
                ),
            player = PlayerRecord(playerPlatform = "Web", playerProduct = "Plex Web"),
        )
    val h2 =
        sampleHistory(
            id = "h-2",
            userId = "u-2",
            ratingKey = "m-2",
            title = "alpha Movie",
            mediaType = "movie",
            sectionKey = "sec-movies",
            streamDecision = "transcode",
            hwTranscode = true,
            startedAtMs = 2_000L,
            stoppedAtMs = 20_000L,
        ).copy(
            watchedDurationSec = 600,
            completionPercent = 40.0,
            player = PlayerRecord(playerPlatform = "iOS", playerProduct = "Plex for iOS"),
        )
    val h3 =
        sampleHistory(
            id = "h-3",
            userId = "u-3",
            ratingKey = "e-1",
            title = "Beta Episode",
            grandparentTitle = "Show B",
            mediaType = "episode",
            sectionKey = "sec-tv",
            streamDecision = "direct_stream",
            hwTranscode = false,
            startedAtMs = 8_000L,
            stoppedAtMs = 15_000L,
        ).copy(
            watchedDurationSec = 150,
            completionPercent = 75.0,
            player = PlayerRecord(playerPlatform = "Android", playerProduct = "Plex for Android"),
        )
    ctx.history.insert(h1).shouldBeRight()
    ctx.history.insert(h2).shouldBeRight()
    ctx.history.insert(h3).shouldBeRight()
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

            ctx.servers.upsert(sampleServer(id = "srv-2", machineId = "mach-2")).shouldBeRight()
            val sec2OnSrv2 =
                movies.copy(
                    serverId = "srv-2",
                    sectionKey = "sec-1",
                    title = "Movies 2",
                    itemCount = 50,
                    updatedAtMs = 12_000L,
                )
            ctx.library.upsertSection(sec2OnSrv2).shouldBeRight()

            ctx.library.findSection("srv-1", "sec-1").shouldBeRight()?.itemCount shouldBe 125
            ctx.library.findSection("srv-1", "missing").shouldBeRight().shouldBeNull()
            ctx.library.findSectionsByKey("sec-1").shouldBeRight().map { it.serverId } shouldContainExactly listOf("srv-1", "srv-2")
            ctx.library.findSectionsByKey("missing").shouldBeRight() shouldHaveSize 0
            ctx.library.findAllSections().shouldBeRight().map { it.serverId to it.sectionKey } shouldContainExactly
                listOf("srv-1" to "sec-1", "srv-2" to "sec-1")

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

private fun FunSpecRootScope.authAccountTests(ctxProvider: () -> ContractContext) {
    context("AuthAccountRepository") {
        test("persists accounts, linked media users, and cascades on media user or account deletion") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-auth")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "mu-1", serverId = "srv-auth", externalUserId = "10", username = "alice")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "mu-2", serverId = "srv-auth", externalUserId = "20", username = "bob")).shouldBeRight()

            val admin =
                AuthAccountRecord(
                    id = "acc-1",
                    username = "AdminUser",
                    usernameNormalized = "adminuser",
                    email = "admin@miru.local",
                    passwordHash = $$"$argon2id$v=19$m=1024,t=1,p=1$c2FsdA$aGFzaA",
                    role = "ADMIN",
                    disabled = false,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                )
            val viewer =
                AuthAccountRecord(
                    id = "acc-2",
                    username = "viewer",
                    usernameNormalized = "viewer",
                    passwordHash = $$"$argon2id$v=19$m=1024,t=1,p=1$c2FsdDI$aGFzaDI",
                    role = "USER",
                    disabled = true,
                    createdAtMs = 2_000L,
                    updatedAtMs = 2_000L,
                )

            ctx.authAccounts.insert(admin).shouldBeRight() shouldBe admin
            ctx.authAccounts.insert(viewer).shouldBeRight() shouldBe viewer

            ctx.authAccounts.findById("acc-1").shouldBeRight() shouldBe admin
            ctx.authAccounts.findByNormalizedUsername("adminuser").shouldBeRight() shouldBe admin
            ctx.authAccounts.findAll().shouldBeRight() shouldContainExactly listOf(admin, viewer)
            ctx.authAccounts.countAdmins().shouldBeRight() shouldBe 1L

            ctx.authAccounts.replaceLinkedMediaUserIds("acc-2", setOf("mu-1", "mu-2"), createdAtMs = 2_500L).shouldBeRight()
            ctx.authAccounts.findLinkedMediaUserIds("acc-2").shouldBeRight() shouldBe setOf("mu-1", "mu-2")

            ctx.authAccounts.replaceLinkedMediaUserIds("acc-2", setOf("mu-2"), createdAtMs = 3_000L).shouldBeRight()
            ctx.authAccounts.findLinkedMediaUserIds("acc-2").shouldBeRight() shouldBe setOf("mu-2")

            // Deleting media server cascades to users and auth_account_media_users
            ctx.servers.delete("srv-auth").shouldBeRight() shouldBe true
            ctx.authAccounts.findLinkedMediaUserIds("acc-2").shouldBeRight() shouldBe emptySet()

            ctx.authAccounts.delete("acc-2").shouldBeRight() shouldBe true
            ctx.authAccounts.delete("acc-2").shouldBeRight() shouldBe false
            ctx.authAccounts.findById("acc-2").shouldBeRight().shouldBeNull()
        }

        test("persists OIDC provider and external subject, supports lookup, and updates profile") {
            val ctx = ctxProvider()
            val oidcUser =
                AuthAccountRecord(
                    id = "acc-oidc-1",
                    username = "oidc.user",
                    usernameNormalized = "oidc.user",
                    email = "oidc@miru.local",
                    passwordHash = "",
                    role = "USER",
                    disabled = false,
                    authProvider = "OIDC",
                    externalSubject = "sub-abc-123",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                )
            val localWithNullSubject =
                AuthAccountRecord(
                    id = "acc-local-1",
                    username = "local1",
                    usernameNormalized = "local1",
                    passwordHash = "hash-1",
                    role = "ADMIN",
                    createdAtMs = 1_100L,
                    updatedAtMs = 1_100L,
                )
            val localWithNullSubject2 =
                AuthAccountRecord(
                    id = "acc-local-2",
                    username = "local2",
                    usernameNormalized = "local2",
                    passwordHash = "hash-2",
                    role = "USER",
                    createdAtMs = 1_200L,
                    updatedAtMs = 1_200L,
                )

            ctx.authAccounts.insert(oidcUser).shouldBeRight() shouldBe oidcUser
            // Multiple LOCAL rows with external_subject = NULL must be allowed by partial unique index
            ctx.authAccounts.insert(localWithNullSubject).shouldBeRight()
            ctx.authAccounts.insert(localWithNullSubject2).shouldBeRight()

            ctx.authAccounts.findByProviderAndSubject("OIDC", "sub-abc-123").shouldBeRight() shouldBe oidcUser
            ctx.authAccounts.findByProviderAndSubject("OIDC", "missing").shouldBeRight().shouldBeNull()

            val updated =
                ctx.authAccounts
                    .updateOidcProfile(
                        id = "acc-oidc-1",
                        username = "oidc.renamed",
                        usernameNormalized = "oidc.renamed",
                        email = "renamed@miru.local",
                        role = "ADMIN",
                        updatedAtMs = 5_000L,
                    ).shouldBeRight()
            updated shouldBe
                oidcUser.copy(
                    username = "oidc.renamed",
                    usernameNormalized = "oidc.renamed",
                    email = "renamed@miru.local",
                    role = "ADMIN",
                    updatedAtMs = 5_000L,
                )
            ctx.authAccounts.countAdmins().shouldBeRight() shouldBe 2L
        }
    }
}

private fun FunSpecRootScope.authSessionTests(ctxProvider: () -> ContractContext) {
    context("AuthSessionRepository") {
        test("persists auth sessions, touches last seen, deletes by token/account/expiry, and cascades on account delete") {
            val ctx = ctxProvider()
            val account =
                AuthAccountRecord(
                    id = "acc-sess",
                    username = "sessuser",
                    usernameNormalized = "sessuser",
                    passwordHash = "hash",
                    role = "ADMIN",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                )
            ctx.authAccounts.insert(account).shouldBeRight()

            val s1 =
                AuthSessionRecord(
                    id = "sess-1",
                    accountId = "acc-sess",
                    tokenHash = "tok-hash-1",
                    createdAtMs = 1_000L,
                    expiresAtMs = 5_000L,
                    lastSeenAtMs = 1_000L,
                )
            val s2 =
                AuthSessionRecord(
                    id = "sess-2",
                    accountId = "acc-sess",
                    tokenHash = "tok-hash-2",
                    createdAtMs = 2_000L,
                    expiresAtMs = 20_000L,
                    lastSeenAtMs = 2_000L,
                )
            ctx.authSessions.insert(s1).shouldBeRight() shouldBe s1
            ctx.authSessions.insert(s2).shouldBeRight() shouldBe s2

            ctx.authSessions.findByTokenHash("tok-hash-1").shouldBeRight() shouldBe s1
            ctx.authSessions.touchLastSeen("sess-2", 3_500L).shouldBeRight() shouldBe true
            ctx.authSessions.findByTokenHash("tok-hash-2").shouldBeRight()?.lastSeenAtMs shouldBe 3_500L

            ctx.authSessions.deleteExpired(nowMs = 5_000L).shouldBeRight() shouldBe 1
            ctx.authSessions.findByTokenHash("tok-hash-1").shouldBeRight().shouldBeNull()
            ctx.authSessions.findByTokenHash("tok-hash-2").shouldBeRight().shouldNotBeNull()

            ctx.authSessions.deleteByTokenHash("tok-hash-2").shouldBeRight() shouldBe true
            ctx.authSessions.deleteByTokenHash("tok-hash-2").shouldBeRight() shouldBe false

            val s3 = s2.copy(id = "sess-3", tokenHash = "tok-hash-3")
            ctx.authSessions.insert(s3).shouldBeRight()
            ctx.authSessions.deleteByAccountId("acc-sess").shouldBeRight() shouldBe 1
            ctx.authSessions.findByTokenHash("tok-hash-3").shouldBeRight().shouldBeNull()

            val s4 = s2.copy(id = "sess-4", tokenHash = "tok-hash-4")
            ctx.authSessions.insert(s4).shouldBeRight()
            ctx.authAccounts.delete("acc-sess").shouldBeRight() shouldBe true
            ctx.authSessions.findByTokenHash("tok-hash-4").shouldBeRight().shouldBeNull()
        }
    }
}

private fun FunSpecRootScope.apiKeyTests(ctxProvider: () -> ContractContext) {
    context("ApiKeyRepository") {
        test("persists API keys, updates lastUsed, revokes, deletes, and cascades on account delete") {
            val ctx = ctxProvider()
            val a1 =
                AuthAccountRecord(
                    id = "acc-k1",
                    username = "keyuser1",
                    usernameNormalized = "keyuser1",
                    passwordHash = "hash",
                    role = "ADMIN",
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                )
            val a2 =
                AuthAccountRecord(
                    id = "acc-k2",
                    username = "keyuser2",
                    usernameNormalized = "keyuser2",
                    passwordHash = "hash",
                    role = "USER",
                    createdAtMs = 2_000L,
                    updatedAtMs = 2_000L,
                )
            ctx.authAccounts.insert(a1).shouldBeRight()
            ctx.authAccounts.insert(a2).shouldBeRight()

            val k1 =
                ApiKeyRecord(
                    id = "key-1",
                    accountId = "acc-k1",
                    name = "CLI Key",
                    keyPrefix = "miru_abcdefg",
                    keyHash = "key-hash-1",
                    role = "ADMIN",
                    expiresAtMs = 99_000L,
                    createdAtMs = 1_500L,
                )
            val k2 =
                ApiKeyRecord(
                    id = "key-2",
                    accountId = "acc-k2",
                    name = "Readonly Key",
                    keyPrefix = "miru_1234567",
                    keyHash = "key-hash-2",
                    role = "USER",
                    createdAtMs = 2_500L,
                )

            ctx.apiKeys.insert(k1).shouldBeRight() shouldBe k1
            ctx.apiKeys.insert(k2).shouldBeRight() shouldBe k2

            ctx.apiKeys.findByKeyHash("key-hash-1").shouldBeRight() shouldBe k1
            ctx.apiKeys.findById("key-2").shouldBeRight() shouldBe k2
            ctx.apiKeys.findAll().shouldBeRight() shouldContainExactly listOf(k1, k2)
            ctx.apiKeys.findAll(accountId = "acc-k2").shouldBeRight() shouldContainExactly listOf(k2)

            ctx.apiKeys.updateLastUsed("key-1", 4_000L).shouldBeRight() shouldBe true
            ctx.apiKeys.findById("key-1").shouldBeRight()?.lastUsedAtMs shouldBe 4_000L

            ctx.apiKeys.revoke("key-1", 5_000L).shouldBeRight() shouldBe true
            ctx.apiKeys.findById("key-1").shouldBeRight()?.revokedAtMs shouldBe 5_000L

            ctx.apiKeys.delete("key-1").shouldBeRight() shouldBe true
            ctx.apiKeys.delete("key-1").shouldBeRight() shouldBe false
            ctx.apiKeys.findById("key-1").shouldBeRight().shouldBeNull()

            ctx.authAccounts.delete("acc-k2").shouldBeRight() shouldBe true
            ctx.apiKeys.findById("key-2").shouldBeRight().shouldBeNull()
        }
    }
}

private fun FunSpecRootScope.historyAndStatsServiceTests(ctxProvider: () -> ContractContext) {
    context("HistoryAndStatsService") {
        test("filters, paginates, aggregates stats across UTC dates, and handles empty datasets") {
            val ctx = ctxProvider()
            val nowMs = Instant.parse("2026-04-10T12:00:00Z").toEpochMilli()
            val service =
                HistoryAndStatsService(
                    playbackHistoryRepository = ctx.history,
                    userRepository = ctx.users,
                    clock = { nowMs },
                )
            val admin = MiruPrincipal(accountId = "acc-admin", username = "admin", role = MiruRole.ADMIN)
            val userAlice =
                MiruPrincipal(
                    accountId = "acc-alice",
                    username = "alice-acct",
                    role = MiruRole.USER,
                    linkedMediaUserIds = setOf("u-alice"),
                )

            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-alice", serverId = "srv-1", externalUserId = "1", username = "alice")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-bob", serverId = "srv-1", externalUserId = "2", username = "bob")).shouldBeRight()

            // Empty dataset behavior
            val emptyHistory = service.queryHistory(admin, HistoryQueryParams()).shouldBeRight()
            emptyHistory.totalCount shouldBe 0L
            emptyHistory.totalPages shouldBe 0
            emptyHistory.items.shouldBeEmpty()

            val emptyOverview = service.getOverviewStats(admin, "30d").shouldBeRight()
            emptyOverview.totalPlays shouldBe 0L
            emptyOverview.activeUsers shouldBe 0
            emptyOverview.playsPerDay.shouldBeEmpty()
            emptyOverview.decisionBreakdown.directPlayPercent shouldBe 0.0

            val emptyUserStats = service.getUserStats(userAlice, "u-alice", "all").shouldBeRight()
            emptyUserStats.totalPlays shouldBe 0L
            emptyUserStats.topPlatforms.shouldBeEmpty()
            emptyUserStats.libraryBreakdown.shouldBeEmpty()

            // Seed multi-day playback across 7d, 30d, and >90d
            seedHistoryForServiceTests(ctx)

            // Pagination + filtering + username/thumbUrl enrichment
            val page1 =
                service
                    .queryHistory(
                        admin,
                        HistoryQueryParams(page = 1, pageSize = 2, sortBy = "stopped_at", sortDir = "desc"),
                    ).shouldBeRight()
            page1.totalCount shouldBe 4L
            page1.totalPages shouldBe 2
            page1.items.map { it.id } shouldContainExactly listOf("p-2", "p-1")
            page1.items[0].username shouldBe "alice"
            page1.items[0].thumbUrl shouldBe "/api/v1/media/ep-10/thumb"

            // RBAC scoping for non-admin
            val aliceHistory = service.queryHistory(userAlice, HistoryQueryParams()).shouldBeRight()
            aliceHistory.totalCount shouldBe 2L
            aliceHistory.items.map { it.id } shouldContainExactly listOf("p-2", "p-1")
            service.queryHistory(userAlice, HistoryQueryParams(userId = "u-bob")).shouldBeLeft()

            // Date-range aggregation on overview (7d vs 30d vs all)
            val stats7d = service.getOverviewStats(admin, "7d").shouldBeRight()
            stats7d.totalPlays shouldBe 2L
            stats7d.activeUsers shouldBe 1
            stats7d.playsPerDay.map { it.date to it.playCount } shouldContainExactly
                listOf("2026-04-08" to 1L, "2026-04-09" to 1L)
            stats7d.decisionBreakdown.directPlay shouldBe 1L
            stats7d.decisionBreakdown.transcode shouldBe 1L
            stats7d.decisionBreakdown.hwTranscode shouldBe 1L
            stats7d.decisionBreakdown.directPlayPercent shouldBe 50.0

            val stats30d = service.getOverviewStats(admin, null).shouldBeRight()
            stats30d.range shouldBe "30d"
            stats30d.totalPlays shouldBe 3L
            stats30d.activeUsers shouldBe 2
            stats30d.topMovies.first().ratingKey shouldBe "mov-10"
            stats30d.topMovies.first().playCount shouldBe 2L
            stats30d.topMovies.first().thumbUrl shouldBe "/api/v1/media/mov-10/thumb"
            stats30d.topShows.first().title shouldBe "Severance"

            val statsAll = service.getOverviewStats(admin, "all").shouldBeRight()
            statsAll.totalPlays shouldBe 4L

            // Per-user stats
            val aliceStats = service.getUserStats(userAlice, "u-alice", "30d").shouldBeRight()
            aliceStats.totalPlays shouldBe 2L
            aliceStats.totalWatchDurationSec shouldBe 3_000L
            aliceStats.topPlatforms.map { it.platform } shouldContainExactly listOf("Apple TV", "Web")
            aliceStats.libraryBreakdown.map { it.librarySectionKey } shouldContainExactly listOf("sec-movies", "sec-tv")
        }
    }
}

private suspend fun seedHistoryForServiceTests(ctx: ContractContext) {
    val dayInWindow1 = Instant.parse("2026-04-08T22:00:00Z").toEpochMilli()
    val dayInWindow2 = Instant.parse("2026-04-09T09:00:00Z").toEpochMilli()
    val day20DaysAgo = Instant.parse("2026-03-21T15:00:00Z").toEpochMilli()
    val day120DaysAgo = Instant.parse("2025-12-11T10:00:00Z").toEpochMilli()

    val plays =
        listOf(
            sampleHistory(
                id = "p-1",
                userId = "u-alice",
                ratingKey = "mov-10",
                title = "Dune",
                mediaType = "movie",
                sectionKey = "sec-movies",
                streamDecision = "direct_play",
                startedAtMs = dayInWindow1 - 3_600_000L,
                stoppedAtMs = dayInWindow1,
            ).copy(
                watchedDurationSec = 1_800,
                completionPercent = 95.0,
                media =
                    MediaMetadataRecord(
                        ratingKey = "mov-10",
                        mediaType = "movie",
                        title = "Dune",
                        thumbPath = "/library/metadata/mov-10/thumb",
                        librarySectionKey = "sec-movies",
                        durationMs = 7_200_000L,
                    ),
                player = PlayerRecord(playerPlatform = "Apple TV", playerProduct = "Plex for Apple TV"),
            ),
            sampleHistory(
                id = "p-2",
                userId = "u-alice",
                ratingKey = "ep-10",
                title = "Episode 1",
                grandparentTitle = "Severance",
                mediaType = "episode",
                sectionKey = "sec-tv",
                streamDecision = "transcode",
                hwTranscode = true,
                startedAtMs = dayInWindow2 - 1_800_000L,
                stoppedAtMs = dayInWindow2,
            ).copy(
                watchedDurationSec = 1_200,
                completionPercent = 88.0,
                media =
                    MediaMetadataRecord(
                        ratingKey = "ep-10",
                        mediaType = "episode",
                        title = "Episode 1",
                        grandparentTitle = "Severance",
                        thumbPath = "/library/metadata/ep-10/thumb",
                        librarySectionKey = "sec-tv",
                        durationMs = 3_000_000L,
                    ),
                player = PlayerRecord(playerPlatform = "Web", playerProduct = "Plex Web"),
            ),
            sampleHistory(
                id = "p-3",
                userId = "u-bob",
                ratingKey = "mov-10",
                title = "Dune",
                mediaType = "movie",
                sectionKey = "sec-movies",
                streamDecision = "direct_stream",
                startedAtMs = day20DaysAgo - 2_000_000L,
                stoppedAtMs = day20DaysAgo,
            ).copy(
                watchedDurationSec = 2_000,
                completionPercent = 60.0,
                player = PlayerRecord(playerPlatform = "Android", playerProduct = "Plex for Android"),
            ),
            sampleHistory(
                id = "p-4",
                userId = "u-bob",
                ratingKey = "mov-99",
                title = "Old Movie",
                mediaType = "movie",
                sectionKey = "sec-movies",
                streamDecision = "direct_play",
                startedAtMs = day120DaysAgo - 1_000_000L,
                stoppedAtMs = day120DaysAgo,
            ).copy(
                watchedDurationSec = 900,
                completionPercent = 50.0,
            ),
        )
    plays.forEach { ctx.history.insert(it).shouldBeRight() }
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
    playerMachineId: String? = null,
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
        player = PlayerRecord(playerName = "Web", playerMachineId = playerMachineId, location = "lan"),
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

private fun FunSpecRootScope.tautulliBatchAndImporterTests(ctxProvider: () -> ContractContext) {
    context("Tautulli import batch & parity") {
        test("insertBatchIfAbsentByImportKey and findExistingImportKeys handle chunking and duplicates") {
            val ctx = ctxProvider()
            ctx.servers.upsert(sampleServer(id = "srv-1")).shouldBeRight()
            ctx.users.upsert(sampleUser(id = "u-1", serverId = "srv-1", externalUserId = "1")).shouldBeRight()

            ctx.history.insertBatchIfAbsentByImportKey(emptyList()).shouldBeRight() shouldBe 0
            ctx.history.findExistingImportKeys(emptySet()).shouldBeRight().shouldBeEmpty()

            val batch =
                (1..600).map { idx ->
                    sampleHistory(
                        id = "hist-batch-$idx",
                        userId = "u-1",
                        ratingKey = "rk-$idx",
                        startedAtMs = 1_000L + idx,
                        stoppedAtMs = 2_000L + idx,
                        externalImportKey = "tautulli:$idx",
                    )
                }
            ctx.history.insertBatchIfAbsentByImportKey(batch).shouldBeRight() shouldBe 600
            ctx.history.insertBatchIfAbsentByImportKey(batch.take(50).map { it.copy(id = "dup-${it.id}") }).shouldBeRight() shouldBe 0

            val lookupKeys = (550..650).map { "tautulli:$it" }.toSet() + (1..520).map { "tautulli:$it" }
            val existing = ctx.history.findExistingImportKeys(lookupKeys).shouldBeRight()
            existing shouldHaveSize 571
            existing shouldContainExactlyInAnyOrder ((1..520).map { "tautulli:$it" } + (550..600).map { "tautulli:$it" })
        }

        test("TautulliImporterService imports idempotently on both SQLite and PostgreSQL") {
            val ctx = ctxProvider()
            val tempDir = Files.createTempDirectory("miru-contract-tautulli-")
            try {
                val dbFile = createMinimalContractTautulliDb(tempDir.resolve("tautulli.db"))
                val importer =
                    TautulliImporterService(
                        mediaServerRepository = ctx.servers,
                        userRepository = ctx.users,
                        playbackHistoryRepository = ctx.history,
                    )

                val dryRun = importer.import(dbFile, TautulliImportOptions(dryRun = true)).shouldBeRight()
                dryRun.dryRun shouldBe true
                dryRun.historyImported shouldBe 2
                dryRun.historySkippedDuplicate shouldBe 0
                ctx.servers.findAll().shouldBeRight().shouldBeEmpty()

                val first = importer.import(dbFile, TautulliImportOptions(dryRun = false)).shouldBeRight()
                first.dryRun shouldBe false
                first.usersDiscovered shouldBe 2
                first.usersUpserted shouldBe 2
                first.historyRowsRead shouldBe 3
                first.historyImported shouldBe 2
                first.historySkippedDuplicate shouldBe 0

                val second = importer.import(dbFile, TautulliImportOptions(dryRun = false)).shouldBeRight()
                second.historyImported shouldBe 0
                second.historySkippedDuplicate shouldBe 2
                ctx.history.query(HistoryFilter()).shouldBeRight().totalCount shouldBe 2L
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}

private fun createMinimalContractTautulliDb(path: Path): Path {
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("CREATE TABLE users (user_id INTEGER, username TEXT, friendly_name TEXT)")
            stmt.executeUpdate(
                "CREATE TABLE session_history (id INTEGER PRIMARY KEY, reference_id INTEGER, started INTEGER, stopped INTEGER, " +
                    "rating_key TEXT, user_id INTEGER, user TEXT, paused_counter INTEGER, view_offset INTEGER, media_type TEXT)",
            )
            stmt.executeUpdate(
                "CREATE TABLE session_history_metadata (id INTEGER PRIMARY KEY, title TEXT, duration INTEGER)",
            )
            stmt.executeUpdate(
                "CREATE TABLE session_history_media_info (id INTEGER PRIMARY KEY, transcode_decision TEXT)",
            )
            stmt.executeUpdate("INSERT INTO users (user_id, username, friendly_name) VALUES (1, 'alice', 'Alice')")
            stmt.executeUpdate(
                "INSERT INTO session_history (id, reference_id, started, stopped, rating_key, user_id, user, paused_counter, " +
                    "view_offset, media_type) VALUES (1, 10, 1700000000, 1700001000, 'rk-1', 1, 'alice', 0, 950, 'movie')",
            )
            stmt.executeUpdate(
                "INSERT INTO session_history (id, reference_id, started, stopped, rating_key, user_id, user, paused_counter, " +
                    "view_offset, media_type) VALUES (2, 10, 1700001100, 1700002000, 'rk-1', 1, 'alice', 50, 1800, 'movie')",
            )
            stmt.executeUpdate(
                "INSERT INTO session_history (id, reference_id, started, stopped, rating_key, user_id, user, paused_counter, " +
                    "view_offset, media_type) VALUES (3, 20, 1700003000, 1700003600, 'rk-2', 99, 'guest', 10, 600, 'episode')",
            )
            stmt.executeUpdate("INSERT INTO session_history_metadata (id, title, duration) VALUES (1, 'Movie One', 2000)")
            stmt.executeUpdate("INSERT INTO session_history_metadata (id, title, duration) VALUES (2, 'Movie One', 2000)")
            stmt.executeUpdate("INSERT INTO session_history_metadata (id, title, duration) VALUES (3, 'Ep One', 1200)")
            stmt.executeUpdate("INSERT INTO session_history_media_info (id, transcode_decision) VALUES (1, 'direct play')")
            stmt.executeUpdate("INSERT INTO session_history_media_info (id, transcode_decision) VALUES (2, 'transcode')")
            stmt.executeUpdate("INSERT INTO session_history_media_info (id, transcode_decision) VALUES (3, 'copy')")
        }
    }
    return path
}
