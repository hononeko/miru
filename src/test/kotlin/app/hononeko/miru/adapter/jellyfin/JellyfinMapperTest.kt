package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.adapter.LibrarySectionType
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.StreamDecision
import app.hononeko.miru.adapter.StreamLocation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class JellyfinMapperTest :
    FunSpec({
        test("mapServerIdentity handles complete, partial, and blank payloads") {
            val full =
                JellyfinMapper.mapServerIdentity(
                    JellyfinSystemInfoDto(
                        id = "  jf-id-1  ",
                        serverName = " Studio JF ",
                        version = " 10.10.3 ",
                        operatingSystem = " Linux ",
                    ),
                )
            full.machineIdentifier shouldBe "jf-id-1"
            full.friendlyName shouldBe "Studio JF"
            full.version shouldBe "10.10.3"
            full.platform shouldBe "Linux"

            val fallbackToId =
                JellyfinMapper.mapServerIdentity(JellyfinSystemInfoDto(id = "jf-id-2", serverName = "   ", version = null))
            fallbackToId.machineIdentifier shouldBe "jf-id-2"
            fallbackToId.friendlyName shouldBe "jf-id-2"
            fallbackToId.version shouldBe "unknown"
            fallbackToId.platform.shouldBeNull()

            val allBlank = JellyfinMapper.mapServerIdentity(JellyfinSystemInfoDto(id = " ", serverName = "", operatingSystem = " "))
            allBlank.machineIdentifier shouldBe "unknown"
            allBlank.friendlyName shouldBe "Jellyfin Media Server"
            allBlank.version shouldBe "unknown"
            allBlank.platform.shouldBeNull()
        }

        test("mapUsers and mapUserLibraryAccess skip blank IDs and respect admin, all-folders, and null policies") {
            val dtos =
                listOf(
                    JellyfinUserDto(id = "  ", name = "Ignored"),
                    JellyfinUserDto(id = "u-1", name = "   ", policy = null),
                    JellyfinUserDto(
                        id = "u-2",
                        name = "Admin",
                        primaryImageTag = "img-tag",
                        policy = JellyfinUserPolicyDto(isAdministrator = true, enableAllFolders = false, enabledFolders = listOf("f1")),
                    ),
                    JellyfinUserDto(
                        id = "u-3",
                        name = "AllFolders",
                        policy = JellyfinUserPolicyDto(isAdministrator = false, enableAllFolders = true, enabledFolders = listOf("f1")),
                    ),
                    JellyfinUserDto(
                        id = "u-4",
                        name = "Scoped",
                        policy =
                            JellyfinUserPolicyDto(
                                isAdministrator = false,
                                enableAllFolders = false,
                                enabledFolders = listOf(" lib-1 ", "", "lib-2", "lib-1"),
                            ),
                    ),
                )

            val users = JellyfinMapper.mapUsers(dtos)
            users shouldHaveSize 4
            users[0].externalId shouldBe "u-1"
            users[0].username shouldBe "u-1"
            users[0].isAdmin shouldBe false
            users[0].thumbUrl.shouldBeNull()
            users[1].isAdmin shouldBe true
            users[1].thumbUrl shouldBe "/Users/u-2/Images/Primary"

            val access = JellyfinMapper.mapUserLibraryAccess(dtos)
            access shouldHaveSize 4
            access[0].allLibraries shouldBe false
            access[0].sectionKeys.shouldBeEmpty()
            access[1].allLibraries shouldBe true
            access[1].sectionKeys.shouldBeEmpty()
            access[2].allLibraries shouldBe true
            access[2].sectionKeys.shouldBeEmpty()
            access[3].allLibraries shouldBe false
            access[3].sectionKeys shouldBe setOf("lib-1", "lib-2")
        }

        test("mapLibrarySections skips blank ItemId, defaults title, and maps every collection type") {
            val sections =
                JellyfinMapper.mapLibrarySections(
                    listOf(
                        JellyfinVirtualFolderDto(itemId = "  ", name = "Skip"),
                        JellyfinVirtualFolderDto(itemId = "s1", name = "   ", collectionType = "movies"),
                        JellyfinVirtualFolderDto(itemId = "s2", name = "Shows", collectionType = "TVSHOWS"),
                        JellyfinVirtualFolderDto(itemId = "s3", name = "Audio", collectionType = "music"),
                        JellyfinVirtualFolderDto(itemId = "s4", name = "Pics", collectionType = "photos"),
                        JellyfinVirtualFolderDto(itemId = "s5", name = "Home", collectionType = "homevideos"),
                        JellyfinVirtualFolderDto(itemId = "s6", name = "Books", collectionType = "books"),
                        JellyfinVirtualFolderDto(itemId = "s7", name = "Misc", collectionType = null),
                    ),
                )

            sections shouldHaveSize 7
            sections[0].title shouldBe "Library s1"
            sections.map { it.type } shouldContainExactly
                listOf(
                    LibrarySectionType.MOVIE,
                    LibrarySectionType.SHOW,
                    LibrarySectionType.ARTIST,
                    LibrarySectionType.PHOTO,
                    LibrarySectionType.PHOTO,
                    LibrarySectionType.OTHER,
                    LibrarySectionType.OTHER,
                )
        }

        test("mapMediaType classifies all Jellyfin item types and Live TV channel fallbacks") {
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Movie")) shouldBe MediaType.MOVIE
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Episode")) shouldBe MediaType.EPISODE
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Season")) shouldBe MediaType.SEASON
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Series")) shouldBe MediaType.SHOW
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Audio")) shouldBe MediaType.TRACK
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Video")) shouldBe MediaType.CLIP
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "MusicVideo")) shouldBe MediaType.CLIP
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Trailer")) shouldBe MediaType.CLIP
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "TvChannel")) shouldBe MediaType.LIVE_TV
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "LiveTvChannel")) shouldBe MediaType.LIVE_TV
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Program")) shouldBe MediaType.LIVE_TV
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "Custom", channelName = "BBC One")) shouldBe MediaType.LIVE_TV
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = null, channelNumber = "101")) shouldBe MediaType.LIVE_TV
            JellyfinMapper.mapMediaType(JellyfinBaseItemDto(type = "BoxSet")) shouldBe MediaType.UNKNOWN
        }

        test("mapSession covers decision inference, partial transcodes, hwAccel none, subtitles, and stream fallbacks") {
            // 1. Unknown playMethod with transcodingInfo having direct video + transcoded audio -> TRANSCODE, hwAccel="none" -> false
            val partialTranscodeItem =
                JellyfinBaseItemDto(
                    id = "item-1",
                    name = "Concert",
                    type = "Movie",
                    mediaSources =
                        listOf(
                            JellyfinMediaSourceDto(
                                id = "ms-a",
                                container = "mkv",
                                bitrate = 12_000_000,
                                runTimeTicks = 36_000_000_000L,
                                mediaStreams =
                                    listOf(
                                        JellyfinMediaStreamDto(type = "Video", index = 0, codec = "h264", width = 1920, height = 1080),
                                        JellyfinMediaStreamDto(type = "Audio", index = 1, codec = "dts", isDefault = true),
                                        JellyfinMediaStreamDto(type = "Audio", index = 2, codec = "ac3"),
                                    ),
                            ),
                            JellyfinMediaSourceDto(
                                id = "ms-b",
                                container = "mp4",
                                bitrate = 5_000_000,
                                mediaStreams =
                                    listOf(
                                        JellyfinMediaStreamDto(type = "Video", index = 0, codec = "hevc", width = 640, height = 480),
                                        JellyfinMediaStreamDto(type = "Audio", index = 5, codec = "aac"),
                                    ),
                            ),
                        ),
                )
            val partialSession =
                JellyfinMapper.mapSession(
                    session =
                        JellyfinSessionDto(
                            id = "s-partial",
                            userId = "  ",
                            userName = "",
                            deviceId = "",
                            client = "",
                            deviceName = "",
                            remoteEndPoint = "203.0.113.10",
                            playState =
                                JellyfinPlayStateDto(
                                    playMethod = null,
                                    mediaSourceId = "ms-b",
                                    audioStreamIndex = 5,
                                    subtitleStreamIndex = 3,
                                    positionTicks = -50L,
                                ),
                            transcodingInfo =
                                JellyfinTranscodingInfoDto(
                                    isVideoDirect = true,
                                    isAudioDirect = false,
                                    videoCodec = "hevc",
                                    audioCodec = "opus",
                                    container = "ts",
                                    hardwareAccelerationType = "none",
                                    transcodeReasons = listOf("AudioCodecNotSupported"),
                                    bitrate = 0,
                                ),
                        ),
                    item = partialTranscodeItem,
                )
            partialSession.state shouldBe PlaybackState.PLAYING
            partialSession.viewOffsetMs shouldBe 0L
            partialSession.user.externalId shouldBe "unknown"
            partialSession.user.username shouldBe "Unknown"
            partialSession.player.machineIdentifier shouldBe "s-partial"
            partialSession.player.product shouldBe "Unknown"
            partialSession.player.location shouldBe StreamLocation.WAN
            partialSession.technical.decision shouldBe StreamDecision.TRANSCODE
            partialSession.technical.videoDecision shouldBe StreamDecision.DIRECT_STREAM
            partialSession.technical.audioDecision shouldBe StreamDecision.TRANSCODE
            partialSession.technical.isHwTranscode shouldBe false
            partialSession.technical.transcodeVideoCodec.shouldBeNull()
            partialSession.technical.transcodeAudioCodec shouldBe "opus"
            partialSession.technical.transcodeContainer shouldBe "ts"
            partialSession.technical.subtitleDecision shouldBe "copy"
            partialSession.technical.videoResolution shouldBe "480p"
            partialSession.technical.bitrateKbps.shouldBeNull()

            // 2. DirectStream with transcodingInfo having both direct video & audio, plus subtitle copy without transcoding
            val remuxSession =
                JellyfinMapper.mapSession(
                    session =
                        JellyfinSessionDto(
                            id = "s-remux",
                            playState = JellyfinPlayStateDto(playMethod = "unknown", subtitleStreamIndex = -1),
                            transcodingInfo =
                                JellyfinTranscodingInfoDto(
                                    isVideoDirect = true,
                                    isAudioDirect = true,
                                    container = "hls",
                                ),
                        ),
                    item =
                        JellyfinBaseItemDto(
                            id = "m-remux",
                            name = "Remux",
                            type = "Movie",
                            mediaStreams =
                                listOf(
                                    JellyfinMediaStreamDto(type = "Video", codec = "h264", width = 1280, height = 720, bitRate = 4_500_000),
                                    JellyfinMediaStreamDto(type = "Audio", codec = "aac"),
                                ),
                        ),
                )
            remuxSession.technical.decision shouldBe StreamDecision.DIRECT_STREAM
            remuxSession.technical.videoDecision shouldBe StreamDecision.DIRECT_STREAM
            remuxSession.technical.audioDecision shouldBe StreamDecision.DIRECT_STREAM
            remuxSession.technical.subtitleDecision.shouldBeNull()
            remuxSession.technical.bitrateKbps shouldBe 4_500

            // 3. DirectPlay with no video/audio streams + subtitle index >= 0 + Live TV channel title fallback
            val liveSession =
                JellyfinMapper.mapSession(
                    session =
                        JellyfinSessionDto(
                            id = null,
                            playState = JellyfinPlayStateDto(playMethod = null, subtitleStreamIndex = 0),
                        ),
                    item = JellyfinBaseItemDto(id = "tv-1", name = "   ", type = "TvChannel", channelName = "News 24"),
                )
            liveSession.sessionId.shouldBeNull()
            liveSession.media.title shouldBe "News 24"
            liveSession.media.grandparentTitle.shouldBeNull()
            liveSession.technical.decision shouldBe StreamDecision.DIRECT_PLAY
            liveSession.technical.videoDecision.shouldBeNull()
            liveSession.technical.audioDecision.shouldBeNull()
            liveSession.technical.subtitleDecision shouldBe "copy"
        }

        test("mapMediaItemDetails handles fallbacks for episode, season, show, movie, track, and technical specs") {
            // Episode with NO parentSeason and NO grandparentSeries exercises fallbackSeriesOverview & fallbackSeasonDetails
            val episodeFallback =
                JellyfinMapper.mapMediaItemDetails(
                    item =
                        JellyfinBaseItemDto(
                            id = "ep-9",
                            name = "   ",
                            type = "Episode",
                            seriesId = "sr-9",
                            seriesName = "Dark",
                            seriesPrimaryImageTag = "sp-tag",
                            seasonId = "sn-9",
                            parentIndexNumber = 2,
                            indexNumber = 4,
                            premiereDate = "2019-06-21T00:00:00Z",
                            dateCreated = "not-an-iso-date",
                            officialRating = "TV-MA",
                            overview = "Time loops",
                            genres = listOf("Sci-Fi", " Sci-Fi ", ""),
                            studios = listOf(JellyfinNameIdPairDto(name = " "), JellyfinNameIdPairDto(name = "Netflix")),
                            people =
                                listOf(
                                    JellyfinPersonDto(name = "Baran bo Odar", type = "Director"),
                                    JellyfinPersonDto(name = "Baran bo Odar", type = "director"),
                                    JellyfinPersonDto(name = "Actor", type = "Actor"),
                                ),
                        ),
                )
            episodeFallback.title shouldBe "Unknown"
            episodeFallback.year shouldBe 2019
            episodeFallback.originallyAvailableAt shouldBe "2019-06-21"
            episodeFallback.addedAtEpochSeconds.shouldBeNull()
            episodeFallback.studio shouldBe "Netflix"
            episodeFallback.genres shouldContainExactly listOf("Sci-Fi")
            episodeFallback.directors shouldContainExactly listOf("Baran bo Odar")
            episodeFallback.thumbPath shouldBe "/Items/sr-9/Images/Primary"
            episodeFallback.artPath shouldBe "/Items/sr-9/Images/Backdrop"
            episodeFallback.technical.shouldBeNull()
            episodeFallback.series.shouldNotBeNull().apply {
                ratingKey shouldBe "sr-9"
                title shouldBe "Dark"
                hasThumb shouldBe true
            }
            episodeFallback.season.shouldNotBeNull().apply {
                ratingKey shouldBe "sn-9"
                title shouldBe "Season 2"
                seasonIndex shouldBe 2
                currentEpisodeIndex shouldBe 4
                hasThumb shouldBe false
            }

            // Season with missing name & missing grandparentSeries + subtitle displayTitle fallback
            val seasonDetails =
                JellyfinMapper.mapMediaItemDetails(
                    item =
                        JellyfinBaseItemDto(
                            id = "sn-10",
                            name = "",
                            type = "Season",
                            parentId = "sr-10",
                            seriesName = "Andor",
                            childCount = 12,
                            mediaStreams =
                                listOf(
                                    JellyfinMediaStreamDto(type = "Subtitle", language = "  ", displayTitle = "English SDH"),
                                    JellyfinMediaStreamDto(type = "Subtitle", language = "fre", displayTitle = "French"),
                                    JellyfinMediaStreamDto(type = "Subtitle", language = "fre", displayTitle = "French"),
                                ),
                        ),
                )
            seasonDetails.parentTitle shouldBe "Andor"
            seasonDetails.season.shouldNotBeNull().apply {
                title shouldBe "Season"
                episodeCount shouldBe 12
                currentEpisodeIndex.shouldBeNull()
            }
            seasonDetails.series.shouldNotBeNull().ratingKey shouldBe "sr-10"
            seasonDetails.technical.shouldNotBeNull().subtitleLanguages shouldContainExactly listOf("English SDH", "fre")

            // Series with unplayed > recursive clamps viewedLeafCount to 0
            val clampedShow =
                JellyfinMapper.mapMediaItemDetails(
                    item =
                        JellyfinBaseItemDto(
                            id = "sr-clamp",
                            name = "Clamp Show",
                            type = "Series",
                            recursiveItemCount = 5,
                            userData = JellyfinUserItemDataDto(unplayedItemCount = 9),
                        ),
                )
            clampedShow.viewedLeafCount shouldBe 0

            // Track falling back to artists.firstOrNull and albumId when albumArtist / albumArtists / artistItems missing
            val trackItem =
                JellyfinMapper.mapLibraryItem(
                    sectionKey = "lib-music",
                    item =
                        JellyfinBaseItemDto(
                            id = "trk-fb",
                            name = "Solo",
                            type = "Audio",
                            artists = listOf("Solo Artist"),
                            album = "Single",
                            albumId = "alb-fb",
                        ),
                )
            trackItem.grandparentTitle shouldBe "Solo Artist"
            trackItem.grandparentRatingKey shouldBe "alb-fb"
            trackItem.parentTitle shouldBe "Single"
        }

        test("isLocalEndpoint validates IPv4 RFC1918/CGNAT, IPv6 loopback/ULA/link-local, mapped IPv4, and public IPs") {
            val localEndpoints =
                listOf(
                    "localhost",
                    "LOCALHOST",
                    "127.0.0.1",
                    "127.0.0.1:8096",
                    "10.0.0.1",
                    "172.16.0.1",
                    "172.31.255.254",
                    "192.168.0.50:12345",
                    "100.64.0.1",
                    "100.127.255.254",
                    "169.254.1.10",
                    "::1",
                    "[::1]:8096",
                    "fc00::1",
                    "fd12:3456::1",
                    "[fd00::1]:8096",
                    "fe80::1%eth0",
                    "::ffff:10.0.0.5",
                    "::ffff:192.168.1.99",
                    "[::ffff:172.20.0.1]:443",
                )
            localEndpoints.forEach { endpoint ->
                JellyfinMapper.isLocalEndpoint(endpoint) shouldBe true
            }

            val remoteEndpoints =
                listOf(
                    null,
                    "",
                    "   ",
                    "8.8.8.8",
                    "1.1.1.1:443",
                    "172.15.255.255",
                    "172.32.0.1",
                    "100.63.255.255",
                    "100.128.0.1",
                    "203.0.113.42",
                    "2001:db8::1",
                    "[2606:4700:4700::1111]:443",
                    "::ffff:8.8.8.8",
                    "not-an-ip.example.com",
                )
            remoteEndpoints.forEach { endpoint ->
                JellyfinMapper.isLocalEndpoint(endpoint) shouldBe false
            }
        }

        test("mapLibraryItemsPage falls back to items.size and requestedStart when server omits counts") {
            val page =
                JellyfinMapper.mapLibraryItemsPage(
                    sectionKey = "lib-1",
                    requestedStart = 20,
                    response =
                        JellyfinItemsQueryResponse(
                            items = listOf(JellyfinBaseItemDto(id = "m1", name = "Film", type = "Movie")),
                            totalRecordCount = null,
                            startIndex = null,
                        ),
                )
            page.totalSize shouldBe 1
            page.offset shouldBe 20
            page.items shouldHaveSize 1
        }
    })
