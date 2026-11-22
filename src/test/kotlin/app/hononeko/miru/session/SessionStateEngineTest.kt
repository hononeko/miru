@file:OptIn(ExperimentalCoroutinesApi::class)

package app.hononeko.miru.session

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.adapter.StreamMedia
import app.hononeko.miru.adapter.StreamPlayer
import app.hononeko.miru.adapter.StreamTechnicalInfo
import app.hononeko.miru.adapter.StreamUser
import app.hononeko.miru.adapter.plex.PlexFixtures
import app.hononeko.miru.adapter.plex.buildPlexRuntime
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedActiveSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_SERVER_ID = "srv-1"
private const val DEFAULT_DURATION_MS = 1_000_000L

private class EngineTestEnv(
    val handle: DatabaseHandle,
    var nowMs: Long = 10_000L,
    private val seq: AtomicInteger = AtomicInteger(0),
) : AutoCloseable {
    val servers = ExposedMediaServerRepository(handle)
    val users = ExposedUserRepository(handle)
    val sessions = ExposedActiveSessionRepository(handle)
    val history = ExposedPlaybackHistoryRepository(handle)

    suspend fun seedServer(
        id: String = DEFAULT_SERVER_ID,
        machineId: String = "mach-$id",
        baseUrl: String = "http://plex.local:32400",
    ): MediaServerRecord {
        val record =
            MediaServerRecord(
                id = id,
                name = "Test Plex",
                serverType = "plex",
                machineId = machineId,
                baseUrl = baseUrl,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        return servers.upsert(record).shouldBeRight()
    }

    fun createEngine(
        serverId: String = DEFAULT_SERVER_ID,
        config: SessionConfig = SessionConfig(),
        missedPollsThreshold: Int = SessionStateEngine.DEFAULT_MISSED_POLLS_THRESHOLD,
    ): SessionStateEngine =
        SessionStateEngine(
            serverIdProvider = { serverId.right() },
            userRepository = users,
            activeSessionRepository = sessions,
            playbackHistoryRepository = history,
            config = config,
            missedPollsThreshold = missedPollsThreshold,
            clock = { nowMs },
            idGenerator = { "id-${seq.incrementAndGet()}" },
        )

    override fun close() {
        handle.close()
    }
}

private fun newEnv(): EngineTestEnv {
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(":memory:")).shouldBeRight()
    return EngineTestEnv(handle)
}

@Suppress("LongParameterList")
private fun sampleSnapshot(
    sessionId: String = "plex-sess-1",
    sessionKey: String? = "10",
    ratingKey: String = "movie-1",
    mediaType: MediaType = MediaType.MOVIE,
    title: String = "Spirited Away",
    durationMs: Long? = DEFAULT_DURATION_MS,
    state: PlaybackState = PlaybackState.PLAYING,
    viewOffsetMs: Long = 0L,
    userExternalId: String = "ext-user-1",
    username: String = "alice",
    playerMachineId: String = "player-1",
    decision: StreamDecision = StreamDecision.DIRECT_PLAY,
    isHwTranscode: Boolean = false,
): ActiveStreamSnapshot =
    ActiveStreamSnapshot(
        sessionId = sessionId,
        sessionKey = sessionKey,
        state = state,
        viewOffsetMs = viewOffsetMs,
        media =
            StreamMedia(
                ratingKey = ratingKey,
                mediaType = mediaType,
                title = title,
                durationMs = durationMs,
                librarySectionId = "sec-1",
            ),
        user =
            StreamUser(
                externalId = userExternalId,
                username = username,
                thumbUrl = "https://plex.tv/u/1",
            ),
        player =
            StreamPlayer(
                machineIdentifier = playerMachineId,
                product = "Plex Web",
                platform = "Chrome",
                device = "Linux",
                title = "Chrome Web",
                address = "10.0.0.5",
                location = StreamLocation.LAN,
                isLocal = true,
            ),
        technical =
            StreamTechnicalInfo(
                decision = decision,
                videoDecision = decision,
                audioDecision = StreamDecision.DIRECT_PLAY,
                isHwTranscode = isHwTranscode,
                videoCodec = "h264",
                audioCodec = "aac",
                videoResolution = "1080p",
                bitrateKbps = 12_000,
            ),
    )

