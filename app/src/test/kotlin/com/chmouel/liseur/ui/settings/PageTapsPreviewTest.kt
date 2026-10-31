package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.data.settings.TapLayout
import com.chmouel.liseur.data.settings.TapZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTapsPreviewTest {

    @Test
    fun `the drawing covers the page once and puts one side where the reader does`() {
        val rects = pageTapRects(1080f, 2400f, 2.625f, TapLayout(TapZones.LEFT, 15))
        val covered = rects.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }
        assertEquals(1.0, covered, 1e-4)
        assertTrue(rects.size < 20)
        val back = rects.single { it.area == TapArea.BACK }
        val forward = rects.single { it.area == TapArea.FORWARD }
        assertEquals(0f, back.left, 0f)
        assertEquals(0.15f, back.right, 0.01f)
        assertEquals(back.bottom, forward.top, 0f)
        assertTrue(forward.bottom == 1f)
        assertTrue(rects.none { it.area == TapArea.NOTHING })
    }
}
