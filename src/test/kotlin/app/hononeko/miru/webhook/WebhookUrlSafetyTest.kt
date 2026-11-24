package app.hononeko.miru.webhook

import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress
import java.net.UnknownHostException

class WebhookUrlSafetyTest :
    FunSpec({
        context("validateUrl") {
            test("accepts public, LAN RFC 1918, Kubernetes internal, IPv6 ULA, and loopback URLs") {
                val allowed =
                    listOf(
                        "https://hooks.example.com/miru",
                        "http://media-webhook-notifier.media.svc.cluster.local:8080/events",
                        "http://192.168.1.50:9000/webhook?token=abc",
                        "http://10.0.0.12:8080/hook",
                        "http://172.16.4.20/hook",
                        "http://100.64.0.5:8080/tailscale",
                        "http://127.0.0.1:8080/local",
                        "http://[::1]:8080/local-v6",
                        "http://[fd00::10]:8080/ula",
                    )
                for (url in allowed) {
                    WebhookUrlSafety.validateUrl(url).shouldBeRight() shouldBe url
                }
            }

            test("rejects invalid schemes, credentials, whitespace, control chars, and blocked SSRF literals") {
                val rejected =
                    listOf(
                        "",
                        "   ",
                        "ftp://example.com/hook",
                        "file:///etc/passwd",
                        "https://user:pass@example.com/hook",
                        "https://token@example.com/hook",
                        "https://example.com/path with space",
                        "https://example.com/hook\nX-Injected: 1",
                        "https://" + "a".repeat(2050),
                        "http://",
                        "http://169.254.169.254/latest/meta-data/",
                        "http://169.254.170.2/v2/credentials",
                        "http://0.0.0.0:8080/hook",
                        "http://0.1.2.3/hook",
                        "http://224.0.0.1/multicast",
                        "http://255.255.255.255/broadcast",
                        "http://[fe80::1]/link-local",
                        "http://[::]/unspecified",
                        "http://[ff02::1]/multicast",
                        "http://[::ffff:169.254.169.254]/mapped-imds",
                        "http://metadata.google.internal/computeMetadata/v1/",
                        "http://metadata.google.internal./computeMetadata/v1/",
                    )
                for (url in rejected) {
                    WebhookUrlSafety.validateUrl(url).shouldBeLeft()
                }
            }
        }

        context("resolveAndValidateHost") {
            test("permits RFC 1918 and public resolved IPs while blocking IMDS, link-local, multicast, and embedded ranges") {
                val allowedResolver =
                    DnsResolver {
                        listOf(
                            InetAddress.getByName("192.168.1.42"),
                            InetAddress.getByName("93.184.216.34"),
                        )
                    }
                WebhookUrlSafety.resolveAndValidateHost("notifier.local", allowedResolver).shouldBeRight()

                val blockedIps =
                    listOf(
                        "169.254.169.254",
                        "0.0.0.0",
                        "224.0.0.1",
                        "240.0.0.1",
                        "fe80::1",
                        "::",
                        "ff02::1",
                        "::ffff:169.254.169.254",
                        "64:ff9b::169.254.169.254",
                        "2002:a9fe:a9fe::1",
                    )
                for (ip in blockedIps) {
                    val resolver = DnsResolver { listOf(InetAddress.getByName(ip)) }
                    WebhookUrlSafety
                        .resolveAndValidateHost("rebind.example.test", resolver)
                        .shouldBeLeft()
                        .shouldBeInstanceOf<MiruError.ValidationError>()
                }

                // Handles bracketed IPv6 host from java.net.URI.host
                val ipv6Resolver = DnsResolver { listOf(InetAddress.getByName("::1")) }
                WebhookUrlSafety.resolveAndValidateHost("[::1]", ipv6Resolver).shouldBeRight()
            }

            test("maps DNS failures to typed errors") {
                val unknownHost = DnsResolver { throw UnknownHostException("nxdomain") }
                WebhookUrlSafety
                    .resolveAndValidateHost("missing.invalid", unknownHost)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                val emptyResolver = DnsResolver { emptyList() }
                WebhookUrlSafety
                    .resolveAndValidateHost("empty.invalid", emptyResolver)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                val blockedDns = DnsResolver { throw SecurityException("denied") }
                WebhookUrlSafety
                    .resolveAndValidateHost("blocked.invalid", blockedDns)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.ValidationError>()
            }
        }

        context("validateAuthHeaderName") {
            test("accepts standard custom auth headers and rejects malformed or reserved headers") {
                WebhookUrlSafety.validateAuthHeaderName("Authorization").shouldBeRight() shouldBe "Authorization"
                WebhookUrlSafety.validateAuthHeaderName("X-Api-Key").shouldBeRight() shouldBe "X-Api-Key"
                WebhookUrlSafety.validateAuthHeaderName("  X-Webhook-Token  ").shouldBeRight() shouldBe "X-Webhook-Token"

                WebhookUrlSafety.validateAuthHeaderName("").shouldBeLeft()
                WebhookUrlSafety.validateAuthHeaderName("Bad Header").shouldBeLeft()
                WebhookUrlSafety.validateAuthHeaderName("Bad:Header").shouldBeLeft()
                WebhookUrlSafety.validateAuthHeaderName("Host").shouldBeLeft()
                WebhookUrlSafety.validateAuthHeaderName("Content-Type").shouldBeLeft()
                WebhookUrlSafety.validateAuthHeaderName("X-Miru-Signature-256").shouldBeLeft()
                WebhookUrlSafety.validateAuthHeaderName("X-Miru-Event").shouldBeLeft()
            }
        }

        context("redactUrl, signPayload, and sanitizeErrorMessage") {
            test("redacts query strings, fragments, and embedded credentials in URLs") {
                WebhookUrlSafety.redactUrl("https://hooks.example.com/notify?token=secret123&mode=fast#frag") shouldBe
                    "https://hooks.example.com/notify?***"
                WebhookUrlSafety.redactUrl("http://user:pw@192.168.1.5:8080/hook?key=val") shouldBe
                    "http://***@192.168.1.5:8080/hook?***"
                WebhookUrlSafety.redactUrl("http://notifier.media.svc.cluster.local:8080/events") shouldBe
                    "http://notifier.media.svc.cluster.local:8080/events"
                WebhookUrlSafety.redactUrl("https://bad url?secret=123") shouldNotContain "secret=123"
            }

            test("computes RFC-compliant HMAC-SHA256 hex signature") {
                // Known HMAC-SHA256("secret", "hello") = 88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b
                val sig = WebhookUrlSafety.signPayload("secret", "hello".toByteArray(Charsets.UTF_8))
                sig shouldBe "sha256=88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b"
            }

            test("sanitizes error messages by redacting secrets, query strings, control chars, and truncating") {
                val raw =
                    "Error posting to https://hooks.example.com/hook?token=super-secret\r\n" +
                        "with Bearer my-auth-token and extra " + "x".repeat(600)
                val sanitized =
                    WebhookUrlSafety.sanitizeErrorMessage(
                        raw = raw,
                        secretsToRedact = listOf("my-auth-token"),
                    )
                sanitized shouldNotContain "super-secret"
                sanitized shouldNotContain "my-auth-token"
                sanitized shouldNotContain "\n"
                sanitized shouldNotContain "\r"
                sanitized shouldContain "https://hooks.example.com/hook?***"
                (sanitized.length <= 500) shouldBe true
            }
        }
    })
