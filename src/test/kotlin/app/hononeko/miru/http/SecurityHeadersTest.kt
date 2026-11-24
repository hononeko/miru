package app.hononeko.miru.http

import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.writeText

class SecurityHeadersTest :
    FunSpec({
        fun expectedSha256Source(body: String): String {
            val normalized = body.replace("\r\n", "\n").replace("\r", "\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return "'sha256-${Base64.getEncoder().encodeToString(digest)}'"
        }

        test("extractInlineScriptSha256Hashes hashes inline scripts with CRLF normalization and skips external src scripts") {
            val inlineOne = "\n\tconsole.log('boot');\n"
            val inlineTwoCrlf = "\r\nwindow.__x = 1;\r\n"
            val html =
                """
                <!doctype html>
                <html>
                  <head>
                    <script src="/theme-init.js"></script>
                    <script type="module" src="/app.js"></script>
                    <script>   </script>
                  </head>
                  <body>
                    <script>$inlineOne</script>
                    <SCRIPT defer>$inlineTwoCrlf</SCRIPT>
                    <script>$inlineOne</script>
                  </body>
                </html>
                """.trimIndent()

            val hashes = extractInlineScriptSha256Hashes(html)
            hashes shouldContainExactly
                listOf(
                    expectedSha256Source(inlineOne),
                    expectedSha256Source(inlineTwoCrlf),
                )
        }

        test("emits global security headers on static web routes with SvelteKit inline script hashes and strict API CSP") {
            val dir = Files.createTempDirectory("miru-sec-headers-")
            val webDir = Files.createDirectory(dir.resolve("static"))
            val inlineScript = "\nconst kit = { start: true };\n"
            val expectedHash = expectedSha256Source(inlineScript)
            webDir.resolve("index.html").writeText(
                "<!doctype html><html><head><script src=\"/theme-init.js\"></script></head>" +
                    "<body><script>$inlineScript</script></body></html>",
            )
            webDir.resolve("theme-init.js").writeText("console.log('theme');")

            val dbPath = dir.resolve("sec-headers.db").toString()
            val dbHandle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
            val authConfig =
                AuthConfig(
                    mode = AuthMode.LOCAL,
                    sessionSecret = "sec-headers-test-session-secret-32-chars",
                )
            val authService =
                AuthService(
                    accountRepository = ExposedAuthAccountRepository(dbHandle),
                    sessionRepository = ExposedAuthSessionRepository(dbHandle),
                    apiKeyRepository = ExposedApiKeyRepository(dbHandle),
                    userRepository = ExposedUserRepository(dbHandle),
                    passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
                    authConfig = authConfig,
                )
            val appConfig =
                AppConfig(
                    server = ServerConfig(host = "127.0.0.1", port = 8080, staticWebDir = webDir.toString()),
                    database = DatabaseConfig.Sqlite(dbPath),
                    plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "plex-token"),
                    session = SessionConfig(),
                    auth = authConfig,
                )

            try {
                testApplication {
                    application {
                        miruModule(config = appConfig, authService = authService)
                    }

                    for (webPath in listOf("/", "/users/u-123", "/theme-init.js")) {
                        val res = client.get(webPath)
                        res.status shouldBe HttpStatusCode.OK
                        res.headers["X-Content-Type-Options"] shouldBe "nosniff"
                        res.headers.getAll("X-Content-Type-Options")!! shouldHaveSize 1
                        res.headers["X-Frame-Options"] shouldBe "DENY"
                        res.headers["Referrer-Policy"] shouldBe "strict-origin-when-cross-origin"
                        res.headers["Permissions-Policy"] shouldBe "camera=(), microphone=(), geolocation=()"

                        val csp = res.headers["Content-Security-Policy"]!!
                        csp shouldContain "default-src 'self'"
                        csp shouldContain "script-src 'self' $expectedHash"
                        val scriptDirective = csp.split(';').first { it.trim().startsWith("script-src") }
                        scriptDirective shouldNotContain "'unsafe-inline'"
                        csp shouldContain "frame-ancestors 'none'"
                        csp shouldContain "object-src 'none'"
                        csp shouldContain "base-uri 'self'"
                        csp shouldContain "form-action 'self'"
                        csp shouldContain "https://fonts.googleapis.com"
                        csp shouldContain "https://fonts.gstatic.com"
                    }

                    for (apiCase in listOf(
                        "/healthz" to HttpStatusCode.OK,
                        "/readyz" to HttpStatusCode.OK,
                        "/api/v1/auth/status" to HttpStatusCode.OK,
                        "/api/v1/auth/me" to HttpStatusCode.Unauthorized,
                        "/api/v1/nonexistent" to HttpStatusCode.NotFound,
                    )) {
                        val (path, expectedStatus) = apiCase
                        val res = client.get(path)
                        res.status shouldBe expectedStatus
                        res.headers["X-Content-Type-Options"] shouldBe "nosniff"
                        res.headers["X-Frame-Options"] shouldBe "DENY"
                        res.headers["Referrer-Policy"] shouldBe "strict-origin-when-cross-origin"
                        res.headers["Permissions-Policy"] shouldBe "camera=(), microphone=(), geolocation=()"
                        res.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
                    }
                }
            } finally {
                dbHandle.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
