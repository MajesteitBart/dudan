package nl.bartvandermeeren.dudan.voice

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Shares the microphone between the wake word listener and voice input. Voice input holds it while
 * it records: the listener closes its microphone and waits. Right after a wake word the listener
 * keeps recording into a [Handoff] instead, so what the user says straight after "Ok Clark" reaches
 * the recording that the wake word opened, without the gap of opening the microphone again.
 */
object WakeMic {
    private val lock = Object()
    private var holds = 0
    private var handoff: Handoff? = null
    private var listenerOpen = false
    private var interruptListener: (() -> Unit)? = null

    /** True while voice input holds the microphone. */
    val held: Boolean get() = synchronized(lock) { holds > 0 }

    /**
     * Voice input starts recording: the listener pauses. Returns the audio after a wake word when one
     * just fired; otherwise waits briefly until the listener has closed its microphone. Pair with [release].
     */
    fun acquire(): LocalSpeechSession.AudioSource? = synchronized(lock) {
        holds++
        val waiting = handoff
        handoff = null
        lock.notifyAll()
        if (waiting != null && waiting.claim()) return waiting
        interruptListener?.invoke()
        val deadline = now() + RELEASE_WAIT_MS
        while (listenerOpen) {
            val left = deadline - now()
            if (left <= 0) break
            lock.wait(left)
        }
        null
    }

    /**
     * Pauses the listener without waiting, for the phone's recognizer, which records in its own process.
     * Audio waiting after a wake word is dropped: that recognizer can't take it.
     */
    fun pause() = synchronized(lock) {
        holds++
        handoff?.expire()
        handoff = null
        interruptListener?.invoke()
        lock.notifyAll()
    }

    fun release() = synchronized(lock) {
        if (holds > 0) holds--
        lock.notifyAll()
    }

    /**
     * The listener's side: its microphone is open, with [interrupt] to stop it from another thread
     * (a read can wait on a stalled microphone), or closed when that's null.
     */
    internal fun listenerMicrophone(interrupt: (() -> Unit)?) = synchronized(lock) {
        listenerOpen = interrupt != null
        interruptListener = interrupt
        lock.notifyAll()
    }

    /**
     * The listener listens on [source] for as long as [listen] runs. Afterwards, also when [listen]
     * throws, the source is closed and voice input no longer waits for it or interrupts it.
     */
    internal inline fun <T> listenOn(source: LocalSpeechSession.AudioSource, listen: () -> T): T {
        try {
            listenerMicrophone(source::interrupt)
            return listen()
        } finally {
            source.close()
            listenerMicrophone(null)
        }
    }

    /** The listener heard the wake word and keeps recording into [next] until voice input takes it. */
    internal fun offer(next: Handoff) = synchronized(lock) {
        handoff?.expire()
        handoff = next
    }

    /** The listener's wait for something to change, such as a hold ending. */
    internal fun await(ms: Long) = synchronized(lock) { lock.wait(ms) }

    private const val RELEASE_WAIT_MS = 600L

    private fun now() = System.nanoTime() / 1_000_000

    /**
     * Audio from the listener's microphone after the wake word, read by voice input as its microphone.
     * Nobody claiming it within [CLAIM_MS] means no recording followed the wake word.
     */
    class Handoff(private val silenced: () -> Boolean) : LocalSpeechSession.AudioSource {
        private enum class State { Waiting, Claimed, Closed }

        private val chunks = LinkedBlockingQueue<ShortArray>()
        private var current: ShortArray? = null
        private var offset = 0
        private val created = now()

        @Volatile private var state = State.Waiting

        /** The listener keeps feeding while this is true. */
        val wanted: Boolean
            get() = when (state) {
                State.Claimed -> true
                State.Waiting -> now() - created < CLAIM_MS && chunks.size < MAX_CHUNKS
                State.Closed -> false
            }

        @Synchronized
        internal fun claim(): Boolean {
            if (state != State.Waiting || !wanted) return false
            state = State.Claimed
            return true
        }

        @Synchronized
        internal fun expire() {
            if (state == State.Waiting) state = State.Closed
        }

        /** The listener's next piece of audio. */
        internal fun push(chunk: ShortArray) {
            if (state != State.Closed) chunks.add(chunk)
        }

        /** The listener lost its microphone; a reader gets false once the queued audio is used up. */
        internal fun end() {
            state = State.Closed
            chunks.add(END)
        }

        override fun read(window: ShortArray): Boolean {
            var filled = 0
            while (filled < window.size) {
                val chunk = current ?: (chunks.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return false)
                if (chunk === END) return false
                current = chunk
                val count = minOf(window.size - filled, chunk.size - offset)
                chunk.copyInto(window, filled, offset, offset + count)
                filled += count
                offset += count
                if (offset == chunk.size) {
                    current = null
                    offset = 0
                }
            }
            return true
        }

        override fun isSilenced() = silenced()

        override fun close() {
            state = State.Closed
            chunks.clear()
        }

        companion object {
            const val CLAIM_MS = 4_000L
            private const val READ_TIMEOUT_MS = 1_000L

            /** A minute of 100 ms chunks: a recording never runs longer. */
            private const val MAX_CHUNKS = 600
            private val END = ShortArray(0)
        }
    }
}
