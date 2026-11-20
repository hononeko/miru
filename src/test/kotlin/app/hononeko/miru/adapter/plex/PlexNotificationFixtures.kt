package app.hononeko.miru.adapter.plex

internal object PlexNotificationFixtures {
    val playingFrame =
        """
        {
          "NotificationContainer": {
            "type": "playing",
            "size": 4,
            "PlaySessionStateNotification": [
              {
                "sessionKey": "23",
                "clientIdentifier": "client-abc",
                "guid": "",
                "ratingKey": "12345",
                "url": "",
                "key": "/library/metadata/12345",
                "viewOffset": 60000,
                "playQueueItemID": 1001,
                "state": "playing",
                "transcodeSession": "t-1"
              },
              { "sessionKey": 24, "ratingKey": 222, "viewOffset": 1000, "state": "paused" },
              { "sessionKey": "25", "state": "buffering" },
              { "sessionKey": "26", "ratingKey": "333", "viewOffset": 5400000, "state": "stopped" },
              { "sessionKey": "27", "state": "teleporting" }
            ]
          }
        }
        """.trimIndent()

    val timelineFrame =
        """
        {
          "NotificationContainer": {
            "type": "timeline",
            "size": 2,
            "TimelineEntry": [
              {
                "identifier": "com.plexapp.plugins.library",
                "sectionID": "1",
                "itemID": "12345",
                "type": 1,
                "title": "Spirited Away",
                "state": 5,
                "metadataState": "created",
                "updatedAt": 1700000000
              },
              { "identifier": "com.plexapp.plugins.library", "sectionID": 2, "itemID": 678, "type": 4, "state": 9 }
            ]
          }
        }
        """.trimIndent()

    val activityFrame =
        """
        {
          "NotificationContainer": {
            "type": "activity",
            "size": 1,
            "ActivityNotification": [
              {
                "event": "updated",
                "uuid": "a1b2c3",
                "Activity": {
                  "uuid": "a1b2c3",
                  "type": "library.update.section",
                  "cancellable": false,
                  "userID": 1,
                  "title": "Scanning Movies",
                  "subtitle": "Processing files",
                  "progress": 42
                }
              }
            ]
          }
        }
        """.trimIndent()

    val statusFrame =
        """
        {"NotificationContainer":{"type":"status","size":1,"StatusNotification":[{"title":"Library scan complete"}]}}
        """.trimIndent()

    val transcodeFrame =
        """
        {"NotificationContainer":{"type":"transcodeSession.update","size":1,"TranscodeSession":[{"key":"t-1"}]}}
        """.trimIndent()

    fun playing(
        sessionKey: String,
        state: String = "playing",
    ): String =
        """{"NotificationContainer":{"type":"playing","size":1,""" +
            """"PlaySessionStateNotification":[{"sessionKey":"$sessionKey","state":"$state","viewOffset":0}]}}"""
}
