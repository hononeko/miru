package app.hononeko.miru.service

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Periodically syncs media server users (including avatar `thumbUrl`s and profile metadata)
 * from [MediaServerAdapter.fetchUsers] into [UserRepository].
 */
class PlexUserSyncService(
    private val adapter: MediaServerAdapter,
    private val serverIdProvider: suspend () -> Either<MiruError, String>,
    private val userRepository: UserRepository,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("plex-user-sync")),
    private val syncInterval: Duration = DEFAULT_SYNC_INTERVAL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : RealtimeLifecycle {
    init {
        require(syncInterval.isPositive()) { "syncInterval must be positive" }
    }

    private val logger = LoggerFactory.getLogger(PlexUserSyncService::class.java)
    private val syncMutex = Mutex()
    private val lifecycleLock = Any()
    private var job: Job? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            job =
                scope.launch(CoroutineName("plex-user-sync-loop")) {
                    runLoop()
                }
        }
    }

    override suspend fun stop() {
        val current = synchronized(lifecycleLock) { job.also { job = null } }
        current?.cancelAndJoin()
    }

    suspend fun syncOnce(): Either<MiruError, Int> =
        syncMutex.withLock {
            syncOnceLocked()
        }

    private suspend fun syncOnceLocked(): Either<MiruError, Int> =
        either {
            val serverId = serverIdProvider().bind()
            val mediaUsers = adapter.fetchUsers().bind()
            val existingByExternalId =
                userRepository
                    .findByServer(serverId)
                    .bind()
                    .associateByTo(linkedMapOf()) { it.externalUserId }

            val nowMs = clock()
            var syncedCount = 0
            for (mediaUser in mediaUsers) {
                val externalId = mediaUser.externalId.trim()
                if (externalId.isEmpty()) continue

                val existing = existingByExternalId[externalId]
                val merged =
                    mergeUserRecord(
                        serverId = serverId,
                        externalId = externalId,
                        mediaUser = mediaUser,
                        existing = existing,
                        nowMs = nowMs,
                    )
                val saved = userRepository.upsert(merged).bind()
                existingByExternalId[externalId] = saved
                syncedCount++
            }
            syncedCount
        }

    private fun mergeUserRecord(
        serverId: String,
        externalId: String,
        mediaUser: MediaUser,
        existing: UserRecord?,
        nowMs: Long,
    ): UserRecord {
        val username =
            mediaUser.username.trim().takeIf { it.isNotEmpty() }
                ?: existing?.username
                ?: externalId
        val displayName =
            mediaUser.displayName.trim().takeIf { it.isNotEmpty() }
                ?: existing?.displayName
        val email =
            mediaUser.email?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing?.email
        val thumbUrl =
            mediaUser.thumbUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing?.thumbUrl

        return UserRecord(
            id = existing?.id ?: idGenerator(),
            serverId = serverId,
            externalUserId = externalId,
            username = username,
            displayName = displayName,
            email = email,
            thumbUrl = thumbUrl,
            isAdmin = mediaUser.isAdmin,
            lastSeenAtMs = existing?.lastSeenAtMs,
            createdAtMs = existing?.createdAtMs ?: nowMs,
            updatedAtMs = nowMs,
        )
    }

    private suspend fun runLoop() {
        runSafeSync("initial user sync")
        while (true) {
            delay(syncInterval)
            runSafeSync("periodic user sync")
        }
    }

    private suspend fun runSafeSync(label: String) {
        try {
            syncOnce().fold(
                ifLeft = { error ->
                    if (error is MiruError.UpstreamUnavailable && error.message.contains("not configured")) {
                        logger.debug("Skipping {}: {}", label, error.message)
                    } else {
                        logger.warn("Plex {} failed: {}", label, error.message)
                    }
                },
                ifRight = { count ->
                    logger.debug("Plex {} completed {}", label, kv("users", count))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error during Plex {}", label, e)
        }
    }

    companion object {
        val DEFAULT_SYNC_INTERVAL: Duration = 1.hours
    }
}
