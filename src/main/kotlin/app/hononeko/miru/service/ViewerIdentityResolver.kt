package app.hononeko.miru.service

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.LinkedUserProfileDto
import app.hononeko.miru.http.UserSummaryDto
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.AuthAccountProfileRecord
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either

internal data class ResolvedViewer(
    val canonicalId: String,
    val accountId: String?,
    val accountProfile: AuthAccountProfileRecord? = null,
    val allProfiles: List<UserRecord>,
    val scopedProfiles: List<UserRecord>,
    val targetMediaUserIds: Set<String>,
    val primaryProfile: UserRecord,
    val primaryDisplayName: String?,
    val avatarUrl: String? = null,
)

internal data class ResolvedMediaUserIdentity(
    val user: UserRecord?,
    val displayName: String?,
    val avatarUrl: String?,
)

internal object ViewerIdentityResolver {
    val stableProfileComparator: Comparator<UserRecord> =
        compareBy<UserRecord> { it.createdAtMs }.thenBy { it.id }

    fun displayProfileComparator(serversById: Map<String, MediaServerRecord>): Comparator<LinkedUserProfileDto> =
        compareBy<LinkedUserProfileDto> { (it.serverName ?: serversById[it.serverId]?.name ?: it.serverId).lowercase() }
            .thenBy { it.username.lowercase() }
            .thenBy { it.userId }

    fun requireTargetViewerAccess(
        principal: MiruPrincipal,
        targetId: String,
    ): Either<MiruError.Forbidden, Unit> {
        if (principal.isAdmin()) return Either.Right(Unit)
        val allowed =
            principal.linkedMediaUserIds.isNotEmpty() &&
                (targetId in principal.linkedMediaUserIds || targetId == principal.accountId)
        return if (allowed) {
            Either.Right(Unit)
        } else {
            Either.Left(MiruError.Forbidden("Access to media user '$targetId' is forbidden"))
        }
    }

    suspend fun resolveViewer(
        userRepository: UserRepository,
        authAccountRepository: AuthAccountRepository?,
        targetId: String,
        serverId: String? = null,
    ): Either<MiruError, ResolvedViewer> =
        either {
            val directUser = userRepository.findById(targetId).bind()
            val accountId: String?
            var loadedAccount: AuthAccountRecord? = null
            val rawProfiles: List<UserRecord>
            if (directUser != null) {
                accountId = authAccountRepository?.findAccountIdByMediaUserId(directUser.id)?.bind()
                rawProfiles =
                    if (accountId != null) {
                        val linkedIds = authAccountRepository.findLinkedMediaUserIds(accountId).bind()
                        val loaded = userRepository.findByIds(linkedIds).bind()
                        if (loaded.any { it.id == directUser.id }) loaded else loaded + directUser
                    } else {
                        listOf(directUser)
                    }
            } else {
                val account =
                    authAccountRepository?.findById(targetId)?.bind()
                        ?: raise(MiruError.NotFound(resource = "User", id = targetId))
                loadedAccount = account
                accountId = account.id
                val linkedIds = authAccountRepository.findLinkedMediaUserIds(account.id).bind()
                if (linkedIds.isEmpty()) {
                    raise(MiruError.NotFound(resource = "User", id = targetId))
                }
                rawProfiles = userRepository.findByIds(linkedIds).bind()
                if (rawProfiles.isEmpty()) {
                    raise(MiruError.NotFound(resource = "User", id = targetId))
                }
            }

            val accountProfile =
                when {
                    loadedAccount != null -> loadedAccount.toProfileRecord()
                    accountId != null -> authAccountRepository?.findProfilesByIds(listOf(accountId))?.bind()?.get(accountId)
                    else -> null
                }

            val orderedAll = rawProfiles.sortedWith(stableProfileComparator)
            val scopedProfiles =
                if (serverId != null) {
                    orderedAll.filter { it.serverId == serverId }
                } else {
                    orderedAll
                }
            val effectiveProfiles = scopedProfiles.ifEmpty { orderedAll }
            val primaryProfile = effectiveProfiles.first()
            val canonicalId =
                if (serverId != null) {
                    scopedProfiles.firstOrNull()?.id ?: accountId ?: targetId
                } else {
                    accountId ?: orderedAll.first().id
                }
            val primaryDisplayName = resolveDisplayName(accountProfile?.displayName, effectiveProfiles)
            val avatarUrl = resolveAvatarUrl(accountProfile, effectiveProfiles)

            ResolvedViewer(
                canonicalId = canonicalId,
                accountId = accountId,
                accountProfile = accountProfile,
                allProfiles = orderedAll,
                scopedProfiles = scopedProfiles,
                targetMediaUserIds = scopedProfiles.mapTo(LinkedHashSet(scopedProfiles.size)) { it.id },
                primaryProfile = primaryProfile,
                primaryDisplayName = primaryDisplayName,
                avatarUrl = avatarUrl,
            )
        }

