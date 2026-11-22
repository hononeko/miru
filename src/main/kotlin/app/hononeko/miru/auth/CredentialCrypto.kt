package app.hononeko.miru.auth

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM credential encryption at rest using a key derived via SHA-256 from `MIRU_ENCRYPTION_KEY`.
 */
object CredentialCrypto {
    const val MIN_KEY_LENGTH = 32
    private const val VERSION_PREFIX = "v1:"
    private const val AES_ALGORITHM = "AES"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8

    private val secureRandom = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun isValidKey(encryptionKey: String?): Boolean =
        encryptionKey != null && encryptionKey.length >= MIN_KEY_LENGTH

    fun validateKey(encryptionKey: String?): Either<MiruError.ValidationError, String> =
        either {
            val key = encryptionKey?.takeIf { it.isNotBlank() }
            ensure(key != null && key.length >= MIN_KEY_LENGTH) {
                MiruError.ValidationError(
                    message = "MIRU_ENCRYPTION_KEY must be configured and at least $MIN_KEY_LENGTH characters",
                    fieldErrors =
                        mapOf(
                            "encryptionKey" to "MIRU_ENCRYPTION_KEY must be at least $MIN_KEY_LENGTH characters",
                        ),
                )
            }
            key
        }

    fun encrypt(
        plaintext: String,
        encryptionKey: String?,
    ): Either<MiruError, String> =
        either {
            val key = validateKey(encryptionKey).bind()
            ensure(plaintext.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Credential plaintext must not be empty",
                    fieldErrors = mapOf("plaintext" to "must not be empty"),
                )
            }

            val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
            val ciphertextAndTag =
                try {
                    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, deriveKeySpec(key), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                    cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                } catch (_: GeneralSecurityException) {
                    raise(MiruError.InternalError("Failed to encrypt credential"))
                }

            val ivEncoded = urlEncoder.encodeToString(iv)
            val ctEncoded = urlEncoder.encodeToString(ciphertextAndTag)
            "$VERSION_PREFIX$ivEncoded.$ctEncoded"
        }

    fun decrypt(
        encrypted: String,
        encryptionKey: String?,
    ): Either<MiruError, String> =
        either {
            val key = validateKey(encryptionKey).bind()
            ensure(encrypted.startsWith(VERSION_PREFIX)) {
                MiruError.InternalError("Unsupported encrypted credential format")
            }

            val payload = encrypted.removePrefix(VERSION_PREFIX)
            val dotIndex = payload.indexOf('.')
            ensure(dotIndex > 0 && dotIndex < payload.length - 1 && payload.indexOf('.', dotIndex + 1) == -1) {
                MiruError.InternalError("Malformed encrypted credential payload")
            }

            val iv = decodeBase64Url(payload.substring(0, dotIndex)).bind()
            val ciphertextAndTag = decodeBase64Url(payload.substring(dotIndex + 1)).bind()

            ensure(iv.size == IV_LENGTH_BYTES && ciphertextAndTag.size > TAG_LENGTH_BYTES) {
                MiruError.InternalError("Invalid encrypted credential envelope")
            }

            val plaintextBytes =
                try {
                    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, deriveKeySpec(key), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                    cipher.doFinal(ciphertextAndTag)
                } catch (_: GeneralSecurityException) {
                    raise(MiruError.InternalError("Failed to decrypt stored credential"))
                }

            String(plaintextBytes, Charsets.UTF_8)
        }

    private fun decodeBase64Url(raw: String): Either<MiruError.InternalError, ByteArray> =
        try {
            Either.Right(urlDecoder.decode(raw))
        } catch (_: IllegalArgumentException) {
            Either.Left(MiruError.InternalError("Malformed base64url segment in encrypted credential"))
        }

    private fun deriveKeySpec(encryptionKey: String): SecretKeySpec {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(encryptionKey.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, AES_ALGORITHM)
    }
}
