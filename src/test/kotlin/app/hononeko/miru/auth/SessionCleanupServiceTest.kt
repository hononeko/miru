package app.hononeko.miru.auth

import app.hononeko.miru.adapter.plex.PlexRuntimeManager
import app.hononeko.miru.cleanupOnStartupFailure
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.createSyncServices
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.AuthSessionRecord
import app.hononeko.miru.persistence.model.SessionAuthContext
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import app.hononeko.miru.registerLifecycleHandlers
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

private class FakeAuthSessionRepository(
    var nextDeleteExpiredResult: (Long) -> Either<MiruError.PersistenceError, Int>,
) : AuthSessionRepository {
    val deleteExpiredCalls = mutableListOf<Long>()

    override suspend fun insert(session: AuthSessionRecord): Either<MiruError.PersistenceError, AuthSessionRecord> =
        session.right()

    override suspend fun findByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, AuthSessionRecord?> =
        null.right()

    override suspend fun findSessionAuthContextByTokenHash(
        tokenHash: String,
    ): Either<MiruError.PersistenceError, SessionAuthContext?> = null.right()

    override suspend fun touchLastSeen(
        id: String,
        lastSeenAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> = false.right()

    override suspend fun deleteByTokenHash(tokenHash: String): Either<MiruError.PersistenceError, Boolean> =
        false.right()

    override suspend fun deleteByAccountId(accountId: String): Either<MiruError.PersistenceError, Int> = 0.right()

    override suspend fun deleteExpired(nowMs: Long): Either<MiruError.PersistenceError, Int> {
        deleteExpiredCalls += nowMs
        return nextDeleteExpiredResult(nowMs)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCleanupServiceTest :
    FunSpec({
        test("runs startup and periodic hourly sweeps, handles Left and exceptions without crashing, and stops cleanly") {
            runTest {
                val nowMs = AtomicLong(10_000L)
                val step = AtomicInteger(0)
                val repo =
                    FakeAuthSessionRepository {
                        when (step.getAndIncrement()) {
                            0 -> 3.right()
                            1 -> 0.right()
                            2 -> MiruError.PersistenceError("deleteExpired", "temporary db busy").left()
                            3 -> throw IllegalStateException("unexpected driver failure")
                            else -> 2.right()
                        }
                    }

                val cleanedTimestamps = mutableListOf<Long>()
                val service =
                    SessionCleanupService(
                        sessionRepository = repo,
                        scope = this,
                        clock = { nowMs.get() },
                        onExpiredCleaned = { ts -> cleanedTimestamps += ts },
                    )

                // Idempotent start() runs startup sweep immediately
                service.start()
                service.start()
                runCurrent()
                repo.deleteExpiredCalls shouldBe listOf(10_000L)

                // Tick 1 (0 deleted)
                nowMs.addAndGet(3_600_000L)
                advanceTimeBy(1.hours)
                runCurrent()
                repo.deleteExpiredCalls shouldBe listOf(10_000L, 3_610_000L)

                // Tick 2 (PersistenceError Left -> loop stays alive)
                nowMs.addAndGet(3_600_000L)
                advanceTimeBy(1.hours)
                runCurrent()
                repo.deleteExpiredCalls shouldBe listOf(10_000L, 3_610_000L, 7_210_000L)

                // Tick 3 (unexpected Exception -> loop stays alive)
                nowMs.addAndGet(3_600_000L)
                advanceTimeBy(1.hours)
                runCurrent()
                repo.deleteExpiredCalls shouldBe listOf(10_000L, 3_610_000L, 7_210_000L, 10_810_000L)

                // Tick 4 (recovers and deletes 2)
                nowMs.addAndGet(3_600_000L)
                advanceTimeBy(1.hours)
                runCurrent()
                repo.deleteExpiredCalls shouldBe listOf(10_000L, 3_610_000L, 7_210_000L, 10_810_000L, 14_410_000L)
                cleanedTimestamps shouldBe listOf(10_000L, 3_610_000L, 14_410_000L)

                service.stop()

                // Advancing time after stop triggers no further sweeps
                advanceTimeBy(2.hours)
                runCurrent()
                repo.deleteExpiredCalls.size shouldBe 5

                // cleanupOnce returns typed Left on error
                repo.nextDeleteExpiredResult = { MiruError.PersistenceError("deleteExpired", "boom").left() }
                service.cleanupOnce().shouldBeLeft().shouldBeInstanceOf<MiruError.PersistenceError>()
            }
        }

        test("Application sync wiring starts, stops, and cleans up SessionCleanupService cleanly") {
            val dir = Files.createTempDirectory("miru-session-cleanup-wiring-")
            val dbPath = dir.resolve("cleanup.db").toString()
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            try {
                val runtimeManager =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(baseUrl = "", token = ""),
                        databaseHandle = handle,
                        mediaServerRepository = ExposedMediaServerRepository(handle),
                    )
                val syncServices =
                    createSyncServices(
                        plexRuntimeManager = runtimeManager,
                        libraryRepository = ExposedLibraryRepository(handle),
                        userRepository = ExposedUserRepository(handle),
                        sessionRepository = ExposedAuthSessionRepository(handle),
                    )
                testApplication {
                    application {
                        registerLifecycleHandlers(
                            syncServices = syncServices,
                            databaseHandle = handle,
                        )
                    }
                    startApplication()
                }

                val handle2 = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("cleanup2.db").toString())).shouldBeRight()
                val runtimeManager2 =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(baseUrl = "", token = ""),
                        databaseHandle = handle2,
                        mediaServerRepository = ExposedMediaServerRepository(handle2),
                    )
                val syncServices2 =
                    createSyncServices(
                        plexRuntimeManager = runtimeManager2,
                        libraryRepository = ExposedLibraryRepository(handle2),
                        userRepository = ExposedUserRepository(handle2),
                        sessionRepository = ExposedAuthSessionRepository(handle2),
                    )
                cleanupOnStartupFailure(
                    syncServices = syncServices2,
                    plexRuntimeManager = runtimeManager2,
                    databaseHandle = handle2,
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
