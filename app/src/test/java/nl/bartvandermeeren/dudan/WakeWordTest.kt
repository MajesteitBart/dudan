package nl.bartvandermeeren.dudan

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import nl.bartvandermeeren.dudan.voice.LocalSpeechSession
import nl.bartvandermeeren.dudan.voice.SentencePieces
import nl.bartvandermeeren.dudan.voice.WakeMic
import nl.bartvandermeeren.dudan.voice.WakePhrase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    /**
     * Every piece of the spotter's bpe.model that occurs in the test words, with its real score. The
     * expected splits are what sentencepiece itself returns for that model.
     */
    private val pieces = SentencePieces(
        mapOf(
            "A" to -4.752318f, "AN" to -5.9806376f, "AR" to -5.2759385f, "AS" to -6.538439f, "C" to -4.6636534f,
            "CH" to -5.559915f, "D" to -4.2264657f, "E" to -4.197028f, "EL" to -6.3479123f, "ER" to -5.2273073f,
            "G" to -5.0244603f, "H" to -6.1747804f, "HE" to -7.1056762f, "I" to -5.194803f, "IS" to -6.704092f,
            "IST" to -6.833780f, "K" to -5.440167f, "L" to -5.0477295f, "LA" to -6.2793384f, "LE" to -5.3424845f,
            "LL" to -5.572025f, "LO" to -6.2875085f, "M" to -4.5914583f, "MA" to -6.8396025f, "ME" to -6.416845f,
            "N" to -4.1642156f, "O" to -4.6626496f, "OR" to -5.266681f, "R" to -4.7054667f, "RI" to -5.532841f,
            "RY" to -6.3806176f, "S" to -2.973654f, "T" to -3.5682678f, "U" to -4.803084f, "W" to -5.4855719f,
            "Y" to -4.518289f, "▁" to -4.624164f, "▁C" to -5.2457824f, "▁DU" to -7.8812284f,
            "▁G" to -6.10704f, "▁GO" to -6.406029f, "▁HE" to -5.315312f, "▁HI" to -7.7755055f,
            "▁ME" to -5.754178f, "▁O" to -6.354817f, "▁OKAY" to -8.292519f, "▁W" to -5.7250566f,
            "▁WORLD" to -7.7006516f,
        ),
    )

    @Test
    fun wordsSplitIntoTheSamePiecesAsSentencepiece() {
        val expected = mapOf(
            "HELLO" to "▁HE LL O",
            "WORLD" to "▁WORLD",
            "GOOGLE" to "▁GO O G LE",
            "MERRY" to "▁ME R RY",
            "CHRISTMAS" to "▁ CH R IST MA S",
            "OK" to "▁O K",
            "OKAY" to "▁OKAY",
            "CLARK" to "▁C L AR K",
            "HEY" to "▁HE Y",
            "DUDAN" to "▁DU D AN",
        )
        expected.forEach { (word, split) -> assertEquals(word, split, pieces.encode(word)?.joinToString(" ")) }
    }

    @Test
    fun aLetterWithoutAPieceCantBeSpelled() {
        assertNull(pieces.encode("QUIZ"))
    }

    @Test
    fun okClarkListensForBothSpellingsOfOk() {
        val keywords = WakePhrase.keywords("Ok Clark", pieces)
        assertEquals("▁O K ▁C L AR K @OK_CLARK\n▁OKAY ▁C L AR K @OKAY_CLARK\n", keywords)
    }

    @Test
    fun phrasesAreWrittenInTheModelsAlphabet() {
        assertEquals("HE DUDAN", WakePhrase.normalize("Hé, dudan!"))
        assertEquals("OK CLARK", WakePhrase.normalize("  ok   clark "))
        assertTrue(WakePhrase.isUsable("Ok Clark"))
        assertFalse(WakePhrase.isUsable("Ok"))
        assertFalse(WakePhrase.isUsable("!!!"))
        assertNull(WakePhrase.keywords("Hi", pieces))
    }

    @Test
    fun aPhraseHasAtMostFiveWords() {
        assertNull(WakePhrase.problem("Ok Clark"))
        assertEquals(WakePhrase.Problem.TooShort, WakePhrase.problem("Ok"))
        // Punctuation and spaces don't make words.
        assertNull(WakePhrase.problem(" Hey, ok... hey - ok  hey! "))
        assertEquals(WakePhrase.Problem.TooLong, WakePhrase.problem("Hey ok hey ok hey ok"))
        assertFalse(WakePhrase.isUsable("Hey hey hey hey hey hey"))
        // Twenty words with two spellings each would be a million lines; it's turned down instead.
        assertNull(WakePhrase.keywords(List(20) { "hey" }.joinToString(" "), pieces))
    }

    @Test
    fun repeatedAlternateSpellingsStayWithinTheBudget() {
        val heyOkHey = WakePhrase.keywords("Hey ok hey", pieces)!!.trimEnd().lines()
        assertEquals(WakePhrase.MAX_VARIANTS, heyOkHey.size)
        assertEquals(heyOkHey.distinct(), heyOkHey)
        assertTrue(heyOkHey.contains("▁ H A Y ▁OKAY ▁ H A Y @HAY_OKAY_HAY"))
        // Five words with two spellings each: the later words keep their written spelling.
        val okOk = WakePhrase.keywords("Ok ok ok ok ok", pieces)!!.trimEnd().lines()
        assertEquals(WakePhrase.MAX_VARIANTS, okOk.size)
        assertEquals(okOk.distinct(), okOk)
        assertEquals("▁O K ▁O K ▁O K ▁O K ▁O K @OK_OK_OK_OK_OK", okOk.first())
        assertTrue(okOk.all { it.endsWith("_OK_OK") })
    }

    @Test
    fun theWakePhraseIsDroppedFromTheFrontOfTheQuestion() {
        assertEquals("What time is it?", WakePhrase.stripLeading("Clark, what time is it?", "Ok Clark"))
        assertEquals("What time is it?", WakePhrase.stripLeading("Okay Clark, what time is it?", "Ok Clark"))
        assertEquals("Wat is het weer?", WakePhrase.stripLeading("OK Clark. Wat is het weer?", "Ok Clark"))
        // A Dutch decode, words heard before the phrase, and nothing but the phrase.
        assertEquals("Hoe laat is het?", WakePhrase.stripLeading("Oké Klark, hoe laat is het?", "Ok Clark"))
        assertEquals("What time is it?", WakePhrase.stripLeading("So. Ok Clark, what time is it?", "Ok Clark"))
        assertEquals("", WakePhrase.stripLeading("Okay Clark.", "Ok Clark"))
        // Only in front, and only whole words of the phrase.
        assertEquals("Ok, set a timer", WakePhrase.stripLeading("Ok, set a timer", "Ok Clark"))
        assertEquals("Call Clark", WakePhrase.stripLeading("Call Clark", "Ok Clark"))
        assertEquals("Clarkson is on", WakePhrase.stripLeading("Clarkson is on", "Ok Clark"))
    }

    @Test
    fun piecesAreReadFromASentencepieceModel() {
        val model = ByteArrayOutputStream().apply {
            // trainer_spec (field 2) comes before the pieces in real models; it's skipped.
            field(2, message { field(3, "unigram".toByteArray()) })
            field(1, piece("<blk>", 0f, type = 4))
            field(1, piece("▁C", -5.2f))
            field(1, piece("L", -5.0f))
            field(1, piece("AR", -5.3f))
            field(1, piece("K", -5.4f))
        }.toByteArray()
        val parsed = SentencePieces.parse(model)
        assertEquals(listOf("▁C", "L", "AR", "K"), parsed.encode("CLARK"))
        assertNull(parsed.encode("BLK"))
    }

    @Test
    fun audioAfterTheWakeWordReachesTheRecordingItOpened() {
        val handoff = WakeMic.Handoff(silenced = { false })
        WakeMic.offer(handoff)
        handoff.push(ShortArray(1600) { 1 })
        handoff.push(ShortArray(1600) { 2 })
        val source = WakeMic.acquire()
        try {
            assertSame(handoff, source)
            val window = ShortArray(512)
            repeat(3) { assertTrue(source!!.read(window)) }
            // The fourth window spans both chunks.
            assertTrue(source!!.read(window))
            assertEquals(1.toShort(), window[0])
            assertEquals(2.toShort(), window[511])
            assertTrue(handoff.wanted)
            source.close()
            assertFalse(handoff.wanted)
        } finally {
            WakeMic.release()
        }
        assertFalse(WakeMic.held)
    }

    @Test
    fun thePhonesRecognizerLeavesTheAudioAfterTheWakeWord() {
        val handoff = WakeMic.Handoff(silenced = { false })
        WakeMic.offer(handoff)
        handoff.push(ShortArray(1600))
        WakeMic.pause()
        try {
            assertTrue(WakeMic.held)
            // The listener stops recording for it; that recognizer only hears what's said once it listens.
            assertFalse(handoff.wanted)
        } finally {
            WakeMic.release()
        }
        assertFalse(WakeMic.held)
    }

    @Test
    fun aRecordingWithoutAWakeWordWaitsForTheListenerToLetGo() {
        var interrupted = false
        WakeMic.listenerMicrophone { interrupted = true }
        val closer = thread {
            Thread.sleep(50)
            WakeMic.listenerMicrophone(null)
        }
        val started = System.nanoTime()
        try {
            assertNull(WakeMic.acquire())
            assertTrue(WakeMic.held)
            // A listener stuck in a read is stopped rather than waited for.
            assertTrue(interrupted)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 600)
        } finally {
            WakeMic.release()
            closer.join()
        }
    }

    @Test
    fun aListenerThatFailsToStartLetsGoOfItsMicrophone() {
        var closed = false
        var interrupted = false
        val microphone = object : LocalSpeechSession.AudioSource {
            override fun read(window: ShortArray) = true

            override fun interrupt() {
                interrupted = true
            }

            override fun close() {
                closed = true
            }
        }
        // The spotter's native stream fails to be created after the microphone opened.
        val failure = runCatching { WakeMic.listenOn(microphone) { error("no stream") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(closed)
        // Voice input neither waits for a listener that's gone nor stops its microphone.
        val started = System.nanoTime()
        try {
            assertNull(WakeMic.acquire())
            assertFalse(interrupted)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 300)
        } finally {
            WakeMic.release()
        }
    }

    @Test
    fun aListenerThatLosesItsMicrophoneEndsTheRecording() {
        val handoff = WakeMic.Handoff(silenced = { false })
        assertTrue(WakeMic.run { offer(handoff); acquire() } === handoff)
        try {
            handoff.push(ShortArray(512) { 7 })
            handoff.end()
            val window = ShortArray(512)
            assertTrue(handoff.read(window))
            assertArrayEquals(ShortArray(512) { 7 }, window)
            assertFalse(handoff.read(window))
        } finally {
            WakeMic.release()
        }
    }

    private fun piece(text: String, score: Float, type: Int = 1) = message {
        field(1, text.toByteArray())
        write(0x15) // field 2, fixed32
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(score).array())
        write(0x18) // field 3, varint
        write(type)
    }

    private fun message(build: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(build).toByteArray()

    private fun ByteArrayOutputStream.field(number: Int, bytes: ByteArray) {
        write(number shl 3 or 2)
        var length = bytes.size
        while (length >= 0x80) {
            write(length and 0x7f or 0x80)
            length = length ushr 7
        }
        write(length)
        write(bytes)
    }
}
