package nl.bartvandermeeren.dudan.openui

import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal bars around a zero line: a negative value grows left of it, as it hangs below a vertical
 * chart's baseline. Positions are fractions of the chart's width. Both apps draw HorizontalBarChart with it.
 */
class BarScale private constructor(private val from: Double, private val span: Double) {
    /** Where [value] falls across the width. */
    fun at(value: Double): Float = ((value - from) / span).toFloat().coerceIn(0f, 1f)

    /** The left and right edges of a bar from [start] to [end], in either order. */
    fun bar(start: Double, end: Double): Pair<Float, Float> = at(min(start, end)) to at(max(start, end))

    companion object {
        /** A value as a bar draws it: missing, NaN and infinite values are zero. */
        fun clean(value: Double?): Double = value?.takeIf { it.isFinite() } ?: 0.0

        /**
         * The scale for [rows], one value per series in each. A stacked row reaches as far right as its
         * positive values add up to, and as far left as its negative ones.
         */
        fun of(rows: List<List<Double>>, stacked: Boolean): BarScale {
            var low = 0.0
            var high = 0.0
            rows.forEach { row ->
                if (stacked) {
                    low = min(low, row.filter { it < 0 }.sum())
                    high = max(high, row.filter { it > 0 }.sum())
                } else {
                    row.forEach {
                        low = min(low, it)
                        high = max(high, it)
                    }
                }
            }
            val span = high - low
            return BarScale(low, if (span > 0) span else 1.0)
        }

        /** A stacked row's segments as (start, end): positive values build right from zero, negative ones left. */
        fun stack(row: List<Double>): List<Pair<Double, Double>> {
            var right = 0.0
            var left = 0.0
            return row.map { value ->
                if (value >= 0) (right to right + value).also { right += value } else (left to left + value).also { left += value }
            }
        }
    }
}
