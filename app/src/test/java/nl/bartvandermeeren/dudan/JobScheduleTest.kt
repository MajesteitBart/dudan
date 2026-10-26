package nl.bartvandermeeren.dudan

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import nl.bartvandermeeren.dudan.data.Cron
import nl.bartvandermeeren.dudan.data.IntervalUnit.Hours
import nl.bartvandermeeren.dudan.data.IntervalUnit.Minutes
import nl.bartvandermeeren.dudan.data.JobInfo
import nl.bartvandermeeren.dudan.data.JobSchedule
import nl.bartvandermeeren.dudan.data.JobSchedule.Custom
import nl.bartvandermeeren.dudan.data.JobSchedule.Daily
import nl.bartvandermeeren.dudan.data.JobSchedule.Interval
import nl.bartvandermeeren.dudan.data.JobSchedule.Monthly
import nl.bartvandermeeren.dudan.data.JobSchedule.Once
import nl.bartvandermeeren.dudan.data.JobSchedule.Weekly
import nl.bartvandermeeren.dudan.data.ScheduleText
import nl.bartvandermeeren.dudan.ui.extras.ScheduleDraft
import nl.bartvandermeeren.dudan.ui.extras.ScheduleMode
import nl.bartvandermeeren.dudan.ui.extras.ScheduleProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val AMSTERDAM: ZoneId = ZoneId.of("Europe/Amsterdam")
private fun time(text: String) = LocalTime.parse(text)

