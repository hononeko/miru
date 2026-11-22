package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

private const val TEST_PLEX_URL = "http://plex.local:32400"
private const val TEST_PLEX_TV_URL = "https://plex.tv"
private const val TEST_TOKEN = "super-secret-plex-token-xyz"

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private fun MockRequestHandleScope.respondJson(
    json: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(content = json, status = status, headers = jsonHeaders())

private fun assertTokenHeaderAndSafeUrl(request: HttpRequestData) {
    request.headers["X-Plex-Token"] shouldBe TEST_TOKEN
    request.url.toString() shouldNotContain TEST_TOKEN
    request.url.parameters["X-Plex-Token"] shouldBe null
}

private fun adapterWithMock(
    plexTvBaseUrl: String? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<PlexMediaServerAdapter, MutableList<HttpRequestData>> {
    val recorded = mutableListOf<HttpRequestData>()
    val engine =
        MockEngine { request ->
            recorded += request
            assertTokenHeaderAndSafeUrl(request)
            handler(request)
        }
    val client = createPlexHttpClient(engine = engine)
    val adapter =
        PlexMediaServerAdapter(
            config = PlexConfig(baseUrl = TEST_PLEX_URL, token = TEST_TOKEN),
            httpClient = client,
            plexTvBaseUrl = plexTvBaseUrl,
        )
    return adapter to recorded
}

class PlexMediaServerAdapterTest :
    FunSpec({
        test("checkHealth returns ServerIdentity and sends token exclusively via header") {
            val (adapter, requests) =
                adapterWithMock { request ->
                    request.url.encodedPath shouldBe "/"
                    request.headers[HttpHeaders.Accept] shouldBe ContentType.Application.Json.toString()
                    respondJson(PlexFixtures.serverRootJson)
                }

            adapter.serverType shouldBe "plex"
            val identity = adapter.checkHealth().shouldBeRight()
            identity.machineIdentifier shouldBe "abc123machine"
            identity.friendlyName shouldBe "KerrLab Plex"
            identity.version shouldBe "1.41.3.9314-a0bfb8370"
            identity.platform shouldBe "Linux"
            requests shouldHaveSize 1
        }

        test("fetchActiveSessions handles empty, movie, episode HW transcode, music, and Live TV") {
            val payloads =
                listOf(
                    PlexFixtures.emptySessionsJson,
                    PlexFixtures.movieDirectPlayLanJson,
                    PlexFixtures.episodeHwTranscodeWanJson,
                    PlexFixtures.musicTrackJson,
                    PlexFixtures.liveTvMissingFieldsJson,
                )
            var index = 0
            val (adapter, _) =
                adapterWithMock { request ->
                    request.url.encodedPath shouldBe "/status/sessions"
                    respondJson(payloads[index++])
                }

            adapter.fetchActiveSessions().shouldBeRight().shouldBeEmpty()

            val movie = adapter.fetchActiveSessions().shouldBeRight().single()
            movie.media.mediaType shouldBe MediaType.MOVIE
            movie.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            movie.player.location shouldBe StreamLocation.LAN

            val episode = adapter.fetchActiveSessions().shouldBeRight().single()
            episode.media.mediaType shouldBe MediaType.EPISODE
            episode.technical.decision shouldBe StreamDecision.TRANSCODE
            episode.technical.isHwTranscode shouldBe true
            episode.player.location shouldBe StreamLocation.WAN

            val track = adapter.fetchActiveSessions().shouldBeRight().single()
            track.media.mediaType shouldBe MediaType.TRACK
            track.technical.audioCodec shouldBe "flac"

            val live = adapter.fetchActiveSessions().shouldBeRight().single()
            live.media.mediaType shouldBe MediaType.LIVE_TV
            live.media.durationMs shouldBe null
            live.player.location shouldBe StreamLocation.LAN
        }

        test("normalizes local admin User.id == 1 consistently via /accounts and optional plex.tv") {
            val (onlineAdapter, onlineRequests) =
                adapterWithMock(plexTvBaseUrl = TEST_PLEX_TV_URL) { request ->
                    when (request.url.host) {
                        "plex.tv" ->
                            when (request.url.encodedPath) {
                                "/api/v2/user" -> respondJson(PlexFixtures.plexTvUserJson)
                                "/api/v2/friends" -> respondJson(PlexFixtures.plexTvFriendsJson)
                                else -> error("Unexpected plex.tv path: ${request.url}")
                            }

                        else ->
                            when (request.url.encodedPath) {
                                "/accounts" -> respondJson(PlexFixtures.accountsJson)
                                "/status/sessions" -> respondJson(PlexFixtures.localOwnerSessionJson)
                                else -> error("Unexpected PMS path: ${request.url}")
                            }
                    }
                }

            val users = onlineAdapter.fetchUsers().shouldBeRight()
            users shouldHaveSize 3
            val owner = users.first { it.isAdmin }
            owner.externalId shouldBe "99887766"
            owner.username shouldBe "kerrlab"
            owner.email shouldBe "alex@kerrlab.app"

            val session = onlineAdapter.fetchActiveSessions().shouldBeRight().single()
            session.user.externalId shouldBe "99887766"
            session.user.username shouldBe "kerrlab"
            session.user.thumbUrl shouldBe "https://plex.tv/users/owner/avatar"

            // Offline setup (plexTvBaseUrl = null) never calls plex.tv and stays consistent on id "1"
            val (offlineAdapter, offlineRequests) =
                adapterWithMock(plexTvBaseUrl = null) { request ->
                    when (request.url.encodedPath) {
                        "/accounts" -> respondJson(PlexFixtures.accountsJson)
                        "/status/sessions" -> respondJson(PlexFixtures.localOwnerSessionJson)
                        else -> error("Unexpected path: ${request.url}")
                    }
                }

            val offlineUsers = offlineAdapter.fetchUsers().shouldBeRight()
            offlineUsers.first { it.isAdmin }.externalId shouldBe "1"
            val offlineSession = offlineAdapter.fetchActiveSessions().shouldBeRight().single()
            offlineSession.user.externalId shouldBe "1"
            offlineSession.user.username shouldBe "kerrlab"
            offlineRequests.none { it.url.host == "plex.tv" } shouldBe true
            onlineRequests.any { it.url.host == "plex.tv" } shouldBe true
        }

        test("cold fetchActiveSessions resolves local admin id == 1 and fetchUsers degrades gracefully if plex.tv fails") {
            val (adapter, _) =
                adapterWithMock(plexTvBaseUrl = TEST_PLEX_TV_URL) { request ->
                    when {
                        request.url.host == "plex.tv" && request.url.encodedPath == "/api/v2/user" ->
                            respondJson(PlexFixtures.plexTvUserJson)
                        request.url.host == "plex.tv" && request.url.encodedPath == "/api/v2/friends" ->
                            respond("upstream down", HttpStatusCode.ServiceUnavailable)
                        request.url.encodedPath == "/status/sessions" ->
                            respondJson(PlexFixtures.localOwnerSessionJson)
                        request.url.encodedPath == "/accounts" ->
                            respondJson(PlexFixtures.accountsJson)
                        else -> error("Unexpected path: ${request.url}")
                    }
                }

            val coldSession = adapter.fetchActiveSessions().shouldBeRight().single()
            coldSession.user.externalId shouldBe "99887766"
            coldSession.user.username shouldBe "kerrlab"

            val users = adapter.fetchUsers().shouldBeRight()
            users shouldHaveSize 3
            users.first { it.isAdmin }.externalId shouldBe "99887766"
            users.first { it.externalId == "2002" }.email shouldBe null
        }

        test("does not poison owner cache when plex.tv fails transiently and recovers on subsequent poll") {
            var plexTvUp = false
            val (adapter, _) =
                adapterWithMock(plexTvBaseUrl = TEST_PLEX_TV_URL) { request ->
                    when {
                        request.url.host == "plex.tv" && request.url.encodedPath == "/api/v2/user" ->
                            if (plexTvUp) {
                                respondJson(PlexFixtures.plexTvUserJson)
                            } else {
                                respond("plex.tv unavailable", HttpStatusCode.ServiceUnavailable)
                            }
                        request.url.encodedPath == "/status/sessions" ->
                            respondJson(PlexFixtures.localOwnerSessionJson)
                        request.url.encodedPath == "/accounts" ->
                            respondJson(PlexFixtures.accountsJson)
                        else -> error("Unexpected path: ${request.url}")
                    }
                }

            val firstSession = adapter.fetchActiveSessions().shouldBeRight().single()
            firstSession.user.externalId shouldBe "1"

            plexTvUp = true
            val recoveredSession = adapter.fetchActiveSessions().shouldBeRight().single()
            recoveredSession.user.externalId shouldBe "99887766"
            recoveredSession.user.username shouldBe "kerrlab"
        }

        test("fetches library sections, Size=0 counts with optional type, and paginated items") {
            val (adapter, requests) =
                adapterWithMock { request ->
                    when {
                        request.url.encodedPath == "/library/sections" ->
                            respondJson(PlexFixtures.librarySectionsJson)
                        request.url.encodedPath == "/library/sections/1/all" &&
                            request.url.parameters["X-Plex-Container-Size"] == "0" ->
                            respondJson(PlexFixtures.libraryCountTotalSizeJson)
                        request.url.encodedPath == "/library/sections/2/all" &&
                            request.url.parameters["X-Plex-Container-Size"] == "0" &&
                            request.url.parameters["type"] == "4" ->
                            respondJson(PlexFixtures.libraryCountFallbackSizeJson)
                        request.url.encodedPath == "/library/sections/1/all" &&
                            request.url.parameters["X-Plex-Container-Start"] == "20" &&
                            request.url.parameters["X-Plex-Container-Size"] == "50" &&
                            request.url.parameters["type"] == null ->
                            respondJson(PlexFixtures.libraryItemsPageJson)
                        request.url.encodedPath == "/library/sections/2/all" &&
                            request.url.parameters["X-Plex-Container-Start"] == "0" &&
                            request.url.parameters["X-Plex-Container-Size"] == "25" &&
                            request.url.parameters["type"] == "4" ->
                            respondJson(PlexFixtures.libraryItemsPageJson)
                        else -> error("Unexpected request: ${request.url}")
                    }
                }

            val sections = adapter.fetchLibrarySections().shouldBeRight()
            sections shouldHaveSize 5

            adapter.fetchLibraryCount("1").shouldBeRight() shouldBe 412
            adapter.fetchLibraryCount("2", mediaTypeCode = 4).shouldBeRight() shouldBe 87

            val page = adapter.fetchLibraryItems("1", start = 20, size = 50).shouldBeRight()
            page.totalSize shouldBe 412
            page.offset shouldBe 20
            page.items shouldHaveSize 2

            val episodePage = adapter.fetchLibraryItems("2", start = 0, size = 25, mediaTypeCode = 4).shouldBeRight()
            episodePage.items shouldHaveSize 2
            requests shouldHaveSize 5

            adapter.fetchLibraryCount("../bad").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryCount("1", mediaTypeCode = 0).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryItems("1", start = -1, size = 10).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryItems("1", start = 0, size = 0).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter
                .fetchLibraryItems("1", start = 0, size = 10, mediaTypeCode = 0)
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.ValidationError>()
        }

        test("fetchArtwork supports direct and transcoded requests and blocks SSRF or path traversal") {
            val directBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val thumbBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

            val (adapter, requests) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/123/thumb/1700000000" ->
                            respond(
                                content = directBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                            )

                        "/photo/:/transcode" -> {
                            request.url.parameters["width"] shouldBe "300"
                            request.url.parameters["height"] shouldBe "450"
                            request.url.parameters["minSize"] shouldBe "1"
                            request.url.parameters["upscale"] shouldBe "1"
                            request.url.parameters["url"] shouldBe "/library/metadata/123/thumb/1700000000"
                            respond(
                                content = thumbBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                        }

                        else -> error("Unexpected artwork path: ${request.url}")
                    }
                }

            val direct = adapter.fetchArtwork("/library/metadata/123/thumb/1700000000").shouldBeRight()
            direct shouldBe ArtworkData(directBytes, "image/jpeg")

            val resized = adapter.fetchArtwork("/library/metadata/123/thumb/1700000000", width = 300, height = 450).shouldBeRight()
            resized shouldBe ArtworkData(thumbBytes, "image/png")
            requests shouldHaveSize 2

            val unsafePaths =
                listOf(
                    "",
                    "library/metadata/123/thumb/1",
                    "//evil.example.com/thumb.jpg",
                    "http://169.254.169.254/latest/meta-data",
                    "/library/metadata/../../etc/passwd",
                    "/library/%2e%2e/secrets",
                    "/library/metadata/123?token=leak",
                    "/library/metadata/123#fragment",
                    "/library/metadata/123\r\nX-Injected: 1",
                    "/library/metadata/123\u0000",
                    "/library\\metadata\\123",
                )
            unsafePaths.forEach { badPath ->
                adapter.fetchArtwork(badPath).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            }
            adapter
                .fetchArtwork("/library/metadata/123/thumb/1", width = 0)
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.ValidationError>()
            adapter
                .fetchArtwork("/library/metadata/123/thumb/1", height = -5)
                .shouldBeLeft()
                .shouldBeInstanceOf<MiruError.ValidationError>()
            // None of the invalid validation calls should hit the network
            requests shouldHaveSize 2
        }

        test("maps 401, 403, 404, 500, timeouts, and malformed JSON without leaking X-Plex-Token") {
            val (auth401, _) = adapterWithMock { respond("unauthorized", HttpStatusCode.Unauthorized) }
            val err401 = auth401.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()
            err401.provider shouldBe "plex"
            err401.message shouldNotContain TEST_TOKEN

            val (auth403, _) = adapterWithMock { respond("forbidden", HttpStatusCode.Forbidden) }
            val err403 = auth403.fetchUsers().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()
            err403.provider shouldBe "plex"
            err403.message shouldNotContain TEST_TOKEN

            val (notFoundAdapter, _) = adapterWithMock { respond("missing", HttpStatusCode.NotFound) }
            val err404 = notFoundAdapter.fetchLibraryCount("999").shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()
            err404.resource shouldBe "Library section"
            err404.id shouldBe "999"

            val (server500, _) = adapterWithMock { respond("boom", HttpStatusCode.InternalServerError) }
            val err500 = server500.fetchActiveSessions().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            err500.provider shouldBe "plex"
            err500.message shouldNotContain TEST_TOKEN

            val (malformedAdapter, _) = adapterWithMock { respondJson("{not-valid-json") }
            val errJson = malformedAdapter.fetchLibrarySections().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            errJson.provider shouldBe "plex"
            errJson.message shouldNotContain TEST_TOKEN

            val timeoutEngine =
                MockEngine { request ->
                    assertTokenHeaderAndSafeUrl(request)
                    throw HttpRequestTimeoutException(
                        url = "$TEST_PLEX_URL/status/sessions?leaked=$TEST_TOKEN",
                        timeoutMillis = 15_000L,
                    )
                }
            val timeoutAdapter =
                PlexMediaServerAdapter(
                    config = PlexConfig(baseUrl = TEST_PLEX_URL, token = TEST_TOKEN),
                    httpClient = createPlexHttpClient(engine = timeoutEngine),
                )
            val errTimeout = timeoutAdapter.fetchActiveSessions().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            errTimeout.provider shouldBe "plex"
            errTimeout.message shouldNotContain TEST_TOKEN
        }

        test("events delegates to injectable eventSource and PlexReadinessCheck reports health") {
            val sampleEvent =
                MediaServerEvent.PlayingEvent(
                    sessionKey = "11",
                    ratingKey = "12345",
                    state = PlaybackState.PLAYING,
                    viewOffsetMs = 1_000L,
                )
            val healthyClient =
                HttpClient(MockEngine { respondJson(PlexFixtures.serverRootJson) })
            val healthyAdapter =
                PlexMediaServerAdapter(
                    config = PlexConfig(baseUrl = TEST_PLEX_URL, token = TEST_TOKEN),
                    httpClient = healthyClient,
                    eventSource = flowOf(sampleEvent),
                )

            healthyAdapter.events().toList() shouldBe listOf(sampleEvent)
            PlexReadinessCheck(healthyAdapter).check().shouldBeRight() shouldBe Unit

            val (unhealthyAdapter, _) = adapterWithMock { respond("bad token", HttpStatusCode.Unauthorized) }
            unhealthyAdapter.events().toList().shouldBeEmpty()
            PlexReadinessCheck(unhealthyAdapter).check().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()

            val strictClient =
                HttpClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }) {
                    expectSuccess = true
                }
            val strictAdapter =
                PlexMediaServerAdapter(
                    config = PlexConfig(baseUrl = TEST_PLEX_URL, token = TEST_TOKEN),
                    httpClient = strictClient,
                )
            strictAdapter.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()
        }

        test("fetchMediaDetails parses movie, show, season, and episode with concurrent parent lookups") {
            val (adapter, requests) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/12345" -> respondJson(PlexFixtures.movieMetadataDetailsJson)
                        "/library/metadata/54321" -> respondJson(PlexFixtures.episodeMetadataDetailsJson)
                        "/library/metadata/54300" -> respondJson(PlexFixtures.seasonMetadataDetailsJson)
                        "/library/metadata/54000" -> respondJson(PlexFixtures.showMetadataDetailsJson)
                        else -> respond("not found", HttpStatusCode.NotFound)
                    }
                }

            val movie = adapter.fetchMediaDetails("12345").shouldBeRight()
            movie.mediaType shouldBe MediaType.MOVIE
            movie.title shouldBe "Spirited Away"
            movie.studio shouldBe "Studio Ghibli"
            movie.technical.shouldNotBeNull().subtitleLanguages shouldBe listOf("English", "Japanese")
            movie.series.shouldBeNull()

            val episode = adapter.fetchMediaDetails("54321").shouldBeRight()
            episode.mediaType shouldBe MediaType.EPISODE
            val epSeries = episode.series.shouldNotBeNull()
            epSeries.title shouldBe "Breaking Bad"
            epSeries.seasonCount shouldBe 5
            epSeries.episodeCount shouldBe 62
            val epSeason = episode.season.shouldNotBeNull()
            epSeason.title shouldBe "Season 5"
            epSeason.episodeCount shouldBe 16
            epSeason.currentEpisodeIndex shouldBe 14

            val season = adapter.fetchMediaDetails("54300").shouldBeRight()
            season.mediaType shouldBe MediaType.SEASON
            season.series.shouldNotBeNull().title shouldBe "Breaking Bad"
            season.season.shouldNotBeNull().seasonIndex shouldBe 5

            val show = adapter.fetchMediaDetails("54000").shouldBeRight()
            show.mediaType shouldBe MediaType.SHOW
            show.series.shouldNotBeNull().seasonCount shouldBe 5
            show.season.shouldBeNull()

            val paths = requests.map { it.url.encodedPath }
            paths shouldHaveSize 7
        }

        test("fetchMediaDetails gracefully falls back when parent season or series lookup fails or 404s") {
            val (partialAdapter, _) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/54321" -> respondJson(PlexFixtures.episodeMetadataDetailsJson)
                        "/library/metadata/54300" -> respond("missing season", HttpStatusCode.NotFound)
                        "/library/metadata/54000" -> respond("server error", HttpStatusCode.InternalServerError)
                        else -> respond("missing", HttpStatusCode.NotFound)
                    }
                }

            val episode = partialAdapter.fetchMediaDetails("54321").shouldBeRight()
            episode.title shouldBe "Ozymandias"
            val fallbackSeries = episode.series.shouldNotBeNull()
            fallbackSeries.ratingKey shouldBe "54000"
            fallbackSeries.title shouldBe "Breaking Bad"
            fallbackSeries.summary.shouldBeNull()
            val fallbackSeason = episode.season.shouldNotBeNull()
            fallbackSeason.ratingKey shouldBe "54300"
            fallbackSeason.title shouldBe "Season 5"
            fallbackSeason.currentEpisodeIndex shouldBe 14
            fallbackSeason.episodeCount.shouldBeNull()
        }

        test("fetchMediaDetails maps 404, empty metadata, and invalid ratingKey without leaking token") {
            val (missingAdapter, _) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/empty" -> respondJson(PlexFixtures.emptyMetadataDetailsJson)
                        else -> respond("not found", HttpStatusCode.NotFound)
                    }
                }

            val notFound = missingAdapter.fetchMediaDetails("missing-999").shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()
            notFound.resource shouldBe "Media"
            notFound.id shouldBe "missing-999"

            val emptyErr = missingAdapter.fetchMediaDetails("empty").shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()
            emptyErr.resource shouldBe "Media"
            emptyErr.id shouldBe "empty"

            val invalidErr = missingAdapter.fetchMediaDetails("../bad").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            invalidErr.message shouldNotContain TEST_TOKEN
        }
    })
