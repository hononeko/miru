package app.hononeko.miru.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

internal fun Application.installRequestLogging() {
    install(CallLogging) {
        disableDefaultColors()
        filter { call -> call.request.path() != "/healthz" && !call.isPassingReadinessProbe() }
        format { call -> requestLogLine(call) }
    }
    install(ReadinessProbeLogging)
}

/**
 * Logs passing readiness probes at DEBUG on the request log's own logger, since Kubernetes sends one every few seconds.
 * A failing probe is the only trace of an unready dependency, so CallLogging still logs it at INFO.
 */
private val ReadinessProbeLogging =
    createApplicationPlugin("ReadinessProbeLogging") {
        val log = application.environment.log
        on(ResponseSent) { call ->
            if (call.isPassingReadinessProbe() && log.isDebugEnabled) {
                log.debug(requestLogLine(call))
            }
        }
    }

private fun ApplicationCall.isPassingReadinessProbe(): Boolean =
    request.httpMethod == HttpMethod.Get && request.path() == "/readyz" && response.status() == HttpStatusCode.OK

/**
 * Ktor's default line, except that redirect targets lose their query and fragment: those carry the OIDC `state` and
 * `nonce` on login and the ID token on logout. The request's own query string is never logged.
 */
private fun requestLogLine(call: ApplicationCall): String {
    val status = call.response.status()?.toString() ?: "Unhandled"
    val request = "${call.request.httpMethod.value} - ${call.request.path()} in ${call.processingTimeMillis()}ms"
    val target = call.response.headers[HttpHeaders.Location]?.substringBefore('?')?.substringBefore('#')
    return listOfNotNull("$status: $request", target).joinToString(" -> ")
}
