package app.hononeko.miru.adapter.plex

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with full jitter.
 *
 * For attempt `n` (1-based) the ceiling is `min(max, initial * multiplier^(n-1))` and the
 * returned delay is uniformly drawn from `[0, ceiling]`.
 */
data class BackoffPolicy(
    val initial: Duration = 1.seconds,
    val max: Duration = 60.seconds,
    val multiplier: Double = 2.0,
    val resetAfter: Duration = 30.seconds,
) {
    init {
        require(initial.isPositive()) { "initial must be positive" }
        require(max >= initial) { "max must be >= initial" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    fun ceilingFor(attempt: Int): Duration {
        val exponent = (attempt.coerceAtLeast(1) - 1).toDouble()
        val raw = initial.inWholeMilliseconds * multiplier.pow(exponent)
        val capped = min(raw, max.inWholeMilliseconds.toDouble())
        return capped.toLong().milliseconds
    }

    fun delayFor(
        attempt: Int,
        random: Random,
    ): Duration {
        val ceilingMs = ceilingFor(attempt).inWholeMilliseconds
        return random.nextLong(0, ceilingMs + 1).milliseconds
    }
}
