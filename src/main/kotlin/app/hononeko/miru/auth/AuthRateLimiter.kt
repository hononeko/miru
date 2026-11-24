package app.hononeko.miru.auth

import app.hononeko.miru.config.AuthRateLimitConfig
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded-memory fixed-window rate limiter protecting local auth endpoints from credential
 * stuffing and Argon2id hashing DoS.
 *
 * When deployed behind a single trusted reverse proxy that sets proxy forwarding headers, set
 * `MIRU_TRUST_PROXY_HEADERS=true`; [resolveClientIp] takes the rightmost `X-Forwarded-For` entry
 * added by that trusted proxy so clients cannot spoof earlier entries.
 */
class AuthRateLimiter(
    private val config: AuthRateLimitConfig = AuthRateLimitConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
) {
    init {
        require(maxTrackedKeys >= 1) { "maxTrackedKeys must be >= 1" }
    }

    private data class WindowBucket(
        var windowStartMs: Long,
        var count: Int,
        var lastAccessedMs: Long,
    )

    private val mutex = Mutex()
    private val buckets = mutableMapOf<String, WindowBucket>()

    private val windowMs: Long = config.windowSeconds.toLong() * MILLIS_PER_SECOND

    suspend fun checkAndConsumeSetupAttempt(clientIp: String): Either<MiruError.TooManyRequests, Unit> =
        checkAndConsume(
            key = "$PREFIX_SETUP${normalizeKey(clientIp)}",
            maxAttempts = config.setupMaxPerIp,
        )

    suspend fun checkAndConsumeLoginIpAttempt(clientIp: String): Either<MiruError.TooManyRequests, Unit> =
        checkAndConsume(
            key = "$PREFIX_LOGIN_IP${normalizeKey(clientIp)}",
            maxAttempts = config.loginMaxPerIp,
        )

    suspend fun checkLoginAccountAllowed(normalizedUsername: String): Either<MiruError.TooManyRequests, Unit> {
        if (!config.enabled) return Either.Right(Unit)
        val key = normalizedUsername.trim().lowercase()
        if (key.isEmpty()) return Either.Right(Unit)

        return mutex.withLock {
            val now = clock()
            val fullKey = "$PREFIX_LOGIN_ACCOUNT$key"
            val existing = buckets[fullKey] ?: return@withLock Either.Right(Unit)
            if (now - existing.windowStartMs >= windowMs) {
                buckets.remove(fullKey)
                return@withLock Either.Right(Unit)
            }
            existing.lastAccessedMs = now
            if (existing.count >= config.loginMaxFailuresPerAccount) {
                Either.Left(rateLimitError(existing.windowStartMs, now))
            } else {
                Either.Right(Unit)
            }
        }
    }

    suspend fun recordLoginFailure(normalizedUsername: String) {
        if (!config.enabled) return
        val key = normalizedUsername.trim().lowercase()
        if (key.isEmpty()) return

        mutex.withLock {
            val now = clock()
            val fullKey = "$PREFIX_LOGIN_ACCOUNT$key"
            val existing = buckets[fullKey]
            if (existing == null || now - existing.windowStartMs >= windowMs) {
                ensureCapacityLocked(fullKey, now)
                buckets[fullKey] = WindowBucket(windowStartMs = now, count = 1, lastAccessedMs = now)
            } else {
                existing.count += 1
                existing.lastAccessedMs = now
            }
        }
    }

    suspend fun recordLoginSuccess(normalizedUsername: String) {
        if (!config.enabled) return
        val key = normalizedUsername.trim().lowercase()
        if (key.isEmpty()) return

        mutex.withLock {
            buckets.remove("$PREFIX_LOGIN_ACCOUNT$key")
        }
    }

    private suspend fun checkAndConsume(
        key: String,
        maxAttempts: Int,
    ): Either<MiruError.TooManyRequests, Unit> {
        if (!config.enabled) return Either.Right(Unit)

        return mutex.withLock {
            val now = clock()
            val existing = buckets[key]
            if (existing == null || now - existing.windowStartMs >= windowMs) {
                ensureCapacityLocked(key, now)
                buckets[key] = WindowBucket(windowStartMs = now, count = 1, lastAccessedMs = now)
                Either.Right(Unit)
            } else if (existing.count >= maxAttempts) {
                existing.lastAccessedMs = now
                Either.Left(rateLimitError(existing.windowStartMs, now))
            } else {
                existing.count += 1
                existing.lastAccessedMs = now
                Either.Right(Unit)
            }
        }
    }

    private fun ensureCapacityLocked(
        incomingKey: String,
        nowMs: Long,
    ) {
        if (incomingKey in buckets) return
        if (buckets.size < maxTrackedKeys) return

        val expiredKeys =
            buckets.entries
                .asSequence()
                .filter { nowMs - it.value.windowStartMs >= windowMs }
                .map { it.key }
                .toList()
        expiredKeys.forEach { buckets.remove(it) }

        while (buckets.size >= maxTrackedKeys) {
            val oldestKey = buckets.entries.minByOrNull { it.value.lastAccessedMs }?.key ?: break
            buckets.remove(oldestKey)
        }
    }

    private fun rateLimitError(
        windowStartMs: Long,
        nowMs: Long,
    ): MiruError.TooManyRequests {
        val remainingMs = (windowStartMs + windowMs - nowMs).coerceAtLeast(1L)
        val retryAfterSec = ((remainingMs + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND).coerceAtLeast(1L)
        return MiruError.TooManyRequests(
            message = "Too many authentication attempts; retry in ${retryAfterSec}s",
            retryAfterSeconds = retryAfterSec,
        )
    }

    private fun normalizeKey(raw: String): String =
        raw.trim().lowercase().ifEmpty { UNKNOWN_CLIENT_IP }

    companion object {
        const val DEFAULT_MAX_TRACKED_KEYS = 10_000
        private const val MILLIS_PER_SECOND = 1_000L
        private const val UNKNOWN_CLIENT_IP = "unknown"
        private const val PREFIX_SETUP = "setup:"
        private const val PREFIX_LOGIN_IP = "login-ip:"
        private const val PREFIX_LOGIN_ACCOUNT = "login-acct:"

        fun resolveClientIp(
            call: ApplicationCall,
            trustProxyHeaders: Boolean,
        ): String {
            if (trustProxyHeaders) {
                val xForwardedFor = call.request.headers[HttpHeaders.XForwardedFor]
                val rightmostForwarded =
                    xForwardedFor
                        ?.split(',')
                        ?.asSequence()
                        ?.map { it.trim() }
                        ?.lastOrNull { it.isNotEmpty() }
                if (!rightmostForwarded.isNullOrEmpty()) {
                    return rightmostForwarded
                }

                val xRealIp = call.request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() }
                if (xRealIp != null) {
                    return xRealIp
                }
            }
            return call.request.local.remoteAddress.trim().ifEmpty { UNKNOWN_CLIENT_IP }
        }
    }
}
