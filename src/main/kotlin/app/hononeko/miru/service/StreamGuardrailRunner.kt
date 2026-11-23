package app.hononeko.miru.service

import app.hononeko.miru.session.SessionEvent
import app.hononeko.miru.session.SessionStateEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Listens to [SessionStateEngine.sessionEvents] and evaluates stream guardrails on live session updates.
 */
class StreamGuardrailRunner(
    private val sessionEngine: SessionStateEngine,
    private val streamControlService: StreamControlService,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(StreamGuardrailRunner::class.java)
    private val lock = Any()
    private var job: Job? = null

    fun start(): Job =
        synchronized(lock) {
            job?.takeIf { it.isActive }
                ?: scope.launch(CoroutineName("stream-guardrail-runner")) { run() }.also { job = it }
        }

    suspend fun stop() {
        val current = synchronized(lock) { job.also { job = null } }
        current?.cancelAndJoin()
    }

    private suspend fun run() {
        sessionEngine.sessionEvents.collect { event ->
            try {
                when (event) {
                    is SessionEvent.SessionStarted,
                    is SessionEvent.SessionUpdated,
                    -> streamControlService.evaluateActiveSessions(sessionEngine.currentSessions())

                    is SessionEvent.SessionStopped -> streamControlService.onSessionStopped(event.key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Guardrail evaluation failed on session event {}: {}", event.key, e.message)
            }
        }
    }
}