    suspend fun resolveScopedFilterUserIds(
        authAccountRepository: AuthAccountRepository?,
        targetId: String,
    ): Either<MiruError, Set<String>> =
        either {
            if (authAccountRepository == null) return@either setOf(targetId)
            val ownerAccountId = authAccountRepository.findAccountIdByMediaUserId(targetId).bind()
            if (ownerAccountId != null) {
                val linked = authAccountRepository.findLinkedMediaUserIds(ownerAccountId).bind()
                return@either if (linked.isEmpty()) setOf(targetId) else linked
            }
            val linkedForAccount = authAccountRepository.findLinkedMediaUserIds(targetId).bind()
            if (linkedForAccount.isNotEmpty()) {
                return@either linkedForAccount
            }
            if (authAccountRepository.findById(targetId).bind() != null) {
                return@either emptySet()
            }
            setOf(targetId)
        }

    suspend fun resolveOverviewViewerMap(
        userRepository: UserRepository,
        authAccountRepository: AuthAccountRepository?,
        rawUserIds: Collection<String>,
        serverId: String?,
        preloadedLinks: Map<String, String>? = null,
    ): Either<MiruError, Map<String, UserRecord>> =
        either {
            if (rawUserIds.isEmpty()) {
                return@either emptyMap()
            }
            val directUsers = userRepository.findByIds(rawUserIds).bind().associateBy { it.id }
            if (authAccountRepository == null) {
                return@either directUsers
            }

            if (serverId != null) {
                val links = authAccountRepository.findAccountIdsByMediaUserIds(directUsers.keys).bind()
                if (links.isEmpty()) {
                    return@either directUsers
                }
                val accountProfiles = authAccountRepository.findProfilesByIds(links.values).bind()
                return@either directUsers.mapValues { (uid, user) ->
                    val accountDisplay = links[uid]?.let { accountProfiles[it]?.displayName.normalizedDisplayName() }
                    if (accountDisplay != null) user.copy(displayName = accountDisplay) else user
                }
            }

            val allLinks = preloadedLinks ?: authAccountRepository.findAllMediaUserLinks().bind()
            val activeAccountIds = rawUserIds.mapNotNullTo(HashSet()) { allLinks[it] }
            if (activeAccountIds.isEmpty()) {
                return@either directUsers
            }

            val accountProfilesById = authAccountRepository.findProfilesByIds(activeAccountIds).bind()
            val siblingMediaIds =
                allLinks.entries
                    .filter { (_, accId) -> accId in activeAccountIds }
                    .map { (mediaUserId, _) -> mediaUserId }
            val missingMediaIds = siblingMediaIds.filter { it !in directUsers }
            val allUsersById =
                if (missingMediaIds.isEmpty()) {
                    directUsers
                } else {
                    directUsers + userRepository.findByIds(missingMediaIds).bind().associateBy { it.id }
                }

            val result = LinkedHashMap<String, UserRecord>(directUsers.size + activeAccountIds.size)
            for ((uid, user) in allUsersById) {
                if (uid !in allLinks) {
                    result[uid] = user
                }
            }

            val mediaIdsByAccount =
                allLinks.entries
                    .filter { (_, accId) -> accId in activeAccountIds }
                    .groupBy(keySelector = { it.value }, valueTransform = { it.key })

            for ((accountId, linkedMediaIds) in mediaIdsByAccount) {
                val profiles =
                    linkedMediaIds
                        .mapNotNull { allUsersById[it] }
                        .sortedWith(stableProfileComparator)
                val primary = profiles.firstOrNull() ?: continue
                val bestDisplayName = resolveDisplayName(accountProfilesById[accountId]?.displayName, profiles)
                result[accountId] =
                    primary.copy(
                        id = accountId,
                        displayName = bestDisplayName,
                    )
            }

            result
        }

