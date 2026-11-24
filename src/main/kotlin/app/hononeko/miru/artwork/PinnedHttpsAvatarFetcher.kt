package app.hononeko.miru.artwork

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val EXTERNAL_AVATAR_PROVIDER = "avatar"
private const val ARTWORK_ACCEPT_HEADER = "image/*, */*;q=0.8"
private const val READ_BUFFER_BYTES = 8_192
private const val HTTPS_DEFAULT_PORT = 443
private const val REDIRECT_MIN = 300
private const val REDIRECT_MAX = 399
private const val HTTP_OK_MAX = 299

data class ExternalAvatarHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val contentLength: Long?,
    val locationHeader: String?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExternalAvatarHttpResponse) return false
        return statusCode == other.statusCode &&
            contentType == other.contentType &&
            contentLength == other.contentLength &&
            locationHeader == other.locationHeader &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (contentLength?.hashCode() ?: 0)
        result = 31 * result + (locationHeader?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

fun interface ExternalAvatarFetcher {
    suspend fun get(
        url: String,
        pinnedAddresses: List<InetAddress>,
    ): Either<MiruError, ExternalAvatarHttpResponse>
}

internal class HttpClientExternalAvatarFetcher(
    private val client: HttpClient,
) : ExternalAvatarFetcher {
    override suspend fun get(
        url: String,
        pinnedAddresses: List<InetAddress>,
    ): Either<MiruError, ExternalAvatarHttpResponse> =
        try {
            val response =
                client.get(url) {
                    header(HttpHeaders.Accept, ARTWORK_ACCEPT_HEADER)
                }
            ExternalAvatarHttpResponse(
                statusCode = response.status.value,
                contentType = response.contentType()?.toString(),
                contentLength = response.contentLength(),
                locationHeader = response.headers[HttpHeaders.Location],
                bytes = response.bodyAsBytes(),
            ).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                ExternalAvatarHttpResponse(
                    statusCode = HttpStatusCode.NotFound.value,
                    contentType = null,
                    contentLength = null,
                    locationHeader = null,
                    bytes = ByteArray(0),
                ).right()
            } else {
                MiruError
                    .UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar host returned HTTP ${e.response.status.value}",
                        cause = e,
                    ).left()
            }
        } catch (e: Exception) {
            MiruError
                .UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "Failed to fetch external user avatar",
                    cause = e,
                ).left()
        }
}

/**
 * Fetches external HTTPS avatars directly against the DNS-validated [InetAddress] while preserving
 * TLS SNI, certificate validation (`HTTPS` endpoint identification), and zero proxy usage so DNS
 * rebinding cannot redirect an outbound connection to a private/loopback address.
 */
