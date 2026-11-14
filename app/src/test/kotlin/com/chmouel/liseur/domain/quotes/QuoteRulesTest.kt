package com.chmouel.liseur.domain.quotes

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteRulesTest {

    @Test
    fun `a line inside the read point is inside it, one past it is not`() {
        assertTrue(QuoteRules.withinCeiling(0.4, 0.5))
        assertTrue(QuoteRules.withinCeiling(0.5, 0.5))
        assertFalse(QuoteRules.withinCeiling(0.9, 0.5))
    }

    @Test
    fun `the last bits of a fraction are not a spoiler`() {
        // Two writers of the same place differ in the tail; a whole page
        // of difference is a different thing entirely.
        assertTrue(QuoteRules.withinCeiling(0.5 + 1e-9, 0.5))
        assertFalse(QuoteRules.withinCeiling(0.51, 0.5))
    }

    @Test
    fun `a mark nobody placed is not evidence of having read there`() {
        assertFalse(QuoteRules.withinCeiling(null, 1.0))
        assertFalse(QuoteRules.withinCeiling(Double.NaN, 1.0))
    }

    @Test
    fun `a mark in the reader's own resource is compared where both are measured the same way`() {
        val chapter = QuoteRules.PassagePlace("chapter-7.xhtml", 0.8)
        val here = QuoteRules.PassagePlace("chapter-7.xhtml", 0.5)

        // The whole-book fractions could say anything: another client
        // interpolates its own position list. Inside one resource both
        // are fractions of the same file.
        assertFalse(QuoteRules.markWithinCeiling(chapter, 0.1, here, 0.9))
        assertTrue(
            QuoteRules.markWithinCeiling(
                QuoteRules.PassagePlace("chapter-7.xhtml", 0.4),
                0.95,
                here,
                0.2,
            ),
        )
    }

    @Test
    fun `a mark elsewhere in the book falls back to the read ceiling`() {
        val mark = QuoteRules.PassagePlace("chapter-2.xhtml", 0.9)
        val here = QuoteRules.PassagePlace("chapter-9.xhtml", 0.1)

        assertTrue(QuoteRules.markWithinCeiling(mark, 0.3, here, 0.5))
        assertFalse(QuoteRules.markWithinCeiling(mark, 0.7, here, 0.5))
        // Nothing placed locally: the ceiling is all there is.
        assertTrue(QuoteRules.markWithinCeiling(mark, 0.3, null, 0.5))
        assertFalse(QuoteRules.markWithinCeiling(null, null, here, 0.5))
    }

    @Test
    fun `a scan still describes the book only while the work is the same`() {
        assertTrue(QuoteRules.stillSameWork("urn:isbn:1", "urn:isbn:1"))
        assertTrue(QuoteRules.stillSameWork(null, null))
        assertFalse(QuoteRules.stillSameWork("urn:isbn:1", "urn:isbn:2"))
        // One side naming the work and the other not is not the same claim.
        assertFalse(QuoteRules.stillSameWork(null, "urn:isbn:1"))
        assertFalse(QuoteRules.stillSameWork("urn:isbn:1", null))
    }

    @Test
    fun `the wait is until midnight, and never zero`() {
        val zone = ZoneId.of("Europe/Paris")
        val today = LocalDate.of(2024, 3, 9)
        val noon = ZonedDateTime.of(today.atTime(12, 0), zone).toInstant().toEpochMilli()
        val justBefore = ZonedDateTime.of(today.atTime(23, 59, 59), zone).toInstant().toEpochMilli()

        assertEquals(12 * 60 * 60 * 1000L, QuoteRules.millisUntilTomorrow(today, noon, zone))
        // A clock a hair before midnight must not ask again every
        // millisecond, and a clock already past it waits rather than
        // spinning.
        assertEquals(60_000L, QuoteRules.millisUntilTomorrow(today, justBefore, zone))
        assertEquals(60_000L, QuoteRules.millisUntilTomorrow(today, noon + 24 * 60 * 60 * 1000L, zone))
    }
}
