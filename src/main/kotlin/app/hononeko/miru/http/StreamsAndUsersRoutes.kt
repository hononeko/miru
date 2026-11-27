package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.service.StreamsAndUsersService
import app.hononeko.miru.session.SessionEvent
import arrow.core.raise.either
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("app.hononeko.miru.http.StreamsAndUsersRoutes")
private val DEFAULT_SSE_KEEPALIVE_INTERVAL = 15.seconds

fun Route.streamsAndUsersRoutes(
    authService: AuthService,
    authMode: AuthMode,
    streamsAndUsersService: StreamsAndUsersService,
    sessionEvents: SharedFlow<SessionEvent>? = null,
    keepaliveInterval: Duration = DEFAULT_SSE_KEEPALIVE_INTERVAL,
) {
    authenticated(authService = authService, authMode = authMode) {
        route("/api/v1/streams") {
            get {
                val serverId = call.request.queryParameters["serverId"]
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        streamsAndUsersService.listActiveStreams(principal, serverId = serverId).bind()
                    }
                call.respondEither(result)
            }

            sse("/events") {
                val serverId = call.request.queryParameters["serverId"]?.trim()?.takeIf { it.isNotEmpty() }
                val principal =
                    call.requireMiruPrincipal().fold(
                        ifLeft = { error ->
                            logger.debug("Unauthorized SSE connection attempt: {}", error.message)
                            return@sse
                        },
                        ifRight = { it },
                    )
                streamEventsSse(
                    principal = principal,
                    streamsAndUsersService = streamsAndUsersService,
                    sessionEvents = sessionEvents,
                    keepaliveInterval = keepaliveInterval,
                    serverId = serverId,
                )
            }
        }

        route("/api/v1/users") {
            get("/{id}") {
                val userId = call.parameters["id"].orEmpty()
                val serverId = call.request.queryParameters["serverId"]
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        streamsAndUsersService.getUserDetail(principal, userId, serverId = serverId).bind()
                    }
                call.respondEither(result)
            }
        }
    }

    authenticated(authService = authService, authMode = authMode, requiredRole = MiruRole.ADMIN) {
        route("/api/v1/users") {
            get {
                val serverId = call.request.queryParameters["serverId"]
                val merged = call.request.queryParameters["merged"]?.lowercase() != "false"
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        streamsAndUsersService.listUsers(principal, serverId = serverId, merged = merged).bind()
                    }
                call.respondEither(result)
            }
        }
    }
}

private suspend fun ServerSSESession.streamEventsSse(
    principal: MiruPrincipal,
    streamsAndUsersService: StreamsAndUsersService,
    sessionEvents: SharedFlow<SessionEvent>?,
    keepaliveInterval: Duration,
    serverId: String? = null,
) {
    val snapshot =
        streamsAndUsersService.listActiveStreams(principal, serverId = serverId).fold(
            ifLeft = { error ->
                logger.warn("Failed to build initial stream snapshot for SSE: {}", error.message)
                return
            },
            ifRight = { it },
        )

    if (!trySendEvent(ServerSentEvent(data = MiruJson.encodeToString(snapshot), event = "snapshot"))) {
        return
    }

    coroutineScope {
        lateinit var eventsJob: Job
        lateinit var pingJob: Job

        eventsJob =
            launch {
                if (sessionEvents == null) {
                    awaitCancellation()
                } else {
                    sessionEvents.collect { event ->
                        if (principal.canAccessMediaUser(event.sessionUserId()) &&
                            (serverId == null || event.serverId == serverId)
                        ) {
                            val sseEvent = buildSseEvent(event, streamsAndUsersService)
                            if (sseEvent != null && !trySendEvent(sseEvent)) {
                                pingJob.cancel()
                                eventsJob.cancel()
                            }
                        }
                    }
                }
            }

        pingJob =
            launch {
                while (isActive) {
                    delay(keepaliveInterval)
                    if (!trySendEvent(ServerSentEvent(comments = "ping"))) {
                        eventsJob.cancel()
                        break
                    }
                }
            }

        try {
            eventsJob.join()
        } finally {
            eventsJob.cancel()
            pingJob.cancel()
        }
    }
}

private suspend fun buildSseEvent(
    event: SessionEvent,
    streamsAndUsersService: StreamsAndUsersService,
): ServerSentEvent? {
    val session =
        when (event) {
            is SessionEvent.SessionStarted -> event.session
            is SessionEvent.SessionUpdated -> event.session
            is SessionEvent.SessionStopped -> event.session
        }
    val streamDto =
        streamsAndUsersService.toActiveStreamDto(session).fold(
            ifLeft = { error ->
                logger.warn("Failed to map SSE session event '{}': {}", event.key, error.message)
                return null
            },
            ifRight = { it },
        )
    return when (event) {
        is SessionEvent.SessionStarted ->
            ServerSentEvent(
                event = "session_started",
                data =
                    MiruJson.encodeToString(
                        SessionStartedEventDto(
                            timestampMs = event.timestampMs,
                            stream = streamDto,
                        ),
                    ),
            )

        is SessionEvent.SessionUpdated ->
            ServerSentEvent(
                event = "session_updated",
                data =
                    MiruJson.encodeToString(
                        SessionUpdatedEventDto(
                            timestampMs = event.timestampMs,
                            stream = streamDto,
                            previousState = event.previousState,
                            previousStreamDecision = event.previousStreamDecision,
                            streamDecisionChanged = event.streamDecisionChanged,
                        ),
                    ),
            )

        is SessionEvent.SessionStopped ->
            ServerSentEvent(
                event = "session_stopped",
                data =
                    MiruJson.encodeToString(
                        SessionStoppedEventDto(
                            timestampMs = event.timestampMs,
                            serverId = event.serverId,
                            sessionKey = event.key.sessionKey,
                            ratingKey = event.key.ratingKey,
                            userId = event.session.userId,
                            reason = event.reason.name.lowercase(),
                            mergedIntoHistory = event.mergedIntoHistory,
                            historyId = event.historyRecord?.id,
                            stream = streamDto,
                        ),
                    ),
            )
    }
}

private fun SessionEvent.sessionUserId(): String =
    when (this) {
        is SessionEvent.SessionStarted -> session.userId
        is SessionEvent.SessionUpdated -> session.userId
        is SessionEvent.SessionStopped -> session.userId
    }

private suspend fun ServerSSESession.trySendEvent(event: ServerSentEvent): Boolean =
    try {
        send(event)
        true
    } catch (ex: CancellationException) {
        throw ex
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
