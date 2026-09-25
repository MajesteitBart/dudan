package nl.bartvandermeeren.aight.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orukeet speech recognition on the phone through sherpa-onnx. One recognizer serves the whole
 * process: it loads on first use, decodes on a single worker thread, and goes after a few idle minutes.
 */
class OrukeetEngine(private val model: ModelPackage) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "orukeet").apply { priority = Thread.MAX_PRIORITY } }
    private val pending = AtomicInteger()

    // Only touched on the worker thread.
    private var recognizer: OfflineRecognizer? = null
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
     * Transcribes 16 kHz mono audio. [onText] runs on the main thread, with null when decoding failed.
     * Work whose recording was [cancelled] while it waited in the queue is skipped without a callback.
     */
    fun transcribe(samples: FloatArray, cancelled: () -> Boolean = { false }, onText: (String?) -> Unit) {
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
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                } finally {
                    stream.release()
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
        private const val TAG = "AightOrukeet"
        const val SAMPLE_RATE = 16_000

        /** 32 ms, the window Silero VAD works with at 16 kHz. */
        const val WINDOW = 512
        const val VAD_MODEL = "silero_vad.onnx"
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
    }
}
