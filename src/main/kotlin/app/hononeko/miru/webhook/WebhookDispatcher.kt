package app.hononeko.miru.webhook

import app.hononeko.miru.MiruJson
import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.artwork.SystemDnsResolver
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.logging.kv
import app.hononeko.miru.persistence.model.WebhookDeliveryRecord
import app.hononeko.miru.persistence.model.WebhookDeliveryStatus
import app.hononeko.miru.persistence.model.WebhookTargetRecord
import app.hononeko.miru.persistence.repository.WebhookDeliveryRepository
import arrow.core.Either
import arrow.core.raise.either
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID

private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
private const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
private const val MAX_RESPONSE_SNIPPET_BYTES = 1_024
private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299
private const val HTTP_REDIRECT_MIN = 300
private const val HTTP_REDIRECT_MAX = 399
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_MIN = 500
private const val HTTP_SERVER_ERROR_MAX = 599
private const val HEADER_EVENT = "X-Miru-Event"
private const val HEADER_DELIVERY = "X-Miru-Delivery"
private const val HEADER_SIGNATURE = "X-Miru-Signature-256"

fun createWebhookHttpClient(
    engine: HttpClientEngine = Java.create(),
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
        }
    }

/**
 * Asynchronous non-blocking outbound webhook dispatcher with bounded queue, concurrency cap,
 * HMAC-SHA256 payload signing, SSRF host resolution validation, exponential backoff retries,
 * and persistent delivery logging.
 */
