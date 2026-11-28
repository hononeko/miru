package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import arrow.core.Either

/**
 * Serves `webhook_targets` lookups from an in-memory snapshot; the webhook runner reads the enabled targets on every
 * live session event. Every write goes through [delegate] and then drops the snapshot.
 *
 * Each method is spelled out (no `by` delegation) so that a write added to [WebhookTargetRepository] fails to compile
 * here instead of silently skipping invalidation.
 */
class CachingWebhookTargetRepository(
    private val delegate: WebhookTargetRepository,
) : WebhookTargetRepository {
    private val snapshot = ReferenceTableSnapshot { delegate.findAll() }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>> = snapshot.rows()

    // findAll and findEnabled share one ORDER BY, so filtering keeps the order findEnabled would return.
    override suspend fun findEnabled(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>> =
        snapshot.rows().map { targets -> targets.filter { it.enabled } }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, WebhookTargetRecord?> =
        snapshot.rows().map { targets -> targets.firstOrNull { it.id == id } }

    override suspend fun insert(record: WebhookTargetRecord): Either<MiruError.PersistenceError, WebhookTargetRecord> =
        invalidatingAfter { delegate.insert(record) }

    override suspend fun update(record: WebhookTargetRecord): Either<MiruError.PersistenceError, Boolean> =
        invalidatingAfter { delegate.update(record) }

    override suspend fun deleteById(id: String): Either<MiruError.PersistenceError, Boolean> = invalidatingAfter { delegate.deleteById(id) }

    // Also on failure: a write that errored may still have committed before the error surfaced.
    private suspend fun <T> invalidatingAfter(write: suspend () -> T): T =
        try {
            write()
        } finally {
            snapshot.invalidate()
        }

    override fun toString(): String = "CachingWebhookTargetRepository($snapshot)"
}
