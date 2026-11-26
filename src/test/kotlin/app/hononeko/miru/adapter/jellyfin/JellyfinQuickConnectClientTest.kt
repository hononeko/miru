package app.hononeko.miru.adapter.jellyfin

import app.hononeko.miru.domain.error.MiruError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
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

class JellyfinQuickConnectClientTest :
    FunSpec({
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        test("fetchPublicSystemInfo parses server metadata and rejects non-Jellyfin payloads") {
            val okClient =
                JellyfinQuickConnectClient(
                    httpClient =
                        createJellyfinHttpClient(
                            MockEngine { req ->
                                req.url.encodedPath shouldBe "/System/Info/Public"
                                respond(
                                    """{"Id":"jf-1","ServerName":"Home Jellyfin","Version":"10.10.3"}""",
                                    HttpStatusCode.OK,
                                    jsonHeaders,
                                )
                            },
                        ),
                )
            val info = okClient.fetchPublicSystemInfo("http://jf.local:8096/").shouldBeRight()
            info.id shouldBe "jf-1"
            info.serverName shouldBe "Home Jellyfin"
            info.version shouldBe "10.10.3"

            val htmlClient =
                JellyfinQuickConnectClient(
                    httpClient =
                        createJellyfinHttpClient(
                            MockEngine {
                                respond("<html>Not Jellyfin</html>", HttpStatusCode.OK)
                            },
                        ),
                )
            htmlClient.fetchPublicSystemInfo("http://other.local").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()

            val missingFieldsClient =
                JellyfinQuickConnectClient(
                    httpClient =
                        createJellyfinHttpClient(
                            MockEngine {
                                respond("""{"Version":"1.0"}""", HttpStatusCode.OK, jsonHeaders)
                            },
                        ),
                )
            missingFieldsClient.fetchPublicSystemInfo("http://other.local").shouldBeLeft().shouldBeInstanceOf<MiruError.ValidationError>()

            val downClient =
                JellyfinQuickConnectClient(
                    httpClient =
                        createJellyfinHttpClient(
                            MockEngine { error("connection refused") },
                        ),
                )
            downClient.fetchPublicSystemInfo("http://down.local:8096").shouldBeLeft().shouldBeInstanceOf<MiruError.UpstreamUnavailable>()
        }

        test("initiateQuickConnect, checkQuickConnect, and authenticateWithQuickConnect send MediaBrowser headers and redact secrets") {
            val captured = mutableListOf<HttpRequestData>()
            val secret = "qc-secret-value-123"
            val tempToken = "temp-access-token-456"
            var pollPhase = "pending"

            val client =
                JellyfinQuickConnectClient(
                    httpClient =
                        createJellyfinHttpClient(
                            MockEngine { req ->
                                captured += req
                                when (req.url.encodedPath) {
                                    "/QuickConnect/Enabled" ->
                                        respond("true", HttpStatusCode.OK, jsonHeaders)

                                    "/QuickConnect/Initiate" -> {
                                        req.method shouldBe HttpMethod.Post
                                        val auth = req.headers[HttpHeaders.Authorization].orEmpty()
                                        auth shouldContain
                                            """MediaBrowser Client="Miru", Device="Miru", DeviceId="miru-dev-1", Version="1.2.3""""
                                        auth shouldNotContain "Token="
                                        respond(
                                            """{"Secret":"$secret","Code":"482910","DateAdded":"2026-11-26T18:00:00Z"}""",
                                            HttpStatusCode.OK,
                                            jsonHeaders,
                                        )
                                    }

                                    "/QuickConnect/Connect" -> {
                                        req.url.parameters["secret"] shouldBe secret
                                        when (pollPhase) {
                                            "pending" ->
                                                respond(
                                                    """{"Authenticated":false,"Secret":"$secret","Code":"482910"}""",
                                                    HttpStatusCode.OK,
                                                    jsonHeaders,
                                                )
                                            "authenticated" ->
                                                respond(
                                                    """{"Authenticated":true,"Secret":"$secret","Code":"482910"}""",
                                                    HttpStatusCode.OK,
                                                    jsonHeaders,
                                                )
                                            else ->
                                                respond("Not found", HttpStatusCode.NotFound)
                                        }
                                    }

                                    "/Users/AuthenticateWithQuickConnect" -> {
                                        req.method shouldBe HttpMethod.Post
                                        respond(
                                            """
                                            {
                                              "AccessToken": "$tempToken",
                                              "User": {"Id": "u1", "Name": "alice", "Policy": {"IsAdministrator": true}}
                                            }
                                            """.trimIndent(),
                                            HttpStatusCode.OK,
                                            jsonHeaders,
                                        )
                                    }

                                    "/Auth/Keys" -> {
                                        if (req.method == HttpMethod.Post) {
                                            req.url.parameters["app"] shouldBe "Miru"
                                            req.headers[HttpHeaders.Authorization].orEmpty() shouldContain """Token="$tempToken""""
                                            respond("", HttpStatusCode.NoContent)
                                        } else {
                                            respond(
                                                """
                                                {
                                                  "Items": [
                                                    {"Id":1,"AccessToken":"old-key","AppName":"Miru","IsActive":true,"DateCreated":"2026-11-26T17:50:00Z"},
                                                    {"Id":2,"AccessToken":"revoked-key","AppName":"Miru","IsActive":false,"DateCreated":"2026-11-26T18:05:00Z"},
                                                    {"Id":3,"AccessToken":"new-api-key-789","AppName":"Miru","IsActive":true,"DateCreated":"2026-11-26T18:01:00.1234567Z"}
                                                  ],
                                                  "TotalRecordCount": 3
                                                }
                                                """.trimIndent(),
                                                HttpStatusCode.OK,
                                                jsonHeaders,
                                            )
                                        }
                                    }

                                    "/Sessions/Logout" -> respond("", HttpStatusCode.NoContent)
                                    else -> respond("Not found", HttpStatusCode.NotFound)
                                }
                            },
                        ),
                    appVersion = "1.2.3",
                )

            client.isQuickConnectEnabled("http://jf.local:8096").shouldBeRight() shouldBe true

            val initiated = client.initiateQuickConnect("http://jf.local:8096", "miru-dev-1").shouldBeRight()
            initiated.secret shouldBe secret
            initiated.code shouldBe "482910"
            initiated.toString() shouldNotContain secret
            initiated.toString() shouldNotContain "482910"

            client.checkQuickConnect("http://jf.local:8096", secret, "miru-dev-1").shouldBeRight() shouldBe
                JellyfinQuickConnectPollOutcome.Pending

            pollPhase = "authenticated"
            client.checkQuickConnect("http://jf.local:8096", secret, "miru-dev-1").shouldBeRight() shouldBe
                JellyfinQuickConnectPollOutcome.Authenticated

            pollPhase = "expired"
            client.checkQuickConnect("http://jf.local:8096", secret, "miru-dev-1").shouldBeRight() shouldBe
                JellyfinQuickConnectPollOutcome.Expired

            val session = client.authenticateWithQuickConnect("http://jf.local:8096", secret, "miru-dev-1").shouldBeRight()
            session.accessToken shouldBe tempToken
            session.isAdministrator shouldBe true
            session.toString() shouldNotContain tempToken

            client.createApiKey("http://jf.local:8096", tempToken, "miru-dev-1").shouldBeRight()
            val keys = client.listApiKeys("http://jf.local:8096", tempToken, "miru-dev-1").shouldBeRight()
            keys shouldHaveSize 2
            keys[1].accessToken shouldBe "new-api-key-789"
            keys[1].dateCreatedMs shouldBe Instant.parse("2026-11-26T18:01:00.1234567Z").toEpochMilli()
            keys[1].toString() shouldNotContain "new-api-key-789"

            client.logoutSessionBestEffort("http://jf.local:8096", tempToken, "miru-dev-1")
        }

        test("redacts secret and accessToken from error messages and DTO toStrings") {
            val secret = "super-qc-secret"
            val accessToken = "super-temp-token"
            val oldKey = "super-old-api-key"

            val failClient =
                JellyfinQuickConnectClient(
                    httpClient =
                        createJellyfinHttpClient(
                            MockEngine {
                                error("Socket reset with $secret and $accessToken and $oldKey")
                            },
                        ),
                )

            val pollErr = failClient.checkQuickConnect("http://jf.local:8096", secret, "dev-1").shouldBeLeft()
            pollErr.message shouldNotContain secret
            pollErr.message shouldContain "***"

            val keyErr = failClient.createApiKey("http://jf.local:8096", accessToken, "dev-1").shouldBeLeft()
            keyErr.message shouldNotContain accessToken
            keyErr.message shouldContain "***"

            // Best-effort helpers swallow upstream errors cleanly
            failClient.logoutSessionBestEffort("http://jf.local:8096", accessToken, "dev-1")
            failClient.deleteApiKeyBestEffort("http://jf.local:8096", accessToken, oldKey, "dev-1")

            JellyfinQuickConnectResultDto(secret = secret, code = "123456").toString().let {
                it shouldNotContain secret
                it shouldNotContain "123456"
            }
            JellyfinQuickConnectAuthRequest(secret = secret).toString() shouldNotContain secret
            JellyfinAuthenticationResultDto(accessToken = accessToken).toString() shouldNotContain accessToken
            JellyfinAuthenticationInfoDto(accessToken = oldKey).toString() shouldNotContain oldKey
        }
    })
