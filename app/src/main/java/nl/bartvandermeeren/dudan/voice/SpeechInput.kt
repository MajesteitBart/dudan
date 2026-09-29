package nl.bartvandermeeren.dudan.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.bartvandermeeren.dudan.appContainer
import nl.bartvandermeeren.dudan.data.SttEngine

/**
 * One-utterance speech capture with live level and partial transcript. Main thread only. Uses
 * Orukeet on the phone when it's switched on and downloaded, and Android's recognizer otherwise.
 */
class SpeechInput(private val context: Context) {
    enum class Phase { Idle, Starting, Listening, Processing }

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Input level from 0 to 1, for the waveform. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var local: LocalSpeechSession? = null
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((Int) -> Unit)? = null

    val isActive: Boolean get() = _phase.value != Phase.Idle

    private var language: String? = null
    private var candidates: List<android.content.ComponentName> = emptyList()
    private var attempt = 0

    fun start(language: String?, onResult: (String) -> Unit, onError: (Int) -> Unit = {}) {
        release()
        this.language = language
        this.onResult = onResult
        this.onError = onError
        val container = context.appContainer
        if (container.settingsSnapshot.value.sttEngine == SttEngine.Orukeet && container.orukeet.isReady) {
            startLocal(container.orukeet)
            return
        }
        candidates = SpeechRecognizers.candidates(context)
        attempt = 0
        launch()
    }

    private fun startLocal(engine: OrukeetEngine) {
        _partial.value = ""
        _phase.value = Phase.Starting
        lateinit var session: LocalSpeechSession
        session = LocalSpeechSession(
            context,
            engine,
            object : LocalSpeechSession.Listener {
                private val current get() = local === session

                override fun onListening() {
                    if (current) _phase.value = Phase.Listening
                }

                override fun onLevel(level: Float) {
                    if (current && _phase.value == Phase.Listening) _level.value = level
                }

                override fun onPartial(text: String) {
                    if (current) _partial.value = text
                }

                override fun onProcessing() {
                    if (!current) return
                    _phase.value = Phase.Processing
                    _level.value = 0f
                }

                override fun onResult(text: String) {
                    if (!current) return
                    val callback = onResult
                    onResult = null
                    onError = null
                    release()
                    callback?.invoke(text)
                }

                override fun onError(code: Int) {
                    if (!current) return
                    Log.w("DudanSpeech", "Orukeet input error $code")
                    val callback = this@SpeechInput.onError
                    onResult = null
                    this@SpeechInput.onError = null
                    release()
                    callback?.invoke(code)
                }
            },
        )
        local = session
        session.start()
    }

    /** Starts the current candidate recognizer; falls back to the on-device one when none is installed. */
    private fun launch() {
        val created = SpeechRecognizers.create(context, candidates.getOrNull(attempt))
        if (created == null) {
            val callback = onError
            onResult = null
            onError = null
            callback?.invoke(ERROR_UNAVAILABLE)
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (!language.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
        }
        _partial.value = ""
        _phase.value = Phase.Starting
        created.startListening(intent)
    }

    /**
     * Stop capturing; the final transcript still arrives through the start() callback. Some
     * recognizers never answer after a silent stop, so fall back to the partial transcript.
     */
    fun stop() {
        if (_phase.value == Phase.Idle) return
        local?.let {
            it.finish()
            _phase.value = Phase.Processing
            _level.value = 0f
            return
        }
        // Always ask for the final transcript: some recognizers never send partial results.
        recognizer?.stopListening()
        _phase.value = Phase.Processing
        _level.value = 0f
        val stopped = recognizer
        main.postDelayed({
            if (recognizer === stopped && _phase.value == Phase.Processing) {
                val resultCallback = onResult
                val errorCallback = onError
                val partial = _partial.value
                onResult = null
                onError = null
                release()
                if (partial.isNotBlank()) resultCallback?.invoke(partial) else errorCallback?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }, STOP_TIMEOUT_MS)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun cancel() {
        onResult = null
        onError = null
        release()
    }

    private fun release() {
        local?.cancel()
        local = null
        recognizer?.runCatching { cancel(); destroy() }
        recognizer = null
        _phase.value = Phase.Idle
        _level.value = 0f
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _phase.value = Phase.Listening
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _phase.value = Phase.Processing
            _level.value = 0f
        }

        override fun onError(error: Int) {
            android.util.Log.w("DudanSpeech", "recognizer ${candidates.getOrNull(attempt)} error $error in phase ${_phase.value}")
            // A recognizer that fails before it ever listens (no language pack, offline, busy) gets
            // replaced by the next installed one instead of failing the whole voice input.
            if (_phase.value == Phase.Starting && error in fallbackErrors && attempt + 1 < candidates.size) {
                recognizer?.runCatching { destroy() }
                recognizer = null
                attempt++
                main.post { if (onResult != null) launch() }
                return
            }
            val callback = onError
            val partial = _partial.value
            val resultCallback = onResult
            onResult = null
            onError = null
            release()
            // Some recognizers report "no match" after already streaming a usable partial.
            if (partial.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                resultCallback?.invoke(partial)
            } else {
                callback?.invoke(error)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: _partial.value
            val callback = onResult
            onResult = null
            onError = null
            release()
            callback?.invoke(text.orEmpty())
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { _partial.value = it }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        const val ERROR_UNAVAILABLE = -1
        private const val STOP_TIMEOUT_MS = 2_500L

        private val fallbackErrors = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        )

        fun isSilence(error: Int) = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
}
