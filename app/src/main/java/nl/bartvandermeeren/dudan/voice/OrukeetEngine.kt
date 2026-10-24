package nl.bartvandermeeren.dudan.voice

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orukeet speech recognition on the phone through sherpa-onnx. One recognizer serves the whole
 * process: it loads on first use, decodes on a single worker thread, and goes after a few idle minutes.
 * The language clips that keep it to one language ([LanguagePrimer]) come from [assets].
 */
class OrukeetEngine(private val model: ModelPackage, private val assets: AssetManager) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "orukeet").apply { priority = Thread.MAX_PRIORITY } }
    private val pending = AtomicInteger()

    // Only touched on the worker thread.
    private var recognizer: OfflineRecognizer? = null
    private val primers = mutableMapOf<String, LanguagePrimer?>()
    private val releaseIdle = Runnable { worker.execute { releaseRecognizer() } }

    val isReady: Boolean get() = model.isReady

    /** True while a transcription is queued or running, so a live preview can skip a round. */
    val isBusy: Boolean get() = pending.get() > 0

    /** Loads the recognizer while the user is still talking, so the final decode doesn't wait for it. */
    fun warmUp() {
        if (!model.isReady) return
        worker.execute { runCatching { recognizer() }.onFailure { Log.w(TAG, "loading Orukeet failed", it) } }
        scheduleRelease()
    }

    /**
     * Transcribes 16 kHz mono audio. With a [language] (the speech language setting, such as "nl-NL")
     * that has a clip, a short utterance is heard as that language; otherwise Orukeet works it out.
     * [speechSamples] is how much of [samples] is speech, so silence at the end doesn't count toward
     * [LanguagePrimer.MAX_SAMPLES]. [onText] runs on the main thread, with null when decoding failed.
     * Work whose recording was [cancelled] while it waited in the queue is skipped without a callback.
     */
    fun transcribe(
        samples: FloatArray,
        language: String?,
        speechSamples: Int = samples.size,
        cancelled: () -> Boolean = { false },
        onText: (String?) -> Unit,
    ) {
        pending.incrementAndGet()
        main.removeCallbacks(releaseIdle)
        worker.execute {
            if (cancelled()) {
                pending.decrementAndGet()
                scheduleRelease()
                return@execute
            }
            val text = try {
                val engine = recognizer()
                val primer = primer(language)
                if (primer != null && speechSamples <= LanguagePrimer.MAX_SAMPLES) {
                    // Nothing after the clip means Orukeet skipped the utterance; decode it on its own then.
                    primed(engine, primer, samples).ifEmpty { null } ?: decode(engine, samples).text.trim()
                } else {
                    val plain = decode(engine, samples).text.trim()
                    if (primer != null) inLanguage(engine, primer, samples, plain, language) else plain
                }
            } catch (e: Throwable) {
                Log.w(TAG, "transcription failed", e)
                null
            }
            pending.decrementAndGet()
            scheduleRelease()
            main.post { onText(text) }
        }
    }

    private fun primed(engine: OfflineRecognizer, primer: LanguagePrimer, samples: FloatArray): String {
        val result = decode(engine, primer.prime(samples))
        return primer.transcript(result.tokens, result.timestamps)
    }

    /**
     * Longer speech is decoded on its own, since behind the clip Orukeet sometimes skips sentences of it.
     * When that comes out in another language than [language], it's decoded once more behind the clip,
     * and that wins if it is in the right language. Speech that really is in another language stays as heard.
     */
    private fun inLanguage(engine: OfflineRecognizer, primer: LanguagePrimer, samples: FloatArray, plain: String, language: String?): String {
        val wanted = SpeechLanguage.normalize(language) ?: return plain
        val heard = SpeechText.guessLanguage(plain) ?: return plain
        if (SpeechLanguage.sameLanguage(heard, wanted)) return plain
        val again = primed(engine, primer, samples)
        val guess = SpeechText.guessLanguage(again) ?: return plain
        return if (SpeechLanguage.sameLanguage(guess, wanted)) again else plain
    }

    /** A voice activity detector for one recording. Cheap to make: the Silero model is 0.6 MB. */
    fun newVad(): Vad = model.guardLoad {
        Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.file(VAD_MODEL).absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
    }

    private fun decode(engine: OfflineRecognizer, samples: FloatArray): OfflineRecognizerResult {
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            return engine.getResult(stream)
        } finally {
            stream.release()
        }
    }

    /** The clip for [language], loaded once; null without one. Worker thread only. */
    private fun primer(language: String?): LanguagePrimer? {
        val code = LanguagePrimer.languageOf(language) ?: return null
        if (code !in primers) {
            primers[code] = try {
                LanguagePrimer(LanguagePrimer.readWav(assets.open(LanguagePrimer.assetPath(code)).use { it.readBytes() }))
            } catch (e: Exception) {
                Log.w(TAG, "language clip for $code failed to load", e)
                null
            }
        }
        return primers[code]
    }

    private fun scheduleRelease() {
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
    }

    private fun recognizer(): OfflineRecognizer = recognizer ?: model.guardLoad {
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                // Parakeet v3, and so Orukeet, uses 128 mel bins.
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = model.file("encoder.int8.onnx").absolutePath,
                        decoder = model.file("decoder.int8.onnx").absolutePath,
                        joiner = model.file("joiner.int8.onnx").absolutePath,
                    ),
                    tokens = model.file("tokens.txt").absolutePath,
                    // Naming the type skips sherpa-onnx loading the 650 MB encoder twice to detect it.
                    modelType = "nemo_transducer",
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        )
    }.also { recognizer = it }

    private fun releaseRecognizer() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        private const val TAG = "DudanOrukeet"
        const val SAMPLE_RATE = 16_000

        /** 32 ms, the window Silero VAD works with at 16 kHz. */
        const val WINDOW = 512
        const val VAD_MODEL = "silero_vad.onnx"
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
    }
}
