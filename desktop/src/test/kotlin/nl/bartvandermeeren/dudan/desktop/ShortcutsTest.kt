package nl.bartvandermeeren.dudan.desktop

import java.awt.event.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val defaults = Shortcuts.resolve(emptyMap())

    @Test
    fun combosRoundTripThroughTheirSavedForm() {
        listOf(
            KeyCombo(ShortcutKey.Space, ctrl = true, alt = true),
            KeyCombo(ShortcutKey.Equals, ctrl = true),
            KeyCombo(ShortcutKey.NumPlus, ctrl = true, shift = true),
            KeyCombo(ShortcutKey.F5),
            KeyCombo(ShortcutKey.Up, alt = true),
        ).forEach { assertEquals(it, KeyCombo.parse(it.toString())) }
        assertEquals("Ctrl+Alt+Space", KeyCombo(ShortcutKey.Space, ctrl = true, alt = true).toString())
        assertNull(KeyCombo.parse(""))
        assertNull(KeyCombo.parse("Ctrl+Hyper"))
    }

    @Test
    fun theGlobalShortcutUsesWindowsOwnKeyCodes() {
        // AWT and Win32 agree on letters and Space, not on punctuation.
        assertEquals(0x20, ShortcutKey.Space.win)
        assertEquals(KeyEvent.VK_EQUALS, ShortcutKey.Equals.awt)
        assertEquals(0xBB, ShortcutKey.Equals.win)
        val quick = KeyCombo(ShortcutKey.Space, ctrl = true, alt = true)
        assertEquals(GlobalShortcut.MOD_CONTROL or GlobalShortcut.MOD_ALT, quick.winModifiers)
    }

    @Test
    fun aSavedChoiceReplacesTheDefaultAndAnEmptyOneRemovesIt() {
        val resolved = Shortcuts.resolve(mapOf("new_chat" to "Ctrl+Shift+N", "read_aloud" to ""))
        assertEquals(KeyCombo(ShortcutKey.N, ctrl = true, shift = true), resolved[ShortcutAction.NewChat])
        assertNull(resolved[ShortcutAction.ReadAloud])
        assertEquals(ShortcutAction.QuickAsk.default, resolved[ShortcutAction.QuickAsk])
    }

    @Test
    fun theDefaultsDontClash() {
        val combos = defaults.values.filterNotNull()
        assertEquals(combos.size, combos.toSet().size)
        defaults.forEach { (action, combo) -> assertNull("$action", combo?.let { Shortcuts.problem(action, it, defaults) }) }
    }

    @Test
    fun typingAndEditingKeysAreRefused() {
        assertEquals(ShortcutProblem.NeedsModifier, Shortcuts.problem(ShortcutAction.NewChat, KeyCombo(ShortcutKey.N), defaults))
        assertEquals(ShortcutProblem.NeedsModifier, Shortcuts.problem(ShortcutAction.NewChat, KeyCombo(ShortcutKey.N, shift = true), defaults))
        // A function key types nothing, so it may stand alone in the window, but not over every app.
        assertNull(Shortcuts.problem(ShortcutAction.NewChat, KeyCombo(ShortcutKey.F2), defaults))
        assertEquals(ShortcutProblem.NeedsModifier, Shortcuts.problem(ShortcutAction.QuickAsk, KeyCombo(ShortcutKey.F2), defaults))
        assertEquals(ShortcutProblem.Reserved, Shortcuts.problem(ShortcutAction.NewChat, KeyCombo(ShortcutKey.C, ctrl = true), defaults))
        assertEquals(ShortcutProblem.Reserved, Shortcuts.problem(ShortcutAction.NextChat, KeyCombo(ShortcutKey.Right, ctrl = true, shift = true), defaults))
    }

    @Test
    fun aComboAnotherActionHasIsInUse() {
        val combo = KeyCombo(ShortcutKey.N, ctrl = true)
        assertEquals(ShortcutProblem.InUse, Shortcuts.problem(ShortcutAction.OpenSettings, combo, defaults))
        assertEquals(ShortcutAction.NewChat, Shortcuts.owner(combo, defaults))
        // Its own combo again is fine.
        assertNull(Shortcuts.problem(ShortcutAction.NewChat, combo, defaults))
    }

    @Test
    fun matchingNeedsTheExactModifiers() {
        val combo = KeyCombo(ShortcutKey.U, ctrl = true, shift = true)
        assertTrue(combo.matches(KeyEvent.VK_U, ctrl = true, alt = false, shift = true))
        assertTrue(!combo.matches(KeyEvent.VK_U, ctrl = true, alt = false, shift = false))
        assertTrue(!combo.matches(KeyEvent.VK_U, ctrl = true, alt = true, shift = true))
    }
}
