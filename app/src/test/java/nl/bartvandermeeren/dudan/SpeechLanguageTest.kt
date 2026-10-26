package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.voice.LanguagePrimer
import nl.bartvandermeeren.dudan.voice.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLanguageTest {
    @Test
    fun everyWayOfWritingDutchBecomesTheSameTag() {
        listOf("nl-NL", "nl_NL", "nl", "NL", "Dutch", "dutch", "Nederlands", " nl-nl ").forEach {
            assertEquals(it, "nl-NL", SpeechLanguage.normalize(it))
        }
        assertEquals("nl-BE", SpeechLanguage.normalize("nl-BE"))
        assertEquals("en-GB", SpeechLanguage.normalize("en_GB"))
        assertEquals("en-US", SpeechLanguage.normalize("English"))
        assertEquals("sv", SpeechLanguage.normalize("sv"))
    }

    @Test
    fun scriptsAndVariantsAreKept() {
        assertEquals("sr-Latn", SpeechLanguage.normalize("sr-Latn"))
        assertEquals("sr-Latn-RS", SpeechLanguage.normalize("sr_Latn_RS"))
        assertEquals("zh-Hant", SpeechLanguage.normalize("zh-Hant"))
        assertEquals("zh-Hant-TW", SpeechLanguage.normalize("zh-hant-tw"))
        assertEquals("ca-ES-valencia", SpeechLanguage.normalize("ca-ES-valencia"))
        // A language with a script is already what was meant: no region is added to it.
        assertEquals("pt-Latn", SpeechLanguage.normalize("pt-Latn"))
        // Only a bare language gets one.
        assertEquals("pt-PT", SpeechLanguage.normalize("pt"))
        assertEquals("pt-BR", SpeechLanguage.normalize("pt-BR"))
        assertEquals("nl-NL-u-ca-gregory", SpeechLanguage.normalize("nl-u-ca-gregory"))
    }

    @Test
    fun blankOrUnknownLeavesItToTheRecognizer() {
        assertNull(SpeechLanguage.normalize(null))
        assertNull(SpeechLanguage.normalize(""))
        assertNull(SpeechLanguage.normalize("   "))
        assertNull(SpeechLanguage.normalize("Klingon"))
    }

    @Test
    fun theLanguageClipFollowsTheNormalizedSetting() {
        assertEquals("nl", LanguagePrimer.languageOf("Nederlands"))
        assertEquals("en", LanguagePrimer.languageOf("english"))
        assertNull(LanguagePrimer.languageOf("de-DE"))
    }

    @Test
    fun languagesCompareWithoutTheirRegion() {
        assertTrue(SpeechLanguage.sameLanguage("nl-NL", "nl-BE"))
        assertFalse(SpeechLanguage.sameLanguage("nl-NL", "en-US"))
        assertEquals("Dutch", SpeechLanguage.englishName("nl-NL"))
    }
}
