package app.hononeko.miru.adapter

import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.funSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode

/**
 * Shared Kotest contract suite run against every [MediaServerAdapter] implementation
 * (mirroring `RepositoryContractSpec` for SQLite/PostgreSQL parity).
 */
object MediaServerAdapterContractSpec {
    interface Harness {
        val expectedServerType: String
        val secretToken: String
        val moviesSectionKey: String
        val showsSectionKey: String
        val movieRatingKey: String
        val episodeRatingKey: String
        val seasonRatingKey: String
        val showRatingKey: String
        val terminateSessionId: String

        fun assertAuthHeader(request: HttpRequestData)

        fun createHealthyAdapter(): Pair<MediaServerAdapter, () -> List<HttpRequestData>>

        fun createEmptySessionsAdapter(): MediaServerAdapter

        fun createMissingParentDetailsAdapter(): MediaServerAdapter

        fun createStatusErrorAdapter(status: HttpStatusCode): MediaServerAdapter

        fun createMalformedJsonAdapter(): MediaServerAdapter

        fun createIoErrorAdapter(messageWithSecret: String): MediaServerAdapter
    }

    fun tests(
        label: String,
        harness: Harness,
    ) = funSpec {
        include(healthAndSessionsContract(label, harness))
        include(usersAndLibrariesContract(label, harness))
        include(detailsArtworkAndTerminationContract(label, harness))
        include(errorMappingContract(label, harness))
    }

    private fun healthAndSessionsContract(
        label: String,
        harness: Harness,
    ) = funSpec {
        test("[$label] reports serverType, checkHealth identity, auth header, and never leaks secret in URLs") {
            val (adapter, recorded) = harness.createHealthyAdapter()
            adapter.serverType shouldBe harness.expectedServerType

            val identity = adapter.checkHealth().shouldBeRight()
            identity.machineIdentifier.isNotBlank() shouldBe true
            identity.friendlyName.isNotBlank() shouldBe true
            identity.version.isNotBlank() shouldBe true
            identity.platform shouldBe "Linux"

            val requests = recorded()
            requests shouldHaveSize 1
            harness.assertAuthHeader(requests.single())
            requests.single().url.toString() shouldNotContain harness.secretToken
        }

        test("[$label] fetchActiveSessions maps direct play, HW transcode, direct stream, music track, and live TV") {
            val (adapter, recorded) = harness.createHealthyAdapter()
            val sessions = adapter.fetchActiveSessions().shouldBeRight()
            sessions shouldHaveSize 5

            val directPlay = sessions.first { it.media.mediaType == MediaType.MOVIE }
            directPlay.state shouldBe PlaybackState.PLAYING
            directPlay.viewOffsetMs shouldBe 120_000L
            directPlay.media.title shouldBe "Dune"
            directPlay.media.durationMs shouldBe 9_300_000L
            directPlay.player.location shouldBe StreamLocation.LAN
            directPlay.player.isLocal shouldBe true
            directPlay.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            directPlay.technical.videoResolution shouldBe "4k"
            directPlay.technical.isHwTranscode shouldBe false

            val transcode = sessions.first { it.media.mediaType == MediaType.EPISODE }
            transcode.state shouldBe PlaybackState.PAUSED
            transcode.media.grandparentTitle shouldBe "Severance"
            transcode.media.parentTitle shouldBe "Season 1"
            transcode.media.parentIndex shouldBe 1
            transcode.media.index shouldBe 1
            transcode.player.location shouldBe StreamLocation.WAN
            transcode.player.isLocal shouldBe false
            transcode.technical.decision shouldBe StreamDecision.TRANSCODE
            transcode.technical.videoDecision shouldBe StreamDecision.TRANSCODE
            transcode.technical.audioDecision shouldBe StreamDecision.TRANSCODE
            transcode.technical.isHwTranscode shouldBe true
            transcode.technical.transcodeVideoCodec shouldBe "h264"
            transcode.technical.transcodeAudioCodec shouldBe "aac"
            transcode.technical.subtitleDecision shouldBe "burn"

            val directStream = sessions.first { it.technical.decision == StreamDecision.DIRECT_STREAM }
            directStream.media.title shouldBe "Remux Clip"

            val track = sessions.first { it.media.mediaType == MediaType.TRACK }
            track.media.title shouldBe "Time"
            track.media.parentTitle shouldBe "Inception OST"
            track.media.grandparentTitle shouldBe "Hans Zimmer"

            val liveTv = sessions.first { it.media.mediaType == MediaType.LIVE_TV }
            liveTv.media.title shouldBe "Evening News"

            recorded().forEach { req ->
                harness.assertAuthHeader(req)
                req.url.toString() shouldNotContain harness.secretToken
            }
        }

        test("[$label] fetchActiveSessions returns empty list when no active playback exists") {
            val adapter = harness.createEmptySessionsAdapter()
            adapter.fetchActiveSessions().shouldBeRight().shouldBeEmpty()
        }
    }

