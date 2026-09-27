package nl.bartvandermeeren.aight.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import nl.bartvandermeeren.aight.R

/**
 * The accent the user picked in Settings. [color] fills the send button, the selection bar, switches
 * and the primary choice, with dark [on] ink on it; every accent is light enough for that ink to stay
 * above 7:1. [soft] is the accent lifted toward white for links and small text on glass, and [glow]
 * tints the low light in the sky. Moon keeps the original white.
 */
enum class Accent(val color: Color, val on: Color, val soft: Color, val glow: Color, @param:StringRes val label: Int) {
    Moon(Color(0xFFF4F4F8), Color(0xFF1E1C3A), Color(0xFFD3D9FF), Color(0xFFC08AB4), R.string.accent_moon),
    Lavender(Color(0xFFB7A6FF), Color(0xFF1B1542), Color(0xFFD9D0FF), Color(0xFFA98BE0), R.string.accent_lavender),
    Blue(Color(0xFF80B3FF), Color(0xFF0D1C3D), Color(0xFFC6DCFF), Color(0xFF7FA0E6), R.string.accent_blue),
    Teal(Color(0xFF55D9C9), Color(0xFF062520), Color(0xFFABEEE5), Color(0xFF62BFC0), R.string.accent_teal),
    Green(Color(0xFF82DD9C), Color(0xFF0B2814), Color(0xFFBFEFCC), Color(0xFF86C79A), R.string.accent_green),
    Amber(Color(0xFFF7C85C), Color(0xFF2B1E02), Color(0xFFFAE0A6), Color(0xFFD8A26C), R.string.accent_amber),
    Coral(Color(0xFFFF9787), Color(0xFF30100C), Color(0xFFFFC8BF), Color(0xFFE0898C), R.string.accent_coral),
    Pink(Color(0xFFF59CCE), Color(0xFF2F0C22), Color(0xFFF9CAE5), Color(0xFFD58BBE), R.string.accent_pink),
    ;

    /** The user's bubble: the accent as a light tint over the sky. */
    val bubble: Color get() = color.copy(alpha = if (this == Moon) 0.14f else 0.24f)

    companion object {
        /** The stored name, or Moon for anything unknown (such as a preset removed in a later version). */
        fun from(name: String?): Accent = entries.firstOrNull { it.name == name } ?: Moon
    }
}

val LocalAccent = staticCompositionLocalOf { Accent.Moon }
