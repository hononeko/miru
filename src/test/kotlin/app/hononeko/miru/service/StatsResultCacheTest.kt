package app.hononeko.miru.service

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.DecisionBreakdownDto
import app.hononeko.miru.http.OverviewStatsResponse
import app.hononeko.miru.http.TopMediaStatDto
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private val EMPTY_DECISIONS =
    DecisionBreakdownDto(
        directPlay = 0L,
        directStream = 0L,
        transcode = 0L,
        hwTranscode = 0L,
        total = 0L,
        directPlayPercent = 0.0,
        directStreamPercent = 0.0,
        transcodePercent = 0.0,
        hwTranscodePercent = 0.0,
    )

private fun overview(
    totalPlays: Long,
    titles: List<String> = emptyList(),
): OverviewStatsResponse =
    OverviewStatsResponse(
        range = "all",
        totalPlays = totalPlays,
        totalWatchDurationSec = 0L,
        activeUsers = 0,
        playsPerDay = emptyList(),
        topMovies = titles.map { TopMediaStatDto(ratingKey = it, title = it, playCount = 1L, watchDurationSec = 1L) },
        topShows = emptyList(),
        decisionBreakdown = EMPTY_DECISIONS,
    )

private fun overviewKey(
    generation: Long = 0L,
    scopedUserIds: Set<String>? = null,
    serverId: String? = null,
    period: String = "all",
    utcDay: Long? = null,
): StatsCacheKey<OverviewStatsResponse> =
    StatsCacheKey(
        generation = generation,
        endpoint = StatsCacheEndpoint.Overview,
        serverId = serverId,
        scopedUserIds = scopedUserIds,
        viewerId = null,
        period = period,
        metric = AnalyticsMetric.PLAYS,
        utcDay = utcDay,
    )

private class RecordingListener : StatsCacheListener {
    val lookups: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())

    override fun onLookup(
        endpoint: StatsCacheEndpoint<*>,
        hit: Boolean,
    ) {
        lookups += endpoint.label to hit
    }
}

