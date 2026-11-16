package com.chmouel.liseur.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDecisionTest {

    private fun candidate(
        progression: Double?,
        finished: Boolean = false,
        partialCoverage: Boolean = false,
        override: FinishedOverride = FinishedOverride.NONE,
    ) = ResumeCandidate(
        identity = "calibre:abc",
        fileUrl = "file:///books/abc.epub",
        totalProgression = progression,
        finished = finished,
        partialCoverage = partialCoverage,
        override = override,
    )

    @Test
    fun `resumes the book you were reading`() {
        assertTrue(shouldResume(candidate(0.42), leftFromReader = true))
    }

    @Test
    fun `stays on the library when that is where you left`() {
        assertFalse(shouldResume(candidate(0.42), leftFromReader = false))
    }

    @Test
    fun `stays on the library when there is no book`() {
        assertFalse(shouldResume(null, leftFromReader = true))
    }

    @Test
    fun `does not drop you back into a book you finished`() {
        assertFalse(shouldResume(candidate(0.99), leftFromReader = true))
        assertFalse(shouldResume(candidate(1.0), leftFromReader = true))
    }

    @Test
    fun `back matter still counts as finished`() {
        assertFalse(shouldResume(candidate(FINISHED_PROGRESSION), leftFromReader = true))
        assertTrue(shouldResume(candidate(FINISHED_PROGRESSION - 0.01), leftFromReader = true))
    }

    @Test
    fun `a book marked read never pulls you back in`() {
        assertFalse(shouldResume(candidate(0.1, finished = true), leftFromReader = true))
    }

    @Test
    fun `a book still marked unfinished at the back matter resumes`() {
        assertTrue(shouldResume(candidate(0.99, override = FinishedOverride.READING), leftFromReader = true))
        listOf(FinishedOverride.PAUSED, FinishedOverride.DID_NOT_FINISH).forEach { override ->
            assertFalse(shouldResume(candidate(0.99, override = override), leftFromReader = true))
            assertFalse(shouldResume(candidate(null, override = override), leftFromReader = true))
        }
        assertFalse(
            shouldResume(candidate(0.5, override = FinishedOverride.FINISHED), leftFromReader = true),
        )
    }

    @Test
    fun `a book opened but never placed still resumes`() {
        assertTrue(shouldResume(candidate(null), leftFromReader = true))
    }

    /**
     * A recording that covers part of the narrative stops short of the
     * end by design (ADR-0055, ADR-0066), so the library calls such a
     * book unfinished and resume must agree with it rather than refuse
     * the reader their place.
     */
    @Test
    fun `a partial recording near its end is not a finished book`() {
        assertFalse(shouldResume(candidate(0.99), leftFromReader = true))
        assertTrue(shouldResume(candidate(0.99, partialCoverage = true), leftFromReader = true))
        assertTrue(shouldResume(candidate(0.998, partialCoverage = true), leftFromReader = true))
    }
}
