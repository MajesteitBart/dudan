package nl.bartvandermeeren.dudan.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import nl.bartvandermeeren.dudan.R

/**
 * The background the user picked in Settings: the sky behind the glass and the colors the glass frosts
 * it into. Dusk is the original. The others take Dusk's lightness at every step in OKLCH and change
 * only hue and chroma, so white text keeps the contrast measured on Dusk. Forest sits a little darker,
 * since green carries more luminance at the same lightness, and Midnight darker still.
 *
 * [top] to [horizon] paint the sky, [deep] is the night it fades to in the corners and behind an open
 * chat. [background] is the sky's average, which glass blends toward. [wash] darkens glass cards,
 * [chromeSolid], [menu], [sheet] and [overlay] are the solid surfaces for Reduce transparency and for
 * windows the app's blur can't reach. [light] is the cool light high on the right, [glow] the light low
 * on the left under the Moon accent, and [tile] the CTA button's arrow tile under Moon.
 */
enum class Sky(
    val top: Color,
    val high: Color,
    val mid: Color,
    val horizon: Color,
    val deep: Color,
    val background: Color,
    val wash: Color,
    val chromeSolid: Color,
    val menu: Color,
    val sheet: Color,
    val overlay: Color,
    val ctaBody: Color,
    val light: Color,
    val glow: Color,
    val tile: List<Color>,
    @param:StringRes val label: Int,
) {
    Dusk(
        Color(0xFF232C62), Color(0xFF394891), Color(0xFF5061A6), Color(0xFF8676BA), Color(0xFF17173A),
        Color(0xFF1E2350), Color(0xFF1B1942), Color(0xFF383C68), Color(0xFF2A2C52), Color(0xFF262950), Color(0xFF1F2148),
        Color(0xFF1C1936), Color(0xFF8FA6E6), Color(0xFFC08AB4),
        listOf(Color(0xFF5E5BD4), Color(0xFF8A6AD8), Color(0xFFCF7EB3)),
        R.string.sky_dusk,
    ),
    Ocean(
        Color(0xFF003657), Color(0xFF005582), Color(0xFF2A6D97), Color(0xFF34929B), Color(0xFF001F34),
        Color(0xFF002C47), Color(0xFF00233B), Color(0xFF1D455F), Color(0xFF10344B), Color(0xFF0A3148), Color(0xFF012940),
        Color(0xFF052031), Color(0xFF77B0D7), Color(0xFF6BAE9C),
        listOf(Color(0xFF0077B6), Color(0xFF0095A2), Color(0xFF4BB299)),
        R.string.sky_ocean,
    ),
    Forest(
        Color(0xFF083C2C), Color(0xFF145944), Color(0xFF346C58), Color(0xFF688461), Color(0xFF042219),
        Color(0xFF073024), Color(0xFF02271B), Color(0xFF26493C), Color(0xFF19382D), Color(0xFF153529), Color(0xFF0C2D22),
        Color(0xFF0B231B), Color(0xFF83B5A1), Color(0xFFA6A079),
        listOf(Color(0xFF008362), Color(0xFF619156), Color(0xFFA89E68)),
        R.string.sky_forest,
    ),
    Sunset(
        Color(0xFF461F51), Color(0xFF6A367A), Color(0xFF815090), Color(0xFFB86A5B), Color(0xFF291030),
        Color(0xFF391942), Color(0xFF2E1136), Color(0xFF51335B), Color(0xFF3F2547), Color(0xFF3C2144), Color(0xFF34193C),
        Color(0xFF28152E), Color(0xFFC295D0), Color(0xFFC59265),
        listOf(Color(0xFF9943B1), Color(0xFFD1543F), Color(0xFFD28B47)),
        R.string.sky_sunset,
    ),
    Graphite(
        Color(0xFF2F3238), Color(0xFF4C5058), Color(0xFF63676E), Color(0xFF7F838A), Color(0xFF1A1C20),
        Color(0xFF26292E), Color(0xFF1E2024), Color(0xFF3E4146), Color(0xFF2F3135), Color(0xFF2C2E32), Color(0xFF24262A),
        Color(0xFF1C1E21), Color(0xFFA5A8B0), Color(0xFF9B9EA5),
        listOf(Color(0xFF696F7C), Color(0xFF7C828E), Color(0xFF999DA6)),
        R.string.sky_graphite,
    ),
    Midnight(
        Color(0xFF08142D), Color(0xFF1D2F52), Color(0xFF334566), Color(0xFF4E6080), Color(0xFF010310),
        Color(0xFF111C32), Color(0xFF0A1427), Color(0xFF283449), Color(0xFF1A2436), Color(0xFF172134), Color(0xFF101A2D),
        Color(0xFF0B1220), Color(0xFF7284A4), Color(0xFF777796),
        listOf(Color(0xFF395790), Color(0xFF4E6A9E), Color(0xFF8080AB)),
        R.string.sky_midnight,
    ),
    ;

    companion object {
        /** The stored name, or Dusk for anything unknown. */
        fun from(name: String?): Sky = entries.firstOrNull { it.name == name } ?: Dusk
    }
}

val LocalSky = staticCompositionLocalOf { Sky.Dusk }
