package nl.bartvandermeeren.dudan.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Listens for the wake phrase on its own thread while the phone is unlocked, and steps aside whenever
 * voice input records (see [WakeMic]). A small streaming keyword spotter does the listening, so it
 * costs little battery; nothing is recorded or sent anywhere until the phrase is heard.
 */
class WakeWordListener(
    private val context: Context,
    private val model: ModelPackage,
    /** The phrase to listen for; null while the wake word is off. Read on the listener thread. */
    private val phrase: () -> String?,
    /** The phrase was heard. Runs on the main thread; the audio after it waits in [WakeMic]. */
    private val onWake: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var thread: Thread? = null

    /** False once this thread was stopped, also when a new one already took its place. */
    private val running: Boolean get() = thread === Thread.currentThread()

    fun start() {
        if (thread != null) return
        val next = Thread(::run, "dudan-wake")
        thread = next
        next.start()
    }

    fun stop() {
        thread = null
    }

    /**
     * Only while someone uses the phone: unlocked, with the screen on, and not on a call. A locked phone
     * stays out of reach of whoever is near it, since the agent can act on the phone and on its server.
     */
    private fun canListen(): Boolean = !WakeMic.held && phoneAllows()

    private fun phoneAllows(): Boolean =
        model.isReady &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            power.isInteractive &&
            !keyguard.isKeyguardLocked &&
            audio.mode != AudioManager.MODE_IN_CALL &&
            audio.mode != AudioManager.MODE_IN_COMMUNICATION

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var spotter: KeywordSpotter? = null
        var spotterPhrase: String? = null
        try {
            while (running) {
                val wanted = phrase()
                if (wanted == null || !canListen()) {
                    WakeMic.await(IDLE_CHECK_MS)
                    continue
                }
                if (spotter == null || spotterPhrase != wanted) {
                    spotter?.release()
                    spotter = null
                    spotterPhrase = null
                    spotter = try {
                        createSpotter(wanted)
                    } catch (e: Throwable) {
                        Log.w(TAG, "keyword spotter failed to load", e)
                        null
                    }
                    if (spotter == null) {
                        // An unusable phrase or a broken model: wait for either to change.
                        WakeMic.await(RETRY_MS)
                        continue
                    }
                    spotterPhrase = wanted
                }
                listen(spotter, wanted)
            }
        } finally {
            spotter?.release()
        }
    }

    private fun createSpotter(wanted: String): KeywordSpotter? {
        val pieces = SentencePieces.parse(model.file("bpe.model").readBytes())
        val keywords = WakePhrase.keywords(wanted, pieces) ?: return null
        val file = File(context.noBackupFilesDir, "wake-keywords.txt").apply { writeText(keywords) }
        return model.guardLoad {
            KeywordSpotter(
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = OrukeetEngine.SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model.file(ENCODER).absolutePath,
                            decoder = model.file(DECODER).absolutePath,
                            joiner = model.file(JOINER).absolutePath,
                        ),
                        tokens = model.file("tokens.txt").absolutePath,
                        numThreads = 1,
                        provider = "cpu",
                        debug = false,
                    ),
                    maxActivePaths = 4,
                    keywordsFile = file.absolutePath,
                    // Tried on synthetic speech: every "Ok Clark" heard, and none of "OK, let's go to the park",
                    // "I talked to Clark", "the clerk said it was okay". Lower thresholds started to fire on those.
                    keywordsScore = 1.0f,
                    keywordsThreshold = 0.2f,
                    numTrailingBlanks = 1,
                ),
            )
        }
    }

    /** Listens until the phrase is heard and handed off, voice input needs the microphone, or something changed. */
    private fun listen(spotter: KeywordSpotter, wanted: String) {
        val source = TestMicrophone.open(context, TestMicrophone.WAKE_WORD) ?: LocalSpeechSession.openMicrophone(null)
        if (source == null) {
            // Another app records; try again in a moment.
            WakeMic.await(RETRY_MS)
            return
        }
        WakeMic.listenerMicrophone(source::interrupt)
        // Reads wait for audio, so the lock, screen and calls are also watched from the main thread.
        val watch = object : Runnable {
            override fun run() {
                // Not the holds: voice input holds the microphone while it reads the handed-off audio.
                if (!phoneAllows() || phrase() != wanted) source.interrupt() else main.postDelayed(this, WATCH_MS)
            }
        }
        main.postDelayed(watch, WATCH_MS)
        val stream = spotter.createStream()
        try {
            val samples = FloatArray(CHUNK)
            val recent = ArrayDeque<ShortArray>(PRE_ROLL_CHUNKS + 1)
            var chunks = 0
            while (running && !WakeMic.held) {
                if (++chunks % CHECK_EVERY == 0 && (!canListen() || phrase() != wanted)) return
                val pcm = ShortArray(CHUNK)
                if (!source.read(pcm)) return
                recent.addLast(pcm)
                if (recent.size > PRE_ROLL_CHUNKS) recent.removeFirst()
                for (i in pcm.indices) samples[i] = pcm[i] / 32768f
                if (heard(spotter, stream, samples)) {
                    handOff(source, recent)
                    return
                }
            }
        } finally {
            main.removeCallbacks(watch)
            stream.release()
            source.close()
            WakeMic.listenerMicrophone(null)
        }
    }

    private fun heard(spotter: KeywordSpotter, stream: OnlineStream, samples: FloatArray): Boolean {
        stream.acceptWaveform(samples, OrukeetEngine.SAMPLE_RATE)
        var heard = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                spotter.reset(stream)
                heard = true
            }
        }
        return heard
    }

    /**
     * Keeps recording for the voice input the wake word opens, until it has what it needs or nobody comes.
     * The spotter fires a moment after the phrase, and only at a pause when the question follows without
     * one, so the [recent] audio goes first: what was said right after the phrase isn't lost, and the
     * phrase itself is dropped from the transcript again (see [WakePhrase.stripLeading]).
     */
    private fun handOff(source: LocalSpeechSession.AudioSource, recent: Collection<ShortArray>) {
        Log.i(TAG, "wake word heard")
        val handoff = WakeMic.Handoff(silenced = source::isSilenced)
        recent.forEach(handoff::push)
        WakeMic.offer(handoff)
        main.post { onWake() }
        val pcm = ShortArray(CHUNK)
        while (running && handoff.wanted) {
            if (!source.read(pcm)) {
                handoff.end()
                return
            }
            handoff.push(pcm.copyOf())
        }
        handoff.expire()
    }

    companion object {
        private const val TAG = "DudanWake"
        const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"

        /** 100 ms of audio per read. */
        private const val CHUNK = OrukeetEngine.SAMPLE_RATE / 10

        /**
         * 1.5 s of audio before the spotter fired. It fires 0.4 s after the phrase when there's a pause, and
         * up to a second later when the question runs on, so this holds the whole phrase and what follows.
         */
        private const val PRE_ROLL_CHUNKS = 15

        /** Screen, lock, call and phrase are checked once a second while listening. */
        private const val CHECK_EVERY = 10
        private const val IDLE_CHECK_MS = 1_000L
        private const val WATCH_MS = 1_000L
        private const val RETRY_MS = 3_000L
    }
}
