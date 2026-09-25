package nl.bartvandermeeren.aight

import nl.bartvandermeeren.aight.voice.Endpointer
import nl.bartvandermeeren.aight.voice.Endpointer.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    private val window = 32

    /** Feeds [ms] of audio at one probability and returns every event that isn't None, with its time. */
    private fun Endpointer.feed(ms: Int, probability: Float, clock: IntArray): List<Pair<Event, Int>> =
        (0 until ms / window).mapNotNull {
            clock[0] += window
            accept(probability).takeIf { it != Event.None }?.let { it to clock[0] }
        }

    @Test
    fun silenceAloneTimesOut() {
        val clock = intArrayOf(0)
        val events = Endpointer().feed(10_000, 0.05f, clock)
        assertEquals(Event.NoSpeech, events.first().first)
        assertEquals(8_000, events.first().second, window)
    }

    @Test
    fun speechThenSilenceStartsAndEnds() {
        val clock = intArrayOf(0)
        val endpointer = Endpointer()
        val events = endpointer.feed(500, 0.02f, clock) + endpointer.feed(1_000, 0.9f, clock) + endpointer.feed(2_000, 0.02f, clock)
        assertEquals(listOf(Event.Started, Event.Ended), events.map { it.first }.distinct())
        // Started 160 ms into speech; ended 1.1 s after it stopped.
        assertEquals(500 + 160, events.first { it.first == Event.Started }.second, window)
        assertEquals(1_500 + 1_100, events.first { it.first == Event.Ended }.second, window)
    }

    @Test
    fun aClickDoesNotStartAnUtterance() {
        val clock = intArrayOf(0)
        val endpointer = Endpointer()
        val events = endpointer.feed(96, 0.95f, clock) + endpointer.feed(1_000, 0.02f, clock)
        assertEquals(emptyList<Event>(), events.map { it.first })
    }

    @Test
    fun aPauseBetweenSentencesKeepsListening() {
        val clock = intArrayOf(0)
        val endpointer = Endpointer()
        val events = endpointer.feed(1_000, 0.9f, clock) +
            endpointer.feed(700, 0.1f, clock) +
            endpointer.feed(1_000, 0.9f, clock) +
            endpointer.feed(1_500, 0.1f, clock)
        assertEquals(listOf(Event.Started, Event.Ended), events.map { it.first }.distinct())
        // The 700 ms pause didn't end it; only the silence after the second sentence did.
        assertEquals(2_700 + 1_100, events.first { it.first == Event.Ended }.second, window)
    }

    @Test
    fun softSpeechAfterTheStartStillCounts() {
        val clock = intArrayOf(0)
        val endpointer = Endpointer()
        val events = endpointer.feed(500, 0.9f, clock) + endpointer.feed(3_000, 0.4f, clock)
        assertEquals(listOf(Event.Started), events.map { it.first })
    }

    @Test
    fun longMonologueIsCutAtTheLimit() {
        val clock = intArrayOf(0)
        val endpointer = Endpointer(maxUtteranceMs = 5_000)
        val events = endpointer.feed(6_000, 0.9f, clock)
        assertEquals(Event.TooLong, events.first { it.first != Event.Started }.first)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected ± $tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
