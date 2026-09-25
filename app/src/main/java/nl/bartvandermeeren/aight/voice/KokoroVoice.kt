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
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Kokoro-82M speech on the phone through sherpa-onnx. Audio streams sentence by sentence, so the
 * first words play while the rest is still being synthesized. English only: Kokoro has no Dutch.
 */
class KokoroVoice(context: Context, private val model: ModelPackage) {
    data class Voice(val id: String, val speakerId: Int, val label: String) {
        val british: Boolean get() = id.startsWith("b")
    }

    enum class Outcome { Done, Stopped, Failed }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "kokoro").apply { priority = Thread.MAX_PRIORITY } }
    private val audio = appContext.getSystemService(AudioManager::class.java)

    /** Bumped by every speak() and stop(); a synthesis callback that sees a newer value cancels itself. */
    private val generation = AtomicLong()
    @Volatile private var track: AudioTrack? = null

    // Only touched on the worker thread.
    private var engine: OfflineTts? = null
    private var engineBritish: Boolean? = null
    private val releaseIdle = Runnable { worker.execute { releaseEngine() } }

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        // Its own listener keeps this request apart from voice input's; see LocalSpeechSession.
        .setOnAudioFocusChangeListener { }
        .build()

    /** Loads the model ahead of time, so the first reply doesn't wait for it. */
    fun warmUp(voiceId: String) {
        if (!model.isReady) return
        val voice = voiceFor(voiceId)
        worker.execute { runCatching { engineFor(voice) } }
        // Unused, the engine still goes after the idle timeout; a speak() in between resets it.
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
    }

    /**
     * Speaks [text]; returns false when Kokoro can't be used, so the caller falls back to Android's
     * voice. [onDone] runs once on the main thread; Failed means synthesis broke while still current.
     */
    fun speak(text: String, voiceId: String, onDone: (Outcome) -> Unit): Boolean {
        if (!model.isReady) return false
        val voice = voiceFor(voiceId)
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
                val tts = engineFor(voice)
                // Loading the engine takes seconds; a stop() in the meantime must not start playback.
                if (!current()) return@execute
                output = openTrack(tts.sampleRate())
                track = output
                audio?.requestAudioFocus(focus)
                focused = true
                val sink = SampleSink(output, current)
                tts.generateWithCallback(text, voice.speakerId, 1.0f, sink)
                sink.error?.let { throw it }
                if (current() && sink.written > 0) drain(output, sink.written, current)
                if (current()) outcome = Outcome.Done
            } catch (e: Throwable) {
                Log.w("AightKokoro", "synthesis failed", e)
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

    private fun voiceFor(id: String) = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    private fun engineFor(voice: Voice): OfflineTts {
        engine?.let { if (engineBritish == voice.british) return it }
        releaseEngine()
        val dir = model.dir
        fun path(name: String) = File(dir, name).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = path("model.int8.onnx"),
                    voices = path("voices.bin"),
                    tokens = path("tokens.txt"),
                    dataDir = path("espeak-ng-data"),
                    lexicon = path(if (voice.british) "lexicon-gb-en.txt" else "lexicon-us-en.txt"),
                    lang = if (voice.british) "en-gb" else "en-us",
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return model.guardLoad { OfflineTts(config = config) }.also {
            engine = it
            engineBritish = voice.british
        }
    }

    private fun releaseEngine() {
        engine?.release()
        engine = null
        engineBritish = null
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
            // Room for a few seconds, so synthesis of the next sentence overlaps playback.
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 4 * 4))
            .build()
        // A stream track waits for a full buffer before it starts; start after 200 ms of audio instead.
        track.setStartThresholdInFrames(minOf(track.bufferSizeInFrames, sampleRate / 5))
        return track
    }

    /**
     * Receives synthesized audio. sherpa-onnx's JNI looks up `Integer invoke(float[])` on the callback's
     * own class, and Kotlin 2 compiles lambdas through invokedynamic, which only has the erased
     * `Object invoke(Object)`. So this has to be a real class; proguard-rules.pro keeps the method.
     */
    private class SampleSink(private val output: AudioTrack, private val current: () -> Boolean) : (FloatArray) -> Int {
        var written = 0L
            private set
        var error: Throwable? = null
            private set

        // Writes without blocking, so a stop() is noticed within a few milliseconds, even mid-sentence.
        override fun invoke(samples: FloatArray): Int = try {
            var offset = 0
            while (offset < samples.size && current()) {
                if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
                val count = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                check(count >= 0) { "AudioTrack write failed: $count" }
                offset += count
                written += count
                if (count == 0) Thread.sleep(10)
            }
            if (current()) 1 else 0
        } catch (e: Throwable) {
            // An exception must not cross back into sherpa's native code; speak() rethrows it.
            error = e
            0
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

        /** English voices in the Kokoro v1.0 speaker table (ids from the model's id2speaker metadata). */
        val VOICES = listOf(
            Voice("af_heart", 3, "Heart · US"),
            Voice("af_bella", 2, "Bella · US"),
            Voice("af_nicole", 6, "Nicole · US"),
            Voice("af_sarah", 9, "Sarah · US"),
            Voice("am_michael", 16, "Michael · US"),
            Voice("am_fenrir", 14, "Fenrir · US"),
            Voice("am_puck", 18, "Puck · US"),
            Voice("bf_emma", 21, "Emma · UK"),
            Voice("bm_george", 26, "George · UK"),
            Voice("bm_fable", 25, "Fable · UK"),
        )
    }
}
