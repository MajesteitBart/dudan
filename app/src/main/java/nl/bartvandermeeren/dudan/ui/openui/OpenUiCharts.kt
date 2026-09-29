package nl.bartvandermeeren.dudan.ui.openui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.openui.OpenUiText
import nl.bartvandermeeren.dudan.openui.UiNode
import nl.bartvandermeeren.dudan.openui.formatNumber
import nl.bartvandermeeren.dudan.openui.toNumberOrNull
import nl.bartvandermeeren.dudan.openui.unbind
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette

/**
 * Categorical slots for series, in a fixed order that passes the colorblind-separation and
 * lightness checks on dudan's dark skies (the dataviz reference palette's dark steps). A series keeps
 * its slot; nothing is cycled. Series past the eighth are drawn in grey.
 */
private val SeriesColors = listOf(
    Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
    Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
)

private fun seriesColor(index: Int): Color = SeriesColors.getOrElse(index) { Color(0xFF8A8DA8) }

private class Series(val name: String, val values: List<Double?>)

private fun numbers(value: Any?): List<Double?> = (unbind(value) as? List<*>).orEmpty().map { toNumberOrNull(it) }

/** Charts over categories: BarChart, LineChart, AreaChart and HorizontalBarChart. */
@Composable
internal fun SeriesChart(n: UiNode, modifier: Modifier) {
    val labels = n.list("labels").map { OpenUiText.cellText(it) }
    val rawSeries = n.list("series")
    val series = if (rawSeries.all { unbind(it) is Number }) {
        // A bare list of numbers is one unnamed series.
        listOf(Series("", numbers(rawSeries)))
    } else {
        rawSeries.mapNotNull { unbind(it) as? UiNode }.map { Series(it.string("category").orEmpty(), numbers(it["values"])) }
    }
    if (labels.isEmpty() || series.isEmpty()) return
    val stacked = n.string("variant") == "stacked"
    var selected by remember(labels.size) { mutableStateOf<Int?>(null) }
    var table by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartHeader(
            legend = series.map { it.name }.takeIf { series.size > 1 },
            table = table,
            onToggleTable = { table = !table },
        )
        if (table) {
            DataTable(
                headers = listOf(n.string("xLabel").orEmpty()) + series.mapIndexed { i, s -> s.name.ifBlank { "${i + 1}" } },
                rows = labels.indices.map { i -> listOf<Any?>(labels[i]) + series.map { s -> s.values.getOrNull(i)?.let(::formatValue).orEmpty() } },
                numeric = listOf(false) + series.map { true },
            )
            return@Column
        }
        Readout(labels, series, selected)
        n.string("yLabel")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary) }
        when (n.type) {
            "HorizontalBarChart" -> HorizontalBars(labels, series, stacked, selected) { selected = it }
            else -> CategoryCanvas(
                labels = labels,
                series = series,
                kind = n.type,
                stacked = stacked,
                curve = n.string("variant"),
                heightDp = (n.number("height") ?: 220.0).coerceIn(140.0, 320.0).toInt(),
                selected = selected,
                onSelect = { selected = it },
            )
        }
        n.string("xLabel")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.align(Alignment.CenterHorizontally)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartHeader(legend: List<String>?, table: Boolean, onToggleTable: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (legend != null) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                legend.forEachIndexed { i, name -> LegendKey(seriesColor(i), name.ifBlank { "${i + 1}" }) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            stringResource(if (table) R.string.openui_show_chart else R.string.openui_show_table),
            style = MaterialTheme.typography.labelMedium,
            color = LocalAccent.current.soft,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleTable).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LegendKey(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, maxLines = 1)
    }
}

