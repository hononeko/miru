package app.hononeko.miru.persistence.repository

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.exposedReadQuery
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleRecord
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.StreamTerminationAuditRecord
import app.hononeko.miru.persistence.model.TerminationAuditAction
import app.hononeko.miru.persistence.model.TerminationAuditFilter
import app.hononeko.miru.persistence.model.TerminationAuditPage
import app.hononeko.miru.persistence.model.TerminationAuditSource
import app.hononeko.miru.persistence.table.GuardrailRulesTable
import app.hononeko.miru.persistence.table.StreamTerminationAuditTable
import arrow.core.Either
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

interface GuardrailRuleRepository {
    suspend fun findAll(): Either<MiruError.PersistenceError, List<GuardrailRuleRecord>>

    suspend fun findByRuleType(ruleType: GuardrailRuleType): Either<MiruError.PersistenceError, GuardrailRuleRecord?>

    suspend fun upsert(record: GuardrailRuleRecord): Either<MiruError.PersistenceError, GuardrailRuleRecord>
}

interface StreamTerminationAuditRepository {
    suspend fun insert(record: StreamTerminationAuditRecord): Either<MiruError.PersistenceError, StreamTerminationAuditRecord>

    suspend fun query(
        filter: TerminationAuditFilter = TerminationAuditFilter(),
    ): Either<MiruError.PersistenceError, TerminationAuditPage>
}

class ExposedGuardrailRuleRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : GuardrailRuleRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<GuardrailRuleRecord>> =
        readDatabase.exposedReadQuery("guardrail_rule_find_all") {
            GuardrailRulesTable
                .selectAll()
                .orderBy(GuardrailRulesTable.ruleType to SortOrder.ASC)
                .map { it.toGuardrailRuleRecord() }
        }

    override suspend fun findByRuleType(ruleType: GuardrailRuleType): Either<MiruError.PersistenceError, GuardrailRuleRecord?> =
        readDatabase.exposedReadQuery("guardrail_rule_find_by_type") {
            GuardrailRulesTable
                .selectAll()
                .where { GuardrailRulesTable.ruleType eq ruleType.name }
                .singleOrNull()
                ?.toGuardrailRuleRecord()
        }

    override suspend fun upsert(record: GuardrailRuleRecord): Either<MiruError.PersistenceError, GuardrailRuleRecord> =
        database.exposedQuery("guardrail_rule_upsert") {
            GuardrailRulesTable.upsert(
                GuardrailRulesTable.ruleType,
                onUpdate = {
                    it[GuardrailRulesTable.mode] = insertValue(GuardrailRulesTable.mode)
                    it[GuardrailRulesTable.thresholdInt] = insertValue(GuardrailRulesTable.thresholdInt)
                    it[GuardrailRulesTable.message] = insertValue(GuardrailRulesTable.message)
                    it[GuardrailRulesTable.updatedAtMs] = insertValue(GuardrailRulesTable.updatedAtMs)
                    it[GuardrailRulesTable.updatedBy] = insertValue(GuardrailRulesTable.updatedBy)
                },
            ) {
                it[ruleType] = record.ruleType.name
                it[mode] = record.mode.name
                it[thresholdInt] = record.thresholdInt
                it[message] = record.message
                it[updatedAtMs] = record.updatedAtMs
                it[updatedBy] = record.updatedBy
            }
            record
        }
}

class ExposedStreamTerminationAuditRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : StreamTerminationAuditRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

    override suspend fun insert(
        record: StreamTerminationAuditRecord,
    ): Either<MiruError.PersistenceError, StreamTerminationAuditRecord> =
        database.exposedQuery("stream_termination_audit_insert") {
            StreamTerminationAuditTable.insert {
                it[id] = record.id
                it[createdAtMs] = record.createdAtMs
                it[serverId] = record.serverId
                it[sessionId] = record.sessionId
                it[sessionKey] = record.sessionKey
                it[auditSource] = record.source.name
                it[action] = record.action.name
                it[ruleType] = record.ruleType?.name
                it[actorAccountId] = record.actorAccountId
                it[actorName] = record.actorName
                it[targetUserId] = record.targetUserId
                it[targetUsername] = record.targetUsername
                it[mediaRatingKey] = record.mediaRatingKey
                it[mediaTitle] = record.mediaTitle
                it[streamDecision] = record.streamDecision
                it[videoResolution] = record.videoResolution
                it[reason] = record.reason
                it[errorDetail] = record.errorDetail
            }
            record
        }

    override suspend fun query(filter: TerminationAuditFilter): Either<MiruError.PersistenceError, TerminationAuditPage> {
        val safeLimit = filter.limit.coerceAtLeast(0)
        val safeOffset = filter.offset.coerceAtLeast(0)
        return readDatabase.exposedReadQuery("stream_termination_audit_query") {
            val predicates = mutableListOf<Op<Boolean>>()
            filter.source?.let { predicates += StreamTerminationAuditTable.auditSource eq it.name }
            filter.action?.let { predicates += StreamTerminationAuditTable.action eq it.name }
            val condition =
                if (predicates.isEmpty()) {
                    Op.TRUE
                } else {
                    predicates.reduce { acc, next -> acc and next }
                }

            val totalCount =
                StreamTerminationAuditTable
                    .selectAll()
                    .where { condition }
                    .count()

            val items =
                if (safeLimit == 0) {
                    emptyList()
                } else {
                    StreamTerminationAuditTable
                        .selectAll()
                        .where { condition }
                        .orderBy(
                            StreamTerminationAuditTable.createdAtMs to SortOrder.DESC,
                            StreamTerminationAuditTable.id to SortOrder.DESC,
                        ).limit(safeLimit)
                        .offset(safeOffset.toLong())
                        .map { it.toStreamTerminationAuditRecord() }
                }

            TerminationAuditPage(
                items = items,
                totalCount = totalCount,
                limit = safeLimit,
                offset = safeOffset,
            )
        }
    }
}

private fun ResultRow.toGuardrailRuleRecord(): GuardrailRuleRecord =
    GuardrailRuleRecord(
        ruleType = GuardrailRuleType.valueOf(this[GuardrailRulesTable.ruleType]),
        mode = GuardrailMode.valueOf(this[GuardrailRulesTable.mode]),
        thresholdInt = this[GuardrailRulesTable.thresholdInt],
        message = this[GuardrailRulesTable.message],
        updatedAtMs = this[GuardrailRulesTable.updatedAtMs],
        updatedBy = this[GuardrailRulesTable.updatedBy],
    )

private fun ResultRow.toStreamTerminationAuditRecord(): StreamTerminationAuditRecord =
    StreamTerminationAuditRecord(
        id = this[StreamTerminationAuditTable.id],
        createdAtMs = this[StreamTerminationAuditTable.createdAtMs],
        serverId = this[StreamTerminationAuditTable.serverId],
        sessionId = this[StreamTerminationAuditTable.sessionId],
        sessionKey = this[StreamTerminationAuditTable.sessionKey],
        source = TerminationAuditSource.valueOf(this[StreamTerminationAuditTable.auditSource]),
        action = TerminationAuditAction.valueOf(this[StreamTerminationAuditTable.action]),
        ruleType = this[StreamTerminationAuditTable.ruleType]?.let { GuardrailRuleType.valueOf(it) },
        actorAccountId = this[StreamTerminationAuditTable.actorAccountId],
        actorName = this[StreamTerminationAuditTable.actorName],
        targetUserId = this[StreamTerminationAuditTable.targetUserId],
        targetUsername = this[StreamTerminationAuditTable.targetUsername],
        mediaRatingKey = this[StreamTerminationAuditTable.mediaRatingKey],
        mediaTitle = this[StreamTerminationAuditTable.mediaTitle],
        streamDecision = this[StreamTerminationAuditTable.streamDecision],
        videoResolution = this[StreamTerminationAuditTable.videoResolution],
        reason = this[StreamTerminationAuditTable.reason],
        errorDetail = this[StreamTerminationAuditTable.errorDetail],
    )
