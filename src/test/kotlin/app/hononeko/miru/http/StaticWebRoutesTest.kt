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
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.writeBytes
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

        test("negotiates precompressed .br and .gz static assets with Vary, Content-Encoding, original Content-Type, and fallbacks") {
            val root = Files.createTempDirectory("miru-static-precompress")
            try {
                fun gzipBytes(text: String): ByteArray =
                    ByteArrayOutputStream().use { out ->
                        GZIPOutputStream(out).use { gz -> gz.write(text.toByteArray(Charsets.UTF_8)) }
                        out.toByteArray()
                    }

                val html = "<!doctype html><title>Miru Compressed</title>"
                val fakeBrHtml = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                root.resolve("index.html").writeText(html)
                root.resolve("index.html.br").writeBytes(fakeBrHtml)
                root.resolve("index.html.gz").writeBytes(gzipBytes(html))

                val immutableDir = Files.createDirectories(root.resolve("_app/immutable/chunks"))
                val jsText = "console.log('precompressed js chunk');".repeat(40)
                val fakeBrJs = byteArrayOf(0x0A, 0x0B, 0x0C)
                immutableDir.resolve("chunk.1234.js").writeText(jsText)
                immutableDir.resolve("chunk.1234.js.br").writeBytes(fakeBrJs)
                immutableDir.resolve("chunk.1234.js.gz").writeBytes(gzipBytes(jsText))

                val cssText = "body { color: #ff5ca8; }"
                immutableDir.resolve("style.5678.css").writeText(cssText)
                immutableDir.resolve("style.5678.css.gz").writeBytes(gzipBytes(cssText))

                root.resolve("favicon.svg").writeText("<svg></svg>")

                testApplication {
                    application {
                        miruModule(config = testConfig(root.toString()))
                    }

                    // Prefers br over gzip when both are accepted
                    val brHtmlRes =
                        client.get("/") {
                            header(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                        }
                    brHtmlRes.status shouldBe HttpStatusCode.OK
                    brHtmlRes.headers[HttpHeaders.ContentEncoding] shouldBe "br"
                    brHtmlRes.headers[HttpHeaders.Vary] shouldBe "Accept-Encoding"
                    brHtmlRes.contentType()?.withoutParameters() shouldBe ContentType.Text.Html
                    brHtmlRes.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                    brHtmlRes.bodyAsBytes() shouldBe fakeBrHtml

                    // SPA fallback also negotiates precompressed index.html
                    val spaBrRes =
                        client.get("/libraries/1") {
                            header(HttpHeaders.AcceptEncoding, "br, gzip")
                        }
                    spaBrRes.status shouldBe HttpStatusCode.OK
                    spaBrRes.headers[HttpHeaders.ContentEncoding] shouldBe "br"
                    spaBrRes.headers[HttpHeaders.Vary] shouldBe "Accept-Encoding"
                    spaBrRes.contentType()?.withoutParameters() shouldBe ContentType.Text.Html

                    // Respects q=0 exclusion on br and falls back to gzip for immutable JS with JS Content-Type
                    val gzJsRes =
                        client.get("/_app/immutable/chunks/chunk.1234.js") {
                            header(HttpHeaders.AcceptEncoding, "br;q=0, gzip;q=0.8")
                        }
                    gzJsRes.status shouldBe HttpStatusCode.OK
                    gzJsRes.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
                    gzJsRes.headers[HttpHeaders.Vary] shouldBe "Accept-Encoding"
                    gzJsRes.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
                    gzJsRes.contentType()?.withoutParameters() shouldBe ContentType.Text.JavaScript
                    val decodedJs =
                        GZIPInputStream(ByteArrayInputStream(gzJsRes.bodyAsBytes()))
                            .bufferedReader()
                            .use { it.readText() }
                    decodedJs shouldBe jsText

                    // Higher q on gzip picks gzip over br
                    val higherQGzRes =
                        client.get("/_app/immutable/chunks/chunk.1234.js") {
                            header(HttpHeaders.AcceptEncoding, "br;q=0.5, gzip;q=1.0")
                        }
                    higherQGzRes.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"

                    // Missing .br sibling falls back to .gz when client sends br, gzip
                    val cssGzRes =
                        client.get("/_app/immutable/chunks/style.5678.css") {
                            header(HttpHeaders.AcceptEncoding, "br, gzip")
                        }
                    cssGzRes.status shouldBe HttpStatusCode.OK
                    cssGzRes.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
                    cssGzRes.headers[HttpHeaders.Vary] shouldBe "Accept-Encoding"
                    cssGzRes.contentType()?.withoutParameters() shouldBe ContentType.Text.CSS

                    // Without Accept-Encoding, serves uncompressed original with Vary header
                    val plainJsRes = client.get("/_app/immutable/chunks/chunk.1234.js")
                    plainJsRes.status shouldBe HttpStatusCode.OK
                    plainJsRes.headers[HttpHeaders.ContentEncoding] shouldBe null
                    plainJsRes.headers[HttpHeaders.Vary] shouldBe "Accept-Encoding"
                    plainJsRes.bodyAsText() shouldBe jsText

                    // All encodings q=0 serves uncompressed original
                    val zeroQRes =
                        client.get("/") {
                            header(HttpHeaders.AcceptEncoding, "br;q=0, gzip;q=0")
                        }
                    zeroQRes.headers[HttpHeaders.ContentEncoding] shouldBe null
                    zeroQRes.bodyAsText() shouldBe html

                    // Asset with no precompressed siblings serves uncompressed cleanly
                    val plainSvgRes =
                        client.get("/favicon.svg") {
                            header(HttpHeaders.AcceptEncoding, "br, gzip")
                        }
                    plainSvgRes.status shouldBe HttpStatusCode.OK
                    plainSvgRes.headers[HttpHeaders.ContentEncoding] shouldBe null
                    plainSvgRes.bodyAsText() shouldBe "<svg></svg>"
                }
            } finally {
                root.toFile().deleteRecursively()
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
