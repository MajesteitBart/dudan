package nl.bartvandermeeren.dudan

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import nl.bartvandermeeren.dudan.data.Cron
import nl.bartvandermeeren.dudan.data.CronSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CronTest {
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    /** Runs as offset date-times, so a test also shows which side of a clock change each one is on. */
    private fun runs(cron: String, after: String, count: Int, zone: ZoneId = amsterdam): List<String> =
        Cron.parse(cron).nextRuns(zone, OffsetDateTime.parse(after).toInstant(), count).map { it.toOffsetDateTime().toString() }

    @Test
    fun everyFifteenMinutesWithinAnHour() {
        assertEquals(
            listOf("2026-10-26T09:15+01:00", "2026-10-26T09:30+01:00", "2026-10-26T09:45+01:00", "2026-10-27T09:00+01:00"),
            runs("*/15 9 * * *", "2026-10-26T09:05+01:00", 4),
        )
    }

    @Test
    fun runsAreStrictlyAfterTheStart() {
        assertEquals(listOf("2026-10-26T09:15+01:00"), runs("*/15 * * * *", "2026-10-26T09:00+01:00", 1))
    }

    @Test
    fun rangesAndListsOnWeekdays() {
        // Friday 30 October 2026, then Monday.
        assertEquals(
            listOf("2026-10-30T10:00+01:00", "2026-10-30T14:00+01:00", "2026-11-02T08:00+01:00", "2026-11-02T09:00+01:00"),
            runs("0 8-10,14 * * 1-5", "2026-10-30T09:30+01:00", 4),
        )
    }

    @Test
    fun aRangeWithAStep() {
        assertEquals(setOf(7, 9, 11, 13, 15, 17, 19, 21), Cron.parse("0 7-22/2 * * *").hours)
        assertEquals(setOf(0, 30), Cron.parse("*/30 * * * *").minutes)
        assertEquals((10..59 step 10).toSet(), Cron.parse("10/10 * * * *").minutes)
    }

    @Test
    fun dayOfMonthOrDayOfWeekWhenBothAreRestricted() {
        // The 13th or any Friday: December 2026 has Fridays on the 4th, 11th, 18th and a Sunday the 13th.
        assertEquals(
            listOf("2026-12-04T09:00+01:00", "2026-12-11T09:00+01:00", "2026-12-13T09:00+01:00", "2026-12-18T09:00+01:00"),
            runs("0 9 13 * 5", "2026-12-01T00:00+01:00", 4),
        )
        // Only one restricted: only that one counts.
        assertEquals(listOf("2026-12-13T09:00+01:00", "2027-01-13T09:00+01:00"), runs("0 9 13 * *", "2026-12-01T00:00+01:00", 2))
        assertEquals(listOf("2026-12-04T09:00+01:00", "2026-12-11T09:00+01:00"), runs("0 9 * * 5", "2026-12-01T00:00+01:00", 2))
    }

    @Test
    fun theThirtiethOfFebruaryNeverRuns() {
        assertEquals(emptyList<String>(), runs("0 0 30 2 *", "2026-01-01T00:00+01:00", 1))
        // The 29th waits for the next leap year.
        assertEquals(listOf("2028-02-29T00:00+01:00"), runs("0 0 29 2 *", "2026-03-01T00:00+01:00", 1))
    }

    @Test
    fun aTimeInTheSpringForwardGapIsSkipped() {
        // 29 March 2026: 02:00 jumps to 03:00 in Amsterdam, so there is no 02:30 that day.
        assertEquals(listOf("2026-03-28T02:30+01:00", "2026-03-30T02:30+02:00"), runs("30 2 * * *", "2026-03-27T12:00+01:00", 2))
        // Hourly runs go from 01:00 CET straight to 03:00 CEST.
        assertEquals(listOf("2026-03-29T01:00+01:00", "2026-03-29T03:00+02:00"), runs("0 * * * *", "2026-03-29T00:30+01:00", 2))
    }

    @Test
    fun aTimeThatHappensTwiceRunsAtItsFirstInstant() {
        // 25 October 2026: 03:00 CEST goes back to 02:00 CET, so 02:30 happens twice; it runs once, in summer time.
        assertEquals(listOf("2026-10-25T02:30+02:00", "2026-10-26T02:30+01:00"), runs("30 2 * * *", "2026-10-24T12:00+02:00", 2))
        assertEquals(
            listOf("2026-10-25T02:00+02:00", "2026-10-25T02:30+02:00", "2026-10-25T03:00+01:00"),
            runs("*/30 * * * *", "2026-10-25T01:45+02:00", 3),
        )
    }

    @Test
    fun theZoneDecidesWhatNineOClockIs() {
        val after = "2026-10-26T00:00Z"
        assertEquals(listOf("2026-10-26T09:00Z"), runs("0 9 * * *", after, 1, ZoneId.of("UTC")))
        assertEquals(listOf("2026-10-26T09:00-04:00"), runs("0 9 * * *", after, 1, ZoneId.of("America/New_York")))
    }

    @Test
    fun namesAndSundayAsSeven() {
        val cron = Cron.parse("0 9 * jan,JUL mon")
        assertEquals(setOf(1, 7), cron.months)
        assertEquals(setOf(1), cron.weekdays)
        assertEquals(setOf(0), Cron.parse("0 9 * * 7").weekdays)
        assertEquals(setOf(0, 5, 6), Cron.parse("0 9 * * 5-7").weekdays)
        assertEquals(setOf(0, 5, 6), Cron.parse("0 9 * * fri,sat,sun").weekdays)
    }

    @Test
    fun theSameScheduleHowEverItIsWritten() {
        assertTrue(Cron.parse("*/30 7-22 * * *").sameSchedule(Cron.parse("0,30 7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22 * * *")))
        assertTrue(Cron.parse("30 7 * * *").sameSchedule(Cron.parse("30 7 * * 0-6")))
        assertTrue(Cron.parse("0 9 * * 1-5").sameSchedule(Cron.parse("0 9 * * mon-fri")))
        // The 13th or Fridays is not the 13th and Fridays.
        assertFalse(Cron.parse("0 9 13 * 5").sameSchedule(Cron.parse("0 9 * * 5")))
        assertFalse(Cron.parse("0 9 1-31 * 1-5").sameSchedule(Cron.parse("0 9 * * 1-5")))
    }

    @Test
    fun syntaxErrorsNameTheField() {
        fun error(text: String): CronSyntaxException = try {
            Cron.parse(text)
            fail("$text parsed")
            throw AssertionError()
        } catch (e: CronSyntaxException) {
            e
        }
        assertNull(error("* * * *").field)
        assertNull(error("* * * * * *").field)
        assertEquals(0 to "61", error("61 * * * *").let { it.field to it.token })
        assertEquals(1, error("* 24 * * *").field)
        assertEquals(2, error("* * 0 * *").field)
        assertEquals(3, error("* * * 13 *").field)
        assertEquals(4, error("* * * * 8").field)
        assertEquals(0, error("*/0 * * * *").field)
        assertEquals(0, error("5-1 * * * *").field)
        assertEquals(0, error("a * * * *").field)
        assertEquals(1, error("* 1,,2 * * *").field)
        assertNull(Cron.parseOrNull("every 30m"))
    }

    @Test
    fun answersQuicklyForARareSchedule() {
        val start = System.nanoTime()
        val run = Cron.parse("0 0 29 2 *").nextRuns(amsterdam, ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, amsterdam).toInstant(), 5)
        // Eight years ahead holds two leap days.
        assertEquals(listOf(2028, 2032), run.map { it.year })
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 2_000_000_000L)
    }
}
