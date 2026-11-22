package com.chmouel.liseur.hardcover

import com.chmouel.liseur.domain.PersonalEvaluation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcoverEvaluationPlanTest {

    @Test
    fun `not on shelf never pulls and pushes only non-null local values`() {
        assertEquals(
            HardcoverEvaluationDecision.NoChange(),
            HardcoverEvaluationPlan.planRating(
                onShelf = false,
                localRaw = null,
                remoteRating = 4.5,
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote("8"),
            HardcoverEvaluationPlan.planRating(
                onShelf = false,
                localRaw = "8",
                remoteRating = null,
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.NoChange(),
            HardcoverEvaluationPlan.planReview(
                onShelf = false,
                localRaw = null,
                remoteRaw = "Remote review",
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote("Local review"),
            HardcoverEvaluationPlan.planReview(
                onShelf = false,
                localRaw = "  Local review\r\n",
                remoteRaw = null,
                storedBase = null,
            ),
        )
    }

    @Test
    fun `equivalent ratings and reviews record the base and write nothing`() {
        assertEquals(
            HardcoverEvaluationDecision.NoChange(HardcoverEvaluationBase("7", "3.5")),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "7",
                remoteRating = 3.5,
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.NoChange(null),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "7",
                remoteRating = 3.5,
                storedBase = HardcoverEvaluationBase("7", "3.5"),
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.NoChange(HardcoverEvaluationBase("Line 1\nLine 2", "Line 1\nLine 2")),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "  Line 1\r\nLine 2  ",
                remoteRaw = "Line 1\nLine 2\n",
                storedBase = null,
            ),
        )
    }

    @Test
    fun `no base pulls when local is null, pushes when remote is null, and conflicts when both differ`() {
        assertEquals(
            HardcoverEvaluationDecision.PullToLocal("9", HardcoverEvaluationBase("9", "4.5")),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = null,
                remoteRating = 4.5,
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote("8"),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "8",
                remoteRating = null,
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.Conflict("8", "3.0"),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "8",
                remoteRating = 3.0,
                storedBase = null,
            ),
        )

        assertEquals(
            HardcoverEvaluationDecision.PullToLocal("Great book", HardcoverEvaluationBase("Great book", "Great book")),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = null,
                remoteRaw = "Great book",
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote("My thoughts"),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "My thoughts",
                remoteRaw = null,
                storedBase = null,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.Conflict("Local", "Remote"),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "Local",
                remoteRaw = "Remote",
                storedBase = null,
            ),
        )
    }

    @Test
    fun `migration from pushedRating acts as the rating base when no base row exists`() {
        // Local was cleared after pushing 3.5 -> clears remote.
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote(null),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = null,
                remoteRating = 3.5,
                storedBase = null,
                pushedRating = 3.5,
            ),
        )
        // Remote was changed from 3.5 to 4.5 while local stayed 7 -> pulls 4.5 ("9").
        assertEquals(
            HardcoverEvaluationDecision.PullToLocal("9", HardcoverEvaluationBase("9", "4.5")),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "7",
                remoteRating = 4.5,
                storedBase = null,
                pushedRating = 3.5,
            ),
        )
        // Both changed since pushedRating 3.5 -> conflict.
        assertEquals(
            HardcoverEvaluationDecision.Conflict("10", "4.5"),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "10",
                remoteRating = 4.5,
                storedBase = null,
                pushedRating = 3.5,
            ),
        )
    }

    @Test
    fun `with base only local changed pushes new value or null to clear`() {
        val base = HardcoverEvaluationBase("6", "3.0")
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote("8"),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "8",
                remoteRating = 3.0,
                storedBase = base,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote(null),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = null,
                remoteRating = 3.0,
                storedBase = base,
            ),
        )

        val reviewBase = HardcoverEvaluationBase("Old review", "Old review")
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote("New review"),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "New review",
                remoteRaw = "Old review",
                storedBase = reviewBase,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PushToRemote(null),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = null,
                remoteRaw = "Old review",
                storedBase = reviewBase,
            ),
        )
    }

    @Test
    fun `with base only remote changed pulls new value or null to clear`() {
        val base = HardcoverEvaluationBase("6", "3.0")
        assertEquals(
            HardcoverEvaluationDecision.PullToLocal("9", HardcoverEvaluationBase("9", "4.5")),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "6",
                remoteRating = 4.5,
                storedBase = base,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PullToLocal(null, HardcoverEvaluationBase(null, null)),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "6",
                remoteRating = null,
                storedBase = base,
            ),
        )

        val reviewBase = HardcoverEvaluationBase("Old review", "Old review")
        assertEquals(
            HardcoverEvaluationDecision.PullToLocal("Updated remote", HardcoverEvaluationBase("Updated remote", "Updated remote")),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "Old review",
                remoteRaw = "Updated remote",
                storedBase = reviewBase,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.PullToLocal(null, HardcoverEvaluationBase(null, null)),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "Old review",
                remoteRaw = null,
                storedBase = reviewBase,
            ),
        )
    }

    @Test
    fun `with base both changed produces a conflict`() {
        val base = HardcoverEvaluationBase("6", "3.0")
        assertEquals(
            HardcoverEvaluationDecision.Conflict("9", "4.0"),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = "9",
                remoteRating = 4.0,
                storedBase = base,
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.Conflict(null, "4.0"),
            HardcoverEvaluationPlan.planRating(
                onShelf = true,
                localRaw = null,
                remoteRating = 4.0,
                storedBase = base,
            ),
        )

        val reviewBase = HardcoverEvaluationBase("Old", "Old")
        assertEquals(
            HardcoverEvaluationDecision.Conflict("Local edit", "Remote edit"),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "Local edit",
                remoteRaw = "Remote edit",
                storedBase = reviewBase,
            ),
        )
    }

    @Test
    fun `lossy remote 0_5 star maps to local 2 and does not loop once base is recorded`() {
        val first = HardcoverEvaluationPlan.planRating(
            onShelf = true,
            localRaw = null,
            remoteRating = 0.5,
            storedBase = null,
        )
        val expectedBase = HardcoverEvaluationBase("2", "0.5")
        assertEquals(HardcoverEvaluationDecision.PullToLocal("2", expectedBase), first)

        // Next check: local is "2" (1.0 star) and remote is still 0.5 -> neither changed from base.
        val second = HardcoverEvaluationPlan.planRating(
            onShelf = true,
            localRaw = "2",
            remoteRating = 0.5,
            storedBase = expectedBase,
        )
        assertEquals(HardcoverEvaluationDecision.NoChange(), second)
    }

    @Test
    fun `a remote review Liseur cannot hold is left on Hardcover unless both sides changed`() {
        val huge = "a".repeat(PersonalEvaluation.MAX_REVIEW_BYTES + 1)
        assertEquals(
            HardcoverEvaluationDecision.NoChange(recordBase = HardcoverEvaluationBase(null, huge)),
            HardcoverEvaluationPlan.planReview(onShelf = true, localRaw = null, remoteRaw = huge, storedBase = null),
        )
        assertEquals(
            HardcoverEvaluationDecision.NoChange(recordBase = HardcoverEvaluationBase("Short", huge)),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "Short",
                remoteRaw = huge,
                storedBase = HardcoverEvaluationBase("Short", "Short"),
            ),
        )
        assertEquals(
            HardcoverEvaluationDecision.Conflict(conflictLocal = "Edited", conflictRemote = huge, remoteTooLong = true),
            HardcoverEvaluationPlan.planReview(
                onShelf = true,
                localRaw = "Edited",
                remoteRaw = huge,
                storedBase = HardcoverEvaluationBase("Short", "Short"),
            ),
        )
        assertFalse(PersonalEvaluation.valid(PersonalEvaluation.REVIEW, "bad\u0000review"))
    }

    @Test
    fun `rating encodings round to nearest half star`() {
        assertEquals(3.5, HardcoverEvaluationPlan.normalizeRemoteRating(3.49)!!, 0.0)
        assertEquals("3.5", HardcoverEvaluationPlan.encodeRemoteRating(3.5))
        assertEquals("4.0", HardcoverEvaluationPlan.encodeRemoteRating(4.0))
        assertNull(HardcoverEvaluationPlan.normalizeRemoteRating(0.0))
        assertNull(HardcoverEvaluationPlan.normalizeRemoteRating(5.5))
    }
}