    private fun usersAndLibrariesContract(
        label: String,
        harness: Harness,
    ) = funSpec {
        test("[$label] fetchUsers and fetchUserLibraryAccess return admin and scoped non-admin users") {
            val (adapter, _) = harness.createHealthyAdapter()

            val users = adapter.fetchUsers().shouldBeRight()
            users shouldHaveSize 2
            val admin = users.first { it.isAdmin }
            val viewer = users.first { !it.isAdmin }
            admin.username shouldBe "alice"
            viewer.username shouldBe "bob"

            val access = adapter.fetchUserLibraryAccess().shouldBeRight()
            val adminAccess = access.first { it.externalUserId == admin.externalId }
            val viewerAccess = access.first { it.externalUserId == viewer.externalId }
            adminAccess.allLibraries shouldBe true
            viewerAccess.allLibraries shouldBe false
            viewerAccess.sectionKeys shouldBe setOf(harness.moviesSectionKey)
        }

        test("[$label] fetchLibrarySections, fetchLibraryCount, and fetchLibraryItems validate inputs and paginate") {
            val (adapter, recorded) = harness.createHealthyAdapter()

            val sections = adapter.fetchLibrarySections().shouldBeRight()
            sections.map { it.type } shouldContainAll
                listOf(LibrarySectionType.MOVIE, LibrarySectionType.SHOW, LibrarySectionType.ARTIST, LibrarySectionType.PHOTO)

            adapter.fetchLibraryCount(harness.showsSectionKey, mediaTypeCode = 4).shouldBeRight() shouldBe 25
            val page = adapter.fetchLibraryItems(harness.showsSectionKey, start = 5, size = 2, mediaTypeCode = 4).shouldBeRight()
            page.totalSize shouldBe 25
            page.offset shouldBe 5
            page.items shouldHaveSize 1
            page.items.single().mediaType shouldBe MediaType.EPISODE
            page.items.single().grandparentTitle shouldBe "Severance"
            page.items.single().fileSizeBytes shouldBe 500_000_000L

            val callsBeforeValidation = recorded().size
            adapter.fetchLibraryCount("", mediaTypeCode = null).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryCount("../bad", mediaTypeCode = null).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryCount(harness.showsSectionKey, mediaTypeCode = 0)
                .shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryItems(harness.showsSectionKey, start = -1, size = 10)
                .shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryItems(harness.showsSectionKey, start = 0, size = 0)
                .shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchLibraryItems(harness.showsSectionKey, start = 0, size = 10, mediaTypeCode = -2)
                .shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            recorded().size shouldBe callsBeforeValidation
        }
    }

