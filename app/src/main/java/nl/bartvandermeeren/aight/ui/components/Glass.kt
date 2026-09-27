package nl.bartvandermeeren.aight.ui.components

import android.graphics.Bitmap
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import java.util.function.Consumer
import kotlin.random.Random
import nl.bartvandermeeren.aight.ui.theme.Accent
import nl.bartvandermeeren.aight.ui.theme.LocalAccent
import nl.bartvandermeeren.aight.ui.theme.LocalReduceTransparency
import nl.bartvandermeeren.aight.ui.theme.LocalSky
import nl.bartvandermeeren.aight.ui.theme.Palette
import nl.bartvandermeeren.aight.ui.theme.Sky

/** The state behind the current screen's glass, so glass cards can frost the sky under them. Null where nothing is blurred. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

object GlassDefaults {
    /** Superhuman's panel edge: an even line of light, a little brighter along the top. */
    val BorderBrush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.3f),
        0.45f to Color.White.copy(alpha = 0.16f),
        1f to Color.White.copy(alpha = 0.12f),
    )
    val Border = BorderStroke(1.dp, BorderBrush)
    /** Solid panels get an even, brighter edge instead, so their outline still reads without the glass. */
    val SolidBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
    val Sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.06f), 0.4f to Color.Transparent)

    // NN/g: more blur is better over busy backgrounds. The sky should read as light, not as shapes.
    val CardBlur = 40.dp
    val SheetBlur = 48.dp
    val WindowBlur = 32.dp
    val CardShape = RoundedCornerShape(28.dp)
}

/** A quiet surface inside a card: a flat fill, optionally with a hairline. */
fun Modifier.pane(shape: Shape, color: Color = Palette.Surface, outline: Color? = null): Modifier {
    val filled = clip(shape).background(color)
    return if (outline != null) filled.border(1.dp, outline, shape) else filled
}

/** An outlined chip or field, like Superhuman's time chips: a thin light border around a clear [fill]. */
fun Modifier.outlined(shape: Shape, fill: Color = Color.Transparent, outline: Color = Palette.Outline): Modifier =
    clip(shape).background(fill).border(1.dp, outline, shape)

/**
 * A glass card: the sky behind it blurred (see [LocalHazeState]), washed toward the sky's dark by [wash]
 * so white text keeps its contrast, then [tint], a faint sheen and a light edge. Without a haze state
 * (the assistant overlay) the colors are painted flat. With Reduce transparency the card is [solid].
 * [solid] and [wash] default to the current sky's.
 */
fun Modifier.glass(
    shape: Shape,
    tint: Color = Palette.Chrome,
    blur: Boolean = true,
    blurRadius: Dp = GlassDefaults.CardBlur,
    border: Boolean = true,
    solid: Color = Color.Unspecified,
    wash: Color = Color.Unspecified,
): Modifier = composed {
    val solidFill = solid.takeOrElse { Palette.ChromeSolid }
    val washFill = wash.takeOrElse { Palette.GlassWash }
    val clipped = clip(shape)
    if (LocalReduceTransparency.current) {
        val filled = clipped.background(solidFill)
        return@composed if (border) filled.border(GlassDefaults.SolidBorder, shape) else filled
    }
    val hazeState = LocalHazeState.current.takeIf { blur }
    val filled = if (hazeState != null) {
        clipped.hazeEffect(
            hazeState,
            HazeStyle(
                backgroundColor = Palette.Background,
                tints = listOf(HazeTint(washFill), HazeTint(tint)),
                blurRadius = blurRadius,
                noiseFactor = 0.08f,
                // Where blur is off (battery saver on some phones), the solid color keeps text readable.
                fallbackTint = HazeTint(solidFill),
            ),
        )
    } else {
        clipped.background(washFill).background(tint)
    }
    val lit = filled.background(GlassDefaults.Sheen)
    if (border) lit.border(GlassDefaults.Border, shape) else lit
}

/**
 * The container color for a surface in its own window (dialog, sheet, overlay panel). Translucent
 * [glass] only while the system blurs what's behind the window; over a sharp background, or with
 * Reduce transparency, the [solid] version keeps text off whatever app or chat shows through.
 */
