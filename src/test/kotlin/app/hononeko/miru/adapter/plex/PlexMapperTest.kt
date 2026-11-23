package app.hononeko.miru.adapter.plex

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PlexMapperTest :
    FunSpec({
        test("maps server root info and identity-only fallback") {
            val root = MiruJson.decodeFromString<PlexServerInfoResponse>(PlexFixtures.serverRootJson)
            val mappedRoot = PlexMapper.mapServerIdentity(root.mediaContainer)
            mappedRoot.machineIdentifier shouldBe "abc123machine"
            mappedRoot.friendlyName shouldBe "KerrLab Plex"
            mappedRoot.version shouldBe "1.41.3.9314-a0bfb8370"
            mappedRoot.platform shouldBe "Linux"

            val identityOnly = MiruJson.decodeFromString<PlexServerInfoResponse>(PlexFixtures.serverIdentityOnlyJson)
            val mappedIdentity = PlexMapper.mapServerIdentity(identityOnly.mediaContainer)
            mappedIdentity.machineIdentifier shouldBe "identity-only-id"
            mappedIdentity.friendlyName shouldBe "identity-only-id"
            mappedIdentity.version shouldBe "1.41.3.9314"
            mappedIdentity.platform.shouldBeNull()
        }

        test("maps empty /status/sessions without Metadata to empty list") {
            val response = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.emptySessionsJson)
            PlexMapper.mapSessions(response.mediaContainer).shouldBeEmpty()
        }

        test("maps movie direct play LAN session") {
            val response = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.movieDirectPlayLanJson)
            val sessions = PlexMapper.mapSessions(response.mediaContainer)
            sessions shouldHaveSize 1

            val stream = sessions.single()
            stream.sessionId shouldBe "session-movie-1"
            stream.sessionKey shouldBe "11"
            stream.state shouldBe PlaybackState.PLAYING
            stream.viewOffsetMs shouldBe 1_800_000L

            stream.media.ratingKey shouldBe "12345"
            stream.media.mediaType shouldBe MediaType.MOVIE
            stream.media.title shouldBe "Spirited Away"
            stream.media.year shouldBe 2001
            stream.media.durationMs shouldBe 7_500_000L
            stream.media.thumbPath shouldBe "/library/metadata/12345/thumb/1700000000"
            stream.media.artPath shouldBe "/library/metadata/12345/art/1700000000"
            stream.media.librarySectionId shouldBe "1"

            stream.user.externalId shouldBe "2002"
            stream.user.username shouldBe "yuki"
            stream.user.thumbUrl shouldBe "https://plex.tv/users/yuki/avatar"

            stream.player.machineIdentifier shouldBe "player-appletv-1"
            stream.player.product shouldBe "Plex for Apple TV"
            stream.player.platform shouldBe "tvOS"
            stream.player.device shouldBe "Apple TV 4K"
            stream.player.address shouldBe "10.10.20.15"
            stream.player.location shouldBe StreamLocation.LAN
            stream.player.isLocal shouldBe true

            stream.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            stream.technical.videoDecision shouldBe StreamDecision.DIRECT_PLAY
            stream.technical.audioDecision shouldBe StreamDecision.DIRECT_PLAY
            stream.technical.isHwTranscode shouldBe false
            stream.technical.videoCodec shouldBe "h264"
            stream.technical.audioCodec shouldBe "dts"
            stream.technical.container shouldBe "mkv"
            stream.technical.videoResolution shouldBe "1080"
            stream.technical.bitrateKbps shouldBe 14_000
        }

        test("maps TV episode HW transcode WAN session") {
            val response = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.episodeHwTranscodeWanJson)
            val stream = PlexMapper.mapSessions(response.mediaContainer).single()

            stream.state shouldBe PlaybackState.PAUSED
            stream.media.mediaType shouldBe MediaType.EPISODE
            stream.media.title shouldBe "Ozymandias"
            stream.media.parentTitle shouldBe "Season 5"
            stream.media.grandparentTitle shouldBe "Breaking Bad"
            stream.media.parentIndex shouldBe 5
            stream.media.index shouldBe 14
            stream.media.durationMs shouldBe 2_880_000L

            stream.player.location shouldBe StreamLocation.WAN
            stream.player.isLocal shouldBe false

            stream.technical.decision shouldBe StreamDecision.TRANSCODE
            stream.technical.videoDecision shouldBe StreamDecision.TRANSCODE
            stream.technical.audioDecision shouldBe StreamDecision.DIRECT_STREAM
            stream.technical.isHwTranscode shouldBe true
            // The session's Media describes the transcoded output, so the source video codec and container are
            // unknown here (the adapter fills them in). Audio is copied, so its session codec is the file's.
            stream.technical.videoCodec shouldBe null
            stream.technical.transcodeVideoCodec shouldBe "h264"
            stream.technical.audioCodec shouldBe "eac3"
            stream.technical.transcodeAudioCodec shouldBe null
            stream.technical.container shouldBe null
            stream.technical.transcodeContainer shouldBe "mp4"
            stream.technical.subtitleDecision shouldBe "burn"
            stream.technical.videoResolution shouldBe "4k"
            stream.technical.bitrateKbps shouldBe 4_500
            stream.technical.transcodeProgress shouldBe 42.5
            stream.technical.transcodeSpeed shouldBe 3.4
        }

        test("detects software transcode when HW requested fell back and maps direct stream remux") {
            val swResponse = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.episodeSwTranscodeJson)
            val swStream = PlexMapper.mapSessions(swResponse.mediaContainer).single()
            swStream.state shouldBe PlaybackState.BUFFERING
            swStream.technical.decision shouldBe StreamDecision.TRANSCODE
            swStream.technical.isHwTranscode shouldBe false
            // With no Media element, the source codecs come from the transcode session.
            swStream.technical.videoCodec shouldBe "hevc"
            swStream.technical.transcodeVideoCodec shouldBe "h264"
            swStream.technical.audioCodec shouldBe "truehd"
            swStream.technical.transcodeAudioCodec shouldBe "opus"
            swStream.technical.subtitleDecision shouldBe null

            val remuxResponse = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.directStreamRemuxJson)
            val remuxStream = PlexMapper.mapSessions(remuxResponse.mediaContainer).single()
            remuxStream.technical.decision shouldBe StreamDecision.DIRECT_STREAM
            remuxStream.technical.videoDecision shouldBe StreamDecision.DIRECT_STREAM
            remuxStream.technical.audioDecision shouldBe StreamDecision.DIRECT_STREAM
            remuxStream.technical.isHwTranscode shouldBe false
            remuxStream.technical.videoCodec shouldBe "h264"
            remuxStream.technical.transcodeVideoCodec shouldBe null
            remuxStream.technical.container shouldBe null
            remuxStream.technical.transcodeContainer shouldBe "mp4"
        }

        test("treats a '*' transcode codec as unknown") {
            val response = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.episodeSwTranscodeJson)
            val metadata = response.mediaContainer.metadata.orEmpty().single()
            metadata.transcodeSession = metadata.transcodeSession?.copy(sourceVideoCodec = "*", videoCodec = "*")
            val tech = PlexMapper.mapSession(metadata).technical
            tech.videoCodec shouldBe null
            tech.transcodeVideoCodec shouldBe null
        }

        test("maps Plexamp music track and Live TV with missing duration and Media") {
            val trackResponse = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.musicTrackJson)
            val track = PlexMapper.mapSessions(trackResponse.mediaContainer).single()
            track.media.mediaType shouldBe MediaType.TRACK
            track.media.title shouldBe "Resonance"
            track.media.parentTitle shouldBe "Odyssey"
            track.media.grandparentTitle shouldBe "HOME"
            track.media.parentIndex shouldBe 1
            track.media.index shouldBe 5
            track.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            track.technical.audioCodec shouldBe "flac"
            track.technical.videoCodec.shouldBeNull()

            val liveResponse = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.liveTvMissingFieldsJson)
            val live = PlexMapper.mapSessions(liveResponse.mediaContainer).single()
            live.media.mediaType shouldBe MediaType.LIVE_TV
            live.media.title shouldBe "Evening News"
            live.media.grandparentTitle shouldBe "NHK-G"
            live.media.durationMs.shouldBeNull()
            live.player.location shouldBe StreamLocation.LAN
            live.player.isLocal shouldBe true
            live.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            live.technical.videoCodec.shouldBeNull()
        }

        test("normalizes local owner User.id == 1 consistently with owner metadata") {
            val response = MiruJson.decodeFromString<PlexSessionsResponse>(PlexFixtures.localOwnerSessionJson)
            val resolvedOwner =
                MediaUser(
                    externalId = "99887766",
                    username = "kerrlab",
                    displayName = "Alex Kerr",
                    email = "alex@kerrlab.app",
                    thumbUrl = "https://plex.tv/users/owner/avatar",
                    isAdmin = true,
                )

            val normalized = PlexMapper.mapSessions(response.mediaContainer, ownerUser = resolvedOwner).single()
            normalized.user.externalId shouldBe "99887766"
            normalized.user.username shouldBe "kerrlab"
            normalized.user.thumbUrl shouldBe "https://plex.tv/users/owner/avatar"

            val unnormalized = PlexMapper.mapSessions(response.mediaContainer, ownerUser = null).single()
            unnormalized.user.externalId shouldBe "1"
            unnormalized.user.username shouldBe "Local Admin"
        }

        test("merges /accounts with optional plex.tv user and friends and filters dummy id 0") {
            val accounts = MiruJson.decodeFromString<PlexAccountsResponse>(PlexFixtures.accountsJson).mediaContainer.account.orEmpty()
            val tvUser = MiruJson.decodeFromString<PlexTvUserDto>(PlexFixtures.plexTvUserJson)
            val tvFriends = MiruJson.decodeFromString<List<PlexTvFriendDto>>(PlexFixtures.plexTvFriendsJson)

            val offlineUsers = PlexMapper.mapUsers(accounts)
            offlineUsers shouldHaveSize 3
            offlineUsers[0] shouldBe MediaUser(externalId = "1", username = "kerrlab", displayName = "kerrlab", isAdmin = true)
            offlineUsers[1] shouldBe MediaUser(externalId = "2002", username = "yuki", displayName = "yuki", isAdmin = false)
            offlineUsers[2] shouldBe MediaUser(externalId = "3003", username = "managed_kid", displayName = "managed_kid", isAdmin = false)

            val onlineUsers = PlexMapper.mapUsers(accounts, tvUser, tvFriends)
            onlineUsers shouldHaveSize 3
            onlineUsers[0] shouldBe
                MediaUser(
                    externalId = "99887766",
                    username = "kerrlab",
                    displayName = "Alex Kerr",
                    email = "alex@kerrlab.app",
                    thumbUrl = "https://plex.tv/users/owner/avatar",
                    isAdmin = true,
                )
            onlineUsers[1] shouldBe
                MediaUser(
                    externalId = "2002",
                    username = "yuki",
                    displayName = "Yuki Tanaka",
                    email = "yuki@example.com",
                    thumbUrl = "https://plex.tv/users/yuki/avatar",
                    isAdmin = false,
                )
            onlineUsers[2].externalId shouldBe "3003"
            onlineUsers[2].isAdmin shouldBe false
        }

        test("captures thumbUrl from local /accounts for owner fallback and managed/home users") {
            val accountsWithThumbs =
                listOf(
                    PlexAccountDto(id = 1L, name = "kerrlab", thumb = "https://plex.tv/users/local-owner/avatar"),
                    PlexAccountDto(id = 3003L, name = "managed_kid", thumb = "https://plex.tv/users/managed/avatar"),
                )
            val tvUserWithoutThumb = PlexTvUserDto(id = 99887766L, username = "kerrlab", thumb = null)

            val mapped = PlexMapper.mapUsers(accounts = accountsWithThumbs, plexTvUser = tvUserWithoutThumb)
            mapped shouldHaveSize 2
            mapped[0].externalId shouldBe "99887766"
            mapped[0].thumbUrl shouldBe "https://plex.tv/users/local-owner/avatar"
            mapped[1].externalId shouldBe "3003"
            mapped[1].thumbUrl shouldBe "https://plex.tv/users/managed/avatar"
        }

        test("filters unnamed and id-as-name numeric /accounts stubs while keeping plex.tv matches and managed users") {
            val accounts =
                MiruJson
                    .decodeFromString<PlexAccountsResponse>(PlexFixtures.accountsWithNumericGhostsJson)
                    .mediaContainer
                    .account
                    .orEmpty()
            val tvUser = MiruJson.decodeFromString<PlexTvUserDto>(PlexFixtures.plexTvUserJson)
            val tvFriends = PlexTvUsersXmlParser.parse(PlexFixtures.plexTvUsersXml)

            val mapped = PlexMapper.mapUsers(accounts = accounts, plexTvUser = tvUser, plexTvFriends = tvFriends)
            mapped.map { it.externalId to it.username } shouldBe
                listOf(
                    "99887766" to "kerrlab",
                    "2002" to "yuki",
                    "3003" to "managed_kid",
                )

            val offlineMapped = PlexMapper.mapUsers(accounts = accounts)
            offlineMapped.map { it.externalId to it.username } shouldBe
                listOf(
                    "1" to "kerrlab",
                    "3003" to "managed_kid",
                )
        }

        test("PlexTvUsersXmlParser parses shared/managed users and rejects XXE DOCTYPE declarations") {
            val parsed = PlexTvUsersXmlParser.parse(PlexFixtures.plexTvUsersXml)
            parsed shouldHaveSize 2
            parsed[0] shouldBe
                PlexTvFriendDto(
                    id = 2002L,
                    uuid = "yuki-uuid-2",
                    username = "yuki",
                    title = "Yuki Tanaka",
                    email = "yuki@example.com",
                    thumb = "https://plex.tv/users/yuki/avatar",
                )
            parsed[1] shouldBe
                PlexTvFriendDto(
                    id = 3003L,
                    uuid = null,
                    username = null,
                    title = "managed_kid",
                    email = null,
                    thumb = "https://plex.tv/users/managed/avatar",
                )

            val xxeXml =
                """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <MediaContainer><User id="2002" username="&xxe;" /></MediaContainer>
                """.trimIndent()
            shouldThrow<IllegalArgumentException> {
                PlexTvUsersXmlParser.parse(xxeXml)
            }
        }

        test("maps library sections and paginated library items") {
            val sectionsResponse = MiruJson.decodeFromString<PlexLibrarySectionsResponse>(PlexFixtures.librarySectionsJson)
            val sections = PlexMapper.mapLibrarySections(sectionsResponse.mediaContainer)
            sections shouldHaveSize 5
            sections.map { it.type } shouldBe
                listOf(
                    LibrarySectionType.MOVIE,
                    LibrarySectionType.SHOW,
                    LibrarySectionType.ARTIST,
                    LibrarySectionType.PHOTO,
                    LibrarySectionType.OTHER,
                )

            val itemsResponse = MiruJson.decodeFromString<PlexLibraryItemsResponse>(PlexFixtures.libraryItemsPageJson)
            val page = PlexMapper.mapLibraryItemsPage(sectionKey = "1", requestedStart = 0, container = itemsResponse.mediaContainer)
            page.totalSize shouldBe 412
            page.offset shouldBe 20
            page.items shouldHaveSize 2

            val movie = page.items[0]
            movie.ratingKey shouldBe "12345"
            movie.sectionKey shouldBe "1"
            movie.mediaType shouldBe MediaType.MOVIE
            movie.fileSizeBytes shouldBe 11_718_750_000L
            movie.videoResolution shouldBe "1080"
            movie.videoCodec shouldBe "h264"
            movie.audioCodec shouldBe "dts"
            movie.addedAtEpochSeconds shouldBe 1_700_000_000L

            val episode = page.items[1]
            episode.mediaType shouldBe MediaType.EPISODE
            episode.parentTitle shouldBe "Season 5"
            episode.grandparentTitle shouldBe "Breaking Bad"
            episode.videoCodec shouldBe "hevc"
            episode.audioCodec shouldBe "eac3"
            episode.fileSizeBytes shouldBe 8_500_000_000L
        }

        test("ArtworkData implements value equality and safe toString") {
            val a1 = ArtworkData(byteArrayOf(1, 2, 3), "image/jpeg")
            val a2 = ArtworkData(byteArrayOf(1, 2, 3), "image/jpeg")
            val b = ArtworkData(byteArrayOf(4, 5, 6), "image/png")

            a1 shouldBe a2
            a1.hashCode() shouldBe a2.hashCode()
            a1 shouldNotBe b
            a1.toString() shouldBe "ArtworkData(size=3, contentType=image/jpeg)"
        }

        test("maps rich movie, episode, season, and show metadata details") {
            val movieDto =
                MiruJson
                    .decodeFromString<PlexLibraryItemsResponse>(PlexFixtures.movieMetadataDetailsJson)
                    .mediaContainer
                    .metadata!!
                    .single()
            val movie = PlexMapper.mapMediaItemDetails(movieDto)
            movie.ratingKey shouldBe "12345"
            movie.mediaType shouldBe MediaType.MOVIE
            movie.title shouldBe "Spirited Away"
            movie.originallyAvailableAt shouldBe "2001-07-20"
            movie.studio shouldBe "Studio Ghibli"
            movie.contentRating shouldBe "PG"
            movie.genres shouldBe listOf("Animation", "Fantasy")
            movie.directors shouldBe listOf("Hayao Miyazaki")
            movie.librarySectionId shouldBe "1"
            movie.librarySectionTitle shouldBe "Movies"
            movie.technical?.videoResolution shouldBe "1080"
            movie.technical?.videoCodec shouldBe "h264"
            movie.technical?.videoProfile shouldBe "high"
            movie.technical?.audioCodec shouldBe "dts"
            movie.technical?.audioChannels shouldBe 6
            movie.technical?.container shouldBe "mkv"
            movie.technical?.bitrateKbps shouldBe 14000
            movie.technical?.fileSizeBytes shouldBe 11_718_750_000L
            movie.technical?.subtitleLanguages shouldBe listOf("English", "Japanese")
            movie.series.shouldBeNull()
            movie.season.shouldBeNull()

            val episodeDto =
                MiruJson
                    .decodeFromString<PlexLibraryItemsResponse>(PlexFixtures.episodeMetadataDetailsJson)
                    .mediaContainer
                    .metadata!!
                    .single()
            val seasonDto =
                MiruJson
                    .decodeFromString<PlexLibraryItemsResponse>(PlexFixtures.seasonMetadataDetailsJson)
                    .mediaContainer
                    .metadata!!
                    .single()
            val showDto =
                MiruJson
                    .decodeFromString<PlexLibraryItemsResponse>(PlexFixtures.showMetadataDetailsJson)
                    .mediaContainer
                    .metadata!!
                    .single()

            val episode = PlexMapper.mapMediaItemDetails(episodeDto, parentSeason = seasonDto, grandparentSeries = showDto)
            episode.mediaType shouldBe MediaType.EPISODE
            episode.series?.ratingKey shouldBe "54000"
            episode.series?.title shouldBe "Breaking Bad"
            episode.series?.studio shouldBe "AMC"
            episode.series?.seasonCount shouldBe 5
            episode.series?.episodeCount shouldBe 62
            episode.series?.genres shouldBe listOf("Crime", "Drama")
            episode.series?.hasThumb shouldBe true
            episode.season?.ratingKey shouldBe "54300"
            episode.season?.title shouldBe "Season 5"
            episode.season?.seasonIndex shouldBe 5
            episode.season?.episodeCount shouldBe 16
            episode.season?.currentEpisodeIndex shouldBe 14
            episode.season?.hasThumb shouldBe true
            episode.technical?.videoProfile shouldBe "main 10"
            episode.technical?.subtitleLanguages shouldBe listOf("eng")

            val season = PlexMapper.mapMediaItemDetails(seasonDto, grandparentSeries = showDto)
            season.mediaType shouldBe MediaType.SEASON
            season.series?.title shouldBe "Breaking Bad"
            season.season?.seasonIndex shouldBe 5
            season.season?.currentEpisodeIndex.shouldBeNull()

            val show = PlexMapper.mapMediaItemDetails(showDto)
            show.mediaType shouldBe MediaType.SHOW
            show.series?.seasonCount shouldBe 5
            show.series?.episodeCount shouldBe 62
            show.season.shouldBeNull()
            show.technical.shouldBeNull()
        }
    })
