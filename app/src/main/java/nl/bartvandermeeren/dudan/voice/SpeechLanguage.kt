package nl.bartvandermeeren.dudan.voice

import java.util.Locale

/**
 * The speech language setting as a BCP 47 tag. People type "nl", "nl_NL", "Dutch" or "Nederlands" as
 * often as "nl-NL"; each of those has to reach the recognizers as the same language.
 */
object SpeechLanguage {
    /** Language names in English and in the language itself, for the languages people here type most. */
    private val names = mapOf(
        "dutch" to "nl-NL", "nederlands" to "nl-NL", "flemish" to "nl-BE", "vlaams" to "nl-BE",
        "english" to "en-US", "engels" to "en-US",
        "german" to "de-DE", "deutsch" to "de-DE", "duits" to "de-DE",
        "french" to "fr-FR", "français" to "fr-FR", "francais" to "fr-FR", "frans" to "fr-FR",
        "spanish" to "es-ES", "español" to "es-ES", "espanol" to "es-ES", "spaans" to "es-ES",
        "italian" to "it-IT", "italiano" to "it-IT", "italiaans" to "it-IT",
        "portuguese" to "pt-PT", "português" to "pt-PT", "portugees" to "pt-PT",
    )

    /** A bare language gets the region most people mean, since some recognizers want one. */
    private val regions = mapOf(
        "nl" to "NL", "en" to "US", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "PT",
    )

    /** The tag for [setting], or null when it's blank or not a language: then the recognizer works it out. */
    fun normalize(setting: String?): String? {
        val raw = setting?.trim().orEmpty()
        if (raw.isEmpty()) return null
        names[raw.lowercase()]?.let { return it }
        val locale = Locale.forLanguageTag(raw.replace('_', '-'))
        val language = locale.language
        if (language.length !in 2..3) return null
        val region = locale.country.ifEmpty { regions[language].orEmpty() }
        return if (region.isEmpty()) language else "$language-$region"
    }

    /** The language's English name, such as "Dutch", for instructions to the agent. */
    fun englishName(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    /** True when [a] and [b] name the same language, whatever their regions. */
    fun sameLanguage(a: String, b: String): Boolean =
        Locale.forLanguageTag(a).language.equals(Locale.forLanguageTag(b).language, ignoreCase = true)
}
