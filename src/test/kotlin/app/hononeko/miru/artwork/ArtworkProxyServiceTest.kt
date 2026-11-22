package app.hononeko.miru.artwork

import app.hononeko.miru.adapter.ArtworkData
import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.auth.AuthSource
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.http.toProblemDetails
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk

private class InMemoryUserRepository(
    users: List<UserRecord>,
) : UserRepository {
    private val byId = users.associateBy { it.id }.toMutableMap()

    override suspend fun upsert(user: UserRecord): Either<MiruError.PersistenceError, UserRecord> {
        byId[user.id] = user
        return user.right()
    }

    override suspend fun findById(id: String): Either<MiruError.PersistenceError, UserRecord?> = byId[id].right()

    override suspend fun findByExternalId(
        serverId: String,
        externalUserId: String,
    ): Either<MiruError.PersistenceError, UserRecord?> =
        byId.values.firstOrNull { it.serverId == serverId && it.externalUserId == externalUserId }.right()

    override suspend fun findByServer(serverId: String): Either<MiruError.PersistenceError, List<UserRecord>> =
        byId.values.filter { it.serverId == serverId }.right()

    override suspend fun findAll(): Either<MiruError.PersistenceError, List<UserRecord>> = byId.values.toList().right()

    override suspend fun updateLastSeen(
        id: String,
        lastSeenAtMs: Long,
        updatedAtMs: Long,
    ): Either<MiruError.PersistenceError, Boolean> = false.right()
}

private val adminPrincipal =
    MiruPrincipal(
        accountId = "acc-admin",
        username = "admin",
        email = null,
        role = MiruRole.ADMIN,
        linkedMediaUserIds = emptySet(),
        authSource = AuthSource.SESSION_COOKIE,
    )

