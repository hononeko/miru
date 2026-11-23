package app.hononeko.miru.persistence.model

import kotlinx.serialization.Serializable

@Serializable
enum class GuardrailRuleType {
    TRANSCODE_4K,
    PAUSED_TIMEOUT,
    CONCURRENT_STREAMS,
}

@Serializable
enum class GuardrailMode {
    DISABLED,
    WARN,
    ENFORCE,
}

@Serializable
enum class TerminationAuditSource {
    MANUAL,
    GUARDRAIL,
}

@Serializable
enum class TerminationAuditAction {
    TERMINATED,
    WARNED,
    TERMINATE_FAILED,
}

data class GuardrailRuleRecord(
    val ruleType: GuardrailRuleType,
    val mode: GuardrailMode,
    val thresholdInt: Int? = null,
    val message: String,
    val updatedAtMs: Long,
    val updatedBy: String? = null,
)

data class StreamTerminationAuditRecord(
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

data class TerminationAuditFilter(
    val source: TerminationAuditSource? = null,
    val action: TerminationAuditAction? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    companion object {
        const val DEFAULT_LIMIT = 25
    }
}

data class TerminationAuditPage(
    val items: List<StreamTerminationAuditRecord>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
)
