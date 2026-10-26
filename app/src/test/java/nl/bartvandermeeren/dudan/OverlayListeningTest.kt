package nl.bartvandermeeren.dudan

import android.speech.SpeechRecognizer
import nl.bartvandermeeren.dudan.assist.AssistState
import nl.bartvandermeeren.dudan.assist.AssistState.AfterError
import nl.bartvandermeeren.dudan.assist.Openings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayListeningTest {
    private val silence = listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    private val hiccups = listOf(SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_NETWORK)

    @Test
    fun thePhonesRecognizerListensAgainForAQuestionItMissedAfterTheWakeWord() {
        silence.forEach { code ->
            assertEquals(AfterError.ListenAgain, AssistState.afterError(code, retries = 0, wakeWord = true, phoneRecognizer = true))
        }
    }

    @Test
    fun silenceAfterTheWakeWordEndsListeningWithOrukeet() {
        // Orukeet got the audio after the phrase, so it heard everything that was said.
        silence.forEach { code ->
            assertEquals(AfterError.End, AssistState.afterError(code, retries = 0, wakeWord = true, phoneRecognizer = false))
        }
    }

    @Test
    fun silenceWithoutTheWakeWordEndsListening() {
        // Also the second try after a wake word, and listening stopped by hand: the overlay passes those on as no wake word.
        silence.forEach { code ->
            assertEquals(AfterError.End, AssistState.afterError(code, retries = 0, wakeWord = false, phoneRecognizer = true))
            assertEquals(AfterError.End, AssistState.afterError(code, retries = 0, wakeWord = false, phoneRecognizer = false))
        }
    }

    @Test
    fun anythingButSilenceGetsTwoMoreTries() {
        for (code in hiccups) {
            for (wakeWord in listOf(true, false)) {
                for (phoneRecognizer in listOf(true, false)) {
                    val after = { retries: Int -> AssistState.afterError(code, retries, wakeWord, phoneRecognizer) }
                    assertEquals(AfterError.Retry, after(0))
                    assertEquals(AfterError.Retry, after(AssistState.MAX_RETRIES - 1))
                    assertEquals(AfterError.End, after(AssistState.MAX_RETRIES))
                }
            }
        }
    }

    @Test
    fun workForTheOpenOverlayRuns() {
        val openings = Openings()
        val opening = openings.open()
        assertTrue(openings.isCurrent(opening))
        // A retry scheduled now belongs to this opening.
        assertTrue(openings.isCurrent(openings.latest))
    }

    @Test
    fun workForAnEarlierOpeningDoesNotRunWhenTheOverlayOpensAgainWhileOpen() {
        // The side key, the wake word or Clark while the overlay is still open: the new chat does the listening.
        val openings = Openings()
        val first = openings.open()
        val second = openings.open()
        assertFalse(openings.isCurrent(first))
        assertTrue(openings.isCurrent(second))
    }

    @Test
    fun workDoesNotRunAfterTheOverlayClosed() {
        // Settings that finish loading, or a retry that comes due, after the overlay was dismissed.
        val openings = Openings()
        val opening = openings.open()
        openings.close()
        assertFalse(openings.isCurrent(opening))
    }

    @Test
    fun workFromBeforeAClosedOverlayDoesNotRunWhenItOpensAgain() {
        val openings = Openings()
        val first = openings.open()
        openings.close()
        val second = openings.open()
        assertFalse(openings.isCurrent(first))
        assertTrue(openings.isCurrent(second))
    }
}
