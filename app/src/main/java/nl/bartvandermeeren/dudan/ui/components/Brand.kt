package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.ui.theme.GoogleSans
import nl.bartvandermeeren.dudan.ui.theme.Palette

/** The supplied dudan mark. It pulses while the agent works and stays still at rest. */
@Composable
fun DudanMark(size: Dp, modifier: Modifier = Modifier, working: Boolean = false, halo: Boolean = false) {
    val amount by animateFloatAsState(if (working) 1f else 0f, tween(400), label = "working")
    if (working || amount > 0f) {
        val transition = rememberInfiniteTransition(label = "mark")
        val pulse by transition.animateFloat(
            initialValue = 0.92f, targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
        )
        MarkImage(size, modifier, halo, scale = { 1f + (pulse - 1f) * amount })
    } else {
        MarkImage(size, modifier, halo, scale = { 1f })
    }
}

@Composable
private fun MarkImage(size: Dp, modifier: Modifier, halo: Boolean, scale: () -> Float) {
    Image(
        painter = painterResource(if (halo) R.drawable.dudan_mark_halo else R.drawable.dudan_mark),
        contentDescription = null,
        modifier = modifier.size(size).graphicsLayer {
            val s = scale()
            scaleX = s
            scaleY = s
        },
    )
}

private val RingColors = listOf(Palette.OrbViolet, Palette.OrbIndigo, Palette.GlowBlue, Palette.SparkRose, Palette.OrbViolet)
private val AvatarGlass = Brush.linearGradient(listOf(Color(0x807A6CFF), Color(0x402A5BFF)))

/** User avatar: initial on a violet glass disc, optionally inside a ring in the orb's colors. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, ring: Boolean = true) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (ring) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * 0.07f
                drawCircle(
                    brush = Brush.sweepGradient(RingColors, center),
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
                .background(AvatarGlass)
                .background(GlassDefaults.Sheen)
                .border(GlassDefaults.Border, CircleShape),
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
