package nl.bartvandermeeren.dudan.data

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class IntervalUnit { Minutes, Hours }

/**
 * When a scheduled task runs, as the editor's picker shows it. Everything but [Once] is a five-field cron with
 * numbers only, which Hermes and Clark both accept; [Once] goes out as a local date-time with the zone's offset.
 */
sealed interface JobSchedule {
    data class Once(val dateTime: LocalDateTime) : JobSchedule

    /**
     * Every [every] minutes or hours, optionally only from [window]'s first hour (HH:00) to its last (HH:59),
     * and only on [days] (empty: every day). Minutes divide the hour and hours divide the day, so the cron is exact.
     */
    data class Interval(val every: Int, val unit: IntervalUnit, val window: IntRange? = null, val days: Set<DayOfWeek> = emptySet()) : JobSchedule

    data class Daily(val time: LocalTime) : JobSchedule

    data class Weekly(val days: Set<DayOfWeek>, val time: LocalTime) : JobSchedule

    /** The [dayOfMonth] of every month; months without that day are skipped, as cron does. */
    data class Monthly(val dayOfMonth: Int, val time: LocalTime) : JobSchedule

    data class Custom(val cron: String) : JobSchedule

    /** The cron this sends, or null for [Once]. */
    fun cron(): String? = when (this) {
        is Once -> null
        is Interval -> intervalCron(this)
        is Daily -> "${time.minute} ${time.hour} * * *"
        is Weekly -> "${time.minute} ${time.hour} * * ${if (days.size == 7) "*" else weekdayField(days)}"
        is Monthly -> "${time.minute} ${time.hour} $dayOfMonth * *"
        is Custom -> cron.trim().split(Regex("\\s+")).joinToString(" ")
    }

    /** The `schedule` value for the server: a cron, or for [Once] an ISO date-time with [zone]'s offset at that time. */
    fun wire(zone: ZoneId): String = cron() ?: (this as Once).dateTime.truncatedTo(ChronoUnit.MINUTES).atZone(zone).format(ISO_WITH_OFFSET)

    companion object {
        /** What the picker offers; [MINUTE_STEPS_ALL] and [HOUR_STEPS] are what a parsed cron may use. */
        val MINUTE_STEPS = listOf(5, 10, 15, 20, 30)
        val MINUTE_STEPS_ALL = listOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30)
        val HOUR_STEPS = listOf(1, 2, 3, 4, 6, 8, 12)

        val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet()
        val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** `2026-10-27T09:00:00+01:00`; seconds always, `Z` for UTC. */
        private val ISO_WITH_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")
        private val AT = Regex("""^at\s+(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})$""")
        private val ISO = Regex("""^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}.*""")
        private val EVERY_MINUTES = Regex("""^every\s+(\d+)\s*m$""")

        /**
         * The picker's view of a job's schedule, or null when it can't show it (Hermes' `every 90m`, a broken cron).
         * Clark's `at YYYY-MM-DDTHH:MM` is wall time in the job's [jobZone]; Hermes' `run_at` carries an offset. Both
         * become [Once] in the phone's [zone]. Hermes' `every Nm` becomes [Interval] when the minutes fit one.
         */
        fun parse(expr: String?, kind: String?, jobZone: ZoneId?, zone: ZoneId): JobSchedule? {
            val text = expr?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            AT.matchEntire(text)?.let { match ->
                val local = runCatching { LocalDateTime.parse("${match.groupValues[1]}T${match.groupValues[2]}") }.getOrNull() ?: return null
                return Once(local.atZone(jobZone ?: zone).withZoneSameInstant(zone).toLocalDateTime())
            }
            if (kind == "once" || ISO.matches(text)) return parseIso(text, jobZone, zone)?.let(::Once)
            EVERY_MINUTES.matchEntire(text.lowercase())?.let { return fromMinutes(it.groupValues[1].toIntOrNull() ?: return null) }
            if (kind == "interval") return null
            return fromCron(text)
        }

