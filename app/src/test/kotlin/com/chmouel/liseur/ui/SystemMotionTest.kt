package com.chmouel.liseur.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Android's animator duration scale as the request it stands for.
 */
class SystemMotionTest {

    @Test
    fun `an ordinary phone animates`() {
        assertFalse(motionRemoved(animatorScale = 1f))
    }

    @Test
    fun `slowed-down animations are still animations`() {
        assertFalse(motionRemoved(animatorScale = 10f))
        assertFalse(motionRemoved(animatorScale = 0.5f))
    }

    /**
     * Accessibility -> Remove animations takes every scale to zero, this
     * one among them, which is how the switch is seen at all.
     */
    @Test
    fun `a scale of zero reads as no motion`() {
        assertTrue(motionRemoved(animatorScale = 0f))
    }

    /**
     * The value comes out of a settings table rather than out of a
     * checkbox, and `Float.parseFloat` answers for anything that looks
     * like a number. A negative scale is not a slower animation, and
     * `NaN` is not a scale at all.
     */
    @Test
    fun `a scale below zero reads as no motion`() {
        assertTrue(motionRemoved(animatorScale = -1f))
    }

    @Test
    fun `a scale that is not a number reads as no motion`() {
        assertTrue(motionRemoved(animatorScale = Float.NaN))
    }
}
