package app.hononeko.miru.persistence

import app.hononeko.miru.config.DatabaseConfig
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class SqliteRepositoryTest :
    FunSpec({
        var tempDir: Path? = null
        var handle: DatabaseHandle? = null

        fun requireHandle(): DatabaseHandle = checkNotNull(handle) { "SQLite handle not initialized" }

        beforeSpec {
            val dir = Files.createTempDirectory("miru-sqlite-test-")
            tempDir = dir
            val dbPath = dir.resolve("nested/miru.db")
            handle = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(dbPath.toString())).shouldBeRight()
        }

        afterSpec {
            handle?.close()
            tempDir?.toFile()?.deleteRecursively()
        }

        test("enables WAL mode, foreign keys, and passes DatabaseReadinessCheck") {
            val db = requireHandle()
            db.dialect shouldBe DatabaseDialect.SQLITE

            db.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1).lowercase() shouldBe "wal"
                    }
                    stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                    stmt.executeQuery("PRAGMA synchronous").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                    stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 5000
                    }
                }
            }

            DatabaseReadinessCheck(db.dataSource).check().shouldBeRight()
        }

        test("returns PersistenceError when readiness check runs against closed pool and on blank sqlite path") {
            val emptyPathResult = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite("   "))
            emptyPathResult.shouldBeLeft()

            val ephemeral = DatabaseFactory.createAndMigrate(DatabaseConfig.Sqlite(":memory:")).shouldBeRight()
            ephemeral.close()
            DatabaseReadinessCheck(ephemeral.dataSource).check().shouldBeLeft()
        }

        include(repositoryContractSpec { requireHandle() })
    })
