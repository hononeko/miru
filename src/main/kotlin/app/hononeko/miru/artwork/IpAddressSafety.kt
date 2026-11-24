package app.hononeko.miru.artwork

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

fun interface DnsResolver {
    fun resolveAll(host: String): List<InetAddress>
}

object SystemDnsResolver : DnsResolver {
    override fun resolveAll(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()
}

/**
 * Validates resolved DNS addresses against loopback, RFC 1918 private, link-local, CGNAT,
 * unique-local IPv6, multicast, documentation, and transition/embedded non-public ranges.
 */
@Suppress("MagicNumber")
object IpAddressSafety {
    private const val PROVIDER = "avatar"

    fun resolveAndValidatePublicIps(
        host: String,
        dnsResolver: DnsResolver = SystemDnsResolver,
    ): Either<MiruError.UpstreamUnavailable, List<InetAddress>> =
        either {
            val resolved =
                try {
                    dnsResolver.resolveAll(host)
                } catch (e: UnknownHostException) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER,
                            message = "Failed to resolve external avatar host '$host'",
                            cause = e,
                        ),
                    )
                } catch (e: SecurityException) {
                    raise(
                        MiruError.UpstreamUnavailable(
                            provider = PROVIDER,
                            message = "DNS resolution blocked for external avatar host '$host'",
                            cause = e,
                        ),
                    )
                }

            ensure(resolved.isNotEmpty()) {
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER,
                    message = "External avatar host '$host' resolved to no addresses",
                )
            }

            ensure(resolved.all(::isPublicAddress)) {
                MiruError.UpstreamUnavailable(
                    provider = PROVIDER,
                    message = "External avatar host '$host' resolved to a disallowed non-public IP address",
                )
            }

            val (ipv4, other) = resolved.partition { it is Inet4Address }
            ipv4 + other
        }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) {
            return false
        }
        if (address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return false
        }

        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    internal fun isPublicIpv4(raw: ByteArray): Boolean {
        if (raw.size != 4) return false
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        val b2 = raw[2].toInt() and 0xFF

        val disallowed =
            when {
                b0 == 0 -> true // 0.0.0.0/8 ("this" network)
                b0 == 10 -> true // 10.0.0.0/8 (RFC 1918)
                b0 == 100 && (b1 and 0xC0) == 64 -> true // 100.64.0.0/10 (CGNAT RFC 6598)
                b0 == 127 -> true // 127.0.0.0/8 (loopback)
                b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 (link-local / cloud metadata)
                b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12 (RFC 1918)
                b0 == 192 && b1 == 0 && b2 == 0 -> true // 192.0.0.0/24 (IETF protocol assignments)
                b0 == 192 && b1 == 0 && b2 == 2 -> true // 192.0.2.0/24 (TEST-NET-1)
                b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 (RFC 1918)
                b0 == 198 && (b1 == 18 || b1 == 19) -> true // 198.18.0.0/15 (benchmarking)
                b0 == 198 && b1 == 51 && b2 == 100 -> true // 198.51.100.0/24 (TEST-NET-2)
                b0 == 203 && b1 == 0 && b2 == 113 -> true // 203.0.113.0/24 (TEST-NET-3)
                b0 >= 224 -> true // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved
                else -> false
            }
        return !disallowed
    }

    internal fun isPublicIpv6(raw: ByteArray): Boolean {
        if (raw.size != 16) return false
        val b = IntArray(16) { idx -> raw[idx].toInt() and 0xFF }
        if (hasDisallowedIpv6Prefix(b)) return false
        val embeddedIpv4 = extractEmbeddedIpv4(raw, b)
        return embeddedIpv4 == null || isPublicIpv4(embeddedIpv4)
    }

    private fun hasDisallowedIpv6Prefix(b: IntArray): Boolean =
        when {
            // Unspecified ::/128 or loopback ::1/128
            isAllZero(b, 0, 15) && (b[15] == 0 || b[15] == 1) -> true
            // ULA fc00::/7 or multicast ff00::/8
            (b[0] and 0xFE) == 0xFC || b[0] == 0xFF -> true
            // Link-local fe80::/10 or deprecated site-local fec0::/10
            b[0] == 0xFE && (b[1] and 0x80) == 0x80 -> true
            // Discard-only 0100::/64
            hasBytesPrefix(b, 0x01, 0x00) && isAllZero(b, 2, 8) -> true
            // Documentation 2001:db8::/32
            hasBytesPrefix(b, 0x20, 0x01, 0x0D, 0xB8) -> true
            // Local-use NAT64 64:ff9b:1::/48
            hasBytesPrefix(b, 0x00, 0x64, 0xFF, 0x9B, 0x00, 0x01) -> true
            else -> false
        }

    private fun extractEmbeddedIpv4(
        raw: ByteArray,
        b: IntArray,
    ): ByteArray? =
        when {
            // IPv4-mapped ::ffff:0:0/96
            isAllZero(b, 0, 10) && b[10] == 0xFF && b[11] == 0xFF -> raw.copyOfRange(12, 16)
            // IPv4-compatible ::/96
            isAllZero(b, 0, 12) -> raw.copyOfRange(12, 16)
            // Well-known NAT64 64:ff9b::/96
            hasBytesPrefix(b, 0x00, 0x64, 0xFF, 0x9B) && isAllZero(b, 4, 12) -> raw.copyOfRange(12, 16)
            // 6to4 2002::/16 (embeds IPv4 in bits 16..47)
            hasBytesPrefix(b, 0x20, 0x02) -> raw.copyOfRange(2, 6)
            else -> null
        }

    private fun hasBytesPrefix(
        b: IntArray,
        vararg expected: Int,
    ): Boolean {
        for (i in expected.indices) {
            if (b[i] != expected[i]) return false
        }
        return true
    }

    private fun isAllZero(
        b: IntArray,
        fromInclusive: Int,
        toExclusive: Int,
    ): Boolean {
        for (i in fromInclusive until toExclusive) {
            if (b[i] != 0) return false
        }
        return true
    }
}
