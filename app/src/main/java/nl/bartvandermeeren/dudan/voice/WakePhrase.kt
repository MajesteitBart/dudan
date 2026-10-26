package nl.bartvandermeeren.dudan.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * The wake phrase ("Ok Clark") as the keyword spotter wants it. The spotter's model is English and
 * hears words as the sentencepiece tokens of its training text, so a phrase is written in capitals,
 * split into those tokens, and given its common spellings ("OK" and "OKAY").
 */
object WakePhrase {
    const val DEFAULT = "Ok Clark"

    /** Alternatives people say for one written word. */
    private val spellings = mapOf(
        "OK" to listOf("OK", "OKAY"),
        "OKAY" to listOf("OKAY", "OK"),
        "HEY" to listOf("HEY", "HAY"),
    )

    /** The phrase in the model's alphabet: capitals and apostrophes, accents dropped. Empty when nothing is left. */
    fun normalize(phrase: String): String =
        Normalizer.normalize(phrase, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase()
            .replace(Regex("[^A-Z']+"), " ")
            .trim()

    /** Why a phrase can't be the wake phrase. */
    enum class Problem {
        /** Fewer than four letters: it would fire on everyday speech. */
        TooShort,

        /** More than [MAX_WORDS] words. */
        TooLong,
    }

    /** What keeps [phrase] from working as the wake phrase, or null when it works. */
    fun problem(phrase: String): Problem? {
        val normalized = normalize(phrase)
        return when {
            normalized.count { it.isLetter() } < MIN_LETTERS -> Problem.TooShort
            normalized.split(' ').size > MAX_WORDS -> Problem.TooLong
            else -> null
        }
    }

    fun isUsable(phrase: String): Boolean = problem(phrase) == null

    /** Four letters ("Ok Bo") is the least that doesn't fire on everyday speech. */
    private const val MIN_LETTERS = 4

    /** The most words a phrase may have. Two or three work best; more only take longer to say. */
    const val MAX_WORDS = 5

    /**
     * The most spellings of a phrase the spotter listens for. "Ok hey ok" already has eight; words after
     * that keep the spelling they were written in, so a phrase never spells out into thousands of lines.
     */
    internal const val MAX_VARIANTS = 8

    /**
     * The spotter's keywords file for [phrase]: a line of tokens per spelling, or null when the phrase is
     * unusable or has letters the model can't spell.
     */
    fun keywords(phrase: String, pieces: SentencePieces): String? {
        if (!isUsable(phrase)) return null
        val words = normalize(phrase).split(' ')
        val lines = variants(words).map { variant ->
            variant.map { pieces.encode(it) ?: return null }.flatten().joinToString(" ") + " @" + variant.joinToString("_")
        }
        return lines.distinct().joinToString("\n", postfix = "\n")
    }

    /** The spellings of [words], the written one first; at most [MAX_VARIANTS] of them. */
    private fun variants(words: List<String>): List<List<String>> =
        words.fold(listOf(emptyList())) { acc, word ->
            val options = spellings[word]?.takeIf { acc.size * it.size <= MAX_VARIANTS } ?: listOf(word)
            acc.flatMap { prefix -> options.map { prefix + it } }
        }

    /**
     * The transcript without the wake phrase and anything before it. The recording that the wake word
     * opens starts with the last second and a half the listener heard, since the spotter only fires a
     * moment after the phrase, so the transcript normally starts with it: "Ok Clark, what time is it?".
     * Words are compared loosely, since a Dutch decode may write "Oké Klark".
     */
    fun stripLeading(transcript: String, phrase: String): String {
        val wake = normalize(phrase).split(' ').filter { it.isNotEmpty() }.map(::looseKey)
        if (wake.isEmpty()) return transcript
        val words = Regex("[\\p{L}']+").findAll(transcript).toList()
        val keys = words.map { looseKey(normalize(it.value)) }
        // The whole phrase among the first words; the latest match, in case the phrase came out twice.
        val whole = (0..minOf(SEARCH_WORDS, keys.size - wake.size)).lastOrNull { at -> keys.subList(at, at + wake.size) == wake }
        // Or its last words leading the transcript, when the recording started inside the phrase.
        val end = whole?.let { it + wake.size } ?: (minOf(wake.size, keys.size) downTo 1).firstOrNull { count ->
            keys.take(count) == wake.takeLast(count)
        } ?: return transcript
        val rest = transcript.substring(words[end - 1].range.last + 1).trimStart(' ', ',', '.', '!', '?', ':', ';', '-')
        return rest.replaceFirstChar { it.titlecase() }
    }

    /** How far into the transcript the phrase may start: a few words the listener heard before it. */
    private const val SEARCH_WORDS = 8

    /** A word as it sounds, roughly: the spellings of "ok" become one, and C and K are the same. */
    private fun looseKey(word: String): String {
        if (word in okay) return "OK"
        return word.replace("CK", "K").replace("PH", "F").replace('C', 'K').replace(Regex("(.)\\1+"), "$1")
    }

    private val okay = setOf("OK", "OKAY", "OKE", "OKEE", "OKEY", "OCAY")
}

/**
 * A sentencepiece unigram vocabulary, read from the model's `bpe.model`, that splits a word into its
 * most likely pieces the way sentencepiece does: the split whose piece scores add up highest.
 */
class SentencePieces(private val scores: Map<String, Float>) {
    private val longest = scores.keys.maxOfOrNull { it.length } ?: 0

