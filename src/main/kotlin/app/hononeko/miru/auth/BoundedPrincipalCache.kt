package app.hononeko.miru.auth

enum class CachedCredentialKind {
    SESSION,
    API_KEY,
}

private data class CredentialCacheKey(
    val kind: CachedCredentialKind,
    val hash: String,
) {
    override fun toString(): String = "CredentialCacheKey(kind=$kind, hash=***)"
}

data class CachedAuthEntry(
    val kind: CachedCredentialKind,
    val credentialHash: String,
    val credentialId: String,
    val accountId: String,
    val principal: MiruPrincipal,
    val credentialExpiresAtMs: Long?,
    val cachedAtMs: Long,
    val cacheExpiresAtMs: Long,
    val lastActivityAtMs: Long?,
) {
    override fun toString(): String =
        "CachedAuthEntry(kind=$kind, credentialHash=***, credentialId=$credentialId, " +
            "accountId=$accountId, principal=$principal, credentialExpiresAtMs=$credentialExpiresAtMs, " +
            "cachedAtMs=$cachedAtMs, cacheExpiresAtMs=$cacheExpiresAtMs, lastActivityAtMs=$lastActivityAtMs)"
}

sealed interface PrincipalCacheLookup {
    data class Hit(
        val entry: CachedAuthEntry,
        val shouldPersistActivity: Boolean,
    ) : PrincipalCacheLookup

    data class ExpiredCredential(
        val entry: CachedAuthEntry,
    ) : PrincipalCacheLookup

    data object Miss : PrincipalCacheLookup
}

/**
 * Thread-safe bounded LRU principal cache keyed strictly by SHA-256 credential hash.
 *
 * Enforces both short cache TTL and strict credential expiration (`expiresAtMs <= nowMs`), tracks
 * last-seen/last-used timestamps to coalesce activity writes across cache hits, and uses a
 * monotonic invalidation generation so in-flight DB reads cannot repopulate stale state after a
 * concurrent mutation.
 */
class BoundedPrincipalCache(
    private val ttlMs: Long,
    private val maxEntries: Int,
) {
    init {
        require(ttlMs >= 0L) { "ttlMs must be >= 0" }
        require(maxEntries >= 0) { "maxEntries must be >= 0" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<CredentialCacheKey, CachedAuthEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var generation: Long = 0L

    val enabled: Boolean
        get() = ttlMs > 0L && maxEntries > 0

    fun currentGeneration(): Long = synchronized(lock) { generation }

    fun getAndClaimActivityUpdate(
        kind: CachedCredentialKind,
        hash: String,
        nowMs: Long,
        touchThresholdMs: Long,
    ): PrincipalCacheLookup {
        if (!enabled) return PrincipalCacheLookup.Miss

        synchronized(lock) {
            val key = CredentialCacheKey(kind, hash)
            val entry = entries[key] ?: return PrincipalCacheLookup.Miss

            if (entry.credentialExpiresAtMs != null && entry.credentialExpiresAtMs <= nowMs) {
                entries.remove(key)
                generation++
                return PrincipalCacheLookup.ExpiredCredential(entry)
            }

            if (nowMs >= entry.cacheExpiresAtMs) {
                entries.remove(key)
                return PrincipalCacheLookup.Miss
            }

            val shouldPersist = shouldPersistActivity(entry.lastActivityAtMs, nowMs, touchThresholdMs)
            val effectiveEntry =
                if (shouldPersist) {
                    entry.copy(lastActivityAtMs = nowMs).also { updated -> entries[key] = updated }
                } else {
                    entry
                }
            return PrincipalCacheLookup.Hit(entry = effectiveEntry, shouldPersistActivity = shouldPersist)
        }
    }

    fun putIfGeneration(
        entry: CachedAuthEntry,
        expectedGeneration: Long,
    ) {
        if (!enabled || entry.cacheExpiresAtMs <= entry.cachedAtMs) return

        synchronized(lock) {
            if (generation != expectedGeneration) return

            val key = CredentialCacheKey(entry.kind, entry.credentialHash)
            entries.remove(key)
            evictExpiredLocked(entry.cachedAtMs)

            val iterator = entries.entries.iterator()
            while (iterator.hasNext() && entries.size >= maxEntries) {
                iterator.next()
                iterator.remove()
            }

            entries[key] = entry
        }
    }

    fun invalidateByKey(
        kind: CachedCredentialKind,
        hash: String,
    ) {
        synchronized(lock) {
            generation++
            entries.remove(CredentialCacheKey(kind, hash))
        }
    }

    fun invalidateByAccountId(accountId: String) {
        synchronized(lock) {
            generation++
            entries.entries.removeIf { it.value.accountId == accountId }
        }
    }

    fun invalidateSessionsByAccountId(accountId: String) {
        synchronized(lock) {
            generation++
            entries.entries.removeIf {
                it.key.kind == CachedCredentialKind.SESSION && it.value.accountId == accountId
            }
        }
    }

    fun evictExpired(nowMs: Long) {
        synchronized(lock) {
            if (evictExpiredLocked(nowMs)) {
                generation++
            }
        }
    }

    fun invalidateAll() {
        synchronized(lock) {
            generation++
            entries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun evictExpiredLocked(nowMs: Long): Boolean {
        var removed = false
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val credentialExpired = entry.credentialExpiresAtMs != null && entry.credentialExpiresAtMs <= nowMs
            val cacheExpired = nowMs >= entry.cacheExpiresAtMs
            if (credentialExpired || cacheExpired) {
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f

        fun shouldPersistActivity(
            lastActivityAtMs: Long?,
            nowMs: Long,
            thresholdMs: Long,
        ): Boolean =
            lastActivityAtMs == null || (nowMs - lastActivityAtMs) >= thresholdMs
    }
}
