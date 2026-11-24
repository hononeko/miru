package app.hononeko.miru.persistence.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object WebhookTargetsTable : Table("webhook_targets") {
    val id = text("id")
    val name = text("name")
    val url = text("url")
    val enabled = bool("enabled").default(true)
    val subscribedEvents = text("subscribed_events")
    val encryptedSigningSecret = text("encrypted_signing_secret").nullable()
    val authHeaderName = text("auth_header_name").nullable()
    val encryptedAuthHeaderValue = text("encrypted_auth_header_value").nullable()
    val cooldownSeconds = integer("cooldown_seconds").default(60)
    val concurrentStreamThreshold = integer("concurrent_stream_threshold").default(3)
    val bufferingSpikeThreshold = integer("buffering_spike_threshold").default(3)
    val bufferingWindowSeconds = integer("buffering_window_seconds").default(120)
    val transcode4kOnly = bool("transcode_4k_only").default(false)
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(id)
}

object WebhookDeliveriesTable : Table("webhook_deliveries") {
    val id = text("id")
    val targetId = text("target_id").references(WebhookTargetsTable.id, onDelete = ReferenceOption.CASCADE)
    val targetName = text("target_name")
    val eventId = text("event_id")
    val eventType = text("event_type")
    val status = text("status")
    val attemptCount = integer("attempt_count").default(0)
    val httpStatus = integer("http_status").nullable()
    val durationMs = integer("duration_ms").nullable()
    val errorMessage = text("error_message").nullable()
    val createdAtMs = long("created_at_ms")
    val completedAtMs = long("completed_at_ms").nullable()

    override val primaryKey = PrimaryKey(id)
}
