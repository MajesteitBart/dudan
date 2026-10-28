package nl.bartvandermeeren.dudan.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Schedules and run times as short sentences in English or Dutch, the app's two languages. The words are written
 * out here rather than taken from the platform's locale data, which abbreviates differently per Android version
 * ("okt" or "okt.") and has no plural weekdays. Times are 24-hour, as the picker shows them.
 */
class ScheduleText(locale: Locale) {
    private val dutch = locale.language == "nl"

    fun describe(schedule: JobSchedule, today: LocalDate): String = when (schedule) {
        is JobSchedule.Once ->
            if (dutch) "Eenmalig op ${date(schedule.dateTime.toLocalDate(), today)} om ${time(schedule.dateTime.toLocalTime())}"
            else "Once on ${date(schedule.dateTime.toLocalDate(), today)} at ${time(schedule.dateTime.toLocalTime())}"
        is JobSchedule.Interval -> interval(schedule)
        is JobSchedule.Daily -> if (dutch) "Elke dag om ${time(schedule.time)}" else "Every day at ${time(schedule.time)}"
        is JobSchedule.Weekly -> weekly(schedule.days, schedule.time)
        is JobSchedule.Monthly ->
            if (dutch) "Op de ${schedule.dayOfMonth}e van elke maand om ${time(schedule.time)}"
            else "On the ${ordinal(schedule.dayOfMonth)} of every month at ${time(schedule.time)}"
        is JobSchedule.Custom -> schedule.cron.trim()
    }

    /** [describe], naming [clock] when a recurring schedule runs on another zone's clock than the phone's [zone]. */
    fun describe(schedule: JobSchedule, today: LocalDate, clock: ZoneId?, zone: ZoneId): String {
        val described = describe(schedule, today)
        return if (schedule !is JobSchedule.Once && clock != null && clock != zone) "$described ($clock)" else described
    }

    /** "Tue 27 Oct" or "di 27 okt", with the year when it isn't [today]'s. */
    fun date(date: LocalDate, today: LocalDate): String {
        val day = shortDay(date.dayOfWeek)
        val month = (if (dutch) NL_MONTHS else EN_MONTHS)[date.monthValue - 1]
        val year = if (date.year != today.year) " ${date.year}" else ""
        return "$day ${date.dayOfMonth} $month$year"
    }

    /** "Mon 26 Oct 09:30", as the list shows the next run. */
    fun dateTime(at: LocalDateTime, today: LocalDate): String = "${date(at.toLocalDate(), today)} ${time(at.toLocalTime())}"

    /** A moment that has passed, such as when a result came in: "07:30" on [today], else "Mon 26 Oct 07:30". */
    fun moment(at: LocalDateTime, today: LocalDate): String =
        if (at.toLocalDate() == today) time(at.toLocalTime()) else dateTime(at, today)

    /** Run times for a preview: each day's date once, then only the times on the same day. */
    fun runs(times: List<LocalDateTime>, today: LocalDate): String {
        var previous: LocalDate? = null
        return times.joinToString(", ") { at ->
            val sameDay = at.toLocalDate() == previous
            previous = at.toLocalDate()
            if (sameDay) time(at.toLocalTime()) else dateTime(at, today)
        }
    }

    fun time(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /**
     * A task's schedule for the list: the sentence for what the picker can show, with the task's zone when it runs
     * on another zone's clock than the phone's, or else the server's own display text.
     */
    fun summary(job: JobInfo, zone: ZoneId, today: LocalDate): String? {
        val schedule = JobSchedule.parse(job, zone) ?: return job.schedule
        return describe(schedule, today, job.zoneId, zone)
    }

    /**
     * A time the server sent, such as `next_run_at`, as "Mon 26 Oct 09:30" on the phone's clock. Hermes and Clark
     * send an offset; one without is shown as written, and text that isn't a date-time shows its first 16 characters.
     */
    fun serverTime(raw: String?, zone: ZoneId, today: LocalDate): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val local = runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: return text.take(16).replace('T', ' ')
        return dateTime(local, today)
    }

