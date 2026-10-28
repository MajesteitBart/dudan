package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.settings.ConnectionForm
import nl.bartvandermeeren.dudan.ui.settings.TestState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Setup's connection test against a server that takes a moment to answer. */
class ConnectionTest {
    private val dir: File = Files.createTempDirectory("dudan-connect").toFile()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = DesktopSettings(dir)
    private val vm = MainViewModel(store, scope)

    @After
    fun cleanUp() {
        scope.cancel()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun slowAnswer() = MockResponse().setBody("""{"model": "test-model"}""").setBodyDelay(400, TimeUnit.MILLISECONDS)

    private fun onSwing(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    @Test
    fun aKeyChangedDuringTheTestIsNotSavedAsTested() {
        server.enqueue(slowAnswer())
        lateinit var form: ConnectionForm
        onSwing {
            form = ConnectionForm(vm, scope, AppSettings())
            form.url = server.url("/").toString()
            form.key = "first-key"
            form.connect()
            form.key = "second-key"
            form.edited()
        }
        server.takeRequest(5, TimeUnit.SECONDS)
        Thread.sleep(800)
        assertEquals("", store.current().apiKey)
        onSwing { assertEquals(TestState.Idle, form.test) }
    }

    @Test
    fun whatWasTestedIsWhatIsSaved() {
        server.enqueue(slowAnswer())
        lateinit var form: ConnectionForm
        onSwing {
            form = ConnectionForm(vm, scope, AppSettings())
            form.url = server.url("/").toString()
            form.key = "tested-key"
            form.name = "Bart"
            form.connect()
            // A name typed while the server answers isn't part of what was tested.
            form.name = "Someone else"
        }
        val until = System.currentTimeMillis() + 5_000
        while (store.current().apiKey.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("tested-key", store.current().apiKey)
        assertEquals("Bart", store.current().userName)
    }
}
