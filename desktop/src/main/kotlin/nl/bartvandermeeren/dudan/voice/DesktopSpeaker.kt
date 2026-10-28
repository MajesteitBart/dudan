package nl.bartvandermeeren.dudan.voice

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads replies aloud on the PC with the phone's voices, picked the way the phone's Automatic picks them:
 * Kokoro for English, Supertonic 3 for Dutch and the other languages it has. The language is guessed per
 * reply. Windows' own voices sound far worse, so there's no fallback to them: a voice that isn't
 * downloaded yet is reported instead, and the user can get it.
 */
class DesktopSpeaker(
    val kokoro: ModelStore,
    val supertonic: ModelStore,
    private val englishVoice: () -> String,
    private val otherVoice: () -> String,
    /** Windows' display language, which settles a reply that doesn't tell its own. */
    private val windowsLanguage: String = Locale.getDefault().language,
) {
    enum class Engine { Kokoro, Supertonic }

    sealed interface Result {
        data object Started : Result
        /** Nothing in the reply to read, such as a reply that's only files. */
        data object Empty : Result
        data class Missing(val engine: Engine, val language: String) : Result
        data class Unsupported(val language: String) : Result
    }

    private val kokoroSpeech = KokoroSpeech(kokoro)
    private val supertonicSpeech = SupertonicSpeech(supertonic)
    private var token = 0L

    private val _speakingId = MutableStateFlow<String?>(null)
    /** The message being read, or "preview:<voice>" for a sample from Settings. */
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    private val _failed = MutableStateFlow(0)
    /** Counts readings that broke off with an error (a damaged model, no sound device), for a notice. */
    val failed: StateFlow<Int> = _failed.asStateFlow()

    fun store(engine: Engine): ModelStore = if (engine == Engine.Kokoro) kokoro else supertonic

    /** Reads a reply's markdown aloud, in the language it's written in. Call on the Swing thread. */
    fun speak(id: String, markdown: String): Result {
        val text = SpeechText.withPauses(SpeechText.fromMarkdown(markdown))
        if (text.isBlank()) return Result.Empty
        val language = languageOf(text, windowsLanguage)
        return when (val choice = choose(language, kokoro.isReady, supertonic.isReady)) {
            is Choice.Use -> {
                say(id, text, language, choice.engine, if (choice.engine == Engine.Kokoro) englishVoice() else otherVoice())
                Result.Started
            }
            is Choice.Missing -> Result.Missing(choice.engine, language)
            Choice.Unsupported -> Result.Unsupported(language)
        }
    }

    /** Settings' sample of [voice] on [engine], greeting [name]. */
    fun preview(engine: Engine, voice: String, name: String) {
        val language = if (engine == Engine.Kokoro) "en" else windowsLanguage.takeIf { it in Supertonic.LANGUAGES } ?: "nl"
        say("preview:$voice", VoiceSamples.text(language, name), language, engine, voice)
    }

    /** Loads the voice the next reply most likely needs, so it starts without the load's few seconds. */
    fun warmUp() {
        if (windowsLanguage == "en" || !supertonic.isReady) kokoroSpeech.warmUp(englishVoice()) else supertonicSpeech.warmUp(otherVoice())
    }

    private fun say(id: String, text: String, language: String, engine: Engine, voice: String) {
        stop()
        // Ids repeat (reading a reply again, a preview twice), so the callback checks this request's token.
        val mine = ++token
        _speakingId.value = id
        val speech = if (engine == Engine.Kokoro) kokoroSpeech else supertonicSpeech
        val started = speech.speak(text, voice, language) { outcome ->
            if (token != mine) return@speak
            _speakingId.value = null
            if (outcome == SherpaSpeech.Outcome.Failed) _failed.value++
        }
        if (!started) _speakingId.value = null
    }

    fun stop() {
        token++
        kokoroSpeech.stop()
        supertonicSpeech.stop()
        _speakingId.value = null
    }

    sealed interface Choice {
        data class Use(val engine: Engine) : Choice
        data class Missing(val engine: Engine) : Choice
        data object Unsupported : Choice
    }

    companion object {
        /**
         * Who reads [language]: Kokoro for English, else Supertonic, which also reads English while Kokoro
         * isn't downloaded. A missing voice is the one that would read it best.
         */
        fun choose(language: String, kokoroReady: Boolean, supertonicReady: Boolean): Choice = when {
            language == "en" && kokoroReady -> Choice.Use(Engine.Kokoro)
            language in Supertonic.LANGUAGES && supertonicReady -> Choice.Use(Engine.Supertonic)
            language == "en" -> Choice.Missing(Engine.Kokoro)
            language in Supertonic.LANGUAGES -> Choice.Missing(Engine.Supertonic)
            else -> Choice.Unsupported
        }

        /** The language code to read [text] in; a tie, or text that doesn't tell, goes to Windows' language. */
        fun languageOf(text: String, windowsLanguage: String): String =
            LanguageGuess.guess(text, listOf(windowsLanguage, "en").filter { it.isNotBlank() }.distinct())
                ?: windowsLanguage.ifBlank { "en" }
    }
}
