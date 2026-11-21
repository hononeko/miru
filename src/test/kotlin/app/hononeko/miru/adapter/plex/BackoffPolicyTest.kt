package app.hononeko.miru.adapter.plex

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffPolicyTest :
    FunSpec({
        val policy = BackoffPolicy(initial = 1.seconds, max = 60.seconds, multiplier = 2.0)

        test("ceiling doubles per attempt and is capped at max") {
            (1..9).map { policy.ceilingFor(it) } shouldBe
                listOf(1, 2, 4, 8, 16, 32, 60, 60, 60).map { it.seconds }
            policy.ceilingFor(0) shouldBe 1.seconds
            policy.ceilingFor(Int.MAX_VALUE) shouldBe 60.seconds
        }

        test("full jitter stays within [0, ceiling] and is deterministic for a seed") {
            val random = Random(42)
            (1..200).forEach { attempt ->
                val delay = policy.delayFor(attempt % 10 + 1, random)
                delay shouldBeGreaterThanOrEqualTo Duration.ZERO
                delay shouldBeLessThanOrEqualTo policy.ceilingFor(attempt % 10 + 1)
            }

            val a = (1..5).map { policy.delayFor(it, Random(7)) }
            val b = (1..5).map { policy.delayFor(it, Random(7)) }
            a shouldBe b
        }

        test("jitter actually spreads delays") {
            val random = Random(1)
            val samples = (1..50).map { policy.delayFor(6, random) }.toSet()
            samples.size shouldNotBe 1
        }

        test("invalid policies are rejected") {
            shouldThrow<IllegalArgumentException> { BackoffPolicy(initial = Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { BackoffPolicy(initial = 2.seconds, max = 1.seconds) }
            shouldThrow<IllegalArgumentException> { BackoffPolicy(multiplier = 0.5) }
            BackoffPolicy(initial = 10.milliseconds, max = 10.milliseconds).ceilingFor(5) shouldBe 10.milliseconds
        }
    })
