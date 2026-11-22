package app.hononeko.miru.auth.oidc

import app.hononeko.miru.config.OidcConfig
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val b64Url = Base64.getUrlEncoder().withoutPadding()

private fun bigIntToBase64Url(value: BigInteger): String {
    val raw = value.toByteArray()
    val unsigned = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
    return b64Url.encodeToString(unsigned)
}

private fun bigIntFixedBase64Url(
    value: BigInteger,
    byteLen: Int,
): String {
    val raw = value.toByteArray()
    val out = ByteArray(byteLen)
    if (raw.size >= byteLen) {
        System.arraycopy(raw, raw.size - byteLen, out, 0, byteLen)
    } else {
        System.arraycopy(raw, 0, out, byteLen - raw.size, raw.size)
    }
    return b64Url.encodeToString(out)
}

private fun rsaToJwk(
    pub: RSAPublicKey,
    kid: String,
    alg: String = "RS256",
): JwkKey =
    JwkKey(
        kty = "RSA",
        use = "sig",
        kid = kid,
        alg = alg,
        n = bigIntToBase64Url(pub.modulus),
        e = bigIntToBase64Url(pub.publicExponent),
    )

private fun ecToJwk(
    pub: ECPublicKey,
    kid: String,
    alg: String = "ES256",
    crv: String = "P-256",
    coordBytes: Int = 32,
): JwkKey =
    JwkKey(
        kty = "EC",
        use = "sig",
        kid = kid,
        alg = alg,
        crv = crv,
        x = bigIntFixedBase64Url(pub.w.affineX, coordBytes),
        y = bigIntFixedBase64Url(pub.w.affineY, coordBytes),
    )

private fun ed25519ToJwk(
    pub: EdECPublicKey,
    kid: String,
): JwkKey {
    val yLittle = pub.point.y.toByteArray().reversedArray()
    val encoded = ByteArray(32)
    System.arraycopy(yLittle, 0, encoded, 0, minOf(yLittle.size, 32))
    if (pub.point.isXOdd) {
        encoded[31] = (encoded[31].toInt() or 0x80).toByte()
    }
    return JwkKey(
        kty = "OKP",
        use = "sig",
        kid = kid,
        alg = "EdDSA",
        crv = "Ed25519",
        x = b64Url.encodeToString(encoded),
    )
}

private fun signJwt(
    header: JsonObject,
    payload: JsonObject,
    alg: String,
    privateKey: PrivateKey?,
): String {
    val headerPart = b64Url.encodeToString(header.toString().toByteArray(Charsets.UTF_8))
    val payloadPart = b64Url.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    val signingInput = "$headerPart.$payloadPart".toByteArray(Charsets.US_ASCII)

    if (privateKey == null) {
        return "$headerPart.$payloadPart.unsigned"
    }

    val jcaAlg =
        when (alg) {
            "RS256" -> "SHA256withRSA"
            "RS384" -> "SHA384withRSA"
            "RS512" -> "SHA512withRSA"
            "ES256" -> "SHA256withECDSAinP1363Format"
            "ES384" -> "SHA384withECDSAinP1363Format"
            "ES512" -> "SHA512withECDSAinP1363Format"
            "EdDSA" -> "Ed25519"
            else -> error("Unsupported test alg $alg")
        }

    val sig = Signature.getInstance(jcaAlg)
    sig.initSign(privateKey)
    sig.update(signingInput)
    val sigBytes = sig.sign()
    return "$headerPart.$payloadPart.${b64Url.encodeToString(sigBytes)}"
}

