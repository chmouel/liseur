package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room a page keeps clear for the footer. The footer's text is set
 * in sp and follows the system font size; the reservation is derived
 * from the same line height and the same paddings, so it must follow
 * too — the fixed 26dp it replaced is exactly the bug being guarded
 * against here.
 */
class FooterMetricsTest {

    @Test
    fun `reserves the line and both paddings at default scale`() {
        // 16sp line + 2 × 6dp padding + 4dp clearance.
        assertEquals(32f, FooterMetrics.reservedHeightDp(16f, 1.0f), 1e-4f)
    }

    @Test
    fun `grows with the font scale`() {
        assertEquals(36.8f, FooterMetrics.reservedHeightDp(16f, 1.3f), 1e-4f)
        assertEquals(48f, FooterMetrics.reservedHeightDp(16f, 2.0f), 1e-4f)
    }

    @Test
    fun `keeps a fractional scale exact rather than rounding it away`() {
        assertEquals(34.4f, FooterMetrics.reservedHeightDp(16f, 1.15f), 1e-4f)
    }

    @Test
    fun `only the text scales, never the paddings`() {
        val atOne = FooterMetrics.reservedHeightDp(16f, 1.0f)
        val atTwo = FooterMetrics.reservedHeightDp(16f, 2.0f)
        assertEquals(16f, atTwo - atOne, 1e-4f)
    }

    @Test
    fun `clears the footer's drawn height at every scale`() {
        for (scale in floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f, 2.0f)) {
            val drawn = 16f * scale + FooterMetrics.VERTICAL_PADDING_DP * 2
            assertTrue(
                "no clearance left at font scale $scale",
                FooterMetrics.reservedHeightDp(16f, scale) > drawn,
            )
        }
    }
}