class JobScheduleTest {
    /** Every preset the picker makes, with the cron it sends. */
    private val presets = listOf(
        Interval(30, Minutes, 7..22) to "*/30 7-22 * * *",
        Interval(15, Minutes) to "*/15 * * * *",
        Interval(1, Minutes) to "* * * * *",
        Interval(10, Minutes, null, JobSchedule.WEEKEND) to "*/10 * * * 0,6",
        Interval(5, Minutes, 9..17, JobSchedule.WEEKDAYS) to "*/5 9-17 * * 1-5",
        Interval(1, Hours) to "0 * * * *",
        Interval(2, Hours) to "0 */2 * * *",
        Interval(12, Hours) to "0 */12 * * *",
        Interval(1, Hours, 8..18, JobSchedule.WEEKDAYS) to "0 8-18 * * 1-5",
        Interval(3, Hours, 6..21) to "0 6-21/3 * * *",
        Daily(time("07:30")) to "30 7 * * *",
        Daily(time("00:00")) to "0 0 * * *",
        Weekly(JobSchedule.WEEKDAYS, time("07:30")) to "30 7 * * 1-5",
        Weekly(setOf(MONDAY, WEDNESDAY, FRIDAY), time("09:00")) to "0 9 * * 1,3,5",
        Weekly(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), time("09:00")) to "0 9 * * 1-4",
        Weekly(JobSchedule.WEEKEND, time("10:15")) to "15 10 * * 0,6",
        Weekly(setOf(SUNDAY), time("18:00")) to "0 18 * * 0",
        Monthly(1, time("09:00")) to "0 9 1 * *",
        Monthly(31, time("23:45")) to "45 23 31 * *",
    )

    @Test
    fun eachPresetWritesItsCron() {
        presets.forEach { (preset, cron) -> assertEquals(preset.toString(), cron, preset.cron()) }
    }

    @Test
    fun eachPresetsCronReadsBackAsThatPreset() {
        presets.forEach { (preset, cron) ->
            assertEquals(cron, preset, JobSchedule.fromCron(cron))
            assertEquals(cron, cron, JobSchedule.fromCron(cron)!!.cron())
        }
    }

    @Test
    fun theHeartbeatIsEveryHalfHourDuringTheDay() {
        val heartbeat = JobSchedule.fromCron("*/30 7-22 * * *")
        assertEquals(Interval(30, Minutes, 7..22), heartbeat)
        assertEquals("*/30 7-22 * * *", heartbeat!!.cron())
        assertEquals("Every 30 minutes, 07:00–22:59", ScheduleText(Locale.ENGLISH).describe(heartbeat, LocalDate.of(2026, 10, 26)))
        assertEquals("Elke 30 minuten, 07:00–22:59", ScheduleText(Locale.forLanguageTag("nl-NL")).describe(heartbeat, LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun otherSpellingsOfAPresetFindIt() {
        assertEquals(Interval(30, Minutes, 7..22), JobSchedule.fromCron("0,30 07-22 * * *"))
        assertEquals(Daily(time("07:30")), JobSchedule.fromCron("30 7 * * 0-6"))
        assertEquals(Weekly(JobSchedule.WEEKDAYS, time("07:30")), JobSchedule.fromCron("30 7 * * mon-fri"))
        assertEquals(Weekly(setOf(SUNDAY), time("18:00")), JobSchedule.fromCron("0 18 * * 7"))
        // 7-22/2 runs at 7, 9 ... 21: the same times as a window to 21.
        val stepped = JobSchedule.fromCron("0 7-22/2 * * *")
        assertEquals(Interval(2, Hours, 7..21), stepped)
        assertTrue(Cron.parse(stepped!!.cron()!!).sameSchedule(Cron.parse("0 7-22/2 * * *")))
    }

    @Test
    fun cronsNoPresetReproducesStayCustom() {
        listOf(
            "0 9 13 * 5", // the 13th or Fridays
            "0 9 * 1 *", // January only
            "15,45 * * * *",
            "30 7,12,18 * * *",
            "0 9,17 * * *",
            "*/7 * * * *",
            "5-59/15 * * * *",
            "0 9 1,15 * *",
            "0 9 1 * 1",
        ).forEach { assertEquals(it, Custom(it), JobSchedule.fromCron(it)) }
        assertNull(JobSchedule.fromCron("not a cron"))
        assertNull(JobSchedule.fromCron("61 * * * *"))
    }

    @Test
    fun clarksAtIsWallTimeInTheJobsZone() {
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse("at 2026-10-27T09:00", null, AMSTERDAM, AMSTERDAM))
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse("at 2026-10-27 09:00", "once", AMSTERDAM, AMSTERDAM))
        // 09:00 in London is 10:00 on a phone in Amsterdam.
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 10, 0)), JobSchedule.parse("at 2026-10-27T09:00", null, ZoneId.of("Europe/London"), AMSTERDAM))
        assertNull(JobSchedule.parse("at 2026-02-30T09:00", null, AMSTERDAM, AMSTERDAM))
    }

    @Test
    fun hermesRunAtCarriesItsOffset() {
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse("2026-10-27T09:00:00+01:00", "once", null, AMSTERDAM))
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse("2026-10-27T08:00:00.123456+00:00", "once", null, AMSTERDAM))
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse("2026-10-27T09:00:00", "once", null, AMSTERDAM))
    }

    @Test
    fun hermesIntervalsBecomeRepeatWhenTheyFit() {
        assertEquals(Interval(30, Minutes), JobSchedule.parse("every 30m", "interval", null, AMSTERDAM))
        assertEquals(Interval(2, Hours), JobSchedule.parse("every 120m", "interval", null, AMSTERDAM))
        // 90 minutes isn't a cron; the editor keeps it as it is.
        assertNull(JobSchedule.parse("every 90m", "interval", null, AMSTERDAM))
        assertNull(JobSchedule.parse(null, null, null, AMSTERDAM))
    }

    @Test
    fun parsesAJobsOwnFields() {
        val clark = JobInfo(
            "heartbeat", "heartbeat", "*/30 7-22 * * * (Europe/Amsterdam)", null, true, false, null, null, null,
            scheduleExpr = "*/30 7-22 * * *", scheduleKind = "cron", timezone = "Europe/Amsterdam",
        )
        assertEquals(Interval(30, Minutes, 7..22), JobSchedule.parse(clark, AMSTERDAM))
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse(clark.copy(scheduleExpr = "at 2026-10-27T09:00", scheduleKind = "once"), AMSTERDAM))
        assertEquals(Once(LocalDateTime.of(2026, 10, 27, 9, 0)), JobSchedule.parse(clark.copy(scheduleExpr = "at 2026-10-27T09:00", timezone = "Not/AZone"), AMSTERDAM))
    }

    @Test
    fun onceGoesOutWithTheOffsetOfItsDay() {
        assertEquals("2026-10-27T09:00:00+01:00", Once(LocalDateTime.of(2026, 10, 27, 9, 0)).wire(AMSTERDAM))
        assertEquals("2026-07-01T09:00:00+02:00", Once(LocalDateTime.of(2026, 7, 1, 9, 0, 42)).wire(AMSTERDAM))
        assertEquals("2026-07-01T09:00:00Z", Once(LocalDateTime.of(2026, 7, 1, 9, 0)).wire(ZoneId.of("UTC")))
        assertEquals("30 7 * * 1-5", Weekly(JobSchedule.WEEKDAYS, time("07:30")).wire(AMSTERDAM))
        assertEquals("0 9 * * *", Custom("  0  9 * *   * ").wire(AMSTERDAM))
    }

    @Test
    fun weekdayFieldsCompressRuns() {
        assertEquals("1-5", JobSchedule.weekdayField(JobSchedule.WEEKDAYS))
        assertEquals("0,6", JobSchedule.weekdayField(JobSchedule.WEEKEND))
        assertEquals("1,3,5", JobSchedule.weekdayField(setOf(FRIDAY, MONDAY, WEDNESDAY)))
        assertEquals("1,2", JobSchedule.weekdayField(setOf(MONDAY, TUESDAY)))
        assertEquals("0,4-6", JobSchedule.weekdayField(setOf(THURSDAY, FRIDAY, SATURDAY, SUNDAY)))
    }
}

