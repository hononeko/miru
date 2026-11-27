package app.hononeko.miru.persistence.repository

import app.hononeko.miru.MiruJson
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.exposedQuery
import app.hononeko.miru.persistence.exposedReadQuery
import app.hononeko.miru.persistence.model.WebhookDeliveryFilter
import app.hononeko.miru.persistence.model.WebhookDeliveryPage
import app.hononeko.miru.persistence.model.WebhookDeliveryRecord
import app.hononeko.miru.persistence.model.WebhookDeliveryStatus
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.persistence.table.WebhookDeliveriesTable
import app.hononeko.miru.persistence.table.WebhookTargetsTable
import arrow.core.Either
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

private const val PRUNE_BATCH_SIZE = 500

interface WebhookTargetRepository {
    suspend fun findAll(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>>

    suspend fun findEnabled(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>>

    suspend fun findById(id: String): Either<MiruError.PersistenceError, WebhookTargetRecord?>

    suspend fun insert(record: WebhookTargetRecord): Either<MiruError.PersistenceError, WebhookTargetRecord>

    suspend fun update(record: WebhookTargetRecord): Either<MiruError.PersistenceError, Boolean>

    suspend fun deleteById(id: String): Either<MiruError.PersistenceError, Boolean>
}

interface WebhookDeliveryRepository {
    suspend fun insert(record: WebhookDeliveryRecord): Either<MiruError.PersistenceError, WebhookDeliveryRecord>

    suspend fun update(record: WebhookDeliveryRecord): Either<MiruError.PersistenceError, Boolean>

    suspend fun query(
        filter: WebhookDeliveryFilter = WebhookDeliveryFilter(),
    ): Either<MiruError.PersistenceError, WebhookDeliveryPage>

    suspend fun pruneOldest(maxRows: Int): Either<MiruError.PersistenceError, Int>
}

class ExposedWebhookTargetRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : WebhookTargetRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>> =
        readDatabase.exposedReadQuery("webhook_target_find_all") {
            WebhookTargetsTable
                .selectAll()
                .orderBy(
                    WebhookTargetsTable.createdAtMs to SortOrder.ASC,
                    WebhookTargetsTable.id to SortOrder.ASC,
                ).map { it.toWebhookTargetRecord() }
        }

    override suspend fun findEnabled(): Either<MiruError.PersistenceError, List<WebhookTargetRecord>> =
        readDatabase.exposedReadQuery("webhook_target_find_enabled") {
            WebhookTargetsTable
                .selectAll()
                .where { WebhookTargetsTable.enabled eq true }
                .orderBy(
                    WebhookTargetsTable.createdAtMs to SortOrder.ASC,
                    WebhookTargetsTable.id to SortOrder.ASC,
                ).map { it.toWebhookTargetRecord() }
        }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, WebhookTargetRecord?> =
        readDatabase.exposedReadQuery("webhook_target_find_by_id") {
            WebhookTargetsTable
                .selectAll()
                .where { WebhookTargetsTable.id eq id }
                .singleOrNull()
                ?.toWebhookTargetRecord()
        }

    override suspend fun insert(record: WebhookTargetRecord): Either<MiruError.PersistenceError, WebhookTargetRecord> =
        database.exposedQuery("webhook_target_insert") {
            WebhookTargetsTable.insert {
                it[id] = record.id
                it[name] = record.name
                it[url] = record.url
                it[enabled] = record.enabled
                it[subscribedEvents] = encodeSubscribedEvents(record.subscribedEvents)
                it[encryptedSigningSecret] = record.encryptedSigningSecret
                it[authHeaderName] = record.authHeaderName
                it[encryptedAuthHeaderValue] = record.encryptedAuthHeaderValue
                it[cooldownSeconds] = record.cooldownSeconds
                it[concurrentStreamThreshold] = record.concurrentStreamThreshold
                it[bufferingSpikeThreshold] = record.bufferingSpikeThreshold
                it[bufferingWindowSeconds] = record.bufferingWindowSeconds
                it[transcode4kOnly] = record.transcode4kOnly
                it[createdAtMs] = record.createdAtMs
                it[updatedAtMs] = record.updatedAtMs
            }
            record
        }

    override suspend fun update(record: WebhookTargetRecord): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("webhook_target_update") {
            val updated =
                WebhookTargetsTable.update({ WebhookTargetsTable.id eq record.id }) {
                    it[name] = record.name
                    it[url] = record.url
                    it[enabled] = record.enabled
                    it[subscribedEvents] = encodeSubscribedEvents(record.subscribedEvents)
                    it[encryptedSigningSecret] = record.encryptedSigningSecret
                    it[authHeaderName] = record.authHeaderName
                    it[encryptedAuthHeaderValue] = record.encryptedAuthHeaderValue
                    it[cooldownSeconds] = record.cooldownSeconds
                    it[concurrentStreamThreshold] = record.concurrentStreamThreshold
                    it[bufferingSpikeThreshold] = record.bufferingSpikeThreshold
                    it[bufferingWindowSeconds] = record.bufferingWindowSeconds
                    it[transcode4kOnly] = record.transcode4kOnly
                    it[updatedAtMs] = record.updatedAtMs
                }
            updated > 0
        }

    override suspend fun deleteById(id: String): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("webhook_target_delete") {
            WebhookTargetsTable.deleteWhere { WebhookTargetsTable.id eq id } > 0
        }
}

class ExposedWebhookDeliveryRepository(
    private val database: Database,
    private val readDatabase: Database = database,
) : WebhookDeliveryRepository {
    constructor(handle: DatabaseHandle) : this(
        database = handle.database,
        readDatabase = handle.readDatabase,
    )

    override suspend fun insert(record: WebhookDeliveryRecord): Either<MiruError.PersistenceError, WebhookDeliveryRecord> =
        database.exposedQuery("webhook_delivery_insert") {
            WebhookDeliveriesTable.insert {
                it[id] = record.id
                it[targetId] = record.targetId
                it[targetName] = record.targetName
                it[eventId] = record.eventId
                it[eventType] = record.eventType
                it[status] = record.status.name
                it[attemptCount] = record.attemptCount
                it[httpStatus] = record.httpStatus
                it[durationMs] = record.durationMs
                it[errorMessage] = record.errorMessage
                it[createdAtMs] = record.createdAtMs
                it[completedAtMs] = record.completedAtMs
            }
            record
        }

    override suspend fun update(record: WebhookDeliveryRecord): Either<MiruError.PersistenceError, Boolean> =
        database.exposedQuery("webhook_delivery_update") {
            val updated =
                WebhookDeliveriesTable.update({ WebhookDeliveriesTable.id eq record.id }) {
                    it[targetName] = record.targetName
                    it[status] = record.status.name
                    it[attemptCount] = record.attemptCount
                    it[httpStatus] = record.httpStatus
                    it[durationMs] = record.durationMs
                    it[errorMessage] = record.errorMessage
                    it[completedAtMs] = record.completedAtMs
                }
            updated > 0
        }

    override suspend fun query(filter: WebhookDeliveryFilter): Either<MiruError.PersistenceError, WebhookDeliveryPage> {
        val safeLimit = filter.limit.coerceAtLeast(0)
        val safeOffset = filter.offset.coerceAtLeast(0)
        return readDatabase.exposedReadQuery("webhook_delivery_query") {
            val predicates = mutableListOf<Op<Boolean>>()
            filter.targetId?.let { predicates += WebhookDeliveriesTable.targetId eq it }
            filter.eventType?.let { predicates += WebhookDeliveriesTable.eventType eq it }
            filter.status?.let { predicates += WebhookDeliveriesTable.status eq it.name }
            val condition =
                if (predicates.isEmpty()) {
                    Op.TRUE
                } else {
                    predicates.reduce { acc, next -> acc and next }
                }

            val totalCount =
                WebhookDeliveriesTable
                    .selectAll()
                    .where { condition }
                    .count()

            val items =
                if (safeLimit == 0) {
                    emptyList()
                } else {
                    WebhookDeliveriesTable
                        .selectAll()
                        .where { condition }
                        .orderBy(
                            WebhookDeliveriesTable.createdAtMs to SortOrder.DESC,
                            WebhookDeliveriesTable.id to SortOrder.DESC,
                        ).limit(safeLimit)
                        .offset(safeOffset.toLong())
                        .map { it.toWebhookDeliveryRecord() }
                }

            WebhookDeliveryPage(
                items = items,
                totalCount = totalCount,
                limit = safeLimit,
                offset = safeOffset,
            )
        }
    }

    override suspend fun pruneOldest(maxRows: Int): Either<MiruError.PersistenceError, Int> =
        database.exposedQuery("webhook_delivery_prune") {
            val cap = maxRows.coerceAtLeast(0)
            val total = WebhookDeliveriesTable.selectAll().count()
            if (total <= cap) {
                return@exposedQuery 0
            }
            val excess = (total - cap).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val idsToDelete =
                WebhookDeliveriesTable
                    .select(WebhookDeliveriesTable.id)
                    .orderBy(
                        WebhookDeliveriesTable.createdAtMs to SortOrder.ASC,
                        WebhookDeliveriesTable.id to SortOrder.ASC,
                    ).limit(excess)
                    .map { it[WebhookDeliveriesTable.id] }

            var deleted = 0
            for (batch in idsToDelete.chunked(PRUNE_BATCH_SIZE)) {
                deleted += WebhookDeliveriesTable.deleteWhere { WebhookDeliveriesTable.id inList batch }
            }
            deleted
        }
}

private fun encodeSubscribedEvents(events: List<WebhookEventType>): String =
    MiruJson.encodeToString(events.map { it.eventKey })

private fun decodeSubscribedEvents(raw: String): List<WebhookEventType> {
    val keys =
        runCatching { MiruJson.decodeFromString<List<String>>(raw) }
            .getOrElse { raw.split(',').map { part -> part.trim() }.filter { part -> part.isNotEmpty() } }
    return keys.mapNotNull { WebhookEventType.fromKey(it) }.distinct()
}

private fun ResultRow.toWebhookTargetRecord(): WebhookTargetRecord =
    WebhookTargetRecord(
        id = this[WebhookTargetsTable.id],
        name = this[WebhookTargetsTable.name],
        url = this[WebhookTargetsTable.url],
        enabled = this[WebhookTargetsTable.enabled],
        subscribedEvents = decodeSubscribedEvents(this[WebhookTargetsTable.subscribedEvents]),
        encryptedSigningSecret = this[WebhookTargetsTable.encryptedSigningSecret],
        authHeaderName = this[WebhookTargetsTable.authHeaderName],
        encryptedAuthHeaderValue = this[WebhookTargetsTable.encryptedAuthHeaderValue],
        cooldownSeconds = this[WebhookTargetsTable.cooldownSeconds],
        concurrentStreamThreshold = this[WebhookTargetsTable.concurrentStreamThreshold],
        bufferingSpikeThreshold = this[WebhookTargetsTable.bufferingSpikeThreshold],
        bufferingWindowSeconds = this[WebhookTargetsTable.bufferingWindowSeconds],
        transcode4kOnly = this[WebhookTargetsTable.transcode4kOnly],
        createdAtMs = this[WebhookTargetsTable.createdAtMs],
        updatedAtMs = this[WebhookTargetsTable.updatedAtMs],
    )

private fun ResultRow.toWebhookDeliveryRecord(): WebhookDeliveryRecord =
    WebhookDeliveryRecord(
        id = this[WebhookDeliveriesTable.id],
        targetId = this[WebhookDeliveriesTable.targetId],
        targetName = this[WebhookDeliveriesTable.targetName],
        eventId = this[WebhookDeliveriesTable.eventId],
        eventType = this[WebhookDeliveriesTable.eventType],
        status = WebhookDeliveryStatus.valueOf(this[WebhookDeliveriesTable.status]),
        attemptCount = this[WebhookDeliveriesTable.attemptCount],
        httpStatus = this[WebhookDeliveriesTable.httpStatus],
        durationMs = this[WebhookDeliveriesTable.durationMs],
        errorMessage = this[WebhookDeliveriesTable.errorMessage],
        createdAtMs = this[WebhookDeliveriesTable.createdAtMs],
        completedAtMs = this[WebhookDeliveriesTable.completedAtMs],
    )
