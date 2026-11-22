package app.hononeko.miru.auth.oidc

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.TokenCrypto
import app.hononeko.miru.auth.oidc.OidcJwtVerifier.Companion.stringClaim
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.URLBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class OidcAuthService(
    private val oidcClient: OidcClient,
    private val authService: AuthService,
    private val accountRepository: AuthAccountRepository,
    private val userRepository: UserRepository,
    private val authConfig: AuthConfig,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val logger = LoggerFactory.getLogger(OidcAuthService::class.java)
    private val txMutex = Mutex()
    private val pendingTransactions = LinkedHashMap<String, OidcPendingTransaction>()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    suspend fun startAuthorization(): Either<MiruError, OidcAuthorizationStart> =
        either {
            ensure(authConfig.oidcEnabled) {
                MiruError.Conflict("OIDC authentication is disabled")
            }

            val discovery = oidcClient.getDiscovery().bind()
            val oidc = authConfig.oidc
            val state = TokenCrypto.generateToken(TOKEN_BYTES)
            val nonce = TokenCrypto.generateToken(TOKEN_BYTES)
            val codeVerifier = TokenCrypto.generateToken(TOKEN_BYTES)
            val codeChallenge = computeS256Challenge(codeVerifier)

            val now = clock()
            val tx =
                OidcPendingTransaction(
                    state = state,
                    nonce = nonce,
                    codeVerifier = codeVerifier,
                    createdAtMs = now,
                    expiresAtMs = now + TX_TTL_MS,
                )
            storeTransaction(tx, now)

            val authUrl =
                URLBuilder(discovery.authorizationEndpoint)
                    .apply {
                        parameters.append("response_type", "code")
                        parameters.append("client_id", oidc.clientId)
                        parameters.append("redirect_uri", oidc.redirectUri)
                        parameters.append("scope", oidc.scopes.joinToString(" "))
                        parameters.append("state", state)
                        parameters.append("nonce", nonce)
                        parameters.append("code_challenge", codeChallenge)
                        parameters.append("code_challenge_method", "S256")
                    }.buildString()

            val txCookieValue = TokenCrypto.signSessionToken(state, authConfig.sessionSecret)
            OidcAuthorizationStart(
                authorizationUrl = authUrl,
                state = state,
                txCookieValue = txCookieValue,
            )
        }

    suspend fun handleCallback(
        code: String?,
        state: String?,
        txCookie: String?,
        errorParam: String?,
        errorDescription: String?,
    ): Either<MiruError, OidcCallbackResult> =
        either {
            ensure(authConfig.oidcEnabled) {
                MiruError.Conflict("OIDC authentication is disabled")
            }

            if (!errorParam.isNullOrBlank()) {
                val safeDetail = errorDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                raise(MiruError.UpstreamAuthError("oidc", "OIDC provider returned error '$errorParam'$safeDetail"))
            }

            val trimmedState = state?.trim()?.takeIf { it.isNotEmpty() }
                ?: raise(MiruError.Unauthorized("Missing OIDC state parameter"))
            val trimmedCode = code?.trim()?.takeIf { it.isNotEmpty() }
                ?: raise(MiruError.Unauthorized("Missing OIDC authorization code"))
            val rawTxCookie = txCookie?.trim()?.takeIf { it.isNotEmpty() }
                ?: raise(MiruError.Unauthorized("Missing OIDC transaction cookie"))

            val signedState =
                TokenCrypto.verifySignedSessionToken(rawTxCookie, authConfig.sessionSecret)
                    ?: raise(MiruError.Unauthorized("Invalid OIDC transaction cookie"))

            val stateMatches =
                MessageDigest.isEqual(
                    signedState.toByteArray(Charsets.UTF_8),
                    trimmedState.toByteArray(Charsets.UTF_8),
                )
            ensure(stateMatches) {
                MiruError.Unauthorized("OIDC state mismatch")
            }

            val now = clock()
            val pending =
                consumeTransaction(trimmedState, now)
                    ?: raise(MiruError.Unauthorized("OIDC transaction not found or expired"))

            val tokenResponse = oidcClient.exchangeCode(trimmedCode, pending.codeVerifier).bind()
            val rawIdToken =
                tokenResponse.idToken?.takeIf { it.isNotBlank() }
                    ?: raise(MiruError.UpstreamAuthError("oidc", "OIDC token response did not include an id_token"))

            val claims = oidcClient.verifyIdToken(rawIdToken, expectedNonce = pending.nonce).bind()
            val enriched = enrichClaimsFromUserInfo(claims, tokenResponse.accessToken).bind()
            val role = mapGroupsToRole(enriched.groups).bind()

            val account = upsertOidcAccount(enriched, role, now).bind()
            val linkedMediaUserIds = autoLinkMediaUsersIfNeeded(account, now).bind()
            val loginResult = authService.issueSessionForAccount(account, linkedMediaUserIds).bind()

            OidcCallbackResult(
                loginResult = loginResult,
                idTokenHint = rawIdToken,
            )
        }

    suspend fun hasEndSessionEndpoint(): Boolean {
        if (!authConfig.oidcEnabled) return false
        return oidcClient
            .getDiscovery()
            .getOrNull()
            ?.endSessionEndpoint
            ?.isNotBlank() == true
    }

    suspend fun buildEndSessionUrl(idTokenHint: String?): String? {
        if (!authConfig.oidcEnabled) return null
        val discovery = oidcClient.getDiscovery().getOrNull() ?: return null
        val endpoint = discovery.endSessionEndpoint?.takeIf { it.isNotBlank() } ?: return null

        return URLBuilder(endpoint)
            .apply {
                parameters.append("client_id", authConfig.oidc.clientId)
                authConfig.oidc.postLogoutRedirectUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parameters.append("post_logout_redirect_uri", it) }
                idTokenHint
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parameters.append("id_token_hint", it) }
            }.buildString()
    }

    private suspend fun enrichClaimsFromUserInfo(
        claims: OidcIdTokenClaims,
        accessToken: String?,
    ): Either<MiruError, OidcIdTokenClaims> =
        either {
            if ((claims.groups.isNotEmpty() && !claims.email.isNullOrBlank()) || accessToken.isNullOrBlank()) {
                return@either claims
            }

            val userInfoResult = oidcClient.fetchUserInfo(accessToken)
            val userInfo: JsonObject? =
                when (userInfoResult) {
                    is Either.Right -> userInfoResult.value
                    is Either.Left -> {
                        if (claims.groups.isEmpty()) {
                            raise(userInfoResult.value)
                        }
                        logger.warn("Optional OIDC userinfo fetch failed: {}", userInfoResult.value.message)
                        null
                    }
                }

            if (userInfo == null) return@either claims

            val extraGroups = OidcJwtVerifier.extractGroupsClaim(userInfo, authConfig.oidc.groupsClaim)
            val resolvedUsername =
                claims.preferredUsername
                    ?: userInfo.stringClaim("preferred_username")?.trim()?.takeIf { it.isNotEmpty() }
            claims.copy(
                preferredUsername = resolvedUsername,
                name = claims.name ?: userInfo.stringClaim("name")?.trim()?.takeIf { it.isNotEmpty() },
                email = claims.email ?: userInfo.stringClaim("email")?.trim()?.takeIf { it.isNotEmpty() },
                groups = claims.groups.ifEmpty { extraGroups },
            )
        }

    private fun mapGroupsToRole(groups: Set<String>): Either<MiruError.Forbidden, MiruRole> =
        either {
            val oidc = authConfig.oidc
            when {
                groups.any { it in oidc.adminGroups } -> MiruRole.ADMIN
                groups.any { it in oidc.userGroups } -> MiruRole.USER
                else -> raise(MiruError.Forbidden("User is not a member of an authorized Miru group"))
            }
        }

    private suspend fun upsertOidcAccount(
        claims: OidcIdTokenClaims,
        role: MiruRole,
        nowMs: Long,
    ): Either<MiruError, AuthAccountRecord> =
        either {
            val existing = accountRepository.findByProviderAndSubject(PROVIDER_OIDC, claims.subject).bind()
            val candidateBase = deriveBaseUsername(claims)

            if (existing != null) {
                ensure(!existing.disabled) {
                    MiruError.Unauthorized("Account is disabled")
                }

                val targetUsername = resolveUniqueUsername(candidateBase, excludeAccountId = existing.id).bind()
                val updatedEmail = claims.email ?: existing.email
                accountRepository
                    .updateOidcProfile(
                        id = existing.id,
                        username = targetUsername,
                        usernameNormalized = targetUsername.lowercase(),
                        email = updatedEmail,
                        role = role.name,
                        updatedAtMs = nowMs,
                    ).bind()
                    ?: existing
            } else {
                val uniqueUsername = resolveUniqueUsername(candidateBase, excludeAccountId = null).bind()
                val record =
                    AuthAccountRecord(
                        id = idGenerator(),
                        username = uniqueUsername,
                        usernameNormalized = uniqueUsername.lowercase(),
                        email = claims.email,
                        passwordHash = "",
                        role = role.name,
                        disabled = false,
                        authProvider = PROVIDER_OIDC,
                        externalSubject = claims.subject,
                        createdAtMs = nowMs,
                        updatedAtMs = nowMs,
                    )
                accountRepository.insert(record).bind()
            }
        }

    private suspend fun autoLinkMediaUsersIfNeeded(
        account: AuthAccountRecord,
        nowMs: Long,
    ): Either<MiruError, Set<String>> =
        either {
            val currentLinks = accountRepository.findLinkedMediaUserIds(account.id).bind()
            if (currentLinks.isNotEmpty() || !authConfig.oidc.autoLinkByEmail || account.email.isNullOrBlank()) {
                return@either currentLinks
            }

            val allUsers = userRepository.findAll().bind()
            val matchedIds =
                allUsers
                    .filter { it.email != null && it.email.equals(account.email, ignoreCase = true) }
                    .map { it.id }
                    .toSet()

            if (matchedIds.isNotEmpty()) {
                accountRepository.replaceLinkedMediaUserIds(account.id, matchedIds, nowMs).bind()
            }
            matchedIds
        }

    private suspend fun resolveUniqueUsername(
        base: String,
        excludeAccountId: String?,
    ): Either<MiruError.PersistenceError, String> =
        either {
            val firstNormalized = base.lowercase()
            val existing = accountRepository.findByNormalizedUsername(firstNormalized).bind()
            if (existing == null || existing.id == excludeAccountId) {
                return@either base
            }

            for (attempt in 2..MAX_USERNAME_ATTEMPTS) {
                val suffix = "-$attempt"
                val truncated = base.take(AuthService.MAX_USERNAME_LENGTH - suffix.length)
                val candidate = "$truncated$suffix"
                val found = accountRepository.findByNormalizedUsername(candidate.lowercase()).bind()
                if (found == null || found.id == excludeAccountId) {
                    return@either candidate
                }
            }

            val fallbackSuffix = "-${TokenCrypto.generateToken(4).take(6)}"
            val truncated = base.take(AuthService.MAX_USERNAME_LENGTH - fallbackSuffix.length)
            "$truncated$fallbackSuffix"
        }

    private fun deriveBaseUsername(claims: OidcIdTokenClaims): String {
        val raw =
            claims.preferredUsername
                ?: claims.email?.substringBefore('@')
                ?: claims.name
                ?: "oidc-${claims.subject}"
        val sanitized =
            raw
                .trim()
                .replace(INVALID_USERNAME_CHAR_REGEX, "_")
                .trim('_')
                .ifEmpty { "oidc_user" }

        val padded =
            if (sanitized.length < AuthService.MIN_USERNAME_LENGTH) {
                sanitized.padEnd(AuthService.MIN_USERNAME_LENGTH, '_')
            } else {
                sanitized
            }
        return padded.take(AuthService.MAX_USERNAME_LENGTH)
    }

    private fun computeS256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    private suspend fun storeTransaction(
        tx: OidcPendingTransaction,
        nowMs: Long,
    ) {
        txMutex.withLock {
            pruneExpiredLocked(nowMs)
            while (pendingTransactions.size >= MAX_PENDING_TRANSACTIONS) {
                val oldestKey = pendingTransactions.keys.firstOrNull() ?: break
                pendingTransactions.remove(oldestKey)
            }
            pendingTransactions[tx.state] = tx
        }
    }

    private suspend fun consumeTransaction(
        state: String,
        nowMs: Long,
    ): OidcPendingTransaction? =
        txMutex.withLock {
            pruneExpiredLocked(nowMs)
            val tx = pendingTransactions.remove(state) ?: return@withLock null
            if (tx.expiresAtMs <= nowMs) null else tx
        }

    private fun pruneExpiredLocked(nowMs: Long) {
        val iterator = pendingTransactions.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMs <= nowMs) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val PROVIDER_OIDC = "OIDC"
        const val TX_TTL_MS = 10L * 60L * 1000L
        const val MAX_PENDING_TRANSACTIONS = 1_000
        private const val TOKEN_BYTES = 32
        private const val MAX_USERNAME_ATTEMPTS = 100
        private val INVALID_USERNAME_CHAR_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}
