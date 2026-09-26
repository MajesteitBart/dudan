package nl.bartvandermeeren.aight.ui.components

import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import java.util.function.Consumer
import nl.bartvandermeeren.aight.ui.theme.LocalReduceTransparency
import nl.bartvandermeeren.aight.ui.theme.Palette

/** The state behind the current screen's glass, so floating chrome can frost what scrolls under it. Null where nothing is blurred. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

object GlassDefaults {
    /** Light catches the top-left edge and fades out along the sides, with a faint rim at the bottom right. */
    val BorderBrush = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.34f),
        0.35f to Color.White.copy(alpha = 0.09f),
        0.7f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.15f),
    )
    val Border = BorderStroke(1.dp, BorderBrush)
    /** Solid panels get an even, brighter edge instead, so their outline still reads without the glass. */
    val SolidBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
    val Sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.5f to Color.Transparent)

    // NN/g: more blur is better over busy backgrounds, and scrolling text is one. Behind chrome the
    // chat should read as light and shape, not as words.
    val ChromeBlur = 32.dp
    val SheetBlur = 48.dp
    val WindowBlur = 32.dp

    /**
     * The orb's violet-to-blue for the one primary action on a screen (send, connect). Deeper than the
     * orb itself so white text on it stays above 4.5:1; the orb's #B867F7 measured 3.4:1.
     */
    val Orb = Brush.linearGradient(listOf(Color(0xFF7450E0), Color(0xFF5A55E0), Color(0xFF3F55D6)))
}

/**
 * A quiet content surface: a flat fill, optionally with a hairline. Content sits on the backdrop with
 * nothing moving behind it, so it gets no blur, sheen or lit edge; those mark what floats.
 */
fun Modifier.pane(shape: Shape, color: Color = Palette.Surface, outline: Color? = null): Modifier {
    val filled = clip(shape).background(color)
    return if (outline != null) filled.border(1.dp, outline, shape) else filled
}

/**
 * A pane of glass for chrome that floats over the chat: [tint] over a blur of the screen's backdrop
 * (see [LocalHazeState]), with a sheen and a lit edge. A navy wash under the tint dims bright content
 * passing behind, so text on the glass keeps its contrast. With Reduce transparency the pane turns
 * solid [Palette.ChromeSolid] (or [solid]) with an even edge.
 */
fun Modifier.glass(
    shape: Shape,
    tint: Color = Palette.Chrome,
    blur: Boolean = true,
    blurRadius: Dp = GlassDefaults.ChromeBlur,
    border: Boolean = true,
    solid: Color = Palette.ChromeSolid,
): Modifier = composed {
    val clipped = clip(shape)
    if (LocalReduceTransparency.current) {
        val filled = clipped.background(solid)
        return@composed if (border) filled.border(GlassDefaults.SolidBorder, shape) else filled
    }
    val hazeState = LocalHazeState.current.takeIf { blur }
    val filled = if (hazeState != null) {
        clipped.hazeEffect(
            hazeState,
            HazeStyle(
                backgroundColor = Palette.Background,
                tints = listOf(HazeTint(Palette.Background.copy(alpha = 0.3f)), HazeTint(tint)),
                blurRadius = blurRadius,
                noiseFactor = 0.06f,
                // Where blur is off (battery saver on some phones), the solid color keeps text readable.
                fallbackTint = HazeTint(solid),
            ),
        )
    } else {
        clipped.background(tint)
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

/**
 * The navy of the aight icon lit by soft violet and blue light, for the glass to frost. [glow] raises
 * the floor light under the composer, as on the empty chat. It stays still and simple: the chat's
 * text sits on it directly.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, glow: Boolean = false) {
    val strength by animateFloatAsState(if (glow) 1f else 0f, tween(700), label = "backdrop-glow")
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val unit = size.minDimension
        drawRect(
            Brush.radialGradient(
                0f to Palette.BackdropCenter, 0.55f to Palette.Background, 1f to Palette.BackdropEdge,
                center = Offset(w * 0.5f, h * 0.4f),
                radius = size.maxDimension * 0.85f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.GlowViolet.copy(alpha = 0.34f), Palette.GlowViolet.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(w * 0.06f, h * 0.08f),
                radius = unit * 0.95f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.GlowBlue.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * 1.02f, h * 0.5f),
                radius = unit * 0.8f,
            ),
        )
        // The icon's floor glow: a wide, flat ellipse of blue light along the bottom edge.
        val floor = Offset(w * 0.55f, h)
        scale(scaleX = 1f, scaleY = 0.42f, pivot = floor) {
            drawCircle(
                Brush.radialGradient(
                    listOf(
                        Palette.GlowBlue.copy(alpha = 0.3f + 0.25f * strength),
                        Palette.GlowViolet.copy(alpha = 0.1f + 0.1f * strength),
                        Color.Transparent,
                    ),
                    center = floor,
                    radius = w * (0.6f + 0.15f * strength),
                ),
                radius = w * (0.6f + 0.15f * strength),
                center = floor,
            )
        }
    }
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

/** An [AlertDialog] as a pane of dark glass over the blurred app. */
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
 * A [DropdownMenu] in dark glass with a lit edge. Menus are popups, which can't blur what's behind
 * them, so their glass is nearly opaque; with Reduce transparency it is.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReduceTransparency.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        containerColor = if (reduced) Palette.MenuSolid else Palette.Menu,
        border = if (reduced) GlassDefaults.SolidBorder else GlassDefaults.Border,
        content = content,
    )
}
