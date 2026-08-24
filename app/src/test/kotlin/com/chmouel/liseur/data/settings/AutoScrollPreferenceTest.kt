package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-scroll notch, as it is stored and read back.
 *
 * Nothing in the app writes a step outside the slider's range, so
 * everything here is about the other door: a preference file on a
 * device, which can hold whatever it ends up holding. A step that is not
 * a number is the dangerous one — it survives arithmetic silently and
 * comes out the far end as a page that never moves.
 */
class AutoScrollPreferenceTest {

    @Test
    fun `the default is a notch on the slider`() {
        assertEquals(
            AutoScrollPreference.DEFAULT_STEP,
            AutoScrollPreference.snap(AutoScrollPreference.DEFAULT_STEP),
        )
        assertTrue(AutoScrollPreference.DEFAULT_STEP >= AutoScrollPreference.MIN_STEP)
        assertTrue(AutoScrollPreference.DEFAULT_STEP <= AutoScrollPreference.MAX_STEP)
    }

    @Test
    fun `snapping lands on a whole notch inside the slider`() {
        assertEquals(3f, AutoScrollPreference.snap(3.4f))
        assertEquals(4f, AutoScrollPreference.snap(3.6f))
        assertEquals(AutoScrollPreference.MIN_STEP.toFloat(), AutoScrollPreference.snap(0f))
        assertEquals(AutoScrollPreference.MAX_STEP.toFloat(), AutoScrollPreference.snap(42f))
    }

    @Test
    fun `a stored step outside the slider is brought back onto it`() {
        assertEquals(AutoScrollPreference.MIN_STEP.toFloat(), AutoScrollPreference.sanitize(-99f))
        assertEquals(AutoScrollPreference.MAX_STEP.toFloat(), AutoScrollPreference.sanitize(1e9f))
    }

    @Test
    fun `a stored step that is not a number falls back to the default`() {
        assertEquals(AutoScrollPreference.DEFAULT_STEP, AutoScrollPreference.sanitize(Float.NaN))
        assertEquals(
            AutoScrollPreference.DEFAULT_STEP,
            AutoScrollPreference.sanitize(Float.POSITIVE_INFINITY),
        )
        assertEquals(
            AutoScrollPreference.DEFAULT_STEP,
            AutoScrollPreference.sanitize(Float.NEGATIVE_INFINITY),
        )
    }

    @Test
    fun `snapping a step that is not a number does not throw`() {
        // Float.roundToInt() throws on NaN, so snap has to go through
        // sanitize rather than round first and ask questions later.
        assertEquals(AutoScrollPreference.DEFAULT_STEP, AutoScrollPreference.snap(Float.NaN))
    }

    @Test
    fun `a sanitised step is always somewhere the slider can sit`() {
        val awkward = listOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -1f, 0f, 0.4f, 5.5f, 10.5f, 1e30f, -1e30f,
        )
        awkward.forEach { step ->
            val safe = AutoScrollPreference.sanitize(step)
            assertTrue("$step sanitised to $safe, which is not a number", safe.isFinite())
            assertTrue("$step sanitised below the slider", safe >= AutoScrollPreference.MIN_STEP)
            assertTrue("$step sanitised above the slider", safe <= AutoScrollPreference.MAX_STEP)
        }
    }
}
