package nl.bartvandermeeren.aight.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import nl.bartvandermeeren.aight.ui.theme.GoogleSans
import nl.bartvandermeeren.aight.ui.theme.Palette

// The mark in the 400-unit coordinates of assets/aight-icon.svg. It fills a 160-unit square.
private const val MARK_LEFT = 121.5f
private const val MARK_TOP = 120f
private const val MARK_SIZE = 160f
private val OrbCenter = Offset(200.125f, 200f)
private const val ORB_RADIUS = 78.375f
private val MoonCenter = Offset(235.875f, 233f)
private const val MOON_RADIUS = 45.375f

private val OrbClip = Path().apply { addOval(Rect(OrbCenter, ORB_RADIUS)) }
private val OrbBrush = Brush.linearGradient(
    0f to Color(0xFFB867F7), 0.45f to Color(0xFF7061F1), 1f to Color(0xFF4A63EE),
    start = Offset(239.313f, 121.625f), end = Offset(160.938f, 278.375f),
)
private val ShadeBrush = Brush.linearGradient(
    listOf(Color(0xFF4658E6), Color(0xFF2F40C9)),
    start = Offset(95.625f, 205.5f), end = Offset(191.325f, 365f),
)

// The SVG blurs a violet disc behind the orb and a blue one behind the moon; these gradients follow that falloff.
private const val HALO_RADIUS = 136.5f
private val HaloBrush = Brush.radialGradient(
    0.34f to Color(0x707A5CFF), 0.47f to Color(0x617A5CFF), 0.6f to Color(0x397A5CFF),
    0.74f to Color(0x127A5CFF), 0.87f to Color(0x037A5CFF), 1f to Color.Transparent,
    center = OrbCenter, radius = HALO_RADIUS,
)
private const val MOON_SHADOW_RADIUS = 57.125f
private val moonShadowStops = arrayOf(
    0.74f to Color(0x963A5BE8), 0.79f to Color(0x813A5BE8), 0.84f to Color(0x4D3A5BE8),
    0.9f to Color(0x183A5BE8), 0.95f to Color(0x033A5BE8), 1f to Color.Transparent,
)
private val moonStops = arrayOf(0f to Color(0xFFA9C2FF), 0.6f to Color(0xFF7F9FF8), 1f to Color(0xFF5F84F0))
private val MoonHighlight = Offset(-13.613f, -18.15f)

/** The aight mark: a violet orb with a small moon. While the agent works the moon circles the orb and the mark breathes. */
@Composable
fun AightMark(size: Dp, modifier: Modifier = Modifier, working: Boolean = false, halo: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "mark")
    val orbit by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "orbit",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val amount by animateFloatAsState(if (working) 1f else 0f, tween(400), label = "working")
    Canvas(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + (pulse - 1f) * amount
                scaleX = s
                scaleY = s
            },
    ) {
        val scale = this.size.minDimension / MARK_SIZE
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(-MARK_LEFT, -MARK_TOP)
        }) {
            drawMark(orbitDegrees = orbit * amount, halo = halo)
        }
    }
}

private fun DrawScope.drawMark(orbitDegrees: Float, halo: Boolean) {
    if (halo) drawCircle(HaloBrush, HALO_RADIUS, OrbCenter)
    drawCircle(OrbBrush, ORB_RADIUS, OrbCenter)
    clipPath(OrbClip) { drawCircle(ShadeBrush, 79.75f, Offset(175.375f, 285.25f)) }
    val moon = MoonCenter.rotatedAround(OrbCenter, orbitDegrees)
    drawCircle(Brush.radialGradient(*moonShadowStops, center = moon, radius = MOON_SHADOW_RADIUS), MOON_SHADOW_RADIUS, moon)
    drawCircle(Brush.radialGradient(*moonStops, center = moon + MoonHighlight, radius = 68.0625f), MOON_RADIUS, moon)
}

private fun Offset.rotatedAround(pivot: Offset, degrees: Float): Offset {
    if (degrees == 0f) return this
    val radians = Math.toRadians(degrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val d = this - pivot
    return Offset(pivot.x + d.x * c - d.y * s, pivot.y + d.x * s + d.y * c)
}

/** User avatar: initial on a dark disc inside a multicolor ring, like the Google account ring. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, ring: Boolean = true) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (ring) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * 0.07f
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC04), Color(0xFF34A853), Color(0xFF4285F4)),
                        center,
                    ),
                    radius = this.size.minDimension / 2f - stroke / 2f,
                    style = Stroke(stroke),
                )
            }
        }
        Box(
            Modifier
                .padding(if (ring) size * 0.12f else 0.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF1B2233)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                color = Color.White,
                fontFamily = GoogleSans,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.36f).sp,
            )
        }
    }
}

/** The navy glow that rises from the bottom of an empty chat. */
@Composable
fun BottomGlow(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(700), label = "glow")
    if (alpha == 0f) return
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.58f to Color.Transparent,
                0.72f to Color(0xFF020307),
                0.84f to Palette.GlowMid,
                1.0f to Palette.GlowBottom,
            ),
            alpha = alpha,
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.GlowBottom.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.85f, size.height * 1.02f),
                radius = size.width * 0.8f,
            ),
            alpha = alpha,
        )
    }
}
