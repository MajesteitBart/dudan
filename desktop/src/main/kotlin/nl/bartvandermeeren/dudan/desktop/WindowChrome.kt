package nl.bartvandermeeren.dudan.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Windows draws the title bar itself, white by default above dudan's dark sky. DWM can paint it in the
 * sky's own top color (Windows 11) or at least dark (Windows 10); older systems keep their default.
 */
object WindowChrome {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    fun apply(window: Window, caption: Color, text: Color) {
        val api = dwm ?: return
        val hwnd = WinDef.HWND(Native.getComponentPointer(window) ?: return)
        api.DwmSetWindowAttribute(hwnd, USE_IMMERSIVE_DARK_MODE, IntByReference(1), 4)
        api.DwmSetWindowAttribute(hwnd, CAPTION_COLOR, IntByReference(colorRef(caption)), 4)
        api.DwmSetWindowAttribute(hwnd, TEXT_COLOR, IntByReference(colorRef(text)), 4)
    }

    /** Win32's COLORREF is 0x00BBGGRR. */
    private fun colorRef(color: Color): Int {
        val argb = color.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
