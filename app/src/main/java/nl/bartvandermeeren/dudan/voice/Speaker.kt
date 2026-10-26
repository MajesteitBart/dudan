package nl.bartvandermeeren.dudan.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.LanguageVoice
import nl.bartvandermeeren.dudan.data.VoiceEngine

/**
 * Reads replies aloud with the voice set for the reply's language (see [VoiceLanguages]): Kokoro or
 * Supertonic on the phone once downloaded, else Android's voice. The language is guessed per reply,
 * since Hermes can answer in any.
 */
class Speaker(
    private val context: Context,
    private val kokoro: KokoroVoice,
    private val supertonic: SupertonicVoice,
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

    /**
     * Loads the on-device voices in the background when they will be needed soon (overlay shown, Live
     * started): every engine the language rows use, since the next reply can be in any of them.
     */
    fun warmUp() {
        val s = settings()
        val rows = VoiceLanguages.rows(VoiceLanguages.languages(s.speechLanguage), s.voices)
        rows.map { resolve(it, s.voices, rows) }.distinctBy { it.engine }.forEach { (engine, voice) ->
            if (voice != null) sherpa(engine)?.warmUp(voice)
        }
    }

    private fun sherpa(engine: VoiceEngine): SherpaVoice? = when (engine) {
        VoiceEngine.Kokoro -> kokoro
        VoiceEngine.Supertonic -> supertonic
        VoiceEngine.System -> null
    }

    private fun resolve(language: String, voices: Map<String, LanguageVoice>, rows: List<String>) =
        VoiceLanguages.resolve(language, voices, rows) { sherpa(it)?.isReady == true }

    /**
     * The reply's language decides the voice. When the text doesn't tell ("OK", a name), [languageHint]
     * (the speech language setting) does, and without one the phone's language. A tie between two
     * languages goes to [languageHint], then to the phone's own. See [VoiceLanguages.replyLanguage].
     */
    fun speak(id: String, markdown: String, languageHint: String? = null, onDone: (() -> Unit)? = null) {
        val text = SpeechText.withPauses(SpeechText.fromMarkdown(markdown))
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val s = settings()
        val rows = VoiceLanguages.rows(VoiceLanguages.languages(s.speechLanguage), s.voices)
        val language = VoiceLanguages.replyLanguage(text, languageHint, rows)
        say(id, text, language, resolve(VoiceLanguages.code(language), s.voices, rows), onDone)
    }

    /** Reads [text] in [language] (a code such as "de") with [choice], for Preview in Settings. */
    fun preview(text: String, language: String, choice: LanguageVoice) {
        say("preview", text, language, resolve(language, mapOf(language to choice), listOf(language)), null)
    }

    private fun say(id: String, text: String, language: String, resolved: VoiceLanguages.Resolved, onDone: (() -> Unit)?) {
        stop()
        // Message ids repeat (replaying a reply, Preview twice), so callbacks check this request's token instead.
        val mine = ++token
        _speakingId.value = id
        val finished = {
            if (token == mine) {
                _speakingId.value = null
                onDone?.invoke()
            }
        }
        val voice = sherpa(resolved.engine)
        val spoken = voice != null && resolved.voice != null && voice.speak(text, resolved.voice, VoiceLanguages.code(language)) { outcome ->
            if (token != mine) return@speak
            when (outcome) {
                SherpaVoice.Outcome.Done -> finished()
                // The model broke (out of memory, damaged files); say it with Android's voice instead.
                SherpaVoice.Outcome.Failed -> speakWithSystem(mine, text, language, finished)
                SherpaVoice.Outcome.Stopped -> _speakingId.value = null
            }
        }
        if (!spoken) speakWithSystem(mine, text, language, finished)
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
        supertonic.stop()
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
