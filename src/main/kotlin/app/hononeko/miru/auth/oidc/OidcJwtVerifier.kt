package app.hononeko.miru.auth.oidc

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

sealed interface JwtVerificationFailure {
    val error: MiruError.UpstreamAuthError

    data class UnknownKeyId(
        val kid: String,
        override val error: MiruError.UpstreamAuthError =
            MiruError.UpstreamAuthError("oidc", "No matching JWK found for key id '$kid'"),
    ) : JwtVerificationFailure

    data class Rejected(
        override val error: MiruError.UpstreamAuthError,
    ) : JwtVerificationFailure
}

class OidcJwtVerifier(
    private val clockSkewSeconds: Long = DEFAULT_CLOCK_SKEW_SECONDS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base64UrlDecoder = Base64.getUrlDecoder()

    fun verify(
        idToken: String,
        jwkSet: JwkSet,
        expectedIssuer: String,
        expectedClientId: String,
        expectedNonce: String?,
        groupsClaim: String = "groups",
    ): Either<MiruError.UpstreamAuthError, OidcIdTokenClaims> =
        verifyDetailed(
            idToken = idToken,
            jwkSet = jwkSet,
            expectedIssuer = expectedIssuer,
            expectedClientId = expectedClientId,
            expectedNonce = expectedNonce,
            groupsClaim = groupsClaim,
        ).mapLeft { it.error }

    fun verifyDetailed(
        idToken: String,
        jwkSet: JwkSet,
        expectedIssuer: String,
        expectedClientId: String,
        expectedNonce: String?,
        groupsClaim: String = "groups",
    ): Either<JwtVerificationFailure, OidcIdTokenClaims> =
        either {
            val parts = idToken.split('.')
            ensure(parts.size == JWT_PART_COUNT && parts.all { it.isNotEmpty() }) {
                rejected("Malformed OIDC ID token")
            }

            val headerBytes = decodeBase64Url(parts[0]) ?: raise(rejected("Invalid OIDC ID token header encoding"))
            val payloadBytes = decodeBase64Url(parts[1]) ?: raise(rejected("Invalid OIDC ID token payload encoding"))
            val signatureBytes = decodeBase64Url(parts[2]) ?: raise(rejected("Invalid OIDC ID token signature encoding"))

            val headerObj = parseJsonObject(headerBytes, "header").bind()
            val payloadObj = parseJsonObject(payloadBytes, "payload").bind()

            val alg = headerObj.stringClaim("alg") ?: raise(rejected("OIDC ID token missing 'alg' header"))
            val algSpec = resolveAlgSpec(alg) ?: raise(rejected("Unsupported or disallowed JWS algorithm '$alg'"))
            val kid = headerObj.stringClaim("kid")

            val candidateKeys = selectCandidateKeys(jwkSet, alg, algSpec, kid).bind()
            val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
            val signatureValid =
                candidateKeys.any { jwk ->
                    verifySignatureWithJwk(
                        jwk = jwk,
                        algSpec = algSpec,
                        signingInput = signingInput,
                        signatureBytes = signatureBytes,
                    )
                }
            ensure(signatureValid) {
                rejected("OIDC ID token signature verification failed")
            }

            validateClaims(
                claims = payloadObj,
                expectedIssuer = expectedIssuer,
                expectedClientId = expectedClientId,
                expectedNonce = expectedNonce,
                groupsClaim = groupsClaim,
            ).bind()
        }

    private fun parseJsonObject(
        bytes: ByteArray,
        section: String,
    ): Either<JwtVerificationFailure, JsonObject> =
        try {
            Either.Right(json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        } catch (_: Exception) {
            Either.Left(rejected("Invalid OIDC ID token $section JSON"))
        }

    private fun resolveAlgSpec(alg: String): JwsAlgSpec? {
        if (alg.equals("none", ignoreCase = true) || alg.startsWith("HS", ignoreCase = true)) {
            return null
        }
        return SUPPORTED_ALGS[alg]
    }

    private fun selectCandidateKeys(
        jwkSet: JwkSet,
        alg: String,
        algSpec: JwsAlgSpec,
        kid: String?,
    ): Either<JwtVerificationFailure, List<JwkKey>> =
        either {
            val compatible =
                jwkSet.keys.filter { key ->
                    key.kty == algSpec.kty &&
                        (key.use == null || key.use == "sig") &&
                        (key.alg == null || key.alg == alg)
                }

            if (kid != null) {
                val byKid = compatible.filter { it.kid == kid }
                if (byKid.isEmpty()) {
                    raise(JwtVerificationFailure.UnknownKeyId(kid))
                }
                byKid
            } else {
                ensure(compatible.isNotEmpty()) {
                    rejected("No compatible verification key found in JWKS for '$alg'")
                }
                compatible
            }
        }

    private fun verifySignatureWithJwk(
        jwk: JwkKey,
        algSpec: JwsAlgSpec,
        signingInput: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val publicKey = buildPublicKey(jwk, algSpec) ?: return false
        return try {
            val signature = Signature.getInstance(algSpec.jcaAlgorithm)
            signature.initVerify(publicKey)
            signature.update(signingInput)
            signature.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun buildPublicKey(
        jwk: JwkKey,
        algSpec: JwsAlgSpec,
    ): PublicKey? =
        try {
            when (algSpec.kty) {
                "RSA" -> buildRsaPublicKey(jwk)
                "EC" -> buildEcPublicKey(jwk, algSpec.expectedCurve)
                "OKP" -> buildEd25519PublicKey(jwk, algSpec.expectedCurve)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun buildRsaPublicKey(jwk: JwkKey): PublicKey? {
        val nBytes = jwk.n?.let(::decodeBase64Url) ?: return null
        val eBytes = jwk.e?.let(::decodeBase64Url) ?: return null
        if (nBytes.isEmpty() || eBytes.isEmpty()) return null
        val spec = RSAPublicKeySpec(BigInteger(1, nBytes), BigInteger(1, eBytes))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private fun buildEcPublicKey(
        jwk: JwkKey,
        expectedCurve: String?,
    ): PublicKey? {
        val crv = jwk.crv ?: return null
        if (expectedCurve != null && crv != expectedCurve) return null
        val stdName = EC_CURVE_MAP[crv] ?: return null
        val xBytes = jwk.x?.let(::decodeBase64Url) ?: return null
        val yBytes = jwk.y?.let(::decodeBase64Url) ?: return null
        if (xBytes.isEmpty() || yBytes.isEmpty()) return null

        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec(stdName))
        val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, xBytes), BigInteger(1, yBytes))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecSpec))
    }

    private fun buildEd25519PublicKey(
        jwk: JwkKey,
        expectedCurve: String?,
    ): PublicKey? {
        if (jwk.crv != expectedCurve || expectedCurve != "Ed25519") return null
        val raw = jwk.x?.let(::decodeBase64Url) ?: return null
        if (raw.size != ED25519_KEY_BYTES) return null

        val msb = raw[ED25519_KEY_BYTES - 1].toInt() and BYTE_MASK
        val xOdd = (msb and SIGN_BIT_MASK) != 0
        val yLittleEndian = raw.copyOf()
        yLittleEndian[ED25519_KEY_BYTES - 1] = (msb and MAGNITUDE_MASK).toByte()
        val yBigEndian = yLittleEndian.reversedArray()
        val point = EdECPoint(xOdd, BigInteger(1, yBigEndian))
        val spec = EdECPublicKeySpec(NamedParameterSpec.ED25519, point)
        return KeyFactory.getInstance("EdDSA").generatePublic(spec)
    }

    private fun validateClaims(
        claims: JsonObject,
        expectedIssuer: String,
        expectedClientId: String,
        expectedNonce: String?,
        groupsClaim: String,
    ): Either<JwtVerificationFailure, OidcIdTokenClaims> =
        either {
            val issuer = claims.stringClaim("iss") ?: raise(rejected("OIDC ID token missing 'iss' claim"))
            ensure(issuer == expectedIssuer) {
                rejected("OIDC ID token 'iss' mismatch")
            }

            val subject =
                claims.stringClaim("sub")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: raise(rejected("OIDC ID token missing or blank 'sub' claim"))

            val audiences = extractAudiences(claims["aud"]) ?: raise(rejected("OIDC ID token missing valid 'aud' claim"))
            ensure(expectedClientId in audiences) {
                rejected("OIDC ID token 'aud' does not include configured clientId")
            }

            val azp = claims.stringClaim("azp")
            if (audiences.size > 1) {
                ensure(azp == expectedClientId) {
                    rejected("OIDC ID token with multiple audiences requires matching 'azp'")
                }
            } else if (azp != null) {
                ensure(azp == expectedClientId) {
                    rejected("OIDC ID token 'azp' does not match configured clientId")
                }
            }

            val nowSec = clock() / MILLIS_PER_SECOND
            val exp = claims.longClaim("exp") ?: raise(rejected("OIDC ID token missing 'exp' claim"))
            ensure(nowSec - clockSkewSeconds < exp) {
                rejected("OIDC ID token has expired")
            }

            val nbf = claims.longClaim("nbf")
            if (nbf != null) {
                ensure(nowSec + clockSkewSeconds >= nbf) {
                    rejected("OIDC ID token is not yet valid (nbf)")
                }
            }

            val iat = claims.longClaim("iat") ?: raise(rejected("OIDC ID token missing 'iat' claim"))
            ensure(iat <= nowSec + clockSkewSeconds) {
                rejected("OIDC ID token 'iat' is in the future")
            }

            if (expectedNonce != null) {
                val actualNonce = claims.stringClaim("nonce") ?: raise(rejected("OIDC ID token missing 'nonce' claim"))
                val nonceMatches =
                    MessageDigest.isEqual(
                        expectedNonce.toByteArray(Charsets.UTF_8),
                        actualNonce.toByteArray(Charsets.UTF_8),
                    )
                ensure(nonceMatches) {
                    rejected("OIDC ID token 'nonce' mismatch")
                }
            }

            val groups = extractGroupsClaim(claims, groupsClaim)
            OidcIdTokenClaims(
                subject = subject,
                issuer = issuer,
                audiences = audiences,
                preferredUsername = claims.stringClaim("preferred_username")?.trim()?.takeIf { it.isNotEmpty() },
                name = claims.stringClaim("name")?.trim()?.takeIf { it.isNotEmpty() },
                email = claims.stringClaim("email")?.trim()?.takeIf { it.isNotEmpty() },
                emailVerified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull,
                groups = groups,
                issuedAtEpochSec = iat,
                expiresAtEpochSec = exp,
            )
        }

    private fun extractAudiences(element: JsonElement?): List<String>? =
        when (element) {
            is JsonPrimitive -> {
                if (element.isString && element.content.isNotBlank()) listOf(element.content) else null
            }

            is JsonArray -> {
                val list =
                    element.mapNotNull { item ->
                        (item as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                    }
                if (list.size == element.size && list.isNotEmpty()) list else null
            }

            else -> null
        }

    private fun decodeBase64Url(encoded: String): ByteArray? =
        try {
            base64UrlDecoder.decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun rejected(message: String): JwtVerificationFailure.Rejected =
        JwtVerificationFailure.Rejected(MiruError.UpstreamAuthError("oidc", message))

    private data class JwsAlgSpec(
        val jcaAlgorithm: String,
        val kty: String,
        val expectedCurve: String? = null,
    )

    companion object {
        const val DEFAULT_CLOCK_SKEW_SECONDS = 60L
        private const val JWT_PART_COUNT = 3
        private const val MILLIS_PER_SECOND = 1_000L
        private const val ED25519_KEY_BYTES = 32
        private const val BYTE_MASK = 0xFF
        private const val SIGN_BIT_MASK = 0x80
        private const val MAGNITUDE_MASK = 0x7F

        private val SUPPORTED_ALGS: Map<String, JwsAlgSpec> =
            mapOf(
                "RS256" to JwsAlgSpec("SHA256withRSA", "RSA"),
                "RS384" to JwsAlgSpec("SHA384withRSA", "RSA"),
                "RS512" to JwsAlgSpec("SHA512withRSA", "RSA"),
                "ES256" to JwsAlgSpec("SHA256withECDSAinP1363Format", "EC", "P-256"),
                "ES384" to JwsAlgSpec("SHA384withECDSAinP1363Format", "EC", "P-384"),
                "ES512" to JwsAlgSpec("SHA512withECDSAinP1363Format", "EC", "P-521"),
                "EdDSA" to JwsAlgSpec("Ed25519", "OKP", "Ed25519"),
            )

        private val EC_CURVE_MAP: Map<String, String> =
            mapOf(
                "P-256" to "secp256r1",
                "P-384" to "secp384r1",
                "P-521" to "secp521r1",
            )

        fun extractGroupsClaim(
            claims: JsonObject,
            groupsClaim: String,
        ): Set<String> {
            val element = resolveClaimElement(claims, groupsClaim) ?: return emptySet()
            return when (element) {
                is JsonArray ->
                    element
                        .mapNotNull { item ->
                            (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                        }.toSet()

                is JsonPrimitive ->
                    if (element.isString) {
                        element.content.trim().takeIf { it.isNotEmpty() }?.let { setOf(it) }.orEmpty()
                    } else {
                        emptySet()
                    }

                else -> emptySet()
            }
        }

        private fun resolveClaimElement(
            claims: JsonObject,
            claimPath: String,
        ): JsonElement? {
            claims[claimPath]?.let { return it }
            if (!claimPath.contains('.')) return null
            var current: JsonElement = claims
            for (segment in claimPath.split('.')) {
                val obj = current as? JsonObject ?: return null
                current = obj[segment] ?: return null
            }
            return current
        }

        internal fun JsonObject.stringClaim(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.longClaim(name: String): Long? =
            (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
