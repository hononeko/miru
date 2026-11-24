package app.hononeko.miru.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.encoder.EncoderBase
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val DEFAULT_MAX_DEPTH_PER_THROWABLE = 30
private const val MAX_CAUSE_CHAIN_DEPTH = 16
private const val INITIAL_BUFFER_CAPACITY = 256
private const val CONTROL_CHAR_THRESHOLD = 0x20
private const val HEX_RADIX = 16

private val RESERVED_KEYS =
    setOf("@timestamp", "message", "logger_name", "thread_name", "level", "stack_trace")

/**
 * Structured key-value log argument rendered inline as `key=value` in formatted messages and
 * emitted as a top-level JSON field by [MiruJsonLogEncoder].
 */
data class LogField(
    val key: String,
    val value: Any?,
) {
    override fun toString(): String = "$key=$value"
}

fun kv(
    key: String,
    value: Any?,
): LogField = LogField(key = key, value = value)

/**
 * Lightweight zero-dependency Logback NDJSON encoder replacing `logstash-logback-encoder` + Jackson.
 */
class MiruJsonLogEncoder : EncoderBase<ILoggingEvent>() {
    var maxDepthPerThrowable: Int = DEFAULT_MAX_DEPTH_PER_THROWABLE
    var rootCauseFirst: Boolean = true

    override fun headerBytes(): ByteArray? = null

    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val sb = StringBuilder(INITIAL_BUFFER_CAPACITY)
        sb.append('{')
        appendStringField(sb, "@timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.timeStamp)), first = true)
        appendStringField(sb, "message", event.formattedMessage.orEmpty(), first = false)
        appendStringField(sb, "logger_name", event.loggerName.orEmpty(), first = false)
        appendStringField(sb, "thread_name", event.threadName.orEmpty(), first = false)
        appendStringField(sb, "level", event.level?.toString() ?: "INFO", first = false)

        val emittedKeys = HashSet<String>(RESERVED_KEYS)

        val mdc = runCatching { event.mdcPropertyMap }.getOrNull()
        if (!mdc.isNullOrEmpty()) {
            for ((key, value) in mdc) {
                if (key.isNotBlank() && value != null && emittedKeys.add(key)) {
                    appendStringField(sb, key, value, first = false)
                }
            }
        }

        val kvPairs = event.keyValuePairs
        if (!kvPairs.isNullOrEmpty()) {
            for (pair in kvPairs) {
                val key = pair.key
                val value = pair.value ?: continue
                if (!key.isNullOrBlank() && emittedKeys.add(key)) {
                    appendValueField(sb, key, value)
                }
            }
        }

        val args = event.argumentArray
        if (args != null) {
            for (arg in args) {
                if (arg !is LogField) continue
                val value = arg.value ?: continue
                if (arg.key.isNotBlank() && emittedKeys.add(arg.key)) {
                    appendValueField(sb, arg.key, value)
                }
            }
        }

        val throwable = event.throwableProxy
        if (throwable != null) {
            appendStringField(sb, "stack_trace", formatThrowable(throwable), first = false)
        }

        sb.append("}\n")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun formatThrowable(root: IThrowableProxy): String {
        val chain = mutableListOf<IThrowableProxy>()
        val seen = HashSet<IThrowableProxy>()
        var cursor: IThrowableProxy? = root
        while (cursor != null && chain.size < MAX_CAUSE_CHAIN_DEPTH && seen.add(cursor)) {
            chain += cursor
            cursor = cursor.cause
        }

        val ordered = if (rootCauseFirst) chain.asReversed() else chain
        val depthLimit = maxDepthPerThrowable.coerceAtLeast(1)
        val out = StringBuilder(INITIAL_BUFFER_CAPACITY)
        ordered.forEachIndexed { idx, proxy ->
            if (idx > 0) {
                out.append(if (rootCauseFirst) "Wrapped by: " else "Caused by: ")
            }
            out.append(proxy.className)
            val msg = proxy.message
            if (!msg.isNullOrEmpty()) {
                out.append(": ").append(msg)
            }
            out.append('\n')

            val frames = proxy.stackTraceElementProxyArray.orEmpty()
            val commonFrames = proxy.commonFrames.coerceIn(0, frames.size)
            val uniqueCount = frames.size - commonFrames
            val toPrint = minOf(uniqueCount, depthLimit)
            for (i in 0 until toPrint) {
                out.append("\tat ").append(frames[i].steAsString).append('\n')
            }
            val omittedUnique = uniqueCount - toPrint
            if (omittedUnique > 0) {
                out.append("\t... ").append(omittedUnique).append(" frames truncated\n")
            }
            if (commonFrames > 0) {
                out.append("\t... ").append(commonFrames).append(" common frames omitted\n")
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun appendStringField(
        sb: StringBuilder,
        key: String,
        value: String,
        first: Boolean,
    ) {
        if (!first) sb.append(',')
        appendJsonString(sb, key)
        sb.append(':')
        appendJsonString(sb, value)
    }

    private fun appendValueField(
        sb: StringBuilder,
        key: String,
        value: Any,
    ) {
        sb.append(',')
        appendJsonString(sb, key)
        sb.append(':')
        when (value) {
            is Boolean -> sb.append(value)
            is Byte, is Short, is Int, is Long -> sb.append(value)
            is Float -> if (value.isFinite()) sb.append(value) else appendJsonString(sb, value.toString())
            is Double -> if (value.isFinite()) sb.append(value) else appendJsonString(sb, value.toString())
            else -> appendJsonString(sb, value.toString())
        }
    }

    private fun appendJsonString(
        sb: StringBuilder,
        raw: String,
    ) {
        sb.append('"')
        for (i in raw.indices) {
            when (val ch = raw[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (ch.code < CONTROL_CHAR_THRESHOLD) {
                        sb.append("\\u")
                        val hex = ch.code.toString(HEX_RADIX)
                        repeat(4 - hex.length) { sb.append('0') }
                        sb.append(hex)
                    } else {
                        sb.append(ch)
                    }
            }
        }
        sb.append('"')
    }
}
