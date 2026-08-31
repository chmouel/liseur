package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Where each span begins, and which day of the week it begins on. */
class StatsRangeTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    // A Wednesday, so a week-aligned span is neither empty nor whole.
    private val wednesday: LocalDate = LocalDate.of(2026, 8, 5)

    @Test
    fun `the week begins where the reader's calendar begins`() {
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        assertEquals(
            LocalDate.of(2026, 8, 3),
            StatsRange.THIS_WEEK.startDate(wednesday, DayOfWeek.MONDAY),
        )
        assertEquals(
            LocalDate.of(2026, 8, 2),
            StatsRange.THIS_WEEK.startDate(wednesday, DayOfWeek.SUNDAY),
        )
        // Some locales, Maldivian among them, start on a Friday.
        assertEquals(
            LocalDate.of(2026, 7, 31),
            StatsRange.THIS_WEEK.startDate(wednesday, DayOfWeek.FRIDAY),
        )
    }

    @Test
    fun `the first day of the week is a week of one day, not of none`() {
        val monday = LocalDate.of(2026, 8, 3)
        assertEquals(monday, StatsRange.THIS_WEEK.startDate(monday, DayOfWeek.MONDAY))
        assertEquals(1, StatsRange.THIS_WEEK.days(monday, DayOfWeek.MONDAY))
    }

    @Test
    fun `the last day of the week is a week of seven`() {
        val sunday = LocalDate.of(2026, 8, 9)
        assertEquals(
            LocalDate.of(2026, 8, 3),
            StatsRange.THIS_WEEK.startDate(sunday, DayOfWeek.MONDAY),
        )
        assertEquals(7, StatsRange.THIS_WEEK.days(sunday, DayOfWeek.MONDAY))
    }

    @Test
    fun `the week grows a day at a time and never past seven`() {
        val monday = LocalDate.of(2026, 8, 3)
        val counted = (0L..6L).map {
            StatsRange.THIS_WEEK.days(monday.plusDays(it), DayOfWeek.MONDAY)
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), counted)
    }

    @Test
    fun `a partial week is always short enough to draw as bars`() {
        for (offset in 0L..6L) {
            assertTrue(
                StatsRange.THIS_WEEK.suitsDailyBars(
                    LocalDate.of(2026, 8, 3).plusDays(offset),
                    DayOfWeek.MONDAY,
                ),
            )
        }
    }

    @Test
    fun `the calendar spans ignore the locale except for the week`() {
        for (weekStart in DayOfWeek.entries) {
            assertEquals(
                wednesday.withDayOfMonth(1),
                StatsRange.THIS_MONTH.startDate(wednesday, weekStart),
            )
            assertEquals(
                wednesday.withDayOfYear(1),
                StatsRange.THIS_YEAR.startDate(wednesday, weekStart),
            )
            assertNull(StatsRange.ALL_TIME.startDate(wednesday, weekStart))
        }
    }

    @Test
    fun `a month is drawn as bars right up to its last day`() {
        // Thirty-one days is what MAX_BAR_DAYS is sized for. A month that
        // fell back to the heatmap on the 31st would change shape once a
        // quarter for no reason the reader could see.
        val lastOfMarch = LocalDate.of(2026, 3, 31)
        assertEquals(31, StatsRange.THIS_MONTH.days(lastOfMarch, DayOfWeek.MONDAY))
        assertTrue(StatsRange.THIS_MONTH.suitsDailyBars(lastOfMarch, DayOfWeek.MONDAY))
        assertTrue(!StatsRange.THIS_YEAR.suitsDailyBars(lastOfMarch, DayOfWeek.MONDAY))
    }

    @Test
    fun `the stored id survives the range being renamed`() {
        // DataStore holds "7d" from before this was a calendar week. A
        // reader upgrading must not be silently moved to another span.
        assertEquals(StatsRange.THIS_WEEK, StatsRange.fromId("7d"))
        assertEquals("7d", StatsRange.THIS_WEEK.id)
    }

    @Test
    fun `a retired span lands on the nearest one still offered`() {
        // These three are on readers' disks and can never be selected
        // again. Falling through to the default would drop someone who
        // had asked for a year of history down to this week without
        // saying so.
        assertEquals(StatsRange.THIS_MONTH, StatsRange.fromId("30d"))
        assertEquals(StatsRange.THIS_YEAR, StatsRange.fromId("90d"))
        assertEquals(StatsRange.THIS_YEAR, StatsRange.fromId("365d"))
        // Anything genuinely unknown still falls back.
        assertEquals(StatsRange.Default, StatsRange.fromId("42d"))
        assertEquals(StatsRange.Default, StatsRange.fromId(null))
    }

    @Test
    fun `a week is compared with the same weekdays of the week before`() {
        val spans = StatsRange.THIS_WEEK.comparison(wednesday, DayOfWeek.MONDAY)!!
        assertEquals(ComparisonPeriod.WEEK, spans.period)
        assertEquals(DateSpan(LocalDate.of(2026, 8, 3), wednesday), spans.current)
        assertEquals(
            DateSpan(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 29)),
            spans.previous,
        )
    }

    @Test
    fun `every locale's week is compared with three of its own days`() {
        // Whichever day the week begins on, a Wednesday is three days in
        // for a Monday-first reader and four for a Sunday-first one, and
        // the baseline has to hold exactly as many.
        for (weekStart in DayOfWeek.entries) {
            val spans = StatsRange.THIS_WEEK.comparison(wednesday, weekStart)!!
            assertEquals(
                ChronoUnit.DAYS.between(spans.current.from, spans.current.to),
                ChronoUnit.DAYS.between(spans.previous.from, spans.previous.to),
            )
            assertEquals(spans.current.from.dayOfWeek, spans.previous.from.dayOfWeek)
            assertEquals(spans.current.to.dayOfWeek, spans.previous.to.dayOfWeek)
        }
    }

    @Test
    fun `the first day of a period is compared with one day, not with none`() {
        val firstOfMonth = LocalDate.of(2026, 8, 1)
        val month = StatsRange.THIS_MONTH.comparison(firstOfMonth, DayOfWeek.MONDAY)!!
        assertEquals(DateSpan(firstOfMonth, firstOfMonth), month.current)
        assertEquals(
            DateSpan(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1)),
            month.previous,
        )

        val newYear = LocalDate.of(2026, 1, 1)
        val year = StatsRange.THIS_YEAR.comparison(newYear, DayOfWeek.MONDAY)!!
        assertEquals(DateSpan(newYear, newYear), year.current)
        // A comparison made on New Year's Day reaches into last January,
        // not into last December.
        assertEquals(
            DateSpan(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1)),
            year.previous,
        )
    }

    @Test
    fun `a month end with no counterpart stops on the last day there is`() {
        // The 31st of March against February, which has 28 days in 2026.
        val march = StatsRange.THIS_MONTH.comparison(
            LocalDate.of(2026, 3, 31),
            DayOfWeek.MONDAY,
        )!!
        assertEquals(
            DateSpan(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            march.previous,
        )

        // The 31st of May against April, which has 30.
        val may = StatsRange.THIS_MONTH.comparison(LocalDate.of(2026, 5, 31), DayOfWeek.MONDAY)!!
        assertEquals(
            DateSpan(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
            may.previous,
        )

        // The 30th of March against February again: still clamped.
        val thirtieth = StatsRange.THIS_MONTH.comparison(
            LocalDate.of(2026, 3, 30),
            DayOfWeek.MONDAY,
        )!!
        assertEquals(LocalDate.of(2026, 2, 28), thirtieth.previous.to)

        // A day that does exist in the previous month is not clamped.
        val fifteenth = StatsRange.THIS_MONTH.comparison(
            LocalDate.of(2026, 3, 15),
            DayOfWeek.MONDAY,
        )!!
        assertEquals(
            DateSpan(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 15)),
            fifteenth.previous,
        )
    }

    @Test
    fun `a leap day is compared with the 28th of February`() {
        val leapDay = LocalDate.of(2024, 2, 29)
        assertEquals(DayOfWeek.THURSDAY, leapDay.dayOfWeek)
        val spans = StatsRange.THIS_YEAR.comparison(leapDay, DayOfWeek.MONDAY)!!
        assertEquals(ComparisonPeriod.YEAR, spans.period)
        assertEquals(DateSpan(LocalDate.of(2024, 1, 1), leapDay), spans.current)
        assertEquals(
            DateSpan(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 2, 28)),
            spans.previous,
        )
    }

    @Test
    fun `a span with no previous period has nothing to compare`() {
        for (weekStart in DayOfWeek.entries) {
            assertNull(StatsRange.ALL_TIME.comparison(wednesday, weekStart))
        }
    }

    @Test
    fun `English and French disagree about Sunday, and both are obeyed`() {
        assertEquals(DayOfWeek.SUNDAY, localeWeekStart(Locale.US))
        assertEquals(DayOfWeek.MONDAY, localeWeekStart(Locale.FRANCE))
    }

    @Test
    fun `the chart spans the week so far, empty days included`() {
        // Read on the Monday and again on the Wednesday, nothing between.
        val sessions = listOf(
            SessionSpan("a", noon(LocalDate.of(2026, 8, 3)), 60_000),
            SessionSpan("a", noon(wednesday), 60_000),
        )
        val stats = readingStats(
            sessions = sessions,
            books = mapOf("a" to StatsBook("a", "A", null, null, false)),
            zone = zone,
            today = wednesday,
            range = StatsRange.THIS_WEEK,
            weekStart = DayOfWeek.MONDAY,
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 4),
                wednesday,
            ),
            stats.recent.map { it.date },
        )
        // The gap is the information. Dropping it would read as an
        // unbroken run of two days.
        assertEquals(0L, stats.recent[1].totalMs)
    }

    @Test
    fun `a Sunday-first week reaches back a day further than a Monday-first one`() {
        // The Sunday before the Wednesday. An American reader counts it
        // as this week; a French one counts it as last.
        val sessions = listOf(SessionSpan("a", noon(LocalDate.of(2026, 8, 2)), 60_000))
        val books = mapOf("a" to StatsBook("a", "A", null, null, false))
        fun countedFrom(weekStart: DayOfWeek) = readingStats(
            sessions = sessions,
            books = books,
            zone = zone,
            today = wednesday,
            range = StatsRange.THIS_WEEK,
            weekStart = weekStart,
        ).totalMs

        assertEquals(60_000L, countedFrom(DayOfWeek.SUNDAY))
        assertEquals(0L, countedFrom(DayOfWeek.MONDAY))
    }

    private fun noon(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}
