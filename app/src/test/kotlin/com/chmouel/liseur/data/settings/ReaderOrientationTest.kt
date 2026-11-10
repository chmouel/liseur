package com.chmouel.liseur.data.settings

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOrientationTest {

    @Test
    fun `an unknown id follows the system`() {
        assertEquals(ReaderOrientation.AUTO, ReaderOrientation.fromId(null))
        assertEquals(ReaderOrientation.AUTO, ReaderOrientation.fromId("upside_down"))
        assertEquals(ReaderOrientation.LANDSCAPE, ReaderOrientation.fromId("landscape"))
    }

    @Test
    fun `a locked orientation still lets the sensor pick its side`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ReaderOrientation.AUTO.requested)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, ReaderOrientation.PORTRAIT.requested)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ReaderOrientation.LANDSCAPE.requested)
    }

    @Test
    fun `android 16 ignores the lock on large screens only`() {
        assertTrue(ReaderOrientation.honoured(sdk = 35, smallestWidthDp = 800))
        assertTrue(ReaderOrientation.honoured(sdk = 36, smallestWidthDp = 411))
        assertFalse(ReaderOrientation.honoured(sdk = 36, smallestWidthDp = 600))
        assertFalse(ReaderOrientation.honoured(sdk = 37, smallestWidthDp = 840))
    }
}
