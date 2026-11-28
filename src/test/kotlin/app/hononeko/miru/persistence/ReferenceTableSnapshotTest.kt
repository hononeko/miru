package app.hononeko.miru.persistence

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.repository.ReferenceTableSnapshot
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReferenceTableSnapshotTest :
    FunSpec({
        test("loads once, serves the same rows until invalidated, then reloads") {
            val table = AtomicReference(listOf("a"))
            val loads = AtomicInteger()
            val snapshot = ReferenceTableSnapshot { Either.Right(table.get()).also { loads.incrementAndGet() } }

            repeat(5) { snapshot.rows().shouldBeRight() shouldBe listOf("a") }
            loads.get() shouldBe 1

            table.set(listOf("a", "b"))
            snapshot.invalidate()
            repeat(5) { snapshot.rows().shouldBeRight() shouldBe listOf("a", "b") }
            loads.get() shouldBe 2
        }

        test("a load that started before a write is never served after the write") {
            val table = AtomicReference(listOf("old"))
            val loadStarted = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val snapshot =
                ReferenceTableSnapshot {
                    val rows = table.get()
                    if (loads.incrementAndGet() == 1) {
                        loadStarted.complete(Unit)
                        releaseLoad.await()
                    }
                    Either.Right(rows)
                }

            coroutineScope {
                val racingRead = async { snapshot.rows() }
                loadStarted.await()
                // The write commits and invalidates while the first load still holds the old rows.
                table.set(listOf("new"))
                snapshot.invalidate()
                releaseLoad.complete(Unit)
                racingRead.await().shouldBeRight() shouldBe listOf("old")
            }

            snapshot.rows().shouldBeRight() shouldBe listOf("new")
            loads.get() shouldBe 2
        }

        test("concurrent cold reads share one load, and a failed load is not cached") {
            val loads = AtomicInteger()
            val fail = AtomicReference(true)
            val snapshot =
                ReferenceTableSnapshot {
                    loads.incrementAndGet()
                    yield()
                    if (fail.get()) {
                        Either.Left(MiruError.PersistenceError(operation = "load", message = "boom"))
                    } else {
                        Either.Right(listOf(1, 2, 3))
                    }
                }

            snapshot.rows().shouldBeLeft()
            fail.set(false)
            coroutineScope {
                List(10) { async { snapshot.rows() } }.awaitAll().forEach { it.shouldBeRight() shouldBe listOf(1, 2, 3) }
            }
            loads.get() shouldBe 2
        }

        test("toString never prints the rows it holds") {
            val snapshot = ReferenceTableSnapshot { Either.Right(listOf("enc-secret-token")) }
            snapshot.rows().shouldBeRight()
            snapshot.toString() shouldNotContain "enc-secret-token"
        }
    })
