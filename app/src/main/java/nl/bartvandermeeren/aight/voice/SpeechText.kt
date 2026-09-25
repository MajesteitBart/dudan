package nl.bartvandermeeren.aight.voice

/** Turns a markdown reply into something a TTS voice can read without saying "asterisk". */
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

    fun fromMarkdown(markdown: String): String = markdown
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

    private val dutch = setOf(
        "de", "het", "een", "en", "is", "niet", "je", "ik", "van", "dat", "die", "voor", "op", "met", "zijn", "er",
        "maar", "ook", "wat", "kan", "naar", "als", "dit", "jij", "wij", "heb", "wordt", "nog", "bij", "uit",
    )
    private val english = setOf(
        "the", "and", "is", "not", "you", "to", "of", "that", "for", "on", "with", "are", "this", "it", "be",
        "but", "also", "what", "can", "as", "have", "was", "will", "from", "your", "an", "or", "by", "at", "in",
    )

    /** Rough language guess from function words; good enough to pick a TTS voice. */
    fun guessLanguage(text: String): String {
        var nl = 0
        var en = 0
        text.lowercase().split(Regex("[^\\p{L}]+")).take(400).forEach { word ->
            if (word in dutch) nl++
            if (word in english) en++
        }
        return if (nl > en) "nl-NL" else "en-US"
    }

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