class ScheduleTextTest {
    private val en = ScheduleText(Locale.ENGLISH)
    private val nl = ScheduleText(Locale.forLanguageTag("nl-NL"))
    private val today = LocalDate.of(2026, 10, 26)

    private fun both(schedule: JobSchedule) = en.describe(schedule, today) to nl.describe(schedule, today)

    @Test
    fun describesEveryPresetInEnglishAndDutch() {
        assertEquals("Every day at 08:00" to "Elke dag om 08:00", both(Daily(time("08:00"))))
        assertEquals("Weekdays at 07:30" to "Op werkdagen om 07:30", both(Weekly(JobSchedule.WEEKDAYS, time("07:30"))))
        assertEquals("Weekends at 10:00" to "In het weekend om 10:00", both(Weekly(JobSchedule.WEEKEND, time("10:00"))))
        assertEquals("Every day at 07:30" to "Elke dag om 07:30", both(Weekly(java.time.DayOfWeek.entries.toSet(), time("07:30"))))
        assertEquals(
            "Mondays, Wednesdays and Fridays at 09:00" to "Elke maandag, woensdag en vrijdag om 09:00",
            both(Weekly(setOf(FRIDAY, MONDAY, WEDNESDAY), time("09:00"))),
        )
        assertEquals("Sundays at 18:00" to "Elke zondag om 18:00", both(Weekly(setOf(SUNDAY), time("18:00"))))
        assertEquals("Monday to Thursday at 09:00" to "Maandag t/m donderdag om 09:00", both(Weekly(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY), time("09:00"))))
        assertEquals(
            "Mon, Tue, Thu and Sat at 09:00" to "Op ma, di, do en za om 09:00",
            both(Weekly(setOf(MONDAY, TUESDAY, THURSDAY, SATURDAY), time("09:00"))),
        )
        assertEquals("On the 1st of every month at 09:00" to "Op de 1e van elke maand om 09:00", both(Monthly(1, time("09:00"))))
        assertEquals("On the 22nd of every month at 09:00" to "Op de 22e van elke maand om 09:00", both(Monthly(22, time("09:00"))))
        assertEquals("On the 13th of every month at 09:00" to "Op de 13e van elke maand om 09:00", both(Monthly(13, time("09:00"))))
        assertEquals("On the 3rd of every month at 09:00" to "Op de 3e van elke maand om 09:00", both(Monthly(3, time("09:00"))))
        assertEquals("Once on Tue 27 Oct at 09:00" to "Eenmalig op di 27 okt om 09:00", both(Once(LocalDateTime.of(2026, 10, 27, 9, 0))))
        assertEquals("Once on Mon 1 Mar 2027 at 09:00" to "Eenmalig op ma 1 mrt 2027 om 09:00", both(Once(LocalDateTime.of(2027, 3, 1, 9, 0))))
        assertEquals("0 9 13 * 5" to "0 9 13 * 5", both(Custom("0 9 13 * 5")))
    }

    @Test
    fun describesIntervals() {
        assertEquals("Every 30 minutes, 07:00–22:59" to "Elke 30 minuten, 07:00–22:59", both(Interval(30, Minutes, 7..22)))
        assertEquals("Every minute" to "Elke minuut", both(Interval(1, Minutes)))
        assertEquals("Every hour" to "Elk uur", both(Interval(1, Hours)))
        assertEquals("Every 2 hours" to "Elke 2 uur", both(Interval(2, Hours)))
        assertEquals(
            "Every 15 minutes on weekdays, 09:00–17:59" to "Elke 15 minuten op werkdagen, 09:00–17:59",
            both(Interval(15, Minutes, 9..17, JobSchedule.WEEKDAYS)),
        )
        assertEquals("Every hour at weekends" to "Elk uur in het weekend", both(Interval(1, Hours, null, JobSchedule.WEEKEND)))
        assertEquals("Every 4 hours on Mon, Wed and Fri" to "Elke 4 uur op ma, wo en vr", both(Interval(4, Hours, null, setOf(MONDAY, WEDNESDAY, FRIDAY))))
        assertEquals("Every 5 minutes on Mon to Thu" to "Elke 5 minuten op ma t/m do", both(Interval(5, Minutes, null, setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY))))
    }

    @Test
    fun runTimesForThePreview() {
        val runs = listOf(LocalDateTime.of(2026, 10, 26, 9, 30), LocalDateTime.of(2026, 10, 26, 10, 0), LocalDateTime.of(2026, 10, 27, 9, 30))
        assertEquals("Mon 26 Oct 09:30, 10:00, Tue 27 Oct 09:30", en.runs(runs, today))
        assertEquals("ma 26 okt 09:30, 10:00, di 27 okt 09:30", nl.runs(runs, today))
    }

    @Test
    fun nextRunInThePhonesClock() {
        assertEquals("Mon 26 Oct 09:30", en.serverTime("2026-10-26T09:30:00+01:00", AMSTERDAM, today))
        assertEquals("ma 26 okt 09:30", nl.serverTime("2026-10-26T08:30:00Z", AMSTERDAM, today))
        // Hermes' microseconds, and a time without an offset as written.
        assertEquals("Mon 26 Oct 09:30", en.serverTime("2026-10-26T09:30:00.481516+01:00", AMSTERDAM, today))
        assertEquals("Mon 26 Oct 09:30", en.serverTime("2026-10-26T09:30:00", AMSTERDAM, today))
        assertEquals("tomorrow-ish", en.serverTime("tomorrow-ish", AMSTERDAM, today))
        assertNull(en.serverTime(null, AMSTERDAM, today))
    }

    @Test
    fun listSummaryFallsBackToTheServersText() {
        val job = JobInfo("a", "A", "every 90m", null, true, false, null, null, null, scheduleExpr = "every 90m", scheduleKind = "interval")
        assertEquals("every 90m", en.summary(job, AMSTERDAM, today))
        val heartbeat = job.copy(schedule = "*/30 7-22 * * * (Europe/Amsterdam)", scheduleExpr = "*/30 7-22 * * *", scheduleKind = "cron", timezone = "Europe/Amsterdam")
        assertEquals("Elke 30 minuten, 07:00–22:59", nl.summary(heartbeat, AMSTERDAM, today))
        // On another zone's clock the zone is named.
        assertEquals("Every 30 minutes, 07:00–22:59 (Europe/Amsterdam)", en.summary(heartbeat, ZoneId.of("America/New_York"), today))
    }
}

