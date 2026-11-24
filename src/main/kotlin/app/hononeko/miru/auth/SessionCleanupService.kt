package app.hononeko.miru.auth

import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.repository.AuthSessionRepository
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Periodically prunes expired rows from `auth_sessions` on startup and on a fixed interval.
 */
class SessionCleanupService(
    private val sessionRepository: AuthSessionRepository,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("auth-session-cleanup")),
    private val cleanupInterval: Duration = DEFAULT_CLEANUP_INTERVAL,
    private val clock: () -> Long = System::currentTimeMillis,
) : RealtimeLifecycle {
    init {
        require(cleanupInterval.isPositive()) { "cleanupInterval must be positive" }
    }

    private val logger = LoggerFactory.getLogger(SessionCleanupService::class.java)
    private val lifecycleLock = Any()
    private var job: Job? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            job =
                scope.launch(CoroutineName("auth-session-cleanup-loop")) {
                    runLoop()
                }
        }
    }

    override suspend fun stop() {
        val current = synchronized(lifecycleLock) { job.also { job = null } }
        current?.cancelAndJoin()
    }

    suspend fun cleanupOnce(): Either<MiruError.PersistenceError, Int> =
        sessionRepository.deleteExpired(clock())

    private suspend fun runLoop() {
        runSafeCleanup("startup session cleanup")
        while (true) {
            delay(cleanupInterval)
            runSafeCleanup("periodic session cleanup")
        }
    }

    private suspend fun runSafeCleanup(label: String) {
        try {
            cleanupOnce().fold(
                ifLeft = { error ->
                    logger.warn("Auth {} failed: {}", label, error.message)
                },
                ifRight = { deleted ->
                    if (deleted > 0) {
                        logger.info("Removed {} expired auth session(s) during {}", deleted, label)
                    } else {
                        logger.debug("No expired auth sessions found during {}", label)
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Unexpected error during auth {}: {}", label, e.message, e)
        }
    }

    companion object {
        val DEFAULT_CLEANUP_INTERVAL: Duration = 1.hours
    }
}
