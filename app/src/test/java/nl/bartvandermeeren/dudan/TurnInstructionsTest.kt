package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.chat.TurnInstructions
import nl.bartvandermeeren.dudan.chat.TurnOrigin
import nl.bartvandermeeren.dudan.chat.TurnOrigin.Surface
import nl.bartvandermeeren.dudan.openui.OpenUiPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnInstructionsTest {
    @Test
    fun appTurnsSayTheUserIsOnTheirPhoneAndThatMarkdownRenders() {
        val text = TurnInstructions.context(TurnOrigin(), phoneControl = false)
        assertTrue(text, text.startsWith("The user is talking to you through dudan, the Hermes app on their Android phone."))
        assertTrue(text, text.contains("dudan renders Markdown"))
        assertFalse(text, text.contains("read aloud"))
        assertFalse(text, text.contains("side button"))
        assertFalse(text, text.contains("screenshot"))
    }

    @Test
    fun spokenTurnsAskForRepliesThatCanBeHeardInsteadOfMarkdown() {
        listOf(TurnOrigin(Surface.Assistant, spoken = true), TurnOrigin(Surface.Live, spoken = true)).forEach { origin ->
            val text = TurnInstructions.context(origin, phoneControl = false)
            assertTrue(text, text.contains("Your reply is read aloud"))
            assertFalse(text, text.contains("Markdown"))
        }
        assertTrue(TurnInstructions.context(TurnOrigin(Surface.Live, spoken = true), phoneControl = false).contains("Live mode"))
    }

    @Test
    fun assistantTurnsMentionTheSideButtonAndAnAttachedScreenshot() {
        val text = TurnInstructions.context(TurnOrigin(Surface.Assistant, screenshot = true), phoneControl = false)
        assertTrue(text, text.contains("with the side button"))
        assertTrue(text, text.contains("screenshot of their screen"))
        // Typed in the overlay: shown, not read aloud.
        assertTrue(text, text.contains("dudan renders Markdown"))
    }

    @Test
    fun phoneControlPointsTheAgentAtThePhoneToolsOrAtTheSetting() {
        val on = TurnInstructions.context(TurnOrigin(), phoneControl = true)
        assertTrue(on, on.contains("act on this same phone"))
        listOf("open_app", "list_apps", "open_link", "set_timer", "set_alarm", "media", "phone_status").forEach {
            assertTrue(it, on.contains(it))
        }
        val off = TurnInstructions.context(TurnOrigin(), phoneControl = false)
        assertFalse(off, off.contains("set_timer"))
        assertTrue(off, off.contains("turn on Phone control"))
        // Unknown, as for reviews: nothing about the phone tools either way.
        val neither = TurnInstructions.context(TurnOrigin(), phoneControl = null)
        assertFalse(neither, neither.contains("set_timer") || neither.contains("Phone control"))
    }

    @Test
    fun theOpenUiPromptComesFirstAndUnchangedSoItStaysCached() {
        val shown = TurnInstructions.build(TurnOrigin(), phoneControl = true, richReplies = true)
        assertTrue(shown.startsWith(OpenUiPrompt.instructions + "\n\n## This turn\n\n"))
        assertTrue(shown.endsWith(TurnInstructions.context(TurnOrigin(), phoneControl = true)))
        // Rich replies off: only the turn's context.
        assertEquals(
            "## This turn\n\n" + TurnInstructions.context(TurnOrigin(), phoneControl = true),
            TurnInstructions.build(TurnOrigin(), phoneControl = true, richReplies = false),
        )
    }

    @Test
    fun repliesThatAreReadAloudGetNoOpenUiPrompt() {
        val spoken = TurnOrigin(Surface.Live, spoken = true)
        val text = TurnInstructions.build(spoken, phoneControl = false, richReplies = true)
        assertFalse(text, text.contains("openui-lang"))
        assertEquals("## This turn\n\n" + TurnInstructions.context(spoken, phoneControl = false), text)
    }
}
