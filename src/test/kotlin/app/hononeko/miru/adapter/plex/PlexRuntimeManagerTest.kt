package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.ActiveStreamSnapshot
import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.LibraryItemsPage
import app.hononeko.miru.adapter.LibrarySectionInfo
import app.hononeko.miru.adapter.MediaItemDetails
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.adapter.MediaServerEvent
import app.hononeko.miru.adapter.MediaType
import app.hononeko.miru.adapter.MediaUser
import app.hononeko.miru.adapter.MediaUserLibraryAccess
import app.hononeko.miru.adapter.PlaybackState
import app.hononeko.miru.adapter.ServerIdentity
import app.hononeko.miru.auth.CredentialCrypto
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.PlexCredentialSource
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

private const val TEST_ENC_KEY = "runtime-manager-test-encryption-key-32c"

private class DelegatingTestStubAdapter(
    override val serverType: String = "plex",
) : MediaServerAdapter {
    var healthResult: Either<MiruError, ServerIdentity> =
        ServerIdentity(machineIdentifier = "mach-stub", friendlyName = "Stub Plex", version = "1.0").right()

    override suspend fun checkHealth(): Either<MiruError, ServerIdentity> = healthResult

    override suspend fun fetchActiveSessions(): Either<MiruError, List<ActiveStreamSnapshot>> = emptyList<ActiveStreamSnapshot>().right()

    override suspend fun fetchUsers(): Either<MiruError, List<MediaUser>> = emptyList<MediaUser>().right()

    override suspend fun fetchLibrarySections(): Either<MiruError, List<LibrarySectionInfo>> = emptyList<LibrarySectionInfo>().right()

    override suspend fun fetchLibraryCount(
        sectionKey: String,
        mediaTypeCode: Int?,
    ): Either<MiruError, Int> = 42.right()

    override suspend fun fetchLibraryItems(
        sectionKey: String,
        start: Int,
        size: Int,
        mediaTypeCode: Int?,
    ): Either<MiruError, LibraryItemsPage> = LibraryItemsPage(totalSize = 0, offset = start, items = emptyList()).right()

    override suspend fun fetchMediaDetails(ratingKey: String): Either<MiruError, MediaItemDetails> =
        MediaItemDetails(ratingKey = ratingKey, mediaType = MediaType.MOVIE, title = "Stub Movie").right()

    override suspend fun fetchArtwork(
        path: String,
        width: Int?,
        height: Int?,
    ): Either<MiruError, ArtworkData> = ArtworkData(bytes = byteArrayOf(1, 2, 3), contentType = "image/png").right()

    override suspend fun fetchUserLibraryAccess(): Either<MiruError, List<MediaUserLibraryAccess>> =
        listOf(MediaUserLibraryAccess(externalUserId = "2002", allLibraries = false, sectionKeys = setOf("1"))).right()

    override suspend fun terminateSession(
        sessionId: String,
        reason: String,
    ): Either<MiruError, Unit> = Unit.right()
}