/** The values at the tapped category, in text colors: the phone's stand-in for a hover tooltip. */
@Composable
private fun Readout(labels: List<String>, series: List<Series>, selected: Int?) {
    val index = selected?.takeIf { it in labels.indices }
    val text = if (index == null) {
        stringResource(R.string.openui_chart_hint)
    } else {
        labels[index] + "  ·  " + series.mapNotNull { s ->
            s.values.getOrNull(index)?.let { v -> (if (s.name.isNotBlank() && series.size > 1) "${s.name} " else "") + formatValue(v) }
        }.joinToString("  ·  ")
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (index == null) Palette.TextTertiary else Palette.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---- Vertical bars, lines and areas -----------------------------------------------------------------

@Composable
private fun CategoryCanvas(
    labels: List<String>,
    series: List<Series>,
    kind: String,
    stacked: Boolean,
    curve: String?,
    heightDp: Int,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = Palette.TextSecondary)
    val bars = kind == "BarChart"
    val count = labels.size
    // Stacked bars reach the sum of their positive parts; everything else its largest value.
    val top = if (bars && stacked) labels.indices.maxOf { i -> series.sumOf { s -> max(0.0, s.values.getOrNull(i) ?: 0.0) } }
    else series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0.0
    val bottom = if (bars && stacked) labels.indices.minOf { i -> series.sumOf { s -> min(0.0, s.values.getOrNull(i) ?: 0.0) } }
    else series.flatMap { it.values }.filterNotNull().minOrNull() ?: 0.0
    // Bars grow from zero; lines may zoom in on their range.
    val ticks = niceTicks(if (bars) min(0.0, bottom) else bottom, if (bars) max(0.0, top) else top)
    val tickTexts = ticks.map { formatCompact(it) }
    val surface = Palette.Background

    fun indexAt(x: Float, left: Float, width: Float): Int = ((x - left) / (width / count)).toInt().coerceIn(0, count - 1)

    // The gesture handlers live across recompositions; they read the current selection through these.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp + 24.dp)
            .pointerInput(count, tickTexts) {
                detectTapGestures { offset ->
                    val left = tickTexts.maxOf { measurer.measure(it, axisStyle).size.width } + 8.dp.toPx()
                    val i = indexAt(offset.x, left, size.width - left)
                    currentOnSelect(if (currentSelected == i) null else i)
                }
            }
            .pointerInput(count, tickTexts) {
                detectHorizontalDragGestures { change, _ ->
                    val left = tickTexts.maxOf { measurer.measure(it, axisStyle).size.width } + 8.dp.toPx()
                    currentOnSelect(indexAt(change.position.x, left, size.width - left))
                }
            },
    ) {
        val labelWidth = tickTexts.maxOf { measurer.measure(it, axisStyle).size.width }.toFloat()
        val left = labelWidth + 8.dp.toPx()
        val plotTop = 6.dp.toPx()
        val plotBottom = size.height - 22.dp.toPx()
        val plotWidth = size.width - left
        val low = ticks.first()
        val high = ticks.last()
        fun y(v: Double): Float = (plotBottom - (v - low) / (high - low) * (plotBottom - plotTop)).toFloat()
        val band = plotWidth / count

        // Recessive grid: a hairline per tick, the zero line one step stronger.
        ticks.forEachIndexed { i, tick ->
            val ty = y(tick)
            drawLine(if (tick == 0.0) Palette.Outline else Palette.Hairline, Offset(left, ty), Offset(size.width, ty), 1.dp.toPx())
            val layout = measurer.measure(tickTexts[i], axisStyle)
            drawText(layout, topLeft = Offset(labelWidth - layout.size.width, ty - layout.size.height / 2f))
        }

        // Category labels, thinned out when they would collide.
        val widest = labels.maxOf { measurer.measure(it.take(14), axisStyle).size.width } + 6.dp.toPx()
        val every = max(1, ceil(widest / band).toInt())
        labels.forEachIndexed { i, label ->
            if (i % every != 0 && i != selected) return@forEachIndexed
            val layout = measurer.measure(label.take(14), axisStyle.copy(color = if (i == selected) Palette.TextPrimary else Palette.TextSecondary))
            val cx = left + band * (i + 0.5f)
            drawText(layout, topLeft = Offset((cx - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width), plotBottom + 6.dp.toPx()))
        }

        if (bars) {
            drawBars(series, count, band, left, stacked, selected, ::y)
        } else {
            if (selected != null) {
                val cx = left + band * (selected + 0.5f)
                drawLine(Palette.Outline, Offset(cx, plotTop), Offset(cx, plotBottom), 1.dp.toPx())
            }
            series.forEachIndexed { s, item ->
                drawLineSeries(item.values, seriesColor(s), band, left, curve, area = kind == "AreaChart", baseline = y(max(low, min(0.0, high))), y = ::y, selected = selected, surface = surface)
            }
        }
    }
}

