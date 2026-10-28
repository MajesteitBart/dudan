package nl.bartvandermeeren.dudan.voice

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.swing.SwingUtilities

/**
 * Speech from a sherpa-onnx model on the PC, played while it's synthesized, as the phone's SherpaVoice
 * does with Java Sound in place of AudioTrack. Subclasses say how to load their engine and call it.
 */
abstract class SherpaSpeech(protected val model: ModelStore, threadName: String) {
    enum class Outcome { Done, Stopped, Failed }

    // One thread loads, synthesizes and releases, so the native engine is never used from two at once.
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, threadName).apply { isDaemon = true; priority = Thread.MAX_PRIORITY } }

    /** Bumped by every speak() and stop(); a synthesis callback that sees a newer value cancels itself. */
    private val generation = AtomicLong()
    @Volatile private var line: SourceDataLine? = null

    // Only touched on the worker thread.
    private var engine: OfflineTts? = null
    private var engineKey: Any? = null
    private var releaseIdle: ScheduledFuture<*>? = null

    val isReady: Boolean get() = model.isReady

    /** Voices with the same key share one loaded engine; a voice with another key reloads it. */
    protected abstract fun engineKey(voiceId: String): Any

    /** Loads the engine for [voiceId] from [ModelStore.dir]. Runs on the worker thread. */
    protected abstract fun loadEngine(voiceId: String): OfflineTts

    /** Synthesizes [text] in [language] into [sink], which plays it; stop early once [Sink.active] turns false. */
    protected abstract fun synthesize(tts: OfflineTts, text: String, voiceId: String, language: String, sink: Sink)

    protected fun path(name: String): String = File(model.dir, name).absolutePath

    /**
     * Speaks [text] in [language] (a code such as "nl"); false when the model isn't there. [onDone] runs once,
     * on the Swing thread; Failed means synthesis broke while this speech was still current.
     */
    fun speak(text: String, voiceId: String, language: String, onDone: (Outcome) -> Unit): Boolean {
        if (!model.isReady) return false
        val mine = generation.incrementAndGet()
        silence()
        worker.execute {
            releaseIdle?.cancel(false)
            val current = { generation.get() == mine }
            var outcome = Outcome.Stopped
            var output: SourceDataLine? = null
            try {
                if (!current()) return@execute
                val tts = engineFor(voiceId)
                // Loading the engine takes seconds; a stop() in the meantime must not start playback.
                if (!current()) return@execute
                output = openLine(tts.sampleRate)
                line = output
                val sink = Sink(output, tts.sampleRate, current)
                try {
                    synthesize(tts, text, voiceId, language, sink)
                } finally {
                    sink.finish()
                }
                sink.error?.let { throw it }
                if (current() && sink.written > 0) drain(output, sink.written, tts.sampleRate, current)
                if (current()) outcome = Outcome.Done
            } catch (e: Throwable) {
                System.err.println("dudan voice: synthesis failed: $e")
                if (current()) outcome = Outcome.Failed
            } finally {
                output?.let {
                    if (line === it) line = null
                    runCatching { it.close() }
                }
                releaseIdle = worker.schedule(::releaseEngine, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
                val result = outcome
                SwingUtilities.invokeLater { onDone(result) }
            }
        }
        return true
    }

    /** Loads the model ahead of time, so the first reply doesn't wait for it. */
    fun warmUp(voiceId: String) {
        if (!model.isReady) return
        worker.execute {
            runCatching { engineFor(voiceId) }
            releaseIdle?.cancel(false)
            releaseIdle = worker.schedule(::releaseEngine, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        silence()
    }

    /** Mutes the playing line at once; its worker sees the new generation and closes it. */
    private fun silence() {
        line?.let {
            runCatching {
                it.stop()
                it.flush()
            }
        }
    }

    /** Waits until everything written has played, by the clock: drain() would hang on a line stopped meanwhile. */
    private fun drain(output: SourceDataLine, written: Long, sampleRate: Int, current: () -> Boolean) {
        val remainingMs = (written - output.longFramePosition).coerceAtLeast(0) * 1000 / sampleRate
        val until = System.currentTimeMillis() + remainingMs + TAIL_MARGIN_MS
        while (current() && System.currentTimeMillis() < until) Thread.sleep(20)
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

    private fun openLine(sampleRate: Int): SourceDataLine {
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val output = AudioSystem.getSourceDataLine(format)
        // Half a second: enough to ride out a hiccup, short enough that Stop is heard at once.
        output.open(format, sampleRate / 2 * format.frameSize)
        return output
    }

    /**
     * Receives synthesized audio and queues it for a writer thread, so synthesis runs ahead of playback:
     * the next piece is ready when this one ends. The queue holds at most [MAX_AHEAD_SECONDS] of audio.
     *
     * sherpa-onnx's JNI looks up `Integer invoke(float[])` on the callback's class, which implementing
     * [OfflineTtsCallback] in a class of its own provides.
     */
    protected class Sink(private val output: SourceDataLine, private val sampleRate: Int, private val current: () -> Boolean) : OfflineTtsCallback {
        private val queue = AudioQueue(MAX_AHEAD_SECONDS * sampleRate)
        private val writer = Thread(::write, "voice-out").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }

        /** Frames handed to the line. */
        @Volatile var written = 0L
            private set
        @Volatile var error: Throwable? = null
            private set

        /** False once this speech was stopped or playback broke; synthesis should end then. */
        val active: Boolean get() = error == null && current()

        /** Plays [seconds] of silence, for a pause between separately synthesized pieces. */
        fun pause(seconds: Float) {
            invoke(FloatArray((sampleRate * seconds).toInt()))
        }

        // sherpa hands over a fresh array each time, so it can be queued as it is.
        override fun invoke(samples: FloatArray): Int = if (queue.put(samples) { active }) 1 else 0

        /** Waits until everything queued is in the line, or until playback stops. */
        fun finish() {
            queue.close()
            writer.join()
        }

        // Writes only what fits, so a stop() is noticed within a few milliseconds, even mid-sentence.
        private fun write() {
            try {
                val bytes = ByteArray(output.bufferSize)
                while (true) {
                    val samples = queue.take() ?: return
                    var offset = 0
                    while (offset < samples.size) {
                        if (!current()) return
                        if (!output.isRunning) output.start()
                        val frames = minOf(output.available() / 2, samples.size - offset, bytes.size / 2)
                        if (frames == 0) {
                            Thread.sleep(10)
                            continue
                        }
                        for (i in 0 until frames) {
                            val pcm = (samples[offset + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                            bytes[i * 2] = pcm.toByte()
                            bytes[i * 2 + 1] = (pcm shr 8).toByte()
                        }
                        output.write(bytes, 0, frames * 2)
                        offset += frames
                        written += frames
                    }
                    queue.release(samples)
                }
            } catch (e: Throwable) {
                // speak() rethrows it once synthesis has returned.
                error = e
            }
        }
    }

    companion object {
        /** Enough lead that the next sentence is ready in time, without keeping a long reply in memory. */
        private const val MAX_AHEAD_SECONDS = 20
        private const val IDLE_RELEASE_MS = 3 * 60 * 1000L
        /** Output latency after the last frame leaves the line, Bluetooth headsets included. */
        private const val TAIL_MARGIN_MS = 300L
        /** A PC has cores to spare; more than four barely speeds Kokoro up. */
        val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/** Kokoro-82M on the PC: English, in the voices the phone has. */
class KokoroSpeech(model: ModelStore) : SherpaSpeech(model, "kokoro") {
    // British and American voices need different lexicons, so they load different engines.
    override fun engineKey(voiceId: String): Any = Kokoro.voice(voiceId).british

    override fun loadEngine(voiceId: String): OfflineTts {
        val british = Kokoro.voice(voiceId).british
        val kokoro = OfflineTtsKokoroModelConfig.builder()
            .setModel(path("model.onnx"))
            .setVoices(path("voices.bin"))
            .setTokens(path("tokens.txt"))
            .setDataDir(path("espeak-ng-data"))
            .setLexicon(path(if (british) "lexicon-gb-en.txt" else "lexicon-us-en.txt"))
            .setLang(if (british) "en-gb" else "en-us")
            .build()
        val config = OfflineTtsConfig.builder()
            .setModel(OfflineTtsModelConfig.builder().setKokoro(kokoro).setNumThreads(THREADS).setDebug(false).setProvider("cpu").build())
            .setMaxNumSentences(1)
            // As on the phone: sherpa's default shortens every pause, which makes Kokoro sound rushed.
            .setSilenceScale(1.0f)
            .build()
        return OfflineTts(config)
    }

    override fun synthesize(tts: OfflineTts, text: String, voiceId: String, language: String, sink: Sink) {
        tts.generateWithCallback(text, Kokoro.voice(voiceId).speakerId, 1.0f, sink)
    }
}

/**
 * Supertonic 3 on the PC, in any of its 31 languages. Dutch numbers, times and amounts are written out
 * first, as on the phone, and the first sentence goes on its own so speech starts quickly.
 */
class SupertonicSpeech(model: ModelStore) : SherpaSpeech(model, "supertonic") {
    // One engine holds every voice; the voice is picked per call.
    override fun engineKey(voiceId: String): Any = Unit

    override fun loadEngine(voiceId: String): OfflineTts {
        val supertonic = OfflineTtsSupertonicModelConfig.builder()
            .setDurationPredictor(path("duration_predictor.onnx"))
            .setTextEncoder(path("text_encoder.onnx"))
            .setVectorEstimator(path("vector_estimator.onnx"))
            .setVocoder(path("vocoder.onnx"))
            .setTtsJson(path("tts.json"))
            .setUnicodeIndexer(path(SupertonicFiles.INDEXER))
            .setVoiceStyle(path(SupertonicFiles.VOICES))
            .build()
        val config = OfflineTtsConfig.builder()
            .setModel(OfflineTtsModelConfig.builder().setSupertonic(supertonic).setNumThreads(THREADS).setDebug(false).setProvider("cpu").build())
            .setSilenceScale(1.0f)
            .build()
        return OfflineTts(config)
    }

    override fun synthesize(tts: OfflineTts, text: String, voiceId: String, language: String, sink: Sink) {
        val config = GenerationConfig().apply {
            silenceScale = 1.0f
            speed = Supertonic.SPEED
            sid = Supertonic.voice(voiceId).speakerId
            numSteps = Supertonic.STEPS
            extra = mapOf("lang" to language)
        }
        val spoken = if (language == "nl") DutchText.normalize(text) else text
        SpeechText.streamingChunks(spoken, Supertonic.MAX_CHUNK).forEachIndexed { index, chunk ->
            if (!sink.active) return
            if (index > 0) sink.pause(Supertonic.PAUSE_SECONDS)
            tts.generateWithConfigAndCallback(chunk, config, sink)
        }
    }
}
