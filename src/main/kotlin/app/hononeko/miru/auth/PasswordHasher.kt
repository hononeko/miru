package app.hononeko.miru.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

interface PasswordHasher {
    fun hash(password: String): String

    fun verify(
        password: String,
        encodedHash: String,
    ): Boolean
}

data class Argon2Params(
    val memoryKb: Int = 19_456,
    val iterations: Int = 2,
    val parallelism: Int = 1,
    val saltBytes: Int = 16,
    val hashBytes: Int = 32,
) {
    init {
        require(memoryKb > 0) { "memoryKb must be > 0" }
        require(iterations > 0) { "iterations must be > 0" }
        require(parallelism > 0) { "parallelism must be > 0" }
        require(saltBytes >= 8) { "saltBytes must be >= 8" }
        require(hashBytes >= 16) { "hashBytes must be >= 16" }
    }
}

class Argon2idPasswordHasher(
    private val params: Argon2Params = Argon2Params(),
    private val secureRandom: SecureRandom = SecureRandom(),
) : PasswordHasher {
    private val base64Encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val base64Decoder: Base64.Decoder = Base64.getDecoder()

    override fun hash(password: String): String {
        val salt = ByteArray(params.saltBytes)
        secureRandom.nextBytes(salt)

        val hash =
            computeArgon2id(
                password = password,
                salt = salt,
                memoryKb = params.memoryKb,
                iterations = params.iterations,
                parallelism = params.parallelism,
                outputLength = params.hashBytes,
            )

        val encodedSalt = base64Encoder.encodeToString(salt)
        val encodedHash = base64Encoder.encodeToString(hash)
        return "\$argon2id\$v=19\$m=${params.memoryKb},t=${params.iterations},p=${params.parallelism}\$$encodedSalt\$$encodedHash"
    }

    override fun verify(
        password: String,
        encodedHash: String,
    ): Boolean {
        val parsed = parsePhcHash(encodedHash) ?: return false
        val actualHash =
            computeArgon2id(
                password = password,
                salt = parsed.salt,
                memoryKb = parsed.memoryKb,
                iterations = parsed.iterations,
                parallelism = parsed.parallelism,
                outputLength = parsed.expectedHash.size,
            )
        return MessageDigest.isEqual(actualHash, parsed.expectedHash)
    }

    private fun computeArgon2id(
        password: String,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int,
        outputLength: Int,
    ): ByteArray {
        val passwordChars = password.toCharArray()
        return try {
            val parameters =
                Argon2Parameters
                    .Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memoryKb)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .build()
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            val output = ByteArray(outputLength)
            generator.generateBytes(passwordChars, output)
            output
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun parsePhcHash(encoded: String): ParsedPhcHash? {
        if (encoded.isBlank()) return null
        val parts = encoded.split('$')
        if (parts.size != PHC_PART_COUNT || parts[0].isNotEmpty()) return null
        if (parts[1] != "argon2id" || parts[2] != "v=19") return null

        val paramMap = parseParams(parts[3]) ?: return null
        val memoryKb = paramMap["m"] ?: return null
        val iterations = paramMap["t"] ?: return null
        val parallelism = paramMap["p"] ?: return null
        if (memoryKb <= 0 || iterations <= 0 || parallelism <= 0) return null

        val salt = decodeBase64(parts[4]) ?: return null
        val expectedHash = decodeBase64(parts[5]) ?: return null
        if (salt.isEmpty() || expectedHash.size < MIN_ARGON2_HASH_BYTES) return null

        return ParsedPhcHash(
            memoryKb = memoryKb,
            iterations = iterations,
            parallelism = parallelism,
            salt = salt,
            expectedHash = expectedHash,
        )
    }

    private fun parseParams(raw: String): Map<String, Int>? {
        val entries = raw.split(',')
        if (entries.size != 3) return null
        val result = mutableMapOf<String, Int>()
        for (entry in entries) {
            val pair = entry.split('=')
            if (pair.size != 2) return null
            val value = pair[1].toIntOrNull() ?: return null
            result[pair[0]] = value
        }
        return result
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            if (value.isEmpty()) null else base64Decoder.decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private data class ParsedPhcHash(
        val memoryKb: Int,
        val iterations: Int,
        val parallelism: Int,
        val salt: ByteArray,
        val expectedHash: ByteArray,
    )

    private companion object {
        const val PHC_PART_COUNT = 6
        const val MIN_ARGON2_HASH_BYTES = 4
    }
}
