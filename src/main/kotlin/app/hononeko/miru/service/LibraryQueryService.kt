package app.hononeko.miru.service

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.library.LibraryAggregates
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.repository.LibraryRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

fun interface LibrarySyncTrigger {
    suspend fun triggerSync(
        deep: Boolean,
        sectionKey: String?,
    ): Either<MiruError, LibrarySyncSummary>
}

data class LibraryStatsResult(
    val section: LibrarySectionRecord,
    val aggregates: LibraryAggregates,
    val snapshots: List<LibrarySnapshotRecord>,
)

data class LibraryRecentResult(
    val section: LibrarySectionRecord,
    val items: List<MediaItemCacheRecord>,
)

class LibraryQueryService(
    private val libraryRepository: LibraryRepository,
) {
    private val logger = LoggerFactory.getLogger(LibraryQueryService::class.java)

    suspend fun listLibraries(
        principal: MiruPrincipal,
        serverId: String? = null,
    ): Either<MiruError, List<LibrarySectionRecord>> =
        either {
            if (!principal.isAdmin() && principal.linkedMediaUserIds.isEmpty()) {
                return@either emptyList()
            }
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val sections =
                if (normalizedServerId != null) {
                    libraryRepository.findSectionsByServer(normalizedServerId).bind()
                } else {
                    libraryRepository.findAllSections().bind()
                }
            if (principal.isAdmin()) {
                sections
            } else {
                val access =
                    libraryRepository
                        .findAccessibleLibrariesForMediaUsers(principal.linkedMediaUserIds)
                        .bind()
                sections.filter { access.canAccess(it.serverId, it.sectionKey) }
            }
        }

    suspend fun getLibrarySection(
        principal: MiruPrincipal,
        sectionId: String,
        serverId: String? = null,
    ): Either<MiruError, LibrarySectionRecord> =
        resolveAccessibleSection(
            principal = principal,
            sectionId = sectionId,
            serverId = serverId,
        )

    suspend fun getLibraryStats(
        principal: MiruPrincipal,
        sectionId: String,
        serverId: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = DEFAULT_STATS_LIMIT,
    ): Either<MiruError, LibraryStatsResult> =
        either {
            validateStatsParams(fromMs = fromMs, toMs = toMs, limit = limit).bind()
            val section = resolveAccessibleSection(principal = principal, sectionId = sectionId, serverId = serverId).bind()

            val snapshots =
                libraryRepository
                    .findSnapshots(
                        serverId = section.serverId,
                        sectionKey = section.sectionKey,
                        limit = limit,
                        fromMs = fromMs,
                        toMs = toMs,
                    ).bind()

            val latestSnapshot =
                if (fromMs == null && toMs == null) {
                    snapshots.firstOrNull()
                } else {
                    libraryRepository
                        .findSnapshots(
                            serverId = section.serverId,
                            sectionKey = section.sectionKey,
                            limit = 1,
                        ).bind()
                        .firstOrNull()
                }

            val aggregates = decodeAggregates(latestSnapshot?.aggregatesJson, section.sectionKey)

            LibraryStatsResult(
                section = section,
                aggregates = aggregates,
                snapshots = snapshots,
            )
        }

    suspend fun getRecentlyAdded(
        principal: MiruPrincipal,
        sectionId: String,
        serverId: String? = null,
        limit: Int = DEFAULT_RECENT_LIMIT,
    ): Either<MiruError, LibraryRecentResult> =
        either {
            ensure(limit in 1..MAX_RECENT_LIMIT) {
                MiruError.ValidationError(
                    message = "Invalid limit parameter",
                    fieldErrors = mapOf("limit" to "must be between 1 and $MAX_RECENT_LIMIT"),
                )
            }
            val section = resolveAccessibleSection(principal = principal, sectionId = sectionId, serverId = serverId).bind()
            val items =
                libraryRepository
                    .findRecentlyAdded(
                        serverId = section.serverId,
                        sectionKey = section.sectionKey,
                        limit = limit,
                    ).bind()

            LibraryRecentResult(
                section = section,
                items = items,
            )
        }

    private suspend fun resolveAccessibleSection(
        principal: MiruPrincipal,
        sectionId: String,
        serverId: String?,
    ): Either<MiruError, LibrarySectionRecord> =
        either {
            val trimmedId = sectionId.trim()
            ensure(trimmedId.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid library section id",
                    fieldErrors = mapOf("id" to "must not be blank"),
                )
            }
            if (!principal.isAdmin() && principal.linkedMediaUserIds.isEmpty()) {
                raise(MiruError.Forbidden("Access to library section '$trimmedId' is forbidden"))
            }

            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
            val access =
                if (principal.isAdmin()) {
                    null
                } else {
                    val resolved =
                        libraryRepository
                            .findAccessibleLibrariesForMediaUsers(principal.linkedMediaUserIds)
                            .bind()
                    val allowedOnTarget =
                        if (normalizedServerId != null) {
                            resolved.canAccess(normalizedServerId, trimmedId)
                        } else {
                            resolved.allLibrariesServerIds.isNotEmpty() ||
                                resolved.allowedSections.any { it.second == trimmedId }
                        }
                    ensure(allowedOnTarget) {
                        MiruError.Forbidden("Access to library section '$trimmedId' is forbidden")
                    }
                    resolved
                }

            val candidates =
                if (normalizedServerId != null) {
                    listOfNotNull(libraryRepository.findSection(normalizedServerId, trimmedId).bind())
                } else {
                    libraryRepository.findSectionsByKey(trimmedId).bind()
                }

            if (candidates.isEmpty()) {
                raise(MiruError.NotFound(resource = "Library section", id = trimmedId))
            }

            val visible =
                if (access == null) {
                    candidates
                } else {
                    val accessible = candidates.filter { access.canAccess(it.serverId, it.sectionKey) }
                    ensure(accessible.isNotEmpty()) {
                        MiruError.Forbidden("Access to library section '$trimmedId' is forbidden")
                    }
                    accessible
                }

            when (visible.size) {
                1 -> visible.first()
                else ->
                    raise(
                        MiruError.Conflict(
                            message = "Multiple media servers contain library section '$trimmedId'; specify serverId",
                        ),
                    )
            }
        }

    private fun validateStatsParams(
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
    ): Either<MiruError.ValidationError, Unit> =
        either {
            val errors = linkedMapOf<String, String>()
            if (fromMs != null && fromMs < 0L) {
                errors["from"] = "must be greater than or equal to 0"
            }
            if (toMs != null && toMs < 0L) {
                errors["to"] = "must be greater than or equal to 0"
            }
            if (fromMs != null && toMs != null && fromMs > toMs) {
                errors["from"] = "must be less than or equal to 'to'"
            }
            if (limit !in 1..MAX_STATS_LIMIT) {
                errors["limit"] = "must be between 1 and $MAX_STATS_LIMIT"
            }
            ensure(errors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid library stats query parameters",
                    fieldErrors = errors,
                )
            }
        }

    private fun decodeAggregates(
        rawJson: String?,
        sectionKey: String,
    ): LibraryAggregates {
        val text = rawJson?.takeIf { it.isNotBlank() } ?: return LibraryAggregates()
        return try {
            MiruJson.decodeFromString<LibraryAggregates>(text)
        } catch (e: SerializationException) {
            logger.warn("Failed to decode aggregates JSON for section '{}': {}", sectionKey, e.message)
            LibraryAggregates()
        } catch (e: IllegalArgumentException) {
            logger.warn("Malformed aggregates JSON for section '{}': {}", sectionKey, e.message)
            LibraryAggregates()
        }
    }

    companion object {
        const val DEFAULT_STATS_LIMIT = 90
        const val MAX_STATS_LIMIT = 365
        const val DEFAULT_RECENT_LIMIT = 25
        const val MAX_RECENT_LIMIT = 100
    }
}
