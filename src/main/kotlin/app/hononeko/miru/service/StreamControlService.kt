package app.hononeko.miru.service

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.config.GuardrailsConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.GuardrailRuleDto
import app.hononeko.miru.http.GuardrailRulesResponse
import app.hononeko.miru.http.TerminateStreamResponse
import app.hononeko.miru.http.TerminationAuditEntryDto
import app.hononeko.miru.http.TerminationAuditListResponse
import app.hononeko.miru.http.UpdateGuardrailRuleRequest
import app.hononeko.miru.persistence.model.ActiveSessionKey
import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.GuardrailMode
import app.hononeko.miru.persistence.model.GuardrailRuleRecord
import app.hononeko.miru.persistence.model.GuardrailRuleType
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.StreamTerminationAuditRecord
import app.hononeko.miru.persistence.model.TerminationAuditAction
import app.hononeko.miru.persistence.model.TerminationAuditFilter
import app.hononeko.miru.persistence.model.TerminationAuditSource
import app.hononeko.miru.persistence.repository.ActiveSessionRepository
import app.hononeko.miru.persistence.repository.GuardrailRuleRepository
import app.hononeko.miru.persistence.repository.StreamTerminationAuditRepository
import app.hononeko.miru.persistence.repository.UserRepository
import app.hononeko.miru.webhook.StreamTerminatedEvent
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import app.hononeko.miru.logging.kv
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.UUID

private const val MAX_SESSION_ID_LENGTH = 256
private const val MAX_AUDIT_LIMIT = 100
private const val TERMINATED_EVENT_BUFFER_CAPACITY = 64
private const val ASCII_SPACE = 0x20
private const val ASCII_DEL = 0x7F

internal fun formatAuditMediaTitle(media: MediaMetadataRecord): String {
    val baseTitle = media.title.ifBlank { "Unknown" }
    if (media.mediaType.equals("episode", ignoreCase = true) && !media.grandparentTitle.isNullOrBlank()) {
        val seasonCode = media.parentIndex?.let { "S${it.toString().padStart(2, '0')}" }
        val episodeCode = media.mediaIndex?.let { "E${it.toString().padStart(2, '0')}" }
        val code = listOfNotNull(seasonCode, episodeCode).joinToString("")
        return if (code.isNotEmpty()) "${media.grandparentTitle} — $code · $baseTitle" else "${media.grandparentTitle} — $baseTitle"
    }
    return baseTitle
}

/**
 * Coordinates manual admin stream termination, automated guardrail rule configuration/evaluation,
 * and persistent audit logging.
 */
