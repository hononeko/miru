package app.hononeko.miru

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import java.util.concurrent.atomic.AtomicInteger

class LazyCloseableHttpClientTest :
    FunSpec({
        test("does not invoke factory when closed unused") {
            val invocations = AtomicInteger(0)
            val holder =
                LazyCloseableHttpClient {
                    invocations.incrementAndGet()
                    HttpClient(MockEngine { respondOk() })
                }

            holder.isInitialized shouldBe false
            holder.close()
            holder.isInitialized shouldBe false
            invocations.get() shouldBe 0
        }

        test("initializes once on first access and closes cleanly") {
            val invocations = AtomicInteger(0)
            val holder =
                LazyCloseableHttpClient {
                    invocations.incrementAndGet()
                    HttpClient(MockEngine { respondOk() })
                }

            val first = holder.client
            val second = holder.client
            (first === second) shouldBe true
            holder.isInitialized shouldBe true
            invocations.get() shouldBe 1

            holder.close()
            holder.isInitialized shouldBe false
        }
    })
