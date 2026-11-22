package app.hononeko.miru.auth

import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf

class CredentialCryptoTest :
    FunSpec({
        val validKey = "0123456789abcdef0123456789abcdef"
        val otherKey = "fedcba9876543210fedcba9876543210"

        test("encrypts and decrypts round-trip with v1 AES-GCM envelope and unique IVs") {
            val secretToken = "plex-super-secret-oauth-token-12345"

            val enc1 = CredentialCrypto.encrypt(secretToken, validKey).shouldBeRight()
            val enc2 = CredentialCrypto.encrypt(secretToken, validKey).shouldBeRight()

            enc1 shouldStartWith "v1:"
            enc2 shouldStartWith "v1:"
            enc1 shouldNotBe enc2
            enc1 shouldNotContain secretToken
            enc1 shouldNotContain validKey

            CredentialCrypto.decrypt(enc1, validKey).shouldBeRight() shouldBe secretToken
            CredentialCrypto.decrypt(enc2, validKey).shouldBeRight() shouldBe secretToken
        }

        test("rejects tampered ciphertext or IV without leaking key or plaintext") {
            val secretToken = "plex-token-tamper-check"
            val encrypted = CredentialCrypto.encrypt(secretToken, validKey).shouldBeRight()
            val lastChar = encrypted.last()
            val flipped = if (lastChar == 'A') 'B' else 'A'
            val tamperedCt = encrypted.dropLast(1) + flipped

            val err = CredentialCrypto.decrypt(tamperedCt, validKey).shouldBeLeft()
            err.shouldBeInstanceOf<MiruError.InternalError>()
            err.message shouldNotContain secretToken
            err.message shouldNotContain validKey
        }

        test("fails decryption with wrong encryption key") {
            val secretToken = "plex-token-wrong-key"
            val encrypted = CredentialCrypto.encrypt(secretToken, validKey).shouldBeRight()

            val err = CredentialCrypto.decrypt(encrypted, otherKey).shouldBeLeft()
            err.shouldBeInstanceOf<MiruError.InternalError>()
            err.message shouldNotContain secretToken
            err.message shouldNotContain validKey
            err.message shouldNotContain otherKey
        }

        test("validates encryption key length and rejects malformed payloads") {
            CredentialCrypto.encrypt("tok", null).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            CredentialCrypto.encrypt("tok", "   ").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            CredentialCrypto.encrypt("tok", "too-short-key").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            CredentialCrypto.encrypt("", validKey).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()

            CredentialCrypto.decrypt("v2:abc.def", validKey).shouldBeLeft()
            CredentialCrypto.decrypt("v1:invalid", validKey).shouldBeLeft()
            CredentialCrypto.decrypt("v1:not-base64!.also-bad!", validKey).shouldBeLeft()
        }
    })
