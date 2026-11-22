@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.library

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemInfo
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.LibraryRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private class CollectorHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val libraryRepository: ExposedLibraryRepository,
    val serverId: String = "srv-1",
    val clockMs: AtomicLong = AtomicLong(1_000_000L),
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private suspend fun createCollectorHarness(): CollectorHarness {
    val dir = Files.createTempDirectory("miru-collector-test-")
    val dbPath = dir.resolve("collector.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val serverRepo = ExposedMediaServerRepository(handle)
    serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Main Plex",
                serverType = "plex",
                machineId = "mach-1",
                baseUrl = "http://plex.local:32400",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    return CollectorHarness(
        tempDir = dir,
        dbHandle = handle,
        libraryRepository = ExposedLibraryRepository(handle),
    )
}

private class FakeMediaServerAdapter(
    var sectionsResult: Either<MiruError, List<LibrarySectionInfo>> = emptyList<LibrarySectionInfo>().right(),
    var countHandler: suspend (sectionKey: String, mediaTypeCode: Int?) -> Either<MiruError, Int> = { _, _ -> 0.right() },
    var itemsHandler: suspend (
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ) -> Either<MiruError, LibraryItemsPage> = { _, _, _, _ ->
        LibraryItemsPage(totalSize = 0, offset = 0, items = emptyList()).right()
    },
    val eventsFlow: MutableSharedFlow<MediaServerEvent> = MutableSharedFlow(extraBufferCapacity = 32),
) : MediaServerAdapter {
    override val serverType: String = "plex"

    val countCalls = mutableListOf<Pair<String, Int?>>()
    val itemsCalls = mutableListOf<Triple<String, Int, Int?>>()

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> = sectionsResult

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> {
        countCalls += sectionKey to mediaTypeCode
        return countHandler(sectionKey, mediaTypeCode)
    }

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> {
        itemsCalls += Triple(sectionKey, start, mediaTypeCode)
        return itemsHandler(sectionKey, start, size, mediaTypeCode)
    }

    override fun events(): Flow<MediaServerEvent> = eventsFlow

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> = error("unused")

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> = error("unused")

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> = error("unused")

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> = error("unused")

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> = error("unused")
}

private class InMemoryLibraryRepository : LibraryRepository {
    private val sections = linkedMapOf<Pair<String, String>, LibrarySectionRecord>()
    private val snapshots = mutableListOf<LibrarySnapshotRecord>()
    private val items = linkedMapOf<Pair<String, String>, MediaItemCacheRecord>()

    override suspend fun upsertSection(section: LibrarySectionRecord): Either<MiruError.PersistenceError, Unit> {
        sections[section.serverId to section.sectionKey] = section
        return Unit.right()
    }

    override suspend fun findAllSections(): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        sections.values.toList().right()

    override suspend fun findSection(
        serverId: String,
        sectionKey: String,
    ): Either<MiruError.PersistenceError, LibrarySectionRecord?> = sections[serverId to sectionKey].right()

    override suspend fun findSectionsByKey(sectionKey: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        sections.values.filter { it.sectionKey == sectionKey }.right()

    override suspend fun findSectionsByServer(serverId: String): Either<MiruError.PersistenceError, List<LibrarySectionRecord>> =
        sections.values.filter { it.serverId == serverId }.right()

    override suspend fun insertSnapshot(snapshot: LibrarySnapshotRecord): Either<MiruError.PersistenceError, Unit> {
        snapshots += snapshot
        return Unit.right()
    }

    override suspend fun findSnapshots(
        serverId: String,
        sectionKey: String,
        limit: Int,
        fromMs: Long?,
        toMs: Long?,
    ): Either<MiruError.PersistenceError, List<LibrarySnapshotRecord>> =
        snapshots
            .filter {
                it.serverId == serverId &&
                    it.sectionKey == sectionKey &&
                    (fromMs == null || it.capturedAtMs >= fromMs) &&
                    (toMs == null || it.capturedAtMs <= toMs)
            }.sortedByDescending { it.capturedAtMs }
            .take(limit)
            .right()

    override suspend fun upsertMediaItems(items: List<MediaItemCacheRecord>): Either<MiruError.PersistenceError, Int> {
        items.forEach { this.items[it.serverId to it.ratingKey] = it }
        return items.size.right()
    }

    override suspend fun findRecentlyAdded(
        serverId: String,
        sectionKey: String?,
        limit: Int,
    ): Either<MiruError.PersistenceError, List<MediaItemCacheRecord>> =
        items.values
            .filter { it.serverId == serverId && (sectionKey == null || it.sectionKey == sectionKey) }
            .sortedByDescending { it.addedAtMs }
            .take(limit)
            .right()

    override suspend fun deleteItemsNotIn(
        serverId: String,
        sectionKey: String,
        activeRatingKeys: Set<String>,
    ): Either<MiruError.PersistenceError, Int> {
        val toRemove =
            items.entries
                .filter { it.value.serverId == serverId && it.value.sectionKey == sectionKey && it.value.ratingKey !in activeRatingKeys }
                .map { it.key }
        toRemove.forEach { items.remove(it) }
        return toRemove.size.right()
    }
}

private fun movieItem(
    ratingKey: String,
    sectionKey: String = "1",
    sizeBytes: Long? = 1_000L,
    durationMs: Long? = 6_000_000L,
    resolution: String? = "1080",
    videoCodec: String? = "h264",
    audioCodec: String? = "aac",
    addedAtSec: Long? = 1_700_000_000L,
): LibraryItemInfo =
    LibraryItemInfo(
        ratingKey = ratingKey,
        sectionKey = sectionKey,
        mediaType = MediaType.MOVIE,
        title = "Movie $ratingKey",
        year = 2024,
        thumbPath = "/library/metadata/$ratingKey/thumb/1",
        durationMs = durationMs,
        fileSizeBytes = sizeBytes,
        videoResolution = resolution,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        addedAtEpochSeconds = addedAtSec,
    )

class LibraryStatsCollectorTest :
    FunSpec({
        test("normalizes resolutions and codecs consistently") {
            LibraryNormalizer.normalizeResolution("4k") shouldBe "4K"
            LibraryNormalizer.normalizeResolution("2160") shouldBe "4K"
            LibraryNormalizer.normalizeResolution("2160p") shouldBe "4K"
            LibraryNormalizer.normalizeResolution("1080") shouldBe "1080p"
            LibraryNormalizer.normalizeResolution("1080p") shouldBe "1080p"
            LibraryNormalizer.normalizeResolution("720") shouldBe "720p"
            LibraryNormalizer.normalizeResolution("480") shouldBe "SD"
            LibraryNormalizer.normalizeResolution("576") shouldBe "SD"
            LibraryNormalizer.normalizeResolution("sd") shouldBe "SD"
            LibraryNormalizer.normalizeResolution(null) shouldBe "Unknown"
            LibraryNormalizer.normalizeResolution("   ") shouldBe "Unknown"

            LibraryNormalizer.normalizeCodec("hevc") shouldBe "HEVC"
            LibraryNormalizer.normalizeCodec("h265") shouldBe "HEVC"
            LibraryNormalizer.normalizeCodec("h264") shouldBe "H.264"
            LibraryNormalizer.normalizeCodec("avc") shouldBe "H.264"
            LibraryNormalizer.normalizeCodec("av1") shouldBe "AV1"
            LibraryNormalizer.normalizeCodec("flac") shouldBe "FLAC"
            LibraryNormalizer.normalizeCodec("dts") shouldBe "DTS"
            LibraryNormalizer.normalizeCodec(null) shouldBe "Unknown"
            LibraryNormalizer.normalizeCodec("") shouldBe "Unknown"
        }

        test("syncFastCounts queries Size=0 counts only, resolves hierarchy for TV and Music, and preserves existing totals") {
            createCollectorHarness().use { harness ->
                runTest {
                    harness.libraryRepository
                        .upsertSection(
                            LibrarySectionRecord(
                                serverId = harness.serverId,
                                sectionKey = "1",
                                title = "Movies",
                                sectionType = "movie",
                                itemCount = 10,
                                childCount = 0,
                                leafCount = 10,
                                totalSizeBytes = 99_000_000_000L,
                                totalDurationMs = 500_000_000L,
                                lastSyncedAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()

                    val adapter =
                        FakeMediaServerAdapter(
                            sectionsResult =
                                listOf(
                                    LibrarySectionInfo("1", "Movies", LibrarySectionType.MOVIE),
                                    LibrarySectionInfo("2", "TV Shows", LibrarySectionType.SHOW),
                                    LibrarySectionInfo("3", "Music", LibrarySectionType.ARTIST),
                                    LibrarySectionInfo("4", "Photos", LibrarySectionType.PHOTO),
                                ).right(),
                            countHandler = { sectionKey, typeCode ->
                                when (sectionKey to typeCode) {
                                    "1" to null -> 250.right()
                                    "2" to 2 -> 42.right()
                                    "2" to 3 -> 115.right()
                                    "2" to 4 -> 1_240.right()
                                    "3" to 8 -> 80.right()
                                    "3" to 9 -> 310.right()
                                    "3" to 10 -> 3_500.right()
                                    "4" to null -> 600.right()
                                    else -> error("Unexpected count query: $sectionKey / $typeCode")
                                }
                            },
                        )

                    val collector =
                        LibraryStatsCollector(
                            adapter = adapter,
                            serverIdProvider = { harness.serverId.right() },
                            libraryRepository = harness.libraryRepository,
                            scope = this,
                            clock = { harness.clockMs.get() },
                        )

                    val summary = collector.triggerSync(deep = false, sectionKey = null).shouldBeRight()
                    summary.mode shouldBe LibrarySyncMode.FAST
                    summary.syncedSections shouldBe 4
                    summary.totalItemsSynced shouldBe 0
                    summary.snapshotsCreated shouldBe 0
                    adapter.itemsCalls.shouldBeEmpty()

                    adapter.countCalls shouldContainExactly
                        listOf(
                            "1" to null,
                            "2" to 2,
                            "2" to 3,
                            "2" to 4,
                            "3" to 8,
                            "3" to 9,
                            "3" to 10,
                            "4" to null,
                        )

                    val movies = harness.libraryRepository.findSection(harness.serverId, "1").shouldBeRight().shouldNotBeNull()
                    movies.itemCount shouldBe 250
                    movies.leafCount shouldBe 250
                    movies.totalSizeBytes shouldBe 99_000_000_000L
                    movies.totalDurationMs shouldBe 500_000_000L

                    val shows = harness.libraryRepository.findSection(harness.serverId, "2").shouldBeRight().shouldNotBeNull()
                    shows.itemCount shouldBe 42
                    shows.childCount shouldBe 115
                    shows.leafCount shouldBe 1_240

                    val music = harness.libraryRepository.findSection(harness.serverId, "3").shouldBeRight().shouldNotBeNull()
                    music.itemCount shouldBe 80
                    music.childCount shouldBe 310
                    music.leafCount shouldBe 3_500

                    collector.syncFastCounts("missing").shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()
                }
            }
        }

        test("syncDeep paginates pages, uses leaf type codes, normalizes aggregates, saves snapshots, and prunes items") {
            createCollectorHarness().use { harness ->
                runTest {
                    val movieItems =
                        listOf(
                            movieItem("m-1", "1", 10_000L, 1_000L, "4k", "hevc", "eac3"),
                            movieItem("m-2", "1", 20_000L, 2_000L, "2160", "h265", "dts"),
                            movieItem("m-3", "1", 30_000L, 3_000L, "1080", "h264", "aac"),
                            movieItem("m-4", "1", 40_000L, 4_000L, "720", "avc", "ac3"),
                            movieItem("m-5", "1", 50_000L, 5_000L, null, "av1", null),
                        )
                    val episodeItems =
                        listOf(
                            LibraryItemInfo(
                                ratingKey = "ep-1",
                                sectionKey = "2",
                                mediaType = MediaType.EPISODE,
                                title = "Pilot",
                                parentTitle = "Season 1",
                                grandparentTitle = "Show",
                                durationMs = 2_500L,
                                fileSizeBytes = 5_000L,
                                videoResolution = "480",
                                videoCodec = "mpeg4",
                                audioCodec = "mp3",
                                addedAtEpochSeconds = 1_700_000_100L,
                            ),
                        )

                    val trackItems =
                        listOf(
                            LibraryItemInfo(
                                ratingKey = "tr-1",
                                sectionKey = "3",
                                mediaType = MediaType.TRACK,
                                title = "Track 1",
                                durationMs = 3_000L,
                                fileSizeBytes = 8_000L,
                                audioCodec = "flac",
                                addedAtEpochSeconds = 1_700_000_200L,
                            ),
                        )

                    val adapter =
                        FakeMediaServerAdapter(
                            sectionsResult =
                                listOf(
                                    LibrarySectionInfo("1", "Movies", LibrarySectionType.MOVIE),
                                    LibrarySectionInfo("2", "TV Shows", LibrarySectionType.SHOW),
                                    LibrarySectionInfo("3", "Music", LibrarySectionType.ARTIST),
                                ).right(),
                            countHandler = { key, typeCode ->
                                when (key to typeCode) {
                                    "1" to null -> movieItems.size.right()
                                    "2" to 2 -> 1.right()
                                    "2" to 3 -> 1.right()
                                    "2" to 4 -> episodeItems.size.right()
                                    "3" to 8 -> 1.right()
                                    "3" to 9 -> 1.right()
                                    "3" to 10 -> trackItems.size.right()
                                    else -> 0.right()
                                }
                            },
                            itemsHandler = { key, start, size, typeCode ->
                                when (key) {
                                    "1" -> {
                                        typeCode shouldBe null
                                        val slice = movieItems.drop(start).take(size)
                                        LibraryItemsPage(totalSize = movieItems.size, offset = start, items = slice).right()
                                    }
                                    "2" -> {
                                        typeCode shouldBe 4
                                        LibraryItemsPage(totalSize = episodeItems.size, offset = start, items = episodeItems).right()
                                    }
                                    "3" -> {
                                        typeCode shouldBe 10
                                        LibraryItemsPage(totalSize = trackItems.size, offset = start, items = trackItems).right()
                                    }
                                    else -> error("Unexpected section $key")
                                }
                            },
                        )

                    val seq = AtomicInteger(1)
                    val collector =
                        LibraryStatsCollector(
                            adapter = adapter,
                            serverIdProvider = { harness.serverId.right() },
                            libraryRepository = harness.libraryRepository,
                            scope = this,
                            pageSize = 2,
                            clock = { harness.clockMs.get() },
                            idGenerator = { "snap-${seq.getAndIncrement()}" },
                        )

                    val summary = collector.triggerSync(deep = true, sectionKey = null).shouldBeRight()
                    summary.mode shouldBe LibrarySyncMode.DEEP
                    summary.syncedSections shouldBe 3
                    summary.totalItemsSynced shouldBe 7
                    summary.snapshotsCreated shouldBe 3
                    summary.prunedItems shouldBe 0

                    // Movies paginated 0, 2, 4; TV used type=4; Music used type=10
                    adapter.itemsCalls shouldContainExactly
                        listOf(
                            Triple("1", 0, null),
                            Triple("1", 2, null),
                            Triple("1", 4, null),
                            Triple("2", 0, 4),
                            Triple("3", 0, 10),
                        )

                    val moviesSection = harness.libraryRepository.findSection(harness.serverId, "1").shouldBeRight().shouldNotBeNull()
                    moviesSection.totalSizeBytes shouldBe 150_000L
                    moviesSection.totalDurationMs shouldBe 15_000L
                    moviesSection.itemCount shouldBe 5

                    val movieSnaps = harness.libraryRepository.findSnapshots(harness.serverId, "1").shouldBeRight()
                    movieSnaps shouldHaveSize 1
                    val movieAgg = MiruJson.decodeFromString<LibraryAggregates>(movieSnaps.first().aggregatesJson!!)
                    movieAgg.byResolution shouldContainExactly
                        mapOf(
                            "4K" to 2,
                            "1080p" to 1,
                            "720p" to 1,
                            "Unknown" to 1,
                        )
                    movieAgg.byVideoCodec shouldContainExactly
                        mapOf(
                            "HEVC" to 2,
                            "H.264" to 2,
                            "AV1" to 1,
                        )
                    movieAgg.byAudioCodec shouldContainExactly
                        mapOf(
                            "EAC3" to 1,
                            "DTS" to 1,
                            "AAC" to 1,
                            "AC3" to 1,
                            "Unknown" to 1,
                        )

                    val musicSnaps = harness.libraryRepository.findSnapshots(harness.serverId, "3").shouldBeRight()
                    val musicAgg = MiruJson.decodeFromString<LibraryAggregates>(musicSnaps.first().aggregatesJson!!)
                    musicAgg.byResolution shouldBe emptyMap()
                    musicAgg.byVideoCodec shouldBe emptyMap()
                    musicAgg.byAudioCodec shouldContainExactly mapOf("FLAC" to 1)

                    // Second deep scan where m-4 and m-5 were removed from Plex
                    val reducedMovies = movieItems.take(3)
                    adapter.itemsHandler = { key, start, size, _ ->
                        when (key) {
                            "1" -> {
                                val slice = reducedMovies.drop(start).take(size)
                                LibraryItemsPage(totalSize = reducedMovies.size, offset = start, items = slice).right()
                            }
                            else -> LibraryItemsPage(totalSize = 0, offset = 0, items = emptyList()).right()
                        }
                    }
                    adapter.countHandler = { key, _ -> if (key == "1") 3.right() else 0.right() }
                    harness.clockMs.addAndGet(60_000L)

                    val secondSummary = collector.syncDeep(sectionKeyFilter = "1").shouldBeRight()
                    secondSummary.prunedItems shouldBe 2
                    val remaining = harness.libraryRepository.findRecentlyAdded(harness.serverId, "1", limit = 10).shouldBeRight()
                    remaining.map { it.ratingKey }.toSet() shouldBe setOf("m-1", "m-2", "m-3")
                }
            }
        }

        test("aborts section cleanly on mid-pagination Left without corrupting prior snapshots or pruning items") {
            createCollectorHarness().use { harness ->
                runTest {
                    val page1 = listOf(movieItem("m-1", sizeBytes = 10_000L), movieItem("m-2", sizeBytes = 20_000L))
                    val page2 = listOf(movieItem("m-3", sizeBytes = 30_000L))
                    var failSecondPage = false

                    val adapter =
                        FakeMediaServerAdapter(
                            sectionsResult = listOf(LibrarySectionInfo("1", "Movies", LibrarySectionType.MOVIE)).right(),
                            countHandler = { _, _ -> 3.right() },
                            itemsHandler = { _, start, _, _ ->
                                when (start) {
                                    0 -> LibraryItemsPage(totalSize = 3, offset = 0, items = page1).right()
                                    2 ->
                                        if (failSecondPage) {
                                            MiruError.UpstreamUnavailable("plex", "Connection reset").left()
                                        } else {
                                            LibraryItemsPage(totalSize = 3, offset = 2, items = page2).right()
                                        }
                                    else -> LibraryItemsPage(totalSize = 3, offset = start, items = emptyList()).right()
                                }
                            },
                        )

                    val collector =
                        LibraryStatsCollector(
                            adapter = adapter,
                            serverIdProvider = { harness.serverId.right() },
                            libraryRepository = harness.libraryRepository,
                            scope = this,
                            pageSize = 2,
                            clock = { harness.clockMs.get() },
                        )

                    collector.syncDeep().shouldBeRight()
                    val initialSnapshots = harness.libraryRepository.findSnapshots(harness.serverId, "1").shouldBeRight()
                    initialSnapshots shouldHaveSize 1
                    val initialCached = harness.libraryRepository.findRecentlyAdded(harness.serverId, "1", limit = 10).shouldBeRight()
                    initialCached shouldHaveSize 3

                    // Now simulate transient Plex failure on page 2
                    failSecondPage = true
                    harness.clockMs.addAndGet(30_000L)

                    val error = collector.syncDeep().shouldBeLeft()
                    error.shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                    // Prior snapshot and all 3 cached items must remain untouched!
                    val snapshotsAfterFailure = harness.libraryRepository.findSnapshots(harness.serverId, "1").shouldBeRight()
                    snapshotsAfterFailure shouldBe initialSnapshots
                    val cachedAfterFailure = harness.libraryRepository.findRecentlyAdded(harness.serverId, "1", limit = 10).shouldBeRight()
                    cachedAfterFailure.map { it.ratingKey }.toSet() shouldBe setOf("m-1", "m-2", "m-3")
                }
            }
        }

        test("start triggers initial fast sync, debounces TimelineEvents with maxWait, runs periodic deep scan, and stops cleanly") {
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val eventStream = MutableSharedFlow<MediaServerEvent>(extraBufferCapacity = 32)
                val repo = InMemoryLibraryRepository()
                val serverId = "srv-1"
                var failSections = true

                val adapter =
                    FakeMediaServerAdapter(
                        eventsFlow = eventStream,
                        countHandler = { _, _ -> 5.right() },
                        itemsHandler = { _, _, _, _ ->
                            LibraryItemsPage(totalSize = 1, offset = 0, items = listOf(movieItem("m-1"))).right()
                        },
                    )
                adapter.sectionsResult = MiruError.UpstreamUnavailable("plex", "unreachable").left()

                val collector =
                    LibraryStatsCollector(
                        adapter = adapter,
                        serverIdProvider = {
                            if (failSections) {
                                MiruError.UpstreamUnavailable("plex", "unreachable").left()
                            } else {
                                serverId.right()
                            }
                        },
                        libraryRepository = repo,
                        scope = scope,
                        deepScanInterval = 6.hours,
                        timelineDebounceWindow = 10.seconds,
                        timelineMaxWait = 30.seconds,
                        clock = { testScheduler.currentTime },
                    )

                // Start does not throw or block when Plex is unreachable
                collector.start()
                runCurrent()
                repo.findAllSections().shouldBeRight().shouldBeEmpty()

                // Now Plex becomes reachable
                failSections = false
                adapter.sectionsResult = listOf(LibrarySectionInfo("1", "Movies", LibrarySectionType.MOVIE)).right()

                // Burst of TimelineEvents inside debounce window collapses into 1 deep sync for section "1"
                eventStream.emit(MediaServerEvent.TimelineEvent(itemId = "10", sectionId = "1"))
                advanceTimeBy(5.seconds)
                runCurrent()
                adapter.itemsCalls.shouldBeEmpty()

                eventStream.emit(MediaServerEvent.TimelineEvent(itemId = "11", sectionId = "1"))
                advanceTimeBy(5.seconds)
                runCurrent()
                // Quiet window hasn't completed since second event was at t=5s
                adapter.itemsCalls.shouldBeEmpty()

                advanceTimeBy(5.seconds)
                runCurrent()
                adapter.itemsCalls shouldHaveSize 1
                repo.findSnapshots(serverId, "1").shouldBeRight() shouldHaveSize 1

                // Continuous burst capped by timelineMaxWait (30s)
                repeat(4) { idx ->
                    eventStream.emit(MediaServerEvent.TimelineEvent(itemId = "b-$idx", sectionId = "1"))
                    advanceTimeBy(8.seconds)
                    runCurrent()
                }
                // At 32s (> 30s maxWait), the debounced sync fired once
                adapter.itemsCalls shouldHaveSize 2

                // Periodic deep scan fires at 6 hours
                advanceTimeBy(6.hours)
                runCurrent()
                adapter.itemsCalls shouldHaveSize 3

                collector.stop()
                scope.isActive shouldBe true
            }
        }
    })
