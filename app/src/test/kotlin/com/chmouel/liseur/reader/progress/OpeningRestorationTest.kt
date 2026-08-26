package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps a book's opening out of the sync log.
 *
 * Written against behaviour rather than the enum: what matters is which
 * emissions are allowed to become a position other devices will be sent
 * to, and that no arrangement of them can leave the gate shut forever.
 */
class OpeningRestorationTest {

    private val timeout = 5_000L

    private fun at(href: String, progression: Double?, exact: Boolean = false) =
        RestorePoint(href = href, progression = progression, exact = exact)

    private fun OpeningRestoration.emit(
        here: RestorePoint,
        anchorVerified: Boolean = false,
        elapsedMs: Long = 0,
    ) = onEmission(here, anchorVerified, elapsedMs)

    @Test
    fun `a book with no saved position is not gated at all`() {
        val gate = OpeningRestoration(target = null, timeoutMs = timeout)

        assertFalse(gate.isGated)
        assertEquals(
            OpeningRestorationVerdict.RELEASE,
            gate.emit(at("ch1.xhtml", 0.0)),
        )
    }

    @Test
    fun `an exact restoration suppresses until its anchor is found`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        // The WebView reporting the top of the resource before Readium
        // has scrolled it. This is op 2149.
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.3877), anchorVerified = false),
        )
        // The restoration arriving. Suppressed as well: it is not the
        // reader moving, and writing it back is what produced op 2142.
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.4699), anchorVerified = true),
        )
        assertFalse(gate.isGated)
        // The page actually turned afterwards is reading.
        assertEquals(
            OpeningRestorationVerdict.RELEASE,
            gate.emit(at("ch7.xhtml", 0.4720), anchorVerified = false),
        )
    }

    @Test
    fun `reopening a book exactly where it was left publishes nothing`() {
        // Op 2142: the same device sent a byte-identical locator ten
        // hours later under a fresh revision, because the first emission
        // after reopening was written back as reading. Nothing moved, so
        // nothing may be published.
        val gate = OpeningRestoration(at("ch7.xhtml", 0.4284, exact = true), timeout)

        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.4284), anchorVerified = true),
        )
    }

    @Test
    fun `an exact restoration is not released by landing near the right place`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        // Same href, same progression, but the anchor was not found:
        // the reader is on a page that merely measures the same.
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.47), anchorVerified = false),
        )
    }

    @Test
    fun `an approximate restoration releases on href and progression`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47), timeout)

        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch1.xhtml", 0.02)),
        )
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.39)),
        )
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.4705)),
        )
        assertFalse(gate.isGated)
    }

    @Test
    fun `an approximate target with no progression asks only for the resource`() {
        val gate = OpeningRestoration(at("ch7.xhtml", null), timeout)

        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch1.xhtml", 0.0)))
        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch7.xhtml", null)))
        assertFalse(gate.isGated)
    }

    @Test
    fun `the deadline releases without any further emission`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch7.xhtml", 0.0)))
        assertTrue(gate.isGated)

        gate.onDeadline()

        assertFalse(gate.isGated)
    }

    @Test
    fun `an emission past the deadline releases even if restoration never landed`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.0), elapsedMs = timeout - 1),
        )
        assertEquals(
            OpeningRestorationVerdict.RELEASE,
            gate.emit(at("ch7.xhtml", 0.0), elapsedMs = timeout),
        )
    }

    @Test
    fun `a reader navigating during the window is not overtaken by a stale emission`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch7.xhtml", 0.0)))

        // The reader taps a contents entry. nav.go() is asynchronous and
        // the caller's marker for the move is single use, so this
        // emission — still in flight from the pre-restore position —
        // would take the marker and be saved as the reader's jump.
        gate.onNavigationIssued(at("ch2.xhtml", 0.10))

        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch7.xhtml", 0.0)))
        assertTrue(gate.isGated)

        // Arriving where they asked to go ends the restoration.
        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch2.xhtml", 0.10)))
        assertFalse(gate.isGated)
        assertEquals(OpeningRestorationVerdict.RELEASE, gate.emit(at("ch2.xhtml", 0.11)))
    }

    @Test
    fun `a fallback retargets and keeps suppressing`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        gate.onNavigationIssued(at("ch7.xhtml", 0.44))

        // nav.go() is asynchronous: this arrives from the page being
        // left behind, and releasing on "fallback issued" would let it
        // through as a page turn.
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch1.xhtml", 0.0)),
        )
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.0)),
        )
        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.4405)),
        )
        assertFalse(gate.isGated)
    }

    @Test
    fun `a fallback does not extend the deadline`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        gate.emit(at("ch7.xhtml", 0.0), elapsedMs = timeout - 100)
        gate.onNavigationIssued(at("ch7.xhtml", 0.44))

        assertEquals(
            OpeningRestorationVerdict.RELEASE,
            gate.emit(at("ch1.xhtml", 0.0), elapsedMs = timeout),
        )
    }

    @Test
    fun `retargeting to a text anchor still waits for that anchor`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        // A catch-up offer accepted during opening sends the reader to a
        // text-anchored position close to where they already are. Losing
        // its exactness would let the page being left behind pass the
        // 1% tolerance and read as an arrival.
        gate.onNavigationIssued(at("ch7.xhtml", 0.46, exact = true))

        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.emit(at("ch7.xhtml", 0.46)))
        assertTrue(gate.isGated)

        assertEquals(
            OpeningRestorationVerdict.SUPPRESS,
            gate.emit(at("ch7.xhtml", 0.46), anchorVerified = true),
        )
        assertFalse(gate.isGated)
    }

    @Test
    fun `a fallback after release does not close the gate again`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47, exact = true), timeout)

        gate.onDeadline()
        gate.onNavigationIssued(at("ch7.xhtml", 0.44))

        assertFalse(gate.isGated)
        assertEquals(OpeningRestorationVerdict.RELEASE, gate.emit(at("ch1.xhtml", 0.0)))
    }

    @Test
    fun `releasing is idempotent whichever path arrives first`() {
        val gate = OpeningRestoration(at("ch7.xhtml", 0.47), timeout)

        gate.onDeadline()
        gate.onDeadline()

        assertFalse(gate.isGated)
        assertEquals(OpeningRestorationVerdict.RELEASE, gate.emit(at("ch1.xhtml", 0.0)))
    }

    @Test
    fun `a new navigator gets a fresh gate rather than the last one's state`() {
        val first = OpeningRestoration(at("ch7.xhtml", 0.47), timeout)
        first.onDeadline()
        assertFalse(first.isGated)

        // What a column or scroll mode change builds.
        val second = OpeningRestoration(at("ch7.xhtml", 0.47), timeout)

        assertTrue(second.isGated)
        assertEquals(OpeningRestorationVerdict.SUPPRESS, second.emit(at("ch1.xhtml", 0.0)))
    }

    @Test
    fun `exact opening verification budget leaves room before fail open`() {
        assertEquals(3_000L, OpeningRestoration.exactOpenVerifyBudgetMs(elapsedMs = 0))
        assertEquals(2_500L, OpeningRestoration.exactOpenVerifyBudgetMs(elapsedMs = 1_500))
        assertEquals(1L, OpeningRestoration.exactOpenVerifyBudgetMs(elapsedMs = 3_999))
        assertEquals(0L, OpeningRestoration.exactOpenVerifyBudgetMs(elapsedMs = 4_000))
        assertEquals(0L, OpeningRestoration.exactOpenVerifyBudgetMs(elapsedMs = timeout))

        assertTrue(
            OpeningRestoration.EXACT_OPEN_VERIFY_BUDGET_MS +
                OpeningRestoration.FALLBACK_BEFORE_DEADLINE_MS <= timeout,
        )
    }
}
