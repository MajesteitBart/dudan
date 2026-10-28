package nl.bartvandermeeren.dudan.data

import okio.BufferedSource

data class SseFrame(val event: String?, val data: String)

/** Minimal Server-Sent Events reader. Skips comment lines, which Hermes uses as keepalives. */
class SseReader(private val source: BufferedSource) {
    fun next(): SseFrame? {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: return if (hasData) SseFrame(event, data.toString()) else null
            if (line.isEmpty()) {
                if (hasData) return SseFrame(event, data.toString())
                event = null
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
    }
}