private fun DrawScope.drawBars(
    series: List<Series>,
    count: Int,
    band: Float,
    left: Float,
    stacked: Boolean,
    selected: Int?,
    y: (Double) -> Float,
) {
    val gap = 2.dp.toPx()
    val radius = CornerRadius(4.dp.toPx())
    val maxBar = 24.dp.toPx()
    val groups = if (stacked) 1 else series.size
    val barWidth = min(maxBar, (band * 0.72f - gap * (groups - 1)) / groups).coerceAtLeast(2.dp.toPx())
    val groupWidth = barWidth * groups + gap * (groups - 1)
    val zero = y(0.0)
    for (i in 0 until count) {
        val alpha = if (selected == null || selected == i) 1f else 0.45f
        val start = left + band * i + (band - groupWidth) / 2f
        if (stacked) {
            var up = 0.0
            var down = 0.0
            val parts = series.mapIndexedNotNull { s, item -> item.values.getOrNull(i)?.let { s to it } }
            val lastUp = parts.lastOrNull { it.second > 0 }?.first
            val lastDown = parts.lastOrNull { it.second < 0 }?.first
            parts.forEach { (s, v) ->
                val from = if (v >= 0) up else down
                val to = from + v
                if (v >= 0) up = to else down = to
                // The surface gap between segments: each segment gives up 2 dp at its outer end.
                val outer = (s == lastUp && v > 0) || (s == lastDown && v < 0)
                val y0 = y(from)
                var y1 = y(to)
                if (!outer) y1 += if (v >= 0) gap else -gap
                drawBar(start, barWidth, y0, y1, seriesColor(s).copy(alpha = alpha), if (outer) radius else CornerRadius.Zero)
            }
        } else {
            series.forEachIndexed { s, item ->
                val v = item.values.getOrNull(i) ?: return@forEachIndexed
                val x = start + s * (barWidth + gap)
                drawBar(x, barWidth, zero, y(v), seriesColor(s).copy(alpha = alpha), radius)
            }
        }
    }
}

/** A bar from [baseY] to [valueY], square at the baseline and rounded at the data end. */
private fun DrawScope.drawBar(x: Float, width: Float, baseY: Float, valueY: Float, color: Color, radius: CornerRadius) {
    if (abs(valueY - baseY) < 0.5f) return
    val up = valueY < baseY
    val rect = androidx.compose.ui.geometry.Rect(x, min(baseY, valueY), x + width, max(baseY, valueY))
    val r = if (radius.x * 2 > rect.height) CornerRadius(rect.height / 2f) else radius
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect,
                topLeft = if (up) r else CornerRadius.Zero,
                topRight = if (up) r else CornerRadius.Zero,
                bottomRight = if (up) CornerRadius.Zero else r,
                bottomLeft = if (up) CornerRadius.Zero else r,
            ),
        )
    }
    drawPath(path, color)
}

