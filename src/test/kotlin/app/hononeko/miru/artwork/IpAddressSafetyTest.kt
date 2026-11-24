package app.hononeko.miru.artwork

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.toProblemDetails
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

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

        test("PinnedHttpsAvatarFetcher fetches, handles redirects/404, enforces byte limits, and verifies TLS hostname") {
            val okBytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4)
            val loopback = InetAddress.getLoopbackAddress()
            val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }

            withLoopbackTlsServer(okBytes) { sslContext, port ->
                val fetcher =
                    PinnedHttpsAvatarFetcher(
                        connectTimeoutMs = 2_000,
                        requestTimeoutMs = 2_000,
                        maxBytes = 32,
                        sslContext = sslContext,
                    )
                val pinned = listOf(loopback)

                val ok = fetcher.get("https://plex.tv:$port/ok", pinned).shouldBeRight()
                ok.statusCode shouldBe 200
                ok.contentType shouldBe "image/png"
                ok.contentLength shouldBe okBytes.size.toLong()
                ok.locationHeader shouldBe null
                ok.bytes shouldBe okBytes

                val redir = fetcher.get("https://plex.tv:$port/redir", pinned).shouldBeRight()
                redir.statusCode shouldBe 302
                redir.locationHeader shouldBe "https://secure.gravatar.com/a.png"
                redir.bytes shouldBe ByteArray(0)

                val missing = fetcher.get("https://plex.tv:$port/missing", pinned).shouldBeRight()
                missing.statusCode shouldBe 404
                missing.bytes shouldBe ByteArray(0)

                fetcher
                    .get("https://plex.tv:$port/too-big-header", pinned)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                    .message shouldContain "exceeds maximum size"

                fetcher
                    .get("https://plex.tv:$port/too-big-stream", pinned)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                    .message shouldContain "exceeds maximum size"

                fetcher
                    .get("https://secure.gravatar.com:$port/ok", pinned)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                fetcher
                    .get("https://plex.tv:$closedPort/ok", pinned)
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                PinnedHttpsAvatarFetcher()
                    .get("https://plex.tv:$port/ok", emptyList())
                    .shouldBeLeft()
                    .shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            }
        }

        test("PinnedIpSSLSocketFactory delegates cipher suites and pins all createSocket overloads") {
            val loopback = InetAddress.getLoopbackAddress()
            val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }

            ServerSocket(0, 1, loopback).use { plainServer ->
                PinnedPlainSocket(loopback, plainServer.localPort).use { plain ->
                    plain.connect(InetSocketAddress.createUnresolved("rebinding.invalid", plainServer.localPort))
                    plain.inetAddress shouldBe loopback
                }
                plainServer.accept().close()
            }

            withLoopbackTlsServer(byteArrayOf(1, 2, 3)) { sslContext, port ->
                val delegate = sslContext.socketFactory
                val factory =
                    PinnedIpSSLSocketFactory(
                        delegate = delegate,
                        pinnedAddress = loopback,
                        expectedHost = "plex.tv",
                        connectTimeoutMs = 2_000,
                    )

                factory.defaultCipherSuites.toList() shouldBe delegate.defaultCipherSuites.toList()
                factory.supportedCipherSuites.toList() shouldBe delegate.supportedCipherSuites.toList()

                val createdSockets =
                    listOf(
                        factory.createSocket("untrusted.invalid", port),
                        factory.createSocket("untrusted.invalid", port, loopback, 0),
                        factory.createSocket(InetAddress.getByName("93.184.216.34"), port),
                        factory.createSocket(InetAddress.getByName("93.184.216.34"), port, loopback, 0),
                    )
                createdSockets.forEach { raw ->
                    val ssl = raw.shouldBeInstanceOf<SSLSocket>()
                    ssl.use { socket ->
                        socket.startHandshake()
                        socket.inetAddress shouldBe loopback
                        socket.port shouldBe port
                        socket.session.isValid shouldBe true
                    }
                }

                shouldThrow<IOException> {
                    factory.createSocket("plex.tv", closedPort)
                }
            }
        }

        test("ExternalAvatarHttpResponse implements structural equality and hashCode") {
            val base =
                ExternalAvatarHttpResponse(
                    statusCode = 200,
                    contentType = "image/png",
                    contentLength = 3L,
                    locationHeader = "https://plex.tv/a.png",
                    bytes = byteArrayOf(1, 2, 3),
                )
            val equalCopy = base.copy(bytes = byteArrayOf(1, 2, 3))
            val nullRef: Any? = null
            val otherType: Any = "other"

            base.equals(base) shouldBe true
            (base == equalCopy) shouldBe true
            base.hashCode() shouldBe equalCopy.hashCode()

            (base == base.copy(statusCode = 404)) shouldBe false
            (base == base.copy(contentType = "image/jpeg")) shouldBe false
            (base == base.copy(contentLength = 4L)) shouldBe false
            (base == base.copy(locationHeader = "https://secure.gravatar.com/b.png")) shouldBe false
            (base == base.copy(bytes = byteArrayOf(1, 2, 4))) shouldBe false
            base.equals(nullRef) shouldBe false
            base.equals(otherType) shouldBe false

            val emptyFields = ExternalAvatarHttpResponse(404, null, null, null, ByteArray(0))
            (emptyFields == emptyFields.copy(bytes = ByteArray(0))) shouldBe true
            emptyFields.hashCode() shouldBe emptyFields.copy(bytes = ByteArray(0)).hashCode()
            emptyFields.hashCode() shouldNotBe base.hashCode()
        }
    })