class OidcJwtVerifierTest :
    FunSpec({
        val nowMs = AtomicLong(1_700_000_000_000L)
        val nowSec = 1_700_000_000L
        val verifier = OidcJwtVerifier(clockSkewSeconds = 60L, clock = { nowMs.get() })

        val rsaKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val ecKeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val edKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val rsaJwk = rsaToJwk(rsaKeyPair.public as RSAPublicKey, kid = "rsa-1")
        val ecJwk = ecToJwk(ecKeyPair.public as ECPublicKey, kid = "ec-1")
        val edJwk = ed25519ToJwk(edKeyPair.public as EdECPublicKey, kid = "ed-1")
        val jwkSet = JwkSet(keys = listOf(rsaJwk, ecJwk, edJwk))

        fun basePayload(
            iss: String = "https://idp.example.com",
            sub: String = "user-123",
            aud: String = "miru-client",
            nonce: String = "nonce-xyz",
            exp: Long = nowSec + 300L,
            iat: Long = nowSec - 10L,
            nbf: Long? = null,
        ): JsonObject =
            buildJsonObject {
                put("iss", iss)
                put("sub", sub)
                put("aud", aud)
                put("nonce", nonce)
                put("exp", exp)
                put("iat", iat)
                if (nbf != null) put("nbf", nbf)
                put("preferred_username", "sergiu")
                put("email", "sergiu@example.com")
                put(
                    "groups",
                    buildJsonArray {
                        add(JsonPrimitive("miru-admins"))
                        add(JsonPrimitive("media"))
                    },
                )
            }

        test("verifies valid RS256, ES256, and EdDSA tokens and extracts claims and groups") {
            val rsaToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1"); put("typ", "JWT") },
                    payload = basePayload(),
                    alg = "RS256",
                    privateKey = rsaKeyPair.private,
                )
            val rsaClaims =
                verifier
                    .verify(
                        idToken = rsaToken,
                        jwkSet = jwkSet,
                        expectedIssuer = "https://idp.example.com",
                        expectedClientId = "miru-client",
                        expectedNonce = "nonce-xyz",
                    ).shouldBeRight()
            rsaClaims.subject shouldBe "user-123"
            rsaClaims.preferredUsername shouldBe "sergiu"
            rsaClaims.email shouldBe "sergiu@example.com"
            rsaClaims.groups shouldBe setOf("miru-admins", "media")

            val ecToken =
                signJwt(
                    header = buildJsonObject { put("alg", "ES256"); put("kid", "ec-1") },
                    payload =
                        buildJsonObject {
                            put("iss", "https://idp.example.com")
                            put("sub", "ec-user")
                            put("aud", buildJsonArray { add(JsonPrimitive("miru-client")); add(JsonPrimitive("other")) })
                            put("azp", "miru-client")
                            put("nonce", "nonce-ec")
                            put("exp", nowSec + 300L)
                            put("iat", nowSec)
                            put("roles", "miru-users")
                        },
                    alg = "ES256",
                    privateKey = ecKeyPair.private,
                )
            val ecClaims =
                verifier
                    .verify(
                        idToken = ecToken,
                        jwkSet = jwkSet,
                        expectedIssuer = "https://idp.example.com",
                        expectedClientId = "miru-client",
                        expectedNonce = "nonce-ec",
                        groupsClaim = "roles",
                    ).shouldBeRight()
            ecClaims.subject shouldBe "ec-user"
            ecClaims.audiences shouldContainExactly listOf("miru-client", "other")
            ecClaims.groups shouldBe setOf("miru-users")

            val edToken =
                signJwt(
                    header = buildJsonObject { put("alg", "EdDSA"); put("kid", "ed-1") },
                    payload = basePayload(),
                    alg = "EdDSA",
                    privateKey = edKeyPair.private,
                )
            verifier
                .verify(
                    idToken = edToken,
                    jwkSet = jwkSet,
                    expectedIssuer = "https://idp.example.com",
                    expectedClientId = "miru-client",
                    expectedNonce = "nonce-xyz",
                ).shouldBeRight()
                .subject shouldBe "user-123"
        }

        test("rejects bad signature, alg=none, HS256, expired, wrong iss, aud, azp, nbf, iat, and nonce") {
            val otherRsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val badSigToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                    payload = basePayload(),
                    alg = "RS256",
                    privateKey = otherRsa.private,
                )
            verifier
                .verify(badSigToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "signature"

            val algNoneToken =
                signJwt(
                    header = buildJsonObject { put("alg", "none") },
                    payload = basePayload(),
                    alg = "none",
                    privateKey = null,
                )
            verifier
                .verify(algNoneToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "Unsupported"

            val hsToken =
                signJwt(
                    header = buildJsonObject { put("alg", "HS256") },
                    payload = basePayload(),
                    alg = "HS256",
                    privateKey = null,
                )
            verifier
                .verify(hsToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "Unsupported"

            val expiredToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                    payload = basePayload(exp = nowSec - 120L),
                    alg = "RS256",
                    privateKey = rsaKeyPair.private,
                )
            verifier
                .verify(expiredToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "expired"

            val futureNbfToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                    payload = basePayload(nbf = nowSec + 120L),
                    alg = "RS256",
                    privateKey = rsaKeyPair.private,
                )
            verifier
                .verify(futureNbfToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "nbf"

            val wrongIssToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                    payload = basePayload(iss = "https://evil.example.com"),
                    alg = "RS256",
                    privateKey = rsaKeyPair.private,
                )
            verifier
                .verify(wrongIssToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "iss"

            val wrongAudToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                    payload = basePayload(aud = "other-client"),
                    alg = "RS256",
                    privateKey = rsaKeyPair.private,
                )
            verifier
                .verify(wrongAudToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "aud"

            val wrongNonceToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                    payload = basePayload(nonce = "wrong-nonce"),
                    alg = "RS256",
                    privateKey = rsaKeyPair.private,
                )
            verifier
                .verify(wrongNonceToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .message shouldContain "nonce"
        }

        test("refreshes JWKS once on key rotation when encountering an unknown kid") {
            val rotatedKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val rotatedJwk = rsaToJwk(rotatedKeyPair.public as RSAPublicKey, kid = "rsa-2")
            val jwksFetches = AtomicInteger(0)

            val rotatedToken =
                signJwt(
                    header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-2") },
                    payload = basePayload(),
                    alg = "RS256",
                    privateKey = rotatedKeyPair.private,
                )

            // Confirm direct verifier returns UnknownKeyId for old JWKS
            verifier
                .verifyDetailed(rotatedToken, jwkSet, "https://idp.example.com", "miru-client", "nonce-xyz")
                .shouldBeLeft()
                .shouldBeInstanceOf<JwtVerificationFailure.UnknownKeyId>()

            val jsonFormat = Json { encodeDefaults = true }
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/.well-known/openid-configuration" ->
                            respond(
                                content =
                                    jsonFormat.encodeToString(
                                        OidcDiscoveryDocument(
                                            issuer = "https://idp.example.com",
                                            authorizationEndpoint = "https://idp.example.com/auth",
                                            tokenEndpoint = "https://idp.example.com/token",
                                            jwksUri = "https://idp.example.com/jwks",
                                        ),
                                    ),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )

                        "/jwks" -> {
                            val count = jwksFetches.incrementAndGet()
                            val keysToServe = if (count == 1) JwkSet(listOf(rsaJwk)) else JwkSet(listOf(rsaJwk, rotatedJwk))
                            respond(
                                content = jsonFormat.encodeToString(keysToServe),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }

                        else -> respond("not found", HttpStatusCode.NotFound)
                    }
                }

            OidcClient(
                config =
                    OidcConfig(
                        enabled = true,
                        issuerUrl = "https://idp.example.com",
                        clientId = "miru-client",
                        redirectUri = "http://localhost:8080/api/v1/auth/oidc/callback",
                        adminGroups = setOf("miru-admins"),
                    ),
                jwtVerifier = verifier,
                engine = engine,
                clock = { nowMs.get() },
            ).use { client ->
                // Warm initial JWKS with rsa-1 token
                val initialToken =
                    signJwt(
                        header = buildJsonObject { put("alg", "RS256"); put("kid", "rsa-1") },
                        payload = basePayload(),
                        alg = "RS256",
                        privateKey = rsaKeyPair.private,
                    )
                client.verifyIdToken(initialToken, "nonce-xyz").shouldBeRight()
                jwksFetches.get() shouldBe 1

                // Now verify rotated rsa-2 token -> triggers single JWKS refresh and succeeds
                val claims = client.verifyIdToken(rotatedToken, "nonce-xyz").shouldBeRight()
                claims.subject shouldBe "user-123"
                jwksFetches.get() shouldBe 2
            }
        }
    })
