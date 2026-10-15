package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeKeyModeTest {

    @Test
    fun `every mode survives a round trip through its id`() {
        for (mode in VolumeKeyMode.entries) {
            assertEquals(mode, VolumeKeyMode.fromId(mode.id))
        }
    }

    @Test
    fun `an unknown id uses the existing forward-first behavior`() {
        assertEquals(VolumeKeyMode.ON, VolumeKeyMode.Default)
        assertEquals(VolumeKeyMode.ON, VolumeKeyMode.fromId(null))
        assertEquals(VolumeKeyMode.ON, VolumeKeyMode.fromId("sideways"))
    }

    @Test
    fun `each mode chooses the correct volume key direction`() {
        assertEquals(true, VolumeKeyMode.ON.forwardFor(isVolumeDown = true))
        assertEquals(false, VolumeKeyMode.ON.forwardFor(isVolumeDown = false))
        assertNull(VolumeKeyMode.OFF.forwardFor(isVolumeDown = true))
        assertNull(VolumeKeyMode.OFF.forwardFor(isVolumeDown = false))
        assertEquals(false, VolumeKeyMode.REVERSED.forwardFor(isVolumeDown = true))
        assertEquals(true, VolumeKeyMode.REVERSED.forwardFor(isVolumeDown = false))
    }
}