    fun shortDay(day: DayOfWeek): String = (if (dutch) NL_SHORT_DAYS else EN_SHORT_DAYS)[day.value - 1]

    private fun interval(schedule: JobSchedule.Interval): String {
        val n = schedule.every
        val base = when (schedule.unit) {
            IntervalUnit.Minutes -> if (dutch) (if (n == 1) "Elke minuut" else "Elke $n minuten") else (if (n == 1) "Every minute" else "Every $n minutes")
            IntervalUnit.Hours -> if (dutch) (if (n == 1) "Elk uur" else "Elke $n uur") else (if (n == 1) "Every hour" else "Every $n hours")
        }
        val days = schedule.days.takeIf { it.isNotEmpty() && it.size < 7 }?.let { " " + onDays(it) }.orEmpty()
        val window = schedule.window?.let { ", ${time(LocalTime.of(it.first, 0))}–${time(LocalTime.of(it.last, 59))}" }.orEmpty()
        return base + days + window
    }

    /** "on weekdays", "op ma, wo en vr", "on Mon to Thu", for an interval limited to some days. */
    private fun onDays(days: Set<DayOfWeek>): String = when {
        days == JobSchedule.WEEKDAYS -> if (dutch) "op werkdagen" else "on weekdays"
        days == JobSchedule.WEEKEND -> if (dutch) "in het weekend" else "on weekends"
        else -> (if (dutch) "op " else "on ") + (consecutive(days, ::shortDay) ?: list(days.sorted().map(::shortDay)))
    }

    private fun weekly(days: Set<DayOfWeek>, at: LocalTime): String {
        val t = time(at)
        val sorted = days.sorted()
        return when {
            // Not a schedule anyone saves (the editor asks for a day first), but never a sentence with a hole in it.
            days.isEmpty() -> if (dutch) "Nooit" else "Never"
            days.size == 7 -> if (dutch) "Elke dag om $t" else "Every day at $t"
            days == JobSchedule.WEEKDAYS -> if (dutch) "Op werkdagen om $t" else "Weekdays at $t"
            days == JobSchedule.WEEKEND -> if (dutch) "In het weekend om $t" else "Weekends at $t"
            dutch -> {
                val names = consecutive(days) { NL_DAYS[it.value - 1] }
                when {
                    names != null -> "${names.replaceFirstChar { it.uppercase() }} om $t"
                    days.size <= 3 -> "Elke ${list(sorted.map { NL_DAYS[it.value - 1] })} om $t"
                    else -> "Op ${list(sorted.map(::shortDay))} om $t"
                }
            }
            else -> {
                val names = consecutive(days) { EN_DAYS[it.value - 1] }
                when {
                    names != null -> "$names at $t"
                    days.size <= 3 -> "${list(sorted.map { EN_DAYS[it.value - 1] + "s" })} at $t"
                    else -> "${list(sorted.map(::shortDay))} at $t"
                }
            }
        }
    }

    /** Three or more days in a row, Monday first, as "Monday to Thursday" or "maandag t/m donderdag"; else null. */
    private fun consecutive(days: Set<DayOfWeek>, name: (DayOfWeek) -> String): String? {
        val sorted = days.sorted()
        if (sorted.size < 3 || !sorted.zipWithNext().all { (a, b) -> b.value == a.value + 1 }) return null
        return "${name(sorted.first())}${if (dutch) " t/m " else " to "}${name(sorted.last())}"
    }

    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + (if (dutch) " en " else " and ") + items.last()
    }

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    companion object {
        private val EN_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val NL_DAYS = listOf("maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag")
        private val EN_SHORT_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private val NL_SHORT_DAYS = listOf("ma", "di", "wo", "do", "vr", "za", "zo")
        private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val NL_MONTHS = listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
    }
}
