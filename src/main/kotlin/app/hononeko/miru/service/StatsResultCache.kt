package app.hononeko.miru.service

import app.hononeko.miru.MiruJson
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.ClientStatsResponse
import app.hononeko.miru.http.HeatmapStatsResponse
import app.hononeko.miru.http.OverviewStatsResponse
import app.hononeko.miru.http.UserStatsResponse
import app.hononeko.miru.http.WrappedStatsResponse
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
private const val DEFAULT_MAX_BYTES = 16L * BYTES_PER_MEGABYTE
private const val DEFAULT_TTL_MS = 5L * 60L * 1000L
private const val DEFAULT_MAX_ENTRIES = 512
private const val MAX_ENTRY_SHARE_DIVISOR = 4L
private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f

// Strings dominate the payloads; object headers and references roughly double their JSON length on the heap
private const val HEAP_BYTES_PER_JSON_CHAR = 2L

/** A stats endpoint whose payloads [StatsResultCache] holds: its metrics label and its response type. */
sealed class StatsCacheEndpoint<T : Any>(
    val label: String,
    private val type: Class<T>,
    private val serializer: KSerializer<T>,
) {
    internal fun cast(value: Any): T = type.cast(value)

    internal fun encodedLength(value: Any): Long = MiruJson.encodeToString(serializer, cast(value)).length.toLong()

    override fun toString(): String = label

    object Overview : StatsCacheEndpoint<OverviewStatsResponse>(
        "overview",
        OverviewStatsResponse::class.java,
        OverviewStatsResponse.serializer(),
    )

    object User : StatsCacheEndpoint<UserStatsResponse>("user", UserStatsResponse::class.java, UserStatsResponse.serializer())

    object Heatmap : StatsCacheEndpoint<HeatmapStatsResponse>(
        "heatmap",
        HeatmapStatsResponse::class.java,
        HeatmapStatsResponse.serializer(),
    )

    object Clients : StatsCacheEndpoint<ClientStatsResponse>(
        "clients",
        ClientStatsResponse::class.java,
        ClientStatsResponse.serializer(),
    )

    object Wrapped : StatsCacheEndpoint<WrappedStatsResponse>(
        "wrapped",
        WrappedStatsResponse::class.java,
        WrappedStatsResponse.serializer(),
    )
}

/**
 * Everything a cached stats payload depends on besides the database, which [generation] stands for.
 *
 * [scopedUserIds] is the scope the caller's access resolved to, never the raw request, so a restricted caller
 * cannot reach a payload computed for an admin (null: every user) or for other viewers.
 */
data class StatsCacheKey<T : Any>(
    val generation: Long,
    val endpoint: StatsCacheEndpoint<T>,
    val serverId: String?,
    val scopedUserIds: Set<String>?,
    /** The viewer the payload names (user and wrapped stats); null when it names nobody. */
    val viewerId: String?,
    /** The normalized range, or the year of wrapped stats. */
    val period: String,
    val metric: AnalyticsMetric,
    /** The UTC day a rolling window ends on, so it moves on at midnight without a write; null for fixed periods. */
    val utcDay: Long?,
)

fun interface StatsCacheListener {
    /** [hit] is true when the payload came without a scan of its own, including by joining one already running. */
    fun onLookup(
        endpoint: StatsCacheEndpoint<*>,
        hit: Boolean,
    )
}

/**
 * Thread-safe in-memory LRU cache of stats payloads, bounded by entry count and estimated bytes.
 *
 * Entries are keyed by a generation that [invalidate] bumps after every write that changes what stats render, so
 * a payload computed before a write is never served after it. Concurrent misses for one key share a single
 * computation. [ttlMs] bounds how far a rolling window drifts within its day and how long writes from outside
 * this process (the `import` command, a manual database edit) take to show.
 */
class StatsResultCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Long = maxBytes / MAX_ENTRY_SHARE_DIVISOR,
    private val weigher: (StatsCacheEndpoint<*>, Any) -> Long = ::estimatedRetainedBytes,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        require(ttlMs > 0) { "ttlMs must be > 0" }
        require(maxEntries >= 0) { "maxEntries must be >= 0" }
        require(maxEntryBytes >= 0) { "maxEntryBytes must be >= 0" }
    }

    private class Entry(
        val value: Any,
        val weight: Long,
        val expiresAtMs: Long,
    )

    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val entries = LinkedHashMap<StatsCacheKey<*>, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentBytes = 0L
    private val inFlight = ConcurrentHashMap<StatsCacheKey<*>, CompletableDeferred<Either<MiruError, Any>>>()

    @Volatile
    var listener: StatsCacheListener? = null

    val enabled: Boolean
        get() = maxBytes > 0 && maxEntries > 0

    fun currentGeneration(): Long = generation.get()

    /** Drops every payload. Call it after a write commits, never before, or a read in between caches the old rows. */
    fun invalidate() {
        synchronized(lock) {
            generation.incrementAndGet()
            entries.clear()
            currentBytes = 0L
        }
    }

    fun entryCount(): Int = synchronized(lock) { entries.size }

    fun sizeBytes(): Long = synchronized(lock) { currentBytes }

    /**
     * Returns the payload cached for [key] at [nowMs], or runs [compute] once for every caller waiting on [key]
     * and caches a successful result. Errors are shared with the callers waiting at the time, never cached.
     */
    suspend fun <T : Any> getOrCompute(
        key: StatsCacheKey<T>,
        nowMs: Long,
        compute: suspend () -> Either<MiruError, T>,
    ): Either<MiruError, T> {
        if (!enabled) return compute()
        while (true) {
            lookup(key, nowMs)?.let { cached ->
                listener?.onLookup(key.endpoint, true)
                return Either.Right(cached)
            }
            val pending = CompletableDeferred<Either<MiruError, Any>>()
            val running = inFlight.putIfAbsent(key, pending)
            if (running == null) {
                return lead(key, nowMs, pending, compute)
            }
            val shared =
                try {
                    running.await()
                } catch (e: CancellationException) {
                    // The caller running it went away; take over unless this caller is going away too
                    currentCoroutineContext().ensureActive()
                    continue
                }
            listener?.onLookup(key.endpoint, true)
            return shared.map { key.endpoint.cast(it) }
        }
    }

    private suspend fun <T : Any> lead(
        key: StatsCacheKey<T>,
        nowMs: Long,
        pending: CompletableDeferred<Either<MiruError, Any>>,
        compute: suspend () -> Either<MiruError, T>,
    ): Either<MiruError, T> {
        var outcome: Either<MiruError, T>? = null
        try {
            // A previous run may have stored it between this caller's lookup and taking the lead
            val stored = lookup(key, nowMs)
            if (stored != null) {
                listener?.onLookup(key.endpoint, true)
                return Either.Right(stored).also { outcome = it }
            }
            listener?.onLookup(key.endpoint, false)
            val result = compute()
            result.onRight { value -> store(key, value, nowMs) }
            outcome = result
            return result
        } finally {
            // Stored before the slot is released, so a caller arriving now either waits here or finds the entry.
            // When the run threw or was cancelled, the waiting callers run it themselves instead of sharing that.
            val finished = outcome
            if (finished != null) pending.complete(finished) else pending.cancel()
            inFlight.remove(key, pending)
        }
    }

    private fun <T : Any> lookup(
        key: StatsCacheKey<T>,
        nowMs: Long,
    ): T? =
        synchronized(lock) {
            val entry = entries[key] ?: return null
            if (nowMs >= entry.expiresAtMs) {
                entries.remove(key)
                currentBytes -= entry.weight
                return null
            }
            key.endpoint.cast(entry.value)
        }

    private fun store(
        key: StatsCacheKey<*>,
        value: Any,
        nowMs: Long,
    ) {
        val weight = weigher(key.endpoint, value)
        if (weight > maxEntryBytes || weight > maxBytes) return
        synchronized(lock) {
            // A write landed while this was computed, so the payload may predate it
            if (key.generation != generation.get()) return
            entries.remove(key)?.let { previous -> currentBytes -= previous.weight }
            evictExpiredLocked(nowMs)
            val iterator = entries.values.iterator()
            while (iterator.hasNext() && (entries.size >= maxEntries || currentBytes + weight > maxBytes)) {
                val eldest = iterator.next()
                iterator.remove()
                currentBytes -= eldest.weight
            }
            entries[key] = Entry(value = value, weight = weight, expiresAtMs = nowMs + ttlMs)
            currentBytes += weight
        }
    }

    private fun evictExpiredLocked(nowMs: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs >= entry.expiresAtMs) {
                iterator.remove()
                currentBytes -= entry.weight
            }
        }
    }

    companion object {
        fun megabytes(value: Int): Long = value.toLong() * BYTES_PER_MEGABYTE
    }
}

/** Approximate heap a payload holds, from its JSON length. */
internal fun estimatedRetainedBytes(
    endpoint: StatsCacheEndpoint<*>,
    value: Any,
): Long = endpoint.encodedLength(value) * HEAP_BYTES_PER_JSON_CHAR
