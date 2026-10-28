package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.dudan.voice.ModelSpec
import nl.bartvandermeeren.dudan.voice.ModelStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreTest {
    private val root: File = Files.createTempDirectory("dudan-voices").toFile()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }

    private fun spec() = ModelSpec(
        folder = "test",
        name = "test-voice",
        url = null,
        sizeBytes = body.size.toLong(),
        required = listOf("voice.bin"),
        extras = listOf(ModelSpec.Extra(server.url("/voice.bin").toString(), "voice.bin", sha256(body))),
    )

    @After
    fun cleanUp() {
        scope.cancel()
        server.shutdown()
        root.deleteRecursively()
    }

    @Test
    fun aDownloadInstallsAfterItsChecksum() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val store = ModelStore(root, OkHttpClient(), scope, spec())
        store.download()
        waitFor { store.state.value !is ModelStore.State.Downloading && store.state.value != ModelStore.State.Missing }
        assertEquals(ModelStore.State.Ready, store.state.value)
        assertEquals(body.size.toLong(), File(store.dir, "voice.bin").length())
    }

    @Test
    fun stopDownloadStopsItAndInstallsNothing() {
        // Slow enough that the download is still running when it's stopped.
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS))
        val store = ModelStore(root, OkHttpClient(), scope, spec())
        store.download()
        waitFor { (store.state.value as? ModelStore.State.Downloading)?.fraction?.let { it > 0f } == true }
        store.cancel()
        waitFor { store.state.value !is ModelStore.State.Downloading }
        // Long enough for a download that ignored the stop to finish.
        Thread.sleep(500)
        assertEquals(ModelStore.State.Missing, store.state.value)
        assertFalse(File(store.dir, ".complete").exists())
        assertFalse(File(root, "test/test-voice.partial").exists())
    }

    @Test
    fun stopBreaksOffARequestTheServerHoldsBack() {
        // The server never answers; a stop that missed the request would wait for OkHttp's read timeout.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val store = ModelStore(root, OkHttpClient(), scope, spec())
        store.download()
        waitFor { store.state.value is ModelStore.State.Downloading }
        server.takeRequest(5, TimeUnit.SECONDS)
        store.cancel()
        val started = System.currentTimeMillis()
        waitFor { store.state.value !is ModelStore.State.Downloading }
        assertEquals(ModelStore.State.Missing, store.state.value)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5_000)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