@Composable
fun windowGlass(glass: Color, solid: Color): Color =
    if (LocalReduceTransparency.current || !rememberCrossWindowBlurEnabled()) solid else glass

/** Whether the system currently blurs behind windows; battery saver and a developer option can turn it off. */
@Composable
fun rememberCrossWindowBlurEnabled(): Boolean {
    val context = LocalContext.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) } ?: return false
    var enabled by remember(windowManager) { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

// Soft streaks of cloud across the sky: center (as a fraction of width and height), width as a
// fraction of the shorter side, how flat, how bright and how tilted.
private class Cloud(val x: Float, val y: Float, val width: Float, val flat: Float, val alpha: Float, val tilt: Float)

private val clouds = listOf(
    Cloud(0.18f, 0.24f, 0.9f, 0.16f, 0.08f, -7f),
    Cloud(0.82f, 0.4f, 0.8f, 0.14f, 0.05f, -5f),
    Cloud(0.3f, 0.58f, 1.1f, 0.12f, 0.05f, -9f),
    Cloud(0.7f, 0.74f, 0.9f, 0.18f, 0.08f, -6f),
    Cloud(0.12f, 0.86f, 0.8f, 0.22f, 0.1f, -10f),
)

/** A tile of film grain: breaks up banding in the gradients and gives the sky a photographic surface. */
@Composable
private fun rememberGrain(): ImageBitmap = remember {
    val size = 128
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val v = random.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * A sky for the glass to frost, after the twilight photo behind Superhuman's panels. On Dusk: deep blue
 * at the top (where the status bar and titles sit), periwinkle in the middle, a lavender horizon with
 * light in the accent's hue low on the left, soft streaks of cloud and a little grain. The other skies
 * (see [Sky]) follow the same stops. [glow] lifts the horizon, as on the empty chat. [dim] lets the sky
 * fall toward night behind an open conversation: replies sit on the sky itself, and white text on the
 * bare horizon would drop under 4.5:1.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, glow: Boolean = false, dim: Boolean = false) {
    val strength by animateFloatAsState(if (glow) 1f else 0f, tween(900), label = "backdrop-glow")
    val night by animateFloatAsState(if (dim) 1f else 0f, tween(700), label = "backdrop-dim")
    val sky = LocalSky.current
    val accent = LocalAccent.current
    // Moon is white, so the light low on the left comes from the sky itself.
    val light = if (accent == Accent.Moon) sky.glow else accent.glow
    val grain = rememberGrain()
    val grainBrush = remember(grain) { ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier.fillMaxSize()) {
        drawSky(sky, strength, light)
        if (night > 0f) {
            // Deeper where the sky is brightest, so text keeps the same contrast from top to bottom.
            drawRect(
                Brush.verticalGradient(
                    0f to sky.deep.copy(alpha = 0.3f * night),
                    0.5f to sky.deep.copy(alpha = 0.5f * night),
                    1f to sky.deep.copy(alpha = 0.66f * night),
                ),
            )
        }
        drawRect(grainBrush, alpha = 0.045f)
    }
}

private fun DrawScope.drawSky(sky: Sky, strength: Float, light: Color) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension
    drawRect(
        Brush.verticalGradient(
            0f to sky.top,
            0.28f to sky.high,
            0.62f to sky.mid,
            0.9f to sky.horizon,
            1f to sky.horizon,
        ),
    )
    // Cool light high on the right, the accent's light low on the left.
    drawRect(Brush.radialGradient(listOf(sky.light.copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.95f, h * 0.32f), radius = unit * 0.85f))
    drawRect(
        Brush.radialGradient(
            listOf(light.copy(alpha = 0.36f + 0.16f * strength), light.copy(alpha = 0.1f), Color.Transparent),
            center = Offset(w * 0.08f, h * 0.94f),
            radius = size.maxDimension * (0.55f + 0.1f * strength),
        ),
    )
    clouds.forEach { cloud ->
        val center = Offset(w * cloud.x, h * cloud.y)
        val radius = unit * cloud.width / 2f
        rotate(cloud.tilt, pivot = center) {
            scale(scaleX = 1f, scaleY = cloud.flat, pivot = center) {
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = cloud.alpha), Color.Transparent), center = center, radius = radius), radius, center)
            }
        }
    }
    // Night settling into the lower right corner, and the top kept deep for the status bar.
    drawRect(Brush.radialGradient(listOf(sky.deep.copy(alpha = 0.75f), Color.Transparent), center = Offset(w * 1.05f, h * 1.05f), radius = unit * 0.95f))
    drawRect(Brush.verticalGradient(0f to sky.top.copy(alpha = 0.6f), 0.12f to Color.Transparent))
}

