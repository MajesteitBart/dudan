package nl.bartvandermeeren.dudan.data

import android.view.KeyEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Complete key actions; Android Back and modifier-only events remain local. */
object DesktopKeys {
    fun input(code: Int, unicode: Int, ctrl: Boolean = false, alt: Boolean = false,
        shift: Boolean = false, meta: Boolean = false): JsonObject? {
        if (!ctrl && !alt && !meta && unicode in 32..0x10ffff) return buildJsonObject {
            put("kind", "text"); put("text", String(Character.toChars(unicode)))
        }
        val key = when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + code - KeyEvent.KEYCODE_A).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + code - KeyEvent.KEYCODE_0).toString()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ESCAPE -> "escape"
            KeyEvent.KEYCODE_DEL -> "backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"
            KeyEvent.KEYCODE_MOVE_END -> "end"
            KeyEvent.KEYCODE_PAGE_UP -> "pageup"
            KeyEvent.KEYCODE_PAGE_DOWN -> "pagedown"
            KeyEvent.KEYCODE_SPACE -> "space"
            else -> return null
        }
        val keys = buildList { if (ctrl) add("ctrl"); if (alt) add("alt"); if (shift) add("shift"); if (meta) add("super"); add(key) }
        if (keys.size > 4) return null
        return buildJsonObject { put("kind", "key"); put("keys", JsonArray(keys.map(::JsonPrimitive))) }
    }
}
