package app.hononeko.miru.webhook

import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.persistence.repository.WebhookTargetRepository
import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.SessionStateEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory

/**
 * Listens to live session events, stream terminations, and Plex reconcile health transitions,
 * evaluates subscribed webhook targets, and enqueues non-blocking deliveries on [WebhookDispatcher].
 */
@Suppress("LongParameterList")
class WebhookRunner(
    private val sessionEngine: SessionStateEngine,
    private val terminatedEvents: SharedFlow<StreamTerminatedEvent>,
    private val reconcileHealthSignals: SharedFlow<ReconcileHealthSignal>,
    private val targetRepository: WebhookTargetRepository,
    private val userRepository: UserRepository,
    private val serverInfoProvider: suspend () -> WebhookServerPayload?,
    private val evaluator: WebhookEventEvaluator,
    private val dispatcher: WebhookDispatcher,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(WebhookRunner::class.java)
    private val lock = Any()
    private var job: Job? = null

    fun start(): Job =
        synchronized(lock) {
            job?.takeIf { it.isActive }
                ?: scope.launch(CoroutineName("webhook-runner")) { run() }.also { job = it }
        }

    suspend fun stop() {
        val current = synchronized(lock) { job.also { job = null } }
        current?.cancelAndJoin()
    }

    private suspend fun run() {
        supervisorScope {
            launch(CoroutineName("webhook-runner-sessions")) {
                sessionEngine.sessionEvents.collect { event ->
                    handleSessionEvent(event)
                }
            }
            launch(CoroutineName("webhook-runner-terminations")) {
                terminatedEvents.collect { event ->
                    handleStreamTerminated(event)
                }
            }
            launch(CoroutineName("webhook-runner-health")) {
                reconcileHealthSignals.collect { signal ->
                    handleReconcileHealth(signal)
                }
            }
        }
    }

    private suspend fun handleSessionEvent(event: SessionEvent) {
        try {
            val targets = loadEnabledTargets()
            val activeSessions = sessionEngine.currentSessions()
            val server = if (targets.isNotEmpty()) resolveServerPayload(event.serverId) else null
            val user =
                if (targets.isNotEmpty() && event !is SessionEvent.SessionStopped) {
                    val userId =
                        when (event) {
                            is SessionEvent.SessionStarted -> event.session.userId
                            is SessionEvent.SessionUpdated -> event.session.userId
                            is SessionEvent.SessionStopped -> event.session.userId
                        }
                    userRepository.findById(userId).getOrNull()
                } else {
                    null
                }
            val matches = evaluator.onSessionEvent(event, activeSessions, targets, server, user)
            for (match in matches) {
                dispatcher.enqueue(match.target, match.payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Webhook session event evaluation failed for {}: {}", event.key, e.message)
        }
    }

    private suspend fun handleStreamTerminated(event: StreamTerminatedEvent) {
        try {
            val targets = loadEnabledTargets()
            if (targets.isEmpty()) return
            val server = resolveServerPayload(event.serverId)
            val user = userRepository.findById(event.session.userId).getOrNull()
            val matches = evaluator.onStreamTerminated(event, targets, server, user)
            for (match in matches) {
                dispatcher.enqueue(match.target, match.payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Webhook stream termination evaluation failed: {}", e.message)
        }
    }

    private suspend fun handleReconcileHealth(signal: ReconcileHealthSignal) {
        try {
            val targets = loadEnabledTargets()
            val server = if (targets.isNotEmpty()) serverInfoProvider() else null
            val matches = evaluator.onReconcileHealth(signal, targets, server)
            for (match in matches) {
                dispatcher.enqueue(match.target, match.payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Webhook server health evaluation failed: {}", e.message)
        }
    }

    private suspend fun loadEnabledTargets() =
        targetRepository.findEnabled().fold(
            ifLeft = { error ->
                logger.warn("Failed loading enabled webhook targets: {}", error.message)
                emptyList()
            },
            ifRight = { it },
        )

    private suspend fun resolveServerPayload(fallbackServerId: String): WebhookServerPayload {
        val current = serverInfoProvider()
        return if (current != null && current.id == fallbackServerId) {
            current
        } else {
            WebhookServerPayload(id = fallbackServerId, name = current?.name)
        }
    }
}