class PlexRuntimeManagerTest :
    FunSpec({
        test("DelegatingMediaServerAdapter returns UpstreamUnavailable when unconfigured and delegates + emits when configured") {
            val adapter = DelegatingMediaServerAdapter()
            adapter.currentDelegate().shouldBeNull()

            adapter.checkHealth().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchActiveSessions().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchUsers().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchLibrarySections().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchLibraryCount("1").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchLibraryItems("1").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchArtwork("/library/metadata/1/thumb").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.fetchUserLibraryAccess().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            adapter.terminateSession("s-1", "Maintenance").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            val stub = DelegatingTestStubAdapter()
            adapter.setDelegate(stub)
            adapter.currentDelegate() shouldBe stub

            adapter.checkHealth().shouldBeRight().machineIdentifier shouldBe "mach-stub"
            adapter.fetchActiveSessions().shouldBeRight().shouldBeEmpty()
            adapter.fetchUsers().shouldBeRight().shouldBeEmpty()
            adapter.fetchLibrarySections().shouldBeRight().shouldBeEmpty()
            adapter.fetchLibraryCount("1").shouldBeRight() shouldBe 42
            adapter.fetchLibraryItems("1").shouldBeRight().totalSize shouldBe 0
            adapter.fetchArtwork("/library/metadata/1/thumb").shouldBeRight().contentType shouldBe "image/png"
            adapter.fetchUserLibraryAccess().shouldBeRight() shouldBe
                listOf(MediaUserLibraryAccess(externalUserId = "2002", allLibraries = false, sectionKeys = setOf("1")))
            adapter.terminateSession("s-1", "Maintenance").shouldBeRight()

            val event = MediaServerEvent.PlayingEvent(sessionKey = "10", ratingKey = "20", state = PlaybackState.PLAYING)
            val received = CompletableDeferred<MediaServerEvent>()
            val job = launch { received.complete(adapter.events().first()) }
            kotlinx.coroutines.yield()
            adapter.emitEvent(event)
            withTimeout(2_000) { received.await() } shouldBe event
            job.cancel()
        }

        test("PlexRuntimeManager supports zero-config startup, decrypt failure, reload, sync hook, and DB fallback") {
            val tempDir = Files.createTempDirectory("miru-runtime-mgr-test-")
            try {
                val dbPath = tempDir.resolve("runtime.db").toString()
                val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
                val serverRepo = ExposedMediaServerRepository(handle)
                val reachable = AtomicBoolean(true)
                val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

                val mockEngine =
                    MockEngine { req ->
                        if (!reachable.get()) {
                            respond("Down", HttpStatusCode.ServiceUnavailable)
                        } else {
                            when (req.url.encodedPath) {
                                "/" ->
                                    respond(
                                        content =
                                            """
                                            {"MediaContainer":{"machineIdentifier":"mach-1","friendlyName":"Plex 1","version":"1.41"}}
                                            """.trimIndent(),
                                        status = HttpStatusCode.OK,
                                        headers = jsonHeaders,
                                    )
                                "/status/sessions" ->
                                    respond(
                                        content = PlexFixtures.localOwnerSessionJson,
                                        status = HttpStatusCode.OK,
                                        headers = jsonHeaders,
                                    )
                                "/api/v2/user" ->
                                    respond(
                                        content = PlexFixtures.plexTvUserJson,
                                        status = HttpStatusCode.OK,
                                        headers = jsonHeaders,
                                    )
                                else -> respond("Not found", HttpStatusCode.NotFound)
                            }
                        }
                    }

                val manager =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(websocketEnabled = false, encryptionKey = TEST_ENC_KEY),
                        databaseHandle = handle,
                        sessionConfig = SessionConfig(),
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { mockEngine },
                    )

                // Zero-config state
                manager.initialize().shouldBeRight() shouldBe false
                manager.isConfigured() shouldBe false
                manager.currentCredentialSource() shouldBe PlexCredentialSource.NOT_CONFIGURED
                manager.currentServerName().shouldBeNull()
                manager.serverIdProvider().shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

                // Reload activates pipeline and triggers sync callback (even if callback throws)
                val syncCalled = CompletableDeferred<Unit>()
                manager.setOnReloadLibrarySync {
                    syncCalled.complete(Unit)
                    error("simulated sync failure")
                }
                manager.reload(baseUrl = "http://plex.local:32400", token = "tok-1", machineId = "mach-1")
                withTimeout(2_000) { syncCalled.await() }
                manager.isConfigured() shouldBe true
                manager.currentBaseUrl() shouldBe "http://plex.local:32400"
                manager.currentMachineId() shouldBe "mach-1"
                manager.currentCredentialSource() shouldBe PlexCredentialSource.STORED_ENCRYPTED

                // serverIdProvider resolves via checkHealth and caches id; default plex.tv lookup resolves local owner
                val resolvedId = manager.serverIdProvider().shouldBeRight()
                manager.serverIdProvider().shouldBeRight() shouldBe resolvedId
                manager.currentServerName() shouldBe "Plex 1"
                val active = manager.adapter.fetchActiveSessions().shouldBeRight().single()
                active.user.externalId shouldBe "99887766"
                active.user.username shouldBe "kerrlab"

                // Reload while Plex unreachable exercises DB fallback for serverIdProvider
                reachable.set(false)
                manager.reload(baseUrl = "http://plex.local:32400/", token = "tok-2", machineId = "mach-1")
                manager.serverIdProvider().shouldBeRight() shouldBe resolvedId

                manager.stop()
                manager.isConfigured() shouldBe false
                manager.currentCredentialSource() shouldBe PlexCredentialSource.NOT_CONFIGURED
                manager.currentBaseUrl().shouldBeNull()
                manager.currentServerName().shouldBeNull()

                // Encrypted DB record with missing key returns Left on initialize
                val encrypted = CredentialCrypto.encrypt("tok-stored", TEST_ENC_KEY).shouldBeRight()
                serverRepo
                    .upsert(
                        MediaServerRecord(
                            id = "srv-stored",
                            name = "Stored Plex",
                            serverType = "plex",
                            machineId = "mach-stored",
                            baseUrl = "http://stored.local:32400",
                            encryptedToken = encrypted,
                            clientIdentifier = "cid-stored",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                val noKeyManager =
                    PlexRuntimeManager(
                        plexConfig = PlexConfig(websocketEnabled = false, encryptionKey = null),
                        databaseHandle = handle,
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { mockEngine },
                    )
                noKeyManager.initialize().shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
                noKeyManager.stop()

                // Static env config ignores reloadFromStoredCredentials and activates via start()
                val staticManager =
                    PlexRuntimeManager(
                        plexConfig =
                            PlexConfig(
                                baseUrl = "http://env.local:32400",
                                token = "env-tok",
                                websocketEnabled = false,
                                encryptionKey = TEST_ENC_KEY,
                            ),
                        databaseHandle = handle,
                        mediaServerRepository = serverRepo,
                        httpClientEngineFactory = { mockEngine },
                    )
                val staticSynced = CompletableDeferred<Unit>()
                staticManager.setOnReloadLibrarySync { staticSynced.complete(Unit) }
                staticManager.start()
                withTimeout(2_000) { staticSynced.await() }
                staticManager.isConfigured() shouldBe true
                staticManager.currentCredentialSource() shouldBe PlexCredentialSource.ENV_OVERRIDE
                staticManager.reloadFromStoredCredentials().shouldBeRight() shouldBe false
                // machineId=null matches baseUrl first, then unmatched baseUrl falls back to oldest plex serverType row
                staticManager.reload(baseUrl = "http://plex.local:32400", token = "tok-p", machineId = null)
                staticManager.currentServerName() shouldBe "Plex 1"
                staticManager.reload(baseUrl = "http://other.local:32400", token = "tok-o", machineId = null)
                staticManager.currentServerName() shouldBe "Stored Plex"
                staticManager.stop()

                handle.close()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    })
