package app.hononeko.miru.adapter

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal val TEST_PNG_BYTES =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
    )

/**
 * Real loopback Netty HTTP + WebSocket Plex test server for end-to-end multi-server integration tests.
 */
internal class EmbeddedPlexTestServer(
    val token: String = "it-plex-token-secret",
    val machineId: String = "it-plex-machine-1",
    val serverName: String = "IT Home Plex",
) : AutoCloseable {
    val outageEnabled = AtomicBoolean(false)
    val wsConnectionCount = AtomicInteger(0)
    val terminatedSessions = CopyOnWriteArrayList<Pair<String, String>>()

    private val activeSessionsJson = AtomicReference("""{"MediaContainer":{"size":0,"Metadata":[]}}""")
    private val wsClients = CopyOnWriteArrayList<DefaultWebSocketServerSession>()

    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                get("/") { call.respondIfAuthed(this@EmbeddedPlexTestServer) { rootJson() } }
                get("/status/sessions") { call.respondIfAuthed(this@EmbeddedPlexTestServer) { activeSessionsJson.get() } }
                post("/status/sessions/terminate") { handleTerminate(call) }
                get("/accounts") { call.respondIfAuthed(this@EmbeddedPlexTestServer) { PLEX_IT_ACCOUNTS_JSON } }
                get("/api/v2/user") { call.respondIfAuthed(this@EmbeddedPlexTestServer) { PLEX_IT_OWNER_JSON } }
                get("/api/users") { handleXmlRoute(call, PLEX_IT_USERS_XML) }
                get("/api/servers/{mid}/shared_servers") { handleXmlRoute(call, PLEX_IT_SHARED_SERVERS_XML) }
                get("/library/sections") { call.respondIfAuthed(this@EmbeddedPlexTestServer) { PLEX_IT_SECTIONS_JSON } }
                get("/library/sections/{key}/all") { handleSectionItems(call) }
                get("/library/metadata/{key}") { handleMetadata(call) }
                get("/library/metadata/{key}/thumb") { handleArtwork(call) }
                get("/photo/:/transcode") { handleArtwork(call) }
                webSocket("/:/websockets/notifications") { handleWebSocket(this) }
            }
        }.start(wait = false)

    val port: Int = runBlocking { server.engine.resolvedConnectors().first().port }
    val baseUrl: String = "http://127.0.0.1:$port"

    fun setActiveSessionsJson(json: String) {
        activeSessionsJson.set(json)
    }

    suspend fun pushPlayingNotification(
        sessionKey: String,
        ratingKey: String,
        state: String,
        viewOffsetMs: Long = 10_000L,
    ) {
        val frame =
            """{"NotificationContainer":{"type":"playing","size":1,""" +
                """"PlaySessionStateNotification":[{"sessionKey":"$sessionKey","ratingKey":"$ratingKey",""" +
                """"state":"$state","viewOffset":$viewOffsetMs}]}}"""
        wsClients.forEach { ws -> runCatching { ws.send(Frame.Text(frame)) } }
    }

    suspend fun dropAllWebSockets() {
        wsClients.toList().forEach { ws ->
            runCatching { ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "simulated drop")) }
        }
    }

    suspend fun waitForWsConnected(
        minConnections: Int = 1,
        timeoutMs: Long = 10_000L,
    ) {
        withTimeout(timeoutMs) {
            while (wsConnectionCount.get() < minConnections || wsClients.isEmpty()) {
                delay(40)
            }
        }
    }

    override fun close() {
        server.stop(0, 1_000)
    }

    private fun rootJson(): String =
        """{"MediaContainer":{"machineIdentifier":"$machineId","friendlyName":"$serverName","version":"1.41.2","platform":"Linux"}}"""

    private suspend fun handleTerminate(call: RoutingCall) {
        if (!checkAuth(call)) return
        val sessionId = call.request.queryParameters["sessionId"].orEmpty()
        val reason = call.request.queryParameters["reason"].orEmpty()
        terminatedSessions += (sessionId to reason)
        call.respondText("", status = HttpStatusCode.OK)
    }

    private suspend fun handleXmlRoute(
        call: RoutingCall,
        xml: String,
    ) {
        if (!checkAuth(call)) return
        if (outageEnabled.get()) {
            call.respondText("Down", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        call.respondText(xml, ContentType.Application.Xml)
    }

    private suspend fun handleSectionItems(call: RoutingCall) {
        if (!checkAuth(call)) return
        if (outageEnabled.get()) {
            call.respondText("Down", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        val key = call.parameters["key"]
        val start = call.request.queryParameters["X-Plex-Container-Start"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["X-Plex-Container-Size"]?.toIntOrNull() ?: 50
        val allItems =
            when (key) {
                "1" -> PLEX_IT_MOVIE_ITEMS
                "2" -> PLEX_IT_EPISODE_ITEMS
                else -> emptyList()
            }
        val slice = if (size == 0) emptyList() else allItems.drop(start).take(size)
        val body =
            """{"MediaContainer":{"size":${slice.size},"totalSize":${allItems.size},"offset":$start,""" +
                """"Metadata":[${slice.joinToString(",")}]}}"""
        call.respondText(body, ContentType.Application.Json)
    }

    private suspend fun handleMetadata(call: RoutingCall) {
        if (!checkAuth(call)) return
        val item = PLEX_IT_METADATA_BY_KEY[call.parameters["key"]]
        if (item == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondText("""{"MediaContainer":{"size":1,"Metadata":[$item]}}""", ContentType.Application.Json)
        }
    }

    private suspend fun handleArtwork(call: RoutingCall) {
        if (!checkAuth(call)) return
        call.respondBytes(TEST_PNG_BYTES, ContentType.Image.PNG)
    }

    private suspend fun handleWebSocket(session: DefaultWebSocketServerSession) {
        val provided = session.call.request.headers["X-Plex-Token"] ?: session.call.request.queryParameters["X-Plex-Token"]
        if (provided != token) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }
        wsClients += session
        wsConnectionCount.incrementAndGet()
        try {
            do {
                val frame = session.incoming.receiveCatching()
            } while (frame.isSuccess)
        } finally {
            wsClients.remove(session)
        }
    }

    internal suspend fun checkAuth(call: RoutingCall): Boolean {
        val provided = call.request.headers["X-Plex-Token"]
        if (provided != token) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return false
        }
        return true
    }

    private suspend fun RoutingCall.respondIfAuthed(
        owner: EmbeddedPlexTestServer,
        body: () -> String,
    ) {
        if (!owner.checkAuth(this)) return
        if (owner.outageEnabled.get()) {
            respondText("Down", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        respondText(body(), ContentType.Application.Json)
    }
}

/**
 * Real loopback Netty HTTP + WebSocket Jellyfin test server for end-to-end multi-server integration tests.
 */
internal class EmbeddedJellyfinTestServer(
    val apiKey: String = "it-jf-api-key-secret",
    val machineId: String = "it-jf-machine-1",
    val serverName: String = "IT Studio Jellyfin",
) : AutoCloseable {
    val outageEnabled = AtomicBoolean(false)
    val wsConnectionCount = AtomicInteger(0)
    val sessionsStartCount = AtomicInteger(0)
    val keepAliveAckCount = AtomicInteger(0)
    val receivedMessages = CopyOnWriteArrayList<Pair<String, String>>()
    val stoppedSessions = CopyOnWriteArrayList<String>()

    private val activeSessionsJson = AtomicReference("[]")
    private val wsClients = CopyOnWriteArrayList<DefaultWebSocketServerSession>()

    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                get("/System/Info") { respondJson(call) { systemInfoJson() } }
                get("/Sessions") { respondJson(call) { activeSessionsJson.get() } }
                post("/Sessions/{id}/Message") { handleMessage(call) }
                post("/Sessions/{id}/Playing/Stop") { handleStop(call) }
                get("/Users") { respondJson(call) { JF_IT_USERS_JSON } }
                get("/Library/VirtualFolders") { respondJson(call) { JF_IT_FOLDERS_JSON } }
                get("/Items") { handleItemsQuery(call) }
                get("/Items/{id}") { handleItemDetails(call) }
                get("/Items/{id}/Ancestors") { handleAncestors(call) }
                get("/Items/{id}/Images/{type}") { handleImage(call) }
                get("/Users/{id}/Images/{type}") { handleImage(call) }
                webSocket("/socket") { handleWebSocket(this) }
            }
        }.start(wait = false)

    val port: Int = runBlocking { server.engine.resolvedConnectors().first().port }
    val baseUrl: String = "http://127.0.0.1:$port"

    fun setActiveSessionsJson(json: String) {
        activeSessionsJson.set(json)
    }

    suspend fun pushSessionsUpdate(sessionsJson: String = activeSessionsJson.get()) {
        val frame = """{"MessageType":"Sessions","Data":$sessionsJson}"""
        wsClients.forEach { ws -> runCatching { ws.send(Frame.Text(frame)) } }
    }

    suspend fun dropAllWebSockets() {
        wsClients.toList().forEach { ws ->
            runCatching { ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "simulated drop")) }
        }
    }

    suspend fun waitForWsConnected(
        minConnections: Int = 1,
        timeoutMs: Long = 10_000L,
    ) {
        withTimeout(timeoutMs) {
            while (wsConnectionCount.get() < minConnections || wsClients.isEmpty()) {
                delay(40)
            }
        }
    }

    override fun close() {
        server.stop(0, 1_000)
    }

    private fun systemInfoJson(): String =
        """{"Id":"$machineId","ServerName":"$serverName","Version":"10.10.3","OperatingSystem":"Linux"}"""

    private suspend fun checkAuth(call: RoutingCall): Boolean {
        val authHeader = call.request.headers[HttpHeaders.Authorization].orEmpty()
        if (!authHeader.startsWith("MediaBrowser ") || !authHeader.contains("""Token="$apiKey"""")) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return false
        }
        return true
    }

    private suspend fun respondJson(
        call: RoutingCall,
        body: () -> String,
    ) {
        if (!checkAuth(call)) return
        if (outageEnabled.get()) {
            call.respondText("Down", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        call.respondText(body(), ContentType.Application.Json)
    }

    private suspend fun handleMessage(call: RoutingCall) {
        if (!checkAuth(call)) return
        val id = call.parameters["id"].orEmpty()
        val body = call.receiveText()
        receivedMessages += (id to body)
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    private suspend fun handleStop(call: RoutingCall) {
        if (!checkAuth(call)) return
        val id = call.parameters["id"].orEmpty()
        stoppedSessions += id
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    private suspend fun handleItemsQuery(call: RoutingCall) {
        if (!checkAuth(call)) return
        if (outageEnabled.get()) {
            call.respondText("Down", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        val idsParam = call.request.queryParameters["Ids"]
        if (idsParam != null) {
            val matched =
                idsParam
                    .split(',')
                    .map { it.trim() }
                    .mapNotNull { JF_IT_ITEMS_BY_ID[it] }
            call.respondText(
                """{"TotalRecordCount":${matched.size},"StartIndex":0,"Items":[${matched.joinToString(",")}]}""",
                ContentType.Application.Json,
            )
            return
        }
        val parentId = call.request.queryParameters["ParentId"]
        val start = call.request.queryParameters["StartIndex"]?.toIntOrNull() ?: 0
        val limit = call.request.queryParameters["Limit"]?.toIntOrNull() ?: 50
        val includeType = call.request.queryParameters["IncludeItemTypes"]
        val items =
            when (parentId) {
                "jf-lib-movies" -> JF_IT_MOVIE_ITEMS
                "jf-lib-shows" ->
                    when (includeType) {
                        "Series" -> listOf(JF_IT_SHOW_ITEM)
                        "Season" -> listOf(JF_IT_SEASON_ITEM)
                        else -> JF_IT_EPISODE_ITEMS
                    }
                else -> emptyList()
            }
        val slice = if (limit == 0) emptyList() else items.drop(start).take(limit)
        call.respondText(
            """{"TotalRecordCount":${items.size},"StartIndex":$start,"Items":[${slice.joinToString(",")}]}""",
            ContentType.Application.Json,
        )
    }

    private suspend fun handleItemDetails(call: RoutingCall) {
        if (!checkAuth(call)) return
        // Real Jellyfin servers reject GET /Items/{id} without user context (405/400); details must use GET /Items?Ids=...
        call.respondText("Use /Items?Ids=...", status = HttpStatusCode.MethodNotAllowed)
    }

    private suspend fun handleAncestors(call: RoutingCall) {
        if (!checkAuth(call)) return
        val id = call.parameters["id"]
        val ancestors =
            when (id) {
                "jf-m1", "jf-m2" -> """[{"Id":"jf-lib-movies","Name":"JF Movies","Type":"CollectionFolder"}]"""
                "jf-ep1", "jf-season1", "jf-show1" -> """[{"Id":"jf-lib-shows","Name":"JF Shows","Type":"CollectionFolder"}]"""
                else -> "[]"
            }
        call.respondText(ancestors, ContentType.Application.Json)
    }

    private suspend fun handleImage(call: RoutingCall) {
        if (!checkAuth(call)) return
        call.respondBytes(TEST_PNG_BYTES, ContentType.Image.PNG)
    }

    private suspend fun handleWebSocket(session: DefaultWebSocketServerSession) {
        if (session.call.request.queryParameters["api_key"] != apiKey) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }
        wsClients += session
        wsConnectionCount.incrementAndGet()
        try {
            session.send(Frame.Text("""{"MessageType":"ForceKeepAlive","Data":30}"""))
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    if (text.contains("SessionsStart")) sessionsStartCount.incrementAndGet()
                    if (text.contains(""""MessageType":"KeepAlive"""")) keepAliveAckCount.incrementAndGet()
                }
            }
        } finally {
            wsClients.remove(session)
        }
    }
}

private const val PLEX_IT_ACCOUNTS_JSON =
    """{"MediaContainer":{"Account":[{"id":1,"name":"alice"},{"id":2002,"name":"bob"}]}}"""

private const val PLEX_IT_OWNER_JSON =
    """{"id":1001,"username":"alice","title":"Alice Plex","email":"alice@plex.test"}"""

private const val PLEX_IT_USERS_XML =
    """<MediaContainer><User id="2002" username="bob" title="Bob Plex" email="bob@plex.test"/></MediaContainer>"""

private const val PLEX_IT_SHARED_SERVERS_XML =
    """<MediaContainer><SharedServer userID="2002" allLibraries="0"><Section key="1" shared="1"/></SharedServer></MediaContainer>"""

private const val PLEX_IT_SECTIONS_JSON =
    """{"MediaContainer":{"Directory":[{"key":"1","title":"Plex Movies","type":"movie"},{"key":"2","title":"Plex TV","type":"show"}]}}"""

private val PLEX_IT_MOVIE_ITEMS =
    listOf(
        """{"ratingKey":"pm-1","type":"movie","title":"Spirited Away","year":2001,"duration":7500000,""" +
            """"librarySectionID":"1","librarySectionTitle":"Plex Movies","thumb":"/library/metadata/pm-1/thumb",""" +
            """"Media":[{"videoResolution":"4k","videoCodec":"hevc","audioCodec":"eac3",""" +
            """"container":"mkv","Part":[{"size":1200000000}]}]}""",
    )

private val PLEX_IT_EPISODE_ITEMS =
    listOf(
        """{"ratingKey":"pe-1","type":"episode","title":"Good News","parentTitle":"Season 1","grandparentTitle":"Severance",""" +
            """"grandparentRatingKey":"ps-1","year":2022,"duration":3400000,"librarySectionID":"2","librarySectionTitle":"Plex TV",""" +
            """"Media":[{"videoResolution":"1080","videoCodec":"h264","audioCodec":"aac","Part":[{"size":600000000}]}]}""",
    )

private val PLEX_IT_METADATA_BY_KEY =
    mapOf(
        "pm-1" to PLEX_IT_MOVIE_ITEMS.first(),
        "pe-1" to PLEX_IT_EPISODE_ITEMS.first(),
    )

private const val JF_IT_USERS_JSON =
    """[{"Id":"jf-u1","Name":"alice","PrimaryImageTag":"av1","Policy":{"IsAdministrator":true,"EnableAllFolders":true}},""" +
        """{"Id":"jf-u2","Name":"diana","Policy":{"IsAdministrator":false,"EnableAllFolders":false,"EnabledFolders":["jf-lib-movies"]}}]"""

private const val JF_IT_FOLDERS_JSON =
    """[{"ItemId":"jf-lib-movies","Name":"JF Movies","CollectionType":"movies"},""" +
        """{"ItemId":"jf-lib-shows","Name":"JF Shows","CollectionType":"tvshows"}]"""

private val JF_IT_MOVIE_ITEMS =
    listOf(
        """{"Id":"jf-m1","Name":"Arrival","Type":"Movie","ProductionYear":2016,"RunTimeTicks":69000000000,""" +
            """"ParentId":"jf-lib-movies","ImageTags":{"Primary":"p1"},"BackdropImageTags":["b1"],""" +
            """"MediaSources":[{"Id":"ms-jfm1","Container":"mkv","Size":1500000000,""" +
            """"MediaStreams":[{"Type":"Video","Codec":"hevc","Width":3840,"Height":2160},{"Type":"Audio","Codec":"truehd"}]}]}""",
    )

private const val JF_IT_SHOW_ITEM =
    """{"Id":"jf-show1","Name":"The Expanse","Type":"Series","ChildCount":6,"RecursiveItemCount":62,"ParentId":"jf-lib-shows"}"""

private const val JF_IT_SEASON_ITEM =
    """{"Id":"jf-season1","Name":"Season 1","Type":"Season","IndexNumber":1,""" +
        """"SeriesId":"jf-show1","SeriesName":"The Expanse","ParentId":"jf-lib-shows"}"""

private val JF_IT_EPISODE_ITEMS =
    listOf(
        """{"Id":"jf-ep1","Name":"Dulcinea","Type":"Episode","SeriesId":"jf-show1","SeriesName":"The Expanse",""" +
            """"SeasonId":"jf-season1","SeasonName":"Season 1","ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":27000000000,""" +
            """"ParentId":"jf-lib-shows","ImageTags":{"Primary":"ep-p1"},""" +
            """"MediaSources":[{"Id":"ms-jfep1","Container":"mkv","Size":900000000,""" +
            """"MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":1080},{"Type":"Audio","Codec":"aac"}]}]}""",
    )

private val JF_IT_ITEMS_BY_ID =
    mapOf(
        "jf-m1" to JF_IT_MOVIE_ITEMS.first(),
        "jf-show1" to JF_IT_SHOW_ITEM,
        "jf-season1" to JF_IT_SEASON_ITEM,
        "jf-ep1" to JF_IT_EPISODE_ITEMS.first(),
    )

internal fun plexActiveMovieSessionJson(
    sessionKey: String = "pk-live-1",
    sessionId: String = "ps-live-1",
    state: String = "playing",
): String =
    """{"MediaContainer":{"size":1,"Metadata":[{""" +
        """"sessionKey":"$sessionKey","ratingKey":"pm-1","type":"movie","title":"Spirited Away","year":2001,""" +
        """"duration":7500000,"viewOffset":1200000,"thumb":"/library/metadata/pm-1/thumb","librarySectionID":"1",""" +
        """"User":{"id":"1","title":"alice"},""" +
        """"Player":{"machineIdentifier":"appletv-1","product":"Apple TV","state":"$state","local":true,"address":"192.168.1.40"},""" +
        """"Session":{"id":"$sessionId","bandwidth":12000,"location":"lan"},""" +
        """"Media":[{"videoResolution":"4k","videoCodec":"hevc","audioCodec":"eac3",""" +
        """"container":"mkv","Part":[{"decision":"directplay"}]}]""" +
        """}]}}"""

internal fun jellyfinActiveEpisodeSessionJson(
    sessionId: String = "jf-live-1",
    paused: Boolean = false,
): String =
    """[{""" +
        """"Id":"$sessionId","UserId":"jf-u1","UserName":"alice","UserPrimaryImageTag":"av1",""" +
        """"Client":"Jellyfin Web","DeviceName":"Firefox","DeviceId":"jf-dev-1","RemoteEndPoint":"203.0.113.8",""" +
        """"PlayState":{"PositionTicks":6000000000,"IsPaused":$paused,"PlayMethod":"Transcode"},""" +
        """"TranscodingInfo":{"VideoCodec":"h264","AudioCodec":"aac","Container":"ts",""" +
        """"IsVideoDirect":false,"IsAudioDirect":false,"HardwareAccelerationType":"vaapi",""" +
        """"Bitrate":6500000,"CompletionPercentage":35.0},""" +
        """"NowPlayingItem":{"Id":"jf-ep1","Name":"Dulcinea","Type":"Episode","SeriesId":"jf-show1",""" +
        """"SeriesName":"The Expanse","SeasonId":"jf-season1","SeasonName":"Season 1",""" +
        """"ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":27000000000,""" +
        """"ImageTags":{"Primary":"ep-p1"},"MediaStreams":[""" +
        """{"Type":"Video","Codec":"hevc","Width":1920,"Height":1080},{"Type":"Audio","Codec":"dts"}]}""" +
        """}]"""
