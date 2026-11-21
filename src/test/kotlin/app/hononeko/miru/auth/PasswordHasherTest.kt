package app.hononeko.miru.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

class PasswordHasherTest :
    FunSpec({
        test("uses OWASP Argon2id defaults and produces PHC formatted hashes") {
            val defaults = Argon2Params()
            defaults.memoryKb shouldBe 19_456
            defaults.iterations shouldBe 2
            defaults.parallelism shouldBe 1
            defaults.saltBytes shouldBe 16
            defaults.hashBytes shouldBe 32

            val hasher = Argon2idPasswordHasher()
            val encoded = hasher.hash("correct-horse-battery-staple")
            encoded shouldStartWith "\$argon2id\$v=19\$m=19456,t=2,p=1\$"
            encoded.split('$').size shouldBe 6

            hasher.verify("correct-horse-battery-staple", encoded) shouldBe true
            hasher.verify("wrong-password", encoded) shouldBe false
        }

        test("generates unique salts per hash") {
            val hasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1))
            val h1 = hasher.hash("same-password-123")
            val h2 = hasher.hash("same-password-123")

            h1 shouldNotBe h2
            hasher.verify("same-password-123", h1) shouldBe true
            hasher.verify("same-password-123", h2) shouldBe true
        }

        test("rejects malformed, unsupported, or tampered PHC hashes safely") {
            val hasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1))
            val valid = hasher.hash("valid-password-123")
            val parts = valid.split('$')
            val tamperedHash = parts.dropLast(1).joinToString("$") + "\$AAAA"

            hasher.verify("valid-password-123", "") shouldBe false
            hasher.verify("valid-password-123", "not-a-phc-hash") shouldBe false
            hasher.verify("valid-password-123", "\$bcrypt\$v=19\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA") shouldBe false
            hasher.verify("valid-password-123", "\$argon2id\$v=16\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA") shouldBe false
            hasher.verify("valid-password-123", "\$argon2id\$v=19\$m=0,t=1,p=1\$c2FsdA\$aGFzaA") shouldBe false
            hasher.verify("valid-password-123", "\$argon2id\$v=19\$m=1024,t=1\$c2FsdA\$aGFzaA") shouldBe false
            hasher.verify("valid-password-123", "\$argon2id\$v=19\$m=1024,t=1,p=1\$!!!badbase64!!!\$aGFzaA") shouldBe false
            hasher.verify("valid-password-123", tamperedHash) shouldBe false
        }
    })
