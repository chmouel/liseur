package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentsScreenTest {
    @Test
    fun `collapsed row believes what it measured`() {
        assertTrue(latchedOverflow(previous = false, expanded = false, measured = true))
        assertFalse(latchedOverflow(previous = true, expanded = false, measured = false))
    }

    @Test
    fun `expanded row keeps the answer it had while collapsed`() {
        // An expanded Text has no line cap, so it always reports that it
        // fits. Believing that would take the "Show less" control away.
        assertTrue(latchedOverflow(previous = true, expanded = true, measured = false))
    }

    @Test
    fun `expanding never invents something to show`() {
        assertFalse(latchedOverflow(previous = false, expanded = true, measured = true))
    }
}
