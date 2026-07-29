package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One answer to "is this book read?", whoever is asking.
 *
 * The reader, the library shelf and calibre-web sync used to work it out
 * separately, with two different thresholds, so a book could be read in
 * one place and unread in another.
 */
class FinishedRuleTest {

    @Test
    fun `the same threshold decides finished and worth resuming`() {
        assertEquals(
            ReadingStatus.FINISHED,
            readingStatusFor(FINISHED_PROGRESSION),
        )
        assertEquals(
            ReadingStatus.READING,
            readingStatusFor(FINISHED_PROGRESSION - 0.001),
        )
        // Whatever counts as finished is exactly what stops being offered
        // to carry on with.
        fun resumes(progression: Double) = shouldResume(
            ResumeCandidate("book", "file", progression),
            leftFromReader = true,
        )
        assertFalse(resumes(FINISHED_PROGRESSION))
        assertTrue(resumes(FINISHED_PROGRESSION - 0.001))
    }

    @Test
    fun `an untouched book is ready to read`() {
        assertEquals(ReadingStatus.READY_TO_READ, readingStatusFor(null))
        assertEquals(ReadingStatus.READY_TO_READ, readingStatusFor(0.0))
    }

    /**
     * The whole point of storing the intent: a book put back on the pile
     * still sits at the last page, so deriving from the position alone
     * would re-mark it read the instant anything looked at it.
     */
    @Test
    fun `marking a book unread survives it sitting at the last page`() {
        assertEquals(
            ReadingStatus.READING,
            readingStatusFor(1.0, FinishedOverride.UNREAD),
        )
    }

    @Test
    fun `marking a book read holds wherever the position is`() {
        assertEquals(
            ReadingStatus.FINISHED,
            readingStatusFor(0.02, FinishedOverride.FINISHED),
        )
        assertEquals(
            ReadingStatus.FINISHED,
            readingStatusFor(null, FinishedOverride.FINISHED),
        )
    }

    @Test
    fun `a book marked unread and never opened is simply unread`() {
        assertEquals(
            ReadingStatus.READY_TO_READ,
            readingStatusFor(0.0, FinishedOverride.UNREAD),
        )
    }

    @Test
    fun `an unknown stored value is read as nobody having said`() {
        assertEquals(FinishedOverride.NONE, FinishedOverride.fromStored(null))
        assertEquals(FinishedOverride.NONE, FinishedOverride.fromStored(0))
        assertEquals(FinishedOverride.FINISHED, FinishedOverride.fromStored(1))
        assertEquals(FinishedOverride.UNREAD, FinishedOverride.fromStored(2))
        assertEquals(FinishedOverride.NONE, FinishedOverride.fromStored(99))
    }

    /**
     * The stored value is an ordinal, so reordering the enum would
     * silently reinterpret every row already on disk.
     */
    @Test
    fun `the stored numbers are fixed`() {
        assertEquals(0, FinishedOverride.NONE.ordinal)
        assertEquals(1, FinishedOverride.FINISHED.ordinal)
        assertEquals(2, FinishedOverride.UNREAD.ordinal)
    }

    /** A deliberate "not read after all" beats a leftover flag on the server. */
    @Test
    fun `a local unread mark is pushed rather than overwritten`() {
        val decision = reconcileReadingState(
            local = ReadingState(0.99, ReadingStatus.READING, updatedAt = 10),
            remote = ReadingState(0.99, ReadingStatus.FINISHED, updatedAt = 99),
            baseline = ReadingBaseline(0.99, ReadingStatus.FINISHED),
            localDirty = true,
            localUnreadOverride = true,
        )
        assertEquals(ReadingStatus.READING, (decision as SyncDecision.Push).state.status)
    }
}
