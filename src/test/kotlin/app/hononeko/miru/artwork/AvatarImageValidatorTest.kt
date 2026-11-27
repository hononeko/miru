package app.hononeko.miru.artwork

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
private val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00)
private val WEBP_BYTES = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
private val GIF_BYTES = "GIF89a\u0001\u0000\u0001\u0000".toByteArray(Charsets.ISO_8859_1)

class AvatarImageValidatorTest :
    FunSpec({
        test("detects PNG, JPEG, and WebP from their bytes, whatever content type the client declared") {
            AvatarImageValidator.validate(PNG_BYTES, "image/png").shouldBeRight().contentType shouldBe "image/png"
            AvatarImageValidator.validate(JPEG_BYTES, "IMAGE/JPEG; charset=binary").shouldBeRight().contentType shouldBe "image/jpeg"
            AvatarImageValidator.validate(WEBP_BYTES, null).shouldBeRight().contentType shouldBe "image/webp"
            // A generic or mislabelled image type is fine: the stored type comes from the bytes
            AvatarImageValidator.validate(PNG_BYTES, "application/octet-stream").shouldBeRight().contentType shouldBe "image/png"
            AvatarImageValidator.validate(PNG_BYTES, "image/jpeg").shouldBeRight().contentType shouldBe "image/png"
        }

        test("rejects empty, oversized, unsupported, and disguised images") {
            AvatarImageValidator.validate(ByteArray(0)).shouldBeLeft().fieldErrors shouldContainKey "avatar"

            val oversized = PNG_BYTES + ByteArray(AvatarImageValidator.MAX_AVATAR_BYTES)
            AvatarImageValidator.validate(oversized, "image/png").shouldBeLeft().fieldErrors shouldContainKey "avatar"
            AvatarImageValidator
                .validate(PNG_BYTES + ByteArray(AvatarImageValidator.MAX_AVATAR_BYTES - PNG_BYTES.size), "image/png")
                .shouldBeRight()

            // Imports may be larger than uploads, and the message names the limit that applied
            val overUploadLimit = PNG_BYTES + ByteArray(AvatarImageValidator.MAX_AVATAR_BYTES)
            AvatarImageValidator.validate(overUploadLimit).shouldBeLeft().message shouldBe
                "Avatar image exceeds maximum allowed size of 512 KiB"
            AvatarImageValidator.validate(overUploadLimit, maxBytes = AvatarImageValidator.MAX_IMPORTED_AVATAR_BYTES).shouldBeRight()
            val overImportLimit = PNG_BYTES + ByteArray(AvatarImageValidator.MAX_IMPORTED_AVATAR_BYTES)
            AvatarImageValidator
                .validate(overImportLimit, maxBytes = AvatarImageValidator.MAX_IMPORTED_AVATAR_BYTES)
                .shouldBeLeft()
                .message shouldBe "Avatar image exceeds maximum allowed size of 4 MiB"

            AvatarImageValidator.validate(GIF_BYTES, "image/gif").shouldBeLeft().fieldErrors shouldContainKey "contentType"
            AvatarImageValidator.validate(GIF_BYTES, null).shouldBeLeft().fieldErrors shouldContainKey "avatar"
            AvatarImageValidator
                .validate("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(), "image/png")
                .shouldBeLeft()
                .fieldErrors shouldContainKey "avatar"
            // Too short to carry a full signature
            AvatarImageValidator.validate(PNG_BYTES.copyOf(4), "image/png").shouldBeLeft()
            AvatarImageValidator.validate(WEBP_BYTES.copyOf(8), "image/webp").shouldBeLeft()
        }

        test("compares by content and never prints the image bytes") {
            val first = AvatarImageValidator.validate(PNG_BYTES).shouldBeRight()
            val second = AvatarImageValidator.validate(PNG_BYTES.copyOf()).shouldBeRight()
            first shouldBe second
            first.hashCode() shouldBe second.hashCode()
            first.toString() shouldBe "ValidatedAvatarImage(size=${PNG_BYTES.size}, contentType=image/png)"
            first.toString() shouldNotContain "["
        }
    })
