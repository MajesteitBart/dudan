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
 * Dusk glass, after Superhuman's frosted panels over a twilight sky and beautifului.dev's quiet
 * components. The sky (GlassBackdrop) runs from deep blue at the top to a lavender horizon. Glass cards
 * frost it into slate-lavender that keeps white text above 7:1; Superhuman's own panels over bright sky
 * measure 3.3 to 4.3:1, too low for reading replies all day. Inside a card, elements are outlined or
 * flat. Every translucent color has a solid counterpart for Reduce transparency.
 */
object Palette {
    // The dusk sky, top to horizon, and the dark it fades to at the corners.
    val SkyTop = Color(0xFF232C62)
    val SkyHigh = Color(0xFF394891)
    val SkyMid = Color(0xFF5061A6)
    val SkyHorizon = Color(0xFF8676BA)
    val SkyGlow = Color(0xFFC08AB4) // mauve light low in the sky, like the clouds in Superhuman's photo
    val SkyDeep = Color(0xFF17173A)
    val Background = Color(0xFF1E2350) // the sky's average; what glass and fallbacks blend toward
    val BackdropCenter = SkyMid
    val BackdropEdge = SkyDeep

    // Glass cards: a violet-navy wash and a little white over the blurred sky, under a light edge.
    val GlassWash = Color(0xB51B1942)
    val Chrome = Color(0x0FFFFFFF)
    val Composer = Color(0x0FFFFFFF)
    val ChromeSolid = Color(0xFF383C68)
    // The phone's full-screen sidebar: darker, so its list reads over the blurred chat.
    val SidebarGlass = Color(0x731B1942)

    // Inside a card: outlined chips and fields, flat bubbles and code.
    val Surface = Color(0x0FFFFFFF) // selected rows, fields, quiet fills
    val Card = Color(0x1AFFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val Outline = Color(0x38FFFFFF) // chip and field borders
    val Code = Color(0x38000010) // code blocks: darker than the card
    val UserBubble = Color(0x24FFFFFF)

    // Window glass: popups, dialogs and sheets live in their own window, where the app's blur can't
    // reach. The system blurs behind dialogs and sheets; menus and phones without that blur get the
    // solid versions.
    val Menu = Color(0xF52A2C52)
    val MenuSolid = Color(0xFF2A2C52)
    val Sheet = Color(0xEB262950)
    val SheetSolid = Color(0xFF262950)
    // The assistant overlay floats over other apps, so its glass is darker still.
    val OverlayGlass = Color(0xD91F2148)
    val OverlaySolid = Color(0xFA1F2148)

    val TextPrimary = Color(0xFFF7F7FC)
    val TextSecondary = Color(0xFFCBCDE2)
    val TextTertiary = Color(0xFFBCBFDA)
    val Icon = Color(0xFFEDEEF8)
    // White is the primary action, as in beautifului.dev's send button; dark ink goes on it.
    val Primary = Color(0xFFF4F4F8)
    val OnPrimary = Color(0xFF1E1C3A)
    val Send = Color(0xFF6A62F2)
    val Live = Color(0x1FFFFFFF)
    val Button = Color(0x2EFFFFFF)
    val ButtonText = Color(0xFFF7F7FC)
    val Disabled = Color(0x14FFFFFF)
    val Link = Color(0xFFD3D9FF)
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
