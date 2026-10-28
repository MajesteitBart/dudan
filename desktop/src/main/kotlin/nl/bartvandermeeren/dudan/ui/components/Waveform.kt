package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max

/** Scrolling voice level bars: dots when quiet, taller strokes when speaking. */
@Composable
fun VoiceWaveform(level: Float, modifier: Modifier = Modifier, bars: Int = 44, color: Color = Color.White) {
    val currentLevel = rememberUpdatedState(level)
    val history = remember { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    LaunchedEffect(Unit) {
        var smoothed = 0f
        while (true) {
            delay(55)
            smoothed = smoothed * 0.45f + currentLevel.value * 0.55f
            history.removeAt(0)
            history.add(smoothed)
        }
    }
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val count = history.size
        val gap = size.width / count
        val stroke = 2.2.dp.toPx()
        val maxHeight = size.height * 0.95f
        val mid = size.height / 2f
        history.forEachIndexed { index, value ->
            val x = gap * index + gap / 2f
            // Taper the edges so the waveform fades in and out like Gemini's.
            val edge = 1f - kotlin.math.abs(index - count / 2f) / (count / 2f)
            val height = max(0f, value * maxHeight * (0.35f + 0.65f * edge))
            drawLine(
                color = color.copy(alpha = 0.55f + 0.45f * value),
                start = Offset(x, mid - height / 2f),
                end = Offset(x, mid + height / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
