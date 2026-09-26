package nl.bartvandermeeren.aight

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import nl.bartvandermeeren.aight.voice.AudioQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQueueTest {
    @Test
    fun synthesisWaitsOnceTheCapIsReached() {
        val queue = AudioQueue(maxSamples = 100)
        assertTrue(queue.put(FloatArray(60)) { true })
        val second = FloatArray(60)
        val queued = CountDownLatch(1)
        val producer = thread { if (queue.put(second) { true }) queued.countDown() }
        // 120 samples would pass the cap, so the second piece waits for the first to be played.
        assertFalse(queued.await(200, TimeUnit.MILLISECONDS))
        val first = queue.take()!!
        queue.release(first)
        assertTrue(queued.await(2, TimeUnit.SECONDS))
        assertSame(second, queue.take())
        producer.join()
    }

    @Test
    fun aStoppedSynthesisStopsWaiting() {
        val queue = AudioQueue(maxSamples = 100)
        assertTrue(queue.put(FloatArray(100)) { true })
        var checks = 0
        // Nobody takes audio after a stop; put must give up instead of blocking the synthesis thread.
        assertFalse(queue.put(FloatArray(10)) { ++checks < 3 })
        assertEquals(3, checks)
    }

    @Test
    fun aPieceLargerThanTheCapFitsWhenTheQueueIsEmpty() {
        val queue = AudioQueue(maxSamples = 100)
        val big = FloatArray(250)
        assertTrue(queue.put(big) { true })
        assertSame(big, queue.take())
    }

    @Test
    fun closeNeverWaitsAndEndsTheStream() {
        val queue = AudioQueue(maxSamples = 10)
        assertTrue(queue.put(FloatArray(10)) { true })
        queue.close()
        queue.take()
        assertNull(queue.take())
    }
}
