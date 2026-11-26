package app.hononeko.miru.library

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.LibraryItemInfo
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.model.UserLibraryAccessRecord
import app.hononeko.miru.persistence.repository.LibraryRepository
import app.hononeko.miru.service.LibrarySyncTrigger
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import app.hononeko.miru.logging.kv
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LibraryStatsCollector(
    private val adapter: MediaServerAdapter,
    private val serverIdProvider: suspend () -> Either<MiruError, String>,
    private val libraryRepository: LibraryRepository,
    private val scope: CoroutineScope,
    private val deepScanInterval: Duration = DEFAULT_DEEP_SCAN_INTERVAL,
    private val accessSyncInterval: Duration = DEFAULT_ACCESS_SYNC_INTERVAL,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val timelineDebounceWindow: Duration = DEFAULT_TIMELINE_DEBOUNCE_WINDOW,
    private val timelineMaxWait: Duration = DEFAULT_TIMELINE_MAX_WAIT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : RealtimeLifecycle,
    LibrarySyncTrigger {
    init {
        require(deepScanInterval.isPositive()) { "deepScanInterval must be positive" }
        require(accessSyncInterval.isPositive()) { "accessSyncInterval must be positive" }
        require(pageSize > 0) { "pageSize must be positive" }
        require(timelineDebounceWindow.isPositive()) { "timelineDebounceWindow must be positive" }
        require(timelineMaxWait >= timelineDebounceWindow) { "timelineMaxWait must be >= timelineDebounceWindow" }
    }

    private data class SectionCounts(
        val itemCount: Int,
        val childCount: Int,
        val leafCount: Int,
    )

    private data class DeepSectionOutcome(
        val itemsSynced: Int,
        val prunedItems: Int,
    )

    private val logger = LoggerFactory.getLogger(LibraryStatsCollector::class.java)
    private val syncMutex = Mutex()
    private val lifecycleLock = Any()
    private val pendingLock = Any()
    private var job: Job? = null
    private var pendingAllSections = false
    private val pendingSectionKeys = linkedSetOf<String>()

    private val _syncEvents =
        MutableSharedFlow<LibrarySyncSummary>(
            extraBufferCapacity = SYNC_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val syncEvents: SharedFlow<LibrarySyncSummary> = _syncEvents.asSharedFlow()

    override fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            job = scope.launch(CoroutineName("library-stats-collector")) { run() }
        }
    }

    override suspend fun stop() {
        val current = synchronized(lifecycleLock) { job.also { job = null } }
        current?.cancelAndJoin()
    }

    override suspend fun triggerSync(
        deep: Boolean,
        sectionKey: String?,
    ): Either<MiruError, LibrarySyncSummary> =
        if (deep) {
            syncDeep(sectionKeyFilter = sectionKey)
        } else {
            syncFastCounts(sectionKeyFilter = sectionKey)
        }

    suspend fun syncFastCounts(sectionKeyFilter: String? = null): Either<MiruError, LibrarySyncSummary> =
        syncMutex.withLock {
            syncFastCountsLocked(sectionKeyFilter)
        }

    suspend fun syncDeep(sectionKeyFilter: String? = null): Either<MiruError, LibrarySyncSummary> =
        syncMutex.withLock {
            syncDeepLocked(sectionKeyFilter)
        }

    /**
     * Counts all sections. Items that were cached before Miru kept the show or artist they belong to are also scanned
     * again, which is the case once after an upgrade.
     */
    suspend fun syncInitial(): Either<MiruError, LibrarySyncSummary> =
        syncMutex.withLock {
            either {
                val counted = syncFastCountsLocked(sectionKeyFilter = null).bind()
                val serverId = serverIdProvider().bind()
                if (libraryRepository.lacksGrandparentKeys(serverId).bind()) {
                    syncDeepLocked(sectionKeyFilter = null).bind()
                } else {
                    counted
                }
            }
        }

    suspend fun syncLibraryAccess(): Either<MiruError, Int> =
        syncMutex.withLock {
            syncLibraryAccessLocked()
        }

    private suspend fun syncLibraryAccessLocked(): Either<MiruError, Int> =
        either {
            val serverId = serverIdProvider().bind()
            val entries = adapter.fetchUserLibraryAccess().bind()
            val nowMs = clock()
            val records =
                entries.mapNotNull { entry ->
                    val externalUserId = entry.externalUserId.trim()
                    if (externalUserId.isEmpty()) {
                        null
                    } else {
                        UserLibraryAccessRecord(
                            serverId = serverId,
                            externalUserId = externalUserId,
                            allLibraries = entry.allLibraries,
                            sectionKeys = if (entry.allLibraries) emptySet() else entry.sectionKeys,
                            updatedAtMs = nowMs,
                        )
                    }
                }
            libraryRepository.replaceUserLibraryAccess(serverId, records).bind()
            records.size
        }

    private suspend fun syncLibraryAccessBestEffort(serverId: String) {
        try {
            val entries =
                adapter.fetchUserLibraryAccess().fold(
                    ifLeft = { error ->
                        logger.warn("Shared library access sync failed: {}", error.message)
                        return
                    },
                    ifRight = { it },
                )
            val nowMs = clock()
            val records =
                entries.mapNotNull { entry ->
                    val externalUserId = entry.externalUserId.trim()
                    if (externalUserId.isEmpty()) {
                        null
                    } else {
                        UserLibraryAccessRecord(
                            serverId = serverId,
                            externalUserId = externalUserId,
                            allLibraries = entry.allLibraries,
                            sectionKeys = if (entry.allLibraries) emptySet() else entry.sectionKeys,
                            updatedAtMs = nowMs,
                        )
                    }
                }
            libraryRepository.replaceUserLibraryAccess(serverId, records).onLeft { error ->
                logger.warn("Failed to persist shared library access: {}", error.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Unexpected error during shared library access sync: {}", e.message)
        }
    }

    private suspend fun syncFastCountsLocked(sectionKeyFilter: String?): Either<MiruError, LibrarySyncSummary> =
        either {
            val serverId = serverIdProvider().bind()
            val targetSections = resolveTargetSections(sectionKeyFilter).bind()
            val existingByKey = libraryRepository.findSectionsByServer(serverId).bind().associateBy { it.sectionKey }
            val nowMs = clock()

            var syncedSections = 0
            var failedSections = 0
            var firstError: MiruError? = null

            for (section in targetSections) {
                fetchSectionCounts(section).fold(
                    ifLeft = { error ->
                        failedSections++
                        if (firstError == null) firstError = error
                        logger.warn(
                            "Fast count sync failed for section {} ({})",
                            kv("sectionKey", section.sectionKey),
                            error.message,
                        )
                    },
                    ifRight = { counts ->
                        val existing = existingByKey[section.sectionKey]
                        val record =
                            LibrarySectionRecord(
                                serverId = serverId,
                                sectionKey = section.sectionKey,
                                title = section.title,
                                sectionType = section.type.name.lowercase(),
                                itemCount = counts.itemCount,
                                childCount = counts.childCount,
                                leafCount = counts.leafCount,
                                totalSizeBytes = existing?.totalSizeBytes ?: 0L,
                                totalDurationMs = existing?.totalDurationMs ?: 0L,
                                lastSyncedAtMs = nowMs,
                                updatedAtMs = nowMs,
                            )
                        libraryRepository.upsertSection(record).bind()
                        syncedSections++
                    },
                )
            }

            val summary =
                LibrarySyncSummary(
                    mode = LibrarySyncMode.FAST,
                    syncedSections = syncedSections,
                    failedSections = failedSections,
                    totalItemsSynced = 0,
                    snapshotsCreated = 0,
                    prunedItems = 0,
                    syncedAtMs = nowMs,
                )
            if (firstError != null) {
                if (syncedSections > 0) {
                    _syncEvents.tryEmit(summary)
                }
                raise(firstError)
            }

            syncLibraryAccessBestEffort(serverId)
            _syncEvents.tryEmit(summary)
            summary
        }

    private suspend fun syncDeepLocked(sectionKeyFilter: String?): Either<MiruError, LibrarySyncSummary> =
        either {
            val serverId = serverIdProvider().bind()
            val targetSections = resolveTargetSections(sectionKeyFilter).bind()
            val nowMs = clock()

            var syncedSections = 0
            var failedSections = 0
            var totalItemsSynced = 0
            var snapshotsCreated = 0
            var prunedItems = 0
            var firstError: MiruError? = null

            for (section in targetSections) {
                syncSectionDeep(serverId = serverId, section = section).fold(
                    ifLeft = { error ->
                        failedSections++
                        if (firstError == null) firstError = error
                        logger.warn(
                            "Deep library scan aborted for section {} ({}): preserving existing cached items and snapshots",
                            kv("sectionKey", section.sectionKey),
                            error.message,
                        )
                    },
                    ifRight = { outcome ->
                        syncedSections++
                        totalItemsSynced += outcome.itemsSynced
                        snapshotsCreated++
                        prunedItems += outcome.prunedItems
                    },
                )
            }

            val summary =
                LibrarySyncSummary(
                    mode = LibrarySyncMode.DEEP,
                    syncedSections = syncedSections,
                    failedSections = failedSections,
                    totalItemsSynced = totalItemsSynced,
                    snapshotsCreated = snapshotsCreated,
                    prunedItems = prunedItems,
                    syncedAtMs = nowMs,
                )
            if (firstError != null) {
                if (syncedSections > 0) {
                    _syncEvents.tryEmit(summary)
                }
                raise(firstError)
            }

            syncLibraryAccessBestEffort(serverId)
            _syncEvents.tryEmit(summary)
            summary
        }

    private suspend fun syncSectionDeep(
        serverId: String,
        section: LibrarySectionInfo,
    ): Either<MiruError, DeepSectionOutcome> =
        either {
            val counts = fetchSectionCounts(section).bind()
            val leafTypeCode = leafMediaTypeCode(section.type)

            val seenKeys = linkedSetOf<String>()
            val byResolution = linkedMapOf<String, Int>()
            val byVideoCodec = linkedMapOf<String, Int>()
            val byAudioCodec = linkedMapOf<String, Int>()
            var totalSizeBytes = 0L
            var totalDurationMs = 0L
            var itemsSynced = 0

            var start = 0
            while (true) {
                val page =
                    adapter
                        .fetchLibraryItems(
                            sectionKey = section.sectionKey,
                            start = start,
                            size = pageSize,
                            mediaTypeCode = leafTypeCode,
                        ).bind()

                val items = page.items
                if (items.isEmpty()) break

                val itemTimestampMs = clock()
                val pageItems = ArrayList<MediaItemCacheRecord>(items.size)
                for (item in items) {
                    if (item.ratingKey.isBlank() || !seenKeys.add(item.ratingKey)) {
                        continue
                    }
                    totalSizeBytes += (item.fileSizeBytes ?: 0L).coerceAtLeast(0L)
                    totalDurationMs += (item.durationMs ?: 0L).coerceAtLeast(0L)
                    accumulateHistograms(
                        sectionType = section.type,
                        item = item,
                        byResolution = byResolution,
                        byVideoCodec = byVideoCodec,
                        byAudioCodec = byAudioCodec,
                    )
                    pageItems += item.toCacheRecord(serverId = serverId, sectionKey = section.sectionKey, nowMs = itemTimestampMs)
                }

                if (pageItems.isNotEmpty()) {
                    libraryRepository.upsertMediaItems(pageItems).bind()
                    itemsSynced += pageItems.size
                }

                val nextStart = start + items.size
                if (nextStart >= page.totalSize) break
                start = nextStart
            }

            val completedAtMs = clock()
            val pruned = libraryRepository.deleteItemsNotIn(serverId, section.sectionKey, seenKeys).bind()

            val sectionRecord =
                LibrarySectionRecord(
                    serverId = serverId,
                    sectionKey = section.sectionKey,
                    title = section.title,
                    sectionType = section.type.name.lowercase(),
                    itemCount = counts.itemCount,
                    childCount = counts.childCount,
                    leafCount = counts.leafCount,
                    totalSizeBytes = totalSizeBytes,
                    totalDurationMs = totalDurationMs,
                    lastSyncedAtMs = completedAtMs,
                    updatedAtMs = completedAtMs,
                )
            libraryRepository.upsertSection(sectionRecord).bind()

            val aggregates =
                LibraryAggregates(
                    byResolution = byResolution.toMap(),
                    byVideoCodec = byVideoCodec.toMap(),
                    byAudioCodec = byAudioCodec.toMap(),
                )
            val snapshot =
                LibrarySnapshotRecord(
                    id = idGenerator(),
                    serverId = serverId,
                    sectionKey = section.sectionKey,
                    capturedAtMs = completedAtMs,
                    itemCount = counts.itemCount,
                    childCount = counts.childCount,
                    leafCount = counts.leafCount,
                    totalSizeBytes = totalSizeBytes,
                    totalDurationMs = totalDurationMs,
                    aggregatesJson = MiruJson.encodeToString(aggregates),
                )
            libraryRepository.insertSnapshot(snapshot).bind()

            DeepSectionOutcome(
                itemsSynced = itemsSynced,
                prunedItems = pruned,
            )
        }

    private suspend fun resolveTargetSections(sectionKeyFilter: String?): Either<MiruError, List<LibrarySectionInfo>> =
        either {
            val normalizedFilter = sectionKeyFilter?.trim()?.takeIf { it.isNotEmpty() }
            val allSections = adapter.fetchLibrarySections().bind()
            if (normalizedFilter == null) {
                allSections
            } else {
                val filtered = allSections.filter { it.sectionKey == normalizedFilter }
                ensure(filtered.isNotEmpty()) {
                    MiruError.NotFound(resource = "Library section", id = normalizedFilter)
                }
                filtered
            }
        }

    private suspend fun fetchSectionCounts(section: LibrarySectionInfo): Either<MiruError, SectionCounts> =
        either {
            when (section.type) {
                LibrarySectionType.MOVIE,
                LibrarySectionType.PHOTO,
                LibrarySectionType.OTHER,
                -> {
                    val count = adapter.fetchLibraryCount(section.sectionKey).bind()
                    SectionCounts(itemCount = count, childCount = 0, leafCount = count)
                }

                LibrarySectionType.SHOW -> {
                    val shows = adapter.fetchLibraryCount(section.sectionKey, mediaTypeCode = PLEX_TYPE_SHOW).bind()
                    val seasons = adapter.fetchLibraryCount(section.sectionKey, mediaTypeCode = PLEX_TYPE_SEASON).bind()
                    val episodes = adapter.fetchLibraryCount(section.sectionKey, mediaTypeCode = PLEX_TYPE_EPISODE).bind()
                    SectionCounts(itemCount = shows, childCount = seasons, leafCount = episodes)
                }

                LibrarySectionType.ARTIST -> {
                    val artists = adapter.fetchLibraryCount(section.sectionKey, mediaTypeCode = PLEX_TYPE_ARTIST).bind()
                    val albums = adapter.fetchLibraryCount(section.sectionKey, mediaTypeCode = PLEX_TYPE_ALBUM).bind()
                    val tracks = adapter.fetchLibraryCount(section.sectionKey, mediaTypeCode = PLEX_TYPE_TRACK).bind()
                    SectionCounts(itemCount = artists, childCount = albums, leafCount = tracks)
                }
            }
        }

    private fun leafMediaTypeCode(sectionType: LibrarySectionType): Int? =
        when (sectionType) {
            LibrarySectionType.SHOW -> PLEX_TYPE_EPISODE
            LibrarySectionType.ARTIST -> PLEX_TYPE_TRACK
            LibrarySectionType.MOVIE,
            LibrarySectionType.PHOTO,
            LibrarySectionType.OTHER,
            -> null
        }

    private fun accumulateHistograms(
        sectionType: LibrarySectionType,
        item: LibraryItemInfo,
        byResolution: MutableMap<String, Int>,
        byVideoCodec: MutableMap<String, Int>,
        byAudioCodec: MutableMap<String, Int>,
    ) {
        when (sectionType) {
            LibrarySectionType.ARTIST -> {
                if (!item.videoResolution.isNullOrBlank()) {
                    val res = LibraryNormalizer.normalizeResolution(item.videoResolution)
                    byResolution[res] = (byResolution[res] ?: 0) + 1
                }
                if (!item.videoCodec.isNullOrBlank()) {
                    val vCodec = LibraryNormalizer.normalizeCodec(item.videoCodec)
                    byVideoCodec[vCodec] = (byVideoCodec[vCodec] ?: 0) + 1
                }
                val aCodec = LibraryNormalizer.normalizeCodec(item.audioCodec)
                byAudioCodec[aCodec] = (byAudioCodec[aCodec] ?: 0) + 1
            }

            LibrarySectionType.PHOTO -> Unit

            LibrarySectionType.MOVIE,
            LibrarySectionType.SHOW,
            LibrarySectionType.OTHER,
            -> {
                val res = LibraryNormalizer.normalizeResolution(item.videoResolution)
                byResolution[res] = (byResolution[res] ?: 0) + 1

                val vCodec = LibraryNormalizer.normalizeCodec(item.videoCodec)
                byVideoCodec[vCodec] = (byVideoCodec[vCodec] ?: 0) + 1

                val aCodec = LibraryNormalizer.normalizeCodec(item.audioCodec)
                byAudioCodec[aCodec] = (byAudioCodec[aCodec] ?: 0) + 1
            }
        }
    }

    private fun LibraryItemInfo.toCacheRecord(
        serverId: String,
        sectionKey: String,
        nowMs: Long,
    ): MediaItemCacheRecord =
        MediaItemCacheRecord(
            serverId = serverId,
            ratingKey = ratingKey,
            sectionKey = sectionKey,
            mediaType = mediaType.name.lowercase(),
            title = title,
            parentTitle = parentTitle,
            grandparentTitle = grandparentTitle,
            year = year,
            thumbPath = thumbPath,
            durationMs = durationMs,
            tech =
                MediaItemTechRecord(
                    fileSizeBytes = fileSizeBytes,
                    videoResolution = videoResolution,
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                ),
            addedAtMs = addedAtEpochSeconds?.let { it * MILLIS_PER_SECOND } ?: nowMs,
            updatedAtMs = nowMs,
            grandparentRatingKey = grandparentRatingKey,
        )

    private suspend fun run() {
        supervisorScope {
            val dirtySections = Channel<String?>(Channel.UNLIMITED)
            val timelineSignals = Channel<Unit>(Channel.CONFLATED)
            val deepScanSignals = Channel<Unit>(Channel.CONFLATED)
            val accessSyncSignals = Channel<Unit>(Channel.CONFLATED)

            launch(CoroutineName("library-timeline-events")) {
                adapter
                    .events()
                    .filterIsInstance<MediaServerEvent.TimelineEvent>()
                    .catch { e -> logger.warn("Timeline event stream failed: {}", e.message) }
                    .collect { event ->
                        dirtySections.send(event.sectionId?.trim()?.takeIf { it.isNotEmpty() })
                    }
            }

            launch(CoroutineName("library-timeline-debounce")) {
                debounceTimeline(dirtySections, timelineSignals)
            }

            launch(CoroutineName("library-periodic-deep-scan")) {
                while (true) {
                    delay(deepScanInterval)
                    deepScanSignals.send(Unit)
                }
            }

            launch(CoroutineName("library-periodic-access-sync")) {
                while (true) {
                    delay(accessSyncInterval)
                    accessSyncSignals.send(Unit)
                }
            }

            launch(CoroutineName("library-sync-actor")) {
                runSafeSync("initial sync") { syncInitial() }
                while (true) {
                    select {
                        deepScanSignals.onReceive {
                            runSafeSync("periodic deep scan") { syncDeep(sectionKeyFilter = null) }
                        }
                        accessSyncSignals.onReceive {
                            runSafeAccessSync("periodic library access sync") { syncLibraryAccess() }
                        }
                        timelineSignals.onReceive {
                            val (allSections, sections) = drainPendingSections()
                            if (allSections) {
                                runSafeSync("timeline fast count sync") { syncFastCounts(sectionKeyFilter = null) }
                            } else {
                                for (sectionKey in sections) {
                                    runSafeSync("timeline deep scan ($sectionKey)") {
                                        syncDeep(sectionKeyFilter = sectionKey)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun debounceTimeline(
        dirtySections: ReceiveChannel<String?>,
        timelineSignals: Channel<Unit>,
    ) {
        while (true) {
            val first = dirtySections.receive()
            recordPendingSection(first)

            withTimeoutOrNull(timelineMaxWait) {
                while (true) {
                    val next = withTimeoutOrNull(timelineDebounceWindow) { dirtySections.receive() } ?: break
                    recordPendingSection(next)
                }
            }
            timelineSignals.send(Unit)
        }
    }

    private fun recordPendingSection(sectionKey: String?) {
        synchronized(pendingLock) {
            if (sectionKey == null) {
                pendingAllSections = true
            } else {
                pendingSectionKeys += sectionKey
            }
        }
    }

    private fun drainPendingSections(): Pair<Boolean, Set<String>> =
        synchronized(pendingLock) {
            val all = pendingAllSections
            val keys = pendingSectionKeys.toSet()
            pendingAllSections = false
            pendingSectionKeys.clear()
            all to keys
        }

    private suspend fun runSafeSync(
        label: String,
        block: suspend () -> Either<MiruError, LibrarySyncSummary>,
    ) {
        try {
            block().fold(
                ifLeft = { error ->
                    if (error is MiruError.UpstreamUnavailable && error.message.contains("not configured", ignoreCase = true)) {
                        logger.debug("Library {} skipped: {}", label, error.message)
                    } else {
                        logger.warn("Library {} failed: {}", label, error.message)
                    }
                },
                ifRight = { summary ->
                    logger.debug(
                        "Library {} completed {} {}",
                        label,
                        kv("sections", summary.syncedSections),
                        kv("items", summary.totalItemsSynced),
                    )
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error during library {}", label, e)
        }
    }

    private suspend fun runSafeAccessSync(
        label: String,
        block: suspend () -> Either<MiruError, Int>,
    ) {
        try {
            block().fold(
                ifLeft = { error ->
                    if (error is MiruError.UpstreamUnavailable && error.message.contains("not configured", ignoreCase = true)) {
                        logger.debug("Library {} skipped: {}", label, error.message)
                    } else {
                        logger.warn("Library {} failed: {}", label, error.message)
                    }
                },
                ifRight = { count ->
                    logger.debug("Library {} completed {}", label, kv("users", count))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error during {}", label, e)
        }
    }

    companion object {
        val DEFAULT_DEEP_SCAN_INTERVAL: Duration = 6.hours
        val DEFAULT_ACCESS_SYNC_INTERVAL: Duration = 15.minutes
        const val DEFAULT_PAGE_SIZE = 200
        val DEFAULT_TIMELINE_DEBOUNCE_WINDOW: Duration = 10.seconds
        val DEFAULT_TIMELINE_MAX_WAIT: Duration = 60.seconds

        private const val SYNC_EVENT_BUFFER_CAPACITY = 16
        private const val MILLIS_PER_SECOND = 1_000L
        private const val PLEX_TYPE_SHOW = 2
        private const val PLEX_TYPE_SEASON = 3
        private const val PLEX_TYPE_EPISODE = 4
        private const val PLEX_TYPE_ARTIST = 8
        private const val PLEX_TYPE_ALBUM = 9
        private const val PLEX_TYPE_TRACK = 10
    }
}
