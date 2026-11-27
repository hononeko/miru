package app.hononeko.miru.auth

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Gives an account that SSO matched to media users by email a profile of its own: the display name and
 * avatar of its linked user on the media server connected to Miru first. Fills in only what the account
 * lacks, so a name or avatar a person or admin chose stays.
 */
class AccountProfileAutoImporter(
    private val authService: AuthService,
    private val accountRepository: AuthAccountRepository,
    private val userRepository: UserRepository,
    private val mediaServerRepository: MediaServerRepository,
    private val profileImport: ProfileImportSources,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(AccountProfileAutoImporter::class.java)

    /** Imports in the background whenever [emailLinker] changes an account's links. */
    fun attachTo(emailLinker: EmailMediaUserLinker) {
        emailLinker.addOnLinksChangedListener { accountId ->
            scope.launch {
                importMissing(accountId).onLeft { error ->
                    logger.warn("Could not import a profile for an email-linked account: {}", error.message)
                }
            }
        }
    }

    /** Imports what [accountId] lacks from its first-connected server; Right(false) when there was nothing to do. */
    suspend fun importMissing(accountId: String): Either<MiruError, Boolean> =
        either {
            val account = accountRepository.findById(accountId).bind() ?: return@either false
            val needsName = account.displayName.normalizedDisplayName() == null
            if (!needsName && account.hasCustomAvatar) return@either false

            val linkedIds = accountRepository.findLinkedMediaUserIds(accountId).bind()
            if (linkedIds.isEmpty()) return@either false
            val connectedAtByServer = mediaServerRepository.findAll().bind().associate { it.id to it.createdAtMs }
            val firstServerUser =
                userRepository
                    .findByIds(linkedIds)
                    .bind()
                    .minWithOrNull(
                        compareBy<UserRecord> { connectedAtByServer[it.serverId] ?: Long.MAX_VALUE }
                            .thenBy { it.serverId }
                            .thenBy { it.createdAtMs },
                    ) ?: return@either false

            val importAvatar = !account.hasCustomAvatar && !firstServerUser.thumbUrl.isNullOrBlank()
            if (!needsName && !importAvatar) return@either false
            authService
                .importProfileFromServer(
                    accountId = accountId,
                    serverId = firstServerUser.serverId,
                    importDisplayName = needsName,
                    importAvatar = importAvatar,
                    profileImport = profileImport,
                ).bind()
            logger.info(
                "Imported the profile of an email-linked account from its first-connected server (name={}, avatar={})",
                needsName,
                importAvatar,
            )
            true
        }
}
