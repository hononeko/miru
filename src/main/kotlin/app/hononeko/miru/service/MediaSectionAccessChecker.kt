package app.hononeko.miru.service

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.ResolvedLibraryAccess
import app.hononeko.miru.persistence.repository.LibraryRepository
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enforces per-principal library-section access permissions for direct media metadata and artwork lookups.
 *
 * `ADMIN` principals always bypass section restrictions; `USER` principals fail closed when unlinked
 * or when their linked Plex shares omit the media item's library section.
 */
class MediaSectionAccessChecker(
    private val libraryRepository: LibraryRepository?,
    private val adapter: MediaServerAdapter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
    private data class CachedSection(
        val sectionKey: String?,
        val cachedAtMs: Long,
    )

    private class BoundedSectionCache(
        private val maxEntries: Int,
    ) : LinkedHashMap<String, CachedSection>(INITIAL_CACHE_CAPACITY, LRU_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSection>?): Boolean =
            size > maxEntries
    }

    private val mutex = Mutex()
    private val sectionCache = BoundedSectionCache(maxCacheEntries)

    suspend fun requireAnyLibraryAccess(
        principal: MiruPrincipal,
        ratingKey: String,
    ): Either<MiruError, ResolvedLibraryAccess?> =
        either {
            if (principal.isAdmin()) {
                return@either null
            }
            val repo =
                libraryRepository ?: raise(forbiddenError(ratingKey))
            if (principal.linkedMediaUserIds.isEmpty()) {
                raise(forbiddenError(ratingKey))
            }
            val access = repo.findAccessibleLibrariesForMediaUsers(principal.linkedMediaUserIds).bind()
            ensure(access.allLibrariesServerIds.isNotEmpty() || access.allowedSections.isNotEmpty()) {
                forbiddenError(ratingKey)
            }
            access
        }

    suspend fun requireAccessForKnownSection(
        principal: MiruPrincipal,
        ratingKey: String,
        sectionKey: String?,
        preloadedAccess: ResolvedLibraryAccess? = null,
    ): Either<MiruError, Unit> =
        either {
            if (principal.isAdmin()) {
                return@either
            }
            val access = preloadedAccess ?: requireAnyLibraryAccess(principal, ratingKey).bind() ?: return@either
            recordKnownSection(ratingKey = ratingKey, sectionKey = sectionKey)
            val allowed = isSectionAccessible(access = access, sectionKey = sectionKey).bind()
            ensure(allowed) { forbiddenError(ratingKey) }
        }

    suspend fun requireAccessForRatingKey(
        principal: MiruPrincipal,
        ratingKey: String,
    ): Either<MiruError, Unit> =
        either {
            if (principal.isAdmin()) {
                return@either
            }
            val access = requireAnyLibraryAccess(principal, ratingKey).bind() ?: return@either
            val repo = libraryRepository ?: raise(forbiddenError(ratingKey))

            val dbPairs = repo.findSectionKeysByRatingKey(ratingKey).bind()
            if (dbPairs.isNotEmpty()) {
                val allowed = dbPairs.any { (serverId, sectionKey) -> access.canAccess(serverId, sectionKey) }
                ensure(allowed) { forbiddenError(ratingKey) }
                return@either
            }

            val resolvedSectionKey = resolveSectionKeyCached(ratingKey).bind()
            val allowed = isSectionAccessible(access = access, sectionKey = resolvedSectionKey).bind()
            ensure(allowed) { forbiddenError(ratingKey) }
        }

    private suspend fun recordKnownSection(
        ratingKey: String,
        sectionKey: String?,
    ) {
        val now = clock()
        val normalized = sectionKey?.trim()?.takeIf { it.isNotEmpty() }
        mutex.withLock {
            sectionCache[ratingKey] = CachedSection(sectionKey = normalized, cachedAtMs = now)
        }
    }

    private suspend fun resolveSectionKeyCached(ratingKey: String): Either<MiruError, String?> =
        either {
            val now = clock()
            val cached =
                mutex.withLock {
                    val entry = sectionCache[ratingKey]
                    if (entry != null && now - entry.cachedAtMs < cacheTtlMs) {
                        entry
                    } else {
                        null
                    }
                }
            if (cached != null) {
                return@either cached.sectionKey
            }

            val fetched = adapter.fetchMediaSectionKey(ratingKey).bind()?.trim()?.takeIf { it.isNotEmpty() }
            mutex.withLock {
                sectionCache[ratingKey] = CachedSection(sectionKey = fetched, cachedAtMs = clock())
            }
            fetched
        }

    private suspend fun isSectionAccessible(
        access: ResolvedLibraryAccess,
        sectionKey: String?,
    ): Either<MiruError.PersistenceError, Boolean> =
        either {
            val normalized = sectionKey?.trim()?.takeIf { it.isNotEmpty() }
            if (normalized == null) {
                return@either access.allLibrariesServerIds.isNotEmpty()
            }
            val repo = libraryRepository ?: return@either false
            val knownSections = repo.findSectionsByKey(normalized).bind()
            if (knownSections.isNotEmpty()) {
                knownSections.any { access.canAccess(it.serverId, it.sectionKey) }
            } else {
                access.allLibrariesServerIds.isNotEmpty() ||
                    access.allowedSections.any { it.second == normalized }
            }
        }

    private fun forbiddenError(ratingKey: String): MiruError.Forbidden =
        MiruError.Forbidden("Access to media '$ratingKey' is forbidden")

    companion object {
        const val DEFAULT_MAX_CACHE_ENTRIES = 4_096
        const val DEFAULT_CACHE_TTL_MS = 10L * 60L * 1_000L
        private const val INITIAL_CACHE_CAPACITY = 256
        private const val LRU_LOAD_FACTOR = 0.75f
    }
}
