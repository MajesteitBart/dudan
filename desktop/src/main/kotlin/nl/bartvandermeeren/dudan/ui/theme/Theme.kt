package nl.bartvandermeeren.dudan.ui.theme

import java.awt.Toolkit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.delay

/**
 * Dusk glass, after Superhuman's frosted panels over a twilight sky and beautifului.dev's quiet
 * components. The default sky (GlassBackdrop) runs from deep blue at the top to a lavender horizon. Glass
 * cards frost it into slate-lavender that keeps white text above 7:1; Superhuman's own panels over bright sky
 * measure 3.3 to 4.3:1, too low for reading replies all day. Inside a card, elements are outlined or
 * flat. The accent the user picks lives in Accent.kt, the sky in Sky.kt: the colors below that read
 * [LocalSky] follow it, so they only resolve inside a composition. Every translucent color has a solid
 * counterpart for Reduce transparency.
 */
object Palette {
    // The sky, top to horizon, and the dark it fades to at the corners.
    val SkyTop: Color @Composable @ReadOnlyComposable get() = LocalSky.current.top
    val SkyHigh: Color @Composable @ReadOnlyComposable get() = LocalSky.current.high
    val SkyMid: Color @Composable @ReadOnlyComposable get() = LocalSky.current.mid
    val SkyHorizon: Color @Composable @ReadOnlyComposable get() = LocalSky.current.horizon
    val SkyDeep: Color @Composable @ReadOnlyComposable get() = LocalSky.current.deep
    /** The sky's average; what glass and fallbacks blend toward. */
    val Background: Color @Composable @ReadOnlyComposable get() = LocalSky.current.background
    val BackdropEdge: Color @Composable @ReadOnlyComposable get() = LocalSky.current.deep

    // Glass cards: a dark wash in the sky's hue and a little white over the blurred sky, under a light edge.
    val GlassWash: Color @Composable @ReadOnlyComposable get() = LocalSky.current.wash.copy(alpha = 0.71f)
    val Chrome = Color(0x0FFFFFFF)
    val Composer = Color(0x0FFFFFFF)
    val ChromeSolid: Color @Composable @ReadOnlyComposable get() = LocalSky.current.chromeSolid
    // The phone's full-screen sidebar: darker, so its list reads over the blurred chat.
    val SidebarGlass: Color @Composable @ReadOnlyComposable get() = LocalSky.current.wash.copy(alpha = 0.45f)

