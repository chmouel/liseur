package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
    fun `the longer spans stay rolling day counts, whatever the locale`() {
        for (weekStart in DayOfWeek.entries) {
            assertEquals(
                wednesday.minusDays(29),
                StatsRange.LAST_30_DAYS.startDate(wednesday, weekStart),
            )
            assertEquals(
                wednesday.withDayOfYear(1),
                StatsRange.THIS_YEAR.startDate(wednesday, weekStart),
            )
            assertNull(StatsRange.ALL_TIME.startDate(wednesday, weekStart))
        }
    }

    @Test
    fun `the stored id survives the range being renamed`() {
        // DataStore holds "7d" from before this was a calendar week. A
        // reader upgrading must not be silently moved to another span.
        assertEquals(StatsRange.THIS_WEEK, StatsRange.fromId("7d"))
        assertEquals("7d", StatsRange.THIS_WEEK.id)
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
