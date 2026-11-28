package app.hononeko.miru.metrics

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.MediaUserLibraryAccess
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.library.LibraryStatsCollector
import app.hononeko.miru.library.LibrarySyncMode
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.StopReason
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

class MiruMetricsServiceTest :
    FunSpec({
        fun sessionRecord(
            sessionKey: String = "s-1",
            ratingKey: String = "rk-1",
            title: String = "Secret Movie Title",
            mediaType: String = "movie",
            decision: String = "direct_play",
            hwTranscode: Boolean = false,
            location: String = "lan",
            bandwidthKbps: Int? = 8_000,
            ipAddress: String? = "192.168.1.50",
        ): ActiveSessionRecord =
            ActiveSessionRecord(
                serverId = "srv-1",
                sessionKey = sessionKey,
                sessionId = "plex-$sessionKey",
                userId = "user-1",
                media =
                    MediaMetadataRecord(
                        ratingKey = ratingKey,
                        mediaType = mediaType,
                        title = title,
                        durationMs = 7_200_000L,
                    ),
                viewOffsetMs = 120_000L,
                state = "playing",
                streamTech =
                    StreamTechRecord(
                        streamDecision = decision,
                        hwTranscode = hwTranscode,
                        bandwidthKbps = bandwidthKbps,
                    ),
                player =
                    PlayerRecord(
                        playerName = "Apple TV",
                        ipAddress = ipAddress,
                        location = location,
                    ),
                startedAtMs = 1_000L,
                lastSeenAtMs = 2_000L,
            )

        fun historyFrom(session: ActiveSessionRecord): PlaybackHistoryRecord =
            PlaybackHistoryRecord(
                id = "hist-${session.sessionKey}",
                serverId = session.serverId,
                userId = session.userId,
                media = session.media,
                viewOffsetMs = session.viewOffsetMs,
                watchedDurationSec = 300,
                completionPercent = 90.0,
                isWatched = true,
                streamTech = session.streamTech,
                player = session.player,
                startedAtMs = session.startedAtMs,
                stoppedAtMs = 301_000L,
            )

        suspend fun awaitCondition(
            attempts: Int = 50,
            predicate: () -> Boolean,
        ) {
            repeat(attempts) {
                if (predicate()) return
                kotlinx.coroutines.delay(20L)
            }
            predicate() shouldBe true
        }

        test("updates active streams, bandwidth, completed plays, and avoids high-cardinality labels") {
            val sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service =
                MiruMetricsService(
                    sessionEvents = sessionEvents,
                    activeSessionsProvider = { emptyList() },
                    serverNameByIdProvider = { id -> if (id == "srv-1") "Home Plex" else null },
                    activeWebsocketServerLabelProvider = { "Home Plex" },
                    scope = scope,
                )

            try {
                service.start()
                awaitCondition { "miru_websocket_connected" in service.scrape() }

                val movie1 = sessionRecord(sessionKey = "s-1", ratingKey = "rk-1", title = "Dune: Part Two", ipAddress = "10.0.0.99")
                val movie2 = sessionRecord(sessionKey = "s-2", ratingKey = "rk-2", title = "Interstellar")
                val episodeTranscode =
                    sessionRecord(
                        sessionKey = "s-3",
                        ratingKey = "rk-3",
                        title = "Episode Secret",
                        mediaType = "episode",
                        decision = "transcode",
                        hwTranscode = true,
                        location = "wan",
                        bandwidthKbps = 12_500,
                    )

                sessionEvents.emit(SessionEvent.SessionStarted("srv-1", movie1.key, 1_000L, movie1))
                sessionEvents.emit(SessionEvent.SessionStarted("srv-1", movie2.key, 1_000L, movie2))
                sessionEvents.emit(SessionEvent.SessionStarted("srv-1", episodeTranscode.key, 1_000L, episodeTranscode))

                awaitCondition {
                    val out = service.scrape()
                    out.contains("""decision="direct_play"""") && out.contains("""decision="transcode"""")
                }

                val activeOutput = service.scrape()
                activeOutput shouldContain
                    """miru_active_streams{decision="direct_play",hw_transcode="false",location="lan",media_type="movie",server="Home Plex"} 2.0"""
                activeOutput shouldContain
                    """miru_active_streams{decision="transcode",hw_transcode="true",location="wan",media_type="episode",server="Home Plex"} 1.0"""
                activeOutput shouldContain """miru_active_bandwidth_kbps{location="lan",server="Home Plex"} 16000.0"""
                activeOutput shouldContain """miru_active_bandwidth_kbps{location="wan",server="Home Plex"} 12500.0"""
                activeOutput shouldNotContain "Dune: Part Two"
                activeOutput shouldNotContain "10.0.0.99"
                activeOutput shouldNotContain "rk-1"
                activeOutput shouldNotContain "s-1"

                // Update transcode episode to direct_stream without HW transcode; old transcode series must disappear
                val episodeUpdated =
                    episodeTranscode.copy(
                        streamTech = StreamTechRecord(streamDecision = "direct_stream", hwTranscode = false, bandwidthKbps = 4_000),
                    )
                sessionEvents.emit(
                    SessionEvent.SessionUpdated(
                        serverId = "srv-1",
                        key = episodeUpdated.key,
                        timestampMs = 2_000L,
                        session = episodeUpdated,
                        previousState = "playing",
                        previousStreamDecision = "transcode",
                        streamDecisionChanged = true,
                    ),
                )

                awaitCondition {
                    val out = service.scrape()
                    out.contains("""decision="direct_stream"""") && !out.contains("""decision="transcode"""")
                }
                val updatedOutput = service.scrape()
                updatedOutput shouldContain
                    """miru_active_streams{decision="direct_stream",hw_transcode="false",location="wan",media_type="episode",server="Home Plex"} 1.0"""
                updatedOutput shouldContain """miru_active_bandwidth_kbps{location="wan",server="Home Plex"} 4000.0"""

                // Stop movie1 too short (historyRecord == null): should NOT increment plays_completed
                sessionEvents.emit(
                    SessionEvent.SessionStopped(
                        serverId = "srv-1",
                        key = movie1.key,
                        timestampMs = 3_000L,
                        session = movie1,
                        historyRecord = null,
                        mergedIntoHistory = false,
                        reason = StopReason.EXPLICIT_STOP,
                    ),
                )
                // Stop movie2 merged into existing history: should NOT double-count
                sessionEvents.emit(
                    SessionEvent.SessionStopped(
                        serverId = "srv-1",
                        key = movie2.key,
                        timestampMs = 4_000L,
                        session = movie2,
                        historyRecord = historyFrom(movie2),
                        mergedIntoHistory = true,
                        reason = StopReason.EXPLICIT_STOP,
                    ),
                )
                // Stop episodeUpdated with fresh persisted play: SHOULD increment plays_completed by 1
                sessionEvents.emit(
                    SessionEvent.SessionStopped(
                        serverId = "srv-1",
                        key = episodeUpdated.key,
                        timestampMs = 5_000L,
                        session = episodeUpdated,
                        historyRecord = historyFrom(episodeUpdated),
                        mergedIntoHistory = false,
                        reason = StopReason.EXPLICIT_STOP,
                    ),
                )

                awaitCondition {
                    val out = service.scrape()
                    !out.contains("miru_active_streams{") && out.contains("miru_plays_completed_total")
                }

                val stoppedOutput = service.scrape()
                stoppedOutput shouldNotContain "miru_active_streams{"
                stoppedOutput shouldNotContain "miru_active_bandwidth_kbps{"
                stoppedOutput shouldContain
                    """miru_plays_completed_total{decision="direct_stream",media_type="episode",server="Home Plex"} 1.0"""
            } finally {
                service.stop()
                service.close()
                scope.cancel()
            }
        }

        test("populates and refreshes library gauges, websocket state, and reconnect counter") {
            val sections =
                mutableListOf(
                    LibrarySectionRecord(
                        serverId = "srv-1",
                        sectionKey = "1",
                        title = "Movies",
                        sectionType = "movie",
                        itemCount = 420,
                        totalSizeBytes = 2_000_000_000L,
                        updatedAtMs = 1_000L,
                    ),
                    LibrarySectionRecord(
                        serverId = "srv-1",
                        sectionKey = "2",
                        title = "TV Shows",
                        sectionType = "show",
                        itemCount = 65,
                        totalSizeBytes = 5_000_000_000L,
                        updatedAtMs = 1_000L,
                    ),
                )
            val syncEvents = MutableSharedFlow<LibrarySyncSummary>(extraBufferCapacity = 8)
            val wsConnected = MutableStateFlow(false)
            val wsReconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val service =
                MiruMetricsService(
                    librarySectionsProvider = { sections.toList().right() },
                    librarySyncEvents = syncEvents,
                    websocketConnected = wsConnected,
                    websocketReconnected = wsReconnected,
                    serverNameByIdProvider = { "Home Plex" },
                    activeWebsocketServerLabelProvider = { "Home Plex" },
                    scope = scope,
                )

            try {
                service.start()
                awaitCondition {
                    val out = service.scrape()
                    out.contains("""library="Movies"""") && out.contains("""miru_websocket_connected{server="Home Plex"} 0.0""")
                }

                val initial = service.scrape()
                initial shouldContain """miru_library_items{library="Movies",media_type="movie",server="Home Plex"} 420.0"""
                initial shouldContain """miru_library_size_bytes{library="Movies",media_type="movie",server="Home Plex"} 2.0E9"""
                initial shouldContain """miru_library_items{library="TV Shows",media_type="show",server="Home Plex"} 65.0"""

                wsConnected.value = true
                wsReconnected.emit(Unit)
                wsReconnected.emit(Unit)

                sections[0] = sections[0].copy(itemCount = 425, totalSizeBytes = 2_500_000_000L)
                syncEvents.emit(
                    LibrarySyncSummary(
                        mode = LibrarySyncMode.DEEP,
                        syncedSections = 2,
                        totalItemsSynced = 10,
                        snapshotsCreated = 2,
                        prunedItems = 0,
                        syncedAtMs = 2_000L,
                    ),
                )

                awaitCondition {
                    val out = service.scrape()
                    out.contains("425.0") &&
                        out.contains("""miru_websocket_connected{server="Home Plex"} 1.0""") &&
                        out.contains("""miru_websocket_reconnects_total{server="Home Plex"} 2.0""")
                }

                wsConnected.value = false
                awaitCondition {
                    service.scrape().contains("""miru_websocket_connected{server="Home Plex"} 0.0""")
                }
            } finally {
                service.stop()
                service.close()
                scope.cancel()
            }
        }

        test("normalizes unknown labels, deduplicates matching library series, and handles empty or error responses") {
            MiruMetricsService.normalizeDecision("DIRECT_STREAM") shouldBe "direct_stream"
            MiruMetricsService.normalizeDecision("weird_decision") shouldBe "unknown"
            MiruMetricsService.normalizeDecision(null) shouldBe "unknown"

            MiruMetricsService.normalizeLocation("WAN") shouldBe "wan"
            MiruMetricsService.normalizeLocation("satellite") shouldBe "unknown"

            MiruMetricsService.normalizeMediaType("LIVE_TV") shouldBe "live_tv"
            MiruMetricsService.normalizeMediaType("audiobook") shouldBe "unknown"

            MiruMetricsService.normalizeSectionType("ARTIST") shouldBe "artist"
            MiruMetricsService.normalizeSectionType("comics") shouldBe "other"

            MiruMetricsService.normalizeLibraryLabel("  Anime  ", "9") shouldBe "Anime"
            MiruMetricsService.normalizeLibraryLabel("   ", "9") shouldBe "9"
            MiruMetricsService.normalizeLibraryLabel("", "") shouldBe "unknown"

            var shouldFailLibrary = true
            val duplicateSections =
                listOf(
                    LibrarySectionRecord("srv-x", "1", "Movies", "movie", itemCount = 10, totalSizeBytes = 100L, updatedAtMs = 1L),
                    LibrarySectionRecord("srv-x", "2", "Movies", "movie", itemCount = 5, totalSizeBytes = 50L, updatedAtMs = 1L),
                )
            val service =
                MiruMetricsService(
                    activeSessionsProvider = {
                        listOf(
                            ActiveSessionRecord(
                                serverId = "srv-x",
                                sessionKey = "k-1",
                                userId = "u-1",
                                media = MediaMetadataRecord("r-1", "custom_type", "Title", durationMs = 1000L),
                                viewOffsetMs = 0L,
                                state = "playing",
                                streamTech = StreamTechRecord("custom_dec", bandwidthKbps = null),
                                player = PlayerRecord(location = "vpn"),
                                startedAtMs = 1L,
                                lastSeenAtMs = 2L,
                            ),
                        )
                    },
                    librarySectionsProvider = {
                        if (shouldFailLibrary) {
                            MiruError.PersistenceError("read", "db busy").left()
                        } else {
                            duplicateSections.right()
                        }
                    },
                )

            try {
                service.refreshActiveSessions()
                service.refreshLibraryStats() // should not throw on Left
                val out1 = service.scrape()
                out1 shouldContain
                    """miru_active_streams{decision="unknown",hw_transcode="false",location="unknown",media_type="unknown",server="srv-x"} 1.0"""
                out1 shouldContain """miru_active_bandwidth_kbps{location="unknown",server="srv-x"} 0.0"""

                shouldFailLibrary = false
                service.refreshLibraryStats()
                val out2 = service.scrape()
                out2 shouldContain """miru_library_items{library="Movies",media_type="movie",server="srv-x"} 15.0"""
                out2 shouldContain """miru_library_size_bytes{library="Movies",media_type="movie",server="srv-x"} 150.0"""
            } finally {
                service.close()
            }
        }

        test("LibraryStatsCollector sync emits syncEvents that refresh MiruMetricsService from SQLite") {
            val dir = Files.createTempDirectory("miru-metrics-lib-sync-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("lib.db").toString())).shouldBeRight()
            val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val metricsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val serverRepo = ExposedMediaServerRepository(handle)
                serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-db",
                            name = "Plex Box",
                            serverType = "plex",
                            machineId = "m-1",
                            baseUrl = "http://plex.local:32400",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                val repo = ExposedLibraryRepository(handle)
                val stubAdapter =
                    object : MediaServerAdapter {
                        override val serverType: String = "plex"

                        override suspend fun checkHealth(): Either<MiruError, ServerIdentity> =
                            ServerIdentity("m-1", "Plex Box", "1.0").right()

                        override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> =
                            emptyList<ActiveStreamSnapshot>().right()

                        override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> = emptyList<MediaUser>().right()

                        override suspend fun fetchUserLibraryAccess(): Either<MiruError, List<MediaUserLibraryAccess>> =
                            emptyList<MediaUserLibraryAccess>().right()

                        override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> =
                            listOf(LibrarySectionInfo(sectionKey = "1", title = "Films", type = LibrarySectionType.MOVIE)).right()

                        override suspend fun fetchLibraryCount(
                            sectionKey: String,
                            mediaTypeCode: Int?,
                        ): Either<MiruError, Int> = 77.right()

                        override suspend fun fetchLibraryItems(
                            sectionKey: String,
                            start: Int,
                            size: Int,
                            mediaTypeCode: Int?,
                        ): Either<MiruError, LibraryItemsPage> =
                            LibraryItemsPage(totalSize = 0, offset = start, items = emptyList()).right()

                        override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> =
                            MediaItemDetails("1", MediaType.MOVIE, "Film").right()

                        override suspend fun fetchArtwork(
                            path: String,
                            width: Int?,
                            height: Int?,
                        ): Either<MiruError, ArtworkData> = ArtworkData(byteArrayOf(), "image/jpeg").right()

                        override suspend fun terminateSession(
                            sessionId: String,
                            reason: String,
                        ): Either<MiruError, Unit> = Unit.right()
                    }
                val collector =
                    LibraryStatsCollector(
                        adapter = stubAdapter,
                        serverIdProvider = { "srv-db".right() },
                        libraryRepository = repo,
                        scope = collectorScope,
                    )
                val service =
                    MiruMetricsService(
                        librarySectionsProvider = { repo.findAllSections() },
                        librarySyncEvents = collector.syncEvents,
                        serverNameByIdProvider = { "Plex Box" },
                        scope = metricsScope,
                    )
                service.start()
                yield()

                collector.syncFastCounts().shouldBeRight()
                awaitCondition {
                    service.scrape().contains("""miru_library_items{library="Films",media_type="movie",server="Plex Box"} 77.0""")
                }
                service.stop()
                service.close()
            } finally {
                collectorScope.cancel()
                metricsScope.cancel()
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("does not cache transient null server name lookups and later resolves the real server name") {
            var resolvedName: String? = null
            val service =
                MiruMetricsService(
                    serverNameByIdProvider = { id -> if (id == "srv-uuid-1") resolvedName else null },
                )
            try {
                val firstSession = sessionRecord(sessionKey = "s-1", ratingKey = "rk-1").copy(serverId = "srv-uuid-1")
                service.handleSessionEvent(SessionEvent.SessionStarted("srv-uuid-1", firstSession.key, 1_000L, firstSession))
                service.scrape() shouldContain """server="srv-uuid-1""""

                // Now DB/upsert succeeds and returns the friendly name; next event must resolve instead of sticking on UUID
                resolvedName = "Living Room Plex"
                service.handleSessionEvent(
                    SessionEvent.SessionUpdated(
                        serverId = "srv-uuid-1",
                        key = firstSession.key,
                        timestampMs = 2_000L,
                        session = firstSession,
                        previousState = "playing",
                        previousStreamDecision = "direct_play",
                        streamDecisionChanged = false,
                    ),
                )
                val resolvedOut = service.scrape()
                resolvedOut shouldContain """server="Living Room Plex""""
                resolvedOut shouldNotContain """server="srv-uuid-1""""
            } finally {
                service.close()
            }
        }

        test("counts stats cache hits and misses per endpoint") {
            val service = MiruMetricsService()
            try {
                service.recordStatsCacheLookup("overview", hit = false)
                service.recordStatsCacheLookup("overview", hit = true)
                service.recordStatsCacheLookup("overview", hit = true)
                service.recordStatsCacheLookup("heatmap", hit = false)

                val out = service.scrape()
                out shouldContain """miru_stats_cache_hits_total{endpoint="overview"} 2.0"""
                out shouldContain """miru_stats_cache_misses_total{endpoint="overview"} 1.0"""
                out shouldContain """miru_stats_cache_misses_total{endpoint="heatmap"} 1.0"""
                out shouldNotContain """miru_stats_cache_hits_total{endpoint="heatmap"}"""
            } finally {
                service.close()
            }
        }

        test("Grafana dashboard JSON matches Helm chart copy and references only metrics exposed by MiruMetricsService") {
            val dashboardFile = File("docs/grafana/miru-dashboard.json")
            val chartDashboardFile = File("charts/miru/files/miru-dashboard.json")
            val rawDashboard = dashboardFile.readText()
            val dashboardJson = MiruJson.parseToJsonElement(rawDashboard).jsonObject
            val chartDashboardJson = MiruJson.parseToJsonElement(chartDashboardFile.readText()).jsonObject
            chartDashboardJson shouldBe dashboardJson

            val templatingList = dashboardJson.getValue("templating").jsonObject.getValue("list").jsonArray
            val varsByName = templatingList.associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
            varsByName.getValue("server").jsonObject.getValue("allValue").jsonPrimitive.content shouldBe ".*"
            varsByName.getValue("gpu_node").jsonObject.getValue("allValue").jsonPrimitive.content shouldBe ".*"

            val exposedMetricNames =
                setOf(
                    MiruMetricsService.METRIC_ACTIVE_STREAMS,
                    MiruMetricsService.METRIC_ACTIVE_BANDWIDTH_KBPS,
                    MiruMetricsService.METRIC_LIBRARY_ITEMS,
                    MiruMetricsService.METRIC_LIBRARY_SIZE_BYTES,
                    MiruMetricsService.METRIC_PLAYS_COMPLETED_TOTAL,
                    MiruMetricsService.METRIC_WEBSOCKET_CONNECTED,
                    MiruMetricsService.METRIC_WEBSOCKET_RECONNECTS_TOTAL,
                    MiruMetricsService.METRIC_STATS_CACHE_HITS_TOTAL,
                    MiruMetricsService.METRIC_STATS_CACHE_MISSES_TOTAL,
                )
            val referencedMiruMetrics =
                Regex("""\bmiru_[a-z0-9_]+\b""")
                    .findAll(rawDashboard)
                    .map { it.value }
                    .toSet()
            referencedMiruMetrics.isNotEmpty() shouldBe true
            referencedMiruMetrics.forEach { metric ->
                metric shouldBeIn exposedMetricNames
            }
        }
    })
