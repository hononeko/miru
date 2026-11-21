package app.hononeko.miru.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TokenCrypto {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val API_KEY_PREFIX_LENGTH = 12
    private val secureRandom = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val hexFormat: HexFormat = HexFormat.of()

    fun generateToken(byteCount: Int = 32): String {
        require(byteCount > 0) { "byteCount must be > 0" }
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return hexFormat.formatHex(digest)
    }

    fun signSessionToken(
        rawToken: String,
        secret: String,
    ): String {
        require(rawToken.isNotEmpty()) { "rawToken must not be empty" }
        require(secret.isNotEmpty()) { "secret must not be empty" }
        val sig = urlEncoder.encodeToString(computeHmac(rawToken, secret))
        return "$rawToken.$sig"
    }

    fun verifySignedSessionToken(
        signed: String,
        secret: String,
    ): String? {
        if (secret.isEmpty() || signed.isEmpty()) return null
        val dotIndex = signed.indexOf('.')
        if (dotIndex <= 0 || dotIndex >= signed.length - 1 || signed.indexOf('.', dotIndex + 1) != -1) {
            return null
        }

        val rawToken = signed.substring(0, dotIndex)
        val providedSig = signed.substring(dotIndex + 1)
        val expectedSig = urlEncoder.encodeToString(computeHmac(rawToken, secret))
        val isMatch =
            MessageDigest.isEqual(
                expectedSig.toByteArray(Charsets.US_ASCII),
                providedSig.toByteArray(Charsets.US_ASCII),
            )
        return if (isMatch) rawToken else null
    }

    fun generateApiKey(): Pair<String, String> {
        val rawKey = "miru_${generateToken()}"
        val prefix = rawKey.take(API_KEY_PREFIX_LENGTH)
        return rawKey to prefix
    }

    private fun computeHmac(
        payload: String,
        secret: String,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }
}
