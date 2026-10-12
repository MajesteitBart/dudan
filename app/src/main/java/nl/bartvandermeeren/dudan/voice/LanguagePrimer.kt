package nl.bartvandermeeren.dudan.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Keeps Orukeet to one language. Orukeet has no language setting: it hears which language you speak,
 * and a short sentence often gives it too little to go on ("Hoe laat is het?" came out as "Who laat
 * is it?"). So a short utterance is decoded right after a spoken sentence in the chosen language,
 * and that sentence's words are dropped from the transcript again by their timestamps.
 *
 * The sentences are cut from LibriVox recordings, which are in the public domain; docs/reference.md
 * credits them.
 */
class LanguagePrimer(private val clip: FloatArray) {
    /** Where the utterance starts in [prime]'s output, in seconds: halfway through the silence after the clip. */
    val boundary: Float = (clip.size + GAP / 2f) / OrukeetEngine.SAMPLE_RATE

    /** The clip, a short silence, then [audio]. */
    fun prime(audio: FloatArray): FloatArray = FloatArray(clip.size + GAP + audio.size).also {
        clip.copyInto(it)
        audio.copyInto(it, clip.size + GAP)
    }

    /** The utterance's words from a decode of [prime]'s output. */
    fun transcript(tokens: Array<String>, timestamps: FloatArray): String = transcript(tokens, timestamps, boundary)

    companion object {
        /** 0.4 s. A longer silence made Orukeet skip short utterances after the clip altogether. */
        private const val GAP = OrukeetEngine.SAMPLE_RATE * 2 / 5

        /**
         * Only utterances up to 4 s get a clip. Longer ones carry enough of their language, and behind a
         * clip Orukeet sometimes skipped whole sentences of them.
         */
        const val MAX_SAMPLES = OrukeetEngine.SAMPLE_RATE * 4

        /** Languages with a clip in assets/orukeet. */
        private val languages = setOf("nl", "en")

        /** The clip's language for a speech language setting such as "nl-NL"; null when there's no clip for it. */
        fun languageOf(setting: String?): String? {
            if (setting.isNullOrBlank()) return null
            val language = Locale.forLanguageTag(setting.trim().replace('_', '-')).language
            return language.takeIf { it in languages }
        }

        fun assetPath(language: String) = "orukeet/primer-$language.wav"

        /**
         * Keeps the tokens after [boundary]. The clip's closing full stop or comma can land just past it,
         * so the transcript starts at the first token that starts a word. When the clip ended on a comma,
         * Orukeet carries on in lower case, so the first word gets its capital back.
         */
        fun transcript(tokens: Array<String>, timestamps: FloatArray, boundary: Float): String {
            val text = tokens.indices
                .filter { timestamps[it] >= boundary }
                .map { tokens[it] }
                .dropWhile { !it.startsWith(" ") }
                .joinToString("")
                .trim()
            val first = text.substringBefore(' ')
            return if (first == first.lowercase()) text.replaceFirstChar { it.titlecase() } else text
        }

        /** Samples of a 16-bit mono PCM WAV recorded at 16 kHz, the format of the clips. */
        fun readWav(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "not a WAV file"
            }
            buffer.position(12)
            var formatChecked = false
            while (buffer.remaining() >= 8) {
                val id = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
                val size = buffer.int
                val next = buffer.position() + size + (size and 1)
                when (id) {
                    "fmt " -> {
                        val format = buffer.short.toInt()
                        val channels = buffer.short.toInt()
                        val rate = buffer.int
                        buffer.position(buffer.position() + 6) // byte rate, block align
                        val bits = buffer.short.toInt()
                        check(format == 1 && channels == 1 && rate == OrukeetEngine.SAMPLE_RATE && bits == 16) {
                            "expected 16-bit mono PCM at ${OrukeetEngine.SAMPLE_RATE} Hz"
                        }
                        formatChecked = true
                    }
                    "data" -> {
                        check(formatChecked) { "WAV data before its format" }
                        return FloatArray(minOf(size, buffer.remaining()) / 2) { buffer.short / 32768f }
                    }
                }
                buffer.position(minOf(next, bytes.size))
            }
            error("no audio data")
        }
    }
}