class ScheduleDraftTest {
    private val now = LocalDateTime.of(2026, 10, 26, 14, 7)
    private val zonedNow = now.atZone(AMSTERDAM)

    @Test
    fun aNewTaskRunsDailyAtNine() {
        val draft = ScheduleDraft.initial(now)
        assertEquals(Daily(time("09:00")), draft.schedule())
        assertNull(draft.problem(zonedNow))
    }

    @Test
    fun eachChoiceKeepsItsSettings() {
        val weekly = ScheduleDraft.initial(now).withMode(ScheduleMode.Weekly).copy(weekDays = setOf(MONDAY), time = time("07:15"))
        val back = weekly.withMode(ScheduleMode.Daily).withMode(ScheduleMode.Weekly)
        assertEquals(Weekly(setOf(MONDAY), time("07:15")), back.schedule())
        // Daily shares Weekly's time.
        assertEquals(Daily(time("07:15")), weekly.withMode(ScheduleMode.Daily).schedule())
        // Custom starts from the cron of the choice before.
        assertEquals(Custom("15 7 * * 1"), weekly.withMode(ScheduleMode.Custom).schedule())
    }

    @Test
    fun repeatLeavesOutAWindowOrDaysThatAreOff() {
        val draft = ScheduleDraft.initial(now).withMode(ScheduleMode.Repeat)
        assertEquals(Interval(30, Minutes), draft.schedule())
        assertEquals(Interval(30, Minutes, 7..22), draft.copy(windowOn = true).schedule())
        assertEquals(Interval(30, Minutes, null, JobSchedule.WEEKDAYS), draft.copy(daysOn = true).schedule())
        // A window over the whole day is no window.
        assertEquals(Interval(30, Minutes), draft.copy(windowOn = true, windowStart = 0, windowEnd = 23).schedule())
    }

