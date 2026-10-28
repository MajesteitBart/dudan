package nl.bartvandermeeren.dudan.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.ui.DudanRoot
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.chat.ChatHostActions
import nl.bartvandermeeren.dudan.ui.components.OverlayHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Windows app against a real Hermes or Clark, through a profile that is already set up: it connects,
 * loads the chat list, asks one short question on the quick model, waits for the answer and deletes that
 * chat again. It runs a real agent turn, so it only runs when given the profile's folder:
 *   ./gradlew :desktop:test --tests '*LiveServerTest*' -Pdudan.live=<DUDAN_HOME folder>
 * The key stays in the profile, encrypted; the test only reads it through DesktopSettings.
 */
@OptIn(ExperimentalTestApi::class)
class LiveServerTest {
    private val home: String? = System.getProperty("dudan.live")

    @Before
    fun needsProfile() {
        assumeTrue("Pass -Pdudan.live=<a DUDAN_HOME folder that is set up>", home != null)
        Locale.setDefault(Locale.US)
    }

    @Test
    fun asksOneQuestion() = runDesktopComposeUiTest(width = 1180, height = 780) {
        val store = DesktopSettings(File(home!!))
        assertTrue("The profile in $home isn't set up", store.current().isConfigured)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vm = MainViewModel(store, scope)
        setContent { DudanRoot(vm, OverlayHost(), ChatHostActions(onPickImages = {}, onScreenshot = {}), shortcutTaken = false) }
        var asked: String? = null
        var failure: Throwable? = null
        try {
            waitUntil(timeoutMillis = 30_000) { vm.sessions.value.loadedOnce && !vm.sessions.value.loading }
            assertNull("the chat list", vm.sessions.value.error)
            assertTrue("the chat list is empty", vm.sessions.value.items.isNotEmpty())

            runOnIdle { vm.newChat(ModelProfile.Assistant) }
            asked = vm.currentId.value
            composer().performTextInput("Connection check from dudan for Windows. Answer in one short sentence, without tools.")
            onNodeWithContentDescription("Send").performClick()
            waitUntil(timeoutMillis = 180_000) {
                vm.conversation.value.messages.lastOrNull()?.let { it.role == Role.Assistant && !it.isStreaming } == true
            }
            shot("live-answer")
            val answer = vm.conversation.value.messages.last()
            assertEquals("the turn ended with: ${answer.error}", MessageState.Done, answer.state)
            assertTrue("the answer is empty", answer.text.isNotBlank())
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                // Leave the server as it was. A chat the server didn't delete fails the test, after any earlier failure.
                asked?.let { id -> deleteFromServer(vm, id)?.let { cleanup -> failure?.addSuppressed(cleanup) ?: throw cleanup } }
            } finally {
                scope.cancel()
            }
        }
    }

    /** Deletes [id] on the server itself; null when it's gone or was never created there, else what went wrong. */
    private fun deleteFromServer(vm: MainViewModel, id: String): Throwable? = runBlocking {
        try {
            vm.api.deleteSession(id)
            null
        } catch (e: HermesApi.HermesException) {
            if (e.status == 404) null else AssertionError("The test chat $id is still on the server", e)
        } catch (e: Exception) {
            AssertionError("The test chat $id is still on the server", e)
        }
    }
}