    suspend fun resolveMediaUserIdentities(
        userRepository: UserRepository,
        authAccountRepository: AuthAccountRepository?,
        mediaUserIds: Collection<String>,
        preloadedUsersById: Map<String, UserRecord>? = null,
    ): Either<MiruError, Map<String, ResolvedMediaUserIdentity>> =
        either {
            val uniqueIds = mediaUserIds.distinct()
            if (uniqueIds.isEmpty()) return@either emptyMap()

            val directUsers =
                preloadedUsersById
                    ?: userRepository.findByIds(uniqueIds).bind().associateBy { it.id }

            if (authAccountRepository == null) {
                return@either uniqueIds.associateWith { uid ->
                    val user = directUsers[uid]
                    ResolvedMediaUserIdentity(
                        user = user,
                        displayName = user?.displayName.normalizedDisplayName(),
                        avatarUrl = user?.avatarProxyUrl(),
                    )
                }
            }

            val linksByTarget = authAccountRepository.findAccountIdsByMediaUserIds(uniqueIds).bind()
            val accountIds = linksByTarget.values.toSet()
            if (accountIds.isEmpty()) {
                return@either uniqueIds.associateWith { uid ->
                    val user = directUsers[uid]
                    ResolvedMediaUserIdentity(
                        user = user,
                        displayName = user?.displayName.normalizedDisplayName(),
                        avatarUrl = user?.avatarProxyUrl(),
                    )
                }
            }

            val accountProfilesById = authAccountRepository.findProfilesByIds(accountIds).bind()
            val linkedIdsByAccount: Map<String, Set<String>> =
                if (accountIds.size == 1) {
                    val singleAccountId = accountIds.single()
                    mapOf(singleAccountId to authAccountRepository.findLinkedMediaUserIds(singleAccountId).bind())
                } else {
                    val allLinks = authAccountRepository.findAllMediaUserLinks().bind()
                    allLinks.entries
                        .filter { (_, accId) -> accId in accountIds }
                        .groupBy(keySelector = { it.value }, valueTransform = { it.key })
                        .mapValues { it.value.toSet() }
                }

            val allNeededMediaIds = linkedIdsByAccount.values.flatten().distinct()
            val missingIds = allNeededMediaIds.filter { it !in directUsers }
            val allUsersById =
                if (missingIds.isEmpty()) {
                    directUsers
                } else {
                    directUsers + userRepository.findByIds(missingIds).bind().associateBy { it.id }
                }

            uniqueIds.associateWith { uid ->
                val direct = directUsers[uid]
                val accountId = linksByTarget[uid]
                val accountProfile = accountId?.let { accountProfilesById[it] }
                val orderedSiblings =
                    if (accountId != null) {
                        linkedIdsByAccount[accountId]
                            .orEmpty()
                            .mapNotNull { allUsersById[it] }
                            .ifEmpty { listOfNotNull(direct) }
                            .sortedWith(stableProfileComparator)
                    } else {
                        listOfNotNull(direct)
                    }
                ResolvedMediaUserIdentity(
                    user = direct,
                    displayName = resolveDisplayName(accountProfile?.displayName, orderedSiblings),
                    avatarUrl =
                        resolveAvatarUrl(
                            accountProfile = accountProfile,
                            profiles = orderedSiblings,
                            preferredUser = direct,
                        ),
                )
            }
        }

