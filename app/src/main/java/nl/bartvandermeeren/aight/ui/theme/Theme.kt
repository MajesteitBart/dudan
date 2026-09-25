package nl.bartvandermeeren.aight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import nl.bartvandermeeren.aight.R

/** Colors sampled from Gemini's dark Android UI. */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414) // pills, bubbles, code blocks
    val Composer = Color(0xFF1E1E1E)
    val Card = Color(0xFF292A2C)
    val Menu = Color(0xFF232426)
    val Outline = Color(0xFF3C4043)
    val TextPrimary = Color(0xFFE3E3E3)
    val TextSecondary = Color(0xFFA8ABAF)
    val TextTertiary = Color(0xFF7C8085)
    val Icon = Color(0xFFE0E0E0)
    val Send = Color(0xFF233C90)
    val Live = Color(0xFF192967)
    val Button = Color(0xFF1F3B9C)
    val ButtonText = Color(0xFFD3E3FD)
    val Disabled = Color(0xFF343434)
    val Link = Color(0xFFA8C7FA)
    val InlineCode = Color(0xFF232323)
    val GlowMid = Color(0xFF090C1D)
    val GlowBottom = Color(0xFF131E4B)
    val Danger = Color(0xFFF2B8B5)
    val Success = Color(0xFF81C995)

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
    onPrimary = Color(0xFF062E6F),
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
    surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Menu,
    surfaceContainerHighest = Palette.Card,
    inverseSurface = Palette.TextPrimary,
    inverseOnSurface = Color.Black,
    outline = Palette.Outline,
    outlineVariant = Color(0xFF2A2B2D),
    error = Palette.Danger,
    onError = Color(0xFF601410),
    scrim = Color.Black,
)

@Composable
fun AightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AightColors, typography = AightTypography, content = content)
}
