package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager

private data class JdbcTarget(
    val url: String,
    val user: String?,
    val password: String?,
    val migrationLocation: String,
)

private data class SeedAccount(
    val id: String,
    val email: String?,
    val provider: String,
    val subject: String?,
)

/**
 * Migrates an empty database to V7, seeds accounts as they looked before link modes existed, runs the
 * remaining migrations, and checks which accounts V8 marks AUTO: SSO accounts whose links are all email
 * matches (or that have none). Everything else must stay MANUAL so hand-set links are never overwritten.
 * Also checks that V12 gives SSO accounts the AUTO role mode, and local accounts MANUAL.
 */
internal fun assertMediaUserLinkModeBackfill(config: DatabaseConfig) {
    val target =
        when (config) {
            is DatabaseConfig.Sqlite -> JdbcTarget("jdbc:sqlite:${config.path}", null, null, "classpath:db/migration/sqlite")
            is DatabaseConfig.Postgres -> JdbcTarget(config.url, config.user, config.password, "classpath:db/migration/postgres")
        }

    Flyway
        .configure()
        .dataSource(target.url, target.user, target.password)
        .locations(target.migrationLocation)
        .target("7")
        .load()
        .migrate()

    DriverManager.getConnection(target.url, target.user, target.password).use { conn ->
        seedPreLinkModeAccounts(conn)
    }

    val handle = DatabaseFactory.createAndMigrate(config).shouldBeRight()
    try {
        val modes =
            handle.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id, media_user_link_mode FROM auth_accounts").use { rs ->
                        buildMap {
                            while (rs.next()) put(rs.getString(1), rs.getString(2))
                        }
                    }
                }
            }
        val verificationRecorded =
            handle.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM auth_accounts WHERE email_verified IS NOT NULL").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        // No account's email has been checked yet: that is recorded at each account's next sign-in.
        verificationRecorded shouldBe 0
        // V12: the role of every SSO account still comes from its groups, as before.
        val roleModes =
            handle.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id, role_mode FROM auth_accounts").use { rs ->
                        buildMap {
                            while (rs.next()) put(rs.getString(1), rs.getString(2))
                        }
                    }
                }
            }
        roleModes shouldBe modes.keys.associateWith { id -> if (id == "local") "MANUAL" else "AUTO" }
        modes shouldBe
            mapOf(
                "oidc-auto" to "AUTO",
                "oidc-unlinked" to "AUTO",
                "oidc-no-email" to "AUTO",
                "oidc-hand-picked" to "MANUAL",
                "oidc-no-email-linked" to "MANUAL",
                "oidc-emailless-user" to "MANUAL",
                "oidc-both-matches" to "AUTO",
                "oidc-one-match-removed" to "MANUAL",
                "local" to "MANUAL",
            )
    } finally {
        handle.close()
    }
}

private fun seedPreLinkModeAccounts(conn: Connection) {
    conn.createStatement().use { stmt ->
        stmt.executeUpdate(
            "INSERT INTO media_servers (id, name, server_type, machine_id, base_url, created_at_ms, updated_at_ms) " +
                "VALUES ('srv', 'Plex', 'PLEX', 'm-backfill', 'http://plex.local', 1, 1)",
        )
    }

    conn
        .prepareStatement(
            "INSERT INTO users (id, server_id, external_user_id, username, email, created_at_ms, updated_at_ms) " +
                "VALUES (?, 'srv', ?, ?, ?, 1, 1)",
        ).use { insert ->
            listOf(
                Triple("mu-alice", "1", "ALICE@example.test"),
                Triple("mu-bob", "2", "bob@example.test"),
                Triple("mu-nomail", "3", null),
                // The same person on two media user rows (e.g. two servers) shares one email.
                Triple("mu-erin-1", "4", "erin@example.test"),
                Triple("mu-erin-2", "5", "Erin@example.test"),
            ).forEach { (id, externalId, email) ->
                insert.setString(1, id)
                insert.setString(2, externalId)
                insert.setString(3, id)
                insert.setString(4, email)
                insert.executeUpdate()
            }
        }

    conn
        .prepareStatement(
            "INSERT INTO auth_accounts (id, username, username_normalized, email, password_hash, role, " +
                "auth_provider, external_subject, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, '', 'USER', ?, ?, 1, 1)",
        ).use { insert ->
            listOf(
                // Linked automatically to the only email match.
                SeedAccount("oidc-auto", "alice@example.test", "OIDC", "sub-1"),
                // Never matched anything yet.
                SeedAccount("oidc-unlinked", "carol@example.test", "OIDC", "sub-2"),
                SeedAccount("oidc-no-email", null, "OIDC", "sub-3"),
                // Has a link (mu-bob) that no email match explains: an admin picked it.
                SeedAccount("oidc-hand-picked", "alice@example.test", "OIDC", "sub-4"),
                SeedAccount("oidc-no-email-linked", null, "OIDC", "sub-5"),
                SeedAccount("oidc-emailless-user", "dave@example.test", "OIDC", "sub-6"),
                // Linked to both email matches: what automatic linking produced.
                SeedAccount("oidc-both-matches", "erin@example.test", "OIDC", "sub-7"),
                // Linked to only one of two email matches: an admin removed the other.
                SeedAccount("oidc-one-match-removed", "erin@example.test", "OIDC", "sub-8"),
                SeedAccount("local", "alice@example.test", "LOCAL", null),
            ).forEach { account ->
                insert.setString(1, account.id)
                insert.setString(2, account.id)
                insert.setString(3, account.id)
                insert.setString(4, account.email)
                insert.setString(5, account.provider)
                insert.setString(6, account.subject)
                insert.executeUpdate()
            }
        }

    conn
        .prepareStatement(
            "INSERT INTO auth_account_media_users (account_id, media_user_id, created_at_ms) VALUES (?, ?, 1)",
        ).use { insert ->
            listOf(
                "oidc-auto" to "mu-alice",
                "oidc-hand-picked" to "mu-alice",
                "oidc-hand-picked" to "mu-bob",
                "oidc-no-email-linked" to "mu-alice",
                "oidc-emailless-user" to "mu-nomail",
                "oidc-both-matches" to "mu-erin-1",
                "oidc-both-matches" to "mu-erin-2",
                "oidc-one-match-removed" to "mu-erin-1",
                "local" to "mu-alice",
            ).forEach { (accountId, mediaUserId) ->
                insert.setString(1, accountId)
                insert.setString(2, mediaUserId)
                insert.executeUpdate()
            }
        }
}
