package app.hononeko.miru.http

import app.hononeko.miru.MiruJson
import app.hononeko.miru.auth.Argon2Params
import app.hononeko.miru.auth.Argon2idPasswordHasher
import app.hononeko.miru.auth.AuthService
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.SESSION_COOKIE_NAME
import app.hononeko.miru.config.AppConfig
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.config.ServerConfig
import app.hononeko.miru.config.SessionConfig
import app.hononeko.miru.importer.TautulliImportReport
import app.hononeko.miru.importer.TautulliImporterService
import app.hononeko.miru.miruModule
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.DatabaseHandle
import app.hononeko.miru.persistence.model.HistoryFilter
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.cookie
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

private class AdminImportHarness(
    val tempDir: Path,
    val dbHandle: DatabaseHandle,
    val appConfig: AppConfig,
    val authService: AuthService,
    val historyRepo: ExposedPlaybackHistoryRepository,
    val importerService: TautulliImporterService,
) : AutoCloseable {
    override fun close() {
        dbHandle.close()
        tempDir.toFile().deleteRecursively()
    }
}

private fun createAdminImportHarness(): AdminImportHarness {
    val dir = Files.createTempDirectory("miru-admin-import-test-")
    val dbPath = dir.resolve("miru.db").toString()
    val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath)).shouldBeRight()
    val authConfig =
        AuthConfig(
            mode = AuthMode.LOCAL,
            sessionSecret = "super-secret-session-signing-key-32-chars",
        )
    val userRepo = ExposedUserRepository(handle)
    val historyRepo = ExposedPlaybackHistoryRepository(handle)
    val serverRepo = ExposedMediaServerRepository(handle)
    val authService =
        AuthService(
            accountRepository = ExposedAuthAccountRepository(handle),
            sessionRepository = ExposedAuthSessionRepository(handle),
            apiKeyRepository = ExposedApiKeyRepository(handle),
            userRepository = userRepo,
            passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
            authConfig = authConfig,
        )
    val appConfig =
        AppConfig(
            server = ServerConfig(host = "127.0.0.1", port = 8080),
            database = DatabaseConfig.Sqlite(dbPath),
            plex = PlexConfig(baseUrl = "http://plex.local:32400", token = "secret-plex-token"),
            session = SessionConfig(),
            auth = authConfig,
        )
    val importerService =
        TautulliImporterService(
            mediaServerRepository = serverRepo,
            userRepository = userRepo,
            playbackHistoryRepository = historyRepo,
            fallbackBaseUrl = appConfig.plex.baseUrl,
        )
    return AdminImportHarness(
        tempDir = dir,
        dbHandle = handle,
        appConfig = appConfig,
        authService = authService,
        historyRepo = historyRepo,
        importerService = importerService,
    )
}

private fun createSampleTautulliBytes(tempDir: Path): ByteArray {
    val dbFile = tempDir.resolve("upload-fixture.db")
    DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("CREATE TABLE users (user_id INTEGER, username TEXT, friendly_name TEXT)")
            stmt.executeUpdate(
                "CREATE TABLE session_history (id INTEGER PRIMARY KEY, reference_id INTEGER, started INTEGER, stopped INTEGER, " +
                    "rating_key TEXT, user_id INTEGER, user TEXT, paused_counter INTEGER, view_offset INTEGER, media_type TEXT)",
            )
            stmt.executeUpdate("CREATE TABLE session_history_metadata (id INTEGER PRIMARY KEY, title TEXT, duration INTEGER)")
            stmt.executeUpdate("CREATE TABLE session_history_media_info (id INTEGER PRIMARY KEY, transcode_decision TEXT)")
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'alice', 'Alice')")
            stmt.executeUpdate(
                "INSERT INTO session_history VALUES (1, 100, 1700000000, 1700001800, 'rk-1', 1, 'alice', 0, 1700, 'movie')",
            )
            stmt.executeUpdate("INSERT INTO session_history_metadata VALUES (1, 'Dune', 1800)")
            stmt.executeUpdate("INSERT INTO session_history_media_info VALUES (1, 'direct play')")
        }
    }
    return Files.readAllBytes(dbFile)
}

