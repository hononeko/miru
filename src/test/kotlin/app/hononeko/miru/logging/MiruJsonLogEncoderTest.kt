package app.hononeko.miru.logging

import app.hononeko.miru.MiruJson
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.event.KeyValuePair

class MiruJsonLogEncoderTest :
    FunSpec({
        val loggerContext = LoggerContext()
        val logger: Logger = loggerContext.getLogger("app.hononeko.miru.TestLogger")

        test("encodes NDJSON with standard fields, typed kv arguments, SLF4J KeyValuePairs, and MDC while protecting reserved keys") {
            val encoder = MiruJsonLogEncoder().apply { start() }
            encoder.headerBytes().shouldBeNull()
            encoder.footerBytes().shouldBeNull()

            val event =
                LoggingEvent(
                    Logger.FQCN,
                    logger,
                    Level.INFO,
                    "Stream event {} {} {} {} {} {}",
                    null,
                    arrayOf(
                        kv("event", "stream_terminated"),
                        kv("count", 42),
                        kv("durationMs", 9_000_000_000L),
                        kv("active", true),
                        kv("ratio", 0.75),
                        kv("skipped", null),
                        kv("level", "SHOULD_NOT_OVERRIDE"),
                    ),
                )
            event.timeStamp = 1_700_000_000_000L
            event.threadName = "worker-1"
            event.setMDCPropertyMap(mapOf("requestId" to "req-123", "message" to "ignored"))
            event.addKeyValuePair(KeyValuePair("nanVal", Double.NaN))
            event.addKeyValuePair(KeyValuePair("floatVal", 1.5f))

            val raw = String(encoder.encode(event), Charsets.UTF_8)
            raw shouldEndWith "\n"

            val json = MiruJson.parseToJsonElement(raw.trim()).jsonObject
            json["@timestamp"]?.jsonPrimitive?.content shouldBe "2023-11-14T22:13:20Z"
            json["level"]?.jsonPrimitive?.content shouldBe "INFO"
            json["logger_name"]?.jsonPrimitive?.content shouldBe "app.hononeko.miru.TestLogger"
            json["thread_name"]?.jsonPrimitive?.content shouldBe "worker-1"
            json["message"]?.jsonPrimitive?.content shouldContain "event=stream_terminated"
            json["event"]?.jsonPrimitive?.content shouldBe "stream_terminated"
            json["count"]?.jsonPrimitive?.int shouldBe 42
            json["durationMs"]?.jsonPrimitive?.long shouldBe 9_000_000_000L
            json["active"]?.jsonPrimitive?.boolean shouldBe true
            json["ratio"]?.jsonPrimitive?.double shouldBe 0.75
            json["skipped"].shouldBeNull()
            json["requestId"]?.jsonPrimitive?.content shouldBe "req-123"
            json["nanVal"]?.jsonPrimitive?.content shouldBe "NaN"
            json["floatVal"]?.jsonPrimitive?.double shouldBe 1.5
            json["stack_trace"].shouldBeNull()
        }

        test("escapes quotes, backslashes, whitespace, and control characters safely") {
            val encoder = MiruJsonLogEncoder().apply { start() }
            val tricky = "a\"b\\c\nd\re\tf\b\u000C\u0001\u001F"
            val event = LoggingEvent(Logger.FQCN, logger, Level.WARN, tricky, null, null)
            event.timeStamp = 0L

            val json = MiruJson.parseToJsonElement(String(encoder.encode(event), Charsets.UTF_8).trim()).jsonObject
            json["message"]?.jsonPrimitive?.content shouldBe tricky
        }

        test("formats root-cause-first and outer-first stack traces with frame depth truncation") {
            val rootCause = IllegalStateException("root failure")
            val wrapper = RuntimeException("outer failure", rootCause)

            val rootFirstEncoder =
                MiruJsonLogEncoder().apply {
                    maxDepthPerThrowable = 2
                    rootCauseFirst = true
                    start()
                }
            val event = LoggingEvent(Logger.FQCN, logger, Level.ERROR, "boom", wrapper, null)
            val rootFirstJson =
                MiruJson.parseToJsonElement(String(rootFirstEncoder.encode(event), Charsets.UTF_8).trim()).jsonObject
            val rootFirstTrace = rootFirstJson["stack_trace"]?.jsonPrimitive?.content.orEmpty()
            rootFirstTrace shouldContain "java.lang.IllegalStateException: root failure"
            rootFirstTrace shouldContain "Wrapped by: java.lang.RuntimeException: outer failure"
            rootFirstTrace shouldContain "frames truncated"

            val outerFirstEncoder =
                MiruJsonLogEncoder().apply {
                    maxDepthPerThrowable = 2
                    rootCauseFirst = false
                    start()
                }
            val outerFirstJson =
                MiruJson.parseToJsonElement(String(outerFirstEncoder.encode(event), Charsets.UTF_8).trim()).jsonObject
            val outerFirstTrace = outerFirstJson["stack_trace"]?.jsonPrimitive?.content.orEmpty()
            outerFirstTrace shouldContain "java.lang.RuntimeException: outer failure"
            outerFirstTrace shouldContain "Caused by: java.lang.IllegalStateException: root failure"
        }
    })
