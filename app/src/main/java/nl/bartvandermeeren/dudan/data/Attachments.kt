package nl.bartvandermeeren.dudan.data

import android.webkit.MimeTypeMap

/** Files up to this size can be attached; the upload service on the Hermes host enforces the same limit. */
const val MAX_ATTACHMENT_BYTES = 250L * 1024 * 1024

/**
 * A file in a chat turn. It lives on the Hermes host at [path], where the upload service put it, and
 * the agent reads it from there with its own tools. [size] is unknown for files seen in history.
 */
data class FileRef(val name: String, val mime: String, val size: Long?, val path: String)

/**
 * Hermes' messaging adapters (Telegram and the others) tell the agent about a file with a note in
 * the user's turn: "[The user sent a document: 'x.pdf'. It is saved at: /path. ...]". The API server
 * doesn't take files, so dudan writes the same notes itself, word for word as Hermes does
 * (gateway/run.py `_build_document_context_note`, gateway/run_inbound.py
 * `_prepend_inbound_media_file_notes`), and reads them back to show files in history.
 */
object AttachmentNotes {
    // The note is also sent to Hermes as text. Escape characters that could end a field or
    // introduce another note, then decode them when showing attachment names in history.
    private val escapedName = Regex("%(?:25|27|5B|5D|0D|0A)", RegexOption.IGNORE_CASE)
    private fun encodeName(name: String): String = buildString {
        name.forEach { char ->
            when (char) {
                '%' -> append("%25")
                '\'' -> append("%27")
                '[' -> append("%5B")
                ']' -> append("%5D")
                '\r' -> append("%0D")
                '\n' -> append("%0A")
                else -> append(char)
            }
        }
    }
    private fun decodeName(name: String): String = escapedName.replace(name) { match ->
        match.value.drop(1).toInt(16).toChar().toString()
    }

    private val note = Regex(
        "\\[The user sent (a document|a text document|an audio file attachment|a video attachment): '(.*?)'\\. " +
            "It is saved at: (.+)\\. Its (?:text|content) is not inlined[^\\]]*]",
    )

    fun note(file: FileRef): String = when {
        file.mime.startsWith("video/") -> mediaNote("a video attachment", "video", "inspect or process", "a video analysis or media tool", file)
        file.mime.startsWith("audio/") -> mediaNote("an audio file attachment", "audio", "transcribe or process", "a transcription or media tool", file)
        file.mime.startsWith("text/") ->
            "[The user sent a text document: '${encodeName(file.name)}'. It is saved at: ${file.path}. " +
                "Its content is not inlined here. Read the cached file yourself before answering " +
                "when the user's request involves its contents.]"
        else ->
            "[The user sent a document: '${encodeName(file.name)}'. It is saved at: ${file.path}. " +
                "Its text is not inlined here (it's a binary format such as PDF or DOCX). " +
                "To read it, extract the document's text yourself — for example with the " +
                "terminal tool or the ocr-and-documents skill — before answering, instead " +
                "of asking the user to paste the contents.]"
    }

    private fun mediaNote(kind: String, noun: String, verb: String, tool: String, file: FileRef) =
        "[The user sent $kind: '${encodeName(file.name)}'. It is saved at: ${file.path}. " +
            "Its content is not inlined here. If the user's request involves what the $noun contains, " +
            "$verb it yourself — for example by passing the path to $tool — instead of asking the user " +
            "to describe it. Only ask what to do with it if their intent is genuinely unclear.]"

    /**
     * The turn as Hermes gets it: the user's words, then one note per file. Words first, so the chat's
     * preview in the sidebar and the Hermes dashboard shows the question rather than a note.
     */
    fun compose(text: String, files: List<FileRef>): String =
        (listOf(text.trim()) + files.map(::note)).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** Splits a stored user turn back into the user's words and the files it carried. */
    fun parse(message: String): Pair<String, List<FileRef>> {
        if (!message.contains("[The user sent ")) return message to emptyList()
        val files = note.findAll(message).map { match ->
            val (kind, name, path) = match.destructured
            val decodedName = decodeName(name)
            FileRef(decodedName, mimeFor(decodedName, kind), size = null, path = path)
        }.toList()
        if (files.isEmpty()) return message to emptyList()
        val text = message.replace(note, "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return text to files
    }

    // Titles and previews are often cut short, which leaves half a note at the end.
    private val cutNote = Regex("\\[The user sent [^:\\]]*(?::\\s*'?([\\s\\S]*))?$")

    /** A chat's title or preview without notes: the words, or else the names of the files. */
    fun clean(preview: String): String {
        val (text, files) = parse(preview)
        val cut = cutNote.find(text)
        val words = (if (cut != null) text.substring(0, cut.range.first) else text).trim()
        val names = files.map { it.name } + listOfNotNull(cut?.groupValues?.get(1)?.substringBefore("'. It is saved")?.trim()?.takeIf { it.isNotEmpty() }?.let(::decodeName))
        return words.ifBlank { names.joinToString(", ") }
    }

    private fun mimeFor(name: String, kind: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        val guessed = runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }.getOrNull()
        return guessed ?: when (kind) {
            "a video attachment" -> "video/*"
            "an audio file attachment" -> "audio/*"
            "a text document" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
