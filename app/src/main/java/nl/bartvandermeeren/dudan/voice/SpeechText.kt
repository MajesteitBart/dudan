package nl.bartvandermeeren.dudan.voice

import nl.bartvandermeeren.dudan.openui.OpenUiText

/**
 * Turns a markdown reply into something a TTS voice can read without saying "asterisk". OpenUI
 * blocks are read as the text they show, not skipped like other code.
 */
object SpeechText {
    private val codeFence = Regex("```[\\s\\S]*?(```|$)")
    private val inlineCode = Regex("`([^`]+)`")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val heading = Regex("(?m)^#{1,6}\\s*")
    private val bullet = Regex("(?m)^\\s*([-*+]|\\d+[.)])\\s+")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val tableRule = Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$")
    private val quote = Regex("(?m)^>\\s?")

    fun fromMarkdown(markdown: String): String = OpenUiText.expand(markdown)
        .replace(codeFence, " ")
        .replace(image, "$1")
        .replace(link, "$1")
        .replace(bareUrl, "")
        .replace(inlineCode, "$1")
        .replace(tableRule, "")
        .lines().joinToString("\n") { line ->
            // Table rows become "cell, cell" instead of pipes.
            if (line.trimStart().startsWith("|")) {
                line.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            } else line
        }
        .replace(heading, "")
        .replace(bullet, "")
        .replace(quote, "")
        .replace(emphasis, "$2")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /**
     * Ends every line with punctuation. Voices read line breaks as spaces, so a heading or list item
     * without a full stop would run straight into the next line without a pause.
     */
    fun withPauses(text: String): String = text.lines().joinToString("\n") { line ->
        val trimmed = line.trimEnd()
        val last = trimmed.trimEnd('"', '\'', ')', ']', '”', '’').lastOrNull()
        if (last == null || last in ".!?:;,…") trimmed else "$trimmed."
    }

    // Function words, plus the greetings and one-word answers that make up short replies. Words both
    // languages use ("is", "in", "of", "was", "we", "sorry") stay out, so they don't tip a short reply.
    private val dutch = setOf(
        "de", "het", "een", "en", "niet", "je", "ik", "van", "dat", "die", "voor", "op", "met", "zijn", "er",
        "maar", "ook", "wat", "kan", "naar", "als", "dit", "jij", "wij", "heb", "wordt", "nog", "bij", "uit",
        "goedemorgen", "goedemiddag", "goedenavond", "welterusten", "hoi", "hallo", "doei", "dag", "bedankt", "dank",
        "graag", "prima", "oké", "gedaan", "klopt", "zeker", "natuurlijk", "nee", "ja", "goed", "geen", "wel", "veel",
        "vandaag", "morgen", "gisteren", "straks", "misschien", "alleen", "altijd", "komt", "staat",
    )
    private val english = setOf(
        "the", "and", "not", "you", "to", "that", "for", "on", "with", "are", "this", "it", "be",
        "but", "also", "what", "can", "as", "have", "will", "from", "your", "an", "or", "by", "at",
        "hello", "hi", "thanks", "thank", "yes", "no", "sure", "okay", "great", "good", "morning", "evening", "night",
        "today", "tomorrow", "yesterday", "done", "please", "just", "all", "there", "here", "would", "could", "should",
    )

    /** Rough language guess from common words; good enough to pick a TTS voice. Null when it's a tie. */
    fun guessLanguage(text: String): String? {
        var nl = 0
        var en = 0
        text.lowercase().split(Regex("[^\\p{L}]+")).take(400).forEach { word ->
            if (word in dutch) nl++
            if (word in english) en++
        }
        return when {
            nl > en -> "nl-NL"
            en > nl -> "en-US"
            else -> null
        }
    }

    /**
     * Splits text for a voice that synthesizes a whole piece before playing it: a sentence per piece, so
     * the first starts quickly and each next one is ready before the one before it ends. A sentence
     * shorter than [MIN_CHUNK] joins the next, since a word or two on its own sounds flat, unless that
     * makes the first piece long. Sentences over [maxLength] are cut, after a comma if possible.
     */
    fun streamingChunks(text: String, maxLength: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { splitLong(it, maxLength) }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            val limit = if (chunks.isEmpty()) minOf(maxLength, MAX_FIRST_CHUNK) else maxLength
            val full = current.length >= MIN_CHUNK || current.length + 1 + sentence.length > limit
            if (current.isNotEmpty() && full) {
                chunks += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /** Cuts a sentence longer than [maxLength] after a comma if there is one far enough in, else at a space. */
    private fun splitLong(sentence: String, maxLength: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxLength) {
            val window = rest.substring(0, maxLength)
            val cut = window.lastIndexOf(", ").takeIf { it > maxLength / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: maxLength
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    private const val MIN_CHUNK = 25
    private const val MAX_FIRST_CHUNK = 100

    /** Splits text into TTS-sized pieces on paragraph or sentence boundaries. */
    fun chunk(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        text.split(Regex("(?<=[.!?\\n])\\s+")).forEach { sentence ->
            if (current.length + sentence.length + 1 > maxLength && current.isNotEmpty()) {
                chunks += current.toString()
                current.setLength(0)
            }
            if (sentence.length > maxLength) {
                sentence.chunked(maxLength).forEach { chunks += it }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
