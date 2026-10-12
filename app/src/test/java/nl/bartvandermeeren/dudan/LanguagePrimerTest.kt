package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.voice.LanguagePrimer
import nl.bartvandermeeren.dudan.voice.OrukeetEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class LanguagePrimerTest {
    /** Orukeet's tokens for the Dutch clip, as it decodes them in front of an utterance. */
    private val clipTokens = listOf(" De" to 0.08f, " winter" to 0.24f, " ver" to 0.64f, "z" to 0.8f, "acht" to 0.88f, "e" to 1.04f, " zich" to 1.2f)

    private fun decoded(boundary: Float, vararg afterBoundary: Pair<String, Float>): Pair<Array<String>, FloatArray> {
        val all = clipTokens + afterBoundary.map { (token, offset) -> token to boundary + offset }
        return all.map { it.first }.toTypedArray() to all.map { it.second }.toFloatArray()
    }

    @Test
    fun speechLanguageSettingPicksTheClip() {
        assertEquals("nl", LanguagePrimer.languageOf("nl-NL"))
        assertEquals("nl", LanguagePrimer.languageOf("nl_BE"))
        assertEquals("nl", LanguagePrimer.languageOf(" NL "))
        assertEquals("en", LanguagePrimer.languageOf("en-US"))
        // No clip, or no setting: Orukeet works the language out itself.
        assertNull(LanguagePrimer.languageOf("de-DE"))
        assertNull(LanguagePrimer.languageOf("Dutch"))
        assertNull(LanguagePrimer.languageOf(""))
        assertNull(LanguagePrimer.languageOf(null))
    }

    @Test
    fun transcriptDropsTheClipAndItsFullStop() {
        val boundary = 2.1f
        // Orukeet emits the clip's full stop at the very start of the silence, past the boundary.
        val (tokens, times) = decoded(boundary, "." to 0.01f, " Ho" to 0.25f, "e" to 0.41f, " la" to 0.57f, "at" to 0.65f, " is" to 0.8f, " het" to 0.9f, "?" to 1.1f)
        assertEquals("Hoe laat is het?", LanguagePrimer.transcript(tokens, times, boundary))
    }

    @Test
    fun transcriptCapitalizesAfterAClipThatEndedOnAComma() {
        val boundary = 2.1f
        val (tokens, times) = decoded(boundary, "," to 0.01f, " dat" to 0.25f, " hij" to 0.49f, " voor" to 0.6f, "na" to 0.7f, "am" to 0.75f, " vond" to 0.9f, "." to 1.2f)
        assertEquals("Dat hij voornaam vond.", LanguagePrimer.transcript(tokens, times, boundary))
    }

    @Test
    fun transcriptLeavesMixedCaseFirstWordsAlone() {
        val boundary = 2.1f
        val (tokens, times) = decoded(boundary, " i" to 0.25f, "Phone" to 0.3f, " kwijt" to 0.6f)
        assertEquals("iPhone kwijt", LanguagePrimer.transcript(tokens, times, boundary))
    }

    @Test
    fun transcriptIsEmptyWhenOrukeetSkippedTheUtterance() {
        val boundary = 2.1f
        val (tokens, times) = decoded(boundary, "." to 0.01f)
        assertEquals("", LanguagePrimer.transcript(tokens, times, boundary))
    }

    @Test
    fun primePutsSilenceBetweenClipAndUtterance() {
        val clip = floatArrayOf(0.1f, 0.2f, 0.3f)
        val audio = floatArrayOf(0.4f, 0.5f)
        val primer = LanguagePrimer(clip)
        val gap = OrukeetEngine.SAMPLE_RATE * 2 / 5
        val primed = primer.prime(audio)
        assertEquals(clip.size + gap + audio.size, primed.size)
        assertArrayEquals(clip, primed.copyOfRange(0, clip.size), 0f)
        assertTrue(primed.copyOfRange(clip.size, clip.size + gap).all { it == 0f })
        assertArrayEquals(audio, primed.copyOfRange(clip.size + gap, primed.size), 0f)
        assertEquals((clip.size + gap / 2f) / OrukeetEngine.SAMPLE_RATE, primer.boundary, 1e-6f)
    }

    @Test
    fun shippedClipsAreShortSpeechInOrukeetsFormat() {
        for (language in listOf("nl", "en")) {
            val samples = LanguagePrimer.readWav(File("src/main/assets/${LanguagePrimer.assetPath(language)}").readBytes())
            val seconds = samples.size.toFloat() / OrukeetEngine.SAMPLE_RATE
            assertTrue("$language clip lasts $seconds s", seconds in 1f..2.5f)
            assertTrue("$language clip is silent", samples.maxOf { abs(it) } > 0.1f)
        }
    }

    @Test
    fun readWavRejectsOtherFormats() {
        val wav = ByteBuffer.allocate(44 + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(40).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(4).putShort(1000).putShort(-1000)
        }.array()
        assertThrows(IllegalStateException::class.java) { LanguagePrimer.readWav(wav) }
        val ok = wav.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, OrukeetEngine.SAMPLE_RATE) }
        assertArrayEquals(floatArrayOf(1000 / 32768f, -1000 / 32768f), LanguagePrimer.readWav(ok), 1e-6f)
    }
}
