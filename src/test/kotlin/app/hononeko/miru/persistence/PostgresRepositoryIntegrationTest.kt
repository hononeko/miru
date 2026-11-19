package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.postgresql.PostgreSQLContainer

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

        test("passes DatabaseReadinessCheck and redacts passwords on connection errors") {
            val db = requireHandle()
            db.dialect shouldBe DatabaseDialect.POSTGRES
            DatabaseReadinessCheck(db.dataSource).check().shouldBeRight()

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

        include(repositoryContractSpec { requireHandle() })
    })
