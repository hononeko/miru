package app.hononeko.miru.adapter

import app.hononeko.miru.adapter.jellyfin.JellyfinMediaServerAdapter
import app.hononeko.miru.adapter.jellyfin.createJellyfinHttpClient
import app.hononeko.miru.adapter.plex.PlexMediaServerAdapter
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.PlexConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException

private const val PLEX_CONTRACT_URL = "http://plex.contract.local:32400"
private const val PLEX_TV_CONTRACT_URL = "http://plextv.contract.local"
private const val PLEX_CONTRACT_TOKEN = "plex-contract-secret-token-777"
private const val JF_CONTRACT_URL = "http://jellyfin.contract.local:8096"
private const val JF_CONTRACT_KEY = "jf-contract-secret-key-888"

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private fun xmlHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Xml.toString())

private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(content = body, status = status, headers = jsonHeaders())

private object PlexContractHarness : MediaServerAdapterContractSpec.Harness {
    override val expectedServerType = "plex"
    override val secretToken = PLEX_CONTRACT_TOKEN
    override val moviesSectionKey = "1"
    override val showsSectionKey = "2"
    override val movieRatingKey = "101"
    override val episodeRatingKey = "202"
    override val seasonRatingKey = "201"
    override val showRatingKey = "200"
    override val terminateSessionId = "plex-term-sess-1"

    override fun assertAuthHeader(request: HttpRequestData) {
        request.headers["X-Plex-Token"] shouldBe PLEX_CONTRACT_TOKEN
    }

    override fun createHealthyAdapter(): Pair<MediaServerAdapter, () -> List<HttpRequestData>> =
        buildAdapter(missingParents = false)

    override fun createMissingParentDetailsAdapter(): MediaServerAdapter =
        buildAdapter(missingParents = true).first

    override fun createEmptySessionsAdapter(): MediaServerAdapter =
        buildSimpleAdapter { respondJson("""{"MediaContainer":{"size":0,"Metadata":[]}}""") }

    override fun createStatusErrorAdapter(status: HttpStatusCode): MediaServerAdapter =
        buildSimpleAdapter { respond("", status = status) }

    override fun createMalformedJsonAdapter(): MediaServerAdapter =
        buildSimpleAdapter { respondJson("{not-valid-json") }

    override fun createIoErrorAdapter(messageWithSecret: String): MediaServerAdapter =
        buildSimpleAdapter { throw IOException(messageWithSecret) }

