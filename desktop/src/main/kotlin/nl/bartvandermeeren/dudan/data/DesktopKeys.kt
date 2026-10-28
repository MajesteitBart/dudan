package nl.bartvandermeeren.dudan.data

import java.awt.event.KeyEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The PC's keys as Agent screen input, the counterpart of the phone's DesktopKeys. Windows reports a typed
 * character (KEY_TYPED) apart from the key that made it (KEY_PRESSED): characters go as text, and keys that
 * type nothing, or that come with Ctrl, Alt or the Windows key, go as a chord. Modifier-only presses stay local.
 *
 * AltGr is the right Alt key on layouts that have one, and Windows always adds Ctrl to it. A character typed
 * with it is text, and its Ctrl+Alt are left out of chords. A left Ctrl+Alt is a shortcut, as on the phone,
 * and so is the right Alt without Ctrl, which is a plain Alt on layouts such as US English.
 */
object DesktopKeys {
    /** Follows one surface's keys, since AltGr shows only on the right Alt key's own press. */
    class Tracker {
        private var rightAlt = false

        /** Forgets the right Alt, for when its release may have gone to another window. */
        fun reset() {
            rightAlt = false
        }

        fun input(event: KeyEvent): JsonObject? {
            if (event.keyCode == KeyEvent.VK_ALT_GRAPH || (event.keyCode == KeyEvent.VK_ALT && event.keyLocation == KeyEvent.KEY_LOCATION_RIGHT)) {
                rightAlt = event.id == KeyEvent.KEY_PRESSED
                return null
            }
            // A release that went to another window would leave it set; any key without Alt clears it.
            if (!event.isAltDown && !event.isAltGraphDown) rightAlt = false
            // Java also reports the right Alt alone as AltGraph, so only with Ctrl is it AltGr.
            val graph = (rightAlt || event.isAltGraphDown) && event.isControlDown
            return when (event.id) {
                KeyEvent.KEY_TYPED -> typed(event.keyChar, event.isControlDown, event.isAltDown, graph, event.isMetaDown)
                KeyEvent.KEY_PRESSED -> pressed(event.keyCode, event.isControlDown, event.isAltDown, event.isShiftDown, event.isMetaDown, graph)
                else -> null
            }
        }
    }

    /** A character the keyboard typed. With Ctrl, Alt or the Windows key it's a shortcut, which [pressed] sends. */
    internal fun typed(char: Char, ctrl: Boolean, alt: Boolean, altGraph: Boolean, meta: Boolean): JsonObject? {
        if (char == KeyEvent.CHAR_UNDEFINED || char.isISOControl()) return null
        if (!altGraph && (ctrl || alt || meta)) return null
        return buildJsonObject { put("kind", "text"); put("text", char.toString()) }
    }

    internal fun pressed(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean, altGraph: Boolean = false): JsonObject? {
        // AltGr's Ctrl and Alt belong to the character it types, not to a shortcut.
        val control = ctrl && !altGraph
        val option = alt && !altGraph
        val chord = control || option || meta
        val key = when (code) {
            in KeyEvent.VK_A..KeyEvent.VK_Z -> if (chord) ('a' + (code - KeyEvent.VK_A)).toString() else return null
            in KeyEvent.VK_0..KeyEvent.VK_9 -> if (chord) ('0' + (code - KeyEvent.VK_0)).toString() else return null
            KeyEvent.VK_SPACE -> if (chord) "space" else return null
            KeyEvent.VK_ENTER -> "enter"
            KeyEvent.VK_TAB -> "tab"
            KeyEvent.VK_ESCAPE -> "escape"
            KeyEvent.VK_BACK_SPACE -> "backspace"
            KeyEvent.VK_DELETE -> "delete"
            KeyEvent.VK_LEFT -> "left"
            KeyEvent.VK_RIGHT -> "right"
            KeyEvent.VK_UP -> "up"
            KeyEvent.VK_DOWN -> "down"
            KeyEvent.VK_HOME -> "home"
            KeyEvent.VK_END -> "end"
            KeyEvent.VK_PAGE_UP -> "pageup"
            KeyEvent.VK_PAGE_DOWN -> "pagedown"
            else -> return null
        }
        val keys = buildList { if (control) add("ctrl"); if (option) add("alt"); if (shift) add("shift"); if (meta) add("super"); add(key) }
        if (keys.size > 4) return null
        return buildJsonObject { put("kind", "key"); put("keys", JsonArray(keys.map(::JsonPrimitive))) }
    }
}
