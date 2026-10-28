package nl.bartvandermeeren.dudan.desktop

import nl.bartvandermeeren.dudan.voice.DesktopSpeaker
import nl.bartvandermeeren.dudan.voice.DesktopSpeaker.Choice
import nl.bartvandermeeren.dudan.voice.DesktopSpeaker.Engine
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopSpeakerTest {
    @Test
    fun englishGoesToKokoroAndDutchToSupertonic() {
        assertEquals(Choice.Use(Engine.Kokoro), DesktopSpeaker.choose("en", kokoroReady = true, supertonicReady = true))
        assertEquals(Choice.Use(Engine.Supertonic), DesktopSpeaker.choose("nl", kokoroReady = true, supertonicReady = true))
    }

    @Test
    fun supertonicReadsEnglishUntilKokoroIsIn() {
        assertEquals(Choice.Use(Engine.Supertonic), DesktopSpeaker.choose("en", kokoroReady = false, supertonicReady = true))
        assertEquals(Choice.Missing(Engine.Kokoro), DesktopSpeaker.choose("en", kokoroReady = false, supertonicReady = false))
        assertEquals(Choice.Missing(Engine.Supertonic), DesktopSpeaker.choose("nl", kokoroReady = true, supertonicReady = false))
    }

    @Test
    fun aLanguageNeitherVoiceHasIsSaidSo() {
        assertEquals(Choice.Unsupported, DesktopSpeaker.choose("zh", kokoroReady = true, supertonicReady = true))
    }

    @Test
    fun theReplyDecidesItsLanguageAndWindowsSettlesTheRest() {
        assertEquals("nl", DesktopSpeaker.languageOf("Ik heb de afspraak voor morgen om tien uur in je agenda gezet.", windowsLanguage = "en"))
        assertEquals("en", DesktopSpeaker.languageOf("I put the meeting for tomorrow at ten in your calendar.", windowsLanguage = "nl"))
        assertEquals("nl", DesktopSpeaker.languageOf("OK", windowsLanguage = "nl"))
    }
}
