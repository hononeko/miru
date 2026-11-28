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
import io.ktor.server.routing.application
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
    val sessionEventFrames = sessionEvents?.let { SessionEventFrames(it, streamsAndUsersService, application) }
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
                    sessionEventFrames = sessionEventFrames,
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
    sessionEventFrames: SessionEventFrames?,
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
                if (sessionEventFrames == null) {
                    awaitCancellation()
                } else {
                    sessionEventFrames.frames.collect { frame ->
                        // Frames are shared by every client, so this check is what keeps each one to its own streams.
                        if (frame.isVisibleTo(principal, serverId) && !trySendEvent(frame.event)) {
                            pingJob.cancel()
                            eventsJob.cancel()
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
