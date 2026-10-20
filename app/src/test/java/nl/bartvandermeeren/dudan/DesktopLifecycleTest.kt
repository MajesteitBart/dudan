package nl.bartvandermeeren.dudan

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.bartvandermeeren.dudan.data.*
import nl.bartvandermeeren.dudan.ui.chat.DesktopController
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class DesktopLifecycleTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/desktop-v1.json")!!.readText()) as JsonObject
    private val sessionJson get() = (fixture["session"] as JsonObject).toString()
    private val session get() = DesktopSession.from(fixture["session"] as JsonObject, "synthetic-chat")!!
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun backgroundReleasesControlWithoutHandbackAndRejectsFurtherInput() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = desktopDispatcher()
            server.start()
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "synthetic-key") }
            val controller = DesktopController(api, session) { null }
            val watching = launch { controller.watch(false) }
            withTimeout(5000) { while (controller.state.value.stale) delay(10) }
            controller.takeControl()
            assertTrue(controller.state.value.controlling)
            watching.cancelAndJoin()
            assertFalse(controller.state.value.controlling)
            assertTrue(controller.state.value.stale)
            val requests = (0 until server.requestCount).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            val released = requests.single { it.path!!.endsWith("/release") }
            assertTrue(released.path!!.endsWith("/release"))
            assertFalse(released.requestUrl.toString().contains("synthetic-lease"))
            assertTrue(released.body.readUtf8().contains("synthetic-lease"))
            controller.send(buildJsonObject { put("kind", "key"); put("text", "must not send") })
            controller.takeControl()
            assertEquals(requests.size, server.requestCount)
            assertFalse(requests.any { it.path!!.endsWith("/handback") })
        }
    }

    private fun desktopDispatcher(inputCode: Int = 200) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.endsWith("/grant") -> json("""{"grant":"synthetic-grant","expires_at":9999999999999}""")
            request.path!!.endsWith("/frame") -> MockResponse().setHeader("Content-Type", "image/png").setBody("synthetic-frame")
            request.path!!.endsWith("/acquire") -> json("""{"lease":"synthetic-lease","epoch":2,"expires_at":9999999999999}""")
            request.path!!.endsWith("/input") -> json("{}").setResponseCode(inputCode)
            else -> json(sessionJson)
        }
    }

    @Test fun failedInputIsSentOnceThenReleasesLease() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = desktopDispatcher(503)
            server.start()
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "synthetic-key") }
            val controller = DesktopController(api, session) { null }
            val watching = launch { controller.watch(false) }
            withTimeout(5000) { while (controller.state.value.stale) delay(10) }
            controller.takeControl()
            controller.send(buildJsonObject { put("kind", "text"); put("text", "once") })
            controller.send(buildJsonObject { put("kind", "text"); put("text", "never retried") })
            watching.cancelAndJoin()
            val requests = (0 until server.requestCount).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.path!!.endsWith("/input") })
            assertEquals(1, requests.count { it.path!!.endsWith("/release") })
            assertFalse(controller.state.value.controlling)
        }
    }

    @Test fun backgroundDuringAcquireReleasesTheLateLease() = runBlocking {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val normal = desktopDispatcher()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.endsWith("/acquire")) { entered.countDown(); finish.await(5, TimeUnit.SECONDS) }
                    return normal.dispatch(request)
                }
            }
            server.start()
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "synthetic-key") }
            val controller = DesktopController(api, session) { null }
            val watching = launch { controller.watch(false) }
            withTimeout(5000) { while (controller.state.value.stale) delay(10) }
            val taking = async { controller.takeControl() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            watching.cancel()
            delay(20)
            finish.countDown()
            taking.await(); watching.join()
            assertFalse(controller.state.value.controlling)
            val requests = (0 until server.requestCount).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(1, requests.count { it.path!!.endsWith("/release") })
            assertFalse(requests.any { it.path!!.endsWith("/handback") })
        }
    }

    @Test fun replacementViewerWaitsForOldViewerToRelease() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = desktopDispatcher(); server.start()
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "synthetic-key") }
            val controller = DesktopController(api, session) { null }
            val first = launch { controller.watch(false) }
            withTimeout(5000) { while (controller.state.value.stale) delay(10) }
            controller.takeControl()
            val before = server.requestCount
            val replacement = launch { controller.watch(false) }
            delay(30)
            assertEquals(before, server.requestCount)
            first.cancelAndJoin()
            withTimeout(5000) { while (controller.state.value.stale) delay(10) }
            assertFalse(controller.state.value.controlling)
            controller.takeControl()
            assertTrue(controller.state.value.controlling)
            replacement.cancelAndJoin()
            val requests = (0 until server.requestCount).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(2, requests.count { it.path!!.endsWith("/release") })
        }
    }

    @Test fun viewerPinsServerSoChangingSettingsCannotLeakLeaseToAnotherHost() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            var selected = HermesApi.ServerConfig(first.url("/").toString(), "first-key")
            val api = HermesApi(OkHttpClient()) { selected }
            val pinned = api.desktopClient()
            selected = HermesApi.ServerConfig(second.url("/").toString(), "second-key")
            first.enqueue(json("{}"))
            pinned.desktopAction("chat", "desktop", "release", "synthetic-viewer", buildJsonObject { put("lease", "private-lease") })
            val req = first.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer first-key", req.getHeader("Authorization"))
            assertEquals(0, second.requestCount)
        } }
    }
}