class AdminImportRoutesTest :
    FunSpec({
        test("enforces admin RBAC, supports dryRun and idempotent multipart import, and validates bad uploads") {
            createAdminImportHarness().use { harness ->
                val adminLogin = harness.authService.setupInitialAdmin("admin", "Admin-password-123").shouldBeRight()
                harness.authService.createAccount("viewer", "Viewer-password-123", role = MiruRole.USER).shouldBeRight()
                val userLogin = harness.authService.login("viewer", "Viewer-password-123").shouldBeRight()
                val dbBytes = createSampleTautulliBytes(harness.tempDir)

                testApplication {
                    application {
                        miruModule(
                            config = harness.appConfig,
                            authService = harness.authService,
                            tautulliImporterService = harness.importerService,
                        )
                    }

                    // 401 when unauthenticated
                    client.post("/api/v1/admin/imports/tautulli").status shouldBe HttpStatusCode.Unauthorized

                    // 403 for non-admin USER
                    val forbiddenResp =
                        client.post("/api/v1/admin/imports/tautulli") {
                            cookie(SESSION_COOKIE_NAME, userLogin.signedToken)
                        }
                    forbiddenResp.status shouldBe HttpStatusCode.Forbidden

                    // 400 when missing file part
                    val missingFileResp =
                        client.post("/api/v1/admin/imports/tautulli") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append("dryRun", "true")
                                    },
                                ),
                            )
                        }
                    missingFileResp.status shouldBe HttpStatusCode.BadRequest
                    missingFileResp.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson

                    // 400 when non-SQLite bytes uploaded
                    val invalidFileResp =
                        client.post("/api/v1/admin/imports/tautulli?dryRun=true") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            "not-a-sqlite-db".toByteArray(),
                                            Headers.build {
                                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"bad.db\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    invalidFileResp.status shouldBe HttpStatusCode.BadRequest
                    invalidFileResp.bodyAsText() shouldContain "Invalid SQLite"

                    // 200 dryRun=true -> no DB writes
                    val dryResp =
                        client.post("/api/v1/admin/imports/tautulli?dryRun=true") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            dbBytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"tautulli.db\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    dryResp.status shouldBe HttpStatusCode.OK
                    val dryReport = MiruJson.decodeFromString<TautulliImportReport>(dryResp.bodyAsText())
                    dryReport.dryRun shouldBe true
                    dryReport.usersDiscovered shouldBe 1
                    dryReport.usersUpserted shouldBe 0
                    dryReport.historyImported shouldBe 1
                    dryReport.historySkippedDuplicate shouldBe 0
                    harness.historyRepo.query(HistoryFilter()).shouldBeRight().totalCount shouldBe 0L

                    // 200 real import via form field dryRun=false
                    val importResp =
                        client.post("/api/v1/admin/imports/tautulli") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append("dryRun", "false")
                                        append(
                                            "file",
                                            dbBytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"tautulli.db\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    importResp.status shouldBe HttpStatusCode.OK
                    val importReport = MiruJson.decodeFromString<TautulliImportReport>(importResp.bodyAsText())
                    importReport.dryRun shouldBe false
                    importReport.usersUpserted shouldBe 1
                    importReport.historyImported shouldBe 1
                    importReport.historySkippedDuplicate shouldBe 0
                    harness.historyRepo.query(HistoryFilter()).shouldBeRight().totalCount shouldBe 1L

                    // 200 repeat import -> idempotent (0 imported, 1 skipped)
                    val repeatResp =
                        client.post("/api/v1/admin/imports/tautulli") {
                            cookie(SESSION_COOKIE_NAME, adminLogin.signedToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            dbBytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"tautulli.db\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    repeatResp.status shouldBe HttpStatusCode.OK
                    val repeatReport = MiruJson.decodeFromString<TautulliImportReport>(repeatResp.bodyAsText())
                    repeatReport.historyImported shouldBe 0
                    repeatReport.historySkippedDuplicate shouldBe 1
                }
            }
        }
    })