private fun userWithThumb(
    id: String,
    thumbUrl: String,
): UserRecord =
    UserRecord(
        id = id,
        serverId = "srv-1",
        externalUserId = "ext-$id",
        username = id,
        thumbUrl = thumbUrl,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun createService(
    users: List<UserRecord>,
    plexBaseUrl: String? = "http://plex.local:32400",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<ArtworkProxyService, MutableList<HttpRequestData>> {
    val requests = mutableListOf<HttpRequestData>()
    val client =
        createAvatarHttpClient(
            engine =
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
        )
    val adapter = mockk<MediaServerAdapter>(relaxed = true)
    val service =
        ArtworkProxyService(
            adapter = adapter,
            userRepository = InMemoryUserRepository(users),
            externalAvatarClient = client,
            cache = BoundedArtworkCache(),
            plexBaseUrl = plexBaseUrl,
        )
    return service to requests
}

class ArtworkProxyServiceTest :
    FunSpec({
        test("follows allowlisted 302 redirects from plex.tv to assets.plex.tv and secure.gravatar.com and caches") {
            val assetsPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01)
            val gravatarJpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x02)
            val relativePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x03)

            val (service, requests) =
                createService(
                    users =
                        listOf(
                            userWithThumb("u-assets", "https://plex.tv/users/hash1/avatar?c=100"),
                            userWithThumb("u-gravatar", "https://plex.tv/users/hash2/avatar?c=200"),
                            userWithThumb("u-relative", "https://plex.tv/users/hash3/avatar?c=300"),
                        ),
                ) { request ->
                    when (request.url.toString()) {
                        "https://plex.tv/users/hash1/avatar?c=100" ->
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "https://assets.plex.tv/avatars/hash1.png"),
                            )
                        "https://assets.plex.tv/avatars/hash1.png" ->
                            respond(
                                content = assetsPng,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                        "https://plex.tv/users/hash2/avatar?c=200" ->
                            respond(
                                content = "",
                                status = HttpStatusCode.MovedPermanently,
                                headers = headersOf(HttpHeaders.Location, "https://secure.gravatar.com/avatar/hash2?s=200"),
                            )
                        "https://secure.gravatar.com/avatar/hash2?s=200" ->
                            respond(
                                content = gravatarJpg,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                            )
                        "https://plex.tv/users/hash3/avatar?c=300" ->
                            respond(
                                content = "",
                                status = HttpStatusCode.TemporaryRedirect,
                                headers = headersOf(HttpHeaders.Location, "/avatars/relative-hash3.png"),
                            )
                        "https://plex.tv/avatars/relative-hash3.png" ->
                            respond(
                                content = relativePng,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/png"),
                            )
                        else -> error("Unexpected external request: ${request.url}")
                    }
                }

            val assetsFirst = service.getUserAvatar(adminPrincipal, "u-assets").shouldBeRight()
            assetsFirst.bytes shouldBe assetsPng
            assetsFirst.contentType shouldBe "image/png"
            requests shouldHaveSize 2

            // Second call hits cache under original cacheKey without hitting network
            val assetsCached = service.getUserAvatar(adminPrincipal, "u-assets").shouldBeRight()
            assetsCached shouldBe assetsFirst
            requests shouldHaveSize 2

            val gravatar = service.getUserAvatar(adminPrincipal, "u-gravatar").shouldBeRight()
            gravatar.bytes shouldBe gravatarJpg
            gravatar.contentType shouldBe "image/jpeg"
            requests shouldHaveSize 4

            val relative = service.getUserAvatar(adminPrincipal, "u-relative").shouldBeRight()
            relative.bytes shouldBe relativePng
            requests shouldHaveSize 6
        }

        test("allows multi-hop redirect chain up to 3 hops and rejects >3 hops with 502 UpstreamUnavailable") {
            val finalBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x09)
            val (service, requests) =
                createService(
                    users =
                        listOf(
                            userWithThumb("u-3hops", "https://plex.tv/hop0"),
                            userWithThumb("u-4hops", "https://plex.tv/chain0"),
                        ),
                ) { request ->
                    when (request.url.toString()) {
                        "https://plex.tv/hop0" ->
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://assets.plex.tv/hop1"))
                        "https://assets.plex.tv/hop1" ->
                            respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location, "https://gravatar.com/hop2"))
                        "https://gravatar.com/hop2" ->
                            respond(
                                content = "",
                                status = HttpStatusCode.PermanentRedirect,
                                headers = headersOf(HttpHeaders.Location, "https://secure.gravatar.com/hop3"),
                            )
                        "https://secure.gravatar.com/hop3" ->
                            respond(finalBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))

                        "https://plex.tv/chain0" ->
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://assets.plex.tv/chain1"))
                        "https://assets.plex.tv/chain1" ->
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://gravatar.com/chain2"))
                        "https://gravatar.com/chain2" ->
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://secure.gravatar.com/chain3"))
                        "https://secure.gravatar.com/chain3" ->
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://plex.tv/chain4"))
                        "https://plex.tv/chain4" ->
                            respond(finalBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                        else -> error("Unexpected request: ${request.url}")
                    }
                }

            val ok = service.getUserAvatar(adminPrincipal, "u-3hops").shouldBeRight()
            ok.bytes shouldBe finalBytes
            requests shouldHaveSize 4

            // Cached on second read
            service.getUserAvatar(adminPrincipal, "u-3hops").shouldBeRight() shouldBe ok
            requests shouldHaveSize 4

            // 4th redirect hop must be rejected with 502 without fetching chain4
            val err = service.getUserAvatar(adminPrincipal, "u-4hops").shouldBeLeft()
            err.shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            err.provider shouldBe "avatar"
            err.toProblemDetails().status shouldBe 502
            requests shouldHaveSize 8
        }

        test(
            "blocks redirects to HTTP downgrade, internal IPs, local Plex origin, " +
                "disallowed hosts, traversal, or missing Location with 502",
        ) {
            val blockedTargets =
                mapOf(
                    "u-http" to "http://assets.plex.tv/avatar.png",
                    "u-loopback" to "https://127.0.0.1/admin",
                    "u-metadata" to "https://169.254.169.254/latest/meta-data",
                    "u-rfc1918" to "https://10.0.0.1/internal.png",
                    "u-ipv6" to "https://[::1]/internal.png",
                    "u-evil" to "https://evil.example/avatar.png",
                    "u-suffix-spoof" to "https://plex.tv.evil.com/avatar.png",
                    "u-userinfo" to "https://plex.tv@evil.com/avatar.png",
                    "u-plex-origin" to "http://plex.local:32400/users/1/avatar",
                    "u-traversal" to "https://plex.tv/avatars/../secret.png",
                )
            val users =
                blockedTargets.keys.map { id -> userWithThumb(id, "https://plex.tv/redir/$id") } +
                    listOf(
                        userWithThumb("u-missing-loc", "https://plex.tv/redir/u-missing-loc"),
                        userWithThumb("u-blank-loc", "https://plex.tv/redir/u-blank-loc"),
                    )

            val (service, requests) =
                createService(users = users) { request ->
                    val path = request.url.encodedPath
                    if (!path.startsWith("/redir/")) {
                        error("Blocked redirect target was unexpectedly requested: ${request.url}")
                    }
                    val id = path.removePrefix("/redir/")
                    when (id) {
                        "u-missing-loc" -> respond("", HttpStatusCode.Found)
                        "u-blank-loc" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "   "))
                        else ->
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, blockedTargets.getValue(id)),
                            )
                    }
                }

            val allIds = blockedTargets.keys + listOf("u-missing-loc", "u-blank-loc")
            allIds.forEach { id ->
                val error = service.getUserAvatar(adminPrincipal, id).shouldBeLeft()
                val upstream = error.shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
                upstream.provider shouldBe "avatar"
                upstream.toProblemDetails().status shouldBe 502
            }

            // Exactly 1 outbound request per user (only the initial allowlisted plex.tv hop, never the blocked target)
            requests shouldHaveSize allIds.size
        }
    })
