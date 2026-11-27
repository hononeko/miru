package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.jellyfin.JellyfinMediaServerAdapter
import app.hononeko.miru.adapter.jellyfin.createJellyfinHttpClient
import app.hononeko.miru.adapter.plex.PlexMediaServerAdapter
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.AvatarImageValidator
import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.artwork.createAvatarHttpClient
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.JellyfinConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private const val PROFILE_PLEX_URL = "http://plex.local:32400"
private const val PROFILE_JELLYFIN_URL = "http://jellyfin.local:8096"
private const val PROFILE_SESSION_SECRET = "profile-test-session-signing-secret-32-chars"
private const val PLEX_AVATAR_URL = "https://plex.tv/users/7/avatar"
private const val JELLYFIN_THUMB = "/Users/abc/Images/Primary"

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun png(
    marker: Int,
    size: Int = 64,
): ByteArray = PNG_SIGNATURE + ByteArray(size - PNG_SIGNATURE.size) { (it + marker).toByte() }

private val UPLOADED_PNG = png(marker = 1)

/** plex.tv serves uploaded avatars at their original size, which can be over a MiB. */
private val PLEX_ORIGINAL_PNG = png(marker = 2, size = AvatarImageValidator.MAX_AVATAR_BYTES + 2_048)
private val PLEX_SCALED_PNG = png(marker = 4, size = 200)

/** Jellyfin keeps the original upload, which is larger than an account avatar may be. */
private val JELLYFIN_ORIGINAL_PNG = png(marker = 3, size = AvatarImageValidator.MAX_AVATAR_BYTES + 1_024)

