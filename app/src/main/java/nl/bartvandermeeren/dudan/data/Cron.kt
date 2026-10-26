package nl.bartvandermeeren.dudan.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Why a cron expression doesn't parse. [field] is 0 to 4 (minute, hour, day of the month, month, day of the
 * week) with the [token] that broke it, or null when the expression doesn't have five fields.
 */
class CronSyntaxException(val field: Int?, val token: String?, message: String) : IllegalArgumentException(message)

/**
 * A five-field cron expression, `minute hour day-of-month month day-of-week`, read the way Clark's scheduler
 * (src/scheduler/time.ts) and Vixie cron read it: `*`, numbers, ranges, lists, `/n` steps, month and weekday
 * names, Sunday as 0 or 7, and a day matching either day field when both are restricted. Evaluated in a time
 * zone, a local time in the spring-forward gap is skipped and one that happens twice runs at its first instant,
 * which is Clark's default.
 */
class Cron private constructor(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val days: Set<Int>,
    val months: Set<Int>,
    /** 0 is Sunday. */
    val weekdays: Set<Int>,
    val dayRestricted: Boolean,
    val weekdayRestricted: Boolean,
) {
    private val sortedHours = hours.sorted()
    private val sortedMinutes = minutes.sorted()

    /** Whether the expression runs on [date] at all. */
    fun matches(date: LocalDate): Boolean {
        if (date.monthValue !in months) return false
        val dayOk = date.dayOfMonth in days
        val weekdayOk = date.dayOfWeek.cronNumber in weekdays
        return when {
            dayRestricted && weekdayRestricted -> dayOk || weekdayOk
            dayRestricted -> dayOk
            weekdayRestricted -> weekdayOk
            else -> true
        }
    }

    /**
     * The next [count] runs strictly after [after], in [zone]. Walks matching days and only their hours and
     * minutes, so even a schedule that runs once every leap year answers quickly. Searches [horizonDays] ahead;
     * an expression that never matches (the 30th of February) gives an empty list.
     */
    fun nextRuns(zone: ZoneId, after: Instant, count: Int, horizonDays: Long = DEFAULT_HORIZON_DAYS): List<ZonedDateTime> {
        if (count <= 0) return emptyList()
        val out = ArrayList<ZonedDateTime>(count)
        var date = after.atZone(zone).toLocalDate()
        val end = date.plusDays(horizonDays)
        while (!date.isAfter(end)) {
            if (date.monthValue !in months) {
                date = date.withDayOfMonth(1).plusMonths(1)
                continue
            }
            if (matches(date)) {
                for (hour in sortedHours) for (minute in sortedMinutes) {
                    val local = LocalDateTime.of(date, LocalTime.of(hour, minute))
                    // No valid offset: the clock skips this time (spring forward), so the run is skipped too.
                    if (zone.rules.getValidOffsets(local).isEmpty()) continue
                    // Twice valid (fall back): ofLocal takes the earlier offset, so the first instant.
                    val run = ZonedDateTime.ofLocal(local, zone, null)
                    if (run.toInstant().isAfter(after)) {
                        out += run
                        if (out.size == count) return out
                    }
                }
            }
            date = date.plusDays(1)
        }
        return out
    }

    /** Whether [other] runs at exactly the same times, however either was written. */
    fun sameSchedule(other: Cron): Boolean =
        minutes == other.minutes && hours == other.hours && months == other.months &&
            effectiveDays == other.effectiveDays && effectiveWeekdays == other.effectiveWeekdays &&
            (dayRestricted && weekdayRestricted) == (other.dayRestricted && other.weekdayRestricted)

    // An unrestricted day field matches every day, whatever set it was written as.
    private val effectiveDays: Set<Int> get() = if (dayRestricted) days else ALL_DAYS
    private val effectiveWeekdays: Set<Int> get() = if (weekdayRestricted) weekdays else ALL_WEEKDAYS

    companion object {
        /** Eight years and a bit: long enough to find a run on the 29th of February. */
        const val DEFAULT_HORIZON_DAYS = 366L * 8 + 2

        val ALL_DAYS: Set<Int> = (1..31).toSet()
        val ALL_WEEKDAYS: Set<Int> = (0..6).toSet()
        val ALL_MONTHS: Set<Int> = (1..12).toSet()

        private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val WEEKDAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

        fun parseOrNull(text: String): Cron? = runCatching { parse(text) }.getOrNull()

        /** Throws [CronSyntaxException] naming the field that's wrong. */
        fun parse(text: String): Cron {
            val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size != 5) throw CronSyntaxException(null, null, "expected 5 fields, got ${parts.size}: $text")
            val weekdays = field(parts[4], 4, 0, 7, WEEKDAY_NAMES).map { if (it == 7) 0 else it }.toSet()
            return Cron(
                minutes = field(parts[0], 0, 0, 59),
                hours = field(parts[1], 1, 0, 23),
                days = field(parts[2], 2, 1, 31),
                months = field(parts[3], 3, 1, 12, MONTH_NAMES),
                weekdays = weekdays,
                dayRestricted = parts[2] != "*",
                weekdayRestricted = parts[4] != "*",
            )
        }

        private fun field(text: String, index: Int, min: Int, max: Int, names: List<String> = emptyList()): Set<Int> {
            fun fail(): Nothing = throw CronSyntaxException(index, text, "\"$text\" is not valid in field ${index + 1}")
            fun value(token: String): Int {
                val named = names.indexOf(token.lowercase())
                if (named >= 0) return named + min
                if (token.isEmpty() || !token.all { it.isDigit() } || token.length > 3) fail()
                return token.toInt()
            }
            val out = sortedSetOf<Int>()
            for (part in text.split(",")) {
                val pieces = part.split("/")
                if (pieces.size > 2) fail()
                val range = pieces[0]
                val step = if (pieces.size == 2) pieces[1].takeIf { s -> s.isNotEmpty() && s.length <= 3 && s.all { it.isDigit() } }?.toInt() ?: fail() else 1
                if (step < 1) fail()
                val (low, high) = when {
                    range == "*" -> min to max
                    range.contains('-') -> {
                        val ends = range.split("-")
                        if (ends.size != 2) fail()
                        value(ends[0]) to value(ends[1])
                    }
                    else -> value(range).let { it to if (pieces.size == 2) max else it }
                }
                if (low < min || high > max || low > high) fail()
                for (v in low..high step step) out += v
            }
            return out
        }
    }
}

/** Cron's day-of-week number: Sunday 0, Monday 1, ... Saturday 6. */
val DayOfWeek.cronNumber: Int get() = value % 7

fun cronDay(number: Int): DayOfWeek = DayOfWeek.of(if (number % 7 == 0) 7 else number % 7)
