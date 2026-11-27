package app.hononeko.miru.auth

import app.hononeko.miru.auth.oidc.OidcAuthService.Companion.PROVIDER_OIDC
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

/**
 * Links SSO accounts in [MediaUserLinkMode.AUTO] to the media users that share their verified email.
 *
 * Only the identity provider knows whether an email is verified, and Miru hears from it only at sign-in, which
 * records the answer on the account ([AuthAccountRecord.emailVerified]). Linking then runs when that record
 * changes at sign-in, when an admin switches an account back to AUTO, and after every media user sync, so Plex
 * users who are added or change their email are picked up without waiting for anyone to sign in again.
 *
 * An account whose email is recorded as unverified has no email links; one with nothing recorded yet keeps
 * whatever links it has until its next sign-in records the answer.
 */
class EmailMediaUserLinker(
    private val accountRepository: AuthAccountRepository,
    private val userRepository: UserRepository,
    private val authConfig: AuthConfig,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val logger = LoggerFactory.getLogger(EmailMediaUserLinker::class.java)

    /** Whether email linking is on: SSO enabled and OIDC_AUTO_LINK_BY_EMAIL true. */
    val enabled: Boolean
        get() = authConfig.oidcEnabled && authConfig.oidc.autoLinkByEmail

    /** Whether this account's links follow its verified email. */
    fun manages(account: AuthAccountRecord): Boolean =
        enabled &&
            account.authProvider.equals(PROVIDER_OIDC, ignoreCase = true) &&
            MediaUserLinkMode.fromStored(account.mediaUserLinkMode) == MediaUserLinkMode.AUTO

    /** Brings one account's links in line with its recorded email and returns its links afterwards. */
    suspend fun relink(account: AuthAccountRecord): Either<MiruError, Set<String>> =
        either {
            if (!manages(account) || account.emailVerified == null) {
                return@either accountRepository.findLinkedMediaUserIds(account.id).bind()
            }
            val mediaUsers = userRepository.findAll().bind()
            val allLinks = accountRepository.findAllMediaUserLinks().bind().toMutableMap()
            syncAccount(account, mediaUsers, allLinks).bind().links
        }

    /** Relinks every account this linker manages and returns how many accounts' links changed. */
    suspend fun relinkAll(): Either<MiruError, Int> =
        either {
            val accounts = accountRepository.findAll().bind().filter { manages(it) && it.emailVerified != null }
            if (accounts.isEmpty()) return@either 0
            val mediaUsers = userRepository.findAll().bind()
            val allLinks = accountRepository.findAllMediaUserLinks().bind().toMutableMap()
            accounts.count { account -> syncAccount(account, mediaUsers, allLinks).bind().changed }
        }

    private suspend fun syncAccount(
        account: AuthAccountRecord,
        mediaUsers: List<UserRecord>,
        allLinks: MutableMap<String, String>,
    ): Either<MiruError, LinkResult> =
        either {
            val current = accountRepository.findLinkedMediaUserIds(account.id).bind()
            val expected = expectedLinks(account, mediaUsers, current, allLinks)
            if (expected == current) {
                return@either LinkResult(current, changed = false)
            }
            val applied = accountRepository.replaceLinkedMediaUserIdsIfAuto(account.id, expected, clock()).bind()
            if (!applied) {
                // An admin made the account MANUAL in the meantime; their links stand.
                return@either LinkResult(accountRepository.findLinkedMediaUserIds(account.id).bind(), changed = false)
            }
            allLinks.entries.removeAll { it.value == account.id }
            expected.forEach { allLinks[it] = account.id }
            logger.info("Updated email-linked media users for SSO account '{}' ({} linked)", account.username, expected.size)
            LinkResult(expected, changed = true)
        }

    private fun expectedLinks(
        account: AuthAccountRecord,
        mediaUsers: List<UserRecord>,
        current: Set<String>,
        allLinks: Map<String, String>,
    ): Set<String> {
        val verifiedEmail = account.email?.takeIf { it.isNotBlank() && account.emailVerified == true } ?: return emptySet()
        val matchesByServer =
            mediaUsers
                .filter { it.email != null && it.email.equals(verifiedEmail, ignoreCase = true) }
                .groupBy { it.serverId }
        // An account is linked to at most one user per server, and each media user belongs to at most
        // one Miru account. When several users on one server share the email, Miru can't tell which is
        // this person's: it keeps the one already linked, if any, and otherwise links none there.
        return matchesByServer
            .mapNotNull { (serverId, users) ->
                val candidate =
                    users.singleOrNull()?.id
                        ?: users.map { it.id }.singleOrNull { it in current }
                        ?: null.also {
                            logger.warn(
                                "Not linking SSO account '{}' by email on server {}: {} media users there share its email",
                                account.username,
                                serverId,
                                users.size,
                            )
                        }
                candidate?.takeIf { candidateId ->
                    val owner = allLinks[candidateId]
                    if (owner != null && owner != account.id) {
                        logger.warn(
                            "Not linking SSO account '{}' to media user {} on server {}: already linked to another account",
                            account.username,
                            candidateId,
                            serverId,
                        )
                        false
                    } else {
                        true
                    }
                }
            }.toSet()
    }

    private data class LinkResult(
        val links: Set<String>,
        val changed: Boolean,
    )
}
