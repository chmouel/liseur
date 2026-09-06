package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class StatsChartTest {

    @Test
    fun `a month is added into locale aligned weeks clipped to the month`() {
        val days = (1..19).map { day ->
            ReadingDay(LocalDate.of(2026, 8, day), day.toLong())
        }

        val periods = readingPeriods(days, StatsRange.THIS_MONTH, DayOfWeek.MONDAY)

        assertEquals(
            listOf(
                ReadingPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 3),
                ReadingPeriod(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9), 42),
                ReadingPeriod(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16), 91),
                ReadingPeriod(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 19), 54),
            ),
            periods,
        )

        assertEquals(
            ReadingPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), 1),
            readingPeriods(days, StatsRange.THIS_MONTH, DayOfWeek.SUNDAY).first(),
        )
    }

    @Test
    fun `a year is added into months including empty days`() {
        val days = listOf(
            ReadingDay(LocalDate.of(2026, 1, 30), 10),
            ReadingDay(LocalDate.of(2026, 1, 31), 20),
            ReadingDay(LocalDate.of(2026, 2, 1), 0),
            ReadingDay(LocalDate.of(2026, 2, 2), 40),
        )

        assertEquals(
            listOf(
                ReadingPeriod(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 1, 31), 30),
                ReadingPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2), 40),
            ),
            readingPeriods(days, StatsRange.THIS_YEAR, DayOfWeek.SUNDAY),
        )
    }

    @Test
    fun `all time is added into years`() {
        val days = listOf(
            ReadingDay(LocalDate.of(2024, 12, 31), 10),
            ReadingDay(LocalDate.of(2025, 1, 1), 20),
            ReadingDay(LocalDate.of(2025, 1, 2), 30),
        )

        assertEquals(
            listOf(
                ReadingPeriod(LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 31), 10),
                ReadingPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2), 50),
            ),
            readingPeriods(days, StatsRange.ALL_TIME, DayOfWeek.MONDAY),
        )
    }
}
