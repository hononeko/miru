package app.hononeko.miru.artwork

import java.security.MessageDigest
import java.util.HexFormat

private const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
private const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
private const val DEFAULT_MAX_ENTRY_BYTES = 8L * 1024 * 1024
private const val ETAG_HEX_LENGTH = 32
private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f

data class CachedArtwork(
    val bytes: ByteArray,
    val contentType: String,
    val etag: String,
    val cachedAtMs: Long,
    val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CachedArtwork) return false
        return bytes.contentEquals(other.bytes) &&
            contentType == other.contentType &&
            etag == other.etag &&
            cachedAtMs == other.cachedAtMs &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + etag.hashCode()
        result = 31 * result + cachedAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }

    override fun toString(): String =
        "CachedArtwork(size=${bytes.size}, contentType=$contentType, etag=$etag, cachedAtMs=$cachedAtMs, expiresAtMs=$expiresAtMs)"
}

/**
 * Thread-safe byte-bounded LRU artwork cache with TTL expiration and strong SHA-256 ETags.
 */
class BoundedArtworkCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        require(ttlMs > 0) { "ttlMs must be > 0" }
        require(maxEntryBytes >= 0) { "maxEntryBytes must be >= 0" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, CachedArtwork>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentBytes: Long = 0L

    fun get(key: String): CachedArtwork? =
        synchronized(lock) {
            val entry = entries[key] ?: return null
            if (clock() >= entry.expiresAtMs) {
                entries.remove(key)
                currentBytes -= entry.bytes.size
                return null
            }
            entry
        }

    fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ): CachedArtwork {
        val now = clock()
        val etag = "\"${sha256Hex(bytes).take(ETAG_HEX_LENGTH)}\""
        val cached =
            CachedArtwork(
                bytes = bytes,
                contentType = contentType,
                etag = etag,
                cachedAtMs = now,
                expiresAtMs = now + ttlMs,
            )
        val entrySize = bytes.size.toLong()

        synchronized(lock) {
            entries.remove(key)?.let { previous ->
                currentBytes -= previous.bytes.size
            }

            if (entrySize > maxEntryBytes || entrySize > maxBytes) {
                return cached
            }

            evictExpiredLocked(now)

            val iterator = entries.entries.iterator()
            while (iterator.hasNext() && currentBytes + entrySize > maxBytes) {
                val eldest = iterator.next()
                iterator.remove()
                currentBytes -= eldest.value.bytes.size
            }

            entries[key] = cached
            currentBytes += entrySize
        }

        return cached
    }

    fun sizeBytes(): Long = synchronized(lock) { currentBytes }

    fun entryCount(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentBytes = 0L
        }
    }

    private fun evictExpiredLocked(nowMs: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs >= entry.value.expiresAtMs) {
                iterator.remove()
                currentBytes -= entry.value.bytes.size
            }
        }
    }

    companion object {
        private val hexFormat: HexFormat = HexFormat.of()

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return hexFormat.formatHex(digest)
        }

        fun sha256Hex(raw: String): String = sha256Hex(raw.toByteArray(Charsets.UTF_8))
    }
}