    @Test
    fun problemsStopSaving() {
        val draft = ScheduleDraft.initial(now)
        assertEquals(ScheduleProblem.Past, draft.copy(mode = ScheduleMode.Once, once = now.minusMinutes(1)).problem(zonedNow))
        assertNull(draft.copy(mode = ScheduleMode.Once, once = ScheduleDraft.inAnHour(now)).problem(zonedNow))
        assertEquals(ScheduleProblem.NoDays, draft.copy(mode = ScheduleMode.Weekly, weekDays = emptySet()).problem(zonedNow))
        assertEquals(ScheduleProblem.NoDays, draft.copy(mode = ScheduleMode.Repeat, daysOn = true, repeatDays = emptySet()).problem(zonedNow))
        assertEquals(ScheduleProblem.BadWindow, draft.copy(mode = ScheduleMode.Repeat, windowOn = true, windowStart = 22, windowEnd = 7).problem(zonedNow))
        assertEquals(ScheduleProblem.Syntax(null, null), draft.copy(mode = ScheduleMode.Custom, cron = "0 9 * *").problem(zonedNow))
        assertEquals(ScheduleProblem.Syntax(1, "25"), draft.copy(mode = ScheduleMode.Custom, cron = "0 25 * * *").problem(zonedNow))
        assertEquals(ScheduleProblem.Never, draft.copy(mode = ScheduleMode.Custom, cron = "0 9 30 2 *").problem(zonedNow))
        assertNull(draft.copy(mode = ScheduleMode.Custom, cron = "0 9 13 * 5").problem(zonedNow))
    }

    @Test
    fun quickChoicesForOnce() {
        assertEquals(LocalDateTime.of(2026, 10, 26, 15, 10), ScheduleDraft.inAnHour(now))
        assertEquals(LocalDateTime.of(2026, 10, 26, 15, 10), ScheduleDraft.inAnHour(LocalDateTime.of(2026, 10, 26, 14, 10, 30)))
        assertEquals(LocalDateTime.of(2026, 10, 27, 0, 0), ScheduleDraft.inAnHour(LocalDateTime.of(2026, 10, 26, 22, 58)))
        assertEquals(LocalDateTime.of(2026, 10, 26, 20, 0), ScheduleDraft.tonight(now))
        assertNull(ScheduleDraft.tonight(LocalDateTime.of(2026, 10, 26, 20, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 27, 9, 0), ScheduleDraft.tomorrowMorning(now))
    }

    @Test
    fun opensOnWhatTheJobUses() {
        assertEquals(ScheduleMode.Repeat, ScheduleDraft.from(Interval(30, Minutes, 7..22), now).mode)
        val repeat = ScheduleDraft.from(Interval(30, Minutes, 7..22), now)
        assertEquals(Interval(30, Minutes, 7..22), repeat.schedule())
        assertEquals("*/30 7-22 * * *", repeat.withMode(ScheduleMode.Custom).cron)
        assertEquals(Monthly(15, time("06:00")), ScheduleDraft.from(Monthly(15, time("06:00")), now).schedule())
        // A schedule the picker can't show opens as its text, for reference.
        val unknown = ScheduleDraft.from(null, now, fallback = "every 90m")
        assertEquals(ScheduleMode.Custom, unknown.mode)
        assertEquals("every 90m", unknown.cron)
    }

    @Test
    fun theEditorOpensOnDefaultsOrOnTheTask() {
        assertEquals(ScheduleDraft.initial(now), ScheduleDraft.opening(null, null, now))
        val job = JobInfo("a", "A", "every 90m", null, true, false, null, null, null, scheduleExpr = "every 90m", scheduleKind = "interval")
        assertEquals(ScheduleMode.Custom to "every 90m", ScheduleDraft.opening(job, null, now).let { it.mode to it.cron })
        assertEquals(Daily(time("07:30")), ScheduleDraft.opening(job, Daily(time("07:30")), now).schedule())
    }

    @Test
    fun survivesBeingSaved() {
        val draft = ScheduleDraft.initial(now).copy(mode = ScheduleMode.Repeat, every = 2, unit = Hours, windowOn = true, daysOn = true, repeatDays = setOf(SATURDAY, SUNDAY), weekDays = setOf(TUESDAY), cron = "1 2 3 4 5")
        assertEquals(draft, ScheduleDraft.decode(draft.encode()))
    }

    @Test
    fun thePreviewRunsInThePhonesZone() {
        val runs = Cron.parse(Weekly(JobSchedule.WEEKDAYS, time("07:30")).cron()!!).nextRuns(AMSTERDAM, ZonedDateTime.of(now, AMSTERDAM).toInstant(), 3)
        assertEquals(listOf("2026-10-27T07:30", "2026-10-28T07:30", "2026-10-29T07:30"), runs.map { it.toLocalDateTime().toString() })
    }
}
