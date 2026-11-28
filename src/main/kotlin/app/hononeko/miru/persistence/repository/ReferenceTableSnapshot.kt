package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The full contents of a small reference table, loaded on first use and kept until [invalidate].
 *
 * Callers invalidate after every write to the table. Each load is tagged with the generation it started in and
 * only counts while that is still the current generation, so a load that raced a write can never be served after
 * the write returns. Rows may carry secrets (encrypted tokens), so [toString] never prints them.
 */
class ReferenceTableSnapshot<T>(
    private val load: suspend () -> Either<MiruError.PersistenceError, List<T>>,
) {
    private class Loaded<T>(
        val generation: Long,
        val rows: List<T>,
    )

    private val generation = AtomicLong()
    private val loaded = AtomicReference<Loaded<T>?>(null)

    // Single-flight: a burst of reads right after a write makes one query, not one each.
    private val loadMutex = Mutex()

    /** Bumped by every [invalidate]; lets readers that derive data from these rows tell when they changed. */
    fun generation(): Long = generation.get()

    suspend fun rows(): Either<MiruError.PersistenceError, List<T>> {
        current()?.let { return Either.Right(it) }
        return loadMutex.withLock {
            current()?.let { return@withLock Either.Right(it) }
            val startedIn = generation.get()
            load().map { rows ->
                rows.toList().also { snapshot -> loaded.set(Loaded(startedIn, snapshot)) }
            }
        }
    }

    fun invalidate() {
        generation.incrementAndGet()
        loaded.set(null)
    }

    private fun current(): List<T>? = loaded.get()?.takeIf { it.generation == generation.get() }?.rows

    override fun toString(): String = "ReferenceTableSnapshot(loaded=${current()?.size ?: "no"})"
}
