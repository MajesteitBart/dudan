package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSettingsTest {
    private val dir: File = Files.createTempDirectory("dudan-settings").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun theKeyIsStoredEncryptedAndReadBackAfterARestart() = runBlocking {
        DesktopSettings(dir).saveSetup(" http://clark:8642/ ", " secret-key ", "Bart", "")
        val raw = File(dir, "settings.json").readText()
        assertFalse(raw, "secret-key" in raw)

        val again = DesktopSettings(dir).current()
        assertEquals("secret-key", again.apiKey)
        assertEquals("http://clark:8642", again.serverUrl)
        // A blank assistant name is the default one.
        assertEquals("dudan", again.assistantName)
        assertTrue(again.isConfigured)
        // Nothing on the PC acts for Hermes; rich replies are on, as on the phone.
        assertFalse(again.phoneControl)
        assertTrue(again.richReplies)
    }

    @Test
    fun modelChoicesKeepLeavingTheEffortToHermesApartFromTheDefault() = runBlocking {
        val store = DesktopSettings(dir)
        // Untouched, the quick model starts on Low, as on the phone.
        assertEquals(ReasoningEffort.Low, store.current().assistantModel.effort)
        assertNull(store.current().model.effort)

        store.setModel(ModelProfile.Assistant, ModelChoice("openai", "gpt-6-luna", "GPT-6 Luna", effort = null, fast = true))
        store.setModel(ModelProfile.Chats, ModelChoice("anthropic", "claude-opus-5-5", "Opus 5.5", ReasoningEffort.High))

        val again = DesktopSettings(dir).current()
        assertEquals(ModelChoice("openai", "gpt-6-luna", "GPT-6 Luna", effort = null, fast = true), again.assistantModel)
        assertEquals(ModelChoice("anthropic", "claude-opus-5-5", "Opus 5.5", ReasoningEffort.High), again.model)
    }

    @Test
    fun anotherServerForgetsTheModelsPickedOnTheLastOne() = runBlocking {
        val store = DesktopSettings(dir)
        store.saveSetup("http://one:8642", "k", "", "")
        store.setModel(ModelProfile.Chats, ModelChoice("anthropic", "claude-opus-5-5", "Opus 5.5"))
        store.saveSetup("http://one:8642", "k2", "", "")
        assertEquals("claude-opus-5-5", store.current().model.model)
        store.saveSetup("http://two:8642", "k2", "", "")
        assertEquals(ModelChoice(), store.current().model)
    }

    @Test
    fun aKeyWithCharactersOutsideAsciiComesBackTheSame() = runBlocking {
        DesktopSettings(dir).saveSetup("http://clark:8642", "sleutel-€-ü", "", "")
        assertEquals("sleutel-€-ü", DesktopSettings(dir).current().apiKey)
    }

    @Test
    fun richRepliesStartOnAlsoAfterAVersionThatStoredThemOff() = runBlocking {
        // Before OpenUI was drawn here, every save wrote richReplies = false without the user choosing it.
        File(dir, "settings.json").writeText("""{"serverUrl": "http://clark:8642", "richReplies": false}""")
        val store = DesktopSettings(dir)
        assertTrue(store.current().richReplies)
        store.setRichReplies(false)
        assertFalse(DesktopSettings(dir).current().richReplies)
    }

    @Test
    fun windowsOnlySettingsSurviveARestart() = runBlocking {
        val store = DesktopSettings(dir)
        store.setTextScale(1.18f)
        store.setThreadSort(ThreadSort.Title)
        store.setReadAloud(true)
        store.setEnglishVoice("bm_george")
        store.setOtherVoice("F3")
        store.setShortcut(ShortcutAction.NewChat, KeyCombo(ShortcutKey.T, ctrl = true))
        store.setShortcut(ShortcutAction.ReadAloud, null)

        val again = DesktopSettings(dir).prefs.value
        // Text sizes snap to the steps Settings offers.
        assertEquals(1.2f, again.textScale)
        assertEquals(ThreadSort.Title, again.threadSort)
        assertTrue(again.readAloud)
        assertEquals("bm_george", again.englishVoice)
        assertEquals("F3", again.otherVoice)
        assertEquals(KeyCombo(ShortcutKey.T, ctrl = true), again.shortcut(ShortcutAction.NewChat))
        assertNull(again.shortcut(ShortcutAction.ReadAloud))
        assertEquals(ShortcutAction.QuickAsk.default, again.shortcut(ShortcutAction.QuickAsk))

        store.resetShortcuts()
        assertEquals(ShortcutAction.NewChat.default, DesktopSettings(dir).prefs.value.shortcut(ShortcutAction.NewChat))
    }

    @Test
    fun aQuickQuestionShortcutSwitchedOffBeforeStaysOffUntilKeysAreSet() = runBlocking {
        // The version before shortcuts could change had an on/off switch.
        File(dir, "settings.json").writeText("""{"serverUrl": "http://clark:8642", "quickAskShortcut": false}""")
        val store = DesktopSettings(dir)
        assertNull(store.prefs.value.shortcut(ShortcutAction.QuickAsk))
        store.setShortcut(ShortcutAction.QuickAsk, ShortcutAction.QuickAsk.default)
        assertEquals(ShortcutAction.QuickAsk.default, DesktopSettings(dir).prefs.value.shortcut(ShortcutAction.QuickAsk))
    }

    @Test
    fun unknownVoicesAndSizesFallBackToTheDefaults() {
        File(dir, "settings.json").writeText("""{"textScale": 7.0, "englishVoice": "nobody", "threadSort": "random"}""")
        val prefs = DesktopSettings(dir).prefs.value
        assertEquals(1f, prefs.textScale)
        assertEquals("af_heart", prefs.englishVoice)
        assertEquals(ThreadSort.Activity, prefs.threadSort)
    }

    @Test
    fun aDamagedFileStartsFromScratch() {
        File(dir, "settings.json").writeText("{ not json")
        assertFalse(DesktopSettings(dir).current().isConfigured)
    }
}