private suspend fun withLoopbackTlsServer(
    okBytes: ByteArray,
    block: suspend (sslContext: SSLContext, port: Int) -> Unit,
) {
    val tempDir = Files.createTempDirectory("miru-tls-test-")
    try {
        val password = "changeit".toCharArray()
        val keystorePath = generateLoopbackKeystore(tempDir, password)
        val sslContext = loadLoopbackSslContext(keystorePath, password)
        val serverSocket =
            sslContext.serverSocketFactory.createServerSocket(
                0,
                5,
                InetAddress.getLoopbackAddress(),
            ) as SSLServerSocket
        serverSocket.use { server ->
            val thread =
                Thread({ serveLoopbackTlsRequests(server, okBytes) }, "loopback-tls-test-server").apply {
                    isDaemon = true
                    start()
                }
            try {
                block(sslContext, server.localPort)
            } finally {
                server.close()
                thread.join(2_000)
            }
        }
    } finally {
        tempDir.toFile().deleteRecursively()
    }
}

private fun generateLoopbackKeystore(
    tempDir: Path,
    password: CharArray,
): Path {
    val keystorePath = tempDir.resolve("test-keystore.p12")
    val keytoolPath = File(System.getProperty("java.home"), "bin/keytool").absolutePath
    val pass = String(password)
    val process =
        ProcessBuilder(
            keytoolPath,
            "-genkeypair",
            "-alias",
            "test",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "1",
            "-dname",
            "CN=plex.tv",
            "-ext",
            "san=dns:plex.tv",
            "-storetype",
            "PKCS12",
            "-keystore",
            keystorePath.toString(),
            "-storepass",
            pass,
            "-keypass",
            pass,
        ).redirectErrorStream(true).start()
    process.outputStream.close()
    val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    val exitCode = process.waitFor()
    check(exitCode == 0) { "keytool failed with exit $exitCode: $output" }
    return keystorePath
}

private fun loadLoopbackSslContext(
    keystorePath: Path,
    password: CharArray,
): SSLContext {
    val keyStore =
        KeyStore.getInstance("PKCS12").apply {
            keystorePath.toFile().inputStream().use { load(it, password) }
        }
    val kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
    val tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
    return SSLContext.getInstance("TLS").apply {
        init(kmf.keyManagers, tmf.trustManagers, null)
    }
}

private fun serveLoopbackTlsRequests(
    serverSocket: SSLServerSocket,
    okBytes: ByteArray,
) {
    while (!serverSocket.isClosed) {
        val accepted =
            try {
                serverSocket.accept() as SSLSocket
            } catch (_: SocketException) {
                break
            }
        accepted.use { socket ->
            runCatching { handleLoopbackTlsConnection(socket, okBytes) }
        }
    }
}

private fun handleLoopbackTlsConnection(
    socket: SSLSocket,
    okBytes: ByteArray,
) {
    socket.soTimeout = 5_000
    val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII))
    val requestLine = reader.readLine() ?: return
    while (true) {
        val headerLine = reader.readLine()
        if (headerLine.isNullOrEmpty()) break
    }
    val path = requestLine.split(' ').getOrNull(1).orEmpty()
    val out = socket.outputStream
    when (path) {
        "/ok" -> writeHttpResponse(out, "200 OK", listOf("Content-Type: image/png", "Content-Length: ${okBytes.size}"), okBytes)
        "/redir" ->
            writeHttpResponse(
                out,
                "302 Found",
                listOf("Location: https://secure.gravatar.com/a.png", "Content-Length: 0"),
            )
        "/missing" -> writeHttpResponse(out, "404 Not Found", listOf("Content-Length: 0"))
        "/too-big-header" ->
            writeHttpResponse(out, "200 OK", listOf("Content-Type: image/png", "Content-Length: 1024"))
        "/too-big-stream" ->
            writeHttpResponse(
                out,
                "200 OK",
                listOf("Content-Type: image/png"),
                ByteArray(64) { 0x2A },
            )
        else -> writeHttpResponse(out, "400 Bad Request", listOf("Content-Length: 0"))
    }
    out.flush()
}

private fun writeHttpResponse(
    out: java.io.OutputStream,
    status: String,
    headers: List<String>,
    body: ByteArray = ByteArray(0),
) {
    val head =
        buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
    out.write(head.toByteArray(StandardCharsets.US_ASCII))
    if (body.isNotEmpty()) {
        out.write(body)
    }
}