    // Inside a card: outlined chips and fields, flat bubbles and code.
    val Surface = Color(0x0FFFFFFF) // selected rows, fields, quiet fills
    val Card = Color(0x1AFFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val Outline = Color(0x38FFFFFF) // chip and field borders
    val Code = Color(0x38000010) // code blocks: darker than the card

    // Window glass: popups, dialogs and sheets live in their own window, where the app's blur can't
    // reach. The system blurs behind dialogs and sheets; menus and phones without that blur get the
    // solid versions.
    val Menu: Color @Composable @ReadOnlyComposable get() = LocalSky.current.menu.copy(alpha = 0.96f)
    val MenuSolid: Color @Composable @ReadOnlyComposable get() = LocalSky.current.menu
    val Sheet: Color @Composable @ReadOnlyComposable get() = LocalSky.current.sheet.copy(alpha = 0.92f)
    val SheetSolid: Color @Composable @ReadOnlyComposable get() = LocalSky.current.sheet
    // The assistant overlay floats over other apps, so its glass is darker still.
    val OverlayGlass: Color @Composable @ReadOnlyComposable get() = LocalSky.current.overlay.copy(alpha = 0.85f)
    val OverlaySolid: Color @Composable @ReadOnlyComposable get() = LocalSky.current.overlay.copy(alpha = 0.98f)

    val TextPrimary = Color(0xFFF7F7FC)
    val TextSecondary = Color(0xFFCBCDE2)
    val TextTertiary = Color(0xFFBCBFDA)
    val Icon = Color(0xFFEDEEF8)
    val Send = Color(0xFF6A62F2)
    val Live = Color(0x1FFFFFFF)
    val Button = Color(0x2EFFFFFF)
    val ButtonText = Color(0xFFF7F7FC)
    val Disabled = Color(0x14FFFFFF)
    val InlineCode = Color(0x29000010)
    val Danger = Color(0xFFFFB0B3)
    val DangerPane = Color(0x33FF5A6A)
    val WarningPane = Color(0x26F5B84A)
    val Success = Color(0xFF86E3AE)

    // The orb's gradient stops and glows, used for accents and the backdrop.
    val OrbViolet = Color(0xFFB867F7)
    val OrbIndigo = Color(0xFF7061F1)
    val OrbBlue = Color(0xFF4A63EE)
    val GlowViolet = Color(0xFF7A5CFF)
    val GlowBlue = Color(0xFF2A5BFF)

    // Accent colors for the overlay and Live glows.
    val SparkBlue = Color(0xFF4C8DF6)
    val SparkViolet = Color(0xFF8E6CF1)
    val SparkRose = Color(0xFFF2728C)
    val SparkAmber = Color(0xFFF5A623)
}

// The Android app's font files, copied onto the classpath by the build (see desktop/build.gradle.kts).
val GoogleSans = FontFamily(
    Font("fonts/google_sans_flex_400.ttf", FontWeight.Normal),
    Font("fonts/google_sans_flex_500.ttf", FontWeight.Medium),
    Font("fonts/google_sans_flex_600.ttf", FontWeight.SemiBold),
)

val GoogleSansCode = FontFamily(
    Font("fonts/google_sans_code_400.ttf", FontWeight.Normal),
    Font("fonts/google_sans_code_500.ttf", FontWeight.Medium),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) = TextStyle(
    fontFamily = GoogleSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val DudanTypography = Typography(
    displayLarge = style(44, 52),
    displayMedium = style(36, 44),
    displaySmall = style(32, 40, spacing = -0.2f),
    headlineLarge = style(30, 38),
    headlineMedium = style(26, 34),
    headlineSmall = style(23, 30),
    titleLarge = style(21, 28),
    titleMedium = style(17, 24, FontWeight.Medium),
    titleSmall = style(15, 20, FontWeight.Medium),
    bodyLarge = style(17, 27),
    bodyMedium = style(15, 22),
    bodySmall = style(13, 18),
    labelLarge = style(15, 20, FontWeight.Medium),
    labelMedium = style(13, 16, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)

/**
 * Material components (dialog buttons, text fields) take the accent's soft tone as their primary color
 * and their surfaces from the sky.
 */
private fun dudanColors(accent: Accent, sky: Sky) = darkColorScheme(
    primary = accent.soft,
    onPrimary = accent.on,
    primaryContainer = Palette.Send,
    onPrimaryContainer = Color.White,
    secondary = Palette.TextSecondary,
    onSecondary = Color.Black,
    secondaryContainer = Palette.Card,
    onSecondaryContainer = Palette.TextPrimary,
    background = sky.background,
    onBackground = Palette.TextPrimary,
    surface = sky.background,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = sky.background,
    surfaceContainerLow = sky.mid,
    surfaceContainer = sky.sheet.copy(alpha = 0.92f),
    surfaceContainerHigh = sky.menu.copy(alpha = 0.96f),
    surfaceContainerHighest = sky.menu.copy(alpha = 0.96f),
    inverseSurface = Palette.TextPrimary,
    inverseOnSurface = sky.background,
    outline = Palette.Outline,
    outlineVariant = Color(0x14FFFFFF),
    error = Palette.Danger,
    onError = Color(0xFF601410),
    scrim = sky.deep,
)

/**
 * True when glass should turn into solid panels: the user asked for less transparency, or the phone's
 * color contrast is raised. Like iOS's Reduce Transparency, this trades the look for legibility.
 */
val LocalReduceTransparency = staticCompositionLocalOf { false }

/** [textScale] is Settings' text size: it scales every text, as Windows' own text size does, and leaves the layout be. */
@Composable
fun DudanTheme(
    reduceTransparency: Boolean = false,
    accent: Accent = Accent.Moon,
    sky: Sky = Sky.Dusk,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val reduced = reduceTransparency || rememberSystemHighContrast()
    val colors = remember(accent, sky) { dudanColors(accent, sky) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalReduceTransparency provides reduced,
        LocalAccent provides accent,
        LocalSky provides sky,
        LocalDensity provides Density(density.density, density.fontScale * textScale),
    ) {
        MaterialTheme(colorScheme = colors, typography = DudanTypography, content = content)
    }
}

/**
 * Windows' contrast themes (Settings > Accessibility > Contrast themes). AWT reports the setting but
 * not its changes, so it's read again every few seconds.
 */
@Composable
private fun rememberSystemHighContrast(): Boolean {
    var on by remember { mutableStateOf(windowsHighContrast()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            on = windowsHighContrast()
        }
    }
    return on
}

private fun windowsHighContrast(): Boolean =
    Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") as? Boolean ?: false
