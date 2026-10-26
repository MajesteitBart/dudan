package nl.bartvandermeeren.dudan.voice

import android.content.res.Resources
import java.util.Locale
import nl.bartvandermeeren.dudan.data.LanguageVoice
import nl.bartvandermeeren.dudan.data.VoiceEngine

/**
 * Which voice reads which language. Kokoro reads English, Supertonic 3 reads 31 languages (English and
 * Dutch among them), and Android's voice reads whatever the phone has voices for.
 *
 * Settings shows a row for each of the phone's languages, the speech language and English. Every other
 * language follows the Other languages row, saved under [OTHER].
 */
object VoiceLanguages {
    const val OTHER = "*"

    /** Supertonic 3's languages, from its README. */
    val SUPERTONIC = setOf(
        "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it",
        "ja", "ko", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
    )

    /** The engine and voice that read a reply. [voice] is null for Android's voice. */
    data class Resolved(val engine: VoiceEngine, val voice: String?)

    /** The engines that can read [language], best first. Android's voice can always try. */
    fun engines(language: String): List<VoiceEngine> = buildList {
        if (language == "en") add(VoiceEngine.Kokoro)
        if (language == OTHER || language in SUPERTONIC) add(VoiceEngine.Supertonic)
        add(VoiceEngine.System)
    }

    /** The engine [choice] asks for in [language]: its own, or Automatic's pick once every model is in. */
    fun preferred(language: String, choice: LanguageVoice): VoiceEngine = choice.engine ?: engines(language).first()

    /**
     * The on-device models to fetch: those the language rows ask for. Automatic in Other languages only
     * uses Supertonic when it's already there, so a language you rarely get doesn't cost 400 MB.
     */
    fun needed(rows: List<String>, voices: Map<String, LanguageVoice>): Set<VoiceEngine> = buildSet {
        rows.forEach { add(preferred(it, voices[it] ?: LanguageVoice())) }
        voices[OTHER]?.engine?.let(::add)
        remove(VoiceEngine.System)
    }

    /** The user's own [languages] in their order, then English, then languages that kept a setting of their own. */
    fun rows(own: List<String>, voices: Map<String, LanguageVoice>): List<String> =
        (own + "en" + voices.keys.sorted()).filter { it != OTHER }.distinct()

    /**
     * The user's own languages: the phone's, then [speechLanguage] (a tag such as "nl-NL", or empty), since
     * the user talks to dudan in it and replies tend to follow.
     */
    fun languages(speechLanguage: String, phone: List<String> = phoneLanguages()): List<String> =
        (phone + code(speechLanguage.trim())).filter { it.isNotEmpty() }.distinct()

    /**
     * Who reads a reply in [language]. A language without a row follows Other languages. Automatic takes
     * the best engine whose model is [ready]. A chosen engine that isn't ready, or can't read the language,
     * leaves it to Android's voice.
     */
    fun resolve(language: String, voices: Map<String, LanguageVoice>, rows: List<String>, ready: (VoiceEngine) -> Boolean): Resolved {
        val choice = voices[language] ?: (if (language in rows) null else voices[OTHER]) ?: LanguageVoice()
        val able = engines(language)
        val engine = when (val chosen = choice.engine) {
            null -> able.firstOrNull { it == VoiceEngine.System || ready(it) } ?: VoiceEngine.System
            else -> chosen.takeIf { it in able && (it == VoiceEngine.System || ready(it)) } ?: VoiceEngine.System
        }
        return Resolved(engine, voiceFor(engine, choice.voice))
    }

    /** [voice] when [engine] has it, else the engine's default. Null for Android's voice. */
    fun voiceFor(engine: VoiceEngine, voice: String?): String? = when (engine) {
        VoiceEngine.Kokoro -> voice?.takeIf { id -> KokoroVoice.VOICES.any { it.id == id } } ?: KokoroVoice.DEFAULT
        VoiceEngine.Supertonic -> voice?.takeIf { id -> SupertonicVoice.VOICES.any { it.id == id } } ?: SupertonicVoice.DEFAULT
        VoiceEngine.System -> null
    }

    /** The language part of a tag such as "nl-NL", in the form these lists use. */
    fun code(tag: String): String = when (val language = tag.substringBefore('-').substringBefore('_').lowercase()) {
        // Java's old codes, which older Android versions still report.
        "in" -> "id"
        "iw" -> "he"
        else -> language
    }

    /** The languages set in Android's settings, in their order. */
    fun phoneLanguages(): List<String> {
        val locales = Resources.getSystem().configuration.locales
        return (0 until locales.size()).map { code(locales[it].language) }.filter { it.isNotEmpty() }.distinct()
    }

    /** "Nederlands" or "Dutch": [language]'s name in the language of [ui]. */
    fun displayName(language: String, ui: Locale): String =
        Locale.forLanguageTag(language).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }
}
