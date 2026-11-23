package app.hononeko.miru.service

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.cleanupOnStartupFailure
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.createSyncServices
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.registerLifecycleHandlers
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

private class InMemorySyncUserRepository : UserRepository {
    private val byId = linkedMapOf<String, UserRecord>()

    override suspend fun upsert(user: UserRecord): Either<MiruError.PersistenceError, UserRecord> {
        byId[user.id] = user
        return user.right()
    }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, UserRecord?> = byId[id].right()

    override suspend fun findByIds(ids: Collection<String>): Either<MiruError.PersistenceError, List<UserRecord>> =
        ids.distinct().mapNotNull { byId[it] }.right()

    override suspend fun findByExternalId(
        serverId: String,
        externalUserId: String,
    ): Either<MiruError.PersistenceError, UserRecord?> =
        byId.values.firstOrNull { it.serverId == serverId && it.externalUserId == externalUserId }.right()

    override suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<UserRecord>> =
        byId.values.filter { it.serverId == serverId }.right()

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<UserRecord>> = byId.values.toList().right()

    override suspend fun updateLastSeen(
        id: String,
        lastSeenAtMs: Long,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> = false.right()
}

private class FakeUserAdapter(
    var nextResult: () -> Either<MiruError, List<MediaUser>>,
) : MediaServerAdapter {
    override val serverType: String = "plex"
    val fetchCalls = AtomicInteger(0)

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> =
        ServerIdentity("mach-1", "Main Plex", "1.41.0").right()

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> = emptyList<ActiveStreamSnapshot>().right()

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> {
        fetchCalls.incrementAndGet()
        return nextResult()
    }

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> = emptyList<LibrarySectionInfo>().right()

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> = 0.right()

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> = LibraryItemsPage(0, 0, emptyList()).right()

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> = MiruError.NotFound("Artwork", path).left()

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> =
        MiruError.NotFound("Media", ratingKey).left()

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> = Unit.right()

    override fun events(): Flow<MediaServerEvent> = emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlexUserSyncServiceTest :
    FunSpec({
        test("syncOnce upserts users, refreshes thumbUrl/displayName/email/username/isAdmin, and preserves omitted fields") {
            val dir = Files.createTempDirectory("miru-user-sync-test-")
            val dbPath = dir.resolve("sync.db").toString()
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            try {
                val serverRepo = ExposedMediaServerRepository(handle)
                val userRepo = ExposedUserRepository(handle)
                serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-1",
                            name = "Main Plex",
                            serverType = "PLEX",
                            machineId = "mach-1",
                            baseUrl = "http://plex.local:32400",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                // Seed existing user that was previously discovered via active session without avatar/email
                userRepo
                    .upsert(
                        UserRecord(
                            id = "existing-yuki-id",
                            serverId = "srv-1",
                            externalUserId = "2002",
                            username = "yuki",
                            displayName = "Old Yuki",
                            email = "old-yuki@example.com",
                            thumbUrl = null,
                            isAdmin = false,
                            lastSeenAtMs = 50_000L,
                            createdAtMs = 10_000L,
                            updatedAtMs = 10_000L,
                        ),
                    ).shouldBeRight()

                val nowMs = AtomicLong(100_000L)
                var currentUsers =
                    listOf(
                        MediaUser(
                            externalId = "99887766",
                            username = "kerrlab",
                            displayName = "Alex Kerr",
                            email = "alex@kerrlab.app",
                            thumbUrl = "https://plex.tv/users/owner/avatar?c=1",
                            isAdmin = true,
                        ),
                        MediaUser(
                            externalId = "2002",
                            username = "yuki_new",
                            displayName = "Yuki Tanaka",
                            email = "yuki@example.com",
                            thumbUrl = "https://plex.tv/users/yuki/avatar?c=2",
                            isAdmin = false,
                        ),
                    )

                val adapter = FakeUserAdapter { currentUsers.right() }
                val service =
                    PlexUserSyncService(
                        adapter = adapter,
                        serverIdProvider = { "srv-1".right() },
                        userRepository = userRepo,
                        clock = { nowMs.get() },
                    )

                service.syncOnce().shouldBeRight() shouldBe 2

                val afterFirst = userRepo.findByServer("srv-1").shouldBeRight()
                afterFirst shouldHaveSize 2

                val yuki = userRepo.findByExternalId("srv-1", "2002").shouldBeRight()!!
                yuki.id shouldBe "existing-yuki-id"
                yuki.username shouldBe "yuki_new"
                yuki.displayName shouldBe "Yuki Tanaka"
                yuki.email shouldBe "yuki@example.com"
                yuki.thumbUrl shouldBe "https://plex.tv/users/yuki/avatar?c=2"
                yuki.lastSeenAtMs shouldBe 50_000L
                yuki.createdAtMs shouldBe 10_000L
                yuki.updatedAtMs shouldBe 100_000L

                val owner = userRepo.findByExternalId("srv-1", "99887766").shouldBeRight()!!
                owner.isAdmin shouldBe true
                owner.thumbUrl shouldBe "https://plex.tv/users/owner/avatar?c=1"

                // Subsequent sync where upstream omits thumbUrl/email/displayName preserves existing non-null fields,
                // while refreshing non-null fields like updated avatar URL or admin flag
                nowMs.set(200_000L)
                currentUsers =
                    listOf(
                        MediaUser(
                            externalId = "99887766",
                            username = "kerrlab",
                            displayName = "",
                            email = null,
                            thumbUrl = "https://plex.tv/users/owner/avatar?c=99",
                            isAdmin = true,
                        ),
                        MediaUser(
                            externalId = "2002",
                            username = "yuki_new",
                            displayName = "",
                            email = null,
                            thumbUrl = null,
                            isAdmin = false,
                        ),
                    )

                service.syncOnce().shouldBeRight() shouldBe 2

                val ownerAfterSecond = userRepo.findByExternalId("srv-1", "99887766").shouldBeRight()!!
                ownerAfterSecond.displayName shouldBe "Alex Kerr"
                ownerAfterSecond.email shouldBe "alex@kerrlab.app"
                ownerAfterSecond.thumbUrl shouldBe "https://plex.tv/users/owner/avatar?c=99"
                ownerAfterSecond.updatedAtMs shouldBe 200_000L

                val yukiAfterSecond = userRepo.findByExternalId("srv-1", "2002").shouldBeRight()!!
                yukiAfterSecond.displayName shouldBe "Yuki Tanaka"
                yukiAfterSecond.email shouldBe "yuki@example.com"
                yukiAfterSecond.thumbUrl shouldBe "https://plex.tv/users/yuki/avatar?c=2"
            } finally {
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("handles unconfigured or failing adapter cleanly and runs periodic 1-hour loop") {
            runTest {
                val userRepo = InMemorySyncUserRepository()
                var configured = false
                val adapter =
                    FakeUserAdapter {
                        if (!configured) {
                            MiruError.UpstreamUnavailable("plex", "Plex server is not configured").left()
                        } else {
                            listOf(
                                MediaUser(
                                    externalId = "42",
                                    username = "alice",
                                    displayName = "Alice",
                                    thumbUrl = "https://plex.tv/users/alice/avatar",
                                    isAdmin = false,
                                ),
                            ).right()
                        }
                    }

                var afterSyncRuns = 0
                val service =
                    PlexUserSyncService(
                        adapter = adapter,
                        serverIdProvider = {
                            if (!configured) {
                                MiruError.UpstreamUnavailable("plex", "Plex server is not configured").left()
                            } else {
                                "srv-1".right()
                            }
                        },
                        userRepository = userRepo,
                        scope = this,
                        onUsersSynced = { afterSyncRuns++ },
                    )

                // Unconfigured syncOnce returns Left cleanly without throwing, and skips the after-sync hook
                service.syncOnce().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                afterSyncRuns shouldBe 0

                // Start loop while unconfigured, then configure and advance 1 hour
                service.start()
                runCurrent()
                userRepo.findByServer("srv-1").shouldBeRight() shouldHaveSize 0

                configured = true
                advanceTimeBy(1.hours)
                runCurrent()

                val synced = userRepo.findByServer("srv-1").shouldBeRight()
                synced shouldHaveSize 1
                synced.single().thumbUrl shouldBe "https://plex.tv/users/alice/avatar"
                afterSyncRuns shouldBe 1

                // Failing adapter on next tick does not crash loop
                adapter.nextResult = { MiruError.UpstreamUnavailable("plex", "timeout").left() }
                advanceTimeBy(1.hours)
                runCurrent()
                adapter.fetchCalls.get() shouldBe 2

                // Unexpected exception during periodic sync is caught without terminating loop,
                // and blank usernames fall back cleanly to existing username or externalId
                adapter.nextResult = { throw IllegalStateException("unexpected boom") }
                advanceTimeBy(1.hours)
                runCurrent()

                adapter.nextResult = {
                    listOf(
                        MediaUser(
                            externalId = "42",
                            username = "   ",
                            displayName = "Alice",
                            isAdmin = false,
                        ),
                        MediaUser(
                            externalId = "99",
                            username = "",
                            displayName = "",
                            isAdmin = false,
                        ),
                    ).right()
                }
                advanceTimeBy(1.hours)
                runCurrent()

                userRepo.findByExternalId("srv-1", "42").shouldBeRight()!!.username shouldBe "alice"
                userRepo.findByExternalId("srv-1", "99").shouldBeRight()!!.username shouldBe "99"

                service.stop()
            }
        }

        test("Application sync wiring creates, starts, stops, and cleans up sync services cleanly") {
            val dir = Files.createTempDirectory("miru-app-sync-wiring-")
            val dbPath = dir.resolve("wiring.db").toString()
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            try {
                val mediaServerRepo = ExposedMediaServerRepository(handle)
                val libraryRepo = ExposedLibraryRepository(handle)
                val userRepo = ExposedUserRepository(handle)
                val runtimeManager =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(baseUrl = "", token = ""),
                        databaseHandle = handle,
                        mediaServerRepository = mediaServerRepo,
                    )

                val syncServices = createSyncServices(runtimeManager, libraryRepo, userRepo)
                testApplication {
                    application {
                        registerLifecycleHandlers(
                            syncServices = syncServices,
                            databaseHandle = handle,
                            AutoCloseable { },
                            null,
                        )
                    }
                    startApplication()
                }

                // Also verify cleanupOnStartupFailure closes resources safely when startup fails
                val handle2 = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("wiring2.db").toString())).shouldBeRight()
                val runtimeManager2 =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(baseUrl = "", token = ""),
                        databaseHandle = handle2,
                        mediaServerRepository = ExposedMediaServerRepository(handle2),
                    )
                val syncServices2 =
                    createSyncServices(
                        runtimeManager2,
                        ExposedLibraryRepository(handle2),
                        ExposedUserRepository(handle2),
                    )
                cleanupOnStartupFailure(
                    syncServices = syncServices2,
                    plexRuntimeManager = runtimeManager2,
                    databaseHandle = handle2,
                    AutoCloseable { throw IllegalStateException("close error") },
                    null,
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