class SessionStateEngineTest :
    FunSpec({
        test("rejects invalid missedPollsThreshold") {
            newEnv().use { env ->
                shouldThrow<IllegalArgumentException> {
                    env.createEngine(missedPollsThreshold = 0)
                }
            }
        }

        test("normal play, update, and explicit stop creates history and emits SessionStarted/Updated/Stopped") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 60, watchedThresholdPercent = 85))
                    val collected = mutableListOf<SessionEvent>()
                    val collector =
                        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                            engine.sessionEvents.collect { collected += it }
                        }

                    // Unknown STOPPED snapshot is ignored
                    engine
                        .reconcile(listOf(sampleSnapshot(state = PlaybackState.STOPPED)), ReconcileTrigger.NOTIFICATION)
                        .shouldBeRight()
                        .shouldBeEmpty()

                    // Start at t=10s
                    env.nowMs = 10_000L
                    val startedEvents =
                        engine
                            .reconcile(listOf(sampleSnapshot(viewOffsetMs = 10_000L)), ReconcileTrigger.NOTIFICATION)
                            .shouldBeRight()
                    startedEvents shouldHaveSize 1
                    startedEvents.first().shouldBeInstanceOf<SessionEvent.SessionStarted>()
                    engine.currentSessions() shouldHaveSize 1

                    // Update at t=110s with null durationMs -> preserves original durationMs
                    env.nowMs = 110_000L
                    val updatedEvents =
                        engine
                            .reconcile(
                                listOf(sampleSnapshot(viewOffsetMs = 500_000L, durationMs = null)),
                                ReconcileTrigger.PERIODIC,
                            ).shouldBeRight()
                    val updated = updatedEvents.single().shouldBeInstanceOf<SessionEvent.SessionUpdated>()
                    updated.streamDecisionChanged shouldBe false
                    updated.session.media.durationMs shouldBe DEFAULT_DURATION_MS

                    // Stop at t=310s with viewOffsetMs = 0 -> preserves previous viewOffsetMs (500_000 -> 50%, not watched)
                    env.nowMs = 310_000L
                    val stoppedUnwatched =
                        engine
                            .reconcile(
                                listOf(sampleSnapshot(state = PlaybackState.STOPPED, viewOffsetMs = 0L)),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                    val stoppedEvent1 = stoppedUnwatched.single().shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    stoppedEvent1.reason shouldBe StopReason.EXPLICIT_STOP
                    stoppedEvent1.mergedIntoHistory shouldBe false
                    val hist1 = stoppedEvent1.historyRecord.shouldNotBeNull()
                    hist1.watchedDurationSec shouldBe 300
                    hist1.viewOffsetMs shouldBe 500_000L
                    hist1.completionPercent.shouldBeWithinPercentageOf(50.0, 0.01)
                    hist1.isWatched shouldBe false
                    engine.currentSessions().shouldBeEmpty()
                    env.sessions.findByServer(DEFAULT_SERVER_ID).shouldBeRight().shouldBeEmpty()

                    // Second session reaching >= 85% marks isWatched = true
                    env.nowMs = 1_000_000L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(sessionKey = "11", ratingKey = "movie-2", viewOffsetMs = 100_000L)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    env.nowMs = 1_200_000L
                    val stoppedWatched =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "11",
                                        ratingKey = "movie-2",
                                        state = PlaybackState.STOPPED,
                                        viewOffsetMs = 850_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                    val hist2 = stoppedWatched.single().shouldBeInstanceOf<SessionEvent.SessionStopped>().historyRecord.shouldNotBeNull()
                    hist2.completionPercent.shouldBeWithinPercentageOf(85.0, 0.01)
                    hist2.isWatched shouldBe true

                    collected shouldHaveSize 5
                    collector.cancel()
                }
            }
        }

        test("accumulates pause duration across sub-second polls, pause->resume->stop, and pause->stop") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 1, resumeMergeWindowSeconds = 0))

                    // Pause -> Resume -> Stop
                    env.nowMs = 0L
                    engine.reconcile(listOf(sampleSnapshot(state = PlaybackState.PLAYING)), ReconcileTrigger.PERIODIC).shouldBeRight()
                    env.nowMs = 2_000L
                    engine.reconcile(listOf(sampleSnapshot(state = PlaybackState.PAUSED)), ReconcileTrigger.NOTIFICATION).shouldBeRight()
                    // Sub-second pause intervals: 600ms + 700ms + 1_200ms = 2_500ms paused
                    env.nowMs = 2_600L
                    engine.reconcile(listOf(sampleSnapshot(state = PlaybackState.PAUSED)), ReconcileTrigger.PERIODIC).shouldBeRight()
                    env.nowMs = 3_300L
                    engine.reconcile(listOf(sampleSnapshot(state = PlaybackState.PAUSED)), ReconcileTrigger.PERIODIC).shouldBeRight()
                    env.nowMs = 4_500L
                    val resumed =
                        engine
                            .reconcile(listOf(sampleSnapshot(state = PlaybackState.PLAYING)), ReconcileTrigger.NOTIFICATION)
                            .shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionUpdated>()
                    resumed.session.pausedDurationSec shouldBe 2

                    env.nowMs = 10_000L
                    val stopped1 =
                        engine
                            .reconcile(
                                listOf(sampleSnapshot(state = PlaybackState.STOPPED, viewOffsetMs = 75_000L)),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    val hist1 = stopped1.historyRecord.shouldNotBeNull()
                    hist1.pausedDurationSec shouldBe 2
                    hist1.watchedDurationSec shouldBe 8

                    // Pause -> Stop directly while still paused
                    env.nowMs = 20_000L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(sessionKey = "20", ratingKey = "movie-2", state = PlaybackState.PLAYING)),
                            ReconcileTrigger.PERIODIC,
                        ).shouldBeRight()
                    env.nowMs = 25_000L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(sessionKey = "20", ratingKey = "movie-2", state = PlaybackState.PAUSED)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    env.nowMs = 29_000L
                    val stopped2 =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "20",
                                        ratingKey = "movie-2",
                                        state = PlaybackState.STOPPED,
                                        viewOffsetMs = 50_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    val hist2 = stopped2.historyRecord.shouldNotBeNull()
                    hist2.pausedDurationSec shouldBe 4
                    hist2.watchedDurationSec shouldBe 5
                }
            }
        }

        test("detects stream decision transition from DIRECT_PLAY to TRANSCODE") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine()

                    env.nowMs = 1_000L
                    engine
                        .reconcile(listOf(sampleSnapshot(decision = StreamDecision.DIRECT_PLAY)), ReconcileTrigger.NOTIFICATION)
                        .shouldBeRight()

                    env.nowMs = 5_000L
                    val updated =
                        engine
                            .reconcile(
                                listOf(sampleSnapshot(decision = StreamDecision.TRANSCODE, isHwTranscode = true)),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionUpdated>()

                    updated.previousStreamDecision shouldBe "direct_play"
                    updated.session.streamTech.streamDecision shouldBe "transcode"
                    updated.session.streamTech.hwTranscode shouldBe true
                    updated.streamDecisionChanged shouldBe true
                }
            }
        }

        test("auto-play next episode reusing sessionKey finalizes previous episode immediately without losing new episode") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 10))

                    env.nowMs = 0L
                    engine
                        .reconcile(
                            listOf(
                                sampleSnapshot(
                                    sessionKey = "sess-tv",
                                    ratingKey = "ep-1",
                                    mediaType = MediaType.EPISODE,
                                    title = "Ep 1",
                                ),
                            ),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()

                    // Ep 1 plays for 200s, then auto-plays Ep 2 on same sessionKey
                    env.nowMs = 200_000L
                    val events =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "sess-tv",
                                        ratingKey = "ep-2",
                                        mediaType = MediaType.EPISODE,
                                        title = "Ep 2",
                                        viewOffsetMs = 1_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()

                    events shouldHaveSize 2
                    val ep2Started = events.filterIsInstance<SessionEvent.SessionStarted>().single()
                    val ep1Stopped = events.filterIsInstance<SessionEvent.SessionStopped>().single()
                    ep2Started.key.ratingKey shouldBe "ep-2"
                    ep1Stopped.key.ratingKey shouldBe "ep-1"
                    ep1Stopped.reason shouldBe StopReason.VANISHED
                    ep1Stopped.historyRecord.shouldNotBeNull().watchedDurationSec shouldBe 200

                    // Now stop Ep 2
                    env.nowMs = 350_000L
                    engine
                        .reconcile(
                            listOf(
                                sampleSnapshot(
                                    sessionKey = "sess-tv",
                                    ratingKey = "ep-2",
                                    mediaType = MediaType.EPISODE,
                                    title = "Ep 2",
                                    state = PlaybackState.STOPPED,
                                    viewOffsetMs = 900_000L,
                                ),
                            ),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()

                    val page = env.history.query(HistoryFilter(serverId = DEFAULT_SERVER_ID)).shouldBeRight()
                    page.totalCount shouldBe 2L
                    page.items.map { it.media.ratingKey } shouldBe listOf("ep-2", "ep-1")
                }
            }
        }

        test("vanished session tolerates one missed poll, resets on reappearance, and finalizes after 2 consecutive missed polls") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 10), missedPollsThreshold = 2)

                    env.nowMs = 0L
                    engine.reconcile(listOf(sampleSnapshot(viewOffsetMs = 50_000L)), ReconcileTrigger.PERIODIC).shouldBeRight()
                    env.nowMs = 60_000L
                    engine.reconcile(listOf(sampleSnapshot(viewOffsetMs = 110_000L)), ReconcileTrigger.PERIODIC).shouldBeRight()

                    // 1st missed poll -> still active with missedPolls = 1
                    env.nowMs = 80_000L
                    engine.reconcile(emptyList(), ReconcileTrigger.PERIODIC).shouldBeRight().shouldBeEmpty()
                    engine.currentSessions().single().missedPolls shouldBe 1
                    env.sessions
                        .findByKey(ActiveSessionKey(DEFAULT_SERVER_ID, "10", "movie-1"))
                        .shouldBeRight()
                        .shouldNotBeNull()
                        .missedPolls shouldBe 1

                    // Reappears -> missedPolls resets to 0
                    env.nowMs = 90_000L
                    engine.reconcile(listOf(sampleSnapshot(viewOffsetMs = 140_000L)), ReconcileTrigger.PERIODIC).shouldBeRight()
                    engine.currentSessions().single().missedPolls shouldBe 0

                    // 1st missed poll again
                    env.nowMs = 110_000L
                    engine.reconcile(emptyList(), ReconcileTrigger.PERIODIC).shouldBeRight().shouldBeEmpty()
                    engine.currentSessions().single().missedPolls shouldBe 1

                    // 2nd consecutive missed poll -> finalized using lastSeenAtMs (90_000L)
                    env.nowMs = 130_000L
                    val events = engine.reconcile(emptyList(), ReconcileTrigger.PERIODIC).shouldBeRight()
                    val stopped = events.single().shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    stopped.reason shouldBe StopReason.VANISHED
                    val hist = stopped.historyRecord.shouldNotBeNull()
                    hist.stoppedAtMs shouldBe 90_000L
                    hist.watchedDurationSec shouldBe 90
                    engine.currentSessions().shouldBeEmpty()
                }
            }
        }

        test("short-play filter removes active session, writes no history row, and emits SessionStopped(historyRecord=null)") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 120))

                    env.nowMs = 0L
                    engine.reconcile(listOf(sampleSnapshot()), ReconcileTrigger.NOTIFICATION).shouldBeRight()
                    env.nowMs = 30_000L
                    val events =
                        engine
                            .reconcile(
                                listOf(sampleSnapshot(state = PlaybackState.STOPPED, viewOffsetMs = 30_000L)),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()

                    val stopped = events.single().shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    stopped.historyRecord.shouldBeNull()
                    stopped.mergedIntoHistory shouldBe false
                    stopped.reason shouldBe StopReason.EXPLICIT_STOP
                    env.sessions.findByServer(DEFAULT_SERVER_ID).shouldBeRight().shouldBeEmpty()
                    env.history.query(HistoryFilter(serverId = DEFAULT_SERVER_ID)).shouldBeRight().totalCount shouldBe 0L
                }
            }
        }

        test("resume-window merging merges short trailing resume on same player, but skips outside window or on different player") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine =
                        env.createEngine(
                            config =
                                SessionConfig(
                                    minPlayDurationSeconds = 120,
                                    resumeMergeWindowSeconds = 120,
                                    watchedThresholdPercent = 85,
                                ),
                        )

                    // Initial play: 0 -> 200s (offset 500_000)
                    env.nowMs = 0L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(playerMachineId = "appletv-1", viewOffsetMs = 10_000L)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    env.nowMs = 200_000L
                    val stop1 =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        playerMachineId = "appletv-1",
                                        state = PlaybackState.STOPPED,
                                        viewOffsetMs = 500_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    val firstId = stop1.historyRecord.shouldNotBeNull().id

                    // Resume on SAME player 60s later (within 120s window) and play for only 30s (short trailing resume)
                    env.nowMs = 260_000L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(sessionKey = "11", playerMachineId = "appletv-1", viewOffsetMs = 500_000L)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    env.nowMs = 290_000L
                    val stop2 =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "11",
                                        playerMachineId = "appletv-1",
                                        state = PlaybackState.STOPPED,
                                        viewOffsetMs = 900_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    stop2.mergedIntoHistory shouldBe true
                    val merged = stop2.historyRecord.shouldNotBeNull()
                    merged.id shouldBe firstId
                    merged.startedAtMs shouldBe 0L
                    merged.stoppedAtMs shouldBe 290_000L
                    merged.watchedDurationSec shouldBe 230
                    merged.viewOffsetMs shouldBe 900_000L
                    merged.isWatched shouldBe true

                    // Resume on DIFFERENT player within 120s window -> does NOT merge
                    env.nowMs = 310_000L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(sessionKey = "12", playerMachineId = "iphone-2", viewOffsetMs = 100_000L)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    env.nowMs = 450_000L
                    val stopDifferentPlayer =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "12",
                                        playerMachineId = "iphone-2",
                                        state = PlaybackState.STOPPED,
                                        viewOffsetMs = 250_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    stopDifferentPlayer.mergedIntoHistory shouldBe false
                    stopDifferentPlayer.historyRecord.shouldNotBeNull().id shouldBe "id-3"

                    // Resume on SAME player ("appletv-1") OUTSIDE 120s window (stopped at 290s, resumed at 500s) -> does NOT merge
                    env.nowMs = 500_000L
                    engine
                        .reconcile(
                            listOf(sampleSnapshot(sessionKey = "13", playerMachineId = "appletv-1", viewOffsetMs = 10_000L)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    env.nowMs = 650_000L
                    val stopOutsideWindow =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "13",
                                        playerMachineId = "appletv-1",
                                        state = PlaybackState.STOPPED,
                                        viewOffsetMs = 200_000L,
                                    ),
                                ),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStopped>()
                    stopOutsideWindow.mergedIntoHistory shouldBe false

                    env.history.query(HistoryFilter(serverId = DEFAULT_SERVER_ID)).shouldBeRight().totalCount shouldBe 3L
                }
            }
        }

        test("crash recovery continues surviving session and immediately finalizes vanished session with CRASH_RECOVERY") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    env.users
                        .upsert(
                            UserRecord(
                                id = "u-1",
                                serverId = DEFAULT_SERVER_ID,
                                externalUserId = "ext-user-1",
                                username = "alice",
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()

                    val surviving =
                        ActiveSessionRecord(
                            serverId = DEFAULT_SERVER_ID,
                            sessionKey = "10",
                            sessionId = "plex-10",
                            userId = "u-1",
                            media =
                                MediaMetadataRecord(
                                    ratingKey = "movie-a",
                                    mediaType = "movie",
                                    title = "Movie A",
                                    durationMs = 1_000_000L,
                                ),
                            viewOffsetMs = 120_000L,
                            state = "playing",
                            streamTech = StreamTechRecord(streamDecision = "direct_play"),
                            player = PlayerRecord(playerName = "Web", playerMachineId = "player-1", location = "lan"),
                            startedAtMs = 100_000L,
                            lastSeenAtMs = 200_000L,
                            pausedDurationSec = 15,
                            missedPolls = 1,
                        )
                    val vanished =
                        ActiveSessionRecord(
                            serverId = DEFAULT_SERVER_ID,
                            sessionKey = "11",
                            sessionId = "plex-11",
                            userId = "u-1",
                            media =
                                MediaMetadataRecord(
                                    ratingKey = "movie-b",
                                    mediaType = "movie",
                                    title = "Movie B",
                                    durationMs = 1_000_000L,
                                ),
                            viewOffsetMs = 400_000L,
                            state = "playing",
                            streamTech = StreamTechRecord(streamDecision = "transcode"),
                            player = PlayerRecord(playerName = "TV", playerMachineId = "player-2", location = "lan"),
                            startedAtMs = 50_000L,
                            lastSeenAtMs = 250_000L,
                            pausedDurationSec = 20,
                            missedPolls = 0,
                        )
                    env.sessions.upsert(surviving).shouldBeRight()
                    env.sessions.upsert(vanished).shouldBeRight()

                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 60))
                    engine.recoverFromPersistence().shouldBeRight()
                    engine.currentSessions() shouldHaveSize 2

                    env.nowMs = 500_000L
                    val events =
                        engine
                            .reconcile(
                                listOf(
                                    sampleSnapshot(
                                        sessionKey = "10",
                                        ratingKey = "movie-a",
                                        title = "Movie A",
                                        viewOffsetMs = 420_000L,
                                    ),
                                ),
                                ReconcileTrigger.PERIODIC,
                            ).shouldBeRight()

                    events shouldHaveSize 2
                    val updatedA = events.filterIsInstance<SessionEvent.SessionUpdated>().single()
                    updatedA.session.startedAtMs shouldBe 100_000L
                    updatedA.session.pausedDurationSec shouldBe 15
                    updatedA.session.missedPolls shouldBe 0

                    val stoppedB = events.filterIsInstance<SessionEvent.SessionStopped>().single()
                    stoppedB.key.ratingKey shouldBe "movie-b"
                    stoppedB.reason shouldBe StopReason.CRASH_RECOVERY
                    val histB = stoppedB.historyRecord.shouldNotBeNull()
                    histB.startedAtMs shouldBe 50_000L
                    histB.stoppedAtMs shouldBe 250_000L
                    histB.pausedDurationSec shouldBe 20
                    histB.watchedDurationSec shouldBe 180
                }
            }
        }

        test("preserves existing user email, displayName, and isAdmin when updating lastSeenAtMs on reconcile") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    env.users
                        .upsert(
                            UserRecord(
                                id = "existing-user-id",
                                serverId = DEFAULT_SERVER_ID,
                                externalUserId = "ext-user-1",
                                username = "alice",
                                displayName = "Alice Wonderland",
                                email = "alice@example.org",
                                thumbUrl = "https://plex.tv/custom",
                                isAdmin = true,
                                lastSeenAtMs = 1_000L,
                                createdAtMs = 500L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()

                    val engine = env.createEngine()
                    env.nowMs = 50_000L
                    val started =
                        engine
                            .reconcile(
                                listOf(sampleSnapshot(userExternalId = "ext-user-1", username = "alice")),
                                ReconcileTrigger.NOTIFICATION,
                            ).shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStarted>()

                    started.session.userId shouldBe "existing-user-id"
                    val reloaded = env.users.findById("existing-user-id").shouldBeRight().shouldNotBeNull()
                    reloaded.displayName shouldBe "Alice Wonderland"
                    reloaded.email shouldBe "alice@example.org"
                    reloaded.isAdmin shouldBe true
                    reloaded.lastSeenAtMs shouldBe 50_000L
                }
            }
        }

        test("buildPlexRuntime with databaseHandle wires SessionStateEngine and resolves serverId lazily with offline fallback") {
            runTest {
                newEnv().use { env ->
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    var plexUp = false
                    val mockEngine =
                        MockEngine { request ->
                            if (!plexUp) {
                                respond(content = "down", status = HttpStatusCode.ServiceUnavailable)
                            } else {
                                when (request.url.encodedPath) {
                                    "/" -> respond(content = PlexFixtures.serverRootJson, status = HttpStatusCode.OK, headers = jsonHeaders)
                                    "/status/sessions" ->
                                        respond(content = PlexFixtures.emptySessionsJson, status = HttpStatusCode.OK, headers = jsonHeaders)
                                    else -> respond(content = "", status = HttpStatusCode.NotFound)
                                }
                            }
                        }

                    val runtime =
                        buildPlexRuntime(
                            config =
                                PlexConfig(
                                    baseUrl = "http://plex.local:32400",
                                    token = "test-token",
                                    websocketEnabled = false,
                                ),
                            databaseHandle = env.handle,
                            sessionConfig = SessionConfig(minPlayDurationSeconds = 30),
                            httpClientEngine = mockEngine,
                        )
                    val engine = runtime.sessionEngine.shouldNotBeNull()

                    // When Plex is down and DB has no matching server, reconcile returns Left and onSnapshots handles it cleanly
                    engine.reconcile(emptyList(), ReconcileTrigger.PERIODIC).shouldBeLeft()
                    engine.onSnapshots(emptyList(), ReconcileTrigger.PERIODIC)

                    // When Plex comes up, serverId is resolved via checkHealth() and persisted to media_servers
                    plexUp = true
                    engine.reconcile(emptyList(), ReconcileTrigger.PERIODIC).shouldBeRight().shouldBeEmpty()
                    val persistedServer = env.servers.findByMachineId("abc123machine").shouldBeRight().shouldNotBeNull()
                    persistedServer.name shouldBe "KerrLab Plex"

                    // Fresh runtime when Plex is down falls back to existing media_servers row matching serverType + baseUrl
                    plexUp = false
                    val runtimeFallback =
                        buildPlexRuntime(
                            config =
                                PlexConfig(
                                    baseUrl = "http://plex.local:32400",
                                    token = "test-token",
                                    websocketEnabled = false,
                                ),
                            databaseHandle = env.handle,
                            httpClientEngine = MockEngine { respond("down", HttpStatusCode.ServiceUnavailable) },
                        )
                    val fallbackEngine = runtimeFallback.sessionEngine.shouldNotBeNull()
                    val started =
                        fallbackEngine
                            .reconcile(listOf(sampleSnapshot()), ReconcileTrigger.PERIODIC)
                            .shouldBeRight()
                            .single()
                            .shouldBeInstanceOf<SessionEvent.SessionStarted>()
                    started.serverId shouldBe persistedServer.id

                    runtime.realtime.stop()
                    runtimeFallback.realtime.stop()
                }
            }
        }

        test("reconcile propagates Left when serverIdProvider fails") {
            runTest {
                newEnv().use { env ->
                    val failingEngine =
                        SessionStateEngine(
                            serverIdProvider = { MiruError.UpstreamUnavailable(provider = "plex", message = "offline").left() },
                            userRepository = env.users,
                            activeSessionRepository = env.sessions,
                            playbackHistoryRepository = env.history,
                        )
                    failingEngine.reconcile(listOf(sampleSnapshot()), ReconcileTrigger.PERIODIC).shouldBeLeft()
                }
            }
        }

        test("maintains live transcode technical metadata overlay across start, update, and stop") {
            runTest {
                newEnv().use { env ->
                    env.seedServer()
                    val engine = env.createEngine(config = SessionConfig(minPlayDurationSeconds = 1))

                    val initialSnapshot =
                        sampleSnapshot(
                            sessionKey = "tech-1",
                            ratingKey = "mov-tech",
                            decision = StreamDecision.TRANSCODE,
                            isHwTranscode = true,
                        ).let {
                            it.copy(
                                technical =
                                    it.technical.copy(
                                        transcodeVideoCodec = "h264",
                                        transcodeAudioCodec = "aac",
                                        container = "mkv",
                                        transcodeContainer = "mp4",
                                        transcodeProgress = 42.5,
                                        transcodeSpeed = 0.8,
                                    ),
                            )
                        }

                    env.nowMs = 1_000L
                    engine.reconcile(listOf(initialSnapshot), ReconcileTrigger.NOTIFICATION).shouldBeRight()
                    val key = ActiveSessionKey(DEFAULT_SERVER_ID, "tech-1", "mov-tech")
                    val afterStart = engine.currentLiveTechnical()[key].shouldNotBeNull()
                    afterStart.transcodeProgress shouldBe 42.5
                    afterStart.transcodeSpeed shouldBe 0.8
                    afterStart.container shouldBe "mkv"
                    afterStart.transcodeContainer shouldBe "mp4"

                    val updatedSnapshot =
                        initialSnapshot.copy(
                            technical = initialSnapshot.technical.copy(transcodeProgress = 75.0, transcodeSpeed = 2.4),
                        )
                    env.nowMs = 5_000L
                    engine.reconcile(listOf(updatedSnapshot), ReconcileTrigger.PERIODIC).shouldBeRight()
                    val afterUpdate = engine.currentLiveTechnical()[key].shouldNotBeNull()
                    afterUpdate.transcodeProgress shouldBe 75.0
                    afterUpdate.transcodeSpeed shouldBe 2.4

                    env.nowMs = 10_000L
                    engine
                        .reconcile(
                            listOf(initialSnapshot.copy(state = PlaybackState.STOPPED, viewOffsetMs = 100_000L)),
                            ReconcileTrigger.NOTIFICATION,
                        ).shouldBeRight()
                    engine.currentLiveTechnical()[key].shouldBeNull()
                }
            }
        }
    })
