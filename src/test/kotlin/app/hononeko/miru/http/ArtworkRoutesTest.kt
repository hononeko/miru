package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.adapter.plex.PlexMediaServerAdapter
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.artwork.ArtworkKind
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.BoundedArtworkCache
import app.hononeko.miru.artwork.createAvatarHttpClient
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.AuthSource
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.ProblemDetails
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserLibraryAccessRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedLibraryRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private const val TEST_PLEX_URL = "http://plex.local:32400"
private const val TEST_PLEX_TOKEN = "super-secret-plex-token-mru9"
private const val SESSION_SECRET = "artwork-test-session-signing-secret-32-chars"

private class ArtworkTestHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val nowMs: AtomicLong,
    val authService: AuthService,
    val serverRepo: ExposedMediaServerRepository,
    val libraryRepo: ExposedLibraryRepository,
    val userRepo: ExposedUserRepository,
    val appConfig: AppConfig,
    val plexRequests: MutableList<HttpRequestData>,
    val externalRequests: MutableList<HttpRequestData>,
    val externalClient: HttpClient,
    val artworkProxyService: ArtworkProxyService,
) : AutoCloseable {
    override fun close() {
        externalClient.close()
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createArtworkHarness(
    plexHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(ByteArray(0), HttpStatusCode.NotFound)
    },
    externalHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(ByteArray(0), HttpStatusCode.NotFound)
    },
    cache: BoundedArtworkCache = BoundedArtworkCache(),
): ArtworkTestHarness {
    val dir = Files.createTempDirectory("miru-artwork-test-")
    val dbPath = dir.resolve("miru-artwork.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val nowMs = AtomicLong(1_000_000L)
    val userRepo = ExposedUserRepository(handle)
    val serverRepo = ExposedMediaServerRepository(handle)
    val libraryRepo = ExposedLibraryRepository(handle)
    val authConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = SESSION_SECRET,
        )
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
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
            plex = PlexConfig(baseUrl = TEST_PLEX_URL, token = TEST_PLEX_TOKEN),
            session = SessionConfig(),
            auth = authConfig,
        )

    val plexRequests = mutableListOf<HttpRequestData>()
    val plexClient =
        createPlexHttpClient(
            engine =
                MockEngine { request ->
                    plexRequests += request
                    plexHandler(request)
                },
        )
    val adapter =
        PlexMediaServerAdapter(
            config = appConfig.plex,
            httpClient = plexClient,
        )

    val externalRequests = mutableListOf<HttpRequestData>()
    val externalClient =
        createAvatarHttpClient(
            engine =
                MockEngine { request ->
                    externalRequests += request
                    externalHandler(request)
                },
        )

    val artworkProxyService =
        ArtworkProxyService(
            adapter = adapter,
            userRepository = userRepo,
            externalAvatarClient = externalClient,
            cache = cache,
            plexBaseUrl = TEST_PLEX_URL,
            libraryRepository = libraryRepo,
        )

    return ArtworkTestHarness(
        tempDir = dir,
        dbHandle = handle,
        nowMs = nowMs,
        authService = authService,
        serverRepo = serverRepo,
        libraryRepo = libraryRepo,
        userRepo = userRepo,
        appConfig = appConfig,
        plexRequests = plexRequests,
        externalRequests = externalRequests,
        externalClient = externalClient,
        artworkProxyService = artworkProxyService,
    )
}