private fun DrawScope.drawLineSeries(
    values: List<Double?>,
    color: Color,
    band: Float,
    left: Float,
    curve: String?,
    area: Boolean,
    baseline: Float,
    y: (Double) -> Float,
    selected: Int?,
    surface: Color,
) {
    val points = values.mapIndexedNotNull { i, v -> v?.let { Offset(left + band * (i + 0.5f), y(it)) } }
    if (points.isEmpty()) return
    val line = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            when (curve) {
                "step" -> {
                    lineTo(b.x, a.y)
                    lineTo(b.x, b.y)
                }
                "natural" -> {
                    val dx = (b.x - a.x) / 2f
                    cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y)
                }
                else -> lineTo(b.x, b.y)
            }
        }
    }
    if (area) {
        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, baseline)
            lineTo(points.first().x, baseline)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.1f))
    }
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    // Markers only where they say something: the end of the line and the tapped category.
    val marked = listOfNotNull(values.indexOfLast { it != null }, selected).distinct()
    marked.forEach { i ->
        val v = values.getOrNull(i) ?: return@forEach
        val center = Offset(left + band * (i + 0.5f), y(v))
        drawCircle(surface, radius = 6.dp.toPx(), center = center)
        drawCircle(color, radius = 4.dp.toPx(), center = center)
    }
}

// ---- Horizontal bars ------------------------------------------------------------------------------

@Composable
private fun HorizontalBars(labels: List<String>, series: List<Series>, stacked: Boolean, selected: Int?, onSelect: (Int?) -> Unit) {
    val totals = labels.indices.map { i -> if (stacked) series.sumOf { max(0.0, it.values.getOrNull(i) ?: 0.0) } else series.maxOf { max(0.0, it.values.getOrNull(i) ?: 0.0) } }
    val maxValue = totals.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { i, label ->
            val alpha = if (selected == null || selected == i) 1f else 0.45f
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(if (selected == i) null else i) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatValue(totals[i]), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                }
                if (stacked) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                        val full = maxWidth
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            series.forEachIndexed { s, item ->
                                val v = max(0.0, item.values.getOrNull(i) ?: 0.0)
                                if (v > 0) Box(Modifier.width(full * (v / maxValue).toFloat()).height(14.dp).clip(RoundedCornerShape(4.dp)).background(seriesColor(s).copy(alpha = alpha)))
                            }
                        }
                    }
                } else {
                    series.forEachIndexed { s, item ->
                        val v = max(0.0, item.values.getOrNull(i) ?: 0.0)
                        BoxWithConstraints(Modifier.fillMaxWidth().height(if (series.size > 1) 10.dp else 14.dp)) {
                            Box(
                                Modifier
                                    .width(maxWidth * (v / maxValue).toFloat())
                                    .height(if (series.size > 1) 10.dp else 14.dp)
                                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                    .background(seriesColor(s).copy(alpha = alpha)),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Shares of a whole ----------------------------------------------------------------------------

/** PieChart, RadialChart and SingleStackedBarChart: labels with one value each. */
@Composable
internal fun ShareChart(n: UiNode, modifier: Modifier) {
    val labels = n.list("labels").map { OpenUiText.cellText(it) }
    val values = numbers(n["values"]).map { max(0.0, it ?: 0.0) }
    val count = min(labels.size, values.size)
    if (count == 0) return
    val total = values.take(count).sum().takeIf { it > 0 } ?: 1.0
    var selected by remember(count) { mutableStateOf<Int?>(null) }
    var table by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChartHeader(legend = null, table = table, onToggleTable = { table = !table })
        if (table) {
            DataTable(listOf("", "", "%"), (0 until count).map { listOf(labels[it], formatValue(values[it]), percent(values[it] / total)) }, listOf(false, true, true))
            return@Column
        }
        when (n.type) {
            "SingleStackedBarChart" -> Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0 until count).forEach { i ->
                    if (!(values[i] > 0) || !values[i].isFinite()) return@forEach
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                        count - 1 -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    Box(
                        Modifier
                            .weight(values[i].toFloat())
                            .height(16.dp)
                            .clip(shape)
                            .background(seriesColor(i).copy(alpha = if (selected == null || selected == i) 1f else 0.45f))
                            .clickable { selected = if (selected == i) null else i },
                    )
                }
            }
            else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RingCanvas(values.take(count), radial = n.type == "RadialChart", donut = n.string("variant") == "donut", selected = selected)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (0 until count).forEach { i ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selected = if (selected == i) null else i }
                        .then(if (selected == i) Modifier.pane(RoundedCornerShape(8.dp), Palette.Surface) else Modifier)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(seriesColor(i)))
                    Text(labels[i], style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatValue(values[i]), style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
                    Text(percent(values[i] / total), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.widthIn(min = 40.dp))
                }
            }
        }
    }
}

