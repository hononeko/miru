package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

private const val TEST_JF_URL = "http://jellyfin.local:8096"
private const val TEST_JF_API_KEY = "jf-super-secret-api-key-987"
private const val TEST_DEVICE_ID = "miru-jf-device"

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private fun MockRequestHandleScope.respondJson(
    json: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(content = json, status = status, headers = jsonHeaders())

private fun assertAuthHeaderAndSafeUrl(request: HttpRequestData) {
    val authHeader = request.headers[HttpHeaders.Authorization].shouldNotBeNull()
    authHeader shouldContain "MediaBrowser "
    authHeader shouldContain """Client="Miru""""
    authHeader shouldContain """DeviceId="$TEST_DEVICE_ID""""
    authHeader shouldContain """Token="$TEST_JF_API_KEY""""
    request.url.toString() shouldNotContain TEST_JF_API_KEY
}

private fun adapterWithMock(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<JellyfinMediaServerAdapter, MutableList<HttpRequestData>> {
    val recorded = mutableListOf<HttpRequestData>()
    val engine =
        MockEngine { request ->
            recorded += request
            assertAuthHeaderAndSafeUrl(request)
            handler(request)
        }
    val client = createJellyfinHttpClient(engine = engine)
    val adapter =
        JellyfinMediaServerAdapter(
            config =
                JellyfinConfig(
                    baseUrl = TEST_JF_URL,
                    apiKey = TEST_JF_API_KEY,
                    clientIdentifier = TEST_DEVICE_ID,
                ),
            httpClient = client,
            appVersion = "1.2.3",
        )
    return adapter to recorded
}

class JellyfinMediaServerAdapterTest :
    FunSpec({
        test("checkHealth returns ServerIdentity and sends MediaBrowser auth header") {
            val (adapter, requests) =
                adapterWithMock { request ->
                    request.url.encodedPath shouldBe "/System/Info"
                    respondJson(
                        """{"Id":"jf-machine-1","ServerName":"KerrLab Jellyfin","Version":"10.10.3","OperatingSystem":"Linux"}""",
                    )
                }

            adapter.serverType shouldBe "jellyfin"
            adapter.mediaArtworkPath("abc123", isBackdrop = false) shouldBe "/Items/abc123/Images/Primary"
            adapter.mediaArtworkPath("abc123", isBackdrop = true) shouldBe "/Items/abc123/Images/Backdrop"

            val identity = adapter.checkHealth().shouldBeRight()
            identity.machineIdentifier shouldBe "jf-machine-1"
            identity.friendlyName shouldBe "KerrLab Jellyfin"
            identity.version shouldBe "10.10.3"
            identity.platform shouldBe "Linux"
            requests shouldHaveSize 1
        }

        test("fetchActiveSessions maps direct play, direct stream, HW transcode, audio, and live TV") {
            val sessionsJson =
                """
                [
                  {
                    "Id": "sess-idle",
                    "UserId": "u1",
                    "UserName": "alice"
                  },
                  {
                    "Id": "sess-dp",
                    "UserId": "u1",
                    "UserName": "alice",
                    "UserPrimaryImageTag": "tag1",
                    "Client": "Jellyfin Web",
                    "DeviceName": "Firefox",
                    "DeviceId": "dev-1",
                    "RemoteEndPoint": "192.168.1.42",
                    "PlayState": {
                      "PositionTicks": 1200000000,
                      "IsPaused": false,
                      "PlayMethod": "DirectPlay",
                      "MediaSourceId": "ms-dp",
                      "AudioStreamIndex": 1
                    },
                    "NowPlayingItem": {
                      "Id": "movie-1",
                      "Name": "Dune",
                      "Type": "Movie",
                      "ProductionYear": 2021,
                      "RunTimeTicks": 93000000000,
                      "ImageTags": { "Primary": "img1" },
                      "BackdropImageTags": ["bg1"],
                      "MediaSources": [
                        {
                          "Id": "ms-dp",
                          "Container": "mkv",
                          "Bitrate": 24000000,
                          "Size": 18000000000,
                          "MediaStreams": [
                            { "Type": "Video", "Index": 0, "Codec": "hevc", "Width": 3840, "Height": 2160, "IsDefault": true },
                            { "Type": "Audio", "Index": 1, "Codec": "truehd", "IsDefault": true }
                          ]
                        }
                      ]
                    }
                  },
                  {
                    "Id": "sess-tc",
                    "UserId": "u2",
                    "UserName": "bob",
                    "Client": "Android TV",
                    "DeviceName": "Shield",
                    "DeviceId": "dev-2",
                    "RemoteEndPoint": "203.0.113.9:51234",
                    "PlayState": {
                      "PositionTicks": 300000000,
                      "IsPaused": true,
                      "PlayMethod": "Transcode",
                      "MediaSourceId": "ms-tc",
                      "AudioStreamIndex": 2,
                      "SubtitleStreamIndex": 3
                    },
                    "TranscodingInfo": {
                      "VideoCodec": "h264",
                      "AudioCodec": "aac",
                      "Container": "ts",
                      "IsVideoDirect": false,
                      "IsAudioDirect": false,
                      "Bitrate": 8000000,
                      "CompletionPercentage": 42.5,
                      "TranscodingFramerate": 115.2,
                      "HardwareAccelerationType": "vaapi",
                      "TranscodeReasons": ["SubtitleCodecNotSupported"]
                    },
                    "NowPlayingItem": {
                      "Id": "ep-1",
                      "Name": "Pilot",
                      "Type": "Episode",
                      "SeriesId": "series-1",
                      "SeriesName": "Severance",
                      "SeriesPrimaryImageTag": "sp1",
                      "SeasonId": "season-1",
                      "SeasonName": "Season 1",
                      "ParentIndexNumber": 1,
                      "IndexNumber": 1,
                      "ProductionYear": 2022,
                      "RunTimeTicks": 30000000000,
                      "MediaStreams": [
                        { "Type": "Video", "Index": 0, "Codec": "hevc", "Width": 1920, "Height": 1080 },
                        { "Type": "Audio", "Index": 2, "Codec": "dts" },
                        { "Type": "Subtitle", "Index": 3, "Language": "eng" }
                      ]
                    }
                  }
                ]
                """.trimIndent()

            val (adapter, _) =
                adapterWithMock { request ->
                    request.url.encodedPath shouldBe "/Sessions"
                    respondJson(sessionsJson)
                }

            val sessions = adapter.fetchActiveSessions().shouldBeRight()
            sessions shouldHaveSize 2

            val dp = sessions[0]
            dp.sessionId shouldBe "sess-dp"
            dp.state shouldBe PlaybackState.PLAYING
            dp.viewOffsetMs shouldBe 120_000L
            dp.media.mediaType shouldBe MediaType.MOVIE
            dp.media.title shouldBe "Dune"
            dp.media.durationMs shouldBe 9_300_000L
            dp.media.thumbPath shouldBe "/Items/movie-1/Images/Primary"
            dp.media.artPath shouldBe "/Items/movie-1/Images/Backdrop"
            dp.user.externalId shouldBe "u1"
            dp.user.thumbUrl shouldBe "/Users/u1/Images/Primary"
            dp.player.location shouldBe StreamLocation.LAN
            dp.player.isLocal shouldBe true
            dp.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            dp.technical.videoResolution shouldBe "4k"
            dp.technical.isHwTranscode shouldBe false

            val tc = sessions[1]
            tc.sessionId shouldBe "sess-tc"
            tc.state shouldBe PlaybackState.PAUSED
            tc.viewOffsetMs shouldBe 30_000L
            tc.media.mediaType shouldBe MediaType.EPISODE
            tc.media.grandparentTitle shouldBe "Severance"
            tc.media.parentTitle shouldBe "Season 1"
            tc.media.grandparentThumbPath shouldBe "/Items/series-1/Images/Primary"
            tc.player.location shouldBe StreamLocation.WAN
            tc.player.isLocal shouldBe false
            tc.technical.decision shouldBe StreamDecision.TRANSCODE
            tc.technical.videoDecision shouldBe StreamDecision.TRANSCODE
            tc.technical.audioDecision shouldBe StreamDecision.TRANSCODE
            tc.technical.isHwTranscode shouldBe true
            tc.technical.transcodeVideoCodec shouldBe "h264"
            tc.technical.transcodeAudioCodec shouldBe "aac"
            tc.technical.transcodeContainer shouldBe "ts"
            tc.technical.subtitleDecision shouldBe "burn"
            tc.technical.transcodeProgress shouldBe 42.5
            tc.technical.bitrateKbps shouldBe 8_000
        }

        test("fetchUsers and fetchUserLibraryAccess map admin status, avatars, and enabled folders") {
            val usersJson =
                """
                [
                  {
                    "Id": "jf-u1",
                    "Name": "Alice",
                    "PrimaryImageTag": "avatar123",
                    "Policy": { "IsAdministrator": true, "EnableAllFolders": false, "EnabledFolders": [] }
                  },
                  {
                    "Id": "jf-u2",
                    "Name": "Bob",
                    "Policy": { "IsAdministrator": false, "EnableAllFolders": false, "EnabledFolders": ["lib-1", " lib-2 "] }
                  }
                ]
                """.trimIndent()
            val (adapter, _) =
                adapterWithMock { request ->
                    request.url.encodedPath shouldBe "/Users"
                    respondJson(usersJson)
                }

            val users = adapter.fetchUsers().shouldBeRight()
            users shouldHaveSize 2
            users[0].externalId shouldBe "jf-u1"
            users[0].isAdmin shouldBe true
            users[0].thumbUrl shouldBe "/Users/jf-u1/Images/Primary"
            users[1].isAdmin shouldBe false
            users[1].thumbUrl.shouldBeNull()

            val access = adapter.fetchUserLibraryAccess().shouldBeRight()
            access shouldHaveSize 2
            access[0].allLibraries shouldBe true
            access[0].sectionKeys.shouldBeEmpty()
            access[1].allLibraries shouldBe false
            access[1].sectionKeys shouldBe setOf("lib-1", "lib-2")
        }

        test("fetchLibrarySections, fetchLibraryCount, and fetchLibraryItems paginate and map items") {
            val (adapter, _) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/Library/VirtualFolders" ->
                            respondJson(
                                """
                                [
                                  { "ItemId": "lib-movies", "Name": "Movies", "CollectionType": "movies" },
                                  { "ItemId": "lib-shows", "Name": "TV", "CollectionType": "tvshows" },
                                  { "ItemId": "lib-music", "Name": "Music", "CollectionType": "music" },
                                  { "ItemId": "lib-home", "Name": "Home", "CollectionType": "homevideos" }
                                ]
                                """.trimIndent(),
                            )

                        "/Items" -> {
                            val limit = request.url.parameters["Limit"]?.toIntOrNull()
                            if (limit == 0) {
                                request.url.parameters["ParentId"] shouldBe "lib-shows"
                                request.url.parameters["IncludeItemTypes"] shouldBe "Episode"
                                respondJson("""{"TotalRecordCount":42,"Items":[]}""")
                            } else {
                                request.url.parameters["StartIndex"] shouldBe "0"
                                request.url.parameters["Limit"] shouldBe "2"
                                respondJson(
                                    """
                                    {
                                      "TotalRecordCount": 42,
                                      "StartIndex": 0,
                                      "Items": [
                                        {
                                          "Id": "item-1",
                                          "Name": "Episode One",
                                          "Type": "Episode",
                                          "SeriesId": "series-9",
                                          "SeriesName": "Show X",
                                          "SeasonName": "Season 1",
                                          "ProductionYear": 2024,
                                          "DateCreated": "2025-01-01T00:00:00Z",
                                          "RunTimeTicks": 15000000000,
                                          "ImageTags": { "Primary": "t1" },
                                          "MediaSources": [
                                            {
                                              "Id": "ms1",
                                              "Size": 500000000,
                                              "MediaStreams": [
                                                { "Type": "Video", "Codec": "h264", "Width": 1280, "Height": 720 },
                                                { "Type": "Audio", "Codec": "aac" }
                                              ]
                                            }
                                          ]
                                        }
                                      ]
                                    }
                                    """.trimIndent(),
                                )
                            }
                        }

                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                }

            val sections = adapter.fetchLibrarySections().shouldBeRight()
            sections.map { it.type } shouldContainExactly
                listOf(LibrarySectionType.MOVIE, LibrarySectionType.SHOW, LibrarySectionType.ARTIST, LibrarySectionType.PHOTO)

            adapter.fetchLibraryCount("lib-shows", mediaTypeCode = 4).shouldBeRight() shouldBe 42
            val page = adapter.fetchLibraryItems("lib-shows", start = 0, size = 2, mediaTypeCode = 4).shouldBeRight()
            page.totalSize shouldBe 42
            page.items shouldHaveSize 1
            page.items[0].ratingKey shouldBe "item-1"
            page.items[0].videoResolution shouldBe "720p"
            page.items[0].fileSizeBytes shouldBe 500_000_000L
        }

        test("counts each library the way Jellyfin does, working out what a mixed library holds") {
            // Item counts per library and type, shaped like a real server: libraries also hold folders
            // (extras, samples) and Jellyfin returns every box set whatever the ParentId
            val counts =
                mapOf(
                    "lib-movies" to mapOf("Movie" to 433),
                    "lib-anime" to mapOf("Series" to 46, "Season" to 223, "Episode" to 3681),
                    "lib-films" to mapOf("Movie" to 12),
                    "lib-both" to mapOf("Movie" to 3, "Series" to 2),
                    "lib-collections" to mapOf("BoxSet" to 66),
                )
            val seenTypes = mutableMapOf<String, MutableList<String?>>()
            val (adapter, _) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/Library/VirtualFolders" ->
                            respondJson(
                                """
                                [
                                  { "ItemId": "lib-movies", "Name": "Movies", "CollectionType": "movies" },
                                  { "ItemId": "lib-anime", "Name": "Anime" },
                                  { "ItemId": "lib-films", "Name": "Films", "CollectionType": "mixed" },
                                  { "ItemId": "lib-both", "Name": "Everything", "CollectionType": null },
                                  { "ItemId": "lib-broken", "Name": "Broken" },
                                  { "ItemId": "lib-collections", "Name": "Collections", "CollectionType": "boxsets" }
                                ]
                                """.trimIndent(),
                            )

                        "/Items" -> {
                            val parent = request.url.parameters["ParentId"].orEmpty()
                            val types = request.url.parameters["IncludeItemTypes"]
                            request.url.parameters["IsFolder"] shouldBe null
                            seenTypes.getOrPut(parent) { mutableListOf() } += types
                            if (parent == "lib-broken") {
                                respond("", status = HttpStatusCode.InternalServerError)
                            } else {
                                val byType = counts[parent].orEmpty()
                                val total = types.orEmpty().split(',').sumOf { type -> byType[type] ?: 0 }
                                respondJson("""{"TotalRecordCount":$total,"Items":[]}""")
                            }
                        }

                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                }

            val sections = adapter.fetchLibrarySections().shouldBeRight()
            sections.associate { it.sectionKey to it.type } shouldBe
                mapOf(
                    "lib-movies" to LibrarySectionType.MOVIE,
                    "lib-anime" to LibrarySectionType.SHOW,
                    "lib-films" to LibrarySectionType.MOVIE,
                    "lib-both" to LibrarySectionType.OTHER,
                    "lib-broken" to LibrarySectionType.OTHER,
                    "lib-collections" to LibrarySectionType.OTHER,
                )

            // Without a type, each library counts its titles: movies, not every non-folder item
            adapter.fetchLibraryCount("lib-movies").shouldBeRight() shouldBe 433
            adapter.fetchLibraryCount("lib-films").shouldBeRight() shouldBe 12
            adapter.fetchLibraryCount("lib-both").shouldBeRight() shouldBe 5
            adapter.fetchLibraryCount("lib-collections").shouldBeRight() shouldBe 66
            // A mixed library of shows is counted like a TV library
            adapter.fetchLibraryCount("lib-anime", mediaTypeCode = 2).shouldBeRight() shouldBe 46
            adapter.fetchLibraryCount("lib-anime", mediaTypeCode = 4).shouldBeRight() shouldBe 3681
            adapter.fetchLibraryItems("lib-movies", start = 0, size = 50).shouldBeRight()

            seenTypes["lib-movies"] shouldContainExactly listOf("Movie", "Movie")
            seenTypes["lib-both"].orEmpty().last() shouldBe "Movie,Series"
            seenTypes["lib-collections"] shouldContainExactly listOf("BoxSet")
            // A mixed library is inspected once, when the sections are listed
            seenTypes["lib-anime"] shouldContainExactly listOf("Series", "Movie", "Series", "Episode")
        }

        test("works out a library's titles on first use when its sections were not listed yet") {
            var sectionCalls = 0
            val seenTypes = mutableListOf<String?>()
            val (adapter, _) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/Library/VirtualFolders" -> {
                            sectionCalls++
                            respondJson("""[{ "ItemId": "lib-movies", "Name": "Movies", "CollectionType": "movies" }]""")
                        }

                        "/Items" -> {
                            seenTypes += request.url.parameters["IncludeItemTypes"]
                            respondJson("""{"TotalRecordCount":433,"Items":[]}""")
                        }

                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                }

            adapter.fetchLibraryCount("lib-movies").shouldBeRight() shouldBe 433
            adapter.fetchLibraryCount("lib-movies").shouldBeRight() shouldBe 433
            sectionCalls shouldBe 1
            seenTypes shouldContainExactly listOf("Movie", "Movie")
        }

        test("fetchMediaDetails and fetchMediaSectionKey resolve season, series, and library ancestor") {
            val (adapter, _) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/Items/ep-1" ->
                            respondJson(
                                """
                                {
                                  "Id": "ep-1",
                                  "Name": "Pilot",
                                  "Type": "Episode",
                                  "SeriesId": "show-1",
                                  "SeriesName": "Andor",
                                  "SeasonId": "season-1",
                                  "SeasonName": "Season 1",
                                  "ParentIndexNumber": 1,
                                  "IndexNumber": 1,
                                  "RunTimeTicks": 25000000000,
                                  "Genres": ["Sci-Fi"],
                                  "People": [{ "Name": "Toby Haynes", "Type": "Director" }],
                                  "ImageTags": { "Primary": "p1" },
                                  "BackdropImageTags": ["b1"]
                                }
                                """.trimIndent(),
                            )

                        "/Items/season-1" ->
                            respondJson("""{"Id":"season-1","Name":"Season 1","IndexNumber":1,"RecursiveItemCount":12}""")

                        "/Items/show-1" ->
                            respondJson(
                                """
                                {
                                  "Id": "show-1",
                                  "Name": "Andor",
                                  "Type": "Series",
                                  "ChildCount": 2,
                                  "RecursiveItemCount": 24,
                                  "Genres": ["Sci-Fi", "Drama"],
                                  "Studios": [{ "Name": "Lucasfilm" }],
                                  "ImageTags": { "Primary": "sp1" }
                                }
                                """.trimIndent(),
                            )

                        "/Items/ep-1/Ancestors" ->
                            respondJson(
                                """[{"Id":"season-1","Type":"Season"},""" +
                                    """{"Id":"lib-tv","Name":"TV Shows","Type":"CollectionFolder"}]""",
                            )

                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                }

            val details = adapter.fetchMediaDetails("ep-1").shouldBeRight()
            details.ratingKey shouldBe "ep-1"
            details.librarySectionId shouldBe "lib-tv"
            details.librarySectionTitle shouldBe "TV Shows"
            details.directors shouldContainExactly listOf("Toby Haynes")
            details.series?.title shouldBe "Andor"
            details.series?.seasonCount shouldBe 2
            details.season?.episodeCount shouldBe 12
            details.season?.currentEpisodeIndex shouldBe 1

            adapter.fetchMediaSectionKey("ep-1").shouldBeRight() shouldBe "lib-tv"
        }

        test("fetchArtwork enforces safe paths and terminateSession sends message and stop") {
            val (adapter, requests) =
                adapterWithMock { request ->
                    when {
                        request.url.encodedPath == "/Items/m1/Images/Primary" ->
                            respond(
                                content = byteArrayOf(1, 2, 3),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/webp"),
                            )

                        request.method == HttpMethod.Post && request.url.encodedPath == "/Sessions/sess1/Message" ->
                            respond("", status = HttpStatusCode.InternalServerError)

                        request.method == HttpMethod.Post && request.url.encodedPath == "/Sessions/sess1/Playing/Stop" ->
                            respond("", status = HttpStatusCode.NoContent)

                        else -> respond("", status = HttpStatusCode.Unauthorized)
                    }
                }

            adapter.fetchArtwork("/Items/../etc/passwd").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchArtwork("http://evil.local/img").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchArtwork("/Items/m1/Images/Primary", width = 400, height = 600).shouldBeRight().apply {
                bytes shouldBe byteArrayOf(1, 2, 3)
                contentType shouldBe "image/webp"
            }

            // Even if /Message returns 500, /Playing/Stop succeeds
            adapter.terminateSession("sess1", "Too many streams").shouldBeRight()
            requests.any { it.url.encodedPath == "/Sessions/sess1/Playing/Stop" } shouldBe true
        }

        test("maps 401/403, 404, malformed JSON, and network IOException cleanly") {
            val (authErrAdapter, _) = adapterWithMock { respond("", status = HttpStatusCode.Unauthorized) }
            authErrAdapter.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()

            val (notFoundAdapter, _) = adapterWithMock { respond("", status = HttpStatusCode.NotFound) }
            notFoundAdapter.fetchMediaDetails("missing1").shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()

            val (badJsonAdapter, _) = adapterWithMock { respondJson("{not-json") }
            badJsonAdapter.fetchUsers().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            val (ioAdapter, _) = adapterWithMock { throw IOException("socket timeout token=$TEST_JF_API_KEY") }
            val err = ioAdapter.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            err.message shouldNotContain TEST_JF_API_KEY
        }

        test("maps edge media types, endpoints, resolutions, tracks, series/seasons, and subtitle burn-in") {
            JellyfinMapper.isLocalEndpoint("localhost") shouldBe true
            JellyfinMapper.isLocalEndpoint("[fd12::1]:8096") shouldBe true
            JellyfinMapper.isLocalEndpoint("100.64.0.5") shouldBe true
            JellyfinMapper.isLocalEndpoint("172.16.5.4") shouldBe true
            JellyfinMapper.isLocalEndpoint("::ffff:192.168.1.10") shouldBe true
            JellyfinMapper.isLocalEndpoint("[::ffff:192.168.1.10]:8096") shouldBe true
            JellyfinMapper.isLocalEndpoint("::ffff:203.0.113.5") shouldBe false
            JellyfinMapper.isLocalEndpoint("fe80::1%eth0") shouldBe true
            JellyfinMapper.isLocalEndpoint("evil.example.com") shouldBe false
            JellyfinMapper.isLocalEndpoint("8.8.8.8") shouldBe false
            JellyfinMapper.isLocalEndpoint(null) shouldBe false

            JellyfinMapper.mapSectionType("homevideos") shouldBe LibrarySectionType.PHOTO
            JellyfinMapper.mapSectionType("books") shouldBe LibrarySectionType.OTHER

            val audioItem =
                JellyfinBaseItemDto(
                    id = "trk-1",
                    name = "Track One",
                    type = "Audio",
                    albumArtist = "Composer",
                    albumArtists = listOf(JellyfinNameIdPairDto(id = "art-99", name = "Composer")),
                    album = "OST",
                    albumId = "alb-1",
                    albumPrimaryImageTag = "ap1",
                    parentBackdropItemId = "art-1",
                    parentBackdropImageTags = listOf("bd1"),
                    premiereDate = "2020-05-01T00:00:00Z",
                    dateCreated = "2024-01-01T00:00:00Z",
                    mediaSources =
                        listOf(
                            JellyfinMediaSourceDto(
                                id = "ms1",
                                container = "flac",
                                mediaStreams =
                                    listOf(
                                        JellyfinMediaStreamDto(type = "Audio", index = 0, codec = "flac"),
                                        JellyfinMediaStreamDto(type = "Video", index = 1, width = 7680, height = 4320),
                                    ),
                            ),
                        ),
                )
            val libTrack = JellyfinMapper.mapLibraryItem("lib-music", audioItem)
            libTrack.mediaType shouldBe MediaType.TRACK
            libTrack.grandparentTitle shouldBe "Composer"
            libTrack.grandparentRatingKey shouldBe "art-99"
            libTrack.parentTitle shouldBe "OST"
            libTrack.thumbPath shouldBe "/Items/alb-1/Images/Primary"
            libTrack.videoResolution shouldBe "8k"

            val trackDetails =
                JellyfinMapper.mapMediaItemDetails(
                    item =
                        JellyfinBaseItemDto(
                            id = "trk-2",
                            name = "Track Two",
                            type = "Audio",
                            albumArtist = "Band",
                            artistItems = listOf(JellyfinNameIdPairDto(id = "band-42", name = "Band")),
                            album = "LP",
                            albumId = "alb-2",
                        ),
                )
            trackDetails.grandparentRatingKey shouldBe "band-42"
            trackDetails.parentRatingKey shouldBe "alb-2"

            val clip = JellyfinMapper.mapMediaType(JellyfinBaseItemDto(id = "c1", type = "MusicVideo"))
            clip shouldBe MediaType.CLIP
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(id = "u1", type = "Folder")) shouldBe MediaType.UNKNOWN

            val subSession =
                JellyfinMapper.mapSession(
                    session =
                        JellyfinSessionDto(
                            id = "s-sub",
                            playState = JellyfinPlayStateDto(playMethod = "Transcode", subtitleStreamIndex = 2),
                            transcodingInfo =
                                JellyfinTranscodingInfoDto(
                                    isVideoDirect = false,
                                    isAudioDirect = true,
                                    transcodeReasons = listOf("SubtitleCodecNotSupported"),
                                    videoCodec = "h264",
                                ),
                        ),
                    item = audioItem,
                )
            subSession.technical.subtitleDecision shouldBe "burn"
            subSession.technical.audioDecision shouldBe StreamDecision.DIRECT_STREAM

            val seriesDetails =
                JellyfinMapper.mapMediaItemDetails(
                    item =
                        JellyfinBaseItemDto(
                            id = "sr-1",
                            name = "Show",
                            type = "Series",
                            recursiveItemCount = 20,
                            userData = JellyfinUserItemDataDto(unplayedItemCount = 5),
                            backdropImageTags = listOf("bd"),
                        ),
                )
            seriesDetails.viewedLeafCount shouldBe 15
            seriesDetails.artPath shouldBe "/Items/sr-1/Images/Backdrop"
            seriesDetails.series?.seasonCount shouldBe null
        }

        test("buildMediaBrowserAuthHeader sanitizes values and adapter handles mediaTypeCode, season, section fallback, and quiet stop") {
            val sanitized = buildMediaBrowserAuthHeader(apiKey = "tok\"en\n1", deviceId = "  ", version = " ")
            sanitized shouldContain """DeviceId="miru""""
            sanitized shouldContain """Version="1.0.0""""
            sanitized shouldContain """Token="token1""""
            sanitized shouldNotContain "\n"

            val seenIncludeTypes = mutableListOf<String?>()
            val seenIsFolder = mutableListOf<String?>()
            val (adapter, requests) =
                adapterWithMock { request ->
                    when (request.url.encodedPath) {
                        "/Items" -> {
                            seenIncludeTypes += request.url.parameters["IncludeItemTypes"]
                            seenIsFolder += request.url.parameters["IsFolder"]
                            respondJson("""{"TotalRecordCount":7,"Items":[]}""")
                        }

                        "/Items/season-5" ->
                            respondJson(
                                """{"Id":"season-5","Name":"Season 5","Type":"Season","ParentId":"show-5","SeriesName":"Expanse"}""",
                            )

                        "/Items/show-5" ->
                            respondJson("""{"Id":"show-5","Name":"Expanse","Type":"Series","ChildCount":6}""")

                        "/Items/season-5/Ancestors" -> respond("", status = HttpStatusCode.InternalServerError)

                        "/Items/movie-fallback" ->
                            respondJson("""{"Id":"movie-fallback","Name":"Solo Film","Type":"Movie","ParentId":"lib-movies"}""")

                        "/Items/movie-fallback/Ancestors" -> respond("", status = HttpStatusCode.NotFound)

                        "/Items/userview-item/Ancestors" ->
                            respondJson("""[{"Id":"uv-lib","Name":"Home Movies","Type":"UserView"}]""")

                        "/Items/err-item/Ancestors" -> respond("", status = HttpStatusCode.ServiceUnavailable)

                        "/Items/no-ct/Images/Primary" ->
                            respond(content = byteArrayOf(9, 8), status = HttpStatusCode.OK)

                        "/Sessions/sess-quiet/Playing/Stop" ->
                            if (request.method == HttpMethod.Post) {
                                respond("", status = HttpStatusCode.NoContent)
                            } else {
                                respond("", HttpStatusCode.BadRequest)
                            }

                        else -> respond("", status = HttpStatusCode.NotFound)
                    }
                }

            // Exercise every mediaTypeCode branch: 2, 3, 4, 8, 9, 10, 1, an unmapped positive code, null, and invalid 0.
            // Without a type, an unknown library (its sections can't be listed here) counts generic titles,
            // never "IsFolder=false", which Jellyfin answers with folders too
            listOf(2, 3, 4, 8, 9, 10, 1, 12, null).forEach { code ->
                adapter.fetchLibraryCount("lib-all", mediaTypeCode = code).shouldBeRight() shouldBe 7
            }
            seenIncludeTypes shouldContainExactly
                listOf(
                    "Series",
                    "Season",
                    "Episode",
                    "MusicArtist",
                    "MusicAlbum",
                    "Audio",
                    "Movie",
                    JellyfinMapper.GENERIC_TITLE_ITEM_TYPES,
                    JellyfinMapper.GENERIC_TITLE_ITEM_TYPES,
                )
            seenIsFolder.filterNotNull() shouldHaveSize 0
            adapter.fetchLibraryCount("lib-all", mediaTypeCode = 0).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()

            // Season details fetches parent series via parentId even when ancestors call fails
            val seasonDetails = adapter.fetchMediaDetails("season-5").shouldBeRight()
            seasonDetails.series?.title shouldBe "Expanse"
            seasonDetails.librarySectionId shouldBe "show-5"

            // fetchMediaSectionKey falls back to /Items/{id} parentId on 404 Ancestors, handles UserView, and propagates 503
            adapter.fetchMediaSectionKey("movie-fallback").shouldBeRight() shouldBe "lib-movies"
            adapter.fetchMediaSectionKey("userview-item").shouldBeRight() shouldBe "uv-lib"
            adapter.fetchMediaSectionKey("err-item").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            // Artwork without Content-Type defaults to application/octet-stream
            val rawArtwork = adapter.fetchArtwork("/Items/no-ct/Images/Primary").shouldBeRight()
            rawArtwork.bytes shouldBe byteArrayOf(9, 8)
            rawArtwork.contentType shouldBe "application/octet-stream"

            // Blank termination reason skips /Message and posts only /Playing/Stop
            adapter.terminateSession("sess-quiet", "   ").shouldBeRight()
            requests.none { it.url.encodedPath == "/Sessions/sess-quiet/Message" } shouldBe true
            requests.any { it.url.encodedPath == "/Sessions/sess-quiet/Playing/Stop" } shouldBe true
        }
    })
