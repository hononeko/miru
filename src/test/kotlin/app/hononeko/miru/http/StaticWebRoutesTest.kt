package app.hononeko.miru.http

import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AppConfigLoader
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeText

class StaticWebRoutesTest :
    FunSpec({
        fun testConfig(staticWebDir: String?): AppConfig =
            AppConfig(
                server = ServerConfig(host = "127.0.0.1", port = 8080, staticWebDir = staticWebDir),
                database = DatabaseConfig.Sqlite(path = ":memory:"),
                plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "test-token"),
                session = SessionConfig(),
                auth = AuthConfig(mode = AuthMode.NONE),
            )

        test("serves index.html, static assets with cache headers, SPA fallback, and never shadows API or health routes") {
            val root = Files.createTempDirectory("miru-static-web")
            try {
                root.resolve("index.html").writeText("<!doctype html><title>Miru PWA</title>")
                root.resolve("manifest.webmanifest").writeText("""{"name":"Miru"}""")
                root.resolve("service-worker.js").writeText("self.addEventListener('install', () => {});")
                root.resolve("favicon.svg").writeText("<svg></svg>")
                val immutableDir = Files.createDirectories(root.resolve("_app/immutable/chunks"))
                immutableDir.resolve("app.abc123.js").writeText("console.log('miru');")

                val secretOutside = root.parent.resolve("outside-secret.txt")
                secretOutside.writeText("TOP_SECRET")

                testApplication {
                    application {
                        miruModule(config = testConfig(root.toString()))
                    }

                    val rootRes = client.get("/")
                    rootRes.status shouldBe HttpStatusCode.OK
                    rootRes.bodyAsText() shouldContain "Miru PWA"
                    rootRes.headers[HttpHeaders.CacheControl] shouldBe "no-cache"

                    val spaRouteRes = client.get("/users/u-123")
                    spaRouteRes.status shouldBe HttpStatusCode.OK
                    spaRouteRes.bodyAsText() shouldContain "Miru PWA"
                    spaRouteRes.headers[HttpHeaders.CacheControl] shouldBe "no-cache"

                    val immutableRes = client.get("/_app/immutable/chunks/app.abc123.js")
                    immutableRes.status shouldBe HttpStatusCode.OK
                    immutableRes.bodyAsText() shouldContain "console.log('miru')"
                    immutableRes.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"

                    val manifestRes = client.get("/manifest.webmanifest")
                    manifestRes.status shouldBe HttpStatusCode.OK
                    manifestRes.headers[HttpHeaders.CacheControl] shouldBe "no-cache"

                    val swRes = client.get("/service-worker.js")
                    swRes.status shouldBe HttpStatusCode.OK
                    swRes.headers[HttpHeaders.CacheControl] shouldBe "no-cache"

                    val iconRes = client.get("/favicon.svg")
                    iconRes.status shouldBe HttpStatusCode.OK
                    iconRes.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=3600"

                    client.get("/_app/immutable/chunks/missing.js").status shouldBe HttpStatusCode.NotFound
                    client.get("/%2e%2e/outside-secret.txt").status shouldBe HttpStatusCode.NotFound
                    client.get("/api/v1/does-not-exist").status shouldBe HttpStatusCode.NotFound

                    val healthRes = client.get("/healthz")
                    healthRes.status shouldBe HttpStatusCode.OK
                    healthRes.bodyAsText() shouldContain "\"status\":\"ok\""
                }

                Files.deleteIfExists(secretOutside)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        test("gracefully disables SPA serving when staticWebDir is null or missing index.html") {
            val emptyDir = Files.createTempDirectory("miru-static-empty")
            try {
                testApplication {
                    application {
                        miruModule(config = testConfig(emptyDir.toString()))
                    }
                    client.get("/").status shouldBe HttpStatusCode.NotFound
                    client.get("/healthz").status shouldBe HttpStatusCode.OK
                }

                testApplication {
                    application {
                        miruModule(config = testConfig(null))
                    }
                    client.get("/").status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                emptyDir.toFile().deleteRecursively()
            }
        }

        test("AppConfigLoader parses STATIC_WEB_DIR into ServerConfig") {
            val loaded =
                AppConfigLoader
                    .load(
                        env =
                            mapOf(
                                "PLEX_URL" to "http://plex.local:32400",
                                "PLEX_TOKEN" to "secret-token",
                                "AUTH_MODE" to "none",
                                "STATIC_WEB_DIR" to "/opt/miru/static",
                            ),
                    ).shouldBeRight()
            loaded.server.staticWebDir shouldBe "/opt/miru/static"
        }
    })
