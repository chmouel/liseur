package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStateMergeTest {

    private fun state(
        progression: Double?,
        status: ReadingStatus = ReadingStatus.READING,
        at: Long = 1_000L,
    ) = ReadingState(progression, status, at)

    private fun baseline(
        progression: Double?,
        status: ReadingStatus = ReadingStatus.READING,
    ) = ReadingBaseline(progression, status)

    @Test
    fun `nothing anywhere is nothing to do`() {
        assertEquals(
            SyncDecision.InSync,
            reconcileReadingState(null, null, null, localDirty = false),
        )
    }

    @Test
    fun `a book only the server knows is taken`() {
        val decision = reconcileReadingState(
            local = null,
            remote = state(0.4),
            baseline = null,
            localDirty = false,
        )
        assertEquals(0.4, (decision as SyncDecision.Pull).state.progression)
    }

    @Test
    fun `a book only this device knows is sent, if it owes it`() {
        val decision = reconcileReadingState(
            local = state(0.4),
            remote = null,
            baseline = null,
            localDirty = true,
        )
        assertEquals(0.4, (decision as SyncDecision.Push).state.progression)
    }

    @Test
    fun `a book the server was silent about and that owes nothing is left alone`() {
        assertEquals(
            SyncDecision.InSync,
            reconcileReadingState(
                local = state(0.4),
                remote = null,
                baseline = baseline(0.4),
                localDirty = false,
            ),
        )
    }

    @Test
    fun `positions within a page of each other count as the same place`() {
        assertEquals(
            SyncDecision.InSync,
            reconcileReadingState(
                local = state(0.5),
                remote = state(0.502),
                baseline = baseline(0.5),
                localDirty = true,
            ),
        )
    }

    @Test
    fun `different exact anchors inside percentage tolerance are pulled`() {
        val decision = reconcileReadingState(
            local = state(0.071),
            remote = state(0.074),
            baseline = baseline(0.071),
            localDirty = false,
            exactPositionAgreement = false,
        )

        assertEquals(0.074, (decision as SyncDecision.Pull).state.progression)
    }

    @Test
    fun `different exact anchors preserve two locally changed positions`() {
        val decision = reconcileReadingState(
            local = state(0.072),
            remote = state(0.074),
            baseline = baseline(0.071),
            localDirty = true,
            exactPositionAgreement = false,
        )

        assertTrue(decision is SyncDecision.Conflict)
    }

    @Test
    fun `the same exact anchor agrees despite percentage layout differences`() {
        assertEquals(
            SyncDecision.InSync,
            reconcileReadingState(
                local = state(0.071),
                remote = state(0.078),
                baseline = baseline(0.071),
                localDirty = false,
                exactPositionAgreement = true,
            ),
        )
    }

    @Test
    fun `only the server moved, so its position is taken`() {
        val decision = reconcileReadingState(
            local = state(0.3),
            remote = state(0.7),
            baseline = baseline(0.3),
            localDirty = false,
        )
        assertEquals(0.7, (decision as SyncDecision.Pull).state.progression)
    }

    @Test
    fun `only this device moved, so its position is sent`() {
        val decision = reconcileReadingState(
            local = state(0.7),
            remote = state(0.3),
            baseline = baseline(0.3),
            localDirty = true,
        )
        assertEquals(0.7, (decision as SyncDecision.Push).state.progression)
    }

    @Test
    fun `going back to reread is sent, not overwritten by the further server`() {
        // The whole reason a baseline is stored. Both sides were at 90%;
        // this device deliberately went back to the first chapter. Without
        // the baseline this is indistinguishable from the other device
        // having read on, and "prefer the further position" would throw
        // the reread away.
        val decision = reconcileReadingState(
            local = state(0.02),
            remote = state(0.9),
            baseline = baseline(0.9),
            localDirty = true,
        )
        assertEquals(0.02, (decision as SyncDecision.Push).state.progression)
    }

    @Test
    fun `both sides moved, so neither is chosen`() {
        val decision = reconcileReadingState(
            local = state(0.6),
            remote = state(0.8),
            baseline = baseline(0.3),
            localDirty = true,
        )
        val conflict = (decision as SyncDecision.Conflict)
        assertEquals(0.6, conflict.local.progression)
        assertEquals(0.8, conflict.remote.progression)
    }

    @Test
    fun `one local page in a long book is not erased by a remote move`() {
        // Live regression: one position out of 498 is smaller than the
        // tolerance used for percentages from different renderings. The
        // revision still proves that this device genuinely turned it.
        val decision = reconcileReadingState(
            local = state(0.58635),
            remote = state(0.57646),
            baseline = baseline(0.58434),
            localDirty = true,
        )

        (decision as SyncDecision.Conflict)
    }

    @Test
    fun `the further position does not win a conflict on its own`() {
        val decision = reconcileReadingState(
            local = state(0.1),
            remote = state(0.95),
            baseline = baseline(0.05),
            localDirty = true,
        )
        (decision as SyncDecision.Conflict)
    }

    @Test
    fun `a newer clock does not win a conflict either`() {
        // calibre-web stamps its own time and ignores ours, so the two
        // timestamps are not on the same scale and cannot be compared.
        val decision = reconcileReadingState(
            local = state(0.6, at = 10L),
            remote = state(0.8, at = 9_999_999L),
            baseline = baseline(0.3),
            localDirty = true,
        )
        (decision as SyncDecision.Conflict)
    }

    @Test
    fun `an older clock does not lose a conflict either`() {
        // The same skew the other way round. A server whose clock lags
        // ours must not make its position look stale enough to discard.
        val decision = reconcileReadingState(
            local = state(0.6, at = 9_999_999L),
            remote = state(0.8, at = 10L),
            baseline = baseline(0.3),
            localDirty = true,
        )
        (decision as SyncDecision.Conflict)
    }

    @Test
    fun `no baseline and a divergent server is a conflict, not a guess`() {
        val decision = reconcileReadingState(
            local = state(0.6),
            remote = state(0.8),
            baseline = null,
            localDirty = true,
        )
        (decision as SyncDecision.Conflict)
    }

    @Test
    fun `no baseline and nothing owed takes the server's position`() {
        val decision = reconcileReadingState(
            local = state(0.6),
            remote = state(0.8),
            baseline = null,
            localDirty = false,
        )
        assertEquals(0.8, (decision as SyncDecision.Pull).state.progression)
    }

    @Test
    fun `a status with no position keeps the local position`() {
        val decision = reconcileReadingState(
            local = state(0.42, ReadingStatus.READING),
            remote = state(null, ReadingStatus.FINISHED),
            baseline = baseline(0.42),
            localDirty = false,
        )
        assertEquals(
            ReadingStatus.FINISHED,
            (decision as SyncDecision.AdoptStatus).status,
        )
    }

    @Test
    fun `a status with no position that already matches is nothing to do`() {
        assertEquals(
            SyncDecision.InSync,
            reconcileReadingState(
                local = state(0.42, ReadingStatus.READING),
                remote = state(null, ReadingStatus.READING),
                baseline = baseline(0.42),
                localDirty = false,
            ),
        )
    }

    @Test
    fun `marking a book unread here beats a stale finished on the server`() {
        val decision = reconcileReadingState(
            local = state(0.99, ReadingStatus.READY_TO_READ),
            remote = state(0.99, ReadingStatus.FINISHED),
            baseline = baseline(0.99, ReadingStatus.FINISHED),
            localDirty = true,
            localUnreadOverride = true,
        )
        assertEquals(
            ReadingStatus.READY_TO_READ,
            (decision as SyncDecision.Push).state.status,
        )
    }

    @Test
    fun `a status change on its own is a move`() {
        val decision = reconcileReadingState(
            local = state(0.99, ReadingStatus.READING),
            remote = state(0.99, ReadingStatus.FINISHED),
            baseline = baseline(0.99, ReadingStatus.READING),
            localDirty = false,
        )
        assertEquals(
            ReadingStatus.FINISHED,
            (decision as SyncDecision.Pull).state.status,
        )
    }

    @Test
    fun `a book the feed was silent about and that owes nothing is skipped`() {
        assertFalse(
            needsReconciling(reported = false, hasPending = false, localDirty = false),
        )
    }

    @Test
    fun `a book with reading the server has not seen is looked at`() {
        assertTrue(needsReconciling(reported = false, hasPending = false, localDirty = true))
    }

    @Test
    fun `a state left over from an interrupted run is looked at`() {
        // The sync token has already moved past it, so if this were
        // skipped the server would never mention it again.
        assertTrue(needsReconciling(reported = false, hasPending = true, localDirty = false))
    }

    @Test
    fun `a status derived from the position marks a finished book`() {
        assertEquals(ReadingStatus.FINISHED, ReadingStatus.forProgression(0.995))
        assertEquals(ReadingStatus.READING, ReadingStatus.forProgression(0.5))
        assertEquals(ReadingStatus.READY_TO_READ, ReadingStatus.forProgression(0.0))
        assertEquals(ReadingStatus.READY_TO_READ, ReadingStatus.forProgression(null))
    }

    @Test
    fun `a missing remote position is not a remote position of zero`() {
        // The guarantee the server's null-to-zero coercion was
        // bypassing: a partner that sends only a status must never send
        // this reader back to the start of the book.
        val decision = reconcileReadingState(
            local = state(0.47),
            remote = state(null, status = ReadingStatus.READING),
            baseline = baseline(0.47),
            localDirty = false,
        )

        assertEquals(SyncDecision.InSync, decision)
    }

    @Test
    fun `an unreadable position must never be handed over as a status`() {
        // Why a malformed op is dropped rather than landed with a null
        // progression: forProgression(null) is ReadyToRead, and this is
        // what the merge would then be asked to do with it.
        val decision = reconcileReadingState(
            local = state(0.47, status = ReadingStatus.READING),
            remote = state(null, status = ReadingStatus.READY_TO_READ),
            baseline = baseline(0.47),
            localDirty = false,
        )

        assertEquals(SyncDecision.AdoptStatus(ReadingStatus.READY_TO_READ), decision)
    }
}