private class ProfileHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val nowMs: AtomicLong,
    val authService: AuthService,
    val appConfig: AppConfig,
    val artworkProxyService: ArtworkProxyService,
    val upstreamRequests: MutableList<HttpRequestData>,
    val syncServerUsers: suspend (String) -> Unit,
    private val externalClient: HttpClient,
) : AutoCloseable {
    override fun close() {
        externalClient.close()
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

/** Two servers, and one person on each: Elena's Plex profile and her older Jellyfin one. */
private suspend fun seedProfileMediaUsers(
    servers: ExposedMediaServerRepository,
    userRepo: ExposedUserRepository,
) {
    listOf("srv-plex" to "PLEX", "srv-jelly" to "JELLYFIN").forEach { (id, type) ->
        servers
            .upsert(
                MediaServerRecord(
                    id = id,
                    name = id,
                    serverType = type,
                    machineId = "machine-$id",
                    baseUrl = PROFILE_PLEX_URL,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                ),
            ).shouldBeRight()
    }
    userRepo
        .upsert(
            UserRecord(
                id = "u-plex",
                serverId = "srv-plex",
                externalUserId = "7",
                username = "elena",
                displayName = "Elena P",
                thumbUrl = PLEX_AVATAR_URL,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()
    // The older profile, so it leads; Jellyfin repeats the username as the display name
    userRepo
        .upsert(
            UserRecord(
                id = "u-jelly",
                serverId = "srv-jelly",
                externalUserId = "abc",
                username = "elena",
                displayName = "elena",
                thumbUrl = JELLYFIN_THUMB,
                createdAtMs = 500L,
                updatedAtMs = 500L,
            ),
        ).shouldBeRight()
}

private fun createProfileHarness(
    mode: AuthMode = AuthMode.LOCAL,
    syncServerUsers: suspend (ExposedUserRepository, String) -> Unit = { _, _ -> },
): ProfileHarness {
    val dir = Files.createTempDirectory("miru-profile-test-")
    val dbPath = dir.resolve("miru-profile.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val nowMs = AtomicLong(1_000_000L)
    val accountRepo = ExposedAuthAccountRepository(handle)
    val userRepo = ExposedUserRepository(handle)
    val authConfig = AuthConfig(mode = mode, sessionSecret = if (mode == AuthMode.NONE) "" else PROFILE_SESSION_SECRET)
    val authService =
        AuthService(
            accountRepository = accountRepo,
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
            clock = { nowMs.get() },
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = PROFILE_PLEX_URL, token = "profile-plex-token"),
            session = SessionConfig(),
            auth = authConfig,
        )

    val upstreamRequests = mutableListOf<HttpRequestData>()
    val imageHeaders = { type: String -> headersOf(HttpHeaders.ContentType, type) }
    val plexClient =
        createPlexHttpClient(
            engine =
                MockEngine { request ->
                    upstreamRequests += request
                    if (request.url.encodedPath == "/photo/:/transcode" && request.url.parameters["url"] == PLEX_AVATAR_URL) {
                        respond(PLEX_SCALED_PNG, HttpStatusCode.OK, imageHeaders("image/png"))
                    } else {
                        respond(ByteArray(0), HttpStatusCode.NotFound)
                    }
                },
        )
    // Jellyfin serves a user's picture at its original size, whatever size is asked for
    val jellyfinAdapter =
        JellyfinMediaServerAdapter(
            config = JellyfinConfig(baseUrl = PROFILE_JELLYFIN_URL, apiKey = "profile-jellyfin-key"),
            httpClient =
                createJellyfinHttpClient(
                    engine =
                        MockEngine { request ->
                            upstreamRequests += request
                            if (request.url.encodedPath == JELLYFIN_THUMB) {
                                respond(JELLYFIN_ORIGINAL_PNG, HttpStatusCode.OK, imageHeaders("image/png"))
                            } else {
                                respond(ByteArray(0), HttpStatusCode.NotFound)
                            }
                        },
                ),
        )
    val externalClient =
        createAvatarHttpClient(
            engine =
                MockEngine { request ->
                    if (request.url.toString() == PLEX_AVATAR_URL) {
                        respond(PLEX_ORIGINAL_PNG, HttpStatusCode.OK, imageHeaders("image/png"))
                    } else {
                        respond(ByteArray(0), HttpStatusCode.NotFound)
                    }
                },
        )
    val plexAdapter = PlexMediaServerAdapter(config = appConfig.plex, httpClient = plexClient)
    val artworkProxyService =
        ArtworkProxyService(
            adapter = plexAdapter,
            adapterForServer = { serverId -> if (serverId == "srv-jelly") jellyfinAdapter else plexAdapter },
            userRepository = userRepo,
            externalAvatarClient = externalClient,
            plexBaseUrlProvider = { PROFILE_PLEX_URL },
            dnsResolver = DnsResolver { listOf(InetAddress.getByName("93.184.216.34")) },
            authAccountRepository = accountRepo,
        )

    runBlocking { seedProfileMediaUsers(ExposedMediaServerRepository(handle), userRepo) }

    return ProfileHarness(
        tempDir = dir,
        dbHandle = handle,
        nowMs = nowMs,
        authService = authService,
        appConfig = appConfig,
        artworkProxyService = artworkProxyService,
        upstreamRequests = upstreamRequests,
        syncServerUsers = { serverId -> syncServerUsers(userRepo, serverId) },
        externalClient = externalClient,
    )
}

private fun ApplicationTestBuilder.setupProfileApp(harness: ProfileHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            artworkProxyService = harness.artworkProxyService,
            syncServerUsers = harness.syncServerUsers,
        )
    }
}

private data class SeededAccounts(
    val adminKey: String,
    val elenaId: String,
    val elenaKey: String,
)

private suspend fun seedAccounts(harness: ProfileHarness): SeededAccounts {
    val admin = harness.authService.setupInitialAdmin("admin", "Admin-password-1234").shouldBeRight()
    val adminKey =
        harness.authService
            .createApiKey(accountId = admin.principal.accountId, name = "admin-key", role = MiruRole.ADMIN)
            .shouldBeRight()
            .rawKey
    val elena =
        harness.authService
            .createAccount(
                username = "elena.k",
                password = "Elena-password-1234",
                linkedMediaUserIds = setOf("u-plex", "u-jelly"),
            ).shouldBeRight()
    val elenaKey =
        harness.authService
            .createApiKey(accountId = elena.account.id, name = "elena-key", role = MiruRole.USER)
            .shouldBeRight()
            .rawKey
    return SeededAccounts(adminKey = adminKey, elenaId = elena.account.id, elenaKey = elenaKey)
}

private fun HttpRequestBuilder.jsonBody(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun HttpRequestBuilder.imageBody(
    bytes: ByteArray,
    type: String = "image/png",
) {
    header(HttpHeaders.ContentType, type)
    setBody(bytes)
}

private suspend fun HttpResponse.principal(): PrincipalResponse {
    status shouldBe HttpStatusCode.OK
    return MiruJson.decodeFromString<PrincipalResponse>(bodyAsText())
}

private suspend fun HttpResponse.account(): AccountResponse {
    status shouldBe HttpStatusCode.OK
    return MiruJson.decodeFromString<AccountResponse>(bodyAsText())
}

private suspend fun HttpResponse.fieldErrors(): Map<String, String> {
    status shouldBe HttpStatusCode.BadRequest
    return MiruJson.decodeFromString<ProblemDetails>(bodyAsText()).errors.orEmpty()
}

class AccountProfileRoutesTest :
    FunSpec({
        test("users set and clear their own display name and avatar, and admins import them from a linked server") {
            createProfileHarness().use { harness ->
                val seeded = seedAccounts(harness)
                testApplication {
                    setupProfileApp(harness)

                    suspend fun me() = client.get("/api/v1/auth/me") { header("X-Api-Key", seeded.elenaKey) }.principal()

                    suspend fun patchProfile(body: String) =
                        client.patch("/api/v1/auth/me/profile") {
                            header("X-Api-Key", seeded.elenaKey)
                            jsonBody(body)
                        }

                    suspend fun putAvatar(
                        bytes: ByteArray,
                        type: String = "image/png",
                    ) = client.put("/api/v1/auth/me/avatar") {
                        header("X-Api-Key", seeded.elenaKey)
                        imageBody(bytes, type)
                    }

                    suspend fun getAvatar(url: String) = client.get(url) { header("X-Api-Key", seeded.elenaKey) }

                    suspend fun adminImport(body: String) =
                        client.patch("/api/v1/admin/accounts/${seeded.elenaId}") {
                            header("X-Api-Key", seeded.adminKey)
                            jsonBody(body)
                        }

                    // Before any profile of its own, the account shows its linked servers' best name and avatar
                    val initial = me()
                    initial.displayName shouldBe "Elena P"
                    initial.hasCustomDisplayName shouldBe false
                    initial.avatarUrl shouldBe "/api/v1/users/u-jelly/avatar"
                    initial.hasCustomAvatar shouldBe false

                    patchProfile("{}").status shouldBe HttpStatusCode.BadRequest
                    patchProfile("""{"displayName":"   "}""").fieldErrors() shouldContainKey "displayName"
                    patchProfile("""{"displayName":"Elena\u0007"}""").fieldErrors() shouldContainKey "displayName"
                    patchProfile("""{"displayName":"${"e".repeat(101)}"}""").fieldErrors() shouldContainKey "displayName"
                    patchProfile("""{"displayName":"Elena","clearDisplayName":true}""").fieldErrors() shouldContainKey "clearDisplayName"
                    // Importing from a server is an admin action, not part of a person's own profile
                    patchProfile("""{"importDisplayNameFromServerId":"srv-plex","importAvatarFromServerId":"srv-plex"}""")
                        .status shouldBe HttpStatusCode.BadRequest
                    me() shouldBe initial
                    adminImport("""{"displayName":"Elena","importDisplayNameFromServerId":"srv-plex"}""")
                        .fieldErrors() shouldContainKey "displayName"
                    adminImport("""{"clearAvatar":true,"importAvatarFromServerId":"srv-plex"}""")
                        .fieldErrors() shouldContainKey "clearAvatar"
                    adminImport("""{"importDisplayNameFromServerId":"srv-unlinked"}""").status shouldBe HttpStatusCode.NotFound

                    val named = patchProfile("""{"displayName":"  Elena K  "}""").principal()
                    named.displayName shouldBe "Elena K"
                    named.hasCustomDisplayName shouldBe true
                    me().displayName shouldBe "Elena K"

                    // Uploads are checked by their bytes and size, not the declared type alone
                    putAvatar(png(marker = 9).copyOf(4)).fieldErrors() shouldContainKey "avatar"
                    putAvatar("GIF89a-not-allowed".toByteArray(), type = "image/gif").fieldErrors() shouldContainKey "contentType"
                    putAvatar(png(marker = 9, size = AvatarImageValidator.MAX_AVATAR_BYTES + 1)).fieldErrors() shouldContainKey "avatar"
                    me().hasCustomAvatar shouldBe false

                    harness.nowMs.set(2_000_000L)
                    val uploaded = putAvatar(UPLOADED_PNG).principal()
                    uploaded.hasCustomAvatar shouldBe true
                    uploaded.avatarSourceServerId.shouldBeNull()
                    uploaded.avatarUrl shouldBe "/api/v1/users/${seeded.elenaId}/avatar?v=2000000"

                    val served = getAvatar(uploaded.avatarUrl.shouldNotBeNull())
                    served.status shouldBe HttpStatusCode.OK
                    served.bodyAsBytes().contentEquals(UPLOADED_PNG) shouldBe true
                    served.headers[HttpHeaders.ContentType] shouldBe "image/png"
                    val etag = served.headers[HttpHeaders.ETag].shouldNotBeNull()
                    client
                        .get(uploaded.avatarUrl.shouldNotBeNull()) {
                            header("X-Api-Key", seeded.elenaKey)
                            header(HttpHeaders.IfNoneMatch, etag)
                        }.status shouldBe HttpStatusCode.NotModified

                    // A media user's own avatar URL still serves that server's picture, whatever its size
                    getAvatar("/api/v1/users/u-plex/avatar").bodyAsBytes().contentEquals(PLEX_ORIGINAL_PNG) shouldBe true

                    // Import both from Plex: the name and the picture become the account's own. The plex.tv
                    // original is too large to keep, so Plex's photo transcoder fetches and scales it
                    harness.nowMs.set(3_000_000L)
                    adminImport("""{"importDisplayNameFromServerId":"srv-plex","importAvatarFromServerId":"srv-plex"}""")
                        .account()
                    val imported = me()
                    imported.displayName shouldBe "Elena P"
                    imported.hasCustomDisplayName shouldBe true
                    imported.hasCustomAvatar shouldBe true
                    imported.avatarSourceServerId shouldBe "srv-plex"
                    imported.avatarUrl shouldBe "/api/v1/users/${seeded.elenaId}/avatar?v=3000000"
                    val fromPlex = getAvatar(imported.avatarUrl.shouldNotBeNull())
                    fromPlex.bodyAsBytes().contentEquals(PLEX_SCALED_PNG) shouldBe true
                    fromPlex.headers[HttpHeaders.ContentType] shouldBe "image/png"
                    val plexScale = harness.upstreamRequests.single { it.url.parameters["url"] == PLEX_AVATAR_URL }
                    plexScale.url.encodedPath shouldBe "/photo/:/transcode"
                    plexScale.url.parameters["format"] shouldBe "png"
                    plexScale.url.parameters["width"] shouldBe "256"
                    plexScale.url.parameters["height"] shouldBe "256"

                    // Jellyfin cannot scale user pictures, so its larger original is kept as is (up to 4 MiB),
                    // without asking Jellyfin for a smaller copy it would not make
                    harness.nowMs.set(4_000_000L)
                    adminImport("""{"importAvatarFromServerId":"srv-jelly"}""").account().avatarSourceServerId shouldBe "srv-jelly"
                    val fromJellyfin = me()
                    fromJellyfin.displayName shouldBe "Elena P"
                    val kept = getAvatar(fromJellyfin.avatarUrl.shouldNotBeNull())
                    kept.bodyAsBytes().contentEquals(JELLYFIN_ORIGINAL_PNG) shouldBe true
                    kept.headers[HttpHeaders.ContentType] shouldBe "image/png"
                    harness.upstreamRequests.count { it.url.encodedPath == JELLYFIN_THUMB } shouldBe 1
                    harness.upstreamRequests.none { it.url.parameters["url"] == JELLYFIN_THUMB } shouldBe true

                    // Clearing hands both back to the linked servers
                    val withoutAvatar =
                        client.delete("/api/v1/auth/me/avatar") { header("X-Api-Key", seeded.elenaKey) }.principal()
                    withoutAvatar.hasCustomAvatar shouldBe false
                    withoutAvatar.avatarSourceServerId.shouldBeNull()
                    withoutAvatar.avatarUrl shouldBe "/api/v1/users/u-jelly/avatar"
                    getAvatar("/api/v1/users/${seeded.elenaId}/avatar")
                        .bodyAsBytes()
                        .contentEquals(JELLYFIN_ORIGINAL_PNG) shouldBe true

                    val withoutName = patchProfile("""{"clearDisplayName":true}""").principal()
                    withoutName.displayName shouldBe "Elena P"
                    withoutName.hasCustomDisplayName shouldBe false
                    me() shouldBe withoutName
                }
            }
        }

        test("admins manage any account's display name and avatar, and the account sees the change at once") {
            createProfileHarness().use { harness ->
                val seeded = seedAccounts(harness)
                testApplication {
                    setupProfileApp(harness)

                    suspend fun admin(
                        method: String,
                        path: String,
                        configure: HttpRequestBuilder.() -> Unit = {},
                    ): HttpResponse {
                        val block: HttpRequestBuilder.() -> Unit = {
                            header("X-Api-Key", seeded.adminKey)
                            configure()
                        }
                        return when (method) {
                            "POST" -> client.post(path, block)
                            "PATCH" -> client.patch(path, block)
                            "PUT" -> client.put(path, block)
                            "DELETE" -> client.delete(path, block)
                            else -> client.get(path, block)
                        }
                    }

                    admin("POST", "/api/v1/admin/accounts") {
                        jsonBody("""{"username":"bad.name","password":"Omar-password-1234","displayName":"Om\u0000ar"}""")
                    }.fieldErrors() shouldContainKey "displayName"
                    val blankName =
                        admin("POST", "/api/v1/admin/accounts") {
                            jsonBody("""{"username":"blank.name","password":"Omar-password-1234","displayName":"   "}""")
                        }
                    blankName.status shouldBe HttpStatusCode.Created
                    MiruJson.decodeFromString<AccountResponse>(blankName.bodyAsText()).displayName.shouldBeNull()

                    val created =
                        admin("POST", "/api/v1/admin/accounts") {
                            jsonBody("""{"username":"omar","password":"Omar-password-1234","displayName":" Omar R "}""")
                        }
                    created.status shouldBe HttpStatusCode.Created
                    val omar = MiruJson.decodeFromString<AccountResponse>(created.bodyAsText())
                    omar.displayName shouldBe "Omar R"
                    omar.avatarUrl.shouldBeNull()
                    omar.hasCustomAvatar shouldBe false

                    val omarKey =
                        harness.authService
                            .createApiKey(accountId = omar.id, name = "omar-key", role = MiruRole.USER)
                            .shouldBeRight()
                            .rawKey

                    suspend fun omarMe() = client.get("/api/v1/auth/me") { header("X-Api-Key", omarKey) }.principal()
                    omarMe().displayName shouldBe "Omar R"

                    // Profile fields go along with the other account changes in one request
                    val renamed =
                        admin("PATCH", "/api/v1/admin/accounts/${omar.id}") {
                            jsonBody("""{"displayName":"Omar Rahman","disabled":false}""")
                        }.account()
                    renamed.displayName shouldBe "Omar Rahman"
                    renamed.disabled shouldBe false
                    omarMe().displayName shouldBe "Omar Rahman"

                    harness.nowMs.set(5_000_000L)
                    val withAvatar = admin("PUT", "/api/v1/admin/accounts/${omar.id}/avatar") { imageBody(UPLOADED_PNG) }.account()
                    withAvatar.hasCustomAvatar shouldBe true
                    withAvatar.avatarUrl shouldBe "/api/v1/users/${omar.id}/avatar?v=5000000"
                    val listed =
                        MiruJson
                            .decodeFromString<AccountListResponse>(admin("GET", "/api/v1/admin/accounts").bodyAsText())
                            .accounts
                    listed.single { it.id == omar.id }.avatarUrl shouldBe withAvatar.avatarUrl
                    listed.single { it.id == seeded.elenaId }.avatarUrl shouldBe "/api/v1/users/u-jelly/avatar"
                    omarMe().avatarUrl shouldBe withAvatar.avatarUrl
                    client
                        .get(withAvatar.avatarUrl.shouldNotBeNull()) { header("X-Api-Key", omarKey) }
                        .bodyAsBytes()
                        .contentEquals(UPLOADED_PNG) shouldBe true

                    // Other users may not read the account's avatar or change it
                    client
                        .get(withAvatar.avatarUrl.shouldNotBeNull()) { header("X-Api-Key", seeded.elenaKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .put("/api/v1/admin/accounts/${omar.id}/avatar") {
                            header("X-Api-Key", seeded.elenaKey)
                            imageBody(UPLOADED_PNG)
                        }.status shouldBe HttpStatusCode.Forbidden
                    admin("PUT", "/api/v1/admin/accounts/missing/avatar") { imageBody(UPLOADED_PNG) }.status shouldBe
                        HttpStatusCode.NotFound
                    admin("DELETE", "/api/v1/admin/accounts/missing/avatar").status shouldBe HttpStatusCode.NotFound

                    // An account without linked media users has nothing to import
                    admin("PATCH", "/api/v1/admin/accounts/${omar.id}") {
                        jsonBody("""{"importAvatarFromServerId":"srv-plex"}""")
                    }.status shouldBe HttpStatusCode.NotFound

                    val imported =
                        admin("PATCH", "/api/v1/admin/accounts/${seeded.elenaId}") {
                            jsonBody("""{"importDisplayNameFromServerId":"srv-plex","importAvatarFromServerId":"srv-plex"}""")
                        }.account()
                    imported.displayName shouldBe "Elena P"
                    imported.avatarSourceServerId shouldBe "srv-plex"
                    imported.hasCustomAvatar shouldBe true

                    val cleared = admin("DELETE", "/api/v1/admin/accounts/${omar.id}/avatar").account()
                    cleared.hasCustomAvatar shouldBe false
                    cleared.avatarUrl.shouldBeNull()
                    val unnamed =
                        admin("PATCH", "/api/v1/admin/accounts/${omar.id}") {
                            jsonBody("""{"clearDisplayName":true}""")
                        }.account()
                    unnamed.displayName.shouldBeNull()
                    val omarAfter = omarMe()
                    omarAfter.displayName.shouldBeNull()
                    omarAfter.hasCustomAvatar shouldBe false
                    omarAfter.avatarUrl.shouldBeNull()

                    harness.upstreamRequests.map { it.url.parameters["url"] } shouldContain PLEX_AVATAR_URL
                }
            }
        }

        test("importing from a server first re-syncs that server's users, so it imports what the server has now") {
            val synced = mutableListOf<String>()
            createProfileHarness(
                syncServerUsers = { users, serverId ->
                    synced += serverId
                    // Since the last scheduled sync, Elena renamed herself on Jellyfin
                    val jellyfinUser = users.findById("u-jelly").shouldBeRight().shouldNotBeNull()
                    users.upsert(jellyfinUser.copy(displayName = "Elena J")).shouldBeRight()
                },
            ).use { harness ->
                val seeded = seedAccounts(harness)
                testApplication {
                    setupProfileApp(harness)
                    suspend fun patchElena(body: String) =
                        client
                            .patch("/api/v1/admin/accounts/${seeded.elenaId}") {
                                header("X-Api-Key", seeded.adminKey)
                                jsonBody(body)
                            }.account()
                    patchElena("""{"importDisplayNameFromServerId":"srv-jelly"}""").displayName shouldBe "Elena J"
                    synced shouldBe listOf("srv-jelly")

                    // Changes that import nothing leave the servers alone
                    patchElena("""{"displayName":"Elena K"}""").displayName shouldBe "Elena K"
                    synced shouldBe listOf("srv-jelly")
                }
            }
        }

        test("profile changes are refused when authentication is disabled") {
            createProfileHarness(mode = AuthMode.NONE).use { harness ->
                testApplication {
                    setupProfileApp(harness)
                    client
                        .patch("/api/v1/auth/me/profile") { jsonBody("""{"displayName":"Dev"}""") }
                        .status shouldBe HttpStatusCode.Conflict
                    client.put("/api/v1/auth/me/avatar") { imageBody(UPLOADED_PNG) }.status shouldBe HttpStatusCode.Conflict
                    client.delete("/api/v1/auth/me/avatar").status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    })
