package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.data.LanguageVoice
import nl.bartvandermeeren.dudan.data.VoiceEngine
import nl.bartvandermeeren.dudan.data.decodeVoices
import nl.bartvandermeeren.dudan.data.encodeVoices
import nl.bartvandermeeren.dudan.data.legacyVoices
import nl.bartvandermeeren.dudan.voice.VoiceLanguages
import nl.bartvandermeeren.dudan.voice.VoiceLanguages.OTHER
import nl.bartvandermeeren.dudan.voice.VoiceLanguages.Resolved
import nl.bartvandermeeren.dudan.voice.VoiceSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLanguagesTest {
    private val everything: (VoiceEngine) -> Boolean = { true }
    private val nothing: (VoiceEngine) -> Boolean = { it == VoiceEngine.System }

    @Test
    fun rowsAreThePhonesLanguagesThenEnglish() {
        assertEquals(listOf("nl", "en"), VoiceLanguages.rows(listOf("nl", "en"), emptyMap()))
        assertEquals(listOf("de", "en"), VoiceLanguages.rows(listOf("de"), emptyMap()))
        assertEquals(listOf("en"), VoiceLanguages.rows(listOf("en"), emptyMap()))
        // A language that kept its own setting stays visible after the phone drops it; Other isn't a row.
        assertEquals(listOf("en", "fr"), VoiceLanguages.rows(listOf("en"), mapOf("fr" to LanguageVoice(VoiceEngine.System), OTHER to LanguageVoice(VoiceEngine.System))))
    }

    @Test
    fun automaticTakesTheBestVoiceThatIsInstalled() {
        val rows = listOf("nl", "en")
        assertEquals(Resolved(VoiceEngine.Kokoro, "af_heart"), VoiceLanguages.resolve("en", emptyMap(), rows, everything))
        assertEquals(Resolved(VoiceEngine.Supertonic, "M2"), VoiceLanguages.resolve("nl", emptyMap(), rows, everything))
        // Kokoro still downloading: Supertonic reads English meanwhile.
        assertEquals(Resolved(VoiceEngine.Supertonic, "M2"), VoiceLanguages.resolve("en", emptyMap(), rows) { it != VoiceEngine.Kokoro })
        assertEquals(Resolved(VoiceEngine.System, null), VoiceLanguages.resolve("nl", emptyMap(), rows, nothing))
        // Supertonic doesn't read Chinese.
        assertEquals(Resolved(VoiceEngine.System, null), VoiceLanguages.resolve("zh", emptyMap(), rows, everything))
    }

    @Test
    fun aChosenEngineIsKeptOrLeftToAndroid() {
        val rows = listOf("nl", "en")
        val voices = mapOf("en" to LanguageVoice(VoiceEngine.Supertonic, "F3"), "nl" to LanguageVoice(VoiceEngine.Kokoro))
        assertEquals(Resolved(VoiceEngine.Supertonic, "F3"), VoiceLanguages.resolve("en", voices, rows, everything))
        // Kokoro can't read Dutch, and a chosen engine that isn't downloaded doesn't silently become another.
        assertEquals(Resolved(VoiceEngine.System, null), VoiceLanguages.resolve("nl", voices, rows, everything))
        assertEquals(Resolved(VoiceEngine.System, null), VoiceLanguages.resolve("en", voices, rows, nothing))
    }

    @Test
    fun aVoiceTheEngineDoesntHaveFallsBackToItsDefault() {
        val voices = mapOf("en" to LanguageVoice(voice = "am_fenrir"))
        assertEquals(Resolved(VoiceEngine.Kokoro, "am_fenrir"), VoiceLanguages.resolve("en", voices, listOf("en"), everything))
        assertEquals(Resolved(VoiceEngine.Supertonic, "M2"), VoiceLanguages.resolve("en", voices, listOf("en")) { it != VoiceEngine.Kokoro })
    }

    @Test
    fun languagesWithoutARowFollowOtherLanguages() {
        val rows = listOf("nl", "en")
        val voices = mapOf(OTHER to LanguageVoice(VoiceEngine.System))
        assertEquals(Resolved(VoiceEngine.System, null), VoiceLanguages.resolve("de", voices, rows, everything))
        // A language with a row stays Automatic when Other languages isn't.
        assertEquals(Resolved(VoiceEngine.Supertonic, "M2"), VoiceLanguages.resolve("nl", voices, rows, everything))
        val styled = mapOf(OTHER to LanguageVoice(voice = "F1"))
        assertEquals(Resolved(VoiceEngine.Supertonic, "F1"), VoiceLanguages.resolve("fr", styled, rows, everything))
    }

    @Test
    fun onlyTheRowsLanguagesDownloadModels() {
        assertEquals(setOf(VoiceEngine.Kokoro), VoiceLanguages.needed(listOf("en"), emptyMap()))
        assertEquals(setOf(VoiceEngine.Kokoro, VoiceEngine.Supertonic), VoiceLanguages.needed(listOf("de", "en"), emptyMap()))
        assertEquals(emptySet<VoiceEngine>(), VoiceLanguages.needed(listOf("en"), mapOf("en" to LanguageVoice(VoiceEngine.System))))
        // Picking Supertonic for Other languages fetches it; Automatic there doesn't.
        assertEquals(setOf(VoiceEngine.Kokoro, VoiceEngine.Supertonic), VoiceLanguages.needed(listOf("en"), mapOf(OTHER to LanguageVoice(VoiceEngine.Supertonic))))
        assertEquals(setOf(VoiceEngine.Kokoro), VoiceLanguages.needed(listOf("en"), mapOf(OTHER to LanguageVoice(voice = "F1"))))
    }

    @Test
    fun languageTagsBecomeCodes() {
        assertEquals("nl", VoiceLanguages.code("nl-NL"))
        assertEquals("en", VoiceLanguages.code("en_GB"))
        assertEquals("id", VoiceLanguages.code("in-ID"))
        assertEquals("he", VoiceLanguages.code("iw-IL"))
    }

    @Test
    fun voicesSurviveASaveAndLoad() {
        val voices = mapOf("en" to LanguageVoice(VoiceEngine.Kokoro, "am_fenrir"), "nl" to LanguageVoice(voice = "M1"), OTHER to LanguageVoice(VoiceEngine.System))
        assertEquals(voices, decodeVoices(encodeVoices(voices)))
        // Automatic everywhere saves nothing.
        assertEquals("", encodeVoices(mapOf("de" to LanguageVoice())))
        assertEquals(emptyMap<String, LanguageVoice>(), decodeVoices(""))
        // An engine this version doesn't know is Automatic.
        assertEquals(mapOf("en" to LanguageVoice(voice = "af_bella")), decodeVoices("en:Piper:af_bella,broken"))
    }

    @Test
    fun theOldEnglishAndDutchSettingsCarryOver() {
        assertEquals(
            mapOf("en" to LanguageVoice(voice = "am_fenrir"), "nl" to LanguageVoice(voice = "M1")),
            legacyVoices("Kokoro", "am_fenrir", "Supertonic", "M1"),
        )
        assertEquals(mapOf("nl" to LanguageVoice(VoiceEngine.System)), legacyVoices(null, null, "System", null))
        assertEquals(emptyMap<String, LanguageVoice>(), legacyVoices(null, null, null, null))
    }

    @Test
    fun everySupertonicLanguageHasAPreviewSentence() {
        assertTrue(VoiceSamples.languages.containsAll(VoiceLanguages.SUPERTONIC))
        assertEquals("Hi Bart, this is how I sound when I read a reply aloud.", VoiceSamples.text("en", "Bart"))
        assertEquals("Hallo, so klinge ich, wenn ich eine Antwort auf Deutsch vorlese.", VoiceSamples.text("de", " "))
        assertEquals("こんにちは。日本語で返事を読み上げると、こんな声になります。", VoiceSamples.text("ja", "Bart"))
    }
}
