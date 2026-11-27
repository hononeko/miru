package app.hononeko.miru.artwork

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

data class ValidatedAvatarImage(
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ValidatedAvatarImage) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()

    override fun toString(): String = "ValidatedAvatarImage(size=${bytes.size}, contentType=$contentType)"
}

/**
 * Validates uploaded or imported user avatar images by size and magic bytes (PNG, JPEG, or WebP)
 * rather than trusting client Content-Type headers alone.
 */
object AvatarImageValidator {
    /** Largest avatar a person may upload. */
    const val MAX_AVATAR_BYTES = 512 * 1024

    /**
     * Largest avatar imported from a media server and stored as is. Jellyfin cannot scale user images,
     * and keeps the original upload.
     */
    const val MAX_IMPORTED_AVATAR_BYTES = 4 * 1024 * 1024
    private const val BYTES_PER_KIB = 1024
    private const val MIN_JPEG_BYTES = 3
    private const val MIN_PNG_BYTES = 8
    private const val MIN_WEBP_BYTES = 12
    private const val BYTE_MASK = 0xFF
    private const val JPEG_MAGIC_0 = 0xFF
    private const val JPEG_MAGIC_1 = 0xD8
    private const val JPEG_MAGIC_2 = 0xFF
    private const val PNG_MAGIC_0 = 0x89
    private const val PNG_MAGIC_1 = 0x50 // 'P'
    private const val PNG_MAGIC_2 = 0x4E // 'N'
    private const val PNG_MAGIC_3 = 0x47 // 'G'
    private const val PNG_MAGIC_4 = 0x0D
    private const val PNG_MAGIC_5 = 0x0A
    private const val PNG_MAGIC_6 = 0x1A
    private const val PNG_MAGIC_7 = 0x0A
    private const val RIFF_R = 0x52
    private const val RIFF_I = 0x49
    private const val RIFF_F = 0x46
    private const val WEBP_W = 0x57
    private const val WEBP_E = 0x45
    private const val WEBP_B = 0x42
    private const val WEBP_P = 0x50
    private const val WEBP_TAG_OFFSET = 8

    private val ALLOWED_DECLARED_TYPES =
        setOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
        )

    private val GENERIC_DECLARED_TYPES =
        setOf(
            "application/octet-stream",
            "*/*",
        )

    fun validate(
        bytes: ByteArray,
        declaredContentType: String? = null,
        maxBytes: Int = MAX_AVATAR_BYTES,
    ): Either<MiruError.ValidationError, ValidatedAvatarImage> =
        either {
            ensure(bytes.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Avatar image must not be empty",
                    fieldErrors = mapOf("avatar" to "must not be empty"),
                )
            }
            ensure(bytes.size <= maxBytes) {
                MiruError.ValidationError(
                    message = "Avatar image exceeds maximum allowed size of ${sizeLabel(maxBytes)}",
                    fieldErrors = mapOf("avatar" to "must be at most $maxBytes bytes"),
                )
            }

            val normalizedDeclared =
                declaredContentType
                    ?.trim()
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() }

            if (normalizedDeclared != null && normalizedDeclared !in GENERIC_DECLARED_TYPES) {
                ensure(normalizedDeclared in ALLOWED_DECLARED_TYPES) {
                    MiruError.ValidationError(
                        message = "Unsupported avatar image content type '$normalizedDeclared'",
                        fieldErrors = mapOf("contentType" to "must be image/png, image/jpeg, or image/webp"),
                    )
                }
            }

            val detectedContentType =
                detectImageContentType(bytes)
                    ?: raise(
                        MiruError.ValidationError(
                            message = "Unsupported or invalid avatar image format; expected PNG, JPEG, or WebP",
                            fieldErrors = mapOf("avatar" to "must be a valid PNG, JPEG, or WebP image"),
                        ),
                    )

            ValidatedAvatarImage(
                bytes = bytes,
                contentType = detectedContentType,
            )
        }

    private fun sizeLabel(bytes: Int): String {
        val kib = bytes / BYTES_PER_KIB
        return if (kib % BYTES_PER_KIB == 0) "${kib / BYTES_PER_KIB} MiB" else "$kib KiB"
    }

    fun detectImageContentType(bytes: ByteArray): String? =
        when {
            isPng(bytes) -> "image/png"
            isJpeg(bytes) -> "image/jpeg"
            isWebp(bytes) -> "image/webp"
            else -> null
        }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= MIN_PNG_BYTES &&
            (bytes[0].toInt() and BYTE_MASK) == PNG_MAGIC_0 &&
            (bytes[1].toInt() and BYTE_MASK) == PNG_MAGIC_1 &&
            (bytes[2].toInt() and BYTE_MASK) == PNG_MAGIC_2 &&
            (bytes[3].toInt() and BYTE_MASK) == PNG_MAGIC_3 &&
            (bytes[4].toInt() and BYTE_MASK) == PNG_MAGIC_4 &&
            (bytes[5].toInt() and BYTE_MASK) == PNG_MAGIC_5 &&
            (bytes[6].toInt() and BYTE_MASK) == PNG_MAGIC_6 &&
            (bytes[7].toInt() and BYTE_MASK) == PNG_MAGIC_7

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= MIN_JPEG_BYTES &&
            (bytes[0].toInt() and BYTE_MASK) == JPEG_MAGIC_0 &&
            (bytes[1].toInt() and BYTE_MASK) == JPEG_MAGIC_1 &&
            (bytes[2].toInt() and BYTE_MASK) == JPEG_MAGIC_2

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= MIN_WEBP_BYTES &&
            (bytes[0].toInt() and BYTE_MASK) == RIFF_R &&
            (bytes[1].toInt() and BYTE_MASK) == RIFF_I &&
            (bytes[2].toInt() and BYTE_MASK) == RIFF_F &&
            (bytes[3].toInt() and BYTE_MASK) == RIFF_F &&
            (bytes[WEBP_TAG_OFFSET].toInt() and BYTE_MASK) == WEBP_W &&
            (bytes[WEBP_TAG_OFFSET + 1].toInt() and BYTE_MASK) == WEBP_E &&
            (bytes[WEBP_TAG_OFFSET + 2].toInt() and BYTE_MASK) == WEBP_B &&
            (bytes[WEBP_TAG_OFFSET + 3].toInt() and BYTE_MASK) == WEBP_P
}
