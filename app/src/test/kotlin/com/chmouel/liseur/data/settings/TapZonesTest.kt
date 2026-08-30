package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The preset is read back out of a file on a device, so the only thing
 * worth pinning down is that an id that is not one of the two — from a
 * version that has not been written yet, or from a corrupt store — lands
 * a reader on the layout the app has always had rather than reversing
 * their page turns.
 */
class TapZonesTest {

    @Test
    fun `every preset survives a round trip through its id`() {
        for (zones in TapZones.entries) {
            assertEquals(zones, TapZones.fromId(zones.id))
        }
    }

    @Test
    fun `an id from nowhere is the standard layout`() {
        assertEquals(TapZones.STANDARD, TapZones.Default)
        assertEquals(TapZones.STANDARD, TapZones.fromId(null))
        assertEquals(TapZones.STANDARD, TapZones.fromId(""))
        assertEquals(TapZones.STANDARD, TapZones.fromId("both_forward"))
    }

    @Test
    fun `only the swapped preset reorders the sides`() {
        assertEquals(false, TapZones.STANDARD.swapped)
        assertEquals(true, TapZones.SWAPPED.swapped)
    }
}
