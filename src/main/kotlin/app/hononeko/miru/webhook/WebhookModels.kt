package app.hononeko.miru.webhook

import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.TerminationAuditSource
import app.hononeko.miru.persistence.model.UserRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WebhookServerPayload(
    val id: String,
    val name: String? = null,
)

@Serializable
data class WebhookUserPayload(
    val id: String,
    val username: String,
    val displayName: String? = null,
)

@Serializable
data class WebhookSessionPayload(
    val sessionKey: String,
    val sessionId: String? = null,
    val state: String,
    val mediaType: String,
    val title: String,
    val grandparentTitle: String? = null,
    val streamDecision: String,
    val videoDecision: String? = null,
    val audioDecision: String? = null,
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val transcodeVideoCodec: String? = null,
    val hwTranscode: Boolean = false,
    val playerName: String? = null,
    val playerPlatform: String? = null,
    val bandwidthKbps: Int? = null,
)

@Serializable
data class WebhookPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val event: String,
    val eventId: String,
    val timestamp: String,
    val server: WebhookServerPayload? = null,
    val user: WebhookUserPayload? = null,
    val session: WebhookSessionPayload? = null,
    val details: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class StreamTerminatedEvent(
    val serverId: String,
    val session: ActiveSessionRecord,
    val reason: String,
    val source: TerminationAuditSource,
    val ruleType: GuardrailRuleType? = null,
    val actorName: String,
    val timestampMs: Long,
)

data class ReconcileHealthSignal(
    val reachable: Boolean,
    val errorMessage: String? = null,
    val timestampMs: Long,
)

internal fun ActiveSessionRecord.toWebhookSessionPayload(): WebhookSessionPayload =
    WebhookSessionPayload(
        sessionKey = sessionKey,
        sessionId = sessionId,
        state = state,
        mediaType = media.mediaType,
        title = media.title,
        grandparentTitle = media.grandparentTitle,
        streamDecision = streamTech.streamDecision,
        videoDecision = streamTech.videoDecision,
        audioDecision = streamTech.audioDecision,
        videoResolution = streamTech.videoResolution,
        videoCodec = streamTech.videoCodec,
        transcodeVideoCodec = streamTech.transcodeVideoCodec,
        hwTranscode = streamTech.hwTranscode,
        playerName = player.playerName,
        playerPlatform = player.playerPlatform,
        bandwidthKbps = streamTech.bandwidthKbps,
    )

internal fun UserRecord.toWebhookUserPayload(): WebhookUserPayload =
    WebhookUserPayload(
        id = id,
        username = username,
        displayName = displayName,
    )