private suspend fun seedAuthAndUsers(harness: ArtworkTestHarness): Pair<String, String> {
    val admin = harness.authService.setupInitialAdmin("admin", "Admin-password-1234").shouldBeRight()
    val adminKey =
        harness.authService
            .createApiKey(
                accountId = admin.principal.accountId,
                name = "admin-key",
                role = MiruRole.ADMIN,
            ).shouldBeRight()
            .rawKey

    harness.serverRepo
        .upsert(
            MediaServerRecord(
                id = "srv-1",
                name = "Main Plex",
                serverType = "PLEX",
                machineId = "mach-1",
                baseUrl = TEST_PLEX_URL,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()

    harness.userRepo
        .upsert(
            UserRecord(
                id = "user-plex-tv",
                serverId = "srv-1",
                externalUserId = "101",
                username = "alice",
                displayName = "Alice",
                thumbUrl = "https://plex.tv/users/abc123/avatar?c=100",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()

    harness.userRepo
        .upsert(
            UserRecord(
                id = "user-gravatar",
                serverId = "srv-1",
                externalUserId = "102",
                username = "bob",
                displayName = "Bob",
                thumbUrl = "https://secure.gravatar.com/avatar/deadbeef?s=200",
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        ).shouldBeRight()

    val userAccount =
        harness.authService
            .createAccount(
                username = "alice-miru",
                password = "Alice-password-1234",
                role = MiruRole.USER,
                linkedMediaUserIds = setOf("user-plex-tv"),
            ).shouldBeRight()
    val userKey =
        harness.authService
            .createApiKey(
                accountId = userAccount.account.id,
                name = "user-key",
                role = MiruRole.USER,
            ).shouldBeRight()
            .rawKey

    return adminKey to userKey
}

private fun ApplicationTestBuilder.setupArtworkApp(harness: ArtworkTestHarness) {
    application {
        miruModule(
            config = harness.appConfig,
            authService = harness.authService,
            artworkProxyService = harness.artworkProxyService,
        )
    }
}

class ArtworkRoutesTest :
    FunSpec({
        test("proxies thumb and art with dimensions, caches hits, and serves 304 Not Modified") {
            val thumbBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            val artBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)

            createArtworkHarness(
                plexHandler = { request ->
                    when (request.url.encodedPath) {
                        "/photo/:/transcode" -> {
                            request.url.parameters["url"] shouldBe "/library/metadata/42/thumb"
                            request.url.parameters["width"] shouldBe "300"
                            request.url.parameters["height"] shouldBe "450"
                            respond(
                                content = thumbBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                            )
                        }

                        "/library/metadata/42/art" ->
                            respond(
                                content = artBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )

                        else -> error("Unexpected Plex path: ${request.url}")
                    }
                },
            ).use { harness ->
                val (_, userKey) = seedAuthAndUsers(harness)
                harness.libraryRepo
                    .replaceUserLibraryAccess(
                        serverId = "srv-1",
                        records =
                            listOf(
                                UserLibraryAccessRecord(
                                    serverId = "srv-1",
                                    externalUserId = "101",
                                    allLibraries = false,
                                    sectionKeys = setOf("1"),
                                    updatedAtMs = 1_000L,
                                ),
                            ),
                    ).shouldBeRight()
                harness.libraryRepo
                    .upsertMediaItems(
                        listOf(
                            MediaItemCacheRecord(
                                serverId = "srv-1",
                                ratingKey = "42",
                                sectionKey = "1",
                                mediaType = "movie",
                                title = "Test Movie 42",
                                addedAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ),
                    ).shouldBeRight()
                testApplication {
                    setupArtworkApp(harness)

                    // Unauthenticated request fails with 401
                    client.get("/api/v1/media/42/thumb").status shouldBe HttpStatusCode.Unauthorized

                    // First thumb request hits Plex
                    val firstThumb =
                        client.get("/api/v1/media/42/thumb?w=300&h=450") {
                            header("X-Api-Key", userKey)
                        }
                    firstThumb.status shouldBe HttpStatusCode.OK
                    firstThumb.bodyAsBytes() shouldBe thumbBytes
                    firstThumb.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"
                    firstThumb.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=3600"
                    firstThumb.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    val etag = firstThumb.headers[HttpHeaders.ETag].shouldNotBeNull()
                    harness.plexRequests shouldHaveSize 1

                    // Second thumb request is served from cache
                    val cachedThumb =
                        client.get("/api/v1/media/42/thumb?w=300&h=450") {
                            header("X-Api-Key", userKey)
                        }
                    cachedThumb.status shouldBe HttpStatusCode.OK
                    cachedThumb.bodyAsBytes() shouldBe thumbBytes
                    cachedThumb.headers[HttpHeaders.ETag] shouldBe etag
                    harness.plexRequests shouldHaveSize 1

                    // Conditional request returns 304 Not Modified with empty body
                    val notModified =
                        client.get("/api/v1/media/42/thumb?w=300&h=450") {
                            header("X-Api-Key", userKey)
                            header(HttpHeaders.IfNoneMatch, "W/$etag")
                        }
                    notModified.status shouldBe HttpStatusCode.NotModified
                    notModified.headers[HttpHeaders.ETag] shouldBe etag
                    notModified.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=3600"
                    notModified.bodyAsBytes().size shouldBe 0
                    harness.plexRequests shouldHaveSize 1

                    // Non-matching If-None-Match returns 200 OK
                    val mismatched =
                        client.get("/api/v1/media/42/thumb?w=300&h=450") {
                            header("X-Api-Key", userKey)
                            header(HttpHeaders.IfNoneMatch, "\"0000\"")
                        }
                    mismatched.status shouldBe HttpStatusCode.OK
                    mismatched.bodyAsBytes() shouldBe thumbBytes

                    // Art endpoint works without dimensions
                    val artResp =
                        client.get("/api/v1/media/42/art") {
                            header("X-Api-Key", userKey)
                        }
                    artResp.status shouldBe HttpStatusCode.OK
                    artResp.bodyAsBytes() shouldBe artBytes
                    artResp.headers[HttpHeaders.ContentType] shouldBe "image/png"
                    harness.plexRequests shouldHaveSize 2
                }
            }
        }

        test("maps 404 and 502 from Plex without leaking X-Plex-Token or internal URLs and sanitizes non-image types") {
            createArtworkHarness(
                plexHandler = { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/missing/thumb" -> respond("not found", HttpStatusCode.NotFound)
                        "/library/metadata/broken/thumb" ->
                            respond(
                                content = "upstream error at $TEST_PLEX_URL with token $TEST_PLEX_TOKEN",
                                status = HttpStatusCode.InternalServerError,
                            )
                        "/library/metadata/weird-mime/thumb" ->
                            respond(
                                content = byteArrayOf(1, 2, 3, 4),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                            )
                        else -> respond(ByteArray(0), HttpStatusCode.OK)
                    }
                },
            ).use { harness ->
                val (adminKey, _) = seedAuthAndUsers(harness)
                testApplication {
                    setupArtworkApp(harness)

                    val notFound =
                        client.get("/api/v1/media/missing/thumb") {
                            header("X-Api-Key", adminKey)
                        }
                    notFound.status shouldBe HttpStatusCode.NotFound
                    notFound.bodyAsText() shouldNotContain TEST_PLEX_TOKEN
                    notFound.bodyAsText() shouldNotContain TEST_PLEX_URL

                    val upstreamErr =
                        client.get("/api/v1/media/broken/thumb") {
                            header("X-Api-Key", adminKey)
                        }
                    upstreamErr.status shouldBe HttpStatusCode.BadGateway
                    val errBody = upstreamErr.bodyAsText()
                    errBody shouldNotContain TEST_PLEX_TOKEN
                    errBody shouldNotContain TEST_PLEX_URL
                    upstreamErr.headers.entries().flatMap { it.value }.forEach { v ->
                        v shouldNotContain TEST_PLEX_TOKEN
                        v shouldNotContain TEST_PLEX_URL
                    }

                    val sanitizedMime =
                        client.get("/api/v1/media/weird-mime/thumb") {
                            header("X-Api-Key", adminKey)
                        }
                    sanitizedMime.status shouldBe HttpStatusCode.OK
                    sanitizedMime.headers[HttpHeaders.ContentType] shouldBe ContentType.Application.OctetStream.toString()
                }
            }
        }

        test("validates dimensions and ratingKey with 400 ProblemDetails") {
            createArtworkHarness().use { harness ->
                val (adminKey, _) = seedAuthAndUsers(harness)
                testApplication {
                    setupArtworkApp(harness)

                    val badDimensions =
                        listOf(
                            "/api/v1/media/123/thumb?w=0",
                            "/api/v1/media/123/thumb?w=5000",
                            "/api/v1/media/123/thumb?h=-1",
                            "/api/v1/media/123/thumb?h=2001",
                            "/api/v1/media/123/thumb?w=abc",
                            "/api/v1/media/123/art?h=xyz",
                        )
                    badDimensions.forEach { path ->
                        val resp =
                            client.get(path) {
                                header("X-Api-Key", adminKey)
                            }
                        resp.status shouldBe HttpStatusCode.BadRequest
                        val problem = MiruJson.decodeFromString<ProblemDetails>(resp.bodyAsText())
                        problem.status shouldBe 400
                    }

                    val badRatingKeys = listOf("bad..key", "bad!key", "a%2Fb")
                    badRatingKeys.forEach { key ->
                        val resp =
                            client.get("/api/v1/media/$key/thumb") {
                                header("X-Api-Key", adminKey)
                            }
                        resp.status shouldBe HttpStatusCode.BadRequest
                        val problem = MiruJson.decodeFromString<ProblemDetails>(resp.bodyAsText())
                        problem.errors.shouldNotBeNull().shouldContainKey("ratingKey")
                    }

                    // Also verify direct service rejection of ".."
                    val adminPrincipal =
                        MiruPrincipal(
                            accountId = "acc-admin",
                            username = "admin",
                            role = MiruRole.ADMIN,
                            authSource = AuthSource.SESSION_COOKIE,
                        )
                    harness.artworkProxyService
                        .getMediaArtwork(adminPrincipal, "..", ArtworkKind.THUMB)
                        .shouldBeLeft()
                        .shouldBeInstanceOf<MiruError.ValidationError>()

                    harness.plexRequests shouldHaveSize 0
                }
            }
        }

        test("proxies allowlisted external and Plex user avatars, caches with 304, and enforces RBAC") {
            val plexTvAvatar = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01)
            val gravatarBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x02)
            val plexLocalAvatar = byteArrayOf(0x47, 0x49, 0x46, 0x38)

            createArtworkHarness(
                plexHandler = { request ->
                    when (request.url.encodedPath) {
                        "/users/301/avatar" ->
                            respond(
                                content = plexLocalAvatar,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/gif"),
                            )
                        else -> respond(ByteArray(0), HttpStatusCode.NotFound)
                    }
                },
                externalHandler = { request ->
                    // Ensure X-Plex-Token is NEVER sent to external avatar hosts
                    request.headers["X-Plex-Token"] shouldBe null
                    when (request.url.host) {
                        "plex.tv" ->
                            respond(
                                content = plexTvAvatar,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                        "secure.gravatar.com" ->
                            respond(
                                content = gravatarBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                            )
                        else -> error("Unexpected external host: ${request.url}")
                    }
                },
            ).use { harness ->
                val (adminKey, userKey) = seedAuthAndUsers(harness)
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "user-plex-relative",
                            serverId = "srv-1",
                            externalUserId = "103",
                            username = "carol",
                            thumbUrl = "/users/301/avatar",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "user-plex-origin",
                            serverId = "srv-1",
                            externalUserId = "104",
                            username = "dave",
                            thumbUrl = "$TEST_PLEX_URL/users/301/avatar",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                harness.userRepo
                    .upsert(
                        UserRecord(
                            id = "user-no-avatar",
                            serverId = "srv-1",
                            externalUserId = "105",
                            username = "eve",
                            thumbUrl = null,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()

                testApplication {
                    setupArtworkApp(harness)

                    // Linked user can fetch own avatar (plex.tv)
                    val ownAvatar =
                        client.get("/api/v1/users/user-plex-tv/avatar") {
                            header("X-Api-Key", userKey)
                        }
                    ownAvatar.status shouldBe HttpStatusCode.OK
                    ownAvatar.bodyAsBytes() shouldBe plexTvAvatar
                    ownAvatar.headers[HttpHeaders.ContentType] shouldBe "image/png"
                    val etag = ownAvatar.headers[HttpHeaders.ETag].shouldNotBeNull()
                    harness.externalRequests shouldHaveSize 1

                    // 304 Not Modified on second call (served from cache)
                    val own304 =
                        client.get("/api/v1/users/user-plex-tv/avatar") {
                            header("X-Api-Key", userKey)
                            header(HttpHeaders.IfNoneMatch, etag)
                        }
                    own304.status shouldBe HttpStatusCode.NotModified
                    harness.externalRequests shouldHaveSize 1

                    // Non-admin user cannot fetch another user's avatar -> 403 Forbidden
                    val forbidden =
                        client.get("/api/v1/users/user-gravatar/avatar") {
                            header("X-Api-Key", userKey)
                        }
                    forbidden.status shouldBe HttpStatusCode.Forbidden

                    // Admin can fetch gravatar avatar
                    val gravatar =
                        client.get("/api/v1/users/user-gravatar/avatar") {
                            header("X-Api-Key", adminKey)
                        }
                    gravatar.status shouldBe HttpStatusCode.OK
                    gravatar.bodyAsBytes() shouldBe gravatarBytes
                    gravatar.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"
                    harness.externalRequests shouldHaveSize 2

                    // Relative Plex path delegates to adapter (not externalAvatarClient)
                    val relativeResp =
                        client.get("/api/v1/users/user-plex-relative/avatar") {
                            header("X-Api-Key", adminKey)
                        }
                    relativeResp.status shouldBe HttpStatusCode.OK
                    relativeResp.bodyAsBytes() shouldBe plexLocalAvatar
                    harness.plexRequests shouldHaveSize 1
                    harness.externalRequests shouldHaveSize 2

                    // Absolute URL matching plexBaseUrl delegates to adapter
                    val originResp =
                        client.get("/api/v1/users/user-plex-origin/avatar") {
                            header("X-Api-Key", adminKey)
                        }
                    originResp.status shouldBe HttpStatusCode.OK
                    originResp.bodyAsBytes() shouldBe plexLocalAvatar
                    harness.plexRequests shouldHaveSize 2
                    harness.externalRequests shouldHaveSize 2

                    val unlinkedAccount =
                        harness.authService
                            .createAccount(
                                username = "unlinked-miru",
                                password = "Unlinked-password-1234",
                                role = MiruRole.USER,
                                linkedMediaUserIds = emptySet(),
                            ).shouldBeRight()
                    val unlinkedKey =
                        harness.authService
                            .createApiKey(
                                accountId = unlinkedAccount.account.id,
                                name = "unlinked-key",
                                role = MiruRole.USER,
                            ).shouldBeRight()
                            .rawKey

                    // Missing user / missing thumbUrl -> 404 for ADMIN
                    client
                        .get("/api/v1/users/unknown-user/avatar") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.NotFound
                    client
                        .get("/api/v1/users/user-no-avatar/avatar") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.NotFound

                    // Unlinked USER gets 403 Forbidden for any user avatar
                    client
                        .get("/api/v1/users/user-plex-tv/avatar") { header("X-Api-Key", unlinkedKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/users/user-gravatar/avatar") { header("X-Api-Key", unlinkedKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("rejects SSRF payloads without making outbound requests and blocks 302 redirects to internal IPs") {
            val ssrfUrls =
                listOf(
                    "http://169.254.169.254/latest/meta-data",
                    "http://127.0.0.1/admin",
                    "http://[::1]/admin",
                    "http://localhost/admin",
                    "http://evil.example/avatar.png",
                    "http://plex.tv/avatar.png",
                    "https://evilplex.tv/avatar.png",
                    "https://plex.tv.evil.com/avatar.png",
                    "https://plex.tv@evil.com/avatar.png",
                    "file:///etc/passwd",
                    "/library/../etc/passwd",
                    "/library/%2e%2e/etc/passwd",
                    "//evil.com/avatar.png",
                    "https://plex.tv/avatar#frag",
                    "https://plex.tv/avatar\\test",
                )

            createArtworkHarness(
                externalHandler = { request ->
                    if (request.url.host == "plex.tv" && request.url.encodedPath == "/redirect-internal") {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "http://169.254.169.254/latest/meta-data"),
                        )
                    } else {
                        respond(ByteArray(0), HttpStatusCode.NotFound)
                    }
                },
            ).use { harness ->
                val (adminKey, _) = seedAuthAndUsers(harness)

                testApplication {
                    setupArtworkApp(harness)

                    ssrfUrls.forEachIndexed { idx, badUrl ->
                        val userId = "ssrf-user-$idx"
                        harness.userRepo
                            .upsert(
                                UserRecord(
                                    id = userId,
                                    serverId = "srv-1",
                                    externalUserId = "ssrf-$idx",
                                    username = userId,
                                    thumbUrl = badUrl,
                                    createdAtMs = 1_000L,
                                    updatedAtMs = 1_000L,
                                ),
                            ).shouldBeRight()

                        val resp =
                            client.get("/api/v1/users/$userId/avatar") {
                                header("X-Api-Key", adminKey)
                            }
                        resp.status shouldBe HttpStatusCode.BadRequest
                    }

                    // None of the SSRF attempts may hit either Plex or external HTTP clients
                    harness.plexRequests shouldHaveSize 0
                    harness.externalRequests shouldHaveSize 0

                    // Allowlisted URL returning a 302 redirect to internal IP is NOT followed and returns 502
                    harness.userRepo
                        .upsert(
                            UserRecord(
                                id = "redirect-user",
                                serverId = "srv-1",
                                externalUserId = "redir-1",
                                username = "redir",
                                thumbUrl = "https://plex.tv/redirect-internal",
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()

                    val redirectResp =
                        client.get("/api/v1/users/redirect-user/avatar") {
                            header("X-Api-Key", adminKey)
                        }
                    redirectResp.status shouldBe HttpStatusCode.BadGateway
                    harness.externalRequests shouldHaveSize 1
                }
            }
        }

        test("handles external 404, 500, oversized avatars, subdomains, and wildcard If-None-Match") {
            val subdomainBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0A)
            createArtworkHarness(
                externalHandler = { request ->
                    when (request.url.encodedPath) {
                        "/missing.png" -> respond("not found", HttpStatusCode.NotFound)
                        "/error.png" -> respond("server error", HttpStatusCode.InternalServerError)
                        "/huge-header.png" ->
                            respond(
                                content = byteArrayOf(1, 2),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentLength, (9 * 1024 * 1024).toString()),
                            )
                        "/huge-body.png" ->
                            respond(
                                content = ByteArray(8 * 1024 * 1024 + 1),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                        "/sub-avatar.png" ->
                            respond(
                                content = subdomainBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/webp"),
                            )
                        else -> respond(ByteArray(0), HttpStatusCode.NotFound)
                    }
                },
            ).use { harness ->
                val (adminKey, _) = seedAuthAndUsers(harness)
                val cases =
                    mapOf(
                        "ext-missing" to "https://plex.tv/missing.png",
                        "ext-error" to "https://plex.tv/error.png",
                        "ext-huge-header" to "https://plex.tv/huge-header.png",
                        "ext-huge-body" to "https://plex.tv/huge-body.png",
                        "ext-subdomain" to "https://images.plex.tv/sub-avatar.png",
                    )
                cases.forEach { (id, url) ->
                    harness.userRepo
                        .upsert(
                            UserRecord(
                                id = id,
                                serverId = "srv-1",
                                externalUserId = id,
                                username = id,
                                thumbUrl = url,
                                createdAtMs = 1_000L,
                                updatedAtMs = 1_000L,
                            ),
                        ).shouldBeRight()
                }

                testApplication {
                    setupArtworkApp(harness)

                    client
                        .get("/api/v1/users/ext-missing/avatar") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.NotFound
                    client
                        .get("/api/v1/users/ext-error/avatar") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.BadGateway
                    client
                        .get("/api/v1/users/ext-huge-header/avatar") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.BadGateway
                    client
                        .get("/api/v1/users/ext-huge-body/avatar") { header("X-Api-Key", adminKey) }
                        .status shouldBe HttpStatusCode.BadGateway

                    val subResp =
                        client.get("/api/v1/users/ext-subdomain/avatar") {
                            header("X-Api-Key", adminKey)
                        }
                    subResp.status shouldBe HttpStatusCode.OK
                    subResp.bodyAsBytes() shouldBe subdomainBytes
                    subResp.headers[HttpHeaders.ContentType] shouldBe "image/webp"

                    val wildcard304 =
                        client.get("/api/v1/users/ext-subdomain/avatar") {
                            header("X-Api-Key", adminKey)
                            header(HttpHeaders.IfNoneMatch, "*")
                        }
                    wildcard304.status shouldBe HttpStatusCode.NotModified
                }
            }
        }

        test("enforces library-section access control on media artwork, including against admin-warmed cache and unlinked users") {
            val movieThumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
            val episodeThumb = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x02)

            createArtworkHarness(
                plexHandler = { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/12345" ->
                            respond(
                                content =
                                    """{"MediaContainer":{"size":1,""" +
                                        """"Metadata":[{"ratingKey":"12345","type":"movie","librarySectionID":1}]}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        "/library/metadata/54321" ->
                            respond(
                                content =
                                    """{"MediaContainer":{"size":1,""" +
                                        """"Metadata":[{"ratingKey":"54321","type":"episode","librarySectionID":"2"}]}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        "/library/metadata/12345/thumb" ->
                            respond(movieThumb, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                        "/library/metadata/54321/thumb" ->
                            respond(episodeThumb, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                        else -> respond(ByteArray(0), HttpStatusCode.NotFound)
                    }
                },
            ).use { harness ->
                val (adminKey, _) = seedAuthAndUsers(harness)
                // Bob (externalUserId 102) has access only to section "2", NOT section "1".
                harness.libraryRepo
                    .replaceUserLibraryAccess(
                        serverId = "srv-1",
                        records =
                            listOf(
                                UserLibraryAccessRecord(
                                    serverId = "srv-1",
                                    externalUserId = "102",
                                    allLibraries = false,
                                    sectionKeys = setOf("2"),
                                    updatedAtMs = 1_000L,
                                ),
                            ),
                    ).shouldBeRight()

                val bobAccount =
                    harness.authService
                        .createAccount(
                            username = "bob-user",
                            password = "Bob-password-1234",
                            role = MiruRole.USER,
                            linkedMediaUserIds = setOf("user-gravatar"),
                        ).shouldBeRight()
                val bobKey =
                    harness.authService
                        .createApiKey(bobAccount.account.id, "bob-key", MiruRole.USER)
                        .shouldBeRight()
                        .rawKey

                val unlinkedAccount =
                    harness.authService
                        .createAccount(
                            username = "unlinked-art-user",
                            password = "Unlinked-password-1234",
                            role = MiruRole.USER,
                            linkedMediaUserIds = emptySet(),
                        ).shouldBeRight()
                val unlinkedKey =
                    harness.authService
                        .createApiKey(unlinkedAccount.account.id, "unlinked-key", MiruRole.USER)
                        .shouldBeRight()
                        .rawKey

                testApplication {
                    setupArtworkApp(harness)

                    // 1. ADMIN warms cache for section "1" movie 12345
                    val adminMovie =
                        client.get("/api/v1/media/12345/thumb") {
                            header("X-Api-Key", adminKey)
                        }
                    adminMovie.status shouldBe HttpStatusCode.OK
                    adminMovie.bodyAsBytes() shouldBe movieThumb
                    harness.plexRequests shouldHaveSize 1

                    // 2. Unlinked USER gets 403 Forbidden with zero Plex requests
                    client
                        .get("/api/v1/media/12345/thumb") { header("X-Api-Key", unlinkedKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/v1/media/54321/thumb") { header("X-Api-Key", unlinkedKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    harness.plexRequests shouldHaveSize 1

                    // 3. Bob gets 403 Forbidden for cached section "1" item 12345
                    client
                        .get("/api/v1/media/12345/thumb") { header("X-Api-Key", bobKey) }
                        .status shouldBe HttpStatusCode.Forbidden
                    harness.plexRequests shouldHaveSize 2

                    // 4. Bob succeeds for section "2" episode 54321; second call hits both section + artwork caches
                    val bobEp1 =
                        client.get("/api/v1/media/54321/thumb") {
                            header("X-Api-Key", bobKey)
                        }
                    bobEp1.status shouldBe HttpStatusCode.OK
                    bobEp1.bodyAsBytes() shouldBe episodeThumb
                    harness.plexRequests shouldHaveSize 4

                    val bobEp2 =
                        client.get("/api/v1/media/54321/thumb") {
                            header("X-Api-Key", bobKey)
                        }
                    bobEp2.status shouldBe HttpStatusCode.OK
                    harness.plexRequests shouldHaveSize 4
                }
            }
        }
    })