class PinnedHttpsAvatarFetcher(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Int = DEFAULT_REQUEST_TIMEOUT_MS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val sslContext: SSLContext = SSLContext.getDefault(),
) : ExternalAvatarFetcher {
    override suspend fun get(
        url: String,
        pinnedAddresses: List<InetAddress>,
    ): Either<MiruError, ExternalAvatarHttpResponse> =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            val expectedHost = uri.host?.lowercase()?.trimEnd('.').orEmpty()
            var lastFailure: Exception? = null

            for (address in pinnedAddresses) {
                try {
                    return@withContext executePinnedGet(uri = uri, expectedHost = expectedHost, pinnedAddress = address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastFailure = e
                }
            }

            MiruError
                .UpstreamUnavailable(
                    provider = EXTERNAL_AVATAR_PROVIDER,
                    message = "Failed to fetch external user avatar",
                    cause = lastFailure,
                ).left()
        }

    private fun executePinnedGet(
        uri: URI,
        expectedHost: String,
        pinnedAddress: InetAddress,
    ): Either<MiruError, ExternalAvatarHttpResponse> {
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = requestTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty(HttpHeaders.Accept, ARTWORK_ACCEPT_HEADER)
            connection.setRequestProperty(HttpHeaders.Connection, "close")
            connection.sslSocketFactory =
                PinnedIpSSLSocketFactory(
                    delegate = sslContext.socketFactory,
                    pinnedAddress = pinnedAddress,
                    expectedHost = expectedHost,
                    connectTimeoutMs = connectTimeoutMs,
                )
            connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

            connection.connect()

            val statusCode = connection.responseCode
            val contentType = connection.contentType
            val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
            val locationHeader = connection.getHeaderField(HttpHeaders.Location)

            if (statusCode in REDIRECT_MIN..REDIRECT_MAX || statusCode > HTTP_OK_MAX) {
                return ExternalAvatarHttpResponse(
                    statusCode = statusCode,
                    contentType = contentType,
                    contentLength = contentLength,
                    locationHeader = locationHeader,
                    bytes = ByteArray(0),
                ).right()
            }

            if (contentLength != null && contentLength > maxBytes) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar payload exceeds maximum size",
                    ).left()
            }

            val bytes = readBoundedBytes(connection.inputStream, maxBytes)
            if (bytes == null) {
                return MiruError
                    .UpstreamUnavailable(
                        provider = EXTERNAL_AVATAR_PROVIDER,
                        message = "External avatar payload exceeds maximum size",
                    ).left()
            }

            return ExternalAvatarHttpResponse(
                statusCode = statusCode,
                contentType = contentType,
                contentLength = contentLength,
                locationHeader = locationHeader,
                bytes = bytes,
            ).right()
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedBytes(
        input: InputStream,
        limit: Int,
    ): ByteArray? {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) {
                    return null
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
    }
}

internal class PinnedPlainSocket(
    private val pinnedAddress: InetAddress,
    private val fallbackPort: Int = HTTPS_DEFAULT_PORT,
) : Socket() {
    override fun connect(endpoint: SocketAddress) {
        connect(endpoint, 0)
    }

    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        val port = (endpoint as? InetSocketAddress)?.port?.takeIf { it > 0 } ?: fallbackPort
        super.connect(InetSocketAddress(pinnedAddress, port), timeout)
    }
}

internal class PinnedIpSSLSocketFactory(
    private val delegate: SSLSocketFactory,
    private val pinnedAddress: InetAddress,
    private val expectedHost: String,
    private val connectTimeoutMs: Int,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    /**
     * OpenJDK `HttpsClient.createSocket()` calls 0-arg `createSocket()`, connects via `NetworkClient.doConnect`,
     * and then wraps the connected socket in `HttpsClient.afterConnect()` using the 4-arg overload below.
     * Returning [PinnedPlainSocket] ensures `doConnect` connects directly to [pinnedAddress] without DNS resolution.
     */
    override fun createSocket(): Socket = PinnedPlainSocket(pinnedAddress)

    override fun createSocket(
        s: Socket,
        host: String,
        port: Int,
        autoClose: Boolean,
    ): Socket = configureSsl(delegate.createSocket(s, expectedHost, port, autoClose) as SSLSocket)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = connectPinnedSsl(port = port, localAddress = null, localPort = 0)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = connectPinnedSsl(port = port, localAddress = localHost, localPort = localPort)

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = connectPinnedSsl(port = port, localAddress = null, localPort = 0)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = connectPinnedSsl(port = port, localAddress = localAddress, localPort = localPort)

    private fun connectPinnedSsl(
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): SSLSocket {
        val raw = PinnedPlainSocket(pinnedAddress, fallbackPort = port)
        try {
            if (localAddress != null) {
                raw.bind(InetSocketAddress(localAddress, localPort))
            }
            raw.connect(InetSocketAddress(pinnedAddress, port), connectTimeoutMs)
            return configureSsl(delegate.createSocket(raw, expectedHost, port, true) as SSLSocket)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    internal fun configureSsl(socket: SSLSocket): SSLSocket {
        val params = socket.sslParameters
        if (expectedHost.isNotBlank()) {
            params.serverNames = listOf(SNIHostName(expectedHost))
        }
        params.endpointIdentificationAlgorithm = "HTTPS"
        socket.sslParameters = params
        return socket
    }
}
