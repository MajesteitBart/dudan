package nl.bartvandermeeren.dudan

import android.speech.SpeechRecognizer
import nl.bartvandermeeren.dudan.voice.LocalSpeechSession.StartFailure
import nl.bartvandermeeren.dudan.voice.SpeechInput
import nl.bartvandermeeren.dudan.voice.SpeechInput.StartOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechStartTest {
    @Test
    fun aBusyMicrophoneOrBrokenDetectorGoesToThePhonesRecognizer() {
        assertEquals(StartOutcome.PhoneRecognizer, SpeechInput.startOutcome(StartFailure.Microphone, stopped = false))
        assertEquals(StartOutcome.PhoneRecognizer, SpeechInput.startOutcome(StartFailure.Detector, stopped = false))
    }

    @Test
    fun aCallEndsVoiceInputInsteadOfRecordingTheCall() {
        assertEquals(StartOutcome.End(SpeechRecognizer.ERROR_AUDIO), SpeechInput.startOutcome(StartFailure.Call, stopped = false))
    }

    @Test
    fun aStopWhileOrukeetStartsEndsVoiceInputAsSilence() {
        StartFailure.entries.forEach { failure ->
            val outcome = SpeechInput.startOutcome(failure, stopped = true)
            assertEquals(failure.name, StartOutcome.End(SpeechRecognizer.ERROR_NO_MATCH), outcome)
            // Silence: the overlay doesn't try again, and the app sends a message that waited for the stop.
            assertTrue(failure.name, SpeechInput.isSilence((outcome as StartOutcome.End).code))
        }
    }
}
