package nl.bartvandermeeren.dudan.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.ui.chat.rememberConversationDesktops
import nl.bartvandermeeren.dudan.ui.components.LocalWindowShown
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.data.DesktopState
import nl.bartvandermeeren.dudan.ui.chat.AgentScreenViewer
import nl.bartvandermeeren.dudan.ui.chat.DesktopController
import nl.bartvandermeeren.dudan.ui.theme.DudanTheme
import nl.bartvandermeeren.dudan.ui.DudanRoot
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.Screen
import nl.bartvandermeeren.dudan.ui.chat.ChatHostActions
import nl.bartvandermeeren.dudan.ui.components.OverlayHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A user's first session with the Windows app, against tools/mock-hermes with MOCK_LANG=en: set up the
 * connection, ask a question, approve a command, look at the models, open an older chat and change the
 * accent. It draws the real screens and talks HTTP to the mock, off screen, and saves a picture of each
 * step in build/ui-shots. The tray, the shortcut and the quick question window need a real desktop.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopJourneyTest {
    private val mock: String? = System.getProperty("dudan.mock")

    @Before
    fun needsMock() {
        assumeTrue("Start tools/mock-hermes (MOCK_LANG=en) and pass -Pdudan.mock=<url>", mock != null)
        Locale.setDefault(Locale.US)
    }

    @Test
    fun firstSession() = runDesktopComposeUiTest(width = 1180, height = 780) {
        val profile = Files.createTempDirectory("dudan-journey").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val store = DesktopSettings(profile)
        val vm = MainViewModel(store, scope)
        val overlay = OverlayHost()
        setContent { DudanRoot(vm, overlay, ChatHostActions(onPickImages = {}, onScreenshot = {}), shortcutTaken = false) }

        try {
            // Setup
            field("Server URL").performTextInput(mock!!)
            field("API key (API_SERVER_KEY)").performTextInput("dev-key-dudan-0000000000")
            field("Your name").performTextInput("Bart")
            shot("journey-01-setup")
            onNodeWithText("Connect").performClick()
            waitForText("Hello, Bart")
            waitForText("Autumn school break")
            shot("journey-02-empty-chat")
            assertTrue("the key is stored encrypted", File(profile, "settings.json").readText().let { "dev-key-dudan" !in it && "127.0.0.1" in it })

            // A question and its answer
            composer().performTextInput("What should I focus on this quarter?")
            onNodeWithContentDescription("Send").performClick()
            waitForText("three priorities", timeout = 20_000)
            shot("journey-03-answer")

            // An approval before a dangerous command
            composer().performTextInput("Please delete the old exports")
            onNodeWithContentDescription("Send").performClick()
            waitForText("Allow once", timeout = 20_000)
            shot("journey-04-approval")
            onNodeWithText("Allow once").performClick()
            waitForText("freed 2.1 GB", timeout = 20_000)
            shot("journey-05-approved")

            // The model picker, closed again the way Esc does
            modelButton().performClick()
            waitForText("Chats")
            shot("journey-06-model-picker")
            runOnIdle { overlay.dismissTop() }

            // An older chat from the sidebar
            // Earlier runs against the same mock add chats above it, so scroll the chat list to it first.
            scrollListTo(hasText("Autumn school break"))
            onNodeWithText("Autumn school break").performClick()
            waitForText("Shall I put it in your calendar?", substring = true, timeout = 15_000)
            shot("journey-07-older-chat")

            // Settings: the accent sticks
            onNodeWithContentDescription("Settings").performClick()
            waitForText("Appearance")
            onNodeWithContentDescription("Teal").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { vm.settings.value.accent == "Teal" }
            waitForText("you're doing")
            shot("journey-08-settings")
            assertEquals("Teal", DesktopSettings(profile).current().accent)
            onNodeWithContentDescription("Back").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { vm.screen == Screen.Chat }
            shot("journey-09-teal-chat")
            waitForText("Shall I put it in your calendar?")
        } finally {
            scope.cancel()
            profile.deleteRecursively()
        }
    }

    /**
     * A rich reply with a table, a chart and follow-ups, then the Agent screen card of the mock's office search
     * (MOCK_DESKTOP=1). Open would make a real window, so the test stops at the card's preview.
     */
    @Test
    fun richReplyAndAgentScreen() = runDesktopComposeUiTest(width = 1180, height = 780) {
        val profile = Files.createTempDirectory("dudan-rich").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val store = DesktopSettings(profile)
            kotlinx.coroutines.runBlocking { store.saveSetup(mock!!, "dev-key-dudan-0000000000", "Bart", "") }
            val vm = MainViewModel(store, scope)
            setContent { DudanRoot(vm, OverlayHost(), ChatHostActions(onPickImages = {}, onScreenshot = {}), shortcutTaken = false) }
            waitUntil(timeoutMillis = 10_000) { vm.sessions.value.loadedOnce }

            composer().performTextInput("Compare the fixed energy contracts for me")
            onNodeWithContentDescription("Send").performClick()
            waitForText("Energy contracts", timeout = 20_000)
            waitForText("Supplier A is the cheapest", timeout = 20_000)
            shot("journey-12-rich-reply")
            // A follow-up in the block sends its text as the next question.
            scrollListTo(hasText("Which one is greenest?"))
            onNodeWithText("Which one is greenest?").performClick()
            waitUntil(timeoutMillis = 10_000) { vm.conversation.value.messages.any { it.role == Role.User && it.text == "Which one is greenest?" } }

            assumeTrue("Start the mock with MOCK_DESKTOP=1 for the Agent screen", vm.sessions.value.items.any { it.displayTitle == "Office space in Amsterdam" })
            scrollListTo(hasText("Office space in Amsterdam"))
            onNodeWithText("Office space in Amsterdam").performClick()
            waitForText("Agent screen", substring = false, timeout = 15_000)
            // The thumbnail shows once the first frame is in.
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Desktop image", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Agent screen", substring = false).performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Desktop image", substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitForText("Live", timeout = 15_000)
            shot("journey-13-agent-screen")
        } finally {
            scope.cancel()
            profile.deleteRecursively()
        }
    }

    /**
     * The Agent screen window's content, drawn here instead of in a window: take control of the mock's live
     * desktop (MOCK_DESKTOP=1), type on it, and return control.
     */
    @Test
    fun agentScreenControl() = runDesktopComposeUiTest(width = 1280, height = 860) {
        val profile = Files.createTempDirectory("dudan-desktop").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val store = DesktopSettings(profile)
            kotlinx.coroutines.runBlocking { store.saveSetup(mock!!, "dev-key-dudan-0000000000", "Bart", "") }
            val vm = MainViewModel(store, scope)
            val (client, capability, live) = kotlinx.coroutines.runBlocking {
                val client = vm.api.desktopClient()
                val capability = client.capabilities().desktop
                assumeTrue("Start the mock with MOCK_DESKTOP=1 for the Agent screen", capability != null)
                val chat = vm.api.listSessions(null).first { it.displayTitle == "Office space in Amsterdam" }
                Triple(client, capability!!, client.desktops(chat.id).first { it.state != DesktopState.Stopped })
            }
            val controller = DesktopController(client, live)
            setContent {
                DudanTheme {
                    val view by controller.state.collectAsState()
                    LaunchedEffect(controller) { controller.watch(expanded = true) }
                    AgentScreenViewer(controller, view, capability, onCollapse = {})
                }
            }
            waitForText("Live", timeout = 15_000)
            onNodeWithText("Take control").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.controlling }
            waitForText("You have control")
            onNodeWithText("Type on desktop").performTextInput("Amsterdam Zuid")
            onNodeWithText("Send", substring = false).performClick()
            waitUntil(timeoutMillis = 10_000) { !controller.state.value.busy }
            assertTrue("the input was refused", !controller.state.value.error)
            shot("journey-14-agent-control")
            onNodeWithText("Return control").performClick()
            waitUntil(timeoutMillis = 10_000) { !controller.state.value.controlling && !controller.state.value.busy }
            assertTrue("returning control failed", !controller.state.value.error)
        } finally {
            scope.cancel()
            profile.deleteRecursively()
        }
    }

    /** Hiding the main window and bringing it back keeps the chat's desktop client, which an open Agent screen hangs on. */
    @Test
    fun aRestoredWindowKeepsItsDesktopClient() = runDesktopComposeUiTest(width = 400, height = 300) {
        val profile = Files.createTempDirectory("dudan-shown").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val store = DesktopSettings(profile)
            kotlinx.coroutines.runBlocking { store.saveSetup(mock!!, "dev-key-dudan-0000000000", "Bart", "") }
            val vm = MainViewModel(store, scope)
            val chat = kotlinx.coroutines.runBlocking { vm.api.listSessions(null) }.firstOrNull { it.displayTitle == "Office space in Amsterdam" }
            assumeTrue("Start the mock with MOCK_DESKTOP=1 for the Agent screen", chat != null)
            var shown by mutableStateOf(true)
            val clients = mutableListOf<HermesApi>()
            setContent {
                CompositionLocalProvider(LocalWindowShown provides shown) {
                    rememberConversationDesktops(vm.api, chat!!.id).client?.let { if (clients.lastOrNull() !== it) clients += it }
                }
            }
            waitUntil(timeoutMillis = 10_000) { clients.isNotEmpty() }
            shown = false
            waitForIdle()
            shown = true
            // Let the discovery that starts again reach the mock and come back; a new client would show then.
            repeat(30) {
                Thread.sleep(100)
                waitForIdle()
            }
            assertEquals("a new client would close the open Agent screen", 1, clients.size)
        } finally {
            scope.cancel()
            profile.deleteRecursively()
        }
    }

    /** The quick question card, connected through a saved profile, asking without the screen. */
    @Test
    fun quickQuestion() = runDesktopComposeUiTest(width = 720, height = 560) {
        val profile = Files.createTempDirectory("dudan-quick").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val store = DesktopSettings(profile)
            kotlinx.coroutines.runBlocking { store.saveSetup(mock!!, "dev-key-dudan-0000000000", "Bart", "") }
            val vm = MainViewModel(store, scope)
            // newQuestion, not open: open grabs the real screen, which a test has no business sending.
            val quick = QuickAsk(vm, scope).apply { newQuestion() }
            setContent { QuickAskCard(quick, vm, OverlayHost(), onOpenInApp = {}) }
            shot("journey-10-quick-empty")
            composer().performTextInput("What should I focus on this quarter?")
            onNodeWithContentDescription("Send").performClick()
            waitForText("three priorities", timeout = 20_000)
            shot("journey-11-quick-answer")
            onNodeWithText("New question").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("three priorities", substring = true).fetchSemanticsNodes().isEmpty() }
        } finally {
            scope.cancel()
            profile.deleteRecursively()
        }
    }

    /**
     * The chat list after T3 Code's sidebar: settle and snooze chats from their menu, unfold Settled and search.
     * Then the keyboard, through the main window's own key handler: a shortcut changed in Settings, a refused
     * one, and the text made bigger and reset with shortcuts. Settings shows Read aloud with its voices.
     */
    @Test
    fun chatListShortcutsAndTextSize() = runDesktopComposeUiTest(width = 1180, height = 780) {
        val profile = Files.createTempDirectory("dudan-list").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val store = DesktopSettings(profile)
            kotlinx.coroutines.runBlocking { store.saveSetup(mock!!, "dev-key-dudan-0000000000", "Bart", "") }
            val vm = MainViewModel(store, scope)
            val overlay = OverlayHost()
            setContent {
                // As the window passes its keys; see Main.
                Box(Modifier.fillMaxSize().onPreviewKeyEvent { mainWindowKeys(it, vm, overlay) }) {
                    DudanRoot(vm, overlay, ChatHostActions(onPickImages = {}, onScreenshot = {}), shortcutTaken = false)
                }
            }
            // Earlier runs against the same mock add chats above it, so it may be below the fold: wait for the list itself.
            waitUntil(timeoutMillis = 10_000) { vm.sessions.value.items.any { it.displayTitle == "Drizzle schema for the inbox" } }

            // Settle a chat and snooze another, from their menus.
            scrollListTo(hasText("Drizzle schema for the inbox"))
            onNodeWithText("Drizzle schema for the inbox").performMouseInput { rightClick() }
            onNodeWithText("Settle").performClick()
            waitForText("Settled", substring = false)
            scrollListTo(hasText("Autumn school break"))
            onNodeWithText("Autumn school break").performMouseInput { rightClick() }
            onNodeWithText("Snooze").performClick()
            onNodeWithText("Tomorrow").performClick()
            waitForText("Snoozed", substring = false)
            onNodeWithText("Settled", substring = false).performClick()
            waitUntil(timeoutMillis = 5_000) { vm.chatGroups.value.settled.any { it.session.displayTitle == "Drizzle schema for the inbox" } }
            shot("journey-15-chat-list")
            assertTrue(vm.chatGroups.value.snoozed.any { it.session.displayTitle == "Autumn school break" })

            // Search narrows every group.
            chatSearch().performTextInput("office")
            waitUntil(timeoutMillis = 5_000) { vm.chatGroups.value.open.all { it.session.displayTitle.contains("Office", ignoreCase = true) } }
            shot("journey-16-chat-search")
            chatSearch().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { vm.searchQuery.value.isEmpty() }

            // Settings: read aloud, then a new shortcut for New chat, and one that's refused.
            onNodeWithContentDescription("Settings").performClick()
            waitForText("Read aloud")
            // Off the avatar, whose label would show over the picture.
            onNodeWithText("Dutch and other languages").performScrollTo()
            onNodeWithText("Read new replies aloud").performMouseInput { moveTo(center) }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
            shot("journey-17-read-aloud")
            onNodeWithContentDescription("New chat: Ctrl+N").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { vm.recording == ShortcutAction.NewChat }
            onNodeWithContentDescription("New chat: Press keys").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            waitForText("Ctrl+C is for editing text or for Windows.")
            onNodeWithText("Ctrl+C is for editing text or for Windows.").performScrollTo()
            shot("journey-18-shortcut-refused")
            onNodeWithContentDescription("New chat: Press keys").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
            waitUntil(timeoutMillis = 5_000) { vm.prefs.value.shortcut(ShortcutAction.NewChat) == KeyCombo(ShortcutKey.T, ctrl = true) }
            assertEquals(null, vm.recording)
            assertEquals(KeyCombo(ShortcutKey.T, ctrl = true), DesktopSettings(profile).prefs.value.shortcut(ShortcutAction.NewChat))

            // Bigger text from Settings, then back in the chat from the keyboard.
            onNodeWithContentDescription("Bigger text").performScrollTo().performClick()
            onNodeWithContentDescription("Bigger text").performClick()
            waitUntil(timeoutMillis = 5_000) { vm.prefs.value.textScale == 1.2f }
            onNodeWithText("Text size").performScrollTo()
            shot("journey-19-text-size")
            onNodeWithContentDescription("Back").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { vm.screen == Screen.Chat }
            scrollListTo(hasText("Office space in Amsterdam"))
            onNodeWithText("Office space in Amsterdam").performClick()
            waitForText("Agent screen", substring = false, timeout = 15_000)
            composer().performClick()
            composer().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) } }
            waitUntil(timeoutMillis = 5_000) { vm.prefs.value.textScale == 1.35f }
            shot("journey-20-bigger-chat")
            composer().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Zero) } }
            waitUntil(timeoutMillis = 5_000) { vm.prefs.value.textScale == 1f }
            // The new shortcut starts a chat; the old one types on.
            composer().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.T) } }
            waitForText("Hello, Bart")
        } finally {
            scope.cancel()
            profile.deleteRecursively()
        }
    }

    /** The model button in the prompt bar names a model with a "·" or the server's default. */
    private fun ComposeUiTest.modelButton() = onAllNodes(hasText("GPT", substring = true) or hasText("Hermes", substring = false)).onFirst()

}
