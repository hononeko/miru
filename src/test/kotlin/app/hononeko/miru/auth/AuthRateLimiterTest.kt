package app.hononeko.miru.auth

import app.hononeko.miru.config.AuthRateLimitConfig
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicLong

class AuthRateLimiterTest :
    FunSpec({
        test("enforces setup and per-IP login windows, calculates Retry-After, and resets after window expires") {
            val nowMs = AtomicLong(10_000L)
            val limiter =
                AuthRateLimiter(
                    config =
                        AuthRateLimitConfig(
                            enabled = true,
                            windowSeconds = 30,
                            loginMaxPerIp = 2,
                            loginMaxFailuresPerAccount = 2,
                            setupMaxPerIp = 1,
                        ),
                    clock = { nowMs.get() },
                )

            // Setup limit
            limiter.checkAndConsumeSetupAttempt("203.0.113.10").shouldBeRight()
            val setupBlocked = limiter.checkAndConsumeSetupAttempt("203.0.113.10").shouldBeLeft()
            setupBlocked.retryAfterSeconds shouldBe 30L

            nowMs.set(25_200L)
            limiter.checkAndConsumeSetupAttempt("203.0.113.10").shouldBeLeft().retryAfterSeconds shouldBe 15L

            nowMs.set(40_000L)
            limiter.checkAndConsumeSetupAttempt("203.0.113.10").shouldBeRight()

            // Login IP limit is isolated from setup limit and across IPs
            limiter.checkAndConsumeLoginIpAttempt("198.51.100.5").shouldBeRight()
            limiter.checkAndConsumeLoginIpAttempt("198.51.100.5").shouldBeRight()
            limiter.checkAndConsumeLoginIpAttempt("198.51.100.5").shouldBeLeft().retryAfterSeconds shouldBe 30L
            limiter.checkAndConsumeLoginIpAttempt("198.51.100.6").shouldBeRight()
        }

        test("tracks failed login attempts per normalized account, clears on success, and bounds tracked keys") {
            val nowMs = AtomicLong(1_000L)
            val limiter =
                AuthRateLimiter(
                    config =
                        AuthRateLimitConfig(
                            enabled = true,
                            windowSeconds = 60,
                            loginMaxPerIp = 100,
                            loginMaxFailuresPerAccount = 2,
                            setupMaxPerIp = 10,
                        ),
                    clock = { nowMs.get() },
                    maxTrackedKeys = 3,
                )

            limiter.checkLoginAccountAllowed("Alice").shouldBeRight()
            limiter.recordLoginFailure(" Alice ")
            limiter.checkLoginAccountAllowed("alice").shouldBeRight()
            limiter.recordLoginFailure("ALICE")
            limiter.checkLoginAccountAllowed("alice").shouldBeLeft().retryAfterSeconds shouldBe 60L

            // Successful login clears account failure counter
            limiter.recordLoginSuccess("alice")
            limiter.checkLoginAccountAllowed("alice").shouldBeRight()

            // Bounded key eviction: inserting 4 distinct keys when maxTrackedKeys=3 evicts the oldest
            nowMs.set(2_000L)
            limiter.checkAndConsumeLoginIpAttempt("10.0.0.1").shouldBeRight()
            nowMs.set(3_000L)
            limiter.checkAndConsumeLoginIpAttempt("10.0.0.2").shouldBeRight()
            nowMs.set(4_000L)
            limiter.checkAndConsumeLoginIpAttempt("10.0.0.3").shouldBeRight()
            nowMs.set(5_000L)
            // Consuming 10.0.0.1 is now evicted by 10.0.0.4 below
            limiter.checkAndConsumeLoginIpAttempt("10.0.0.4").shouldBeRight()
            limiter.checkAndConsumeLoginIpAttempt("10.0.0.2").shouldBeRight()
        }

        test("resolveClientIp ignores forwarding headers when untrusted and uses rightmost X-Forwarded-For when trusted") {
            testApplication {
                routing {
                    get("/untrusted") {
                        call.respondText(AuthRateLimiter.resolveClientIp(call, trustProxyHeaders = false))
                    }
                    get("/trusted") {
                        call.respondText(AuthRateLimiter.resolveClientIp(call, trustProxyHeaders = true))
                    }
                }

                // Spoofed X-Forwarded-For ignored when trustProxyHeaders=false
                val untrusted =
                    client.get("/untrusted") {
                        header(HttpHeaders.XForwardedFor, "1.2.3.4, 5.6.7.8")
                        header("X-Real-IP", "9.9.9.9")
                    }
                untrusted.status shouldBe HttpStatusCode.OK
                untrusted.bodyAsText() shouldBe "localhost"

                // Rightmost entry in X-Forwarded-For is returned when trusted (resists client prepended spoof)
                val trustedXff =
                    client.get("/trusted") {
                        header(HttpHeaders.XForwardedFor, "203.0.113.99, 198.51.100.42")
                        header("X-Real-IP", "9.9.9.9")
                    }
                trustedXff.status shouldBe HttpStatusCode.OK
                trustedXff.bodyAsText() shouldBe "198.51.100.42"

                // Falls back to X-Real-IP when X-Forwarded-For is absent or blank
                val trustedRealIp =
                    client.get("/trusted") {
                        header(HttpHeaders.XForwardedFor, " , ")
                        header("X-Real-IP", "192.0.2.77")
                    }
                trustedRealIp.bodyAsText() shouldBe "192.0.2.77"
            }
        }
    })
