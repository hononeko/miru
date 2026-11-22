package app.hononeko.miru.adapter.plex

internal object PlexFixtures {
    val serverRootJson =
        """
        {
          "MediaContainer": {
            "size": 12,
            "claimed": true,
            "machineIdentifier": "abc123machine",
            "friendlyName": "KerrLab Plex",
            "platform": "Linux",
            "version": "1.41.3.9314-a0bfb8370"
          }
        }
        """.trimIndent()

    val serverIdentityOnlyJson =
        """
        {
          "MediaContainer": {
            "size": 0,
            "claimed": true,
            "machineIdentifier": "identity-only-id",
            "version": "1.41.3.9314"
          }
        }
        """.trimIndent()

    val emptySessionsJson =
        """
        {
          "MediaContainer": {
            "size": 0
          }
        }
        """.trimIndent()

    val movieDirectPlayLanJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "11",
                "ratingKey": "12345",
                "type": "movie",
                "title": "Spirited Away",
                "year": 2001,
                "duration": 7500000,
                "viewOffset": 1800000,
                "thumb": "/library/metadata/12345/thumb/1700000000",
                "art": "/library/metadata/12345/art/1700000000",
                "librarySectionID": "1",
                "Media": [
                  {
                    "id": "555",
                    "duration": 7500000,
                    "bitrate": 12500,
                    "videoResolution": "1080",
                    "container": "mkv",
                    "videoCodec": "h264",
                    "audioCodec": "dts",
                    "selected": true,
                    "Part": [
                      {
                        "id": "666",
                        "key": "/library/parts/666/file.mkv",
                        "duration": 7500000,
                        "size": 11718750000,
                        "container": "mkv",
                        "decision": "directplay",
                        "selected": true,
                        "Stream": [
                          {
                            "id": "777",
                            "streamType": 1,
                            "codec": "h264",
                            "decision": "directplay",
                            "selected": true,
                            "width": 1920,
                            "height": 1080
                          },
                          {
                            "id": "778",
                            "streamType": 2,
                            "codec": "dts",
                            "decision": "directplay",
                            "selected": true,
                            "channels": 6
                          }
                        ]
                      }
                    ]
                  }
                ],
                "User": {
                  "id": "2002",
                  "title": "yuki",
                  "thumb": "https://plex.tv/users/yuki/avatar"
                },
                "Player": {
                  "machineIdentifier": "player-appletv-1",
                  "product": "Plex for Apple TV",
                  "platform": "tvOS",
                  "device": "Apple TV 4K",
                  "title": "Living Room Apple TV",
                  "address": "10.10.20.15",
                  "local": true,
                  "state": "playing"
                },
                "Session": {
                  "id": "session-movie-1",
                  "bandwidth": 14000,
                  "location": "lan"
                }
              }
            ]
          }
        }
        """.trimIndent()

    val episodeHwTranscodeWanJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "12",
                "ratingKey": "54321",
                "type": "episode",
                "title": "Ozymandias",
                "parentTitle": "Season 5",
                "grandparentTitle": "Breaking Bad",
                "parentIndex": 5,
                "index": 14,
                "year": 2013,
                "duration": 2880000,
                "viewOffset": 920000,
                "thumb": "/library/metadata/54321/thumb/1700000100",
                "grandparentThumb": "/library/metadata/50000/thumb/1700000100",
                "librarySectionID": "2",
                "Media": [
                  {
                    "id": "888",
                    "bitrate": 18000,
                    "videoResolution": "4k",
                    "container": "mkv",
                    "videoCodec": "hevc",
                    "audioCodec": "eac3",
                    "Part": [
                      {
                        "id": "889",
                        "container": "mkv",
                        "decision": "transcode",
                        "Stream": [
                          {
                            "id": "890",
                            "streamType": 1,
                            "codec": "hevc",
                            "decision": "transcode",
                            "selected": true,
                            "height": 2160
                          },
                          {
                            "id": "891",
                            "streamType": 2,
                            "codec": "eac3",
                            "decision": "copy",
                            "selected": true
                          }
                        ]
                      }
                    ]
                  }
                ],
                "User": {
                  "id": "2002",
                  "title": "yuki"
                },
                "Player": {
                  "machineIdentifier": "player-web-2",
                  "product": "Plex Web",
                  "platform": "Chrome",
                  "device": "Linux",
                  "title": "Chrome",
                  "address": "203.0.113.42",
                  "local": false,
                  "state": "paused"
                },
                "Session": {
                  "id": "session-tv-hw-1",
                  "bandwidth": 4500,
                  "location": "wan"
                },
                "TranscodeSession": {
                  "videoDecision": "transcode",
                  "audioDecision": "copy",
                  "subtitleDecision": "burn",
                  "container": "mp4",
                  "videoCodec": "h264",
                  "audioCodec": "aac",
                  "transcodeHwRequested": true,
                  "transcodeHwFullPipeline": true,
                  "transcodeHwDecoding": "vaapi",
                  "transcodeHwEncoding": "vaapi",
                  "progress": 42.5,
                  "speed": 3.4
                }
              }
            ]
          }
        }
        """.trimIndent()

    val episodeSwTranscodeJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "13",
                "ratingKey": "54322",
                "type": "episode",
                "title": "Felina",
                "parentTitle": "Season 5",
                "grandparentTitle": "Breaking Bad",
                "duration": 3300000,
                "viewOffset": 100000,
                "User": {
                  "id": "3003",
                  "title": "managed_kid"
                },
                "Player": {
                  "machineIdentifier": "player-android-1",
                  "product": "Plex for Android",
                  "local": false,
                  "state": "buffering"
                },
                "Session": {
                  "id": "session-tv-sw-1",
                  "location": "wan"
                },
                "TranscodeSession": {
                  "videoDecision": "transcode",
                  "audioDecision": "transcode",
                  "container": "mkv",
                  "videoCodec": "h264",
                  "audioCodec": "opus",
                  "transcodeHwRequested": true,
                  "transcodeHwFullPipeline": false
                }
              }
            ]
          }
        }
        """.trimIndent()

    val directStreamRemuxJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "14",
                "ratingKey": "60001",
                "type": "movie",
                "title": "Princess Mononoke",
                "duration": 8040000,
                "viewOffset": 400000,
                "Media": [
                  {
                    "container": "mkv",
                    "videoCodec": "h264",
                    "audioCodec": "aac",
                    "videoResolution": "1080"
                  }
                ],
                "User": {
                  "id": "2002",
                  "title": "yuki"
                },
                "Player": {
                  "machineIdentifier": "player-ios-1",
                  "product": "Plex for iOS",
                  "local": true,
                  "state": "playing"
                },
                "Session": {
                  "id": "session-remux-1",
                  "location": "lan"
                },
                "TranscodeSession": {
                  "videoDecision": "copy",
                  "audioDecision": "copy",
                  "container": "mp4",
                  "videoCodec": "h264",
                  "audioCodec": "aac",
                  "transcodeHwRequested": false,
                  "progress": 88.0,
                  "speed": 12.5
                }
              }
            ]
          }
        }
        """.trimIndent()

    val musicTrackJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "15",
                "ratingKey": "77001",
                "type": "track",
                "title": "Resonance",
                "parentTitle": "Odyssey",
                "grandparentTitle": "HOME",
                "parentIndex": 1,
                "index": 5,
                "year": 2014,
                "duration": 212000,
                "viewOffset": 90000,
                "thumb": "/library/metadata/77000/thumb/1700000200",
                "librarySectionID": "3",
                "Media": [
                  {
                    "bitrate": 980,
                    "container": "flac",
                    "audioCodec": "flac",
                    "Part": [
                      {
                        "container": "flac",
                        "decision": "directplay",
                        "Stream": [
                          {
                            "streamType": 2,
                            "codec": "flac",
                            "decision": "directplay",
                            "selected": true,
                            "channels": 2
                          }
                        ]
                      }
                    ]
                  }
                ],
                "User": {
                  "id": "2002",
                  "title": "yuki"
                },
                "Player": {
                  "machineIdentifier": "plexamp-pixel",
                  "product": "Plexamp",
                  "platform": "Android",
                  "device": "Pixel 9",
                  "title": "Pixel 9",
                  "local": false,
                  "state": "playing"
                },
                "Session": {
                  "id": "session-music-1",
                  "bandwidth": 1020,
                  "location": "wan"
                }
              }
            ]
          }
        }
        """.trimIndent()

    val liveTvMissingFieldsJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "16",
                "ratingKey": "live-99",
                "type": "clip",
                "title": "Evening News",
                "live": "1",
                "channelIdentifier": "1.1",
                "channelCallSign": "NHK-G",
                "channelTitle": "NHK General",
                "viewOffset": 45000,
                "User": {
                  "id": "2002",
                  "title": "yuki"
                },
                "Player": {
                  "machineIdentifier": "player-shield-1",
                  "product": "Plex for Android (TV)",
                  "local": 1,
                  "state": "playing"
                }
              }
            ]
          }
        }
        """.trimIndent()

    val localOwnerSessionJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "sessionKey": "17",
                "ratingKey": "12345",
                "type": "movie",
                "title": "Akira",
                "duration": 7440000,
                "viewOffset": 500000,
                "User": {
                  "id": "1",
                  "title": "Local Admin"
                },
                "Player": {
                  "machineIdentifier": "player-mac-1",
                  "product": "Plex Desktop",
                  "local": true,
                  "state": "playing"
                },
                "Session": {
                  "id": "session-owner-1",
                  "location": "lan"
                }
              }
            ]
          }
        }
        """.trimIndent()

    val accountsJson =
        """
        {
          "MediaContainer": {
            "size": 4,
            "Account": [
              { "id": 0, "name": "" },
              { "id": 1, "name": "kerrlab" },
              { "id": 2002, "name": "yuki" },
              { "id": 3003, "name": "managed_kid" }
            ]
          }
        }
        """.trimIndent()

    val plexTvUserJson =
        """
        {
          "id": 99887766,
          "uuid": "owner-uuid-1",
          "username": "kerrlab",
          "title": "Alex Kerr",
          "email": "alex@kerrlab.app",
          "thumb": "https://plex.tv/users/owner/avatar"
        }
        """.trimIndent()

    val plexTvFriendsJson =
        """
        [
          {
            "id": 2002,
            "uuid": "yuki-uuid-2",
            "username": "yuki",
            "title": "Yuki Tanaka",
            "email": "yuki@example.com",
            "thumb": "https://plex.tv/users/yuki/avatar"
          }
        ]
        """.trimIndent()

    val librarySectionsJson =
        """
        {
          "MediaContainer": {
            "size": 5,
            "Directory": [
              { "key": "1", "title": "Movies", "type": "movie", "uuid": "sec-1" },
              { "key": "2", "title": "TV Shows", "type": "show", "uuid": "sec-2" },
              { "key": "3", "title": "Music", "type": "artist", "uuid": "sec-3" },
              { "key": "4", "title": "Photos", "type": "photo", "uuid": "sec-4" },
              { "key": "5", "title": "Home Videos", "type": "other", "uuid": "sec-5" }
            ]
          }
        }
        """.trimIndent()

    val libraryCountTotalSizeJson =
        """
        {
          "MediaContainer": {
            "size": 0,
            "totalSize": 412,
            "offset": 0
          }
        }
        """.trimIndent()

    val libraryCountFallbackSizeJson =
        """
        {
          "MediaContainer": {
            "size": 87,
            "offset": 0
          }
        }
        """.trimIndent()

    val libraryItemsPageJson =
        """
        {
          "MediaContainer": {
            "size": 2,
            "totalSize": 412,
            "offset": 20,
            "Metadata": [
              {
                "ratingKey": "12345",
                "librarySectionID": 1,
                "type": "movie",
                "title": "Spirited Away",
                "year": 2001,
                "thumb": "/library/metadata/12345/thumb/1700000000",
                "duration": 7500000,
                "addedAt": 1700000000,
                "Media": [
                  {
                    "videoResolution": "1080",
                    "videoCodec": "h264",
                    "audioCodec": "dts",
                    "Part": [
                      { "size": 10000000000 },
                      { "size": 1718750000 }
                    ]
                  }
                ]
              },
              {
                "ratingKey": "54321",
                "librarySectionID": "2",
                "type": "episode",
                "title": "Ozymandias",
                "parentTitle": "Season 5",
                "grandparentTitle": "Breaking Bad",
                "year": 2013,
                "thumb": "/library/metadata/54321/thumb/1700000100",
                "duration": 2880000,
                "addedAt": 1700000500,
                "Media": [
                  {
                    "videoResolution": "4k",
                    "Part": [
                      {
                        "size": 8500000000,
                        "Stream": [
                          { "streamType": 1, "codec": "hevc", "selected": true },
                          { "streamType": 2, "codec": "eac3", "selected": true }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    val movieMetadataDetailsJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "ratingKey": "12345",
                "type": "movie",
                "title": "Spirited Away",
                "year": 2001,
                "originallyAvailableAt": "2001-07-20",
                "duration": 7500000,
                "contentRating": "PG",
                "studio": "Studio Ghibli",
                "summary": "A young girl wanders into a world ruled by spirits.",
                "librarySectionID": 1,
                "librarySectionTitle": "Movies",
                "addedAt": 1700000000,
                "thumb": "/library/metadata/12345/thumb/1700000000",
                "art": "/library/metadata/12345/art/1700000000",
                "Genre": [
                  { "id": 1, "tag": "Animation" },
                  { "id": 2, "tag": "Fantasy" }
                ],
                "Director": [
                  { "id": 10, "tag": "Hayao Miyazaki" }
                ],
                "Media": [
                  {
                    "id": 9001,
                    "duration": 7500000,
                    "bitrate": 14000,
                    "videoResolution": "1080",
                    "videoProfile": "high",
                    "container": "mkv",
                    "videoCodec": "h264",
                    "audioCodec": "dts",
                    "audioChannels": 6,
                    "selected": true,
                    "Part": [
                      {
                        "id": 9002,
                        "size": 11718750000,
                        "container": "mkv",
                        "selected": true,
                        "Stream": [
                          { "streamType": 1, "codec": "h264", "profile": "high", "height": 1080, "bitrate": 12500, "selected": true },
                          { "streamType": 2, "codec": "dts", "channels": 6, "selected": true },
                          { "streamType": 3, "codec": "srt", "language": "English", "languageTag": "en" },
                          { "streamType": 3, "codec": "ass", "language": "Japanese", "languageTag": "ja" },
                          { "streamType": 3, "codec": "srt", "language": "English", "languageTag": "en" }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    val episodeMetadataDetailsJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "ratingKey": "54321",
                "parentRatingKey": "54300",
                "grandparentRatingKey": "54000",
                "type": "episode",
                "title": "Ozymandias",
                "parentTitle": "Season 5",
                "grandparentTitle": "Breaking Bad",
                "parentIndex": 5,
                "index": 14,
                "year": 2013,
                "originallyAvailableAt": "2013-09-15",
                "duration": 2880000,
                "contentRating": "TV-MA",
                "summary": "Everyone copes with radically changed circumstances.",
                "librarySectionID": "2",
                "librarySectionTitle": "TV Shows",
                "addedAt": 1700000500,
                "thumb": "/library/metadata/54321/thumb/1700000100",
                "parentThumb": "/library/metadata/54300/thumb/1700000050",
                "grandparentThumb": "/library/metadata/54000/thumb/1700000010",
                "art": "/library/metadata/54000/art/1700000010",
                "Director": [
                  { "tag": "Rian Johnson" }
                ],
                "Media": [
                  {
                    "bitrate": 8200,
                    "videoResolution": "4k",
                    "container": "mkv",
                    "videoCodec": "hevc",
                    "audioCodec": "eac3",
                    "audioChannels": 6,
                    "Part": [
                      {
                        "size": 8500000000,
                        "Stream": [
                          { "streamType": 1, "codec": "hevc", "profile": "main 10", "height": 2160 },
                          { "streamType": 2, "codec": "eac3", "channels": 6 },
                          { "streamType": 3, "languageCode": "eng", "displayTitle": "English (SRT)" }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    val seasonMetadataDetailsJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "ratingKey": "54300",
                "parentRatingKey": "54000",
                "type": "season",
                "title": "Season 5",
                "parentTitle": "Breaking Bad",
                "index": 5,
                "year": 2012,
                "leafCount": 16,
                "viewedLeafCount": 14,
                "summary": "The fifth and final season of Breaking Bad.",
                "librarySectionID": "2",
                "librarySectionTitle": "TV Shows",
                "thumb": "/library/metadata/54300/thumb/1700000050",
                "art": "/library/metadata/54000/art/1700000010"
              }
            ]
          }
        }
        """.trimIndent()

    val showMetadataDetailsJson =
        """
        {
          "MediaContainer": {
            "size": 1,
            "Metadata": [
              {
                "ratingKey": "54000",
                "type": "show",
                "title": "Breaking Bad",
                "year": 2008,
                "originallyAvailableAt": "2008-01-20",
                "contentRating": "TV-MA",
                "studio": "AMC",
                "summary": "A chemistry teacher turns to manufacturing methamphetamine.",
                "librarySectionID": "2",
                "librarySectionTitle": "TV Shows",
                "addedAt": 1699000000,
                "childCount": 5,
                "leafCount": 62,
                "viewedLeafCount": 40,
                "thumb": "/library/metadata/54000/thumb/1700000010",
                "art": "/library/metadata/54000/art/1700000010",
                "Genre": [
                  { "tag": "Crime" },
                  { "tag": "Drama" }
                ]
              }
            ]
          }
        }
        """.trimIndent()

    val emptyMetadataDetailsJson =
        """
        {
          "MediaContainer": {
            "size": 0,
            "Metadata": []
          }
        }
        """.trimIndent()
}
