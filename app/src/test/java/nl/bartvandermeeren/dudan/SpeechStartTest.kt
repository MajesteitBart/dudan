package nl.bartvandermeeren.dudan

import android.speech.SpeechRecognizer
import nl.bartvandermeeren.dudan.voice.LocalSpeechSession.StartFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechStartTest {
    @Test
    fun aMicrophoneThatWontOpenGoesToThePhonesRecognizer() {
        assertTrue(StartFailure.Microphone.phoneRecognizerCanTry)
        assertEquals(SpeechRecognizer.ERROR_AUDIO, StartFailure.Microphone.code)
        assertTrue(StartFailure.Detector.phoneRecognizerCanTry)
    }

    @Test
    fun aCallEndsVoiceInputInsteadOfRecordingTheCall() {
        assertFalse(StartFailure.Call.phoneRecognizerCanTry)
        assertEquals(SpeechRecognizer.ERROR_AUDIO, StartFailure.Call.code)
    }
}
