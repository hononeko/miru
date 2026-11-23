package app.hononeko.miru.http

import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.authenticated
import app.hononeko.miru.auth.requireMiruPrincipal
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.service.HistoryAndStatsService
import app.hononeko.miru.service.HistoryQueryParams
import arrow.core.raise.either
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.historyStatsRoutes(
    historyAndStatsService: HistoryAndStatsService,
    authService: AuthService,
    authMode: AuthMode,
) {
    authenticated(authService = authService, authMode = authMode) {
        route("/api/v1") {
            get("/history") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val qp = call.request.queryParameters
                        historyAndStatsService
                            .queryHistory(
                                principal = principal,
                                params =
                                    HistoryQueryParams(
                                        userId = qp["userId"],
                                        mediaType = qp["mediaType"],
                                        libraryId = qp["libraryId"],
                                        decision = qp["decision"],
                                        from = qp["from"],
                                        to = qp["to"],
                                        q = qp["q"],
                                        sortBy = qp["sortBy"],
                                        sortDir = qp["sortDir"],
                                        pageRaw = qp["page"],
                                        pageSizeRaw = qp["pageSize"],
                                    ),
                            ).bind()
                    }
                call.respondEither(result)
            }

            get("/history/{id}") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val id = call.parameters["id"].orEmpty()
                        historyAndStatsService.getHistoryDetail(principal = principal, id = id).bind()
                    }
                call.respondEither(result)
            }

            get("/stats/overview") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val range = call.request.queryParameters["range"]
                        historyAndStatsService.getOverviewStats(principal = principal, rangeRaw = range).bind()
                    }
                call.respondEither(result)
            }

            get("/stats/heatmap") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val qp = call.request.queryParameters
                        historyAndStatsService
                            .getHeatmapStats(
                                principal = principal,
                                rangeRaw = qp["range"],
                                userId = qp["userId"],
                            ).bind()
                    }
                call.respondEither(result)
            }

            get("/stats/clients") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val qp = call.request.queryParameters
                        historyAndStatsService
                            .getClientStats(
                                principal = principal,
                                rangeRaw = qp["range"],
                                userId = qp["userId"],
                            ).bind()
                    }
                call.respondEither(result)
            }

            get("/stats/wrapped/{year}") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val year = call.parameters["year"]
                        val userId = call.request.queryParameters["userId"]
                        historyAndStatsService
                            .getWrappedStats(
                                principal = principal,
                                yearRaw = year,
                                userId = userId,
                            ).bind()
                    }
                call.respondEither(result)
            }

            get("/users/{id}/stats") {
                val result =
                    either {
                        val principal = call.requireMiruPrincipal().bind()
                        val userId = call.parameters["id"].orEmpty()
                        val range = call.request.queryParameters["range"]
                        historyAndStatsService
                            .getUserStats(
                                principal = principal,
                                userId = userId,
                                rangeRaw = range,
                            ).bind()
                    }
                call.respondEither(result)
            }
        }
    }
}