    /** [word] in capitals as pieces, the first starting with the word marker; null when a letter has no piece. */
    fun encode(word: String): List<String>? {
        val text = MARKER + word
        val best = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(text.length + 1) { -1 }
        best[0] = 0f
        for (end in 1..text.length) {
            for (start in maxOf(0, end - longest) until end) {
                if (best[start] == Float.NEGATIVE_INFINITY) continue
                val score = scores[text.substring(start, end)] ?: continue
                if (best[start] + score > best[end]) {
                    best[end] = best[start] + score
                    from[end] = start
                }
            }
        }
        if (from[text.length] < 0) return null
        val pieces = ArrayList<String>()
        var end = text.length
        while (end > 0) {
            val start = from[end]
            pieces.add(text.substring(start, end))
            end = start
        }
        return pieces.asReversed()
    }

    companion object {
        const val MARKER = "▁"

        /** Sentencepiece's piece type for ordinary pieces; control and user-defined ones (`<blk>`) can't be spoken. */
        private const val NORMAL = 1

        /** Reads the pieces of a sentencepiece `ModelProto`: field 1 holds each piece, its score and its type. */
        fun parse(model: ByteArray): SentencePieces {
            val scores = HashMap<String, Float>()
            val top = Proto(model)
            while (top.hasMore()) {
                val (field, wire) = top.tag()
                if (field == 1 && wire == 2) {
                    val entry = Proto(top.bytes())
                    var piece: String? = null
                    var score = 0f
                    var type = NORMAL
                    while (entry.hasMore()) {
                        val (f, w) = entry.tag()
                        when {
                            f == 1 && w == 2 -> piece = String(entry.bytes(), Charsets.UTF_8)
                            f == 2 && w == 5 -> score = entry.float()
                            f == 3 && w == 0 -> type = entry.varint().toInt()
                            else -> entry.skip(w)
                        }
                    }
                    if (piece != null && type == NORMAL) scores[piece] = score
                } else {
                    top.skip(wire)
                }
            }
            check(scores.isNotEmpty()) { "no pieces in the sentencepiece model" }
            return SentencePieces(scores)
        }
    }

    /** Just enough protobuf wire format to walk a message. */
    private class Proto(private val data: ByteArray) {
        private var pos = 0

        fun hasMore() = pos < data.size

        fun tag(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                check(pos < data.size && shift < 64) { "truncated varint" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun bytes(): ByteArray {
            val length = varint().toInt()
            check(length >= 0 && pos + length <= data.size) { "truncated field" }
            return data.copyOfRange(pos, pos + length).also { pos += length }
        }

        fun float(): Float {
            check(pos + 4 <= data.size) { "truncated float" }
            return ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float.also { pos += 4 }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> error("unsupported wire type $wire")
            }
        }
    }
}
