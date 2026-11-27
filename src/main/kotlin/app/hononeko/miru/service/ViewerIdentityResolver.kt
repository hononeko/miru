package app.hononeko.miru.service

import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.LinkedUserProfileDto
import app.hononeko.miru.http.UserSummaryDto
import app.hononeko.miru.http.normalizedDisplayName
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.AuthAccountRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.either

internal data class ResolvedViewer(
    val canonicalId: String,
    val accountId: String?,
    val allProfiles: List<UserRecord>,
    val scopedProfiles: List<UserRecord>,
    val targetMediaUserIds: Set<String>,
    val primaryProfile: UserRecord,
    val primaryDisplayName: String?,
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

            val orderedAll = rawProfiles.sortedWith(stableProfileComparator)
            val scopedProfiles =
                if (serverId != null) {
                    orderedAll.filter { it.serverId == serverId }
                } else {
                    orderedAll
                }
            val effectiveProfiles = scopedProfiles.ifEmpty { orderedAll }
            val primaryProfile = effectiveProfiles.first()
            val primaryDisplayName = effectiveProfiles.firstNotNullOfOrNull { it.displayName.normalizedDisplayName() }
            val canonicalId =
                if (serverId != null) {
                    scopedProfiles.firstOrNull()?.id ?: accountId ?: targetId
                } else {
                    accountId ?: orderedAll.first().id
                }

            ResolvedViewer(
                canonicalId = canonicalId,
                accountId = accountId,
                allProfiles = orderedAll,
                scopedProfiles = scopedProfiles,
                targetMediaUserIds = scopedProfiles.mapTo(LinkedHashSet(scopedProfiles.size)) { it.id },
                primaryProfile = primaryProfile,
                primaryDisplayName = primaryDisplayName,
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
            if (serverId != null || authAccountRepository == null) {
                return@either directUsers
            }

            val allLinks = preloadedLinks ?: authAccountRepository.findAllMediaUserLinks().bind()
            val activeAccountIds = rawUserIds.mapNotNullTo(HashSet()) { allLinks[it] }
            if (activeAccountIds.isEmpty()) {
                return@either directUsers
            }

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
                val bestDisplayName = profiles.firstNotNullOfOrNull { it.displayName.normalizedDisplayName() }
                result[accountId] =
                    primary.copy(
                        id = accountId,
                        displayName = bestDisplayName,
                    )
            }

            result
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
    ): UserSummaryDto {
        val primary = orderedProfiles.first()
        val primaryDisplayName = orderedProfiles.firstNotNullOfOrNull { it.displayName.normalizedDisplayName() }
        val primaryServer = serversById[primary.serverId]
        val avatarUrl = orderedProfiles.firstNotNullOfOrNull { it.avatarProxyUrl() }
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
        thumbUrl?.let { "/api/v1/users/$id/avatar" }
}
