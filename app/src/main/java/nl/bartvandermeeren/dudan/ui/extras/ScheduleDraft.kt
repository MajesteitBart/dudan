package nl.bartvandermeeren.dudan.ui.extras

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import nl.bartvandermeeren.dudan.data.Cron
import nl.bartvandermeeren.dudan.data.CronSyntaxException
import nl.bartvandermeeren.dudan.data.IntervalUnit
import nl.bartvandermeeren.dudan.data.JobInfo
import nl.bartvandermeeren.dudan.data.JobSchedule

/** The editor's When choices, in the order the picker shows them. */
enum class ScheduleMode { Once, Repeat, Daily, Weekly, Monthly, Custom }

/** Why the picked schedule can't be saved. */
sealed interface ScheduleProblem {
    data object Past : ScheduleProblem
    /** A one-off at a time the clocks skip (spring forward); it would go out, and run, an hour later. */
    data object Skipped : ScheduleProblem
    data object NoDays : ScheduleProblem
    data object BadWindow : ScheduleProblem
    /** [field] 0 to 4 with the [token] that broke it, or null when the count of fields is wrong. */
    data class Syntax(val field: Int?, val token: String?) : ScheduleProblem
    data object Never : ScheduleProblem
}

/**
 * Everything the editor's When section holds. Each choice keeps its own settings, so going from Weekly to Daily
 * and back loses nothing; Daily, Weekly and Monthly share one time. [schedule] is what the current choice makes.
 */
data class ScheduleDraft(
    val mode: ScheduleMode,
    val once: LocalDateTime,
    val every: Int,
    val unit: IntervalUnit,
    val windowOn: Boolean,
    val windowStart: Int,
    val windowEnd: Int,
    val daysOn: Boolean,
    val repeatDays: Set<DayOfWeek>,
    val time: LocalTime,
    val weekDays: Set<DayOfWeek>,
    val monthDay: Int,
    val cron: String,
) {
    fun schedule(): JobSchedule = when (mode) {
        ScheduleMode.Once -> JobSchedule.Once(once)
        ScheduleMode.Repeat -> JobSchedule.Interval(
            every = every,
            unit = unit,
            // The whole day is no window at all; both write the same cron.
            window = (windowStart..windowEnd).takeIf { windowOn && !(windowStart == 0 && windowEnd == 23) },
            days = repeatDays.takeIf { daysOn && it.size < 7 }.orEmpty(),
        )
        ScheduleMode.Daily -> JobSchedule.Daily(time)
        ScheduleMode.Weekly -> JobSchedule.Weekly(weekDays, time)
        ScheduleMode.Monthly -> JobSchedule.Monthly(monthDay, time)
        ScheduleMode.Custom -> JobSchedule.Custom(cron)
    }

    /**
     * What stops this from being saved at [now], or null. [now]'s zone is the clock the schedule runs on: the phone's
     * for a one-off. A custom cron is checked for runs in the next eight years.
     */
    fun problem(now: ZonedDateTime): ScheduleProblem? = when (mode) {
        ScheduleMode.Once -> when {
            !once.isAfter(now.toLocalDateTime()) -> ScheduleProblem.Past
            now.zone.rules.getValidOffsets(once.truncatedTo(ChronoUnit.MINUTES)).isEmpty() -> ScheduleProblem.Skipped
            else -> null
        }
        ScheduleMode.Repeat -> when {
            windowOn && windowStart > windowEnd -> ScheduleProblem.BadWindow
            daysOn && repeatDays.isEmpty() -> ScheduleProblem.NoDays
            else -> null
        }
        ScheduleMode.Weekly -> ScheduleProblem.NoDays.takeIf { weekDays.isEmpty() }
        ScheduleMode.Custom -> try {
            ScheduleProblem.Never.takeIf { Cron.parse(cron).nextRuns(now.zone, now.toInstant(), 1).isEmpty() }
        } catch (e: CronSyntaxException) {
            ScheduleProblem.Syntax(e.field, e.token)
        }
        ScheduleMode.Daily, ScheduleMode.Monthly -> null
    }

    /** Custom starts from the cron of the choice before it, so a preset can be tweaked by hand. */
    fun withMode(next: ScheduleMode): ScheduleDraft {
        if (next == mode) return this
        val carried = if (next == ScheduleMode.Custom && mode != ScheduleMode.Custom) schedule().cron()?.takeIf { Cron.parseOrNull(it) != null } else null
        return copy(mode = next, cron = carried ?: cron)
    }

    /** For rememberSaveable: primitives only. */
    fun encode(): List<Any> = listOf(
        mode.name, once.toString(), every, unit.name, windowOn, windowStart, windowEnd,
        daysOn, mask(repeatDays), time.toString(), mask(weekDays), monthDay, cron,
    )

    companion object {
        /** A new task: every day at 09:00, with the other choices set to what people pick most. */
        fun initial(now: LocalDateTime): ScheduleDraft = ScheduleDraft(
            mode = ScheduleMode.Daily,
            once = tomorrowMorning(now),
            every = 30,
            unit = IntervalUnit.Minutes,
            windowOn = false,
            windowStart = 7,
            windowEnd = 22,
            daysOn = false,
            repeatDays = JobSchedule.WEEKDAYS,
            time = LocalTime.of(9, 0),
            weekDays = JobSchedule.WEEKDAYS,
            monthDay = 1,
            cron = "0 9 * * *",
        )

        /** What the editor opens with: a new task's defaults, or [job]'s [schedule] as the picker reads it. */
        fun opening(job: JobInfo?, schedule: JobSchedule?, now: LocalDateTime): ScheduleDraft =
            if (job == null) initial(now) else from(schedule, now, fallback = job.scheduleExpr)

        /** The draft showing [schedule]; [fallback] fills the custom field when the schedule couldn't be read. */
        fun from(schedule: JobSchedule?, now: LocalDateTime, fallback: String? = null): ScheduleDraft {
            val base = initial(now)
            val draft = when (schedule) {
                null -> base.copy(mode = ScheduleMode.Custom, cron = fallback.orEmpty())
                is JobSchedule.Once -> base.copy(mode = ScheduleMode.Once, once = schedule.dateTime)
                is JobSchedule.Interval -> base.copy(
                    mode = ScheduleMode.Repeat,
                    every = schedule.every,
                    unit = schedule.unit,
                    windowOn = schedule.window != null,
                    windowStart = schedule.window?.first ?: base.windowStart,
                    windowEnd = schedule.window?.last ?: base.windowEnd,
                    daysOn = schedule.days.isNotEmpty(),
                    repeatDays = schedule.days.ifEmpty { base.repeatDays },
                )
                is JobSchedule.Daily -> base.copy(mode = ScheduleMode.Daily, time = schedule.time)
                is JobSchedule.Weekly -> base.copy(mode = ScheduleMode.Weekly, weekDays = schedule.days, time = schedule.time)
                is JobSchedule.Monthly -> base.copy(mode = ScheduleMode.Monthly, monthDay = schedule.dayOfMonth, time = schedule.time)
                is JobSchedule.Custom -> base.copy(mode = ScheduleMode.Custom, cron = schedule.cron)
            }
            return draft.copy(cron = schedule?.cron() ?: draft.cron)
        }

        fun decode(saved: List<Any>): ScheduleDraft = ScheduleDraft(
            mode = ScheduleMode.valueOf(saved[0] as String),
            once = LocalDateTime.parse(saved[1] as String),
            every = saved[2] as Int,
            unit = IntervalUnit.valueOf(saved[3] as String),
            windowOn = saved[4] as Boolean,
            windowStart = saved[5] as Int,
            windowEnd = saved[6] as Int,
            daysOn = saved[7] as Boolean,
            repeatDays = days(saved[8] as Int),
            time = LocalTime.parse(saved[9] as String),
            weekDays = days(saved[10] as Int),
            monthDay = saved[11] as Int,
            cron = saved[12] as String,
        )

        /** Quick choices for Once. An hour from now rounds up to the next five minutes. */
        fun inAnHour(now: LocalDateTime): LocalDateTime {
            val later = now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
            val extra = (5 - later.minute % 5) % 5
            return later.plusMinutes(extra.toLong())
        }

        /** Today at 20:00, or null once that's no longer ahead. */
        fun tonight(now: LocalDateTime): LocalDateTime? = now.toLocalDate().atTime(20, 0).takeIf { it.isAfter(now) }

        fun tomorrowMorning(now: LocalDateTime): LocalDateTime = now.toLocalDate().plusDays(1).atTime(9, 0)

        private fun mask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, day -> acc or (1 shl day.value) }

        private fun days(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl it.value) != 0 }.toSet()
    }
}

