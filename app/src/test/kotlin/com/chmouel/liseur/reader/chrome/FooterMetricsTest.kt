package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room a page keeps clear for the footer. The footer's text is set
 * in sp and follows the system font size; the caller converts it to dp
 * through Compose's Density (which knows about nonlinear font scaling)
 * and the reservation is derived from that converted height plus the
 * same paddings the footer draws with — the fixed 26dp it replaced is
 * exactly the bug being guarded against here. No navigation-bar inset
 * belongs in the figure: the footer is drawn with the bars hidden.
 */
class FooterMetricsTest {

    @Test
    fun `reserves the line, both paddings and the margin under it`() {
        // 16dp line + 2 × 6dp padding + 4dp clearance + 6dp margin.
        assertEquals(38f, FooterMetrics.reservedHeightDp(16f), 1e-4f)
    }

    @Test
    fun `keeps the footer off the screen's edge`() {
        // The bars are hidden whenever the footer is drawn, so nothing
        // but this margin stands between it and the bottom of the glass.
        assertTrue(FooterMetrics.BOTTOM_MARGIN_DP > 0f)
    }

    @Test
    fun `grows with the converted line height`() {
        // 16sp at font scale 1.3 and 2.0 under linear scaling; the
        // exact conversion is Density's business, the reservation just
        // has to carry whatever it says.
        assertEquals(42.8f, FooterMetrics.reservedHeightDp(20.8f), 1e-4f)
        assertEquals(54f, FooterMetrics.reservedHeightDp(32f), 1e-4f)
    }

    @Test
    fun `keeps a fractional height exact rather than rounding it away`() {
        assertEquals(40.4f, FooterMetrics.reservedHeightDp(18.4f), 1e-4f)
    }

    @Test
    fun `only the text grows, never the paddings`() {
        val atOne = FooterMetrics.reservedHeightDp(16f)
        val atTwo = FooterMetrics.reservedHeightDp(32f)
        assertEquals(16f, atTwo - atOne, 1e-4f)
    }

    @Test
    fun `clears the footer's drawn height at every text size`() {
        for (lineDp in floatArrayOf(13.6f, 16f, 18.4f, 20.8f, 32f)) {
            val drawn = lineDp + FooterMetrics.VERTICAL_PADDING_DP * 2 +
                FooterMetrics.BOTTOM_MARGIN_DP
            assertTrue(
                "no clearance left at line height ${lineDp}dp",
                FooterMetrics.reservedHeightDp(lineDp) > drawn,
            )
        }
    }
}
