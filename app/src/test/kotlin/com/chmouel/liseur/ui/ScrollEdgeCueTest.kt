package com.chmouel.liseur.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollEdgeCueTest {

    @Test
    fun aRowThatFitsShowsNothing() {
        assertEquals(EdgeCue(0f, 0f), scrollEdgeCue(value = 0, maxValue = 0, fadePx = 20f, rtl = false))
    }

    @Test
    fun atTheStartOnlyTheFarSideFades() {
        assertEquals(EdgeCue(0f, 1f), scrollEdgeCue(value = 0, maxValue = 200, fadePx = 20f, rtl = false))
    }

    @Test
    fun inTheMiddleBothSidesFade() {
        assertEquals(EdgeCue(1f, 1f), scrollEdgeCue(value = 100, maxValue = 200, fadePx = 20f, rtl = false))
    }

    @Test
    fun atTheEndOnlyTheNearSideFades() {
        assertEquals(EdgeCue(1f, 0f), scrollEdgeCue(value = 200, maxValue = 200, fadePx = 20f, rtl = false))
    }

    @Test
    fun theFadeEasesInOverItsWidthNearAnEnd() {
        assertEquals(EdgeCue(0.25f, 1f), scrollEdgeCue(value = 5, maxValue = 200, fadePx = 20f, rtl = false))
        assertEquals(EdgeCue(1f, 0.5f), scrollEdgeCue(value = 190, maxValue = 200, fadePx = 20f, rtl = false))
    }

    @Test
    fun rightToLeftStartsAtTheRight() {
        assertEquals(EdgeCue(1f, 0f), scrollEdgeCue(value = 0, maxValue = 200, fadePx = 20f, rtl = true))
        assertEquals(EdgeCue(1f, 0f), scrollEdgeCue(canBackward = false, canForward = true, rtl = true))
        assertEquals(EdgeCue(0f, 1f), scrollEdgeCue(canBackward = false, canForward = true, rtl = false))
    }

    @Test
    fun theThumbShowsTheShareOnScreenAndWhere() {
        assertEquals(null, scrollThumb(value = 0, maxValue = 0, viewport = 300f, rtl = false))
        assertEquals(ScrollThumb(0f, 0.75f), scrollThumb(value = 0, maxValue = 100, viewport = 300f, rtl = false))
        assertEquals(ScrollThumb(0.25f, 0.75f), scrollThumb(value = 100, maxValue = 100, viewport = 300f, rtl = false))
        assertEquals(ScrollThumb(0.25f, 0.75f), scrollThumb(value = 0, maxValue = 100, viewport = 300f, rtl = true))
    }
}
