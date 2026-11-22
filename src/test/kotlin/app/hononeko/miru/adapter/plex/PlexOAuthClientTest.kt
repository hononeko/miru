package app.hononeko.miru.adapter.plex

import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant

class PlexOAuthClientTest :
    FunSpec({
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        test("createPin sends standard Plex headers, strong=true, and builds authUrl") {
            val captured = mutableListOf<HttpRequestData>()
            val engine =
                MockEngine { req ->
                    captured += req
                    respond(
                        content =
                            """
                            {
                              "id": 987654,
                              "code": "ABCD1234",
                              "clientIdentifier": "miru-client-1",
                              "expiresIn": 900,
                              "expiresAt": "2026-11-22T17:00:00Z"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.Created,
                        headers = jsonHeaders,
                    )
                }
            val client =
                PlexOAuthClient(
                    httpClient = createPlexHttpClient(engine = engine),
                    plexTvBaseUrl = "https://plex.tv",
                    appVersion = "1.2.3",
                )

            val pin = client.createPin("miru-client-1").shouldBeRight()
            pin.pinId shouldBe 987654L
            pin.code shouldBe "ABCD1234"
            pin.expiresAtMs shouldBe Instant.parse("2026-11-22T17:00:00Z").toEpochMilli()
            pin.authUrl shouldBe
                "https://app.plex.tv/auth#?clientID=miru-client-1&code=ABCD1234&context%5Bdevice%5D%5Bproduct%5D=Miru"

            val req = captured.single()
            req.method shouldBe HttpMethod.Post
            req.url.encodedPath shouldBe "/api/v2/pins"
            req.url.parameters["strong"] shouldBe "true"
            req.headers["X-Plex-Product"] shouldBe "Miru"
            req.headers["X-Plex-Version"] shouldBe "1.2.3"
            req.headers["X-Plex-Client-Identifier"] shouldBe "miru-client-1"
            req.headers["X-Plex-Device"] shouldBe "Miru Server"
            req.headers["X-Plex-Platform"] shouldBe "Web"
            pin.toString() shouldNotContain "ABCD1234"
        }

        test("pollPin maps pending, authorized, and expired states") {
            val nowMs = 1_700_000_000_000L
            val futureIso = Instant.ofEpochMilli(nowMs + 60_000L).toString()
            val pastIso = Instant.ofEpochMilli(nowMs - 1_000L).toString()
            var stage = "pending"

            val engine =
                MockEngine { req ->
                    req.url.encodedPath shouldBe "/api/v2/pins/42"
                    req.url.parameters["code"] shouldBe "CODE42"
                    when (stage) {
                        "pending" ->
                            respond(
                                content = """{"id":42,"code":"CODE42","expiresAt":"$futureIso","authToken":null}""",
                                status = HttpStatusCode.OK,
                                headers = jsonHeaders,
                            )
                        "authorized" ->
                            respond(
                                content = """{"id":42,"code":"CODE42","expiresAt":"$futureIso","authToken":"secret-oauth-tok"}""",
                                status = HttpStatusCode.OK,
                                headers = jsonHeaders,
                            )
                        "expired-ts" ->
                            respond(
                                content = """{"id":42,"code":"CODE42","expiresAt":"$pastIso","authToken":null}""",
                                status = HttpStatusCode.OK,
                                headers = jsonHeaders,
                            )
                        else -> respond("not found", HttpStatusCode.NotFound)
                    }
                }
            val client =
                PlexOAuthClient(
                    httpClient = createPlexHttpClient(engine = engine),
                    clock = { nowMs },
                )

            val pending = client.pollPin(42L, "CODE42", "cid-1").shouldBeRight()
            pending.shouldBeInstanceOf<PlexPinPollResult.Pending>()

            stage = "authorized"
            val authorized = client.pollPin(42L, "CODE42", "cid-1").shouldBeRight()
            authorized.shouldBeInstanceOf<PlexPinPollResult.Authorized>()
            authorized.authToken shouldBe "secret-oauth-tok"
            authorized.toString() shouldNotContain "secret-oauth-tok"

            stage = "expired-ts"
            client.pollPin(42L, "CODE42", "cid-1").shouldBeRight() shouldBe PlexPinPollResult.Expired

            stage = "404"
            client.pollPin(42L, "CODE42", "cid-1").shouldBeRight() shouldBe PlexPinPollResult.Expired
        }

        test("fetchResources filters server provides and prioritizes local LAN -> direct remote -> relay") {
            val engine =
                MockEngine { req ->
                    req.url.encodedPath shouldBe "/api/v2/resources"
                    req.url.parameters["includeHttps"] shouldBe "1"
                    req.url.parameters["includeRelay"] shouldBe "1"
                    req.headers["X-Plex-Token"] shouldBe "account-tok"
                    respond(
                        content =
                            """
                            [
                              {
                                "name": "Web Client",
                                "clientIdentifier": "client-1",
                                "provides": "client",
                                "connections": []
                              },
                              {
                                "name": "Home Media",
                                "productVersion": "1.41.0",
                                "platform": "Linux",
                                "clientIdentifier": "server-mach-1",
                                "provides": "server",
                                "owned": 1,
                                "presence": 1,
                                "accessToken": "srv-secret-tok",
                                "connections": [
                                  {"protocol":"https","uri":"https://1-2-3-4.plex.direct:32400","local":0,"relay":1,"IPv6":0},
                                  {"protocol":"http","uri":"http://203.0.113.10:32400","local":0,"relay":0,"IPv6":0},
                                  {"protocol":"https","uri":"https://plex.example.com:32400","local":0,"relay":0,"IPv6":0},
                                  {"protocol":"http","uri":"http://192.168.1.50:32400/","local":1,"relay":0,"IPv6":0},
                                  {"protocol":"https","uri":"https://192-168-1-50.plex.direct:32400","local":1,"relay":0,"IPv6":0}
                                ]
                              }
                            ]
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders,
                    )
                }
            val client = PlexOAuthClient(httpClient = createPlexHttpClient(engine = engine))

            val servers = client.fetchResources("account-tok", "cid-1").shouldBeRight()
            servers shouldHaveSize 1
            val server = servers.single()
            server.name shouldBe "Home Media"
            server.machineId shouldBe "server-mach-1"
            server.owned shouldBe true
            server.accessToken shouldBe "srv-secret-tok"
            server.toString() shouldNotContain "srv-secret-tok"

            server.connections.map { it.uri } shouldContainExactly
                listOf(
                    "https://192-168-1-50.plex.direct:32400",
                    "http://192.168.1.50:32400",
                    "https://plex.example.com:32400",
                    "http://203.0.113.10:32400",
                    "https://1-2-3-4.plex.direct:32400",
                )
        }

        test("maps upstream errors and redacts tokens from failure messages") {
            val secretToken = "super-secret-plex-tv-token"
            val authFailEngine =
                MockEngine {
                    respond("Unauthorized", HttpStatusCode.Unauthorized)
                }
            val authClient = PlexOAuthClient(httpClient = createPlexHttpClient(engine = authFailEngine))
            val authErr = authClient.fetchResources(secretToken, "cid-1").shouldBeLeft()
            authErr.shouldBeInstanceOf<MiruError.UpstreamAuthError>()
            authErr.message shouldNotContain secretToken

            val networkFailEngine =
                MockEngine {
                    error("Connection reset while sending token=$secretToken")
                }
            val netClient = PlexOAuthClient(httpClient = createPlexHttpClient(engine = networkFailEngine))
            val netErr = netClient.fetchResources(secretToken, "cid-1").shouldBeLeft()
            netErr.shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
            netErr.message shouldNotContain secretToken
            netErr.message shouldContain "***"

            val notFoundClient =
                PlexOAuthClient(httpClient = createPlexHttpClient(engine = MockEngine { respond("Missing", HttpStatusCode.NotFound) }))
            notFoundClient.createPin("cid-1").shouldBeLeft().shouldBeInstanceOf<MiruError.NotFound>()

            val serverErrClient =
                PlexOAuthClient(
                    httpClient = createPlexHttpClient(engine = MockEngine { respond("Boom", HttpStatusCode.InternalServerError) }),
                )
            serverErrClient.fetchResources(secretToken, "cid-1").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()

            val malformedJsonClient =
                PlexOAuthClient(
                    httpClient = createPlexHttpClient(engine = MockEngine { respond("{not-json", HttpStatusCode.OK, jsonHeaders) }),
                )
            malformedJsonClient.createPin("cid-1").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
        }

        test("validates blank inputs and redacts tokens in Plex DTO toString") {
            val client = PlexOAuthClient(httpClient = createPlexHttpClient(engine = MockEngine { error("unused") }))
            client.createPin("   ").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()
            client.fetchResources("   ", "cid-1").shouldBeLeft().shouldBeInstanceOf<MiruError.Unauthorized>()

            val pinDto = PlexPinDto(id = 1L, code = "secret-code", authToken = "secret-auth-tok")
            pinDto.toString() shouldNotContain "secret-code"
            pinDto.toString() shouldNotContain "secret-auth-tok"

            val resDto = PlexResourceDto(name = "Plex", clientIdentifier = "mach-1", accessToken = "secret-srv-tok")
            resDto.toString() shouldNotContain "secret-srv-tok"

            val authorized = PlexPinPollResult.Authorized("secret-poll-tok")
            authorized.toString() shouldNotContain "secret-poll-tok"
        }
    })
