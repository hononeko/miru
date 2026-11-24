package app.hononeko.miru.session

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import app.hononeko.miru.logging.kv
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Why a reconciliation pass was run. */
enum class ReconcileTrigger {
    /** A (debounced) burst of `playing` notifications. */
    NOTIFICATION,

    /** Fallback timer: no reconcile happened within the configured interval. */
    PERIODIC,

    /** The notification socket was re-established and events may have been missed. */
    RECONNECT,
}

/** Receives the authoritative list of active sessions after each successful reconciliation. */
fun interface ActiveSessionsSink {
    suspend fun onSnapshots(
        snapshots: List<ActiveStreamSnapshot>,
        trigger: ReconcileTrigger,
    )
}

/** Placeholder sink until the session-state engine consumes snapshots. */
class LoggingActiveSessionsSink : ActiveSessionsSink {
    private val logger = LoggerFactory.getLogger(LoggingActiveSessionsSink::class.java)

    override suspend fun onSnapshots(
        snapshots: List<ActiveStreamSnapshot>,
        trigger: ReconcileTrigger,
    ) {
        logger.debug("Reconciled active sessions {} {}", kv("trigger", trigger.name), kv("sessions", snapshots.size))
    }
}

/**
 * Keeps Miru's view of active sessions in sync with the media server.
 *
 * All `fetchActiveSessions()` calls are serialized through a single actor so bursts, timer ticks and
 * reconnects never fetch concurrently. `playing` notifications are debounced ([debounceWindow] quiet period,
 * bounded by [maxWait]); a fallback reconcile runs whenever [interval] elapses without one, catching clients
 * that vanish without emitting a `stopped` notification.
 */
class SessionReconciler(
    private val adapter: MediaServerAdapter,
    private val sink: ActiveSessionsSink,
    private val scope: CoroutineScope,
    private val interval: Duration,
    private val debounceWindow: Duration = DEFAULT_DEBOUNCE_WINDOW,
    private val maxWait: Duration = DEFAULT_MAX_WAIT,
    private val reconnectSignals: Flow<Unit> = emptyFlow(),
    private val onReconcileOutcome: (suspend (Either<MiruError, Unit>) -> Unit)? = null,
) {
    init {
        require(interval.isPositive()) { "interval must be positive" }
        require(debounceWindow.isPositive()) { "debounceWindow must be positive" }
        require(maxWait >= debounceWindow) { "maxWait must be >= debounceWindow" }
    }

    private val logger = LoggerFactory.getLogger(SessionReconciler::class.java)
    private val lock = Any()
    private var job: Job? = null

    /** Starts the reconciler. Idempotent: repeated calls return the already-running job. */
    fun start(): Job =
        synchronized(lock) {
            job?.takeIf { it.isActive }
                ?: scope.launch(CoroutineName("session-reconciler")) { run() }.also { job = it }
        }

    suspend fun stop() {
        val current = synchronized(lock) { job.also { job = null } }
        current?.cancelAndJoin()
    }

    private suspend fun run() {
        supervisorScope {
            val triggers = Channel<ReconcileTrigger>(Channel.CONFLATED)
            val burst = Channel<Unit>(Channel.CONFLATED)

            launch(CoroutineName("session-reconciler-events")) {
                adapter
                    .events()
                    .filterIsInstance<MediaServerEvent.PlayingEvent>()
                    .catch { e -> logger.warn("Media server event stream failed: {}", e.message) }
                    .collect { burst.send(Unit) }
            }
            launch(CoroutineName("session-reconciler-debounce")) { debounce(burst, triggers) }
            launch(CoroutineName("session-reconciler-reconnect")) {
                reconnectSignals
                    .catch { e -> logger.warn("Reconnect signal stream failed: {}", e.message) }
                    .collect { triggers.send(ReconcileTrigger.RECONNECT) }
            }
            launch(CoroutineName("session-reconciler-actor")) {
                reconcile(ReconcileTrigger.PERIODIC)
                while (true) {
                    val trigger = withTimeoutOrNull(interval) { triggers.receive() } ?: ReconcileTrigger.PERIODIC
                    reconcile(trigger)
                }
            }
        }
    }

    private suspend fun debounce(
        burst: ReceiveChannel<Unit>,
        triggers: Channel<ReconcileTrigger>,
    ) {
        while (true) {
            burst.receive()
            // Completes (non-null) once the stream goes quiet for debounceWindow; null once maxWait is hit.
            withTimeoutOrNull(maxWait) {
                var moreEvents = true
                while (moreEvents) {
                    moreEvents = withTimeoutOrNull(debounceWindow) { burst.receive() } != null
                }
            }
            triggers.send(ReconcileTrigger.NOTIFICATION)
        }
    }

    private suspend fun reconcile(trigger: ReconcileTrigger) {
        try {
            adapter.fetchActiveSessions().fold(
                ifLeft = { error ->
                    logger.warn(
                        "Active session reconciliation failed {} {}",
                        kv("trigger", trigger.name),
                        kv("reason", error.message),
                    )
                    notifyReconcileOutcome(error.left())
                },
                ifRight = { snapshots ->
                    notifyReconcileOutcome(Unit.right())
                    sink.onSnapshots(snapshots, trigger)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error during session reconciliation {}", kv("trigger", trigger.name), e)
        }
    }

    private suspend fun notifyReconcileOutcome(outcome: Either<MiruError, Unit>) {
        val listener = onReconcileOutcome ?: return
        try {
            listener(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Session reconcile outcome listener failed: {}", e.message)
        }
    }

    companion object {
        val DEFAULT_DEBOUNCE_WINDOW: Duration = 500.milliseconds
        val DEFAULT_MAX_WAIT: Duration = 2.seconds
    }
}