        fun parse(job: JobInfo, zone: ZoneId): JobSchedule? =
            parse(job.scheduleExpr, job.scheduleKind, job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }, zone)

        /** The most specific preset that runs at exactly the cron's times, else [Custom]; null when it isn't a cron. */
        fun fromCron(text: String): JobSchedule? {
            val cron = Cron.parseOrNull(text) ?: return null
            val custom = Custom(text)
            if (cron.months != Cron.ALL_MONTHS) return custom
            val days = if (cron.weekdayRestricted && !cron.dayRestricted) cron.weekdays.map(::cronDay).toSet() else emptySet()
            val candidates = buildList {
                val minute = cron.minutes.singleOrNull()
                val hour = cron.hours.singleOrNull()
                if (minute != null && hour != null) {
                    val time = LocalTime.of(hour, minute)
                    add(Daily(time))
                    if (days.isNotEmpty()) add(Weekly(days, time))
                    cron.days.singleOrNull()?.takeIf { cron.dayRestricted && !cron.weekdayRestricted }?.let { add(Monthly(it, time)) }
                }
                MINUTE_STEPS_ALL.firstOrNull { cron.minutes == stepSet(0, 59, it) }?.let { step ->
                    val all = cron.hours == stepSet(0, 23, 1)
                    val range = contiguous(cron.hours)
                    if (all) add(Interval(step, IntervalUnit.Minutes, null, days))
                    else if (range != null) add(Interval(step, IntervalUnit.Minutes, range, days))
                }
                if (cron.minutes == setOf(0)) {
                    HOUR_STEPS.firstOrNull { cron.hours == stepSet(0, 23, it) }?.let { add(Interval(it, IntervalUnit.Hours, null, days)) }
                    // Two hours (0 9,17) read better as the cron than as "every 8 hours, 09:00–17:59".
                    val low = cron.hours.min()
                    val high = cron.hours.max()
                    if (cron.hours.size >= 3) {
                        HOUR_STEPS.firstOrNull { cron.hours == stepSet(low, high, it) }?.let { add(Interval(it, IntervalUnit.Hours, low..high, days)) }
                    }
                }
            }
            return candidates.firstOrNull { candidate -> candidate.cron()?.let(Cron::parseOrNull)?.sameSchedule(cron) == true } ?: custom
        }

        private fun fromMinutes(minutes: Int): JobSchedule? = when {
            minutes in MINUTE_STEPS_ALL -> Interval(minutes, IntervalUnit.Minutes)
            minutes % 60 == 0 && minutes / 60 in HOUR_STEPS -> Interval(minutes / 60, IntervalUnit.Hours)
            else -> null
        }

        /** An ISO date-time; with an offset it's an instant, without one it's wall time in [jobZone] (or [zone]). */
        private fun parseIso(text: String, jobZone: ZoneId?, zone: ZoneId): LocalDateTime? {
            val normalized = text.replace(' ', 'T')
            runCatching { OffsetDateTime.parse(normalized) }.getOrNull()?.let {
                return it.atZoneSameInstant(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
            }
            val local = runCatching { LocalDateTime.parse(normalized) }.getOrNull() ?: return null
            return local.atZone(jobZone ?: zone).withZoneSameInstant(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        }

        private fun stepSet(low: Int, high: Int, step: Int): Set<Int> = (low..high step step).toSet()

        private fun contiguous(values: Set<Int>): IntRange? {
            val range = values.min()..values.max()
            return range.takeIf { it.count() == values.size }
        }

        private fun intervalCron(interval: Interval): String {
            val days = if (interval.days.isEmpty() || interval.days.size == 7) "*" else weekdayField(interval.days)
            val window = interval.window
            val hours = window?.let { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
            return when (interval.unit) {
                IntervalUnit.Minutes -> "${if (interval.every == 1) "*" else "*/${interval.every}"} ${hours ?: "*"} * * $days"
                IntervalUnit.Hours -> {
                    val field = when {
                        hours == null -> if (interval.every == 1) "*" else "*/${interval.every}"
                        interval.every == 1 || window.first == window.last -> hours
                        else -> "$hours/${interval.every}"
                    }
                    "0 $field * * $days"
                }
            }
        }

        /** Days as cron numbers, Sunday 0, with three or more days in a row written as a range: `1-5`, `0,6`, `1,3,5`. */
        fun weekdayField(days: Set<DayOfWeek>): String {
            val numbers = days.map { it.cronNumber }.sorted()
            val runs = mutableListOf<MutableList<Int>>()
            numbers.forEach { n -> if (runs.lastOrNull()?.last() == n - 1) runs.last() += n else runs += mutableListOf(n) }
            return runs.joinToString(",") { run -> if (run.size >= 3) "${run.first()}-${run.last()}" else run.joinToString(",") }
        }
    }
}
