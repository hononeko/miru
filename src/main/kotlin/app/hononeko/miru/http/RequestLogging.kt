package app.hononeko.miru.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

internal fun Application.installRequestLogging() {
    install(CallLogging) {
        disableDefaultColors()
        filter { call -> call.request.path() != "/healthz" }
        format { call -> requestLogLine(call) }
    }
}

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
