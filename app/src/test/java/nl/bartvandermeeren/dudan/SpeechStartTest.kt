package nl.bartvandermeeren.dudan

import android.speech.SpeechRecognizer
import nl.bartvandermeeren.dudan.voice.LocalSpeechSession
import nl.bartvandermeeren.dudan.voice.LocalSpeechSession.StartFailure
import nl.bartvandermeeren.dudan.voice.SpeechInput
import nl.bartvandermeeren.dudan.voice.SpeechInput.StartOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechStartTest {
    private class Source : LocalSpeechSession.AudioSource {
        var closed = 0

        override fun read(window: ShortArray) = true

        override fun close() {
            closed++
        }
    }

    private class Detector {
        var released = 0
    }

    private val failures = mutableListOf<StartFailure>()

    private fun record(
        openSource: () -> LocalSpeechSession.AudioSource?,
        newDetector: () -> Detector,
        record: (LocalSpeechSession.AudioSource, Detector) -> Unit = { _, _ -> fail("recorded after a failed start") },
    ) = LocalSpeechSession.recordWithInputs(
        openSource = openSource,
        newDetector = newDetector,
        releaseDetector = { it.released++ },
        startFailed = { failure, _ -> failures += failure },
        record = record,
    )

    @Test
    fun aMicrophoneThatWontOpenIsAStartFailure() {
        var detectorLoaded = false
        record(openSource = { null }, newDetector = { detectorLoaded = true; Detector() })
        assertEquals(listOf(StartFailure.Microphone), failures)
        assertEquals(false, detectorLoaded)
    }

    @Test
    fun aDetectorThatWontLoadIsAStartFailureAndClosesTheMicrophone() {
        val source = Source()
        var cause: Throwable? = null
        LocalSpeechSession.recordWithInputs<Detector>(
            openSource = { source },
            newDetector = { throw IllegalStateException("no model") },
            releaseDetector = { fail("released a detector that never loaded") },
            startFailed = { failure, error ->
                failures += failure
                cause = error
            },
            record = { _, _ -> fail("recorded after a failed start") },
        )
        assertEquals(listOf(StartFailure.Detector), failures)
        assertEquals("no model", cause?.message)
        assertEquals(1, source.closed)
    }

    @Test
    fun aRecordingGetsBothInputsAndClosesThemAfterwards() {
        val source = Source()
        val detector = Detector()
        var recorded = false
        record(openSource = { source }, newDetector = { detector }, record = { from, with ->
            assertSame(source, from)
            assertSame(detector, with)
            assertEquals(0, source.closed)
            recorded = true
        })
        assertTrue(recorded)
        assertEquals(emptyList<StartFailure>(), failures)
        assertEquals(1, source.closed)
        assertEquals(1, detector.released)
    }

    @Test
    fun aRecordingThatBreaksStillClosesBothInputs() {
        val source = Source()
        val detector = Detector()
        try {
            record(openSource = { source }, newDetector = { detector }, record = { _, _ -> error("broke") })
            fail("the error got lost")
        } catch (e: IllegalStateException) {
            assertEquals("broke", e.message)
        }
        assertEquals(1, source.closed)
        assertEquals(1, detector.released)
    }

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