    private fun detailsArtworkAndTerminationContract(
        label: String,
        harness: Harness,
    ) = funSpec {
        test("[$label] fetchMediaDetails and fetchMediaSectionKey resolve movie, episode, season, show, and missing parents") {
            val (adapter, _) = harness.createHealthyAdapter()

            val movie = adapter.fetchMediaDetails(harness.movieRatingKey).shouldBeRight()
            movie.mediaType shouldBe MediaType.MOVIE
            movie.title shouldBe "Dune"
            movie.librarySectionId shouldBe harness.moviesSectionKey
            movie.genres shouldContain "Sci-Fi"
            movie.directors shouldContain "Denis Villeneuve"
            movie.technical.shouldNotBeNull().videoCodec shouldBe "hevc"

            val episode = adapter.fetchMediaDetails(harness.episodeRatingKey).shouldBeRight()
            episode.mediaType shouldBe MediaType.EPISODE
            episode.librarySectionId shouldBe harness.showsSectionKey
            episode.series.shouldNotBeNull().title shouldBe "Severance"
            episode.season.shouldNotBeNull().seasonIndex shouldBe 1
            episode.season.currentEpisodeIndex shouldBe 1

            val season = adapter.fetchMediaDetails(harness.seasonRatingKey).shouldBeRight()
            season.mediaType shouldBe MediaType.SEASON
            season.series.shouldNotBeNull().title shouldBe "Severance"
            season.season.shouldNotBeNull().currentEpisodeIndex.shouldBeNull()

            val show = adapter.fetchMediaDetails(harness.showRatingKey).shouldBeRight()
            show.mediaType shouldBe MediaType.SHOW
            show.series.shouldNotBeNull().seasonCount shouldBe 2
            show.viewedLeafCount shouldBe 15

            adapter.fetchMediaSectionKey(harness.episodeRatingKey).shouldBeRight() shouldBe harness.showsSectionKey
            adapter.fetchMediaDetails("../escape").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchMediaSectionKey("bad/key").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()

            val fallbackAdapter = harness.createMissingParentDetailsAdapter()
            val degraded = fallbackAdapter.fetchMediaDetails(harness.episodeRatingKey).shouldBeRight()
            degraded.mediaType shouldBe MediaType.EPISODE
            degraded.series.shouldNotBeNull().title shouldBe "Severance"
            degraded.season.shouldNotBeNull().title shouldBe "Season 1"
        }

        test("[$label] mediaArtworkPath and fetchArtwork enforce SSRF/traversal validation and return image bytes") {
            val (adapter, recorded) = harness.createHealthyAdapter()

            val thumbPath = adapter.mediaArtworkPath(harness.movieRatingKey, isBackdrop = false)
            val artPath = adapter.mediaArtworkPath(harness.movieRatingKey, isBackdrop = true)
            thumbPath.startsWith("/") shouldBe true
            artPath.startsWith("/") shouldBe true
            (thumbPath != artPath) shouldBe true

            val artwork = adapter.fetchArtwork(thumbPath, width = 320, height = 480).shouldBeRight()
            artwork.bytes shouldBe byteArrayOf(1, 2, 3, 4)
            artwork.contentType shouldBe "image/png"

            val callsBeforeBad = recorded().size
            listOf(
                "",
                "   ",
                "http://evil.example/img.png",
                "//evil.example/img.png",
                "/Items/../etc/passwd",
                "/Items/%2e%2e/secret",
                "/Items\\evil",
                "/Items/a\nb",
            ).forEach { badPath ->
                adapter.fetchArtwork(badPath).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            }
            adapter.fetchArtwork(thumbPath, width = 0).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.fetchArtwork(thumbPath, height = -10).shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            recorded().size shouldBe callsBeforeBad
        }

        test("[$label] terminateSession trims valid parameters and rejects invalid sessionId or reason without HTTP calls") {
            val (adapter, recorded) = harness.createHealthyAdapter()

            val callsBeforeBad = recorded().size
            adapter.terminateSession("   ", "Maintenance").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.terminateSession("x".repeat(257), "Maintenance").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.terminateSession("bad\nid", "Maintenance").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.terminateSession(harness.terminateSessionId, "bad\rreason")
                .shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            adapter.terminateSession(harness.terminateSessionId, "r".repeat(501))
                .shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            recorded().size shouldBe callsBeforeBad

            adapter.terminateSession(" ${harness.terminateSessionId} ", "  Stopping stream  ").shouldBeRight()
            (recorded().size > callsBeforeBad) shouldBe true
        }
    }

    private fun errorMappingContract(
        label: String,
        harness: Harness,
    ) = funSpec {
        test("[$label] maps 401/403 to UpstreamAuthError, 404 to NotFound, 5xx/malformed/IOException to UpstreamUnavailable") {
            val unauthorized = harness.createStatusErrorAdapter(HttpStatusCode.Unauthorized)
            val err401 = unauthorized.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()
            err401.provider shouldBe harness.expectedServerType

            val forbidden = harness.createStatusErrorAdapter(HttpStatusCode.Forbidden)
            val err403 = forbidden.fetchActiveSessions().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamAuthError>()
            err403.provider shouldBe harness.expectedServerType

            val notFound = harness.createStatusErrorAdapter(HttpStatusCode.NotFound)
            notFound.fetchMediaDetails(harness.movieRatingKey).shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()

            val serverError = harness.createStatusErrorAdapter(HttpStatusCode.ServiceUnavailable)
            val err503 = serverError.fetchLibrarySections().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            err503.provider shouldBe harness.expectedServerType

            val malformed = harness.createMalformedJsonAdapter()
            malformed.fetchUsers().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            val ioError = harness.createIoErrorAdapter("Upstream connection failed token=${harness.secretToken}")
            val errIo = ioError.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            errIo.provider shouldBe harness.expectedServerType
            errIo.message shouldNotContain harness.secretToken
        }
    }
}
