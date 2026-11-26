package app.hononeko.miru.metrics

import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.plex.RealtimeLifecycle
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.session.SessionEvent
import arrow.core.Either
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Exposes bounded-cardinality Prometheus telemetry for Miru sessions, libraries, playback completions,
 * and Plex notification WebSocket health using Micrometer's [PrometheusMeterRegistry].
 */
@Suppress("LongParameterList")
class MiruMetricsService(
    private val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
    private val sessionEvents: Flow<SessionEvent> = emptyFlow(),
    private val activeSessionsProvider: suspend () -> List<ActiveSessionRecord> = { emptyList() },
    private val librarySectionsProvider: suspend () -> Either<MiruError, List<LibrarySectionRecord>> = { Either.Right(emptyList()) },
    private val librarySyncEvents: Flow<LibrarySyncSummary> = emptyFlow(),
    private val websocketConnected: StateFlow<Boolean> = MutableStateFlow(false),
    private val websocketReconnected: Flow<Unit> = emptyFlow(),
    private val serverNameByIdProvider: suspend (String) -> String? = { null },
    private val activeWebsocketServerLabelProvider: suspend () -> String? = { null },
    private val scope: CoroutineScope? = null,
) : RealtimeLifecycle,
    AutoCloseable {
    private data class ActiveStreamSeriesKey(
        val server: String,
        val decision: String,
        val location: String,
        val mediaType: String,
        val hwTranscode: String,
    )

    private data class BandwidthSeriesKey(
        val server: String,
        val location: String,
    )

    private data class LibrarySeriesKey(
        val server: String,
        val library: String,
        val mediaType: String,
    )

    private data class LibraryTotals(
        val items: Long,
        val sizeBytes: Long,
    )

    private val logger = LoggerFactory.getLogger(MiruMetricsService::class.java)
    private val lifecycleLock = Any()
    private val stateMutex = Mutex()
    private val trackedSessions = linkedMapOf<ActiveSessionKey, ActiveSessionRecord>()
    private val serverNameCache = linkedMapOf<String, String>()
    private var collectorJob: Job? = null
    private var lastWebsocketConnected: Boolean = websocketConnected.value

    private val activeStreamsGauge: MultiGauge =
        MultiGauge
            .builder(METRIC_ACTIVE_STREAMS)
            .description("Current number of active media playback streams")
            .register(registry)

    private val activeBandwidthGauge: MultiGauge =
        MultiGauge
            .builder(METRIC_ACTIVE_BANDWIDTH_KBPS)
            .description("Current aggregate bandwidth of active streams in kbps")
            .register(registry)

    private val libraryItemsGauge: MultiGauge =
        MultiGauge
            .builder(METRIC_LIBRARY_ITEMS)
            .description("Total items tracked in each media library section")
            .register(registry)

    private val librarySizeBytesGauge: MultiGauge =
        MultiGauge
            .builder(METRIC_LIBRARY_SIZE_BYTES)
            .description("Total storage size in bytes for each media library section")
            .register(registry)

    private val websocketConnectedGauge: MultiGauge =
        MultiGauge
            .builder(METRIC_WEBSOCKET_CONNECTED)
            .description("Plex notification WebSocket connection status (1=connected, 0=disconnected)")
            .register(registry)

    fun scrape(): String = registry.scrape()

    override fun start() {
        val targetScope = scope ?: return
        synchronized(lifecycleLock) {
            if (collectorJob?.isActive == true) return
            collectorJob =
                targetScope.launch(CoroutineName("miru-metrics-collectors")) {
                    runCollectors()
                }
        }
    }

    override suspend fun stop() {
        val current = synchronized(lifecycleLock) { collectorJob.also { collectorJob = null } }
        current?.cancelAndJoin()
    }

    override fun close() {
        registry.close()
    }

    private suspend fun runCollectors() {
        coroutineScope {
            launch(CoroutineName("miru-metrics-sessions")) {
                val stream =
                    if (sessionEvents is SharedFlow<SessionEvent>) {
                        sessionEvents.onSubscription { safeRun("refreshActiveSessions") { refreshActiveSessions() } }
                    } else {
                        safeRun("refreshActiveSessions") { refreshActiveSessions() }
                        sessionEvents
                    }
                stream
                    .catch { e -> logger.warn("Metrics session event stream failed: {}", e.message) }
                    .collect { event ->
                        safeRun("handleSessionEvent") { handleSessionEvent(event) }
                    }
            }

            launch(CoroutineName("miru-metrics-libraries")) {
                val stream =
                    if (librarySyncEvents is SharedFlow<LibrarySyncSummary>) {
                        librarySyncEvents.onSubscription { safeRun("refreshLibraryStats") { refreshLibraryStats() } }
                    } else {
                        safeRun("refreshLibraryStats") { refreshLibraryStats() }
                        librarySyncEvents
                    }
                stream
                    .catch { e -> logger.warn("Metrics library sync stream failed: {}", e.message) }
                    .collect {
                        safeRun("refreshLibraryStats") { refreshLibraryStats() }
                    }
            }

            launch(CoroutineName("miru-metrics-ws-state")) {
                websocketConnected.collect { connected ->
                    safeRun("refreshWebsocketState") { refreshWebsocketState(connected) }
                }
            }

            launch(CoroutineName("miru-metrics-ws-reconnects")) {
                websocketReconnected
                    .catch { e -> logger.warn("Metrics websocket reconnect stream failed: {}", e.message) }
                    .collect {
                        safeRun("recordWebsocketReconnect") { recordWebsocketReconnect() }
                    }
            }
        }
    }

    private suspend fun safeRun(
        action: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Metrics action {} failed: {}", action, e.message)
        }
    }

    suspend fun refreshActiveSessions() {
        val sessions = activeSessionsProvider()
        stateMutex.withLock {
            trackedSessions.clear()
            for (session in sessions) {
                trackedSessions[session.key] = session
            }
            updateActiveSessionGaugesLocked()
            updateWebsocketGaugeLocked(lastWebsocketConnected)
        }
    }

    suspend fun handleSessionEvent(event: SessionEvent) {
        stateMutex.withLock {
            when (event) {
                is SessionEvent.SessionStarted -> {
                    trackedSessions[event.key] = event.session
                    updateActiveSessionGaugesLocked()
                    updateWebsocketGaugeLocked(lastWebsocketConnected)
                }

                is SessionEvent.SessionUpdated -> {
                    trackedSessions[event.key] = event.session
                    updateActiveSessionGaugesLocked()
                }

                is SessionEvent.SessionStopped -> {
                    trackedSessions.remove(event.key)
                    updateActiveSessionGaugesLocked()
                    val history = event.historyRecord
                    if (history != null && !event.mergedIntoHistory) {
                        val server = resolveServerLabelLocked(history.serverId)
                        val decision = normalizeDecision(history.streamTech.streamDecision)
                        val mediaType = normalizeMediaType(history.media.mediaType)
                        Counter
                            .builder(METRIC_PLAYS_COMPLETED_TOTAL)
                            .description("Total completed playback sessions persisted to history")
                            .tags(
                                TAG_SERVER,
                                server,
                                TAG_DECISION,
                                decision,
                                TAG_MEDIA_TYPE,
                                mediaType,
                            ).register(registry)
                            .increment()
                    }
                }
            }
        }
    }

    suspend fun refreshLibraryStats() {
        val sections =
            librarySectionsProvider().fold(
                ifLeft = { error ->
                    logger.warn("Failed refreshing library metrics: {}", error.message)
                    return
                },
                ifRight = { it },
            )
        stateMutex.withLock {
            serverNameCache.clear()
            updateLibraryGaugesLocked(sections)
            updateWebsocketGaugeLocked(lastWebsocketConnected)
        }
    }

    suspend fun refreshWebsocketState(connected: Boolean) {
        stateMutex.withLock {
            lastWebsocketConnected = connected
            updateWebsocketGaugeLocked(connected)
        }
    }

    suspend fun recordWebsocketReconnect() {
        stateMutex.withLock {
            val server = resolveActiveWebsocketServerLabelLocked()
            Counter
                .builder(METRIC_WEBSOCKET_RECONNECTS_TOTAL)
                .description("Total Plex notification WebSocket reconnections")
                .tag(TAG_SERVER, server)
                .register(registry)
                .increment()
        }
    }

    private suspend fun updateActiveSessionGaugesLocked() {
        if (trackedSessions.isEmpty()) {
            activeStreamsGauge.register(emptyList(), true)
            activeBandwidthGauge.register(emptyList(), true)
            return
        }

        val streamCounts = linkedMapOf<ActiveStreamSeriesKey, Int>()
        val bandwidthSums = linkedMapOf<BandwidthSeriesKey, Long>()

        for (session in trackedSessions.values) {
            val server = resolveServerLabelLocked(session.serverId)
            val decision = normalizeDecision(session.streamTech.streamDecision)
            val location = normalizeLocation(session.player.location)
            val mediaType = normalizeMediaType(session.media.mediaType)
            val hwTranscode = session.streamTech.hwTranscode.toString()

            val streamKey =
                ActiveStreamSeriesKey(
                    server = server,
                    decision = decision,
                    location = location,
                    mediaType = mediaType,
                    hwTranscode = hwTranscode,
                )
            streamCounts[streamKey] = (streamCounts[streamKey] ?: 0) + 1

            val bwKey = BandwidthSeriesKey(server = server, location = location)
            val kbps = (session.streamTech.bandwidthKbps ?: 0).coerceAtLeast(0).toLong()
            bandwidthSums[bwKey] = (bandwidthSums[bwKey] ?: 0L) + kbps
        }

        val streamRows =
            streamCounts.map { (key, count) ->
                MultiGauge.Row.of(
                    Tags.of(
                        TAG_SERVER,
                        key.server,
                        TAG_DECISION,
                        key.decision,
                        TAG_LOCATION,
                        key.location,
                        TAG_MEDIA_TYPE,
                        key.mediaType,
                        TAG_HW_TRANSCODE,
                        key.hwTranscode,
                    ),
                    count,
                )
            }
        val bandwidthRows =
            bandwidthSums.map { (key, totalKbps) ->
                MultiGauge.Row.of(
                    Tags.of(
                        TAG_SERVER,
                        key.server,
                        TAG_LOCATION,
                        key.location,
                    ),
                    totalKbps,
                )
            }

        activeStreamsGauge.register(streamRows, true)
        activeBandwidthGauge.register(bandwidthRows, true)
    }

    private suspend fun updateLibraryGaugesLocked(sections: List<LibrarySectionRecord>) {
        if (sections.isEmpty()) {
            libraryItemsGauge.register(emptyList(), true)
            librarySizeBytesGauge.register(emptyList(), true)
            return
        }

        val aggregated = linkedMapOf<LibrarySeriesKey, LibraryTotals>()
        for (section in sections) {
            val server = resolveServerLabelLocked(section.serverId)
            val library = normalizeLibraryLabel(section.title, section.sectionKey)
            val mediaType = normalizeSectionType(section.sectionType)
            val key = LibrarySeriesKey(server = server, library = library, mediaType = mediaType)
            val previous = aggregated[key]
            val items = section.itemCount.coerceAtLeast(0).toLong()
            val sizeBytes = section.totalSizeBytes.coerceAtLeast(0L)
            aggregated[key] =
                LibraryTotals(
                    items = (previous?.items ?: 0L) + items,
                    sizeBytes = (previous?.sizeBytes ?: 0L) + sizeBytes,
                )
        }

        val itemRows =
            aggregated.map { (key, totals) ->
                MultiGauge.Row.of(
                    Tags.of(
                        TAG_SERVER,
                        key.server,
                        TAG_LIBRARY,
                        key.library,
                        TAG_MEDIA_TYPE,
                        key.mediaType,
                    ),
                    totals.items,
                )
            }
        val sizeRows =
            aggregated.map { (key, totals) ->
                MultiGauge.Row.of(
                    Tags.of(
                        TAG_SERVER,
                        key.server,
                        TAG_LIBRARY,
                        key.library,
                        TAG_MEDIA_TYPE,
                        key.mediaType,
                    ),
                    totals.sizeBytes,
                )
            }

        libraryItemsGauge.register(itemRows, true)
        librarySizeBytesGauge.register(sizeRows, true)
    }

    private suspend fun updateWebsocketGaugeLocked(connected: Boolean) {
        val server = resolveActiveWebsocketServerLabelLocked()
        val value = if (connected) 1.0 else 0.0
        websocketConnectedGauge.register(
            listOf(MultiGauge.Row.of(Tags.of(TAG_SERVER, server), value)),
            true,
        )
    }

    private suspend fun resolveServerLabelLocked(serverId: String): String {
        val normalizedId = serverId.trim().ifEmpty { DEFAULT_SERVER_LABEL }
        serverNameCache[normalizedId]?.let { return it }
        val resolved =
            serverNameByIdProvider(normalizedId)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: normalizedId
        serverNameCache[normalizedId] = resolved
        return resolved
    }

    private suspend fun resolveActiveWebsocketServerLabelLocked(): String =
        activeWebsocketServerLabelProvider()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: serverNameCache.values.firstOrNull()
            ?: DEFAULT_SERVER_LABEL

    companion object {
        const val METRIC_ACTIVE_STREAMS = "miru_active_streams"
        const val METRIC_ACTIVE_BANDWIDTH_KBPS = "miru_active_bandwidth_kbps"
        const val METRIC_LIBRARY_ITEMS = "miru_library_items"
        const val METRIC_LIBRARY_SIZE_BYTES = "miru_library_size_bytes"
        const val METRIC_PLAYS_COMPLETED_TOTAL = "miru_plays_completed_total"
        const val METRIC_WEBSOCKET_CONNECTED = "miru_websocket_connected"
        const val METRIC_WEBSOCKET_RECONNECTS_TOTAL = "miru_websocket_reconnects_total"

        const val TAG_SERVER = "server"
        const val TAG_DECISION = "decision"
        const val TAG_LOCATION = "location"
        const val TAG_MEDIA_TYPE = "media_type"
        const val TAG_HW_TRANSCODE = "hw_transcode"
        const val TAG_LIBRARY = "library"

        private const val DEFAULT_SERVER_LABEL = "plex"
        private const val UNKNOWN_VALUE = "unknown"
        private const val OTHER_VALUE = "other"

        private val VALID_DECISIONS = StreamDecision.entries.map { it.name.lowercase() }.toSet()
        private val VALID_LOCATIONS = StreamLocation.entries.map { it.name.lowercase() }.toSet()
        private val VALID_MEDIA_TYPES = MediaType.entries.map { it.name.lowercase() }.toSet()
        private val VALID_SECTION_TYPES = LibrarySectionType.entries.map { it.name.lowercase() }.toSet()

        internal fun normalizeDecision(raw: String?): String {
            val candidate = raw?.trim()?.lowercase().orEmpty()
            return if (candidate in VALID_DECISIONS) candidate else UNKNOWN_VALUE
        }

        internal fun normalizeLocation(raw: String?): String {
            val candidate = raw?.trim()?.lowercase().orEmpty()
            return if (candidate in VALID_LOCATIONS) candidate else UNKNOWN_VALUE
        }

        internal fun normalizeMediaType(raw: String?): String {
            val candidate = raw?.trim()?.lowercase().orEmpty()
            return if (candidate in VALID_MEDIA_TYPES) candidate else UNKNOWN_VALUE
        }

        internal fun normalizeSectionType(raw: String?): String {
            val candidate = raw?.trim()?.lowercase().orEmpty()
            return if (candidate in VALID_SECTION_TYPES) candidate else OTHER_VALUE
        }

        internal fun normalizeLibraryLabel(
            title: String,
            sectionKey: String,
        ): String =
            title.trim().takeIf { it.isNotEmpty() }
                ?: sectionKey.trim().takeIf { it.isNotEmpty() }
                ?: UNKNOWN_VALUE
    }
}
