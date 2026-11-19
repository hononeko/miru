package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.table.ActiveSessionsTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

interface ActiveSessionRepository {
    suspend fun upsert(session: ActiveSessionRecord): Either<MiruError.PersistenceError, Unit>

    suspend fun findByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, ActiveSessionRecord?>

    suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<ActiveSessionRecord>>

    suspend fun findAll(): Either<MiruError.PersistenceError, List<ActiveSessionRecord>>

    suspend fun deleteByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, Boolean>

    suspend fun deleteStaleMissing(
        serverId: String,
        activeKeys: Set<ActiveSessionKey>,
    ): Either<MiruError.PersistenceError, Int>
}

class ExposedActiveSessionRepository(
    private val database: Database,
) : ActiveSessionRepository {
    constructor(handle: DatabaseHandle) : this(handle.database)

    override suspend fun upsert(session: ActiveSessionRecord): Either<MiruError.PersistenceError, Unit> =
        database
            .exposedQuery("active_session_upsert") {
                ActiveSessionsTable.upsert(
                    ActiveSessionsTable.serverId,
                    ActiveSessionsTable.sessionKey,
                    ActiveSessionsTable.ratingKey,
                ) {
                    it[serverId] = session.serverId
                    it[sessionKey] = session.sessionKey
                    it[ratingKey] = session.media.ratingKey
                    it[sessionId] = session.sessionId
                    it[userId] = session.userId
                    it[mediaType] = session.media.mediaType
                    it[title] = session.media.title
                    it[parentTitle] = session.media.parentTitle
                    it[grandparentTitle] = session.media.grandparentTitle
                    it[parentIndex] = session.media.parentIndex
                    it[mediaIndex] = session.media.mediaIndex
                    it[year] = session.media.year
                    it[thumbPath] = session.media.thumbPath
                    it[librarySectionKey] = session.media.librarySectionKey
                    it[durationMs] = session.media.durationMs
                    it[viewOffsetMs] = session.viewOffsetMs
                    it[state] = session.state
                    it[streamDecision] = session.streamTech.streamDecision
                    it[videoDecision] = session.streamTech.videoDecision
                    it[audioDecision] = session.streamTech.audioDecision
                    it[hwTranscode] = session.streamTech.hwTranscode
                    it[videoCodec] = session.streamTech.videoCodec
                    it[audioCodec] = session.streamTech.audioCodec
                    it[videoResolution] = session.streamTech.videoResolution
                    it[bandwidthKbps] = session.streamTech.bandwidthKbps
                    it[playerName] = session.player.playerName
                    it[playerPlatform] = session.player.playerPlatform
                    it[playerProduct] = session.player.playerProduct
                    it[playerMachineId] = session.player.playerMachineId
                    it[ipAddress] = session.player.ipAddress
                    it[location] = session.player.location
                    it[startedAtMs] = session.startedAtMs
                    it[lastSeenAtMs] = session.lastSeenAtMs
                    it[pausedDurationSec] = session.pausedDurationSec
                    it[missedPolls] = session.missedPolls
                }
            }.map { }

    override suspend fun findByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, ActiveSessionRecord?> =
        database.exposedQuery("active_session_find_by_key") {
            ActiveSessionsTable
                .selectAll()
                .where {
                    (ActiveSessionsTable.serverId eq key.serverId) and
                        (ActiveSessionsTable.sessionKey eq key.sessionKey) and
                        (ActiveSessionsTable.ratingKey eq key.ratingKey)
                }.singleOrNull()
                ?.toActiveSessionRecord()
        }

    override suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<ActiveSessionRecord>> =
        database.exposedQuery("active_session_find_by_server") {
            ActiveSessionsTable
                .selectAll()
                .where { ActiveSessionsTable.serverId eq serverId }
                .orderBy(
                    ActiveSessionsTable.startedAtMs to SortOrder.DESC,
                    ActiveSessionsTable.sessionKey to SortOrder.ASC,
                    ActiveSessionsTable.ratingKey to SortOrder.ASC,
                ).map { it.toActiveSessionRecord() }
        }

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<ActiveSessionRecord>> =
        database.exposedQuery("active_session_find_all") {
            ActiveSessionsTable
                .selectAll()
                .orderBy(
                    ActiveSessionsTable.startedAtMs to SortOrder.DESC,
                    ActiveSessionsTable.serverId to SortOrder.ASC,
                    ActiveSessionsTable.sessionKey to SortOrder.ASC,
                    ActiveSessionsTable.ratingKey to SortOrder.ASC,
                ).map { it.toActiveSessionRecord() }
        }

    override suspend fun deleteByKey(key: ActiveSessionKey): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("active_session_delete_by_key") {
            ActiveSessionsTable.deleteWhere {
                (ActiveSessionsTable.serverId eq key.serverId) and
                    (ActiveSessionsTable.sessionKey eq key.sessionKey) and
                    (ActiveSessionsTable.ratingKey eq key.ratingKey)
            } > 0
        }

    override suspend fun deleteStaleMissing(
        serverId: String,
        activeKeys: Set<ActiveSessionKey>,
    ): Either<MiruError.PersistenceError, Int> =
        database.exposedQuery("active_session_delete_stale_missing") {
            val serverActiveKeys = activeKeys.filterTo(HashSet()) { it.serverId == serverId }
            if (serverActiveKeys.isEmpty()) {
                return@exposedQuery ActiveSessionsTable.deleteWhere { ActiveSessionsTable.serverId eq serverId }
            }

            val existingKeys =
                ActiveSessionsTable
                    .select(ActiveSessionsTable.sessionKey, ActiveSessionsTable.ratingKey)
                    .where { ActiveSessionsTable.serverId eq serverId }
                    .map { row ->
                        ActiveSessionKey(
                            serverId = serverId,
                            sessionKey = row[ActiveSessionsTable.sessionKey],
                            ratingKey = row[ActiveSessionsTable.ratingKey],
                        )
                    }

            val staleKeys = existingKeys.filter { it !in serverActiveKeys }
            var deleted = 0
            for (key in staleKeys) {
                deleted +=
                    ActiveSessionsTable.deleteWhere {
                        (ActiveSessionsTable.serverId eq key.serverId) and
                            (ActiveSessionsTable.sessionKey eq key.sessionKey) and
                            (ActiveSessionsTable.ratingKey eq key.ratingKey)
                    }
            }
            deleted
        }
}
