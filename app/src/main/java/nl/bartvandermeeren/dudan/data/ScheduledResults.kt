package nl.bartvandermeeren.dudan.data

import nl.bartvandermeeren.dudan.voice.SpeechText

/**
 * How far the catch-up check for scheduled results has looked on [server]. [since] is when it started watching and
 * [marks] says up to when each task chat counts as seen, all in the server's seconds. A chat without a mark counts as
 * seen up to [since], so a task chat Clark makes later reports its first result too.
 */
data class SeenMarks(val server: String, val since: Double, val marks: Map<String, Double> = emptyMap()) {
    fun markFor(sessionId: String): Double = marks[sessionId] ?: since

    /**
     * [sessionId] seen up to [upTo]. Marks only move forward. Past [LIMIT] chats the oldest marks go, and [since]
     * moves up to the newest of them, so a chat that loses its mark doesn't report its old results again.
     */
    fun seen(sessionId: String, upTo: Double): SeenMarks {
        if (upTo <= markFor(sessionId) || !storable(sessionId)) return this
        val all = marks + (sessionId to upTo)
        if (all.size <= LIMIT) return copy(marks = all)
        val dropped = all.entries.sortedBy { it.value }.take(all.size - LIMIT)
        return copy(since = maxOf(since, dropped.last().value), marks = all - dropped.map { it.key }.toSet())
    }

    /** The server, [since], then one `id<TAB>mark` line per chat. */
    fun encode(): String = (listOf(server, since.toString()) + marks.map { (id, mark) -> "$id\t$mark" }).joinToString("\n")

    companion object {
        const val LIMIT = 500

        private fun storable(sessionId: String) = sessionId.isNotEmpty() && sessionId.none { it == '\t' || it == '\n' || it == '\r' }

        fun decode(text: String?): SeenMarks? {
            val lines = text?.lines() ?: return null
            if (lines.size < 2) return null
            val since = lines[1].toDoubleOrNull() ?: return null
            val marks = lines.drop(2).mapNotNull { line ->
                val id = line.substringBefore('\t', "")
                val mark = line.substringAfter('\t', "").toDoubleOrNull()
                if (id.isEmpty() || mark == null) null else id to mark
            }.toMap()
            return SeenMarks(lines[0], since, marks)
        }
    }
}

/**
 * The decisions of the catch-up check for scheduled results, which runs in the background every 15 minutes: which task
 * chats have something new, which of their rows are new results, and how far a chat now counts as seen. Clark gives a
 * task's chat a `job_id`; stock Hermes sends none, so there the check finds nothing.
 */
object ScheduledCatchUp {
    /** How long a chat's activity may run ahead of its newest row before the check stops looking for that row. */
    const val SETTLE_SECONDS = 600.0

    /** How much of a result's text a notification shows. */
    const val PREVIEW_CHARS = 200

    /**
     * The first check on [server], after an install, an upgrade or a change of server: what exists now counts as seen,
     * so old results are never reported.
     */
    fun start(server: String, sessions: List<SessionSummary>, nowSeconds: Double): SeenMarks =
        SeenMarks(server, nowSeconds, sessions.filter { it.jobId != null }.associate { it.id to maxOf(nowSeconds, it.lastActive ?: 0.0) })

    /** Task chats active after their mark. */
    fun due(sessions: List<SessionSummary>, seen: SeenMarks): List<SessionSummary> =
        sessions.filter { it.jobId != null && (it.lastActive ?: 0.0) > seen.markFor(it.id) }

    /** The scheduled results among a chat's latest [rows] that came in after [mark], oldest first. */
    fun fresh(rows: List<HermesMessage>, mark: Double): List<HermesMessage> =
        rows.filter { it.isScheduledResult && (it.timestamp ?: 0.0) > mark }.sortedBy { it.timestamp }

    /**
     * How far a chat counts as seen after its latest [rows] were read: up to the newest row, and up to [lastActive]
     * once a row is as new. Clark marks a chat active a moment before it writes the result, so a check that comes in
     * between looks again next time, until [SETTLE_SECONDS] have passed.
     */
    fun advance(mark: Double, lastActive: Double?, rows: List<HermesMessage>, nowSeconds: Double): Double {
        val newest = rows.mapNotNull { it.timestamp }.maxOrNull() ?: mark
        val active = lastActive ?: 0.0
        val settled = newest >= active || nowSeconds - active > SETTLE_SECONDS
        return maxOf(mark, newest, if (settled) active else mark)
    }

    /** A notification's title: the task's name, else the chat's title, else the task's id. */
    fun title(row: HermesMessage, session: SessionSummary): String =
        row.scheduled?.title ?: session.displayTitle.ifBlank { row.scheduled?.jobId ?: session.jobId.orEmpty() }

    /** The start of a result's text, without markdown, on one line; empty for a result with only files. */
    fun preview(row: HermesMessage): String {
        val text = SpeechText.fromMarkdown(row.content.contentText()).replace(Regex("\\s+"), " ").trim()
        return if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS - 1).trimEnd() + "…"
    }
}
