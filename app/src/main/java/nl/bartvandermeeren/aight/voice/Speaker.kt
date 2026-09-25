package nl.bartvandermeeren.aight.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.data.TtsEngine

/**
 * Reads replies aloud. English goes to Kokoro when it's enabled and downloaded; Dutch, and
 * everything else, uses Android's voice. The language is guessed per reply, since Hermes answers in either.
 */
class Speaker(
    private val context: Context,
    private val kokoro: KokoroVoice,
    private val settings: () -> AppSettings,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<() -> Unit>()
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var token = 0L

    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    private fun ensure(action: () -> Unit) {
        if (ready) return action()
        pending.addLast(action)
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(progress)
                    while (pending.isNotEmpty()) pending.removeFirst().invoke()
                } else {
                    pending.clear()
                    tts = null
                }
            }
        }
    }

    /** Loads Kokoro in the background when it will be needed soon (overlay shown, Live started). */
    fun warmUp() {
        val s = settings()
        if (s.ttsEngine == TtsEngine.Kokoro) kokoro.warmUp(s.kokoroVoice)
    }

    fun speak(id: String, markdown: String, languageOverride: String? = null, onDone: (() -> Unit)? = null) {
        val text = SpeechText.fromMarkdown(markdown)
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        stop()
        // Message ids repeat (replaying a reply, Preview twice), so callbacks check this request's token instead.
        val mine = ++token
        val language = languageOverride?.takeIf { it.isNotBlank() } ?: SpeechText.guessLanguage(text)
        val s = settings()
        _speakingId.value = id
        val finished = {
            if (token == mine) {
                _speakingId.value = null
                onDone?.invoke()
            }
        }
        val usingKokoro = s.ttsEngine == TtsEngine.Kokoro && language.startsWith("en", ignoreCase = true) &&
            kokoro.speak(text, s.kokoroVoice) { outcome ->
                if (token != mine) return@speak
                when (outcome) {
                    KokoroVoice.Outcome.Done -> finished()
                    // Kokoro broke (out of memory, damaged model); say it with Android's voice instead.
                    KokoroVoice.Outcome.Failed -> speakWithSystem(mine, text, language, finished)
                    KokoroVoice.Outcome.Stopped -> _speakingId.value = null
                }
            }
        if (!usingKokoro) speakWithSystem(mine, text, language, finished)
    }

    private fun speakWithSystem(mine: Long, text: String, language: String, finished: () -> Unit) {
        ensure {
            if (token != mine) return@ensure
            val engine = tts ?: return@ensure
            engine.stop()
            callbacks.clear()
            engine.language = Locale.forLanguageTag(language).takeIf {
                engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
            } ?: Locale.getDefault()
            val chunks = SpeechText.chunk(text, TextToSpeech.getMaxSpeechInputLength() - 100)
            chunks.forEachIndexed { index, chunk ->
                val utteranceId = "$mine#$index"
                if (index == chunks.lastIndex) callbacks[utteranceId] = finished
                engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        }
    }

    fun stop() {
        token++
        // Speech queued while the engine was still starting must not play after a stop.
        pending.clear()
        callbacks.clear()
        tts?.stop()
        kokoro.stop()
        _speakingId.value = null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post { utteranceId?.let { callbacks.remove(it)?.invoke() } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onDone(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onDone(utteranceId)
        }
    }
}
