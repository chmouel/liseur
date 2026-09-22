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
        1, "locator-a", true, agreed, revision,
        if (revision == 1L) "locator-a" else "locator-b", local,
        true, remote, verified,
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
}
