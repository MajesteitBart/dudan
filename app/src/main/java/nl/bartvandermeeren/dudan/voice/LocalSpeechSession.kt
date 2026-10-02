package nl.bartvandermeeren.dudan.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One utterance recorded by dudan itself and transcribed by Orukeet. Silero VAD finds where speech
 * starts and ends, previews decode what was said so far, and the final decode runs after the end.
 * Listener calls arrive on the main thread and stop after [cancel].
 */
class LocalSpeechSession(
    private val context: Context,
    private val engine: OrukeetEngine,
    private val listener: Listener,
    /** Silence after speech that ends the utterance; see [Endpointer]. */
    private val endSilenceMs: Int = Endpointer.DEFAULT_END_SILENCE_MS,
) {
    interface Listener {
        fun onListening()
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onProcessing()
        fun onResult(text: String)
        fun onError(code: Int)
    }

    /** Where the audio comes from: the microphone, or a test recording in debug builds. */
    interface AudioSource {
        /** Fills [window] completely; false when the source broke. */
        fun read(window: ShortArray): Boolean

        /** True when Android hands us zeros because someone else took the microphone. */
        fun isSilenced(): Boolean = false

        fun close()
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var live = true

    @Volatile private var finishRequested = false

    fun start() {
        engine.warmUp()
        Thread({ capture() }, "dudan-mic").start()
    }

    /** Stop listening now and transcribe what was said so far. */
    fun finish() {
        finishRequested = true
    }

    /** Ends the recording without a result; no listener calls follow. */
    fun cancel() {
        live = false
    }

    private fun post(action: Listener.() -> Unit) {
        main.post { if (live) listener.action() }
    }

    private fun capture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // One recording at a time: a cancelled session finishes its cleanup (headset route, focus,
        // microphone) before the next one starts, so it can't undo the new session's setup.
        synchronized(captureLock) {
            if (live) captureExclusively()
        }
    }

    private fun captureExclusively() {
        val audio = context.getSystemService(AudioManager::class.java)
        // Music and podcasts pause while we listen, so they don't end up in the transcript.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // A listener of its own gives this request its own identity; without one, Kokoro abandoning
            // its focus would abandon ours too.
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // A phone call holds the audio; recording now would only hear the call.
            post { onError(SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        val headset = HeadsetMicrophone.connect(audio) { live }
        var source: AudioSource? = null
        var vad: Vad? = null
        try {
            source = TestMicrophone.open(context) ?: openMicrophone(headset?.input)
            if (source == null) {
                post { onError(SpeechRecognizer.ERROR_AUDIO) }
                return
            }
            vad = try {
                engine.newVad()
            } catch (e: Throwable) {
                Log.w(TAG, "voice activity detector failed to load", e)
                post { onError(SpeechRecognizer.ERROR_CLIENT) }
                return
            }
            record(source, vad)
        } finally {
            source?.close()
            vad?.release()
            headset?.disconnect()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun record(source: AudioSource, vad: Vad) {
        val pcm = ShortArray(WINDOW)
        val window = FloatArray(WINDOW)
        val audio = Recording()
        val endpointer = Endpointer(endSilenceMs = endSilenceMs)
        var speechStart = -1
        var lastPreview = 0
        var windows = 0
        post { onListening() }
        while (live) {
            if (!source.read(pcm)) {
                post { onError(SpeechRecognizer.ERROR_AUDIO) }
                return
            }
            // A call or another privileged recorder can take the microphone and leave us zeros; say so
            // instead of waiting for speech that can't arrive.
            if (++windows % SILENCE_CHECK_WINDOWS == 0 && source.isSilenced()) {
                Log.w(TAG, "microphone silenced by the system")
                post { onError(SpeechRecognizer.ERROR_AUDIO) }
                return
            }
            var energy = 0.0
            for (i in 0 until WINDOW) {
                val sample = pcm[i] / 32768f
                window[i] = sample
                energy += sample * sample
            }
            audio.append(window)
            val level = levelOf(sqrt(energy / WINDOW))
            post { onLevel(level) }

            val event = endpointer.accept(vad.compute(window))
            if (event == Endpointer.Event.Started) {
                // Keep the speech that triggered the start plus a little lead-in for the first syllable.
                speechStart = max(0, audio.size - (endpointer.startWindows + PRE_ROLL_WINDOWS) * WINDOW)
            }
            when {
                event == Endpointer.Event.NoSpeech && !finishRequested -> {
                    post { onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                    return
                }
                event == Endpointer.Event.Ended || event == Endpointer.Event.TooLong -> {
                    val trailing = endpointer.trailingSilenceMs * SAMPLES_PER_MS - KEEP_SILENCE_MS * SAMPLES_PER_MS
                    transcribe(audio, speechStart, audio.size - max(0, trailing))
                    return
                }
                finishRequested -> {
                    // Stopped by hand: decode everything, since quiet speech may never have tripped the detector.
                    transcribe(audio, if (speechStart >= 0) speechStart else 0, audio.size)
                    return
                }
            }
            if (speechStart >= 0 && audio.size - lastPreview >= PREVIEW_EVERY && !engine.isBusy) {
                lastPreview = audio.size
                engine.transcribe(audio.copy(speechStart, audio.size), cancelled = { !live }) { text ->
                    if (live && !text.isNullOrBlank()) listener.onPartial(text)
                }
            }
        }
    }

    private fun transcribe(audio: Recording, from: Int, to: Int) {
        post { onProcessing() }
        engine.transcribe(audio.copy(from, max(from, to)), cancelled = { !live }) { text ->
            if (!live) return@transcribe
            when {
                text == null -> listener.onError(SpeechRecognizer.ERROR_CLIENT)
                text.isBlank() -> listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                else -> listener.onResult(text)
            }
        }
    }

    @SuppressLint("MissingPermission") // SpeechInput's callers ask for the microphone first.
    private fun openMicrophone(preferred: AudioDeviceInfo?): AudioSource? {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val record = try {
            AudioRecord(
                // Tuned for recognition: most phones skip noise suppression and gain control here.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minimum, SAMPLE_RATE * 2), // one second of 16-bit audio
            )
        } catch (e: Exception) {
            Log.w(TAG, "microphone unavailable", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        if (preferred != null && !record.setPreferredDevice(preferred)) Log.w(TAG, "could not record from the headset")
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Another app holds the microphone.
            record.release()
            return null
        }
        return object : AudioSource {
            override fun read(window: ShortArray): Boolean {
                var offset = 0
                while (offset < window.size) {
                    val count = record.read(window, offset, window.size - offset, AudioRecord.READ_BLOCKING)
                    if (count <= 0) return false
                    offset += count
                }
                return true
            }

            override fun isSilenced() = record.activeRecordingConfiguration?.isClientSilenced == true

            override fun close() {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    /**
     * A Bluetooth headset's microphone, routed the way a call routes it, so Live and the side key work
     * with the phone in a pocket. Google's recognizer did this itself; our own recording has to ask.
     */
    private class HeadsetMicrophone(private val audio: AudioManager, val input: AudioDeviceInfo) {
        fun disconnect() {
            runCatching { audio.clearCommunicationDevice() }
        }

        companion object {
            private val headsetTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

            /**
             * Asks for the headset route and waits until it's up. The request is this app's own: clearing
             * it later leaves other apps' requests, such as a call's, in place.
             */
            fun connect(audio: AudioManager, live: () -> Boolean): HeadsetMicrophone? {
                try {
                    val headset = audio.availableCommunicationDevices.firstOrNull { it.type in headsetTypes } ?: return null
                    if (!audio.setCommunicationDevice(headset)) return null
                    val deadline = SystemClock.uptimeMillis() + ROUTE_TIMEOUT_MS
                    while (audio.communicationDevice?.id != headset.id && live() && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                    val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == headset.type && it.address == headset.address }
                    if (audio.communicationDevice?.id != headset.id || input == null) {
                        // Not up in time: the phone's own microphone beats a half-open headset link.
                        Log.w(TAG, "headset route not ready, using the phone microphone")
                        runCatching { audio.clearCommunicationDevice() }
                        return null
                    }
                    return HeadsetMicrophone(audio, input)
                } catch (e: Exception) {
                    Log.w(TAG, "headset microphone unavailable", e)
                    runCatching { audio.clearCommunicationDevice() }
                    return null
                }
            }
        }
    }

    /** Growing mono buffer; a minute of 16 kHz audio is under 4 MB. */
    private class Recording {
        private var data = FloatArray(SAMPLE_RATE * 8)
        var size = 0
            private set

        fun append(window: FloatArray) {
            if (size + window.size > data.size) data = data.copyOf(data.size * 2)
            window.copyInto(data, size)
            size += window.size
        }

        fun copy(from: Int, to: Int): FloatArray = data.copyOfRange(from.coerceIn(0, size), to.coerceIn(0, size))
    }

    companion object {
        private const val TAG = "DudanSpeech"
        private const val SAMPLE_RATE = OrukeetEngine.SAMPLE_RATE
        private const val WINDOW = OrukeetEngine.WINDOW
        private const val SAMPLES_PER_MS = SAMPLE_RATE / 1000
        private const val PRE_ROLL_WINDOWS = 10
        private const val KEEP_SILENCE_MS = 300

        /** Check for a silenced microphone about twice a second. */
        private const val SILENCE_CHECK_WINDOWS = 16
        private const val ROUTE_TIMEOUT_MS = 1_500L
        private val captureLock = Any()

        /** A live preview every 0.8 s of new audio, when the decoder is free. */
        private const val PREVIEW_EVERY = SAMPLE_RATE * 8 / 10

        /** -55 dBFS (a quiet room) to -15 dBFS (close speech) mapped onto the waveform's 0 to 1. */
        private fun levelOf(rms: Double): Float {
            val db = 20 * log10(rms.coerceAtLeast(1e-6))
            return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
        }
    }
}