@Composable
private fun RingCanvas(values: List<Double>, radial: Boolean, donut: Boolean, selected: Int?) {
    val track = Palette.Surface
    Canvas(Modifier.size(176.dp)) {
        val total = values.sum().takeIf { it > 0 } ?: 1.0
        if (radial) {
            // One ring per value, longest for the largest, from twelve o'clock.
            val maxValue = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val slot = size.minDimension / 2f / (values.size + 1)
            val stroke = radialStrokePx(slot, 14.dp.toPx())
            values.forEachIndexed { i, v ->
                val inset = stroke / 2f + i * slot
                val topLeft = Offset(inset, inset)
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                if (arcSize.width <= 0f) return@forEachIndexed
                drawArc(track, -90f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val alpha = if (selected == null || selected == i) 1f else 0.45f
                drawArc(seriesColor(i).copy(alpha = alpha), -90f, (270 * v / maxValue).toFloat(), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            return@Canvas
        }
        val stroke = if (donut) 30.dp.toPx() else size.minDimension / 2f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        // The 2 dp surface gap between slices, as an angle at the ring's middle radius.
        val gapDegrees = if (values.count { it > 0 } > 1) (2.dp.toPx() / (arcSize.width / 2f) * 180f / Math.PI.toFloat()) else 0f
        var start = -90f
        values.forEachIndexed { i, v ->
            val sweep = (360.0 * v / total).toFloat()
            if (sweep <= 0f) return@forEachIndexed
            val alpha = if (selected == null || selected == i) 1f else 0.45f
            drawArc(seriesColor(i).copy(alpha = alpha), start + gapDegrees / 2f, (sweep - gapDegrees).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            start += sweep
        }
    }
}

internal fun radialStrokePx(slot: Float, maxStroke: Float): Float = min(maxStroke, slot * 0.7f).coerceAtLeast(0.1f)

// ---- Numbers ----------------------------------------------------------------------------------------

/** Four or five round tick values covering [low]..[high]. */
private fun niceTicks(low: Double, high: Double): List<Double> {
    if (!low.isFinite() || !high.isFinite()) return listOf(0.0, 1.0)
    var lo = low
    var hi = high
    if (hi == lo) {
        hi = if (hi == 0.0) 1.0 else hi + abs(hi) * 0.5
        lo = if (lo > 0) 0.0 else lo - abs(lo) * 0.5
    }
    val rough = (hi - lo) / 4
    val magnitude = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.firstOrNull { it >= rough } ?: (10 * magnitude)
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val ticks = mutableListOf<Double>()
    var t = start
    while (t <= end + step / 1000 && ticks.size < 12) {
        ticks += (Math.round(t / step * 1000) / 1000.0) * step
        t += step
    }
    return ticks.takeIf { it.size >= 2 } ?: listOf(lo, hi)
}

private fun formatCompact(value: Double): String {
    val a = abs(value)
    return when {
        a >= 1e9 -> formatNumber(Math.round(value / 1e8) / 10.0) + "B"
        a >= 1e6 -> formatNumber(Math.round(value / 1e5) / 10.0) + "M"
        a >= 1e4 -> formatNumber(Math.round(value / 1e2) / 10.0) + "K"
        else -> formatValue(value)
    }
}

private fun formatValue(value: Double): String {
    val format = NumberFormat.getNumberInstance()
    format.maximumFractionDigits = if (abs(value) >= 100) 0 else 2
    return format.format(value)
}

private fun percent(fraction: Double): String = NumberFormat.getPercentInstance().apply { maximumFractionDigits = if (fraction < 0.1) 1 else 0 }.format(fraction)
