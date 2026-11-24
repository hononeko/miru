package app.hononeko.miru.persistence.model

import app.hononeko.miru.webhook.WebhookUrlSafety
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WebhookEventType(
    val eventKey: String,
) {
    @SerialName("transcode.started")
    TRANSCODE_STARTED("transcode.started"),

    @SerialName("stream.concurrent_limit")
    STREAM_CONCURRENT_LIMIT("stream.concurrent_limit"),

    @SerialName("stream.buffering_spike")
    STREAM_BUFFERING_SPIKE("stream.buffering_spike"),

    @SerialName("stream.terminated")
    STREAM_TERMINATED("stream.terminated"),

    @SerialName("server.unreachable")
    SERVER_UNREACHABLE("server.unreachable"),

    @SerialName("server.recovered")
    SERVER_RECOVERED("server.recovered"),
    ;

    companion object {
        const val TEST_EVENT_KEY = "webhook.test"

        private val byKey: Map<String, WebhookEventType> = entries.associateBy { it.eventKey }

        fun fromKey(raw: String): WebhookEventType? = byKey[raw.trim().lowercase()]

        val allKeys: List<String> = entries.map { it.eventKey }
    }
}

@Serializable
enum class WebhookDeliveryStatus {
    DELIVERED,
    RETRYING,
    FAILED,
}

data class WebhookTargetRecord(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val subscribedEvents: List<WebhookEventType>,
    val encryptedSigningSecret: String? = null,
    val authHeaderName: String? = null,
    val encryptedAuthHeaderValue: String? = null,
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
    val concurrentStreamThreshold: Int = DEFAULT_CONCURRENT_STREAM_THRESHOLD,
    val bufferingSpikeThreshold: Int = DEFAULT_BUFFERING_SPIKE_THRESHOLD,
    val bufferingWindowSeconds: Int = DEFAULT_BUFFERING_WINDOW_SECONDS,
    val transcode4kOnly: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    override fun toString(): String =
        "WebhookTargetRecord(id=$id, name=$name, url=${WebhookUrlSafety.redactUrl(url)}, enabled=$enabled, " +
            "subscribedEvents=${subscribedEvents.map { it.eventKey }}, " +
            "hasSigningSecret=${encryptedSigningSecret != null}, authHeaderName=$authHeaderName, " +
            "hasAuthHeader=${encryptedAuthHeaderValue != null}, cooldownSeconds=$cooldownSeconds, " +
            "concurrentStreamThreshold=$concurrentStreamThreshold, bufferingSpikeThreshold=$bufferingSpikeThreshold, " +
            "bufferingWindowSeconds=$bufferingWindowSeconds, transcode4kOnly=$transcode4kOnly, " +
            "createdAtMs=$createdAtMs, updatedAtMs=$updatedAtMs)"

    companion object {
        const val DEFAULT_COOLDOWN_SECONDS = 60
        const val MIN_COOLDOWN_SECONDS = 0
        const val MAX_COOLDOWN_SECONDS = 86_400

        const val DEFAULT_CONCURRENT_STREAM_THRESHOLD = 3
        const val MIN_CONCURRENT_STREAM_THRESHOLD = 2
        const val MAX_CONCURRENT_STREAM_THRESHOLD = 50

        const val DEFAULT_BUFFERING_SPIKE_THRESHOLD = 3
        const val MIN_BUFFERING_SPIKE_THRESHOLD = 2
        const val MAX_BUFFERING_SPIKE_THRESHOLD = 20

        const val DEFAULT_BUFFERING_WINDOW_SECONDS = 120
        const val MIN_BUFFERING_WINDOW_SECONDS = 10
        const val MAX_BUFFERING_WINDOW_SECONDS = 3_600
    }
}

data class WebhookDeliveryRecord(
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

data class WebhookDeliveryFilter(
    val targetId: String? = null,
    val eventType: String? = null,
    val status: WebhookDeliveryStatus? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
    }
}

data class WebhookDeliveryPage(
    val items: List<WebhookDeliveryRecord>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)
