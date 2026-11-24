package app.hononeko.miru.service

import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.CreateWebhookTargetRequest
import app.hononeko.miru.http.UpdateWebhookTargetRequest
import app.hononeko.miru.http.WebhookDeliveriesResponse
import app.hononeko.miru.http.WebhookDeliveryDto
import app.hononeko.miru.http.WebhookTargetDto
import app.hononeko.miru.http.WebhookTargetsResponse
import app.hononeko.miru.logging.kv
import app.hononeko.miru.persistence.model.WebhookDeliveryFilter
import app.hononeko.miru.persistence.model.WebhookDeliveryRecord
import app.hononeko.miru.persistence.model.WebhookEventType
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.persistence.repository.WebhookDeliveryRepository
import app.hononeko.miru.persistence.repository.WebhookTargetRepository
import app.hononeko.miru.webhook.WebhookDispatcher
import app.hononeko.miru.webhook.WebhookEventEvaluator
import app.hononeko.miru.webhook.WebhookServerPayload
import app.hononeko.miru.webhook.WebhookUrlSafety
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.slf4j.LoggerFactory
import java.util.UUID

private const val MAX_TARGET_NAME_LENGTH = 100
private const val MAX_SIGNING_SECRET_LENGTH = 512
private const val MAX_AUTH_HEADER_VALUE_LENGTH = 2048
private const val ASCII_SPACE = 0x20
private const val ASCII_DEL = 0x7F

/**
 * Manages outbound webhook targets (CRUD, validation, encryption at rest), synthetic test deliveries,
 * and paginated delivery logs.
 */
