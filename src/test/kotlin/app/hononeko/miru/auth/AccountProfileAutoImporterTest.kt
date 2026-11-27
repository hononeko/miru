package app.hononeko.miru.auth

import app.hononeko.miru.adapter.plex.PlexMediaServerAdapter
import app.hononeko.miru.adapter.plex.createPlexHttpClient
import app.hononeko.miru.artwork.ArtworkProxyService
import app.hononeko.miru.artwork.DnsResolver
import app.hononeko.miru.artwork.createAvatarHttpClient
import app.hononeko.miru.config.AuthConfig
import app.hononeko.miru.config.AuthMode
import app.hononeko.miru.config.DatabaseConfig
import app.hononeko.miru.config.OidcConfig
import app.hononeko.miru.config.PlexConfig
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.model.AuthAccountRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.ExposedApiKeyRepository
import app.hononeko.miru.persistence.repository.ExposedAuthAccountRepository
import app.hononeko.miru.persistence.repository.ExposedAuthSessionRepository
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import java.net.InetAddress
import java.nio.file.Files

private val AVATAR_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { it.toByte() }

private fun ssoAccount(
    id: String,
    email: String,
    displayName: String? = null,
): AuthAccountRecord =
    AuthAccountRecord(
        id = id,
        username = id,
        usernameNormalized = id,
        email = email,
        passwordHash = "",
        role = "USER",
        authProvider = "OIDC",
        externalSubject = "sub-$id",
        mediaUserLinkMode = "AUTO",
        emailVerified = true,
        displayName = displayName,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

private fun mediaUser(
    id: String,
    serverId: String,
    email: String,
    displayName: String,
    thumbUrl: String? = null,
): UserRecord =
    UserRecord(
        id = id,
        serverId = serverId,
        externalUserId = "ext-$id",
        username = id,
        displayName = displayName,
        email = email,
        thumbUrl = thumbUrl,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

class AccountProfileAutoImporterTest :
    FunSpec({
        test("an account SSO links by email gets the name and avatar it lacks from the server connected first") {
            val dir = Files.createTempDirectory("miru-auto-import-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val avatarClient =
                createAvatarHttpClient(
                    engine =
                        MockEngine { request ->
                            if (request.url.host == "plex.tv") {
                                respond(AVATAR_PNG, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                            } else {
                                respond(ByteArray(0), HttpStatusCode.NotFound)
                            }
                        },
                )
            try {
                val accounts = ExposedAuthAccountRepository(handle)
                val users = ExposedUserRepository(handle)
                val servers = ExposedMediaServerRepository(handle)
                val authConfig =
                    AuthConfig(
                        mode = AuthMode.LOCAL,
                        sessionSecret = "auto-import-test-session-secret-32-chars",
                        oidc = OidcConfig(enabled = true, autoLinkByEmail = true),
                    )
                val linker = EmailMediaUserLinker(accounts, users, authConfig)
                val authService =
                    AuthService(
                        accountRepository = accounts,
                        sessionRepository = ExposedAuthSessionRepository(handle),
                        apiKeyRepository = ExposedApiKeyRepository(handle),
                        userRepository = users,
                        passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
                        authConfig = authConfig,
                        emailLinker = linker,
                    )
                val plexConfig = PlexConfig(baseUrl = "http://plex.local:32400", token = "auto-import-plex-token")
                val artwork =
                    ArtworkProxyService(
                        adapter =
                            PlexMediaServerAdapter(
                                config = plexConfig,
                                httpClient = createPlexHttpClient(engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) }),
                            ),
                        userRepository = users,
                        externalAvatarClient = avatarClient,
                        dnsResolver = DnsResolver { listOf(InetAddress.getByName("93.184.216.34")) },
                        authAccountRepository = accounts,
                    )
                val importer =
                    AccountProfileAutoImporter(
                        authService = authService,
                        accountRepository = accounts,
                        userRepository = users,
                        mediaServerRepository = servers,
                        profileImport = ProfileImportSources(artworkProxyService = artwork),
                        scope = scope,
                    )
                importer.attachTo(linker)

                // Plex was connected first, Jellyfin later; the Jellyfin profiles were synced earlier
                listOf("srv-plex" to 1_000L, "srv-jelly" to 2_000L).forEach { (id, connectedAt) ->
                    servers
                        .upsert(
                            MediaServerRecord(
                                id = id,
                                name = id,
                                serverType = if (id == "srv-plex") "PLEX" else "JELLYFIN",
                                machineId = "machine-$id",
                                baseUrl = "http://$id.local",
                                createdAtMs = connectedAt,
                                updatedAtMs = connectedAt,
                            ),
                        ).shouldBeRight()
                }
                listOf(
                    mediaUser("u-elena-jelly", "srv-jelly", "elena@example.com", "Elena J").copy(createdAtMs = 500L),
                    mediaUser("u-elena-plex", "srv-plex", "elena@example.com", "Elena P", thumbUrl = "https://plex.tv/users/1/avatar"),
                    mediaUser("u-omar-plex", "srv-plex", "omar@example.com", "Omar P", thumbUrl = "https://plex.tv/users/2/avatar"),
                ).forEach { users.upsert(it).shouldBeRight() }
                accounts.insert(ssoAccount("elena", "elena@example.com")).shouldBeRight()
                // Omar named himself before he was matched
                accounts.insert(ssoAccount("omar", "omar@example.com", displayName = "Omar R")).shouldBeRight()

                linker.relinkAll().shouldBeRight() shouldBe 2
                scope.coroutineContext.job.children.forEach { it.join() }

                val elena = accounts.findById("elena").shouldBeRight().shouldNotBeNull()
                elena.displayName shouldBe "Elena P"
                elena.avatarSourceServerId shouldBe "srv-plex"
                accounts
                    .findAvatarById("elena")
                    .shouldBeRight()
                    .shouldNotBeNull()
                    .bytes
                    .contentEquals(AVATAR_PNG) shouldBe true

                val omar = accounts.findById("omar").shouldBeRight().shouldNotBeNull()
                omar.displayName shouldBe "Omar R"
                omar.avatarSourceServerId shouldBe "srv-plex"

                // Once the account has its own profile, later matches leave it alone
                importer.importMissing("elena").shouldBeRight() shouldBe false
                importer.importMissing("missing").shouldBeRight() shouldBe false
            } finally {
                scope.cancel()
                avatarClient.close()
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }

        test("the first server's user without a picture gives the account only a name") {
            val dir = Files.createTempDirectory("miru-auto-import-name-")
            val handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dir.resolve("miru.db").toString())).shouldBeRight()
            try {
                val accounts = ExposedAuthAccountRepository(handle)
                val users = ExposedUserRepository(handle)
                val servers = ExposedMediaServerRepository(handle)
                val authConfig =
                    AuthConfig(
                        mode = AuthMode.LOCAL,
                        sessionSecret = "auto-import-test-session-secret-32-chars",
                        oidc = OidcConfig(enabled = true, autoLinkByEmail = true),
                    )
                val authService =
                    AuthService(
                        accountRepository = accounts,
                        sessionRepository = ExposedAuthSessionRepository(handle),
                        apiKeyRepository = ExposedApiKeyRepository(handle),
                        userRepository = users,
                        passwordHasher = Argon2idPasswordHasher(Argon2Params(memoryKb = 1024, iterations = 1, parallelism = 1)),
                        authConfig = authConfig,
                    )
                servers
                    .upsert(
                        MediaServerRecord(
                            id = "srv-jelly",
                            name = "Jellyfin",
                            serverType = "JELLYFIN",
                            machineId = "machine-jelly",
                            baseUrl = "http://jelly.local",
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                        ),
                    ).shouldBeRight()
                users.upsert(mediaUser("u-jelly", "srv-jelly", "ana@example.com", "Ana J")).shouldBeRight()
                accounts.insert(ssoAccount("ana", "ana@example.com")).shouldBeRight()
                accounts.replaceLinkedMediaUserIds("ana", setOf("u-jelly"), createdAtMs = 1_000L).shouldBeRight()

                val importer =
                    AccountProfileAutoImporter(
                        authService = authService,
                        accountRepository = accounts,
                        userRepository = users,
                        mediaServerRepository = servers,
                        // No artwork access is needed when there is no picture to import
                        profileImport = ProfileImportSources(),
                        scope = CoroutineScope(Dispatchers.IO),
                    )
                importer.importMissing("ana").shouldBeRight() shouldBe true
                val ana = accounts.findById("ana").shouldBeRight().shouldNotBeNull()
                ana.displayName shouldBe "Ana J"
                ana.avatarUpdatedAtMs.shouldBeNull()
            } finally {
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
