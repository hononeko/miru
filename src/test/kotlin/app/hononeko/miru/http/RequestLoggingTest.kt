package app.hononeko.miru.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RequestLoggingTest :
    FunSpec({
        test("logs passing readiness probes at DEBUG, failing ones and other requests at INFO, and skips /healthz") {
            val ready = AtomicBoolean(true)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val requestLogger = AtomicReference<Logger>()
            val previousLevel = AtomicReference<Level>()

            try {
                testApplication {
                    application {
                        val logger = environment.log as Logger
                        previousLevel.set(logger.level)
                        logger.level = Level.DEBUG
                        logger.addAppender(appender)
                        requestLogger.set(logger)

                        installRequestLogging()
                        routing {
                            get("/readyz") {
                                val status = if (ready.get()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                                call.respondText("{}", status = status)
                            }
                            get("/healthz") { call.respond(HttpStatusCode.OK) }
                            get("/api/v1/ping") { call.respondText("pong") }
                        }
                    }

                    client.get("/readyz")
                    ready.set(false)
                    client.get("/readyz")
                    client.get("/healthz")
                    client.get("/api/v1/ping")
                }
            } finally {
                requestLogger.get()?.let { logger ->
                    logger.detachAppender(appender)
                    logger.level = previousLevel.get()
                }
            }

            val requestLine = Regex("""(.+): GET - (\S+) in \d+ms""")
            val logged =
                appender.list.toList().mapNotNull { event ->
                    requestLine.matchEntire(event.formattedMessage)?.let { match ->
                        Triple(event.level, match.groupValues[1], match.groupValues[2])
                    }
                }

            logged shouldContainExactly
                listOf(
                    Triple(Level.DEBUG, "200 OK", "/readyz"),
                    Triple(Level.INFO, "503 Service Unavailable", "/readyz"),
                    Triple(Level.INFO, "200 OK", "/api/v1/ping"),
                )
        }
    })