    fun buildUserSummaryDto(
        id: String,
        accountId: String?,
        orderedProfiles: List<UserRecord>,
        serversById: Map<String, MediaServerRecord>,
        activeStreamCount: Int,
        totalPlays: Long,
        totalWatchDurationSec: Long,
        linkedProfileRecords: List<UserRecord> = orderedProfiles,
        accountProfile: AuthAccountProfileRecord? = null,
    ): UserSummaryDto {
        val primary = orderedProfiles.first()
        val primaryDisplayName = resolveDisplayName(accountProfile?.displayName, orderedProfiles)
        val primaryServer = serversById[primary.serverId]
        val avatarUrl = resolveAvatarUrl(accountProfile, orderedProfiles)
        val lastSeenAtMs = orderedProfiles.mapNotNull { it.lastSeenAtMs }.maxOrNull()
        val profileDtos =
            linkedProfileRecords
                .map { profile -> profile.toLinkedProfileDto(serversById[profile.serverId]) }
                .sortedWith(displayProfileComparator(serversById))

        return UserSummaryDto(
            id = id,
            accountId = accountId,
            serverId = primary.serverId,
            serverName = primaryServer?.name?.normalizedDisplayName(),
            serverType = primaryServer?.serverType?.lowercase(),
            externalUserId = primary.externalUserId,
            username = primary.username,
            displayName = primaryDisplayName,
            isAdmin = orderedProfiles.any { it.isAdmin },
            avatarUrl = avatarUrl,
            isStreaming = activeStreamCount > 0,
            activeStreamCount = activeStreamCount,
            lastSeenAtMs = lastSeenAtMs,
            totalPlays = totalPlays,
            totalWatchDurationSec = totalWatchDurationSec,
            linkedProfiles = profileDtos,
        )
    }

    fun bestMediaProfileDisplayName(profiles: Collection<UserRecord>): String? {
        var fallback: String? = null
        for (profile in profiles) {
            val candidate = profile.displayName.normalizedDisplayName() ?: continue
            if (candidate != profile.username.trim()) {
                return candidate
            }
            if (fallback == null) {
                fallback = candidate
            }
        }
        return fallback
    }

    fun resolveDisplayName(
        accountDisplayName: String?,
        profiles: Collection<UserRecord>,
    ): String? = accountDisplayName.normalizedDisplayName() ?: bestMediaProfileDisplayName(profiles)

    /**
     * The URL of an account's own (uploaded or imported) avatar, versioned by its update time so
     * browsers drop a cached copy as soon as it changes; null when the account has none.
     */
    fun accountAvatarUrl(
        accountId: String,
        avatarUpdatedAtMs: Long?,
    ): String? = avatarUpdatedAtMs?.let { "/api/v1/users/$accountId/avatar?v=$it" }

    /** The account's own avatar when it has one, else the preferred or first linked media profile's picture. */
    fun resolveAvatarUrl(
        accountProfile: AuthAccountProfileRecord?,
        profiles: Collection<UserRecord>,
        preferredUser: UserRecord? = null,
    ): String? =
        accountProfile?.let { accountAvatarUrl(it.id, it.avatarUpdatedAtMs) }
            ?: preferredUser?.avatarProxyUrl()
            ?: profiles.firstNotNullOfOrNull { it.avatarProxyUrl() }

    fun AuthAccountRecord.toProfileRecord(): AuthAccountProfileRecord =
        AuthAccountProfileRecord(
            id = id,
            username = username,
            displayName = displayName,
            avatarContentType = avatarContentType,
            avatarSourceServerId = avatarSourceServerId,
            avatarUpdatedAtMs = avatarUpdatedAtMs,
        )

    fun UserRecord.toLinkedProfileDto(server: MediaServerRecord?): LinkedUserProfileDto =
        LinkedUserProfileDto(
            userId = id,
            serverId = serverId,
            serverName = server?.name?.normalizedDisplayName(),
            serverType = server?.serverType?.lowercase(),
            externalUserId = externalUserId,
            username = username,
            displayName = displayName.normalizedDisplayName(),
            isAdmin = isAdmin,
            avatarUrl = avatarProxyUrl(),
            lastSeenAtMs = lastSeenAtMs,
        )

    fun UserRecord.avatarProxyUrl(): String? =
        thumbUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { "/api/v1/users/$id/avatar" }
}
