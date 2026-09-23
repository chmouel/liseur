package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BookOrbitExactPositionTest {
    private fun decide(
        revision: Long? = 1,
        agreed: String? = "a",
        local: String? = "a",
        remote: String? = "a",
        verified: Boolean = false,
    ) = reconcileExactPosition(
        1, "locator-a", true, agreed, 25.0, revision,
        if (revision == 1L) "locator-a" else "locator-b", local,
        true, remote, 25.0, verified,
    )

    private fun percentageOnly(
        agreedPercentage: Double? = 67.0,
        remotePercentage: Double = 67.0,
        revision: Long? = 1,
        local: String? = null,
        agreedSaved: Boolean? = true,
        agreedCfi: String? = null,
    ) = reconcileExactPosition(
        1, "locator-a", agreedSaved, agreedCfi, agreedPercentage, revision,
        if (revision == 1L) "locator-a" else "locator-b", local,
        true, null, remotePercentage, true,
    )

    @Test fun `local page turn pushes even below percentage rounding`() {
        assertEquals(ExactPositionDecision.Push, decide(revision = 2, local = "b"))
    }

    @Test fun `remote page turn pulls only after exact verification`() {
        assertEquals(ExactPositionDecision.Unresolved, decide(remote = "b"))
        assertEquals(ExactPositionDecision.Pull, decide(remote = "b", verified = true))
    }

    @Test fun `same exact anchor settles regardless of percentage or revision`() {
        assertEquals(ExactPositionDecision.Settled, decide(revision = 2, local = "b", remote = "b"))
    }

    @Test fun `different anchors at same percentage conflict or remain unverified`() {
        assertEquals(ExactPositionDecision.Conflict, decide(revision = 2, local = "b", remote = "c"))
        assertEquals(ExactPositionDecision.Unresolved, decide(revision = 2, local = null, remote = "a"))
    }

    @Test fun `percentage-only place without an agreed baseline is never pulled or overwritten`() {
        assertEquals(ExactPositionDecision.Unresolved,
            percentageOnly(agreedSaved = null, agreedPercentage = null, revision = 2, local = "b"))
        assertEquals(ExactPositionDecision.Unresolved,
            percentageOnly(agreedSaved = null, agreedPercentage = null))
    }

    @Test fun `reading on from an agreed percentage-only place pushes the exact place`() {
        assertEquals(ExactPositionDecision.Push, percentageOnly(revision = 2, local = "b"))
        assertEquals(ExactPositionDecision.Settled, percentageOnly())
    }

    @Test fun `a different percentage-only place stays unresolved`() {
        assertEquals(ExactPositionDecision.Unresolved,
            percentageOnly(remotePercentage = 70.0, revision = 2, local = "b"))
        assertEquals(ExactPositionDecision.Unresolved, percentageOnly(remotePercentage = 70.0))
        assertEquals(ExactPositionDecision.Unresolved,
            percentageOnly(agreedCfi = "a", agreedPercentage = 67.0, local = "a"))
    }
}
