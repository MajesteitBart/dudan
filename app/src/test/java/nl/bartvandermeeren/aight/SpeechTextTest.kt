package nl.bartvandermeeren.aight

import nl.bartvandermeeren.aight.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTextTest {
    @Test
    fun listItemsAndHeadingsGetAPause() {
        val text = SpeechText.withPauses(SpeechText.fromMarkdown("## Today\n\n- Standup at 9\n- Lunch with Anna"))
        assertEquals("Today.\nStandup at 9.\nLunch with Anna.", text)
    }

    @Test
    fun shortRepliesArePlacedByLanguage() {
        assertEquals("nl-NL", SpeechText.guessLanguage("Goedemorgen!"))
        assertEquals("nl-NL", SpeechText.guessLanguage("Prima, gedaan."))
        assertEquals("en-US", SpeechText.guessLanguage("Thanks, done."))
        // Nothing to go on: the caller falls back to the speech input or phone language.
        assertNull(SpeechText.guessLanguage("OK"))
    }

    @Test
    fun thePreviewSentencesPickTheirOwnVoice() {
        assertEquals("en-US", SpeechText.guessLanguage("Hi Bart, this is how I sound when I read a reply aloud."))
        assertEquals("nl-NL", SpeechText.guessLanguage("Hoi Bart, zo klink ik als ik een antwoord in het Nederlands voorlees."))
    }

    @Test
    fun eachSentenceStreamsAloneAndShortOnesJoinTheNext() {
        val text = "Hoi Bart, je volgende afspraak begint om half drie. Wil je de punten horen? Ik heb er drie. En een vraag."
        assertEquals(
            listOf("Hoi Bart, je volgende afspraak begint om half drie.", "Wil je de punten horen? Ik heb er drie.", "En een vraag."),
            SpeechText.streamingChunks(text, 200),
        )
    }

    @Test
    fun aVeryShortFirstSentenceTakesTheNextAlong() {
        assertEquals(listOf("Oké. Ik zet het in je agenda.", "Nog iets?"), SpeechText.streamingChunks("Oké. Ik zet het in je agenda. Nog iets?", 30))
    }

    @Test
    fun aShortFirstSentenceStaysAloneWhenTheNextIsLong() {
        val second = "Dit is een testantwoord van de mock-server op een vraag over de trein van vanmiddag om kwart over drie."
        assertEquals(listOf("Goede vraag.", second), SpeechText.streamingChunks("Goede vraag. $second", 200))
    }

    @Test
    fun longSentencesAreCutAfterACommaWithinTheLimit() {
        val chunks = SpeechText.streamingChunks("Dit is een lange zin met veel woorden, en daarna komt er nog een stuk tekst achteraan.", 50)
        assertEquals(listOf("Dit is een lange zin met veel woorden,", "en daarna komt er nog een stuk tekst achteraan."), chunks)
    }

    @Test
    fun linesThatAlreadyEndASentenceStayAsTheyAre() {
        val text = "Here's your agenda:\nIs that right?\nShe said \"fine.\"\n(see above)"
        assertEquals("Here's your agenda:\nIs that right?\nShe said \"fine.\"\n(see above).", SpeechText.withPauses(text))
    }
}
