package nl.bartvandermeeren.aight.ui.components

import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
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
    val Sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.5f to Color.Transparent)
    // Soft enough that text behind small controls turns into shapes of light rather than vanishing.
    val BlurRadius = 18.dp
    /** The orb's violet-to-blue, for the one primary action on a screen (send, connect). */
    val Orb = Brush.linearGradient(listOf(Palette.OrbViolet, Palette.OrbIndigo, Palette.OrbBlue))
}

/**
 * A glass pane: [tint] over a blur of whatever [hazeState] marks as backdrop, with a sheen and a lit edge.
 * Without a state (inside scrolling content, over other apps) the tint alone fills the pane.
 */
fun Modifier.glass(
    shape: Shape,
    tint: Color = Palette.Surface,
    hazeState: HazeState? = null,
    blurRadius: Dp = GlassDefaults.BlurRadius,
    border: Boolean = true,
): Modifier {
    val clipped = clip(shape)
    val filled = if (hazeState != null) {
        clipped.hazeEffect(
            hazeState,
            HazeStyle(
                backgroundColor = Palette.Background,
                tints = listOf(HazeTint(tint)),
                blurRadius = blurRadius,
                noiseFactor = 0.06f,
                // Where blur is off (battery saver on some phones), a denser tint keeps text readable.
                fallbackTint = HazeTint(tint.compositeOver(Palette.Background).copy(alpha = 0.94f)),
            ),
        )
    } else {
        clipped.background(tint)
    }
    val lit = filled.background(GlassDefaults.Sheen)
    return if (border) lit.border(GlassDefaults.Border, shape) else lit
}

/** A [glass] container that frosts the screen's backdrop when [blur] is on. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Palette.Surface,
    blur: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, tint, if (blur) LocalHazeState.current else null), content = content)
}

/**
 * The navy of the aight icon lit by soft violet and blue light, for the glass to frost. [glow] raises
 * the floor light under the composer, as on the empty chat.
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
                listOf(Palette.GlowViolet.copy(alpha = 0.36f), Palette.GlowViolet.copy(alpha = 0.1f), Color.Transparent),
                center = Offset(w * 0.06f, h * 0.08f),
                radius = unit * 0.95f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.GlowBlue.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(w * 1.02f, h * 0.5f),
                radius = unit * 0.8f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.OrbViolet.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(0f, h * 0.86f),
                radius = unit * 0.7f,
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
 * its window, so popups get their frost from the system compositor. Phones without cross-window blur
 * ignore the flag, and the dense [Palette.Sheet] tint carries the dialog on its own.
 */
@Composable
fun BlurBehindWindow(radius: Dp = 24.dp) {
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, px) {
        val window = generateSequence<View>(view) { it.parent as? View }
            .firstNotNullOfOrNull { (it as? DialogWindowProvider)?.window }
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = px }
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
        containerColor = Palette.Sheet,
        modifier = Modifier.border(GlassDefaults.Border, shape),
    )
}

/** A [DropdownMenu] in dark glass with a lit edge. */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        containerColor = Palette.Menu,
        border = GlassDefaults.Border,
        content = content,
    )
}
