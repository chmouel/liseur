package com.chmouel.liseur.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSaveDecisionTest {

    @Test
    fun `a pending BookOrbit choice is saved even at the stored place`() {
        assertFalse(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = false,
                settlementPending = true,
            ),
        )
    }

    @Test
    fun `the same stored place skips later redundant saves`() {
        assertTrue(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = false,
                settlementPending = false,
            ),
        )
    }

    @Test
    fun `a pending page capture is not skipped`() {
        assertFalse(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = true,
                settlementPending = false,
            ),
        )
    }

    @Test
    fun `a settled opening skips repeated same-place saves`() {
        assertTrue(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = false,
                settlementPending = false,
            ),
        )
    }

    @Test
    fun `an older flush cannot clear a newer position save`() {
        assertFalse(shouldApplyPositionSaveResult(completedGeneration = 4, currentGeneration = 5))
        assertTrue(shouldApplyPositionSaveResult(completedGeneration = 5, currentGeneration = 5))
    }
}
