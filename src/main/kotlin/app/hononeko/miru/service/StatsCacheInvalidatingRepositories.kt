package app.hononeko.miru.service

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.AvatarMutation
import app.hononeko.miru.persistence.model.DisplayNameMutation
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either

/*
 * Repository decorators that invalidate the stats cache after every write that changes a stats payload: plays
 * recorded, merged, imported, or removed with their server, and the names, links and profiles that label the
 * viewers in them. Reads and the other writes pass straight through.
 */

/** Plays: finalized sessions, resume merges and Tautulli imports. */
class StatsInvalidatingPlaybackHistoryRepository(
    private val delegate: PlaybackHistoryRepository,
    private val invalidate: () -> Unit,
) : PlaybackHistoryRepository by delegate {
    override suspend fun insert(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, PlaybackHistoryRecord> =
        invalidatingAfter(invalidate) { delegate.insert(record) }

    override suspend fun insertIfAbsentByImportKey(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        invalidatingAfter(invalidate) { delegate.insertIfAbsentByImportKey(record) }

    override suspend fun insertBatchIfAbsentByImportKey(records: List<PlaybackHistoryRecord>): Either<MiruError.PersistenceError, Int> =
        invalidatingAfter(invalidate) { delegate.insertBatchIfAbsentByImportKey(records) }

    override suspend fun updateMergedSession(record: PlaybackHistoryRecord): Either<MiruError.PersistenceError, Boolean> =
        invalidatingAfter(invalidate) { delegate.updateMergedSession(record) }
}

/** Media users' names, written by session tracking, user sync and imports. Last-seen times are not shown in stats. */
class StatsInvalidatingUserRepository(
    private val delegate: UserRepository,
    private val invalidate: () -> Unit,
) : UserRepository by delegate {
    override suspend fun upsert(user: UserRecord): Either<MiruError.PersistenceError, UserRecord> =
        invalidatingAfter(invalidate) { delegate.upsert(user) }
}

/** Servers: removing one deletes its plays and users; the others rename or disconnect it. */
class StatsInvalidatingMediaServerRepository(
    private val delegate: MediaServerRepository,
    private val invalidate: () -> Unit,
) : MediaServerRepository by delegate {
    override suspend fun upsert(server: MediaServerRecord): Either<MiruError.PersistenceError, MediaServerRecord> =
        invalidatingAfter(invalidate) { delegate.upsert(server) }

    override suspend fun upsertExclusiveStoredCredentials(
        server: MediaServerRecord,
    ): Either<MiruError.PersistenceError, MediaServerRecord> =
        invalidatingAfter(invalidate) { delegate.upsertExclusiveStoredCredentials(server) }

    override suspend fun updateCredentials(
        id: String,
        name: String,
        baseUrl: String,
        encryptedToken: String?,
        clientIdentifier: String?,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, MediaServerRecord?> =
        invalidatingAfter(invalidate) {
            delegate.updateCredentials(id, name, baseUrl, encryptedToken, clientIdentifier, updatedAtMs)
        }

    override suspend fun clearCredentials(
        id: String,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, MediaServerRecord?> =
        invalidatingAfter(invalidate) { delegate.clearCredentials(id, updatedAtMs) }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        invalidatingAfter(invalidate) { delegate.delete(id) }
}

/**
 * Accounts group their linked media users into one viewer and name it, so stats change with the links and the
 * account's display name. Sign-in, password and role writes do not change them.
 */
class StatsInvalidatingAuthAccountRepository(
    private val delegate: AuthAccountRepository,
    private val invalidate: () -> Unit,
) : AuthAccountRepository by delegate {
    override suspend fun insert(account: AuthAccountRecord): Either<MiruError.PersistenceError, AuthAccountRecord> =
        invalidatingAfter(invalidate) { delegate.insert(account) }

    override suspend fun delete(id: String): Either<MiruError.PersistenceError, Boolean> =
        invalidatingAfter(invalidate) { delegate.delete(id) }

    override suspend fun replaceLinkedMediaUserIds(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
        linkMode: String?,
    ): Either<MiruError.PersistenceError, Unit> =
        invalidatingAfter(invalidate) { delegate.replaceLinkedMediaUserIds(accountId, mediaUserIds, createdAtMs, linkMode) }

    override suspend fun replaceLinkedMediaUserIdsIfAuto(
        accountId: String,
        mediaUserIds: Set<String>,
        createdAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> =
        invalidatingAfter(invalidate) { delegate.replaceLinkedMediaUserIdsIfAuto(accountId, mediaUserIds, createdAtMs) }

    override suspend fun updateProfile(
        id: String,
        displayName: DisplayNameMutation,
        avatar: AvatarMutation,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, AuthAccountRecord?> =
        invalidatingAfter(invalidate) { delegate.updateProfile(id, displayName, avatar, updatedAtMs) }
}

fun PlaybackHistoryRepository.invalidatingStats(cache: StatsResultCache): PlaybackHistoryRepository =
    StatsInvalidatingPlaybackHistoryRepository(this, cache::invalidate)

fun UserRepository.invalidatingStats(cache: StatsResultCache): UserRepository = StatsInvalidatingUserRepository(this, cache::invalidate)

fun MediaServerRepository.invalidatingStats(cache: StatsResultCache): MediaServerRepository =
    StatsInvalidatingMediaServerRepository(this, cache::invalidate)

fun AuthAccountRepository.invalidatingStats(cache: StatsResultCache): AuthAccountRepository =
    StatsInvalidatingAuthAccountRepository(this, cache::invalidate)

// After the write, never before: a stats read starting in between would cache the old rows under the new
// generation. On failure too, since a write can commit before its error surfaces.
private inline fun <T> invalidatingAfter(
    invalidate: () -> Unit,
    write: () -> T,
): T =
    try {
        write()
    } finally {
        invalidate()
    }
