package app.hononeko.miru.artwork

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.atomic.AtomicLong

class BoundedArtworkCacheTest :
    FunSpec({
        test("evicts least recently used entries when maxBytes is exceeded") {
            val nowMs = AtomicLong(1_000L)
            val cache = BoundedArtworkCache(maxBytes = 10L, ttlMs = 60_000L, maxEntryBytes = 10L, clock = { nowMs.get() })

            cache.put("k1", byteArrayOf(1, 2, 3, 4), "image/png")
            cache.put("k2", byteArrayOf(5, 6, 7, 8), "image/png")
            cache.sizeBytes() shouldBe 8L
            cache.entryCount() shouldBe 2

            cache.put("k3", byteArrayOf(9, 10, 11, 12), "image/png")
            cache.sizeBytes() shouldBe 8L
            cache.entryCount() shouldBe 2
            cache.get("k1").shouldBeNull()
            cache.get("k2").shouldNotBeNull()
            cache.get("k3").shouldNotBeNull()
        }

        test("cache hit refreshes recency so older untouched entry is evicted first") {
            val nowMs = AtomicLong(1_000L)
            val cache = BoundedArtworkCache(maxBytes = 10L, ttlMs = 60_000L, maxEntryBytes = 10L, clock = { nowMs.get() })

            cache.put("k1", byteArrayOf(1, 2, 3, 4), "image/jpeg")
            cache.put("k2", byteArrayOf(5, 6, 7, 8), "image/jpeg")

            // Touch k1 so k2 becomes least recently used
            cache.get("k1").shouldNotBeNull()

            cache.put("k3", byteArrayOf(9, 10, 11, 12), "image/jpeg")
            cache.get("k1").shouldNotBeNull()
            cache.get("k2").shouldBeNull()
            cache.get("k3").shouldNotBeNull()
            cache.sizeBytes() shouldBe 8L
        }

        test("lazily evicts expired entries on read and prunes expired entries on put") {
            val nowMs = AtomicLong(1_000L)
            val cache = BoundedArtworkCache(maxBytes = 10L, ttlMs = 5_000L, maxEntryBytes = 10L, clock = { nowMs.get() })

            cache.put("k1", byteArrayOf(1, 2, 3, 4), "image/png")
            nowMs.set(3_000L)
            cache.put("k2", byteArrayOf(5, 6, 7, 8), "image/png")

            nowMs.set(6_000L) // k1 expired at 6_000L, k2 expires at 8_000L
            cache.get("k1").shouldBeNull()
            cache.sizeBytes() shouldBe 4L
            cache.entryCount() shouldBe 1
            cache.get("k2").shouldNotBeNull()

            nowMs.set(9_000L) // k2 now expired
            cache.put("k3", byteArrayOf(1, 2, 3, 4, 5, 6), "image/webp")
            cache.sizeBytes() shouldBe 6L
            cache.entryCount() shouldBe 1
            cache.get("k3").shouldNotBeNull()
        }

        test("oversized entry bypasses storage without crashing and still returns CachedArtwork") {
            val nowMs = AtomicLong(1_000L)
            val cache = BoundedArtworkCache(maxBytes = 16L, ttlMs = 60_000L, maxEntryBytes = 5L, clock = { nowMs.get() })

            val small = cache.put("small", byteArrayOf(1, 2, 3), "image/png")
            small.bytes.contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
            cache.sizeBytes() shouldBe 3L
            cache.entryCount() shouldBe 1

            val largeBytes = ByteArray(8) { it.toByte() }
            val oversized = cache.put("large", largeBytes, "image/jpeg")
            oversized.bytes.contentEquals(largeBytes) shouldBe true
            oversized.contentType shouldBe "image/jpeg"
            oversized.etag shouldMatch Regex("^\"[0-9a-f]{32}\"$")

            cache.get("large").shouldBeNull()
            cache.sizeBytes() shouldBe 3L
            cache.entryCount() shouldBe 1

            // Updating an existing key with an oversized entry removes the stale entry
            cache.put("small", largeBytes, "image/jpeg")
            cache.get("small").shouldBeNull()
            cache.sizeBytes() shouldBe 0L
            cache.entryCount() shouldBe 0
        }

        test("generates deterministic strong ETag and safe toString without raw bytes") {
            val nowMs = AtomicLong(2_000L)
            val cache = BoundedArtworkCache(clock = { nowMs.get() })

            val payloadA = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val payloadB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

            val first = cache.put("a1", payloadA, "image/png")
            val second = cache.put("a2", payloadA.copyOf(), "image/png")
            val different = cache.put("b1", payloadB, "image/jpeg")

            first.etag shouldBe second.etag
            first.etag shouldMatch Regex("^\"[0-9a-f]{32}\"$")
            first.etag shouldNotBe different.etag
            first shouldBe second.copy()
            first.hashCode() shouldBe second.hashCode()
            first.toString() shouldNotContain "[B@"
            first.toString() shouldNotContain "89504e47"

            cache.clear()
            cache.sizeBytes() shouldBe 0L
            cache.entryCount() shouldBe 0
        }

        test("rejects invalid cache configuration arguments") {
            shouldThrow<IllegalArgumentException> { BoundedArtworkCache(maxBytes = -1) }
            shouldThrow<IllegalArgumentException> { BoundedArtworkCache(ttlMs = 0) }
            shouldThrow<IllegalArgumentException> { BoundedArtworkCache(maxEntryBytes = -1) }
        }
    })
