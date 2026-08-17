package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesKeyTest {

    @Test
    fun `nothing is not a series`() {
        assertEquals("", seriesKey(null))
        assertEquals("", seriesKey(""))
        assertEquals("", seriesKey("   "))
    }

    @Test
    fun `case and surrounding space do not make two series`() {
        assertEquals(seriesKey("Wheel of Time"), seriesKey("  wheel of TIME "))
    }

    @Test
    fun `accents do not make two series`() {
        assertEquals(seriesKey("Les Misérables"), seriesKey("Les Miserables"))
    }

    @Test
    fun `the two Greek lower-case sigmas name one series`() {
        assertEquals(seriesKey("Κόσμος"), seriesKey("Κόσμοσ"))
    }

    @Test
    fun `a leading article does not make two series`() {
        assertEquals(seriesKey("The Expanse"), seriesKey("Expanse"))
        assertEquals(seriesKey("La Comédie humaine"), seriesKey("Comedie humaine"))
    }

    @Test
    fun `different series stay apart`() {
        assertNotEquals(seriesKey("Dune"), seriesKey("Duna"))
    }

    @Test
    fun `a whole number reads without a decimal point`() {
        assertEquals("1", seriesIndexLabel(1.0))
        assertEquals("14", seriesIndexLabel(14.0))
    }

    @Test
    fun `a half volume keeps only the digits it needs`() {
        assertEquals("7.5", seriesIndexLabel(7.50))
        assertEquals("2.25", seriesIndexLabel(2.25))
    }

    @Test
    fun `a decimal volume does not expose its binary representation`() {
        assertEquals("7.1", seriesIndexLabel(7.1))
        assertEquals("3.3", seriesIndexLabel(3.3))
        assertEquals("1.05", seriesIndexLabel(1.05))
    }

    @Test
    fun `an absent or impossible number has no label`() {
        assertNull(seriesIndexLabel(null))
        assertNull(seriesIndexLabel(Double.NaN))
        assertNull(seriesIndexLabel(Double.POSITIVE_INFINITY))
    }
}