class StatsResultCacheTest :
    FunSpec({
        test("serves a cached payload until it expires and reports hits and misses") {
            val listener = RecordingListener()
            val cache = StatsResultCache(ttlMs = 1_000L).also { it.listener = listener }
            val computations = AtomicInteger()
            val compute = suspend { Either.Right(overview(totalPlays = computations.incrementAndGet().toLong())) }

            cache.getOrCompute(overviewKey(), nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe 1L
            val warm = cache.getOrCompute(overviewKey(), nowMs = 999L, compute = compute).shouldBeRight()
            warm.totalPlays shouldBe 1L
            computations.get() shouldBe 1

            cache.getOrCompute(overviewKey(), nowMs = 1_000L, compute = compute).shouldBeRight().totalPlays shouldBe 2L
            listener.lookups shouldContainExactly listOf("overview" to false, "overview" to true, "overview" to false)
        }

        test("keys entries by every field, so another scope, server, period or day misses") {
            val cache = StatsResultCache()
            val computations = AtomicInteger()
            val compute = suspend { Either.Right(overview(totalPlays = computations.incrementAndGet().toLong())) }
            val keys =
                listOf(
                    overviewKey(),
                    overviewKey(scopedUserIds = emptySet()),
                    overviewKey(scopedUserIds = setOf("u-alice")),
                    overviewKey(scopedUserIds = setOf("u-alice", "u-bob")),
                    overviewKey(serverId = "srv-1"),
                    overviewKey(period = "7d", utcDay = 20_000L),
                    overviewKey(period = "7d", utcDay = 20_001L),
                )

            keys.forEach { cache.getOrCompute(it, nowMs = 0L, compute = compute).shouldBeRight() }
            computations.get() shouldBe keys.size
            keys.forEachIndexed { index, key ->
                cache.getOrCompute(key, nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe index + 1L
            }
            computations.get() shouldBe keys.size
            // Sets compare by content, so a scope resolved again on the next request still hits
            cache.getOrCompute(overviewKey(scopedUserIds = linkedSetOf("u-bob", "u-alice")), nowMs = 0L, compute = compute)
                .shouldBeRight()
                .totalPlays shouldBe 4L
        }

        test("invalidate drops every payload and moves keys to a new generation") {
            val cache = StatsResultCache()
            val computations = AtomicInteger()
            val compute = suspend { Either.Right(overview(totalPlays = computations.incrementAndGet().toLong())) }

            cache.getOrCompute(overviewKey(generation = cache.currentGeneration()), nowMs = 0L, compute = compute).shouldBeRight()
            cache.entryCount() shouldBe 1
            cache.invalidate()
            cache.entryCount() shouldBe 0
            cache.sizeBytes() shouldBe 0L
            cache.currentGeneration() shouldBe 1L

            // A key built before the write finds nothing and stores nothing
            cache.getOrCompute(overviewKey(generation = 0L), nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe 2L
            cache.entryCount() shouldBe 0
            cache.getOrCompute(overviewKey(generation = 1L), nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe 3L
            cache.getOrCompute(overviewKey(generation = 1L), nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe 3L
        }

        test("does not store a payload computed while a write landed") {
            val cache = StatsResultCache()
            val key = overviewKey(generation = cache.currentGeneration())

            cache
                .getOrCompute(key, nowMs = 0L) {
                    cache.invalidate()
                    Either.Right(overview(totalPlays = 1L))
                }.shouldBeRight()

            cache.entryCount() shouldBe 0
        }

        test("never caches errors") {
            val cache = StatsResultCache()
            val computations = AtomicInteger()
            val failing = suspend {
                computations.incrementAndGet()
                Either.Left(MiruError.PersistenceError(operation = "stats", message = "database unavailable"))
            }

            cache.getOrCompute(overviewKey(), nowMs = 0L, compute = failing).shouldBeLeft()
            cache.getOrCompute(overviewKey(), nowMs = 0L, compute = failing).shouldBeLeft()
            computations.get() shouldBe 2
            cache.entryCount() shouldBe 0
        }

        test("evicts the least recently used payload beyond the entry limit") {
            val cache = StatsResultCache(maxEntries = 2)
            val computations = AtomicInteger()
            val compute = suspend { Either.Right(overview(totalPlays = computations.incrementAndGet().toLong())) }
            val first = overviewKey(serverId = "srv-1")
            val second = overviewKey(serverId = "srv-2")
            val third = overviewKey(serverId = "srv-3")

            cache.getOrCompute(first, nowMs = 0L, compute = compute)
            cache.getOrCompute(second, nowMs = 0L, compute = compute)
            // Touching the first makes the second the eldest
            cache.getOrCompute(first, nowMs = 0L, compute = compute)
            cache.getOrCompute(third, nowMs = 0L, compute = compute)

            cache.entryCount() shouldBe 2
            computations.get() shouldBe 3
            cache.getOrCompute(first, nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe 1L
            cache.getOrCompute(second, nowMs = 0L, compute = compute).shouldBeRight().totalPlays shouldBe 4L
        }

        test("stays within the byte budget and skips a payload too large to share it") {
            val cache =
                StatsResultCache(
                    maxBytes = 100L,
                    maxEntryBytes = 60L,
                    weigher = { _, value -> (value as OverviewStatsResponse).totalPlays },
                )
            val sized = { weight: Long -> suspend { Either.Right(overview(totalPlays = weight)) } }

            cache.getOrCompute(overviewKey(serverId = "a"), nowMs = 0L, compute = sized(40L))
            cache.getOrCompute(overviewKey(serverId = "b"), nowMs = 0L, compute = sized(40L))
            cache.sizeBytes() shouldBe 80L
            cache.getOrCompute(overviewKey(serverId = "c"), nowMs = 0L, compute = sized(50L))
            cache.sizeBytes() shouldBe 90L
            cache.entryCount() shouldBe 2

            val oversized = cache.getOrCompute(overviewKey(serverId = "d"), nowMs = 0L, compute = sized(61L)).shouldBeRight()
            oversized.totalPlays shouldBe 61L
            cache.sizeBytes() shouldBe 90L
            cache.entryCount() shouldBe 2
        }

        test("estimates a payload's size from its JSON, so a larger payload weighs more") {
            val small = estimatedRetainedBytes(StatsCacheEndpoint.Overview, overview(totalPlays = 1L))
            val large = estimatedRetainedBytes(StatsCacheEndpoint.Overview, overview(totalPlays = 1L, titles = (1..50).map { "Title $it" }))
            small shouldBeGreaterThan 0L
            large shouldBeGreaterThan small * 5
            shouldThrow<ClassCastException> {
                estimatedRetainedBytes(StatsCacheEndpoint.Clients, overview(totalPlays = 1L))
            }
        }

        test("runs concurrent misses for one key once and shares the result") {
            runTest {
                val listener = RecordingListener()
                val cache = StatsResultCache().also { it.listener = listener }
                val computations = AtomicInteger()
                val gate = CompletableDeferred<Unit>()
                val payload = overview(totalPlays = 7L)
                val compute = suspend {
                    computations.incrementAndGet()
                    gate.await()
                    Either.Right(payload)
                }

                val callers = (1..8).map { async { cache.getOrCompute(overviewKey(), nowMs = 0L, compute = compute) } }
                testScheduler.runCurrent()
                computations.get() shouldBe 1
                gate.complete(Unit)

                callers.awaitAll().forEach { it.shouldBeRight() shouldBeSameInstanceAs payload }
                computations.get() shouldBe 1
                listener.lookups.count { !it.second } shouldBe 1
                listener.lookups.count { it.second } shouldBe 7
            }
        }

        test("shares an error with the callers waiting on it without caching it") {
            runTest {
                val cache = StatsResultCache()
                val computations = AtomicInteger()
                val gate = CompletableDeferred<Unit>()
                val compute = suspend {
                    computations.incrementAndGet()
                    gate.await()
                    Either.Left(MiruError.PersistenceError(operation = "stats", message = "database unavailable"))
                }

                val callers = (1..3).map { async { cache.getOrCompute(overviewKey(), nowMs = 0L, compute = compute) } }
                testScheduler.runCurrent()
                gate.complete(Unit)

                callers.awaitAll().forEach { it.shouldBeLeft() }
                computations.get() shouldBe 1
                cache.entryCount() shouldBe 0
            }
        }

        test("a waiting caller takes over when the caller running the computation is cancelled") {
            runTest {
                val cache = StatsResultCache()
                val computations = AtomicInteger()
                val leaderGate = CompletableDeferred<Unit>()
                val leader =
                    async {
                        cache.getOrCompute(overviewKey(), nowMs = 0L) {
                            computations.incrementAndGet()
                            leaderGate.await()
                            Either.Right(overview(totalPlays = 1L))
                        }
                    }
                testScheduler.runCurrent()
                val follower =
                    async {
                        cache.getOrCompute(overviewKey(), nowMs = 0L) {
                            computations.incrementAndGet()
                            Either.Right(overview(totalPlays = 2L))
                        }
                    }
                testScheduler.runCurrent()

                leader.cancel()
                follower.await().shouldBeRight().totalPlays shouldBe 2L
                computations.get() shouldBe 2
                cache.getOrCompute(overviewKey(), nowMs = 0L) { error("cached") }.shouldBeRight().totalPlays shouldBe 2L
            }
        }

        test("a waiting caller runs the computation itself when the running one throws") {
            runTest {
                val cache = StatsResultCache()
                val gate = CompletableDeferred<Unit>()
                val leader =
                    async {
                        runCatching {
                            cache.getOrCompute(overviewKey(), nowMs = 0L) {
                                gate.await()
                                error("boom")
                            }
                        }
                    }
                testScheduler.runCurrent()
                val follower = async { cache.getOrCompute(overviewKey(), nowMs = 0L) { Either.Right(overview(totalPlays = 3L)) } }
                testScheduler.runCurrent()
                gate.complete(Unit)

                leader.await().exceptionOrNull()?.message shouldBe "boom"
                follower.await().shouldBeRight().totalPlays shouldBe 3L
            }
        }

        test("a cache with no memory computes every time") {
            val cache = StatsResultCache(maxBytes = 0L)
            val computations = AtomicInteger()
            val compute = suspend { Either.Right(overview(totalPlays = computations.incrementAndGet().toLong())) }

            cache.enabled shouldBe false
            cache.getOrCompute(overviewKey(), nowMs = 0L, compute = compute)
            cache.getOrCompute(overviewKey(), nowMs = 0L, compute = compute)
            computations.get() shouldBe 2
            cache.entryCount() shouldBe 0
        }
    })