@Suppress("LongParameterList")
class StreamControlService(
    private val adapter: MediaServerAdapter,
    private val activeSessionRepository: ActiveSessionRepository,
    private val userRepository: UserRepository,
    private val guardrailRuleRepository: GuardrailRuleRepository,
    private val auditRepository: StreamTerminationAuditRepository,
    private val guardrailsConfig: GuardrailsConfig = GuardrailsConfig(),
    private val evaluator: StreamGuardrailEvaluator = StreamGuardrailEvaluator(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val logger = LoggerFactory.getLogger(StreamControlService::class.java)
    private val _terminatedEvents =
        MutableSharedFlow<StreamTerminatedEvent>(
            extraBufferCapacity = TERMINATED_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val terminatedEvents: SharedFlow<StreamTerminatedEvent> = _terminatedEvents.asSharedFlow()

    suspend fun terminateStream(
        principal: MiruPrincipal,
        sessionId: String,
        reason: String?,
    ): Either<MiruError, TerminateStreamResponse> =
        either {
            principal.requireAdmin().bind()
            val trimmedSessionId = validateSessionId(sessionId).bind()
            val effectiveReason =
                if (reason == null) {
                    DEFAULT_MANUAL_TERMINATION_REASON
                } else {
                    ensure(reason.none { it.code < ASCII_SPACE || it.code == ASCII_DEL }) {
                        MiruError.ValidationError(
                            message = "Invalid reason",
                            fieldErrors =
                                mapOf(
                                    "reason" to
                                        "must be at most ${GuardrailsConfig.MAX_MESSAGE_LENGTH} " +
                                        "characters without control characters",
                                ),
                        )
                    }
                    reason
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { validateTextField(it, "reason").bind() }
                        ?: DEFAULT_MANUAL_TERMINATION_REASON
                }

            val sessions = activeSessionRepository.findAll().bind()
            val session =
                sessions.firstOrNull { it.sessionId == trimmedSessionId }
                    ?: sessions.firstOrNull { it.sessionKey == trimmedSessionId }
                    ?: raise(MiruError.NotFound(resource = "Stream session", id = trimmedSessionId))

            val outcome = terminateOnServer(session, effectiveReason)
            val targetUsername =
                userRepository
                    .findById(session.userId)
                    .getOrNull()
                    ?.let { user -> user.displayName?.takeIf { it.isNotBlank() } ?: user.username }
            val audit =
                session.toAuditRecord(
                    source = TerminationAuditSource.MANUAL,
                    outcome = outcome,
                    actorName = principal.username,
                    targetUsername = targetUsername,
                    reason = effectiveReason,
                ).copy(actorAccountId = principal.accountId)
            insertAudit(audit)

            outcome
                .onLeft { error ->
                    logger.warn(
                        "Admin stream termination failed {} {} {} {} {}",
                        kv("event", "stream_terminate_failed"),
                        kv("actor", principal.username),
                        kv("sessionId", audit.sessionId),
                        kv("targetUser", targetUsername ?: session.userId),
                        kv("reason", error.message),
                    )
                }.bind()

            logger.info(
                "Admin terminated active stream {} {} {} {} {}",
                kv("event", "stream_terminated"),
                kv("actor", principal.username),
                kv("sessionId", audit.sessionId),
                kv("targetUser", targetUsername ?: session.userId),
                kv("mediaTitle", audit.mediaTitle),
            )

            _terminatedEvents.tryEmit(
                StreamTerminatedEvent(
                    serverId = session.serverId,
                    session = session,
                    reason = effectiveReason,
                    source = TerminationAuditSource.MANUAL,
                    ruleType = null,
                    actorName = principal.username,
                    timestampMs = audit.createdAtMs,
                ),
            )

            TerminateStreamResponse(
                sessionId = audit.sessionId,
                sessionKey = session.sessionKey,
                reason = effectiveReason,
                auditEventId = audit.id,
                terminatedAtMs = audit.createdAtMs,
            )
        }

    suspend fun listGuardrails(principal: MiruPrincipal): Either<MiruError, GuardrailRulesResponse> =
        either {
            principal.requireAdmin().bind()
            val storedByType = guardrailRuleRepository.findAll().bind().associateBy { it.ruleType }
            GuardrailRulesResponse(
                rules = GuardrailRuleType.entries.map { type -> buildRuleDto(type, storedByType[type]) },
            )
        }

    suspend fun updateGuardrail(
        principal: MiruPrincipal,
        ruleTypeRaw: String,
        request: UpdateGuardrailRuleRequest,
    ): Either<MiruError, GuardrailRuleDto> =
        either {
            principal.requireAdmin().bind()
            val ruleType = parseRuleType(ruleTypeRaw).bind()
            val currentStored = guardrailRuleRepository.findByRuleType(ruleType).bind()
            val defaultRule = defaultRuleFor(ruleType)
            val fallbackThreshold = currentStored?.thresholdInt ?: defaultRule.threshold
            val validatedThreshold = validateThreshold(ruleType, request.threshold, fallbackThreshold).bind()
            val effectiveMessage =
                if (request.message == null) {
                    defaultRule.message
                } else {
                    ensure(request.message.none { it.code < ASCII_SPACE || it.code == ASCII_DEL }) {
                        MiruError.ValidationError(
                            message = "Invalid message",
                            fieldErrors =
                                mapOf(
                                    "message" to
                                        "must be at most ${GuardrailsConfig.MAX_MESSAGE_LENGTH} " +
                                        "characters without control characters",
                                ),
                        )
                    }
                    request.message
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { validateTextField(it, "message").bind() }
                        ?: defaultRule.message
                }

            val nowMs = clock()
            val saved =
                guardrailRuleRepository
                    .upsert(
                        GuardrailRuleRecord(
                            ruleType = ruleType,
                            mode = request.mode,
                            thresholdInt = validatedThreshold,
                            message = effectiveMessage,
                            updatedAtMs = nowMs,
                            updatedBy = principal.username,
                        ),
                    ).bind()

            logger.info(
                "Updated stream guardrail {} {} {} {}",
                kv("event", "guardrail_updated"),
                kv("actor", principal.username),
                kv("rule", ruleType.name),
                kv("mode", request.mode.name),
            )

            buildRuleDto(ruleType, saved)
        }

    suspend fun listAuditLog(
        principal: MiruPrincipal,
        filter: TerminationAuditFilter,
    ): Either<MiruError, TerminationAuditListResponse> =
        either {
            principal.requireAdmin().bind()
            ensure(filter.limit in 1..MAX_AUDIT_LIMIT) {
                MiruError.ValidationError(
                    message = "Invalid audit page limit",
                    fieldErrors = mapOf("limit" to "must be between 1 and $MAX_AUDIT_LIMIT"),
                )
            }
            ensure(filter.offset >= 0) {
                MiruError.ValidationError(
                    message = "Invalid audit page offset",
                    fieldErrors = mapOf("offset" to "must be >= 0"),
                )
            }
            val page = auditRepository.query(filter).bind()
            TerminationAuditListResponse(
                items = page.items.map { it.toDto() },
                totalCount = page.totalCount,
                limit = page.limit,
                offset = page.offset,
            )
        }

    suspend fun evaluateActiveSessions(sessions: List<ActiveSessionRecord>) {
        val storedByType =
            guardrailRuleRepository.findAll().fold(
                ifLeft = { error ->
                    logger.warn("Skipping guardrail evaluation; failed loading rules: {}", error.message)
                    return
                },
                ifRight = { rows -> rows.associateBy { it.ruleType } },
            )
        val effectiveRules =
            GuardrailRuleType.entries.associateWith { type ->
                val stored = storedByType[type]
                val defaults = defaultRuleFor(type)
                EffectiveGuardrailRule(
                    ruleType = type,
                    mode = stored?.mode ?: defaults.mode,
                    threshold = stored?.thresholdInt ?: defaults.threshold,
                    message = stored?.message ?: defaults.message,
                )
            }

        val decisions = evaluator.evaluate(sessions, effectiveRules, clock())
        if (decisions.isEmpty()) return

        val userIds = decisions.map { it.session.userId }.distinct()
        val usersById =
            userRepository
                .findByIds(userIds)
                .fold(ifLeft = { emptyMap() }, ifRight = { list -> list.associateBy { it.id } })

        for (decision in decisions) {
            val targetUser = usersById[decision.session.userId]
            val targetName = targetUser?.displayName?.takeIf { it.isNotBlank() } ?: targetUser?.username
            executeGuardrailDecision(decision, targetName)
        }
    }

    suspend fun onSessionStopped(key: ActiveSessionKey) {
        evaluator.onSessionStopped(key)
    }

    private suspend fun executeGuardrailDecision(
        decision: GuardrailDecision,
        targetUsername: String?,
    ) {
        val session = decision.session
        val outcome =
            if (decision.mode == GuardrailMode.ENFORCE) terminateOnServer(session, decision.reason) else null
        val audit =
            session.toAuditRecord(
                source = TerminationAuditSource.GUARDRAIL,
                outcome = outcome,
                actorName = guardrailActorName(decision.ruleType),
                targetUsername = targetUsername,
                reason = decision.reason,
            ).copy(ruleType = decision.ruleType)

        when (outcome) {
            null ->
                logger.warn(
                    "Guardrail dry-run warning {} {} {} {} {}",
                    kv("event", "guardrail_warn"),
                    kv("rule", decision.ruleType.name),
                    kv("sessionId", audit.sessionId),
                    kv("targetUser", targetUsername ?: session.userId),
                    kv("mediaTitle", audit.mediaTitle),
                )

            is Either.Left ->
                logger.warn(
                    "Guardrail stream termination failed {} {} {} {} {}",
                    kv("event", "guardrail_terminate_failed"),
                    kv("rule", decision.ruleType.name),
                    kv("sessionId", audit.sessionId),
                    kv("targetUser", targetUsername ?: session.userId),
                    kv("reason", outcome.value.message),
                )

            is Either.Right ->
                logger.info(
                    "Guardrail terminated active stream {} {} {} {} {}",
                    kv("event", "guardrail_terminated"),
                    kv("rule", decision.ruleType.name),
                    kv("sessionId", audit.sessionId),
                    kv("targetUser", targetUsername ?: session.userId),
                    kv("mediaTitle", audit.mediaTitle),
                )
        }

        insertAudit(audit)

        if (audit.action == TerminationAuditAction.TERMINATED) {
            _terminatedEvents.tryEmit(
                StreamTerminatedEvent(
                    serverId = session.serverId,
                    session = session,
                    reason = decision.reason,
                    source = TerminationAuditSource.GUARDRAIL,
                    ruleType = decision.ruleType,
                    actorName = audit.actorName,
                    timestampMs = audit.createdAtMs,
                ),
            )
        }
    }

    /** Asks the media server to terminate [session], which takes only its own session ID for that. */
    private suspend fun terminateOnServer(
        session: ActiveSessionRecord,
        reason: String,
    ): Either<MiruError, Unit> =
        either {
            val sessionId =
                ensureNotNull(session.sessionId?.takeIf { it.isNotBlank() }) {
                    MiruError.Conflict("Plex has not reported a session ID for this stream, so it cannot be stopped")
                }
            adapter.terminateSession(sessionId, reason).bind()
        }

    /** [outcome] is null for a dry run, which does not ask the media server for anything. */
    private fun ActiveSessionRecord.toAuditRecord(
        source: TerminationAuditSource,
        outcome: Either<MiruError, Unit>?,
        actorName: String,
        targetUsername: String?,
        reason: String,
    ): StreamTerminationAuditRecord =
        StreamTerminationAuditRecord(
            id = idGenerator(),
            createdAtMs = clock(),
            serverId = serverId,
            sessionId = sessionId?.takeIf { it.isNotBlank() } ?: sessionKey,
            sessionKey = sessionKey,
            source = source,
            action =
                when (outcome) {
                    null -> TerminationAuditAction.WARNED
                    is Either.Left -> TerminationAuditAction.TERMINATE_FAILED
                    is Either.Right -> TerminationAuditAction.TERMINATED
                },
            actorName = actorName,
            targetUserId = userId,
            targetUsername = targetUsername,
            mediaRatingKey = media.ratingKey,
            mediaTitle = formatAuditMediaTitle(media),
            streamDecision = streamTech.streamDecision,
            videoResolution = streamTech.videoResolution,
            reason = reason,
            errorDetail = outcome?.leftOrNull()?.message,
        )

    /** Never fails the caller: by now the media server has already been asked, and its answer is what counts. */
    private suspend fun insertAudit(record: StreamTerminationAuditRecord) {
        auditRepository.insert(record).onLeft { error ->
            logger.error(
                "Failed to persist stream termination audit entry {} {} {} {}",
                kv("event", "termination_audit_failed"),
                kv("action", record.action.name),
                kv("sessionId", record.sessionId),
                kv("reason", error.message),
            )
        }
    }

    private fun buildRuleDto(
        ruleType: GuardrailRuleType,
        stored: GuardrailRuleRecord?,
    ): GuardrailRuleDto {
        val defaults = defaultRuleFor(ruleType)
        val thresholdUnit =
            when (ruleType) {
                GuardrailRuleType.TRANSCODE_4K -> null
                GuardrailRuleType.PAUSED_TIMEOUT -> "minutes"
                GuardrailRuleType.CONCURRENT_STREAMS -> "streams"
            }
        return GuardrailRuleDto(
            ruleType = ruleType,
            mode = stored?.mode ?: defaults.mode,
            threshold = stored?.thresholdInt ?: defaults.threshold,
            thresholdUnit = thresholdUnit,
            message = stored?.message ?: defaults.message,
            defaultMessage = defaults.message,
            isOverridden = stored != null,
            updatedAtMs = stored?.updatedAtMs,
            updatedBy = stored?.updatedBy,
        )
    }

    private fun defaultRuleFor(ruleType: GuardrailRuleType): EffectiveGuardrailRule =
        when (ruleType) {
            GuardrailRuleType.TRANSCODE_4K ->
                EffectiveGuardrailRule(
                    ruleType = ruleType,
                    mode = guardrailsConfig.transcode4kMode,
                    threshold = null,
                    message = guardrailsConfig.transcode4kMessage,
                )

            GuardrailRuleType.PAUSED_TIMEOUT ->
                EffectiveGuardrailRule(
                    ruleType = ruleType,
                    mode = guardrailsConfig.pausedTimeoutMode,
                    threshold = guardrailsConfig.pausedTimeoutMinutes,
                    message = guardrailsConfig.pausedTimeoutMessage,
                )

            GuardrailRuleType.CONCURRENT_STREAMS ->
                EffectiveGuardrailRule(
                    ruleType = ruleType,
                    mode = guardrailsConfig.concurrentStreamsMode,
                    threshold = guardrailsConfig.concurrentStreamsMax,
                    message = guardrailsConfig.concurrentStreamsMessage,
                )
        }

    private fun parseRuleType(raw: String): Either<MiruError.ValidationError, GuardrailRuleType> =
        either {
            val trimmed = raw.trim()
            GuardrailRuleType.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: raise(
                    MiruError.ValidationError(
                        message = "Unknown guardrail rule '$raw'",
                        fieldErrors = mapOf("ruleType" to "must be one of ${GuardrailRuleType.entries.joinToString()}"),
                    ),
                )
        }

    private fun validateThreshold(
        ruleType: GuardrailRuleType,
        requested: Int?,
        fallback: Int?,
    ): Either<MiruError.ValidationError, Int?> =
        either {
            when (ruleType) {
                GuardrailRuleType.TRANSCODE_4K -> {
                    ensure(requested == null) {
                        MiruError.ValidationError(
                            message = "Threshold is not applicable for TRANSCODE_4K",
                            fieldErrors = mapOf("threshold" to "must be omitted or null for TRANSCODE_4K"),
                        )
                    }
                    null
                }

                GuardrailRuleType.PAUSED_TIMEOUT -> {
                    val value = requested ?: fallback ?: GuardrailsConfig.DEFAULT_PAUSED_TIMEOUT_MINUTES
                    ensure(
                        value in GuardrailsConfig.MIN_PAUSED_TIMEOUT_MINUTES..GuardrailsConfig.MAX_PAUSED_TIMEOUT_MINUTES,
                    ) {
                        MiruError.ValidationError(
                            message = "Invalid paused timeout threshold",
                            fieldErrors =
                                mapOf(
                                    "threshold" to
                                        "must be between ${GuardrailsConfig.MIN_PAUSED_TIMEOUT_MINUTES} " +
                                        "and ${GuardrailsConfig.MAX_PAUSED_TIMEOUT_MINUTES} minutes",
                                ),
                        )
                    }
                    value
                }

                GuardrailRuleType.CONCURRENT_STREAMS -> {
                    val value = requested ?: fallback ?: GuardrailsConfig.DEFAULT_CONCURRENT_STREAMS_MAX
                    ensure(
                        value in GuardrailsConfig.MIN_CONCURRENT_STREAMS_MAX..GuardrailsConfig.MAX_CONCURRENT_STREAMS_MAX,
                    ) {
                        MiruError.ValidationError(
                            message = "Invalid concurrent streams threshold",
                            fieldErrors =
                                mapOf(
                                    "threshold" to
                                        "must be between ${GuardrailsConfig.MIN_CONCURRENT_STREAMS_MAX} " +
                                        "and ${GuardrailsConfig.MAX_CONCURRENT_STREAMS_MAX} streams",
                                ),
                        )
                    }
                    value
                }
            }
        }

    private fun validateSessionId(raw: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = raw.trim()
            ensure(
                trimmed.isNotEmpty() &&
                    trimmed.length <= MAX_SESSION_ID_LENGTH &&
                    raw.none { it.code < ASCII_SPACE || it.code == ASCII_DEL },
            ) {
                MiruError.ValidationError(
                    message = "Invalid stream session identifier",
                    fieldErrors = mapOf("sessionId" to "must be 1..$MAX_SESSION_ID_LENGTH characters without control characters"),
                )
            }
            trimmed
        }

    private fun validateTextField(
        trimmed: String,
        field: String,
    ): Either<MiruError.ValidationError, String> =
        either {
            ensure(
                trimmed.length <= GuardrailsConfig.MAX_MESSAGE_LENGTH &&
                    trimmed.none { it.code < ASCII_SPACE || it.code == ASCII_DEL },
            ) {
                MiruError.ValidationError(
                    message = "Invalid $field",
                    fieldErrors =
                        mapOf(
                            field to "must be at most ${GuardrailsConfig.MAX_MESSAGE_LENGTH} characters without control characters",
                        ),
                )
            }
            trimmed
        }

    private fun guardrailActorName(ruleType: GuardrailRuleType): String =
        when (ruleType) {
            GuardrailRuleType.TRANSCODE_4K -> "Guardrail: 4K Transcode"
            GuardrailRuleType.PAUSED_TIMEOUT -> "Guardrail: Paused Timeout"
            GuardrailRuleType.CONCURRENT_STREAMS -> "Guardrail: Concurrent Streams"
        }

    private fun StreamTerminationAuditRecord.toDto(): TerminationAuditEntryDto =
        TerminationAuditEntryDto(
            id = id,
            createdAtMs = createdAtMs,
            serverId = serverId,
            sessionId = sessionId,
            sessionKey = sessionKey,
            source = source,
            action = action,
            ruleType = ruleType,
            actorAccountId = actorAccountId,
            actorName = actorName,
            targetUserId = targetUserId,
            targetUsername = targetUsername,
            mediaRatingKey = mediaRatingKey,
            mediaTitle = mediaTitle,
            streamDecision = streamDecision,
            videoResolution = videoResolution,
            reason = reason,
            errorDetail = errorDetail,
        )

    companion object {
        const val DEFAULT_MANUAL_TERMINATION_REASON = "Stream terminated by administrator"
    }
}
