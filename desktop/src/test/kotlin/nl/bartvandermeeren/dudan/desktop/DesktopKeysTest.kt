package nl.bartvandermeeren.dudan.desktop

import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import nl.bartvandermeeren.dudan.data.DesktopKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopKeysTest {
    private fun JsonObject.text() = this["text"]?.jsonPrimitive?.content
    private fun JsonObject.keys() = this["keys"]?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun typedCharactersGoAsTextAndTheirKeysStayQuiet() {
        assertEquals("a", DesktopKeys.typed('a', ctrl = false, alt = false, altGraph = false, meta = false)?.text())
        assertEquals("A", DesktopKeys.typed('A', ctrl = false, alt = false, altGraph = false, meta = false)?.text())
        assertEquals(" ", DesktopKeys.typed(' ', ctrl = false, alt = false, altGraph = false, meta = false)?.text())
        // The press that typed it sends nothing, or the agent would get the letter twice.
        assertNull(DesktopKeys.pressed(KeyEvent.VK_A, ctrl = false, alt = false, shift = false, meta = false))
        assertNull(DesktopKeys.pressed(KeyEvent.VK_SPACE, ctrl = false, alt = false, shift = true, meta = false))
    }

    @Test
    fun altGrCharactersAreTextOnceAndCtrlAltIsAShortcut() {
        // AltGr comes with Windows' Ctrl+Alt; its character goes once, as text.
        assertEquals("€", DesktopKeys.typed('€', ctrl = true, alt = true, altGraph = true, meta = false)?.text())
        assertNull(DesktopKeys.pressed(KeyEvent.VK_E, ctrl = true, alt = true, shift = false, meta = false, altGraph = true))
        // The left Ctrl and Alt together are a shortcut, as on the phone.
        assertEquals(listOf("ctrl", "alt", "t"), DesktopKeys.pressed(KeyEvent.VK_T, ctrl = true, alt = true, shift = false, meta = false)?.keys())
        assertNull(DesktopKeys.typed('t', ctrl = true, alt = true, altGraph = false, meta = false))
        // Keys that type nothing keep their own modifiers, without AltGr's.
        assertEquals(listOf("enter"), DesktopKeys.pressed(KeyEvent.VK_ENTER, ctrl = true, alt = true, shift = false, meta = false, altGraph = true)?.keys())
    }

    @Test
    fun shortcutsAndKeysThatTypeNothingGoAsChords() {
        assertEquals(listOf("ctrl", "c"), DesktopKeys.pressed(KeyEvent.VK_C, ctrl = true, alt = false, shift = false, meta = false)?.keys())
        assertEquals(listOf("ctrl", "shift", "t"), DesktopKeys.pressed(KeyEvent.VK_T, ctrl = true, alt = false, shift = true, meta = false)?.keys())
        assertEquals(listOf("alt", "tab"), DesktopKeys.pressed(KeyEvent.VK_TAB, ctrl = false, alt = true, shift = false, meta = false)?.keys())
        assertEquals(listOf("enter"), DesktopKeys.pressed(KeyEvent.VK_ENTER, ctrl = false, alt = false, shift = false, meta = false)?.keys())
        assertEquals(listOf("shift", "left"), DesktopKeys.pressed(KeyEvent.VK_LEFT, ctrl = false, alt = false, shift = true, meta = false)?.keys())
        // The control characters a shortcut also types are dropped.
        assertNull(DesktopKeys.typed(3.toChar(), ctrl = true, alt = false, altGraph = false, meta = false))
        assertNull(DesktopKeys.typed('\n', ctrl = false, alt = false, altGraph = false, meta = false))
        assertNull(DesktopKeys.typed('c', ctrl = true, alt = false, altGraph = false, meta = false))
        // Modifier keys on their own stay local.
        assertNull(DesktopKeys.pressed(KeyEvent.VK_SHIFT, ctrl = false, alt = false, shift = true, meta = false))
    }

    @Test
    fun theTrackerTellsAltGrFromLeftCtrlAltByTheKeysThemselves() {
        val source = object : Component() {}
        val ctrlAlt = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK
        fun key(id: Int, code: Int, modifiers: Int, char: Char = KeyEvent.CHAR_UNDEFINED, location: Int = KeyEvent.KEY_LOCATION_STANDARD) =
            KeyEvent(source, id, 0L, modifiers, if (id == KeyEvent.KEY_TYPED) KeyEvent.VK_UNDEFINED else code, char, if (id == KeyEvent.KEY_TYPED) KeyEvent.KEY_LOCATION_UNKNOWN else location)
        val tracker = DesktopKeys.Tracker()

        // AltGr+E: Windows sends left Ctrl, then the right Alt, then the character.
        assertNull(tracker.input(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK, location = KeyEvent.KEY_LOCATION_LEFT)))
        assertNull(tracker.input(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ALT, ctrlAlt, location = KeyEvent.KEY_LOCATION_RIGHT)))
        assertNull(tracker.input(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_E, ctrlAlt)))
        assertEquals("€", tracker.input(key(KeyEvent.KEY_TYPED, 0, ctrlAlt, '€'))?.text())
        assertNull(tracker.input(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_ALT, InputEvent.CTRL_DOWN_MASK, location = KeyEvent.KEY_LOCATION_RIGHT)))

        // Then the left Ctrl and Alt with T: a shortcut.
        assertNull(tracker.input(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ALT, ctrlAlt, location = KeyEvent.KEY_LOCATION_LEFT)))
        assertEquals(listOf("ctrl", "alt", "t"), tracker.input(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_T, ctrlAlt))?.keys())
    }

    @Test
    fun theRightAltWithoutCtrlIsAPlainAlt() {
        // On US English the right Alt is Alt; Java still reports it as AltGraph.
        val source = object : Component() {}
        val altGraph = InputEvent.ALT_DOWN_MASK or InputEvent.ALT_GRAPH_DOWN_MASK
        val tracker = DesktopKeys.Tracker()
        assertNull(tracker.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, altGraph, KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_RIGHT)))
        assertEquals(listOf("alt", "left"), tracker.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, altGraph, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD))?.keys())
        assertEquals(listOf("alt", "f"), tracker.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, altGraph, KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD))?.keys())
    }

    @Test
    fun anAltGrReleasedElsewhereIsForgottenWhenTheFocusLeaves() {
        val source = object : Component() {}
        val ctrlAlt = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK
        val tracker = DesktopKeys.Tracker()
        assertNull(tracker.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, ctrlAlt, KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_RIGHT)))
        // Its release went to another window; the surface lost the focus meanwhile.
        tracker.reset()
        assertNull(tracker.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, ctrlAlt, KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT)))
        assertEquals(listOf("ctrl", "alt", "t"), tracker.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, ctrlAlt, KeyEvent.VK_T, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD))?.keys())
    }
}
