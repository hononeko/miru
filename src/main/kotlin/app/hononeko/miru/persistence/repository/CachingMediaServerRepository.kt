package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.MediaServerRecord
import arrow.core.Either

/**
 * Serves `media_servers` lookups from an in-memory snapshot; nearly every history, library, stream and user request
 * reads this 1–5 row table. Every write goes through [delegate] and then drops the snapshot.
 *
 * Each method is spelled out (no `by` delegation) so that a write added to [MediaServerRepository] fails to compile
 * here instead of silently skipping invalidation. Only this process's writes are seen: a write from another process
 * against the same database (for example the offline `import tautulli` CLI) shows up after the next write here or a
 * restart.
 */
class CachingMediaServerRepository(
    private val delegate: MediaServerRepository,
) : MediaServerRepository {
    private val snapshot = ReferenceTableSnapshot { delegate.findAll() }

    /** Changes after every write; see [ReferenceTableSnapshot.generation]. */
    fun snapshotGeneration(): Long = snapshot.generation()

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<MediaServerRecord>> = snapshot.rows()

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, MediaServerRecord?> =
        snapshot.rows().map { servers -> servers.firstOrNull { it.id == id } }

    override suspend fun findByMachineId(machineId: String): Either<MiruError.PersistenceError, MediaServerRecord?> =
        snapshot.rows().map { servers -> servers.firstOrNull { it.machineId == machineId } }

    override suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord> =
        invalidatingAfter { delegate.upsert(server) }

    override suspend fun upsertExclusiveStoredCredentials(
        server: MediaServerRecord,
    ): Either<MiruError.PersistenceError, MediaServerRecord> = invalidatingAfter { delegate.upsertExclusiveStoredCredentials(server) }

    override suspend fun updateCredentials(
        id: String,
        name: String,
        baseUrl: String,
        encryptedToken: String?,
        clientIdentifier: String?,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, MediaServerRecord?> =
        invalidatingAfter { delegate.updateCredentials(id, name, baseUrl, encryptedToken, clientIdentifier, updatedAtMs) }

    override suspend fun clearCredentials(
        id: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, MediaServerRecord?> = invalidatingAfter { delegate.clearCredentials(id, updatedAtMs) }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> = invalidatingAfter { delegate.delete(id) }

    // Counts of other tables, which change on every play; not reference data.
    override suspend fun countServerData(id: String): Either<MiruError.PersistenceError, ServerDataCounts> = delegate.countServerData(id)

    override suspend fun countAllServerData(): Either<MiruError.PersistenceError, Map<String, ServerDataCounts>> =
        delegate.countAllServerData()

    // Also on failure: a write that errored may still have committed before the error surfaced.
    private suspend fun <T> invalidatingAfter(write: suspend () -> T): T =
        try {
            write()
        } finally {
            snapshot.invalidate()
        }

    override fun toString(): String = "CachingMediaServerRepository($snapshot)"
}
