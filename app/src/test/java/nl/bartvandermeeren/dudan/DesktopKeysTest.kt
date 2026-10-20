package nl.bartvandermeeren.dudan

import android.view.KeyEvent
import kotlinx.serialization.json.Json
import nl.bartvandermeeren.dudan.data.DesktopKeys
import org.junit.Assert.*
import org.junit.Test

class DesktopKeysTest {
    @Test fun textChordsAndNavigationBecomeCompleteActions() {
        assertEquals(Json.parseToJsonElement("""{"kind":"text","text":"A"}"""), DesktopKeys.input(KeyEvent.KEYCODE_A, 65, shift = true))
        assertEquals(Json.parseToJsonElement("""{"kind":"key","keys":["ctrl","a"]}"""), DesktopKeys.input(KeyEvent.KEYCODE_A, 1, ctrl = true))
        assertEquals(Json.parseToJsonElement("""{"kind":"key","keys":["backspace"]}"""), DesktopKeys.input(KeyEvent.KEYCODE_DEL, 0))
        assertEquals(Json.parseToJsonElement("""{"kind":"key","keys":["shift","tab"]}"""), DesktopKeys.input(KeyEvent.KEYCODE_TAB, 9, shift = true))
    }
    @Test fun backAndModifierOnlyEventsRemainLocal() {
        assertNull(DesktopKeys.input(KeyEvent.KEYCODE_BACK, 0))
        assertNull(DesktopKeys.input(KeyEvent.KEYCODE_CTRL_LEFT, 0, ctrl = true))
        assertNull(DesktopKeys.input(KeyEvent.KEYCODE_A, 0, ctrl = true, alt = true, shift = true, meta = true))
    }
}