@Suppress("LongParameterList")
class WebhookDispatcher(
    private val httpClientProvider: () -> HttpClient,
    private val deliveryRepository: WebhookDeliveryRepository,
    private val encryptionKey: String?,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val dnsResolver: DnsResolver = SystemDnsResolver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    maxConcurrentDeliveries: Int = DEFAULT_MAX_CONCURRENT_DELIVERIES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val maxRetainedDeliveries: Int = DEFAULT_MAX_RETAINED_DELIVERIES,
) {
    private data class DispatchJob(
        val target: WebhookTargetRecord,
        val payload: WebhookPayload,
    )

    private data class DecryptedSecrets(
        val signingSecret: String?,
        val authHeaderName: String?,
        val authHeaderValue: String?,
    ) {
        fun allSecrets(): List<String> = listOfNotNull(signingSecret, authHeaderValue)
    }

    private data class AttemptResult(
        val succeeded: Boolean,
        val retryable: Boolean,
        val httpStatus: Int? = null,
        val durationMs: Int? = null,
        val errorMessage: String? = null,
    )

    private val logger = LoggerFactory.getLogger(WebhookDispatcher::class.java)
    private val lock = Any()
    private val semaphore = Semaphore(maxConcurrentDeliveries.coerceAtLeast(1))
    private val queue = Channel<DispatchJob>(capacity = queueCapacity.coerceAtLeast(1))
    private var workerJob: Job? = null

    fun start(): Job =
        synchronized(lock) {
            workerJob?.takeIf { it.isActive }
                ?: scope
                    .launch(CoroutineName("webhook-dispatcher-worker")) {
                        runWorker()
                    }.also { workerJob = it }
        }

    suspend fun stop() {
        val current = synchronized(lock) { workerJob.also { workerJob = null } }
        current?.cancelAndJoin()
    }

    /**
     * Enqueues a webhook delivery without blocking the caller. Returns `false` if the bounded queue is full.
     */
    fun enqueue(
        target: WebhookTargetRecord,
        payload: WebhookPayload,
    ): Boolean {
        val sent = queue.trySend(DispatchJob(target = target, payload = payload)).isSuccess
        if (!sent) {
            logger.warn(
                "Webhook delivery queue full; dropping event {} {} {}",
                kv("targetId", target.id),
                kv("targetName", target.name),
                kv("event", payload.event),
            )
        }
        return sent
    }

    /**
     * Executes a single synchronous test delivery (no retry delay) and persists the delivery log row.
     */
    suspend fun dispatchTest(
        target: WebhookTargetRecord,
        payload: WebhookPayload,
    ): Either<MiruError, WebhookDeliveryRecord> =
        either {
            deliverInternal(target = target, payload = payload, maxAttempts = 1)
        }

    private suspend fun runWorker() {
        while (true) {
            // A slot before a job, so that jobs wait in the bounded queue and not in coroutines
            semaphore.acquire()
            val job =
                try {
                    queue.receive()
                } catch (e: CancellationException) {
                    semaphore.release()
                    throw e
                }
            scope
                .launch(CoroutineName("webhook-delivery")) { deliverLogged(job) }
                .invokeOnCompletion { semaphore.release() }
        }
    }

    private suspend fun deliverLogged(job: DispatchJob) {
        try {
            deliverInternal(
                target = job.target,
                payload = job.payload,
                maxAttempts = retryDelaysMs.size + 1,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Unexpected webhook delivery failure {} {} {}",
                kv("targetId", job.target.id),
                kv("event", job.payload.event),
                kv("reason", WebhookUrlSafety.sanitizeErrorMessage(e.message)),
            )
        }
    }

    internal suspend fun deliverInternal(
        target: WebhookTargetRecord,
        payload: WebhookPayload,
        maxAttempts: Int = retryDelaysMs.size + 1,
    ): WebhookDeliveryRecord {
        val deliveryId = idGenerator()
        val createdAtMs = clock()
        val effectiveMaxAttempts = maxAttempts.coerceAtLeast(1)

        val decrypted =
            decryptTargetSecrets(target).fold(
                ifLeft = { error ->
                    val failed =
                        buildDeliveryRecord(
                            deliveryId = deliveryId,
                            target = target,
                            payload = payload,
                            status = WebhookDeliveryStatus.FAILED,
                            attemptCount = 1,
                            httpStatus = null,
                            durationMs = 0,
                            errorMessage = WebhookUrlSafety.sanitizeErrorMessage(error.message),
                            createdAtMs = createdAtMs,
                            completedAtMs = clock(),
                        )
                    persistFinalDelivery(failed, alreadyInserted = false)
                    return failed
                },
                ifRight = { it },
            )

        val bodyString = MiruJson.encodeToString(payload)
        val signatureHeader = decrypted.signingSecret?.let { WebhookUrlSafety.signPayload(it, bodyString.toByteArray(Charsets.UTF_8)) }

        var inserted = false
        var lastResult = AttemptResult(succeeded = false, retryable = false, errorMessage = "No attempts executed")

        for (attempt in 1..effectiveMaxAttempts) {
            if (attempt > 1) {
                val backoffMs = retryDelaysMs.getOrElse(attempt - 2) { retryDelaysMs.lastOrNull() ?: 0L }
                if (backoffMs > 0L) delayFn(backoffMs)
            }

            lastResult =
                executeSingleAttempt(
                    target = target,
                    deliveryId = deliveryId,
                    eventType = payload.event,
                    bodyString = bodyString,
                    signatureHeader = signatureHeader,
                    decrypted = decrypted,
                )

            if (lastResult.succeeded) {
                val delivered =
                    buildDeliveryRecord(
                        deliveryId = deliveryId,
                        target = target,
                        payload = payload,
                        status = WebhookDeliveryStatus.DELIVERED,
                        attemptCount = attempt,
                        httpStatus = lastResult.httpStatus,
                        durationMs = lastResult.durationMs,
                        errorMessage = null,
                        createdAtMs = createdAtMs,
                        completedAtMs = clock(),
                    )
                persistFinalDelivery(delivered, alreadyInserted = inserted)
                return delivered
            }

            if (lastResult.retryable && attempt < effectiveMaxAttempts) {
                val retrying =
                    buildDeliveryRecord(
                        deliveryId = deliveryId,
                        target = target,
                        payload = payload,
                        status = WebhookDeliveryStatus.RETRYING,
                        attemptCount = attempt,
                        httpStatus = lastResult.httpStatus,
                        durationMs = lastResult.durationMs,
                        errorMessage = lastResult.errorMessage,
                        createdAtMs = createdAtMs,
                        completedAtMs = null,
                    )
                persistRetryingDelivery(retrying, alreadyInserted = inserted)
                inserted = true
            } else {
                val failed =
                    buildDeliveryRecord(
                        deliveryId = deliveryId,
                        target = target,
                        payload = payload,
                        status = WebhookDeliveryStatus.FAILED,
                        attemptCount = attempt,
                        httpStatus = lastResult.httpStatus,
                        durationMs = lastResult.durationMs,
                        errorMessage = lastResult.errorMessage,
                        createdAtMs = createdAtMs,
                        completedAtMs = clock(),
                    )
                persistFinalDelivery(failed, alreadyInserted = inserted)
                return failed
            }
        }

        val fallback =
            buildDeliveryRecord(
                deliveryId = deliveryId,
                target = target,
                payload = payload,
                status = WebhookDeliveryStatus.FAILED,
                attemptCount = effectiveMaxAttempts,
                httpStatus = lastResult.httpStatus,
                durationMs = lastResult.durationMs,
                errorMessage = lastResult.errorMessage,
                createdAtMs = createdAtMs,
                completedAtMs = clock(),
            )
        persistFinalDelivery(fallback, alreadyInserted = inserted)
        return fallback
    }

    @Suppress("LongParameterList")
    private fun buildDeliveryRecord(
        deliveryId: String,
        target: WebhookTargetRecord,
        payload: WebhookPayload,
        status: WebhookDeliveryStatus,
        attemptCount: Int,
        httpStatus: Int?,
        durationMs: Int?,
        errorMessage: String?,
        createdAtMs: Long,
        completedAtMs: Long?,
    ): WebhookDeliveryRecord =
        WebhookDeliveryRecord(
            id = deliveryId,
            targetId = target.id,
            targetName = target.name,
            eventId = payload.eventId,
            eventType = payload.event,
            status = status,
            attemptCount = attemptCount,
            httpStatus = httpStatus,
            durationMs = durationMs,
            errorMessage = errorMessage,
            createdAtMs = createdAtMs,
            completedAtMs = completedAtMs,
        )

    private suspend fun persistRetryingDelivery(
        record: WebhookDeliveryRecord,
        alreadyInserted: Boolean,
    ) {
        val result = if (alreadyInserted) deliveryRepository.update(record) else deliveryRepository.insert(record)
        result.onLeft { err ->
            logger.debug("Failed saving retrying webhook delivery {}: {}", record.id, err.message)
        }
    }

    private suspend fun executeSingleAttempt(
        target: WebhookTargetRecord,
        deliveryId: String,
        eventType: String,
        bodyString: String,
        signatureHeader: String?,
        decrypted: DecryptedSecrets,
    ): AttemptResult {
        val startedMs = clock()
        val secrets = decrypted.allSecrets()

        val uri =
            WebhookUrlSafety.validateUrl(target.url).fold(
                ifLeft = { err ->
                    return AttemptResult(
                        succeeded = false,
                        retryable = false,
                        durationMs = elapsedMs(startedMs),
                        errorMessage = WebhookUrlSafety.sanitizeErrorMessage(err.message, secrets),
                    )
                },
                ifRight = { validUrl -> URI(validUrl) },
            )

        val host = uri.host.orEmpty()
        WebhookUrlSafety.resolveAndValidateHost(host, dnsResolver).fold(
            ifLeft = { err ->
                val retryable = err is MiruError.UpstreamUnavailable
                return AttemptResult(
                    succeeded = false,
                    retryable = retryable,
                    durationMs = elapsedMs(startedMs),
                    errorMessage = WebhookUrlSafety.sanitizeErrorMessage(err.message, secrets),
                )
            },
            ifRight = { /* Host resolved to permitted address(es) */ },
        )

        return try {
            val response =
                httpClientProvider().post(target.url) {
                    contentType(ContentType.Application.Json.withParameter("charset", "utf-8"))
                    header(HttpHeaders.UserAgent, "Miru/$appVersion")
                    header(HEADER_EVENT, eventType)
                    header(HEADER_DELIVERY, deliveryId)
                    if (signatureHeader != null) {
                        header(HEADER_SIGNATURE, signatureHeader)
                    }
                    if (!decrypted.authHeaderName.isNullOrBlank() && !decrypted.authHeaderValue.isNullOrBlank()) {
                        header(decrypted.authHeaderName, decrypted.authHeaderValue)
                    }
                    setBody(bodyString)
                }

            val statusCode = response.status.value
            val durationMs = elapsedMs(startedMs)

            if (statusCode in HTTP_OK_MIN..HTTP_OK_MAX) {
                AttemptResult(
                    succeeded = true,
                    retryable = false,
                    httpStatus = statusCode,
                    durationMs = durationMs,
                )
            } else {
                val bodySnippet = readTruncatedBodySnippet(response)
                val baseMsg =
                    when {
                        statusCode in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX ->
                            "Webhook endpoint returned HTTP $statusCode (redirects are disabled)"

                        bodySnippet.isNotEmpty() ->
                            "HTTP $statusCode: $bodySnippet"

                        else ->
                            "Webhook endpoint returned HTTP $statusCode"
                    }
                AttemptResult(
                    succeeded = false,
                    retryable = isRetryableStatus(statusCode),
                    httpStatus = statusCode,
                    durationMs = durationMs,
                    errorMessage = WebhookUrlSafety.sanitizeErrorMessage(baseMsg, secrets),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Network error"
            AttemptResult(
                succeeded = false,
                retryable = true,
                httpStatus = null,
                durationMs = elapsedMs(startedMs),
                errorMessage = WebhookUrlSafety.sanitizeErrorMessage(message, secrets),
            )
        }
    }

    private fun decryptTargetSecrets(target: WebhookTargetRecord): Either<MiruError, DecryptedSecrets> =
        either {
            val signingSecret =
                target.encryptedSigningSecret?.let { encrypted ->
                    CredentialCrypto.decrypt(encrypted, encryptionKey).bind()
                }
            val authHeaderValue =
                target.encryptedAuthHeaderValue?.let { encrypted ->
                    CredentialCrypto.decrypt(encrypted, encryptionKey).bind()
                }
            DecryptedSecrets(
                signingSecret = signingSecret,
                authHeaderName = target.authHeaderName,
                authHeaderValue = authHeaderValue,
            )
        }

    private suspend fun persistFinalDelivery(
        record: WebhookDeliveryRecord,
        alreadyInserted: Boolean,
    ) {
        val writeResult =
            if (alreadyInserted) {
                deliveryRepository.update(record)
            } else {
                deliveryRepository.insert(record)
            }
        writeResult.onLeft { err ->
            logger.warn(
                "Failed persisting webhook delivery {} {} {}",
                kv("deliveryId", record.id),
                kv("targetId", record.targetId),
                kv("reason", err.message),
            )
        }
        deliveryRepository.pruneOldest(maxRetainedDeliveries).onLeft { err ->
            logger.debug("Failed pruning old webhook deliveries: {}", err.message)
        }
    }

    private suspend fun readTruncatedBodySnippet(response: HttpResponse): String =
        try {
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(MAX_RESPONSE_SNIPPET_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = channel.readAvailable(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            if (total == 0) "" else String(buffer, 0, total, Charsets.UTF_8).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }

    private fun elapsedMs(startedMs: Long): Int =
        (clock() - startedMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode == HTTP_REQUEST_TIMEOUT ||
            statusCode == HTTP_TOO_MANY_REQUESTS ||
            statusCode in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(2_000L, 6_000L)
        const val DEFAULT_MAX_CONCURRENT_DELIVERIES = 4
        const val DEFAULT_QUEUE_CAPACITY = 128
        const val DEFAULT_MAX_RETAINED_DELIVERIES = 1_000
    }
}
