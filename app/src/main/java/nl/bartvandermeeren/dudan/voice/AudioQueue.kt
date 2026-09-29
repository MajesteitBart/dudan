package nl.bartvandermeeren.dudan.voice

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Synthesized audio waiting to be played, capped at [maxSamples] so synthesis runs a little ahead of
 * playback but never holds a whole long reply in memory. A piece bigger than the cap still fits once
 * the queue is empty.
 */
internal class AudioQueue(private val maxSamples: Int) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    private val room = Semaphore(maxSamples)

    /** Waits for room, then queues [samples]. False when [keepWaiting] turns false first. */
    fun put(samples: FloatArray, keepWaiting: () -> Boolean): Boolean {
        val needed = cost(samples)
        while (keepWaiting()) {
            if (room.tryAcquire(needed, 20, TimeUnit.MILLISECONDS)) {
                queue.put(samples)
                return true
            }
        }
        return false
    }

    /** Marks the end of the audio. Never waits, so it's safe after playback stopped. */
    fun close() {
        queue.put(END)
    }

    /** The next piece, waiting for one; null once the end is reached. */
    fun take(): FloatArray? = queue.take().takeIf { it !== END }

    /** Frees the room [samples] took, once they're in the track. */
    fun release(samples: FloatArray) {
        room.release(cost(samples))
    }

    private fun cost(samples: FloatArray) = minOf(samples.size, maxSamples)

    private companion object {
        val END = FloatArray(0)
    }
}
