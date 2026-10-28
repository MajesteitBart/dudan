package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.openui.BarScale
import org.junit.Assert.assertEquals
import org.junit.Test

class BarScaleTest {
    private fun assertBar(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.001f)
        assertEquals(expected.second, actual.second, 0.001f)
    }

    @Test
    fun positiveValuesStartAtTheLeftEdgeAsBefore() {
        val scale = BarScale.of(listOf(listOf(5.0), listOf(10.0)), stacked = false)
        assertBar(0f to 0.5f, scale.bar(0.0, 5.0))
        assertBar(0f to 1f, scale.bar(0.0, 10.0))
    }

    @Test
    fun negativeValuesGrowLeftOfTheZeroLine() {
        val scale = BarScale.of(listOf(listOf(-10.0), listOf(5.0)), stacked = false)
        // Zero sits two thirds across: -10 takes the left part, 5 the rest.
        assertEquals(2f / 3, scale.at(0.0), 0.001f)
        assertBar(0f to 2f / 3, scale.bar(0.0, -10.0))
        assertBar(2f / 3 to 1f, scale.bar(0.0, 5.0))
    }

    @Test
    fun onlyNegativeValuesFillTheWidthFromTheRight() {
        val scale = BarScale.of(listOf(listOf(-4.0), listOf(-8.0)), stacked = false)
        assertBar(0.5f to 1f, scale.bar(0.0, -4.0))
        assertBar(0f to 1f, scale.bar(0.0, -8.0))
    }

    @Test
    fun stackedRowsAddTheirSignsApart() {
        val rows = listOf(listOf(3.0, -2.0, 1.0), listOf(1.0, 4.0, 0.0))
        assertEquals(listOf(0.0 to 3.0, 0.0 to -2.0, 3.0 to 4.0), BarScale.stack(rows[0]))
        val scale = BarScale.of(rows, stacked = true)
        // From -2 (the first row's negatives) to 5 (the second row's positives).
        assertBar(0f to 2f / 7, scale.bar(0.0, -2.0))
        assertBar(2f / 7 to 1f, scale.bar(0.0, 5.0))
    }

    @Test
    fun missingAndBrokenValuesAreZero() {
        assertEquals(0.0, BarScale.clean(null), 0.0)
        assertEquals(0.0, BarScale.clean(Double.NaN), 0.0)
        assertEquals(0.0, BarScale.clean(Double.POSITIVE_INFINITY), 0.0)
        // All zero: no bars, and no division by zero.
        assertBar(0f to 0f, BarScale.of(listOf(listOf(0.0)), stacked = false).bar(0.0, 0.0))
    }
}
