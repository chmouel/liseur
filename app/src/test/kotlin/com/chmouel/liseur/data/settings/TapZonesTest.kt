package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The preset is read back out of a file on a device, so the only thing
 * worth pinning down is that an id it does not know, from a version
 * that has not been written yet or from a corrupt store, lands a reader
 * on the layout the app has always had, and that the old Swapped id
 * still means the left thumb.
 */
class TapZonesTest {

    @Test
    fun `every preset survives a round trip through its id`() {
        for (zones in TapZones.entries) {
            assertEquals(zones, TapZones.fromId(zones.id))
        }
    }

    @Test
    fun `an id from nowhere is both sides`() {
        assertEquals(TapZones.BOTH, TapZones.Default)
        assertEquals(TapZones.BOTH, TapZones.fromId(null))
        assertEquals(TapZones.BOTH, TapZones.fromId(""))
        assertEquals(TapZones.BOTH, TapZones.fromId("both_forward"))
    }

    @Test
    fun `the old swapped preset reads as the left side and standard as both`() {
        assertEquals(TapZones.LEFT, TapZones.fromId("swapped"))
        assertEquals(TapZones.BOTH, TapZones.fromId("standard"))
    }

    @Test
    fun `every preset but off turns a side into a page`() {
        assertEquals(listOf(TapZones.OFF), TapZones.entries.filterNot { it.turnsPages })
    }

    @Test
    fun `a stored side width that is not a stop reads as the original layout`() {
        assertEquals(TapSideWidth.WIDE, TapSideWidth.sanitize(null))
        assertEquals(TapSideWidth.WIDE, TapSideWidth.sanitize(17))
        assertEquals(TapSideWidth.WIDE, TapSideWidth.sanitize(-5))
        assertEquals(15, TapSideWidth.sanitize(15))
    }
}
