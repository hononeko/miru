package app.hononeko.miru.artwork

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.toProblemDetails
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

class IpAddressSafetyTest :
    FunSpec({
        test("rejects private, loopback, link-local, CGNAT, multicast, documentation, and reserved IPv4 addresses") {
            val disallowedIpv4 =
                listOf(
                    "0.0.0.0",
                    "10.0.0.1",
                    "100.64.0.1",
                    "127.0.0.1",
                    "127.255.255.254",
                    "169.254.169.254",
                    "172.16.0.1",
                    "172.31.255.254",
                    "192.0.0.1",
                    "192.0.2.1",
                    "192.168.1.1",
                    "198.18.0.1",
                    "198.51.100.1",
                    "203.0.113.1",
                    "224.0.0.1",
                    "240.0.0.1",
                    "255.255.255.255",
                )
            disallowedIpv4.forEach { ip ->
                val address = InetAddress.getByName(ip)
                IpAddressSafety.isPublicAddress(address) shouldBe false
            }
        }

        test("rejects non-public IPv6 and embedded IPv4 transition addresses") {
            val disallowedIpv6 =
                listOf(
                    "::",
                    "::1",
                    "fc00::1",
                    "fd12:3456:789a::1",
                    "fe80::1",
                    "fec0::1",
                    "ff02::1",
                    "0100::1",
                    "2001:db8::1",
                    "64:ff9b:1::1",
                    "64:ff9b::10.0.0.1",
                    "2002:0a00:0001::1",
                )
            disallowedIpv6.forEach { ip ->
                val address = InetAddress.getByName(ip)
                IpAddressSafety.isPublicAddress(address) shouldBe false
            }

            // Construct raw 16-byte IPv4-mapped ::ffff:127.0.0.1 and ::ffff:169.254.169.254
            // (InetAddress.getByName unwraps ::ffff:x.x.x.x to Inet4Address, whereas binary DNS AAAA records can vary).
            val mappedLoopback =
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 127, 0, 0, 1)
            val mappedMetadata =
                byteArrayOf(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0xFF.toByte(), 0xFF.toByte(), 169.toByte(), 254.toByte(), 169.toByte(), 254.toByte(),
                )
            val compatiblePrivate =
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)
            IpAddressSafety.isPublicIpv6(mappedLoopback) shouldBe false
            IpAddressSafety.isPublicIpv6(mappedMetadata) shouldBe false
            IpAddressSafety.isPublicIpv6(compatiblePrivate) shouldBe false
        }

        test("accepts public IPv4, public IPv6, and public-embedded transition addresses") {
            val allowed =
                listOf(
                    "1.1.1.1",
                    "8.8.8.8",
                    "93.184.216.34",
                    "172.32.0.1",
                    "100.128.0.1",
                    "2606:2800:220:1:248:1893:25c8:1946",
                    "64:ff9b::0101:0101",
                    "2002:0101:0101::1",
                )
            allowed.forEach { ip ->
                IpAddressSafety.isPublicAddress(InetAddress.getByName(ip)) shouldBe true
            }
        }

        test("resolveAndValidatePublicIps sorts IPv4 first and fails closed on empty, error, or mixed non-public DNS results") {
            val ipv6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")
            val ipv4 = InetAddress.getByName("93.184.216.34")

            val ordered =
                IpAddressSafety
                    .resolveAndValidatePublicIps("plex.tv", DnsResolver { listOf(ipv6, ipv4) })
                    .shouldBeRight()
            ordered shouldHaveSize 2
            ordered.first().shouldBeInstanceOf<Inet4Address>() shouldBe ipv4

            val mixedErr =
                IpAddressSafety
                    .resolveAndValidatePublicIps(
                        "plex.tv",
                        DnsResolver { listOf(ipv4, InetAddress.getByName("10.0.0.5")) },
                    ).shouldBeLeft()
            mixedErr.toProblemDetails().status shouldBe 502
            mixedErr.message shouldContain "disallowed non-public IP"

            IpAddressSafety
                .resolveAndValidatePublicIps("plex.tv", DnsResolver { emptyList() })
                .shouldBeLeft()
                .toProblemDetails()
                .status shouldBe 502

            IpAddressSafety
                .resolveAndValidatePublicIps("plex.tv", DnsResolver { throw UnknownHostException("nxdomain") })
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            IpAddressSafety
                .resolveAndValidatePublicIps("plex.tv", DnsResolver { throw SecurityException("blocked") })
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
        }

        test("PinnedPlainSocket connects strictly to pinnedAddress even with unresolved foreign hostname") {
            val loopback = InetAddress.getLoopbackAddress()
            ServerSocket(0, 1, loopback).use { server ->
                val factory =
                    PinnedIpSSLSocketFactory(
                        delegate = SSLContext.getDefault().socketFactory,
                        pinnedAddress = loopback,
                        expectedHost = "plex.tv",
                        connectTimeoutMs = 2_000,
                    )

                val plain = factory.createSocket()
                plain.shouldBeInstanceOf<PinnedPlainSocket>()
                plain.use { socket ->
                    socket.connect(InetSocketAddress.createUnresolved("untrusted-rebinding.invalid", server.localPort), 2_000)
                    socket.inetAddress shouldBe loopback
                    socket.port shouldBe server.localPort
                }

                server.accept().use { accepted ->
                    accepted.inetAddress shouldBe loopback
                }
            }
        }

        test("PinnedIpSSLSocketFactory enforces SNI and HTTPS endpoint identification") {
            val sslContext = SSLContext.getDefault()
            val factory =
                PinnedIpSSLSocketFactory(
                    delegate = sslContext.socketFactory,
                    pinnedAddress = InetAddress.getByName("93.184.216.34"),
                    expectedHost = "secure.gravatar.com",
                    connectTimeoutMs = 2_000,
                )

            val unbound = sslContext.socketFactory.createSocket() as javax.net.ssl.SSLSocket
            unbound.use { rawSsl ->
                val configured = factory.configureSsl(rawSsl)
                configured.sslParameters.endpointIdentificationAlgorithm shouldBe "HTTPS"
                configured.sslParameters.serverNames shouldBe listOf(SNIHostName("secure.gravatar.com"))
            }
        }
    })