@Suppress("LongParameterList")
class WebhookService(
    private val targetRepository: WebhookTargetRepository,
    private val deliveryRepository: WebhookDeliveryRepository,
    private val dispatcher: WebhookDispatcher,
    private val evaluator: WebhookEventEvaluator,
    private val encryptionKey: String?,
    private val serverInfoProvider: suspend () -> WebhookServerPayload? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val logger = LoggerFactory.getLogger(WebhookService::class.java)

    suspend fun listTargets(principal: MiruPrincipal): Either<MiruError, WebhookTargetsResponse> =
        either {
            principal.requireAdmin().bind()
            val targets = targetRepository.findAll().bind()
            WebhookTargetsResponse(
                targets = targets.map { it.toDto() },
                encryptionKeyConfigured = CredentialCrypto.isValidKey(encryptionKey),
            )
        }

    suspend fun createTarget(
        principal: MiruPrincipal,
        request: CreateWebhookTargetRequest,
    ): Either<MiruError, WebhookTargetDto> =
        either {
            principal.requireAdmin().bind()
            val name = validateName(request.name).bind()
            val url = WebhookUrlSafety.validateUrl(request.url).bind()
            val events = validateSubscribedEvents(request.subscribedEvents).bind()
            validateThresholds(
                cooldownSeconds = request.cooldownSeconds,
                concurrentStreamThreshold = request.concurrentStreamThreshold,
                bufferingSpikeThreshold = request.bufferingSpikeThreshold,
                bufferingWindowSeconds = request.bufferingWindowSeconds,
            ).bind()

            val encryptedSecret = encryptOptionalSigningSecret(request.signingSecret).bind()
            val (headerName, encryptedHeaderValue) =
                resolveCreateAuthHeader(request.authHeaderName, request.authHeaderValue).bind()

            val nowMs = clock()
            val record =
                WebhookTargetRecord(
                    id = idGenerator(),
                    name = name,
                    url = url,
                    enabled = request.enabled,
                    subscribedEvents = events,
                    encryptedSigningSecret = encryptedSecret,
                    authHeaderName = headerName,
                    encryptedAuthHeaderValue = encryptedHeaderValue,
                    cooldownSeconds = request.cooldownSeconds,
                    concurrentStreamThreshold = request.concurrentStreamThreshold,
                    bufferingSpikeThreshold = request.bufferingSpikeThreshold,
                    bufferingWindowSeconds = request.bufferingWindowSeconds,
                    transcode4kOnly = request.transcode4kOnly,
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs,
                )
            val saved = targetRepository.insert(record).bind()
            logger.info(
                "Created outbound webhook target {} {} {} {}",
                kv("event", "webhook_target_created"),
                kv("actor", principal.username),
                kv("targetId", saved.id),
                kv("url", WebhookUrlSafety.redactUrl(saved.url)),
            )
            saved.toDto()
        }

    suspend fun updateTarget(
        principal: MiruPrincipal,
        id: String,
        request: UpdateWebhookTargetRequest,
    ): Either<MiruError, WebhookTargetDto> =
        either {
            principal.requireAdmin().bind()
            val existing =
                targetRepository.findById(id).bind()
                    ?: raise(MiruError.NotFound(resource = "Webhook target", id = id))

            val name = request.name?.let { validateName(it).bind() } ?: existing.name
            val url = request.url?.let { WebhookUrlSafety.validateUrl(it).bind() } ?: existing.url
            val events = request.subscribedEvents?.let { validateSubscribedEvents(it).bind() } ?: existing.subscribedEvents
            val cooldownSeconds = request.cooldownSeconds ?: existing.cooldownSeconds
            val concurrentStreamThreshold = request.concurrentStreamThreshold ?: existing.concurrentStreamThreshold
            val bufferingSpikeThreshold = request.bufferingSpikeThreshold ?: existing.bufferingSpikeThreshold
            val bufferingWindowSeconds = request.bufferingWindowSeconds ?: existing.bufferingWindowSeconds
            validateThresholds(
                cooldownSeconds = cooldownSeconds,
                concurrentStreamThreshold = concurrentStreamThreshold,
                bufferingSpikeThreshold = bufferingSpikeThreshold,
                bufferingWindowSeconds = bufferingWindowSeconds,
            ).bind()

            val encryptedSecret = resolveUpdatedSigningSecret(existing, request).bind()
            val (headerName, encryptedHeaderValue) = resolveUpdatedAuthHeader(existing, request).bind()

            val updated =
                existing.copy(
                    name = name,
                    url = url,
                    enabled = request.enabled ?: existing.enabled,
                    subscribedEvents = events,
                    encryptedSigningSecret = encryptedSecret,
                    authHeaderName = headerName,
                    encryptedAuthHeaderValue = encryptedHeaderValue,
                    cooldownSeconds = cooldownSeconds,
                    concurrentStreamThreshold = concurrentStreamThreshold,
                    bufferingSpikeThreshold = bufferingSpikeThreshold,
                    bufferingWindowSeconds = bufferingWindowSeconds,
                    transcode4kOnly = request.transcode4kOnly ?: existing.transcode4kOnly,
                    updatedAtMs = clock(),
                )
            val changed = targetRepository.update(updated).bind()
            ensure(changed) { MiruError.NotFound(resource = "Webhook target", id = id) }

            logger.info(
                "Updated outbound webhook target {} {} {} {}",
                kv("event", "webhook_target_updated"),
                kv("actor", principal.username),
                kv("targetId", updated.id),
                kv("url", WebhookUrlSafety.redactUrl(updated.url)),
            )
            updated.toDto()
        }

    suspend fun deleteTarget(
        principal: MiruPrincipal,
        id: String,
    ): Either<MiruError, Unit> =
        either {
            principal.requireAdmin().bind()
            val deleted = targetRepository.deleteById(id).bind()
            ensure(deleted) { MiruError.NotFound(resource = "Webhook target", id = id) }
            logger.info(
                "Deleted outbound webhook target {} {} {}",
                kv("event", "webhook_target_deleted"),
                kv("actor", principal.username),
                kv("targetId", id),
            )
        }

    suspend fun testTarget(
        principal: MiruPrincipal,
        id: String,
    ): Either<MiruError, WebhookDeliveryDto> =
        either {
            principal.requireAdmin().bind()
            val target =
                targetRepository.findById(id).bind()
                    ?: raise(MiruError.NotFound(resource = "Webhook target", id = id))
            val payload = evaluator.buildTestPayload(server = serverInfoProvider(), nowMs = clock())
            val delivery = dispatcher.dispatchTest(target = target, payload = payload).bind()
            logger.info(
                "Dispatched test webhook {} {} {} {}",
                kv("event", "webhook_test_dispatched"),
                kv("actor", principal.username),
                kv("targetId", target.id),
                kv("status", delivery.status.name),
            )
            delivery.toDto()
        }

    suspend fun listDeliveries(
        principal: MiruPrincipal,
        filter: WebhookDeliveryFilter,
    ): Either<MiruError, WebhookDeliveriesResponse> =
        either {
            principal.requireAdmin().bind()
            ensure(filter.limit in 1..WebhookDeliveryFilter.MAX_LIMIT) {
                MiruError.ValidationError(
                    message = "Invalid delivery log limit",
                    fieldErrors = mapOf("limit" to "must be between 1 and ${WebhookDeliveryFilter.MAX_LIMIT}"),
                )
            }
            ensure(filter.offset >= 0) {
                MiruError.ValidationError(
                    message = "Invalid delivery log offset",
                    fieldErrors = mapOf("offset" to "must be >= 0"),
                )
            }
            val page = deliveryRepository.query(filter).bind()
            WebhookDeliveriesResponse(
                items = page.items.map { it.toDto() },
                totalCount = page.totalCount,
                limit = page.limit,
                offset = page.offset,
            )
        }

    private fun validateName(raw: String): Either<MiruError.ValidationError, String> =
        either {
            val trimmed = raw.trim()
            ensure(
                trimmed.isNotEmpty() &&
                    trimmed.length <= MAX_TARGET_NAME_LENGTH &&
                    raw.none { it.code < ASCII_SPACE || it.code == ASCII_DEL },
            ) {
                MiruError.ValidationError(
                    message = "Invalid webhook target name",
                    fieldErrors = mapOf("name" to "must be 1..$MAX_TARGET_NAME_LENGTH characters without control characters"),
                )
            }
            trimmed
        }

    private fun validateSubscribedEvents(rawEvents: List<String>): Either<MiruError.ValidationError, List<WebhookEventType>> =
        either {
            ensure(rawEvents.isNotEmpty()) {
                MiruError.ValidationError(
                    message = "At least one webhook event must be subscribed",
                    fieldErrors = mapOf("subscribedEvents" to "must contain at least one event type"),
                )
            }
            val resolved = linkedSetOf<WebhookEventType>()
            val unknown = mutableListOf<String>()
            for (raw in rawEvents) {
                val parsed = WebhookEventType.fromKey(raw)
                if (parsed == null) {
                    unknown += raw
                } else {
                    resolved += parsed
                }
            }
            ensure(unknown.isEmpty()) {
                MiruError.ValidationError(
                    message = "Unknown webhook event type(s): ${unknown.joinToString()}",
                    fieldErrors = mapOf("subscribedEvents" to "must only contain: ${WebhookEventType.allKeys.joinToString()}"),
                )
            }
            WebhookEventType.entries.filter { it in resolved }
        }

    private fun validateThresholds(
        cooldownSeconds: Int,
        concurrentStreamThreshold: Int,
        bufferingSpikeThreshold: Int,
        bufferingWindowSeconds: Int,
    ): Either<MiruError.ValidationError, Unit> =
        either {
            val fieldErrors = linkedMapOf<String, String>()
            if (cooldownSeconds !in WebhookTargetRecord.MIN_COOLDOWN_SECONDS..WebhookTargetRecord.MAX_COOLDOWN_SECONDS) {
                fieldErrors["cooldownSeconds"] =
                    "must be between ${WebhookTargetRecord.MIN_COOLDOWN_SECONDS} and ${WebhookTargetRecord.MAX_COOLDOWN_SECONDS}"
            }
            if (
                concurrentStreamThreshold !in
                WebhookTargetRecord.MIN_CONCURRENT_STREAM_THRESHOLD..WebhookTargetRecord.MAX_CONCURRENT_STREAM_THRESHOLD
            ) {
                fieldErrors["concurrentStreamThreshold"] =
                    "must be between ${WebhookTargetRecord.MIN_CONCURRENT_STREAM_THRESHOLD} " +
                    "and ${WebhookTargetRecord.MAX_CONCURRENT_STREAM_THRESHOLD}"
            }
            if (
                bufferingSpikeThreshold !in
                WebhookTargetRecord.MIN_BUFFERING_SPIKE_THRESHOLD..WebhookTargetRecord.MAX_BUFFERING_SPIKE_THRESHOLD
            ) {
                fieldErrors["bufferingSpikeThreshold"] =
                    "must be between ${WebhookTargetRecord.MIN_BUFFERING_SPIKE_THRESHOLD} " +
                    "and ${WebhookTargetRecord.MAX_BUFFERING_SPIKE_THRESHOLD}"
            }
            if (
                bufferingWindowSeconds !in
                WebhookTargetRecord.MIN_BUFFERING_WINDOW_SECONDS..WebhookTargetRecord.MAX_BUFFERING_WINDOW_SECONDS
            ) {
                fieldErrors["bufferingWindowSeconds"] =
                    "must be between ${WebhookTargetRecord.MIN_BUFFERING_WINDOW_SECONDS} " +
                    "and ${WebhookTargetRecord.MAX_BUFFERING_WINDOW_SECONDS}"
            }
            ensure(fieldErrors.isEmpty()) {
                MiruError.ValidationError(
                    message = "Invalid webhook threshold configuration",
                    fieldErrors = fieldErrors,
                )
            }
        }

    private fun encryptOptionalSigningSecret(rawSecret: String?): Either<MiruError, String?> =
        either {
            val secret = rawSecret?.takeIf { it.isNotEmpty() } ?: return@either null
            ensure(secret.isNotBlank() && secret.length <= MAX_SIGNING_SECRET_LENGTH && secret.none(::isControlChar)) {
                MiruError.ValidationError(
                    message = "Invalid signing secret",
                    fieldErrors =
                        mapOf("signingSecret" to "must be 1..$MAX_SIGNING_SECRET_LENGTH characters without control characters"),
                )
            }
            encryptSecretField(secret, fieldName = "signingSecret").bind()
        }

    private fun resolveCreateAuthHeader(
        rawName: String?,
        rawValue: String?,
    ): Either<MiruError, Pair<String?, String?>> =
        either {
            val cleanName = rawName?.trim()?.takeIf { it.isNotEmpty() }
            val cleanValue = rawValue?.takeIf { it.isNotEmpty() }
            if (cleanName == null && cleanValue == null) {
                return@either null to null
            }
            ensure(cleanName != null) {
                MiruError.ValidationError(
                    message = "Auth header name is required when auth header value is set",
                    fieldErrors = mapOf("authHeaderName" to "is required when authHeaderValue is provided"),
                )
            }
            ensure(cleanValue != null && cleanValue.isNotBlank()) {
                MiruError.ValidationError(
                    message = "Auth header value is required when auth header name is set",
                    fieldErrors = mapOf("authHeaderValue" to "is required when authHeaderName is provided"),
                )
            }
            val validName = WebhookUrlSafety.validateAuthHeaderName(cleanName).bind()
            val validValue = validateAuthHeaderValue(cleanValue).bind()
            val encryptedValue = encryptSecretField(validValue, fieldName = "authHeaderValue").bind()
            validName to encryptedValue
        }

    private fun resolveUpdatedSigningSecret(
        existing: WebhookTargetRecord,
        request: UpdateWebhookTargetRequest,
    ): Either<MiruError, String?> =
        either {
            val newSecret = request.signingSecret?.takeIf { it.isNotEmpty() }
            ensure(!(request.clearSigningSecret && newSecret != null)) {
                MiruError.ValidationError(
                    message = "Cannot both set and clear signingSecret",
                    fieldErrors = mapOf("signingSecret" to "cannot be provided when clearSigningSecret is true"),
                )
            }
            when {
                request.clearSigningSecret -> null
                newSecret != null -> encryptOptionalSigningSecret(newSecret).bind()
                else -> existing.encryptedSigningSecret
            }
        }

    private fun resolveUpdatedAuthHeader(
        existing: WebhookTargetRecord,
        request: UpdateWebhookTargetRequest,
    ): Either<MiruError, Pair<String?, String?>> =
        either {
            val requestedName = request.authHeaderName?.trim()?.takeIf { it.isNotEmpty() }
            val requestedValue = request.authHeaderValue?.takeIf { it.isNotEmpty() }
            ensure(!(request.clearAuthHeader && (requestedName != null || requestedValue != null))) {
                MiruError.ValidationError(
                    message = "Cannot both set and clear auth header",
                    fieldErrors = mapOf("authHeaderName" to "cannot be provided when clearAuthHeader is true"),
                )
            }
            if (request.clearAuthHeader) {
                return@either null to null
            }
            if (requestedName == null && requestedValue == null) {
                return@either existing.authHeaderName to existing.encryptedAuthHeaderValue
            }
            val effectiveName =
                if (requestedName != null) {
                    WebhookUrlSafety.validateAuthHeaderName(requestedName).bind()
                } else {
                    existing.authHeaderName
                        ?: raise(
                            MiruError.ValidationError(
                                message = "Auth header name is required when auth header value is set",
                                fieldErrors = mapOf("authHeaderName" to "is required when authHeaderValue is provided"),
                            ),
                        )
                }
            val effectiveEncryptedValue =
                if (requestedValue != null) {
                    val validValue = validateAuthHeaderValue(requestedValue).bind()
                    encryptSecretField(validValue, fieldName = "authHeaderValue").bind()
                } else {
                    existing.encryptedAuthHeaderValue
                        ?: raise(
                            MiruError.ValidationError(
                                message = "Auth header value is required when configuring a new auth header",
                                fieldErrors = mapOf("authHeaderValue" to "is required when authHeaderName is set"),
                            ),
                        )
                }
            effectiveName to effectiveEncryptedValue
        }

    private fun validateAuthHeaderValue(rawValue: String): Either<MiruError.ValidationError, String> =
        either {
            ensure(
                rawValue.isNotBlank() &&
                    rawValue.length <= MAX_AUTH_HEADER_VALUE_LENGTH &&
                    rawValue.none(::isControlChar),
            ) {
                MiruError.ValidationError(
                    message = "Invalid auth header value",
                    fieldErrors =
                        mapOf(
                            "authHeaderValue" to
                                "must be 1..$MAX_AUTH_HEADER_VALUE_LENGTH characters without control characters",
                        ),
                )
            }
            rawValue
        }

    private fun encryptSecretField(
        plaintext: String,
        fieldName: String,
    ): Either<MiruError, String> =
        either {
            ensure(CredentialCrypto.isValidKey(encryptionKey)) {
                MiruError.ValidationError(
                    message =
                        "MIRU_ENCRYPTION_KEY (at least ${CredentialCrypto.MIN_KEY_LENGTH} characters) is required " +
                            "to store webhook signing secrets or auth headers",
                    fieldErrors =
                        mapOf(
                            fieldName to
                                "MIRU_ENCRYPTION_KEY (>= ${CredentialCrypto.MIN_KEY_LENGTH} chars) must be configured on the server",
                        ),
                )
            }
            CredentialCrypto.encrypt(plaintext, encryptionKey).bind()
        }

    private fun isControlChar(ch: Char): Boolean = ch.code < ASCII_SPACE || ch.code == ASCII_DEL

    private fun WebhookTargetRecord.toDto(): WebhookTargetDto =
        WebhookTargetDto(
            id = id,
            name = name,
            url = WebhookUrlSafety.redactUrl(url),
            enabled = enabled,
            subscribedEvents = subscribedEvents.map { it.eventKey },
            hasSigningSecret = !encryptedSigningSecret.isNullOrBlank(),
            authHeaderName = authHeaderName,
            hasAuthHeader = !encryptedAuthHeaderValue.isNullOrBlank(),
            cooldownSeconds = cooldownSeconds,
            concurrentStreamThreshold = concurrentStreamThreshold,
            bufferingSpikeThreshold = bufferingSpikeThreshold,
            bufferingWindowSeconds = bufferingWindowSeconds,
            transcode4kOnly = transcode4kOnly,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
        )

    private fun WebhookDeliveryRecord.toDto(): WebhookDeliveryDto =
        WebhookDeliveryDto(
            id = id,
            targetId = targetId,
            targetName = targetName,
            eventId = eventId,
            eventType = eventType,
            status = status,
            attemptCount = attemptCount,
            httpStatus = httpStatus,
            durationMs = durationMs,
            errorMessage = errorMessage,
            createdAtMs = createdAtMs,
            completedAtMs = completedAtMs,
        )
}
