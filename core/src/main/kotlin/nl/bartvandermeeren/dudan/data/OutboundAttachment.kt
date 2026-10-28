package nl.bartvandermeeren.dudan.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A file the agent returned with its answer, from Clark's `attachments` metadata (version 1). It holds
 * no URL or path: [HermesApi.downloadAttachment] builds a fixed route on the configured server from the
 * ids, and the bytes must match [size] and [sha256]. See docs/outbound-attachments.md.
 */
data class OutboundAttachment(
    val sessionId: String,
    val inputId: String,
    val index: Int,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
) {
    val isImage: Boolean get() = mime in PREVIEW_TYPES

    /** Larger images are offered as files only. */
    val previewable: Boolean get() = isImage && size <= MAX_PREVIEW_BYTES

    companion object {
        /** Clark's attach limit; nothing bigger is downloaded. */
        const val MAX_BYTES = 100L * 1024 * 1024
        const val MAX_PREVIEW_BYTES = 16L * 1024 * 1024

        private val PREVIEW_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        private val INPUT_ID = Regex("[a-zA-Z0-9_-]{1,100}")
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val MIME = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
        // Path separators, control characters, characters Windows and some providers refuse, and bidi overrides.
        private val UNSAFE_NAME = Regex("[\\\\/\\p{Cntrl}<>:\"|?*\\u202a-\\u202e\\u2066-\\u2069]")

        /** The valid version-1 entries of an `attachments` array; anything else, or no array, gives none. */
        fun parse(value: JsonElement?): List<OutboundAttachment> = value.asArray().orEmpty().mapNotNull { element ->
            val o = element.asObject() ?: return@mapNotNull null
            if (o.int("version") != 1) return@mapNotNull null
            val session = o.str("session_id")?.takeIf(::isSessionId) ?: return@mapNotNull null
            val input = o.str("input_id")?.takeIf { it.matches(INPUT_ID) } ?: return@mapNotNull null
            val index = o.int("index")?.takeIf { it in 0..99_999 } ?: return@mapNotNull null
            val size = (o["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..MAX_BYTES } ?: return@mapNotNull null
            val hash = o.str("sha256")?.takeIf { it.matches(SHA256) } ?: return@mapNotNull null
            val name = o.str("name").orEmpty().replace(UNSAFE_NAME, "_").trim().trimStart('.').take(180).ifBlank { "attachment" }
            val mime = o.str("mime_type")?.lowercase()?.takeIf { it.matches(MIME) } ?: "application/octet-stream"
            OutboundAttachment(session, input, index, name, mime, size, hash)
        }.distinctBy { Triple(it.sessionId, it.inputId, it.index) }

        /** One path segment that can't climb out of the route: no separators, controls or dot segments. */
        private fun isSessionId(id: String): Boolean =
            id.isNotBlank() && id.length <= 256 && id != "." && id != ".." &&
                id.none { it.code < 32 || it.code == 127 || it == '/' || it == '\\' }
    }
}