/**
 * Blurs everything behind the dialog or sheet window this is composed in. The app's own blur stops at
 * its window, so these get their frost from the system compositor. Off with Reduce transparency.
 */
@Composable
fun BlurBehindWindow(radius: Dp = GlassDefaults.WindowBlur) {
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    val enabled = !LocalReduceTransparency.current
    DisposableEffect(view, px, enabled) {
        val window = generateSequence<View>(view) { it.parent as? View }
            .firstNotNullOfOrNull { (it as? DialogWindowProvider)?.window }
        if (window != null) {
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = if (enabled) px else 0 }
        }
        onDispose { }
    }
}

/** An [AlertDialog] as a pane of glass over the blurred app. */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val reduced = LocalReduceTransparency.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            BlurBehindWindow()
            title()
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = shape,
        containerColor = windowGlass(Palette.Sheet, Palette.SheetSolid),
        modifier = Modifier.border(if (reduced) GlassDefaults.SolidBorder else GlassDefaults.Border, shape),
    )
}

/**
 * A menu in dark glass with a light edge. Menus are popups, which can't blur what's behind them, so
 * their glass is nearly opaque; with Reduce transparency it is.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReduceTransparency.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        containerColor = if (reduced) Palette.MenuSolid else Palette.Menu,
        border = if (reduced) GlassDefaults.SolidBorder else GlassDefaults.Border,
        content = content,
    )
}

/** A menu row in beautifului.dev's sources menu: a line icon, the name, and what it does in grey beside it. */
@Composable
fun GlassMenuItem(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Palette.TextPrimary, maxLines = 1)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Palette.Icon, modifier = Modifier.size(22.dp)) },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        modifier = Modifier.widthIn(min = 320.dp),
    )
}

/** beautifului.dev's segmented switch: a dark track with the chosen option lifted onto a lighter pill. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(modifier.pane(CircleShape, Palette.Code, outline = Palette.Hairline).padding(4.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .then(if (active) Modifier.background(accent.color.copy(alpha = if (accent == Accent.Moon) 0.1f else 0.22f)).border(1.dp, accent.color.copy(alpha = 0.4f), CircleShape) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Palette.TextPrimary else Palette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A setting that is on or off: the name, what it does in grey underneath, and a switch in the accent color. */
@Composable
fun Toggle(title: String, detail: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = LocalAccent.current.color,
                checkedThumbColor = LocalAccent.current.on,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Palette.Surface,
                uncheckedThumbColor = Palette.TextSecondary,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

/**
 * Superhuman's primary button: a dark body in the sky's hue with the label, and a gradient tile
 * holding an arrow.
 */
@Composable
fun CtaButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: @Composable (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    val accent = LocalAccent.current
    val sky = LocalSky.current
    // Moon takes the sky's tile (on Dusk, Superhuman's violet to rose); a colored accent fills the tile itself.
    val tile = if (accent == Accent.Moon) Brush.linearGradient(sky.tile) else Brush.linearGradient(listOf(accent.color, accent.soft))
    val arrow = if (accent == Accent.Moon) Color.White else accent.on
    Row(
        modifier
            .clip(shape)
            .background(if (enabled) sky.ctaBody else Palette.Disabled)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.16f else 0.1f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        busy?.invoke()
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Palette.TextTertiary)
        Box(
            Modifier
                .size(width = 48.dp, height = 40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (enabled) tile else Brush.linearGradient(listOf(Palette.Disabled, Palette.Disabled))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = if (enabled) arrow else Palette.TextTertiary, modifier = Modifier.size(22.dp))
        }
    }
}