    private fun buildSimpleAdapter(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MediaServerAdapter {
        val engine = MockEngine { req -> handler(req) }
        return PlexMediaServerAdapter(
            config = PlexConfig(baseUrl = PLEX_CONTRACT_URL, token = PLEX_CONTRACT_TOKEN, clientIdentifier = "miru-contract"),
            httpClient = createPlexHttpClient(engine = engine),
            plexTvBaseUrl = PLEX_TV_CONTRACT_URL,
        )
    }

    private fun buildAdapter(missingParents: Boolean): Pair<MediaServerAdapter, () -> List<HttpRequestData>> {
        val recorded = mutableListOf<HttpRequestData>()
        val engine =
            MockEngine { req ->
                recorded += req
                handlePlexRequest(req, missingParents)
            }
        val adapter =
            PlexMediaServerAdapter(
                config = PlexConfig(baseUrl = PLEX_CONTRACT_URL, token = PLEX_CONTRACT_TOKEN, clientIdentifier = "miru-contract"),
                httpClient = createPlexHttpClient(engine = engine),
                plexTvBaseUrl = PLEX_TV_CONTRACT_URL,
            )
        return adapter to { recorded.toList() }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun MockRequestHandleScope.handlePlexRequest(
        req: HttpRequestData,
        missingParents: Boolean,
    ): HttpResponseData =
        when (req.url.encodedPath) {
            "/" ->
                respondJson(
                    """{"MediaContainer":{"machineIdentifier":"plex-mid-contract",""" +
                        """"friendlyName":"Contract Plex","version":"1.41.2","platform":"Linux"}}""",
                )

            "/status/sessions" -> respondJson(PLEX_CONTRACT_SESSIONS_JSON)

            "/accounts" ->
                respondJson(
                    """{"MediaContainer":{"Account":[""" +
                        """{"id":1,"name":"alice","thumb":"https://plex.tv/u/1"},""" +
                        """{"id":2002,"name":"bob"}]}}""",
                )

            "/api/v2/user" ->
                respondJson("""{"id":1001,"username":"alice","title":"Alice","email":"alice@example.test"}""")

            "/api/users" ->
                respond(
                    """<MediaContainer><User id="2002" username="bob" title="Bob" email="bob@example.test"/></MediaContainer>""",
                    headers = xmlHeaders(),
                )

            "/api/servers/plex-mid-contract/shared_servers" ->
                respond(
                    """<MediaContainer><SharedServer userID="2002" allLibraries="0">""" +
                        """<Section key="1" shared="1"/><Section key="2" shared="0"/></SharedServer></MediaContainer>""",
                    headers = xmlHeaders(),
                )

            "/library/sections" ->
                respondJson(
                    """{"MediaContainer":{"Directory":[""" +
                        """{"key":"1","title":"Movies","type":"movie"},""" +
                        """{"key":"2","title":"TV Shows","type":"show"},""" +
                        """{"key":"3","title":"Music","type":"artist"},""" +
                        """{"key":"4","title":"Photos","type":"photo"}]}}""",
                )

            "/library/sections/2/all" ->
                if (req.url.parameters["X-Plex-Container-Size"] == "0") {
                    respondJson("""{"MediaContainer":{"size":0,"totalSize":25,"Metadata":[]}}""")
                } else {
                    respondJson(PLEX_CONTRACT_ITEMS_PAGE_JSON)
                }

            "/library/metadata/101" -> respondJson(PLEX_CONTRACT_MOVIE_DETAILS_JSON)
            "/library/metadata/202" -> respondJson(PLEX_CONTRACT_EPISODE_DETAILS_JSON)
            "/library/metadata/201" ->
                if (missingParents) {
                    respond("", status = HttpStatusCode.NotFound)
                } else {
                    respondJson(PLEX_CONTRACT_SEASON_DETAILS_JSON)
                }

            "/library/metadata/200" ->
                if (missingParents) {
                    respond("", status = HttpStatusCode.NotFound)
                } else {
                    respondJson(PLEX_CONTRACT_SHOW_DETAILS_JSON)
                }

            "/photo/:/transcode", "/library/metadata/101/thumb", "/library/metadata/101/art" ->
                respond(byteArrayOf(1, 2, 3, 4), headers = headersOf(HttpHeaders.ContentType, "image/png"))

            "/status/sessions/terminate" ->
                if (req.method == HttpMethod.Post && req.url.parameters["sessionId"] == terminateSessionId) {
                    respond("", status = HttpStatusCode.OK)
                } else {
                    respond("", status = HttpStatusCode.NotFound)
                }

            else -> respond("", status = HttpStatusCode.NotFound)
        }
}

private object JellyfinContractHarness : MediaServerAdapterContractSpec.Harness {
    override val expectedServerType = "jellyfin"
    override val secretToken = JF_CONTRACT_KEY
    override val moviesSectionKey = "jf-lib-movies"
    override val showsSectionKey = "jf-lib-shows"
    override val movieRatingKey = "jf-movie-101"
    override val episodeRatingKey = "jf-ep-202"
    override val seasonRatingKey = "jf-season-201"
    override val showRatingKey = "jf-show-200"
    override val terminateSessionId = "jf-term-sess-1"

    override fun assertAuthHeader(request: HttpRequestData) {
        val header = request.headers[HttpHeaders.Authorization].shouldNotBeNull()
        header shouldContain "MediaBrowser "
        header shouldContain """Token="$JF_CONTRACT_KEY""""
    }

    override fun createHealthyAdapter(): Pair<MediaServerAdapter, () -> List<HttpRequestData>> =
        buildAdapter(missingParents = false)

    override fun createMissingParentDetailsAdapter(): MediaServerAdapter =
        buildAdapter(missingParents = true).first

    override fun createEmptySessionsAdapter(): MediaServerAdapter =
        buildSimpleAdapter {
            // Includes an idle device session (no NowPlayingItem) to verify idle filtering too
            respondJson("""[{"Id":"idle-1","UserId":"jf-u1","UserName":"alice"}]""")
        }

    override fun createStatusErrorAdapter(status: HttpStatusCode): MediaServerAdapter =
        buildSimpleAdapter { respond("", status = status) }

    override fun createMalformedJsonAdapter(): MediaServerAdapter =
        buildSimpleAdapter { respondJson("{not-valid-json") }

    override fun createIoErrorAdapter(messageWithSecret: String): MediaServerAdapter =
        buildSimpleAdapter { throw IOException(messageWithSecret) }

    private fun buildSimpleAdapter(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MediaServerAdapter {
        val engine = MockEngine { req -> handler(req) }
        return JellyfinMediaServerAdapter(
            config = JellyfinConfig(baseUrl = JF_CONTRACT_URL, apiKey = JF_CONTRACT_KEY, clientIdentifier = "miru-contract"),
            httpClient = createJellyfinHttpClient(engine = engine),
        )
    }

    private fun buildAdapter(missingParents: Boolean): Pair<MediaServerAdapter, () -> List<HttpRequestData>> {
        val recorded = mutableListOf<HttpRequestData>()
        val engine =
            MockEngine { req ->
                recorded += req
                handleJellyfinRequest(req, missingParents)
            }
        val adapter =
            JellyfinMediaServerAdapter(
                config = JellyfinConfig(baseUrl = JF_CONTRACT_URL, apiKey = JF_CONTRACT_KEY, clientIdentifier = "miru-contract"),
                httpClient = createJellyfinHttpClient(engine = engine),
            )
        return adapter to { recorded.toList() }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun MockRequestHandleScope.handleJellyfinRequest(
        req: HttpRequestData,
        missingParents: Boolean,
    ): HttpResponseData =
        when (req.url.encodedPath) {
            "/System/Info" ->
                respondJson(
                    """{"Id":"jf-mid-contract","ServerName":"Contract Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                )

            "/Sessions" -> respondJson(JF_CONTRACT_SESSIONS_JSON)

            "/Users" ->
                respondJson(
                    """[""" +
                        """{"Id":"jf-u1","Name":"alice","PrimaryImageTag":"av1",""" +
                        """"Policy":{"IsAdministrator":true,"EnableAllFolders":true}},""" +
                        """{"Id":"jf-u2","Name":"bob","Policy":{"IsAdministrator":false,""" +
                        """"EnableAllFolders":false,"EnabledFolders":["jf-lib-movies"]}}""" +
                        """]""",
                )

            "/Library/VirtualFolders" ->
                respondJson(
                    """[""" +
                        """{"ItemId":"jf-lib-movies","Name":"Movies","CollectionType":"movies"},""" +
                        """{"ItemId":"jf-lib-shows","Name":"TV Shows","CollectionType":"tvshows"},""" +
                        """{"ItemId":"jf-lib-music","Name":"Music","CollectionType":"music"},""" +
                        """{"ItemId":"jf-lib-photos","Name":"Photos","CollectionType":"photos"}]""",
                )

            "/Items" -> {
                val ids = req.url.parameters["Ids"]
                when {
                    ids != null -> {
                        val matched =
                            when (ids) {
                                "jf-movie-101" -> JF_CONTRACT_MOVIE_DETAILS_JSON
                                "jf-ep-202" -> JF_CONTRACT_EPISODE_DETAILS_JSON
                                "jf-season-201" -> if (missingParents) null else JF_CONTRACT_SEASON_DETAILS_JSON
                                "jf-show-200" -> if (missingParents) null else JF_CONTRACT_SHOW_DETAILS_JSON
                                else -> null
                            }
                        if (matched == null) {
                            respondJson("""{"TotalRecordCount":0,"Items":[]}""")
                        } else {
                            respondJson("""{"TotalRecordCount":1,"Items":[$matched]}""")
                        }
                    }

                    req.url.parameters["Limit"] == "0" ->
                        respondJson("""{"TotalRecordCount":25,"Items":[]}""")

                    else ->
                        respondJson(JF_CONTRACT_ITEMS_PAGE_JSON)
                }
            }

            "/Items/jf-movie-101",
            "/Items/jf-ep-202",
            "/Items/jf-season-201",
            "/Items/jf-show-200",
            -> respond("", status = HttpStatusCode.MethodNotAllowed)

            "/Items/jf-movie-101/Ancestors" ->
                respondJson("""[{"Id":"jf-lib-movies","Name":"Movies","Type":"CollectionFolder"}]""")

            "/Items/jf-ep-202/Ancestors" ->
                respondJson("""[{"Id":"jf-lib-shows","Name":"TV Shows","Type":"CollectionFolder"}]""")

            "/Items/jf-season-201/Ancestors" ->
                respondJson("""[{"Id":"jf-lib-shows","Name":"TV Shows","Type":"CollectionFolder"}]""")

            "/Items/jf-show-200/Ancestors" ->
                respondJson("""[{"Id":"jf-lib-shows","Name":"TV Shows","Type":"CollectionFolder"}]""")

            "/Items/jf-movie-101/Images/Primary", "/Items/jf-movie-101/Images/Backdrop" ->
                respond(byteArrayOf(1, 2, 3, 4), headers = headersOf(HttpHeaders.ContentType, "image/png"))

            "/Sessions/$terminateSessionId/Message" ->
                if (req.method == HttpMethod.Post) {
                    respond("", status = HttpStatusCode.NoContent)
                } else {
                    respond("", HttpStatusCode.MethodNotAllowed)
                }

            "/Sessions/$terminateSessionId/Playing/Stop" ->
                if (req.method == HttpMethod.Post) {
                    respond("", status = HttpStatusCode.NoContent)
                } else {
                    respond("", HttpStatusCode.MethodNotAllowed)
                }

            else -> respond("", status = HttpStatusCode.NotFound)
        }
}

class MediaServerAdapterContractTest :
    FunSpec({
        include(MediaServerAdapterContractSpec.tests("Plex", PlexContractHarness))
        include(MediaServerAdapterContractSpec.tests("Jellyfin", JellyfinContractHarness))
    })

private val PLEX_CONTRACT_SESSIONS_JSON =
    """
    {
      "MediaContainer": {
        "size": 5,
        "Metadata": [
          {
            "sessionKey": "1",
            "ratingKey": "101",
            "type": "movie",
            "title": "Dune",
            "year": 2021,
            "duration": 9300000,
            "viewOffset": 120000,
            "thumb": "/library/metadata/101/thumb",
            "art": "/library/metadata/101/art",
            "User": { "id": "1001", "title": "alice" },
            "Player": { "machineIdentifier": "p1", "product": "Plex Web", "state": "playing", "local": true, "address": "192.168.1.20" },
            "Session": { "id": "s-dp", "bandwidth": 24000, "location": "lan" },
            "Media": [{ "videoResolution": "4k", "videoCodec": "hevc", "audioCodec": "truehd", "container": "mkv", "Part": [{ "decision": "directplay" }] }]
          },
          {
            "sessionKey": "2",
            "ratingKey": "202",
            "type": "episode",
            "title": "Pilot",
            "parentTitle": "Season 1",
            "grandparentTitle": "Severance",
            "parentIndex": 1,
            "index": 1,
            "duration": 3000000,
            "viewOffset": 60000,
            "User": { "id": "2002", "title": "bob" },
            "Player": { "machineIdentifier": "p2", "product": "Android TV", "state": "paused", "local": false, "address": "203.0.113.5" },
            "Session": { "id": "s-tc", "bandwidth": 8000, "location": "wan" },
            "TranscodeSession": {
              "videoDecision": "transcode",
              "audioDecision": "transcode",
              "subtitleDecision": "burn",
              "transcodeHwRequested": true,
              "videoCodec": "h264",
              "audioCodec": "aac",
              "container": "mpegts",
              "progress": 45.0
            },
            "Media": [{
              "videoResolution": "1080",
              "videoCodec": "hevc",
              "audioCodec": "dts",
              "container": "mkv",
              "Part": [{ "decision": "transcode", "Stream": [{ "streamType": 3, "decision": "burn" }] }]
            }]
          },
          {
            "sessionKey": "3",
            "ratingKey": "301",
            "type": "clip",
            "title": "Remux Clip",
            "duration": 600000,
            "viewOffset": 10000,
            "User": { "id": "1001", "title": "alice" },
            "Player": { "machineIdentifier": "p3", "product": "Plex iOS", "state": "playing", "local": true, "address": "10.0.0.5" },
            "Session": { "id": "s-ds", "bandwidth": 4000, "location": "lan" },
            "Media": [{ "videoResolution": "1080", "videoCodec": "h264", "audioCodec": "aac", "container": "mkv", "Part": [{ "decision": "copy" }] }]
          },
          {
            "sessionKey": "4",
            "ratingKey": "401",
            "type": "track",
            "title": "Time",
            "parentTitle": "Inception OST",
            "grandparentTitle": "Hans Zimmer",
            "duration": 275000,
            "viewOffset": 15000,
            "User": { "id": "1001", "title": "alice" },
            "Player": { "machineIdentifier": "p4", "product": "Plexamp", "state": "playing", "local": true, "address": "10.0.0.6" },
            "Session": { "id": "s-trk", "bandwidth": 320, "location": "lan" },
            "Media": [{ "audioCodec": "flac", "container": "flac", "Part": [{ "decision": "directplay" }] }]
          },
          {
            "sessionKey": "5",
            "ratingKey": "501",
            "type": "episode",
            "live": 1,
            "title": "Evening News",
            "duration": 1800000,
            "viewOffset": 300000,
            "User": { "id": "2002", "title": "bob" },
            "Player": { "machineIdentifier": "p5", "product": "Apple TV", "state": "playing", "local": true, "address": "192.168.1.9" },
            "Session": { "id": "s-tv", "bandwidth": 6000, "location": "lan" },
            "Media": [{ "videoResolution": "1080", "videoCodec": "h264", "audioCodec": "ac3", "container": "mpegts", "Part": [{ "decision": "directplay" }] }]
          }
        ]
      }
    }
    """.trimIndent()

private val PLEX_CONTRACT_ITEMS_PAGE_JSON =
    """
    {
      "MediaContainer": {
        "size": 1,
        "totalSize": 25,
        "offset": 5,
        "Metadata": [
          {
            "ratingKey": "202",
            "type": "episode",
            "title": "Pilot",
            "parentTitle": "Season 1",
            "grandparentTitle": "Severance",
            "grandparentRatingKey": "200",
            "year": 2022,
            "duration": 3000000,
            "addedAt": 1700000000,
            "Media": [{ "videoResolution": "1080", "videoCodec": "h264", "audioCodec": "aac", "Part": [{ "size": 500000000 }] }]
          }
        ]
      }
    }
    """.trimIndent()

private val PLEX_CONTRACT_MOVIE_DETAILS_JSON =
    """
    {
      "MediaContainer": {
        "Metadata": [
          {
            "ratingKey": "101",
            "type": "movie",
            "title": "Dune",
            "year": 2021,
            "librarySectionID": "1",
            "librarySectionTitle": "Movies",
            "Genre": [{ "tag": "Sci-Fi" }],
            "Director": [{ "tag": "Denis Villeneuve" }],
            "Media": [{ "videoResolution": "4k", "videoCodec": "hevc", "audioCodec": "truehd", "container": "mkv", "bitrate": 24000 }]
          }
        ]
      }
    }
    """.trimIndent()

private val PLEX_CONTRACT_EPISODE_DETAILS_JSON =
    """
    {
      "MediaContainer": {
        "Metadata": [
          {
            "ratingKey": "202",
            "type": "episode",
            "title": "Pilot",
            "parentRatingKey": "201",
            "grandparentRatingKey": "200",
            "parentTitle": "Season 1",
            "grandparentTitle": "Severance",
            "parentIndex": 1,
            "index": 1,
            "librarySectionID": "2",
            "librarySectionTitle": "TV Shows"
          }
        ]
      }
    }
    """.trimIndent()

private val PLEX_CONTRACT_SEASON_DETAILS_JSON =
    """
    {
      "MediaContainer": {
        "Metadata": [
          {
            "ratingKey": "201",
            "type": "season",
            "title": "Season 1",
            "index": 1,
            "parentRatingKey": "200",
            "parentTitle": "Severance",
            "leafCount": 9,
            "librarySectionID": "2"
          }
        ]
      }
    }
    """.trimIndent()

private val PLEX_CONTRACT_SHOW_DETAILS_JSON =
    """
    {
      "MediaContainer": {
        "Metadata": [
          {
            "ratingKey": "200",
            "type": "show",
            "title": "Severance",
            "childCount": 2,
            "leafCount": 20,
            "viewedLeafCount": 15,
            "librarySectionID": "2"
          }
        ]
      }
    }
    """.trimIndent()

private val JF_CONTRACT_SESSIONS_JSON =
    """
    [
      { "Id": "idle-device", "UserId": "jf-u1", "UserName": "alice" },
      {
        "Id": "s-dp",
        "UserId": "jf-u1",
        "UserName": "alice",
        "Client": "Jellyfin Web",
        "DeviceName": "Firefox",
        "DeviceId": "d1",
        "RemoteEndPoint": "192.168.1.20",
        "PlayState": { "PositionTicks": 1200000000, "IsPaused": false, "PlayMethod": "DirectPlay", "MediaSourceId": "ms1" },
        "NowPlayingItem": {
          "Id": "jf-movie-101",
          "Name": "Dune",
          "Type": "Movie",
          "ProductionYear": 2021,
          "RunTimeTicks": 93000000000,
          "ImageTags": { "Primary": "p1" },
          "BackdropImageTags": ["b1"],
          "MediaSources": [{
            "Id": "ms1",
            "Container": "mkv",
            "Bitrate": 24000000,
            "MediaStreams": [
              { "Type": "Video", "Codec": "hevc", "Width": 3840, "Height": 2160 },
              { "Type": "Audio", "Codec": "truehd" }
            ]
          }]
        }
      },
      {
        "Id": "s-tc",
        "UserId": "jf-u2",
        "UserName": "bob",
        "Client": "Android TV",
        "DeviceName": "Shield",
        "DeviceId": "d2",
        "RemoteEndPoint": "203.0.113.5",
        "PlayState": { "PositionTicks": 600000000, "IsPaused": true, "PlayMethod": "Transcode", "SubtitleStreamIndex": 2 },
        "TranscodingInfo": {
          "VideoCodec": "h264",
          "AudioCodec": "aac",
          "Container": "ts",
          "IsVideoDirect": false,
          "IsAudioDirect": false,
          "HardwareAccelerationType": "vaapi",
          "TranscodeReasons": ["SubtitleCodecNotSupported"],
          "Bitrate": 8000000,
          "CompletionPercentage": 45.0
        },
        "NowPlayingItem": {
          "Id": "jf-ep-202",
          "Name": "Pilot",
          "Type": "Episode",
          "SeriesId": "jf-show-200",
          "SeriesName": "Severance",
          "SeasonId": "jf-season-201",
          "SeasonName": "Season 1",
          "ParentIndexNumber": 1,
          "IndexNumber": 1,
          "RunTimeTicks": 30000000000,
          "MediaStreams": [{ "Type": "Video", "Codec": "hevc", "Width": 1920, "Height": 1080 }, { "Type": "Audio", "Codec": "dts" }]
        }
      },
      {
        "Id": "s-ds",
        "UserId": "jf-u1",
        "UserName": "alice",
        "Client": "Swiftfin",
        "DeviceName": "iPhone",
        "DeviceId": "d3",
        "RemoteEndPoint": "10.0.0.5",
        "PlayState": { "PositionTicks": 100000000, "IsPaused": false, "PlayMethod": "DirectStream" },
        "NowPlayingItem": { "Id": "jf-clip-301", "Name": "Remux Clip", "Type": "Video", "RunTimeTicks": 6000000000 }
      },
      {
        "Id": "s-trk",
        "UserId": "jf-u1",
        "UserName": "alice",
        "Client": "Finamp",
        "DeviceName": "Phone",
        "DeviceId": "d4",
        "RemoteEndPoint": "10.0.0.6",
        "PlayState": { "PositionTicks": 150000000, "IsPaused": false, "PlayMethod": "DirectPlay" },
        "NowPlayingItem": { "Id": "jf-trk-401", "Name": "Time", "Type": "Audio", "Album": "Inception OST", "AlbumArtist": "Hans Zimmer", "RunTimeTicks": 2750000000 }
      },
      {
        "Id": "s-tv",
        "UserId": "jf-u2",
        "UserName": "bob",
        "Client": "Jellyfin TV",
        "DeviceName": "Living Room",
        "DeviceId": "d5",
        "RemoteEndPoint": "192.168.1.9",
        "PlayState": { "PositionTicks": 3000000000, "IsPaused": false, "PlayMethod": "DirectPlay" },
        "NowPlayingItem": { "Id": "jf-tv-501", "Name": "Evening News", "Type": "TvChannel", "ChannelName": "Channel 4" }
      }
    ]
    """.trimIndent()

private val JF_CONTRACT_ITEMS_PAGE_JSON =
    """
    {
      "TotalRecordCount": 25,
      "StartIndex": 5,
      "Items": [
        {
          "Id": "jf-ep-202",
          "Name": "Pilot",
          "Type": "Episode",
          "SeriesId": "jf-show-200",
          "SeriesName": "Severance",
          "SeasonName": "Season 1",
          "ProductionYear": 2022,
          "DateCreated": "2024-01-01T00:00:00Z",
          "RunTimeTicks": 30000000000,
          "MediaSources": [{ "Id": "ms-ep", "Size": 500000000, "MediaStreams": [{ "Type": "Video", "Codec": "h264", "Width": 1920, "Height": 1080 }, { "Type": "Audio", "Codec": "aac" }] }]
        }
      ]
    }
    """.trimIndent()

private val JF_CONTRACT_MOVIE_DETAILS_JSON =
    """
    {
      "Id": "jf-movie-101",
      "Name": "Dune",
      "Type": "Movie",
      "ProductionYear": 2021,
      "Genres": ["Sci-Fi"],
      "People": [{ "Name": "Denis Villeneuve", "Type": "Director" }],
      "ImageTags": { "Primary": "p1" },
      "BackdropImageTags": ["b1"],
      "MediaSources": [{ "Id": "ms1", "Container": "mkv", "Bitrate": 24000000, "MediaStreams": [{ "Type": "Video", "Codec": "hevc", "Width": 3840, "Height": 2160 }, { "Type": "Audio", "Codec": "truehd" }] }]
    }
    """.trimIndent()

private val JF_CONTRACT_EPISODE_DETAILS_JSON =
    """
    {
      "Id": "jf-ep-202",
      "Name": "Pilot",
      "Type": "Episode",
      "SeriesId": "jf-show-200",
      "SeriesName": "Severance",
      "SeasonId": "jf-season-201",
      "SeasonName": "Season 1",
      "ParentIndexNumber": 1,
      "IndexNumber": 1
    }
    """.trimIndent()

private val JF_CONTRACT_SEASON_DETAILS_JSON =
    """
    {
      "Id": "jf-season-201",
      "Name": "Season 1",
      "Type": "Season",
      "IndexNumber": 1,
      "SeriesId": "jf-show-200",
      "SeriesName": "Severance",
      "RecursiveItemCount": 9
    }
    """.trimIndent()

private val JF_CONTRACT_SHOW_DETAILS_JSON =
    """
    {
      "Id": "jf-show-200",
      "Name": "Severance",
      "Type": "Series",
      "ChildCount": 2,
      "RecursiveItemCount": 20,
      "UserData": { "UnplayedItemCount": 5 }
    }
    """.trimIndent()