/**
 * The editor's [draft] against [opening], the draft it opened with, for [job] (null for a new task) whose schedule
 * the picker read as [original] (null when it couldn't), at [now] on a phone in [zone].
 */
class ScheduleEdit(
    private val job: JobInfo?,
    original: JobSchedule?,
    opening: ScheduleDraft,
    draft: ScheduleDraft,
    private val zone: ZoneId,
    private val now: Instant,
) {
    val schedule: JobSchedule = draft.schedule()
    val wire: String = schedule.wire(zone)

    /**
     * Whether the picked schedule differs from the one the editor opened with. Worked out rather than set on each
     * tap, so tapping what's already picked, or going Daily, Weekly and back to Daily, changes nothing.
     */
    val touched: Boolean = schedule != opening.schedule()

    /** A schedule the picker can't show that stays as it is, such as Hermes' `every 90m`. */
    val keeps: Boolean = job != null && !touched && original == null

    /** Whether saving sends the schedule: only when it now says something other than what the server has. */
    val changed: Boolean = touched && wire != original?.wire(zone)

    /**
     * The clock the cron runs on. One that isn't sent stays on the task's own zone, when the server names one; one
     * that is goes with the phone's zone, and a one-off carries the phone's offset.
     */
    val clock: ZoneId = if (job != null && !changed && schedule !is JobSchedule.Once) job.zoneId ?: zone else zone

    val problem: ScheduleProblem? = draft.problem(now.atZone(clock))

    /** An existing task's schedule only has to be right once it's touched: a passed one-off can still get a new instruction. */
    val ok: Boolean = problem == null || (job != null && !touched)

    /**
     * The next [count] runs on the phone's clock. Hermes counts an unchanged `every Nm` on from its last run, so those
     * come from the server's next run; everything else from the cron on [clock]. Empty for a one-off.
     */
    fun nextRuns(count: Int): List<LocalDateTime> =
        job?.takeIf { !changed }?.let { JobSchedule.intervalRuns(it, zone, count) } ?: schedule.nextRuns(clock, zone, now, count)
}
