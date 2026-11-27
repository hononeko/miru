package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

class PostgresRepositoryIntegrationTest :
    FunSpec({
        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("miru_test")
                .withUsername("miru")
                .withPassword("miru_test_password")

        var handle: DatabaseHandle? = null

        fun requireHandle(): DatabaseHandle = checkNotNull(handle) { "Postgres handle not initialized" }

        beforeSpec {
            container.start()
            handle =
                DatabaseFactory
                    .createAndMigrate(
                        DatabaseConfig.Postgres(
                            url = container.jdbcUrl,
                            user = container.username,
                            password = container.password,
                            maxPoolSize = 4,
                        ),
                    ).shouldBeRight()
        }

        afterSpec {
            handle?.close()
            if (container.isRunning) {
                container.stop()
            }
        }

        test("passes DatabaseReadinessCheck, shares single pool handle, and redacts passwords on connection errors") {
            val db = requireHandle()
            db.dialect shouldBe DatabaseDialect.POSTGRES
            db.readDataSource shouldBeSameInstanceAs db.dataSource
            db.readDatabase shouldBeSameInstanceAs db.database
            DatabaseReadinessCheck(db).check().shouldBeRight()

            val secretPassword = "super-secret-pg-password-999"
            val failed =
                DatabaseFactory
                    .createAndMigrate(
                        DatabaseConfig.Postgres(
                            url = container.jdbcUrl,
                            user = "nonexistent_user",
                            password = secretPassword,
                            maxPoolSize = 2,
                        ),
                    ).shouldBeLeft()
            failed.message shouldNotContain secretPassword
        }

        test("V8 marks existing SSO accounts AUTO only when their links are all email matches") {
            val databaseName = "miru_link_mode_backfill"
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                conn.createStatement().use { it.executeUpdate("CREATE DATABASE $databaseName") }
            }
            assertMediaUserLinkModeBackfill(
                DatabaseConfig.Postgres(
                    url = container.jdbcUrl.replace("/${container.databaseName}", "/$databaseName"),
                    user = container.username,
                    password = container.password,
                    maxPoolSize = 2,
                ),
            )
        }

        include(repositoryContractSpec { requireHandle() })
    })
