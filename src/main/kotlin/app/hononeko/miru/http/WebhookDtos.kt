package app.hononeko.miru.http

import app.hononeko.miru.persistence.model.WebhookDeliveryStatus
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.webhook.WebhookUrlSafety
import kotlinx.serialization.Serializable

@Serializable
data class WebhookTargetDto(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val subscribedEvents: List<String>,
    val hasSigningSecret: Boolean,
    val authHeaderName: String? = null,
    val hasAuthHeader: Boolean,
    val cooldownSeconds: Int,
    val concurrentStreamThreshold: Int,
    val bufferingSpikeThreshold: Int,
    val bufferingWindowSeconds: Int,
    val transcode4kOnly: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Serializable
data class WebhookTargetsResponse(
    val targets: List<WebhookTargetDto>,
    val encryptionKeyConfigured: Boolean,
)

@Serializable
data class CreateWebhookTargetRequest(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val subscribedEvents: List<String>,
    val signingSecret: String? = null,
    val authHeaderName: String? = null,
    val authHeaderValue: String? = null,
    val cooldownSeconds: Int = WebhookTargetRecord.DEFAULT_COOLDOWN_SECONDS,
    val concurrentStreamThreshold: Int = WebhookTargetRecord.DEFAULT_CONCURRENT_STREAM_THRESHOLD,
    val bufferingSpikeThreshold: Int = WebhookTargetRecord.DEFAULT_BUFFERING_SPIKE_THRESHOLD,
    val bufferingWindowSeconds: Int = WebhookTargetRecord.DEFAULT_BUFFERING_WINDOW_SECONDS,
    val transcode4kOnly: Boolean = false,
) {
    override fun toString(): String =
        "CreateWebhookTargetRequest(name=$name, url=${WebhookUrlSafety.redactUrl(url)}, enabled=$enabled, " +
            "subscribedEvents=$subscribedEvents, signingSecret=${if (signingSecret != null) "***" else "null"}, " +
            "authHeaderName=$authHeaderName, authHeaderValue=${if (authHeaderValue != null) "***" else "null"}, " +
            "cooldownSeconds=$cooldownSeconds, concurrentStreamThreshold=$concurrentStreamThreshold, " +
            "bufferingSpikeThreshold=$bufferingSpikeThreshold, bufferingWindowSeconds=$bufferingWindowSeconds, " +
            "transcode4kOnly=$transcode4kOnly)"
}

@Serializable
data class UpdateWebhookTargetRequest(
    val name: String? = null,
    val url: String? = null,
    val enabled: Boolean? = null,
    val subscribedEvents: List<String>? = null,
    val signingSecret: String? = null,
    val clearSigningSecret: Boolean = false,
    val authHeaderName: String? = null,
    val authHeaderValue: String? = null,
    val clearAuthHeader: Boolean = false,
    val cooldownSeconds: Int? = null,
    val concurrentStreamThreshold: Int? = null,
    val bufferingSpikeThreshold: Int? = null,
    val bufferingWindowSeconds: Int? = null,
    val transcode4kOnly: Boolean? = null,
) {
    override fun toString(): String =
        "UpdateWebhookTargetRequest(name=$name, url=${url?.let { WebhookUrlSafety.redactUrl(it) }}, enabled=$enabled, " +
            "subscribedEvents=$subscribedEvents, signingSecret=${if (signingSecret != null) "***" else "null"}, " +
            "clearSigningSecret=$clearSigningSecret, authHeaderName=$authHeaderName, " +
            "authHeaderValue=${if (authHeaderValue != null) "***" else "null"}, clearAuthHeader=$clearAuthHeader, " +
            "cooldownSeconds=$cooldownSeconds, concurrentStreamThreshold=$concurrentStreamThreshold, " +
            "bufferingSpikeThreshold=$bufferingSpikeThreshold, bufferingWindowSeconds=$bufferingWindowSeconds, " +
            "transcode4kOnly=$transcode4kOnly)"
}

@Serializable
data class WebhookDeliveryDto(
    val id: String,
    val targetId: String,
    val targetName: String,
    val eventId: String,
    val eventType: String,
    val status: WebhookDeliveryStatus,
    val attemptCount: Int,
    val httpStatus: Int? = null,
    val durationMs: Int? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long,
    val completedAtMs: Long? = null,
)

@Serializable
data class WebhookDeliveriesResponse(
    val items: List<WebhookDeliveryDto>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)
