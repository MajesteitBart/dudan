package nl.bartvandermeeren.aight.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Speech from a sherpa-onnx model on the phone, played while it is synthesized, so the first words
 * play while the rest is still being made. Subclasses say how to load their engine and how to call it.
 */
abstract class SherpaVoice(context: Context, protected val model: ModelPackage, threadName: String) {
    enum class Outcome { Done, Stopped, Failed }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, threadName).apply { priority = Thread.MAX_PRIORITY } }
    private val audio = appContext.getSystemService(AudioManager::class.java)

    /** Bumped by every speak() and stop(); a synthesis callback that sees a newer value cancels itself. */
    private val generation = AtomicLong()
    @Volatile private var track: AudioTrack? = null

    // Only touched on the worker thread.
    private var engine: OfflineTts? = null
    private var engineKey: Any? = null
    private val releaseIdle = Runnable { worker.execute { releaseEngine() } }

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        // Its own listener keeps this request apart from voice input's; see LocalSpeechSession.
        .setOnAudioFocusChangeListener { }
        .build()

    /** Voices with the same key share one loaded engine; a voice with another key reloads it. */
    protected abstract fun engineKey(voiceId: String): Any

    /** Loads the engine for [voiceId] from [ModelPackage.dir]. Runs on the worker thread. */
    protected abstract fun loadEngine(voiceId: String): OfflineTts

    /** Synthesizes [text] into [sink], which plays it; stop early once [Sink.active] turns false. */
    protected abstract fun synthesize(tts: OfflineTts, text: String, voiceId: String, sink: Sink)

    /** Loads the model ahead of time, so the first reply doesn't wait for it. */
    fun warmUp(voiceId: String) {
        if (!model.isReady) return
        worker.execute { runCatching { engineFor(voiceId) } }
        // Unused, the engine still goes after the idle timeout; a speak() in between resets it.
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
    }

    /**
     * Speaks [text]; returns false when the model can't be used, so the caller falls back to Android's
     * voice. [onDone] runs once on the main thread; Failed means synthesis broke while still current.
     */
    fun speak(text: String, voiceId: String, onDone: (Outcome) -> Unit): Boolean {
        if (!model.isReady) return false
        val mine = generation.incrementAndGet()
        silence()
        main.removeCallbacks(releaseIdle)
        worker.execute {
            val current = { generation.get() == mine }
            var outcome = Outcome.Stopped
            var output: AudioTrack? = null
            var focused = false
            try {
                if (!current()) return@execute
                val tts = engineFor(voiceId)
                // Loading the engine takes seconds; a stop() in the meantime must not start playback.
                if (!current()) return@execute
                output = openTrack(tts.sampleRate())
                track = output
                audio?.requestAudioFocus(focus)
                focused = true
                val sink = Sink(output, current)
                try {
                    synthesize(tts, text, voiceId, sink)
                } finally {
                    sink.finish()
                }
                sink.error?.let { throw it }
                if (current() && sink.written > 0) drain(output, sink.written, current)
                if (current()) outcome = Outcome.Done
            } catch (e: Throwable) {
                Log.w("AightVoice", "synthesis failed", e)
                if (current()) outcome = Outcome.Failed
            } finally {
                // Only this worker releases its track, so a callback never runs into a released one.
                output?.let {
                    if (track === it) track = null
                    runCatching { it.release() }
                }
                if (focused) audio?.abandonAudioFocusRequest(focus)
                main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
                main.post { onDone(outcome) }
            }
        }
        return true
    }

    fun stop() {
        generation.incrementAndGet()
        silence()
        audio?.abandonAudioFocusRequest(focus)
    }

    /** Mutes the playing track at once; its worker sees the new generation and releases it. */
    private fun silence() {
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    /**
     * Waits until everything written has played. stop() plays out the buffer (and starts a track that
     * never reached its start threshold), but it also resets the head position, so the wait goes by the clock.
     */
    private fun drain(output: AudioTrack, written: Long, current: () -> Boolean) {
        val played = output.playbackHeadPosition.toLong()
        output.stop()
        val remainingMs = (written - played).coerceAtLeast(0) * 1000 / output.sampleRate
        val until = SystemClock.uptimeMillis() + remainingMs + TAIL_MARGIN_MS
        while (current() && SystemClock.uptimeMillis() < until) Thread.sleep(20)
    }

    private fun engineFor(voiceId: String): OfflineTts {
        val key = engineKey(voiceId)
        engine?.let { if (engineKey == key) return it }
        releaseEngine()
        return model.guardLoad { loadEngine(voiceId) }.also {
            engine = it
            engineKey = key
        }
    }

    private fun releaseEngine() {
        engine?.release()
        engine = null
        engineKey = null
    }

    private fun openTrack(sampleRate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // A few seconds; audio synthesized further ahead waits in the Sink's queue.
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 4 * 4))
            .build()
        // A stream track waits for a full buffer before it starts; start after 200 ms of audio instead.
        track.setStartThresholdInFrames(minOf(track.bufferSizeInFrames, sampleRate / 5))
        return track
    }

    /**
     * Receives synthesized audio and queues it for a writer thread, so synthesis runs ahead of playback
     * instead of waiting for room in the track's buffer: the next piece is ready when this one ends.
     *
     * sherpa-onnx's JNI looks up `Integer invoke(float[])` on the callback's own class, and Kotlin 2
     * compiles lambdas through invokedynamic, which only has the erased `Object invoke(Object)`. So this
     * has to be a real class; proguard-rules.pro keeps the method.
     */
    protected class Sink(private val output: AudioTrack, private val current: () -> Boolean) : (FloatArray) -> Int {
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val writer = Thread(::write, "voice-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        @Volatile var written = 0L
            private set
        @Volatile var error: Throwable? = null
            private set

        /** False once this speech was stopped or playback broke; synthesis should end then. */
        val active: Boolean get() = error == null && current()

        /** Plays [seconds] of silence, for a pause between separately synthesized pieces. */
        fun pause(seconds: Float) {
            invoke(FloatArray((output.sampleRate * seconds).toInt()))
        }

        // sherpa hands over a fresh array each time, so it can be queued as it is.
        override fun invoke(samples: FloatArray): Int {
            if (!active) return 0
            queue.put(samples)
            return 1
        }

        /** Waits until everything queued is in the track, or until playback stops. */
        fun finish() {
            queue.put(END)
            writer.join()
        }

        // Writes without blocking, so a stop() is noticed within a few milliseconds, even mid-sentence.
        private fun write() {
            try {
                while (true) {
                    val samples = queue.take()
                    if (samples === END) return
                    var offset = 0
                    while (offset < samples.size) {
                        if (!current()) return
                        if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
                        val count = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0) { "AudioTrack write failed: $count" }
                        offset += count
                        written += count
                        if (count == 0) Thread.sleep(10)
                    }
                }
            } catch (e: Throwable) {
                // speak() rethrows it once synthesis has returned.
                error = e
            }
        }

        private companion object {
            val END = FloatArray(0)
        }
    }

    companion object {
        private const val IDLE_RELEASE_MS = 3 * 60 * 1000L
        /** Output latency after the last frame leaves the buffer; Bluetooth headsets need the most. */
        private const val TAIL_MARGIN_MS = 300L

        private val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
