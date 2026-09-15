package com.chmouel.liseur.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Android's animation scales as the request they stand for.
 */
class SystemMotionTest {

    @Test
    fun `an ordinary phone animates`() {
        assertFalse(motionRemoved(animatorScale = 1f, transitionScale = 1f))
    }

    @Test
    fun `slowed-down animations are still animations`() {
        assertFalse(motionRemoved(animatorScale = 10f, transitionScale = 0.5f))
    }

    @Test
    fun `the accessibility switch takes both scales down`() {
        assertTrue(motionRemoved(animatorScale = 0f, transitionScale = 0f))
    }

    /**
     * Developer options sets each scale on its own, so either at zero is
     * a reader who has asked for less motion than the other one offers.
     */
    @Test
    fun `either scale alone is enough`() {
        assertTrue(motionRemoved(animatorScale = 0f, transitionScale = 1f))
        assertTrue(motionRemoved(animatorScale = 1f, transitionScale = 0f))
    }

    /**
     * The value comes out of a settings table rather than out of a
     * checkbox, and `Float.parseFloat` answers for anything that looks
     * like a number. A negative scale is not a slower animation, and
     * `NaN` is not a scale at all.
     */
    @Test
    fun `a scale below zero reads as no motion`() {
        assertTrue(motionRemoved(animatorScale = -1f, transitionScale = 1f))
        assertTrue(motionRemoved(animatorScale = 1f, transitionScale = -0.25f))
    }

    @Test
    fun `a scale that is not a number reads as no motion`() {
        assertTrue(motionRemoved(animatorScale = Float.NaN, transitionScale = 1f))
        assertTrue(motionRemoved(animatorScale = 1f, transitionScale = Float.NaN))
    }
}
