package com.chmouel.liseur.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSaveDecisionTest {

    @Test
    fun `a pending verified opening is settled by the first save even at the stored place`() {
        assertFalse(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = false,
                verifiedOpeningPending = true,
            ),
        )
    }

    @Test
    fun `the same stored place skips later redundant saves`() {
        assertTrue(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = false,
                verifiedOpeningPending = false,
            ),
        )
    }

    @Test
    fun `a pending page capture is not skipped`() {
        assertFalse(
            shouldSkipPositionSave(
                sameAsBaseline = true,
                awaitingPageCapture = true,
                verifiedOpeningPending = false,
            ),
        )
    }
}
