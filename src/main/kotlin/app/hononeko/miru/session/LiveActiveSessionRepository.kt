package app.hononeko.miru.session

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.MediaServerRepository
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import java.util.concurrent.atomic.AtomicReference

private const val MAX_CONSISTENT_READ_ATTEMPTS = 3

/**
 * `active_sessions` as [findAll] would return it, served from the session engines' memory.
 *
 * The engines write every row they track, so their memory already holds the live rows. What it can lack are rows no
 * engine has loaded: rows restored at startup before an engine's first reconcile (or while its server is
 * unreachable), and rows left for a server no runtime points at. Nothing writes those until an engine adopts or
 * closes them, which changes its [SessionStateEngine.sessionSetVersion]; so they are read from the database once
 * and kept until any engine's version moves. Removing a server cascades its rows away without an engine, so kept
 * rows are also checked against [mediaServerRepository]. Writes and the other reads go straight to [delegate].
 */
class LiveActiveSessionRepository(
    private val delegate: ActiveSessionRepository,
    private val engines: List<SessionStateEngine>,
    private val mediaServerRepository: MediaServerRepository? = null,
) : ActiveSessionRepository by delegate {
    private class Unloaded(
        val versions: List<Long>,
        val rows: List<ActiveSessionRecord>,
    )

    private val unloaded = AtomicReference<Unloaded?>(null)

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<ActiveSessionRecord>> =
        either {
            repeat(MAX_CONSISTENT_READ_ATTEMPTS) { _ ->
                val versions = versions()
                val cached = unloaded.get()?.takeIf { it.versions == versions }
                val persisted = if (cached == null) delegate.findAll().bind() else null
                val live = engines.flatMap { it.currentSessions() }
                // A session that started or ended between the two reads would be missing from one of them.
                if (versions() == versions) {
                    val liveKeys = live.mapTo(HashSet(live.size)) { it.key }
                    val rows = cached?.rows ?: persisted.orEmpty().filter { it.key !in liveKeys }
                    if (cached == null) unloaded.set(Unloaded(versions, rows))
                    return@either merge(live, withExistingServers(rows), liveKeys)
                }
            }
            // The engines kept changing under us; the table itself is always a consistent answer.
            delegate.findAll().bind()
        }

    private fun versions(): List<Long> = engines.map { it.sessionSetVersion() }

    private suspend fun Raise<MiruError.PersistenceError>.withExistingServers(
        rows: List<ActiveSessionRecord>,
    ): List<ActiveSessionRecord> {
        if (rows.isEmpty() || mediaServerRepository == null) return rows
        val serverIds = mediaServerRepository.findAll().bind().mapTo(HashSet()) { it.id }
        return rows.filter { it.serverId in serverIds }
    }

    private fun merge(
        live: List<ActiveSessionRecord>,
        unloadedRows: List<ActiveSessionRecord>,
        liveKeys: Set<ActiveSessionKey>,
    ): List<ActiveSessionRecord> = (live + unloadedRows.filter { it.key !in liveKeys }).sortedWith(ACTIVE_SESSION_ORDER)

    override fun toString(): String = "LiveActiveSessionRepository(engines=${engines.size})"

    private companion object {
        // The order ExposedActiveSessionRepository.findAll sorts by.
        val ACTIVE_SESSION_ORDER: Comparator<ActiveSessionRecord> =
            compareByDescending<ActiveSessionRecord> { it.startedAtMs }
                .thenBy { it.serverId }
                .thenBy { it.sessionKey }
                .thenBy { it.media.ratingKey }
    }
}
