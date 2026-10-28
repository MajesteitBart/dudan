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
        val last = trimmed.trimEnd('"', '\'', ')', ']', '”', '’', '」', '』', '）').lastOrNull()
        if (last == null || last in ".!?:;,…。！？।؟") trimmed else "$trimmed."
    }

    /**
     * Splits text for a voice that synthesizes a whole piece before playing it: a sentence per piece, so
     * the first starts quickly and each next one is ready before the one before it ends. A sentence
     * shorter than [MIN_CHUNK] joins the next, since a word or two on its own sounds flat, unless that
     * makes the first piece long. Sentences over [maxLength] are cut, after a comma if possible.
     */
    fun streamingChunks(text: String, maxLength: Int): List<String> {
        val sentences = text.split(sentenceEnd)
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
            // Japanese runs its sentences together without a space.
            if (current.isNotEmpty() && current.last() !in WIDE_ENDS) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /**
     * Where a sentence ends: after . ! ? … (and the Hindi and Arabic marks) and a space, or after Japanese
     * 。！？, which need no space, with a closing quote kept on its sentence (「…です。」).
     */
    private val sentenceEnd = Regex("(?<=[.!?…।؟])\\s+|(?<=[。！？][」』）]?)\\s*(?=[^\\s。！？」』）])|\\n+")
    private const val WIDE_ENDS = "。！？」』）"

    /**
     * Cuts a sentence longer than [maxLength] after a comma if there is one far enough in (a Japanese 、
     * too, since Japanese has no spaces), else at a space.
     */
    private fun splitLong(sentence: String, maxLength: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxLength) {
            val window = rest.substring(0, maxLength)
            val cut = window.lastIndexOf(", ").takeIf { it > maxLength / 3 }?.plus(1)
                ?: window.indexOfLast { it in "。！？、，" }.takeIf { it > maxLength / 3 }?.plus(1)
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
