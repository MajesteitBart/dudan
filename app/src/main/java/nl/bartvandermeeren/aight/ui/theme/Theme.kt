package nl.bartvandermeeren.aight.ui.theme

import android.app.UiModeManager
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import nl.bartvandermeeren.aight.R

/**
 * Frosted glass over the navy and violet of the aight icon, in three levels (see Glass.kt):
 * - content sits on flat, quiet panes;
 * - chrome that floats over the scrolling chat (composer, top controls, sidebar) is blurred glass;
 * - dialogs, sheets, menus and the assistant overlay are denser glass in their own window.
 * Every translucent color has a solid counterpart for Reduce transparency.
 */
object Palette {
    // The icon's navy backdrop, from its center outwards.
    val Background = Color(0xFF050A1C)
    val BackdropCenter = Color(0xFF0A1433)
    val BackdropEdge = Color(0xFF02040B)

    // Content panes: a faint fill on the backdrop, no blur or lit edge.
    val Surface = Color(0x12FFFFFF) // pills, selected rows, fields, cards
    val Card = Color(0x1FFFFFFF)
    val Hairline = Color(0x1AFFFFFF)
    val Code = Color(0x42020412) // code blocks: darker than prose
    val UserBubble = Color(0x3D7A6CFF)

    // Chrome glass: tint over a blur of the chat. ChromeSolid replaces it with Reduce transparency.
    val Chrome = Color(0x1CFFFFFF)
    val Composer = Color(0x21FFFFFF)
    val ChromeSolid = Color(0xFF1C2349)
    // The phone's full-screen sidebar: a darker frost so its list reads over the blurred chat.
    val SidebarGlass = Color(0xA60B1030)

    // Window glass: popups, dialogs and sheets live in their own window, where the app's blur can't
    // reach. The system blurs behind dialogs and sheets; menus and phones without that blur get the
    // solid versions.
    val Menu = Color(0xF0151A3A)
    val MenuSolid = Color(0xFF151A3A)
    val Sheet = Color(0xE6111633)
    val SheetSolid = Color(0xFF111633)
    // The assistant overlay floats over other apps, so its glass is darker still.
    val OverlayGlass = Color(0xC70B1030)
    val OverlaySolid = Color(0xFA0B1030)

    val Outline = Color(0x24FFFFFF)
    val TextPrimary = Color(0xFFF3F4FF)
    val TextSecondary = Color(0xFFB5B9D8)
    // Lightened from #8288AD, which measured 4.9:1 over the floor glow; this keeps small print above 5.5:1.
    val TextTertiary = Color(0xFF8E94BA)
    val Icon = Color(0xFFE6E8FA)
    val Send = Color(0xFF6A62F2)
    val Live = Color(0x477A5CFF)
    val Button = Color(0x597A6CFF)
    val ButtonText = Color(0xFFF1F0FF)
    val Disabled = Color(0x0FFFFFFF)
    val Link = Color(0xFFB9C3FF)
    val InlineCode = Color(0x1FFFFFFF)
    val Danger = Color(0xFFFFB4B0)
    val DangerPane = Color(0x2EFF5A5A)
    val WarningPane = Color(0x1FF5A623)
    val Success = Color(0xFF8FE0AE)

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

val GoogleSans = FontFamily(
    Font(R.font.google_sans_flex_400, FontWeight.Normal),
    Font(R.font.google_sans_flex_500, FontWeight.Medium),
    Font(R.font.google_sans_flex_600, FontWeight.SemiBold),
)

val GoogleSansCode = FontFamily(
    Font(R.font.google_sans_code_400, FontWeight.Normal),
    Font(R.font.google_sans_code_500, FontWeight.Medium),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) = TextStyle(
    fontFamily = GoogleSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val AightTypography = Typography(
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

private val AightColors = darkColorScheme(
    primary = Palette.Link,
    onPrimary = Color(0xFF14195C),
    primaryContainer = Palette.Send,
    onPrimaryContainer = Color.White,
    secondary = Palette.TextSecondary,
    onSecondary = Color.Black,
    secondaryContainer = Palette.Card,
    onSecondaryContainer = Palette.TextPrimary,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Background,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.BackdropCenter,
    surfaceContainer = Palette.Sheet,
    surfaceContainerHigh = Palette.Menu,
    surfaceContainerHighest = Palette.Menu,
    inverseSurface = Palette.TextPrimary,
    inverseOnSurface = Palette.Background,
    outline = Palette.Outline,
    outlineVariant = Color(0x14FFFFFF),
    error = Palette.Danger,
    onError = Color(0xFF601410),
    scrim = Palette.BackdropEdge,
)

/**
 * True when glass should turn into solid panels: the user asked for less transparency, or the phone's
 * color contrast is raised. Like iOS's Reduce Transparency, this trades the look for legibility.
 */
val LocalReduceTransparency = staticCompositionLocalOf { false }

@Composable
fun AightTheme(reduceTransparency: Boolean = false, content: @Composable () -> Unit) {
    val reduced = reduceTransparency || rememberSystemHighContrast()
    CompositionLocalProvider(LocalReduceTransparency provides reduced) {
        MaterialTheme(colorScheme = AightColors, typography = AightTypography, content = content)
    }
}

/** Android 14's Color contrast setting (Accessibility > Color and motion) at medium or high. */
@Composable
private fun rememberSystemHighContrast(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val context = LocalContext.current
    val uiMode = remember(context) { context.getSystemService(UiModeManager::class.java) } ?: return false
    var contrast by remember(uiMode) { mutableFloatStateOf(uiMode.contrast) }
    DisposableEffect(uiMode) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        uiMode.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { uiMode.removeContrastChangeListener(listener) }
    }
    return contrast >= 0.5f
}
