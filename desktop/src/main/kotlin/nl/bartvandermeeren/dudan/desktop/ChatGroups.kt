package nl.bartvandermeeren.dudan.desktop

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.data.SessionSummary

/**
 * The chat list's groups, after T3 Code's sidebar: the open chats on top, then Working (a turn or
 * background work running here), Snoozed (out of sight until a time) and Settled (done, until something
 * new comes in). A chat is in one group: working beats snoozed, snoozed beats settled.
 */
enum class ChatGroup { Open, Working, Snoozed, Settled }

data class ChatRow(
    val session: SessionSummary,
    val group: ChatGroup,
    val unread: Boolean,
    val activity: ChatEngine.Activity? = null,
    val snoozedUntil: Long? = null,
)

data class ChatGroups(
    val open: List<ChatRow> = emptyList(),
    val working: List<ChatRow> = emptyList(),
    val snoozed: List<ChatRow> = emptyList(),
    val settled: List<ChatRow> = emptyList(),
) {
    fun rows(group: ChatGroup): List<ChatRow> = when (group) {
        ChatGroup.Open -> open
        ChatGroup.Working -> working
        ChatGroup.Snoozed -> snoozed
        ChatGroup.Settled -> settled
    }

    /** The chat's row in whichever group holds it. */
    fun row(id: String): ChatRow? = ChatGroup.entries.firstNotNullOfOrNull { group -> rows(group).firstOrNull { it.session.id == id } }

    /** Only the chats whose title or first question has [query]; a search shows these, the chats' state stays. */
    fun matching(query: String): ChatGroups {
        val needle = query.trim()
        if (needle.isEmpty()) return this
        fun List<ChatRow>.keep() = filter {
            it.session.displayTitle.contains(needle, ignoreCase = true) || it.session.preview?.contains(needle, ignoreCase = true) == true
        }
        return ChatGroups(open.keep(), working.keep(), snoozed.keep(), settled.keep())
    }

    /** The rows in the order the list shows them, skipping [collapsed] groups; Alt+Up and Alt+Down walk it. */
    fun visible(collapsed: Set<ChatGroup>): List<ChatRow> = ChatGroup.entries.filterNot { it in collapsed }.flatMap(::rows)

    companion object {
        /** Sorts [sessions] into groups. Pinned chats lead every group; the rest follow [sort]. */
        fun arrange(
            sessions: List<SessionSummary>,
            marks: ServerMarks,
            activity: Map<String, ChatEngine.Activity>,
            nowMs: Long,
            sort: ThreadSort,
        ): ChatGroups {
            val rows = sessions
                .map { session ->
                    val mark = marks.of(session.id)
                    val running = activity[session.id]
                    val snoozed = mark.snoozedUntil?.takeIf { it > nowMs }
                    val group = when {
                        running != null -> ChatGroup.Working
                        snoozed != null -> ChatGroup.Snoozed
                        isSettled(session, mark) -> ChatGroup.Settled
                        else -> ChatGroup.Open
                    }
                    ChatRow(session, group, isUnread(session, mark, marks.baseline), running, snoozed)
                }
                .sortedWith(comparator(sort))
            return ChatGroups(
                open = rows.filter { it.group == ChatGroup.Open },
                working = rows.filter { it.group == ChatGroup.Working },
                // Soonest back first, as a list of reminders reads.
                snoozed = rows.filter { it.group == ChatGroup.Snoozed }.sortedBy { it.snoozedUntil },
                settled = rows.filter { it.group == ChatGroup.Settled },
            )
        }

        /**
         * New since it was last on screen, or marked unread. Chats from before the first list never were, and
         * neither is a chat only this app knows yet: its time is this PC's clock, not Hermes'.
         */
        fun isUnread(session: SessionSummary, mark: ChatMark, baseline: Double?): Boolean {
            if (mark.unread) return true
            if (session.local) return false
            val active = session.lastActive ?: return false
            val readUpTo = mark.seen ?: baseline ?: return false
            return active > readUpTo + CLOCK_SLACK_S
        }

        /** Settled, and nothing new since. */
        fun isSettled(session: SessionSummary, mark: ChatMark): Boolean {
            val settled = mark.settled ?: return false
            return (session.lastActive ?: 0.0) <= settled + CLOCK_SLACK_S
        }

        private fun comparator(sort: ThreadSort): Comparator<ChatRow> {
            val pinnedFirst = compareByDescending<ChatRow> { it.session.pinned }
            return when (sort) {
                ThreadSort.Activity -> pinnedFirst.thenByDescending { it.session.lastActive ?: 0.0 }
                ThreadSort.Created -> pinnedFirst.thenByDescending { it.session.startedAt ?: it.session.lastActive ?: 0.0 }
                ThreadSort.Title -> pinnedFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.session.displayTitle }
            }
        }

        /** Hermes rounds its timestamps; a second either way is the same moment. */
        private const val CLOCK_SLACK_S = 1.0
    }
}

/** The short times beside a chat, as T3 Code shows them: "now", "5m", "4h", "3d", "2w", then a date. */
object ChatTimes {
    fun ago(epochSeconds: Double?, nowMs: Long, now: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val seconds = epochSeconds ?: return ""
        val minutes = ((nowMs / 1000.0 - seconds) / 60).toLong().coerceAtLeast(0)
        return when {
            minutes < 1 -> now
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d"
            minutes < 5 * 7 * 24 * 60 -> "${minutes / (7 * 24 * 60)}w"
            else -> {
                val date = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone).toLocalDate()
                val sameYear = date.year == Instant.ofEpochMilli(nowMs).atZone(zone).year
                date.format(DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy", locale))
            }
        }
    }

    /** Time left on a snooze: "45m", "4h", "5d". */
    fun left(untilMs: Long, nowMs: Long): String {
        val minutes = ((untilMs - nowMs + 59_999) / 60_000).coerceAtLeast(1)
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${(minutes + 30) / 60}h"
            else -> "${(minutes + 12 * 60) / (24 * 60)}d"
        }
    }

    /** How long something has run: "12s", "4m", "2h". */
    fun running(sinceMs: Long, nowMs: Long): String {
        val seconds = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }
    }
}

/** The snooze menu's choices. Times land on the hour, the way a reminder is set by hand. */
enum class SnoozeOption {
    OneHour, ThreeHours, Tomorrow, NextWeek;

    /** When the snooze ends, from [nowMs]: an hour, three hours, tomorrow at 9:00, or next Monday at 9:00. */
    fun until(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val at = when (this) {
            OneHour -> now.plusHours(1)
            ThreeHours -> now.plusHours(3)
            Tomorrow -> LocalDate.from(now).plusDays(1).atTime(MORNING, 0)
            NextWeek -> LocalDate.from(now).with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).atTime(MORNING, 0)
        }
        return at.atZone(zone).toInstant().toEpochMilli()
    }

    private companion object {
        const val MORNING = 9
    }
}
