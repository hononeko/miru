package app.hononeko.miru.http

import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.TerminationAuditAction
import app.hononeko.miru.persistence.model.TerminationAuditSource
import kotlinx.serialization.Serializable

@Serializable
data class TerminateStreamRequest(
    val reason: String? = null,
)

@Serializable
data class TerminateStreamResponse(
    val sessionId: String,
    val sessionKey: String,
    val reason: String,
    val auditEventId: String,
    val terminatedAtMs: Long,
)

@Serializable
data class GuardrailRuleDto(
    val ruleType: GuardrailRuleType,
    val mode: GuardrailMode,
    val threshold: Int? = null,
    val thresholdUnit: String? = null,
    val message: String,
    val defaultMessage: String,
    val isOverridden: Boolean,
    val updatedAtMs: Long? = null,
    val updatedBy: String? = null,
)

@Serializable
data class GuardrailRulesResponse(
    val rules: List<GuardrailRuleDto>,
)

@Serializable
data class UpdateGuardrailRuleRequest(
    val mode: GuardrailMode,
    val threshold: Int? = null,
    val message: String? = null,
)

@Serializable
data class TerminationAuditEntryDto(
    val id: String,
    val createdAtMs: Long,
    val serverId: String? = null,
    val sessionId: String,
    val sessionKey: String? = null,
    val source: TerminationAuditSource,
    val action: TerminationAuditAction,
    val ruleType: GuardrailRuleType? = null,
    val actorAccountId: String? = null,
    val actorName: String,
    val targetUserId: String? = null,
    val targetUsername: String? = null,
    val mediaRatingKey: String? = null,
    val mediaTitle: String,
    val streamDecision: String? = null,
    val videoResolution: String? = null,
    val reason: String,
    val errorDetail: String? = null,
)

@Serializable
data class TerminationAuditListResponse(
    val items: List<TerminationAuditEntryDto>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)
