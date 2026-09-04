package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClaimTest {

    private val budget = 120L

    @Test
    fun `a touch nothing has taken is never too late for a picture`() {
        val claim = GestureClaim(budget)
        claim.begin(0)

        // A long press is deliberately slower than the budget.
        assertTrue(claim.imageMayWin(5_000))
    }

    @Test
    fun `a late answer cannot take a touch a resize is already using`() {
        val claim = GestureClaim(budget)
        claim.begin(0)
        claim.resizeTook()

        assertTrue(claim.imageMayWin(budget))
        assertFalse(claim.imageMayWin(budget + 1))
    }

    @Test
    fun `a finger lifting out of a pinch does not give the touch back`() {
        val claim = GestureClaim(budget)
        claim.begin(0)
        claim.resizeTook()

        // Two fingers become one, then one becomes two again as another
        // lands. The pinch was forgotten in between; the touch was not.
        assertTrue(claim.claimed)
        assertFalse(claim.imageMayWin(budget + 1))
    }

    @Test
    fun `a genuinely new touch starts unclaimed and off the clock`() {
        val claim = GestureClaim(budget)
        claim.begin(0)
        claim.resizeTook()
        assertFalse(claim.imageMayWin(budget + 1))

        claim.begin(10_000)

        assertFalse(claim.claimed)
        assertTrue(claim.imageMayWin(10_000 + budget * 10))
    }

    @Test
    fun `a touch waits for the document before it becomes a resize`() {
        val claim = GestureClaim(budgetMs = 250L)
        claim.begin(1_000L)
        assertTrue(claim.undecided(1_000L))
        assertTrue(claim.undecided(1_240L))
        assertFalse(claim.undecided(1_260L))
    }

    @Test
    fun `a resize that has taken the touch waits for nothing`() {
        val claim = GestureClaim(budgetMs = 250L)
        claim.begin(1_000L)
        claim.resizeTook()
        assertFalse(claim.undecided(1_010L))
    }
}
