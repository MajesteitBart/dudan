package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.voice.LanguageGuess
import nl.bartvandermeeren.dudan.voice.VoiceLanguages
import nl.bartvandermeeren.dudan.voice.VoiceSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {
    @Test
    fun shortRepliesArePlacedByLanguage() {
        assertEquals("nl", LanguageGuess.guess("Goedemorgen!"))
        assertEquals("nl", LanguageGuess.guess("Prima, gedaan."))
        assertEquals("en", LanguageGuess.guess("Thanks, done."))
        assertEquals("de", LanguageGuess.guess("Danke, erledigt."))
        // Nothing to go on: the caller falls back to the speech input or phone language.
        assertNull(LanguageGuess.guess("OK"))
        // Words English uses as much as the others don't count for any of them.
        assertNull(LanguageGuess.guess("In Amsterdam."))
        assertEquals("nl", LanguageGuess.guess("Het was leuk."))
    }

    @Test
    fun aTieGoesToThePhonesLanguage() {
        // "morgen" is Dutch and German.
        assertNull(LanguageGuess.guess("Of morgen."))
        assertEquals("nl", LanguageGuess.guess("Of morgen.", listOf("nl", "en")))
        assertEquals("de", LanguageGuess.guess("Of morgen.", listOf("de", "en")))
        // A preference outside the tie doesn't decide it.
        assertNull(LanguageGuess.guess("Of morgen.", listOf("fr")))
    }

    @Test
    fun germanIsNotReadAsDutch() {
        // "die" is on the Dutch list too; before German had its own, replies like this went to the Dutch voice.
        val reply = "Die Besprechung ist morgen um 10 Uhr. Ich habe die Unterlagen schon geschickt, und du findest sie im Ordner."
        assertEquals("de", LanguageGuess.guess(reply, listOf("nl", "en")))
    }

    @Test
    fun englishWithAPronounStaysEnglish() {
        assertEquals("en", LanguageGuess.guess("I can do that for you tomorrow, just say the word."))
        assertEquals("en", LanguageGuess.guess("Going to bed.", listOf("nl", "en")))
    }

    @Test
    fun wordsEnglishOrDutchShareWithOtherLanguagesOnlyTie() {
        val phone = listOf("nl", "en")
        // "er" and "om" are Danish too, "om" Swedish as well.
        assertEquals("nl", LanguageGuess.guess("Ze is er om zeven uur.", phone))
        assertEquals("nl", LanguageGuess.guess("Kan er om 8 uur.", phone))
        // "na" and "de" are both Portuguese.
        assertEquals("nl", LanguageGuess.guess("Na de lunch.", phone))
        // "care" is Romanian, "do" Portuguese, "son" Spanish, "pro" Czech, and "I've" leaves Turkish "ve".
        assertEquals("en", LanguageGuess.guess("Take care!", phone))
        assertEquals("en", LanguageGuess.guess("How do I do that?", phone))
        assertEquals("en", LanguageGuess.guess("My son is sick.", phone))
        assertEquals("en", LanguageGuess.guess("Pro tip: use dark mode.", phone))
        assertEquals("en", LanguageGuess.guess("I've booked.", phone))
    }

    @Test
    fun everyPreviewSentenceIsHeardInItsOwnLanguageOnADutchPhone() {
        (VoiceLanguages.SUPERTONIC + "en").forEach { language ->
            assertEquals(language, LanguageGuess.guess(VoiceSamples.text(language, "Bart"), listOf("nl", "en")))
        }
    }

    @Test
    fun scriptsSettleTheirLanguages() {
        assertEquals("ru", LanguageGuess.guess("Это ответ на твой вопрос."))
        assertEquals("uk", LanguageGuess.guess("Це відповідь на твоє питання."))
        assertEquals("bg", LanguageGuess.guess("Това е отговорът на въпроса ти."))
        assertEquals("ja", LanguageGuess.guess("明日の会議は十時からです。"))
        assertEquals("zh", LanguageGuess.guess("明天的会议十点开始。"))
        assertEquals("ko", LanguageGuess.guess("내일 회의는 열 시에 시작합니다."))
        // Code names in a Japanese reply don't make it English.
        assertEquals("ja", LanguageGuess.guess("ChatEngine と SettingsRepository を更新しました。"))
    }

    @Test
    fun everyPreviewSentenceIsHeardInItsOwnLanguage() {
        (VoiceLanguages.SUPERTONIC + "en").forEach { language ->
            assertEquals(language, LanguageGuess.guess(VoiceSamples.text(language, "Bart")))
        }
    }
}
