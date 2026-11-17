package com.chmouel.liseur.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour a series card borrows from its cover. */
class CoverTintTest {

    @Test
    fun `a grey cover gives no tint`() {
        val pixels = IntArray(64) { i -> if (i % 2 == 0) 0xFF808080.toInt() else 0xFF202020.toInt() }
        assertNull(coverTint(pixels))
    }

    @Test
    fun `black and white pixels do not dilute the hue`() {
        val pixels = IntArray(100) { 0xFF000000.toInt() }
        repeat(10) { pixels[it] = 0xFFFFFFFF.toInt() }
        repeat(10) { pixels[50 + it] = 0xFFC02020.toInt() }
        assertEquals(0xFFC02020.toInt(), coverTint(pixels))
    }

    @Test
    fun `a small saturated patch outweighs a large dull one`() {
        val pixels = IntArray(100) { 0xFF707888.toInt() }
        repeat(20) { pixels[it] = 0xFF2040E0.toInt() }
        val tint = coverTint(pixels)!!
        val blue = tint and 0xFF
        val red = (tint shr 16) and 0xFF
        assertTrue("expected a blue tint, got ${Integer.toHexString(tint)}", blue - red > 120)
    }

    @Test
    fun `an empty or transparent sample gives no tint`() {
        assertNull(coverTint(IntArray(0)))
        assertNull(coverTint(IntArray(16) { 0x00FF0000 }))
    }
}
