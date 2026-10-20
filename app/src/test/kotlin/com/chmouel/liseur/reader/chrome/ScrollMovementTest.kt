package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollMovementTest {

    @Test
    fun `a finger scroll between two ungated samples is the reader`() {
        val movement = ScrollMovement()
        assertFalse(movement.sample(0, automatic = false))
        assertTrue(movement.sample(120, automatic = false))
        assertFalse(movement.sample(120, automatic = false))
    }

    @Test
    fun `the opening restoration landing after the gate opens is not a scroll`() {
        val movement = ScrollMovement()
        assertFalse(movement.sample(0, automatic = true))
        assertFalse(movement.sample(4_000, automatic = false))
        assertTrue(movement.sample(4_100, automatic = false))
    }

    @Test
    fun `a place the navigator was sent to is a new baseline`() {
        val movement = ScrollMovement()
        movement.sample(0, automatic = false)
        movement.movedForReader()
        assertFalse(movement.sample(9_000, automatic = false))
        assertTrue(movement.sample(9_050, automatic = false))
    }

    @Test
    fun `a move sampled before its report arrives is not a scroll`() {
        val movement = ScrollMovement()
        movement.sample(0, automatic = false)
        assertFalse(movement.sample(9_000, automatic = true))
        movement.movedForReader()
        assertFalse(movement.sample(9_000, automatic = false))
        assertTrue(movement.sample(9_050, automatic = false))
    }

    @Test
    fun `samples during an automatic move stay suppressed until capture ends`() {
        val movement = ScrollMovement()
        movement.sample(0, automatic = false)
        movement.beginAutomaticMove()
        assertFalse(movement.sample(1_000, automatic = false))
        assertFalse(movement.sample(2_000, automatic = false))
        movement.endAutomaticMove()
        assertFalse(movement.sample(2_050, automatic = false))
        assertTrue(movement.sample(2_100, automatic = false))
    }

    @Test
    fun `a missing page keeps the pending move for the next sample`() {
        val movement = ScrollMovement()
        movement.sample(0, automatic = false)
        movement.movedForReader()
        assertFalse(movement.sample(null, automatic = false))
        assertFalse(movement.sample(500, automatic = false))
    }
}
