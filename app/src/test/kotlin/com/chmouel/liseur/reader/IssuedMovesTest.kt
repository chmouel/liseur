package com.chmouel.liseur.reader

import com.chmouel.liseur.reader.progress.RestorePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuedMovesTest {

    private fun at(href: String, progression: Double?) =
        RestorePoint(href = href, progression = progression, exact = false)

    private fun to(href: String, progression: Double? = null, fragment: String? = null) =
        IssuedMoves.Destination(href = href, progression = progression, fragment = fragment)

    private val cover = at("EPUB/cover.xhtml", 0.0)
    private val bodyTop = at("EPUB/body.xhtml", 0.05)
    private val bodySection = at("EPUB/body.xhtml", 0.60)
    private val section = to("EPUB/body.xhtml", progression = 0.60)

    @Test
    fun `a mark holds while nothing is asked for`() {
        val moves = IssuedMoves()
        val since = moves.mark()
        assertTrue(moves.unchangedSince(since))
    }

    @Test
    fun `a move asked for after a mark retires it`() {
        val moves = IssuedMoves()
        val fit = moves.mark()
        val reflow = moves.mark()
        moves.issue(from = cover, to = section)
        assertFalse(moves.unchangedSince(fit))
        assertFalse(moves.unchangedSince(reflow))
    }

    @Test
    fun `a mark stays retired however many moves follow`() {
        val moves = IssuedMoves()
        val since = moves.mark()
        repeat(3) { moves.issue(from = cover) }
        assertFalse(moves.unchangedSince(since))
    }

    @Test
    fun `a move still in the air refuses a mark outright`() {
        val moves = IssuedMoves()
        moves.issue(from = cover, to = section)
        assertEquals(IssuedMoves.NONE, moves.mark())
        assertFalse(moves.unchangedSince(IssuedMoves.NONE))
    }

    @Test
    fun `a move that landed lets a mark be taken`() {
        val moves = IssuedMoves()
        moves.issue(from = cover, to = section)
        moves.onPosition(bodySection)
        assertTrue(moves.unchangedSince(moves.mark()))
    }

    @Test
    fun `the position published as a resource loads is not the arrival`() {
        val moves = IssuedMoves()
        moves.issue(from = cover, to = section)
        moves.onPosition(bodyTop)
        assertEquals(IssuedMoves.NONE, moves.mark())
        moves.onPosition(bodySection)
        assertTrue(moves.unchangedSince(moves.mark()))
    }

    @Test
    fun `a fragment jump is not answered by its resource appearing`() {
        // The reported shape. `Publication.locatorFromLink` answers a
        // contents entry like `body.xhtml#sec-18` with the fragment and
        // no whole-book progression at all, so nothing the navigator
        // publishes says the jump landed — and the first thing it
        // publishes is the top of the file the section lives in. Only
        // the clock ends this wait.
        val moves = IssuedMoves(settleTimeoutMs = 3_000L)
        moves.issue(from = cover, to = to("EPUB/body.xhtml", fragment = "sec-18"), nowMs = 0L)
        moves.onPosition(bodyTop, nowMs = 10L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 20L))
        moves.onPosition(bodySection, nowMs = 30L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 40L))
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 3_000L)))
    }

    @Test
    fun `landing near enough counts as landing`() {
        val moves = IssuedMoves()
        moves.issue(from = cover, to = section)
        moves.onPosition(at("EPUB/body.xhtml", 0.60 + IssuedMoves.TOLERANCE / 2))
        assertTrue(moves.unchangedSince(moves.mark()))
    }

    @Test
    fun `a chapter destination with no progression lands on its resource`() {
        val moves = IssuedMoves()
        moves.issue(from = cover, to = to("EPUB/body.xhtml"))
        moves.onPosition(cover)
        assertEquals(IssuedMoves.NONE, moves.mark())
        moves.onPosition(bodyTop)
        assertTrue(moves.unchangedSince(moves.mark()))
    }

    @Test
    fun `one turn lands on the first position that differs`() {
        val moves = IssuedMoves()
        moves.issue(from = cover)
        moves.onPosition(cover)
        assertEquals(IssuedMoves.NONE, moves.mark())
        moves.onPosition(bodyTop)
        assertTrue(moves.unchangedSince(moves.mark()))
    }

    @Test
    fun `turns in a flurry are not told apart by their arrivals`() {
        // Readium publishes a resource's own idea of where it is and
        // then the real position, so emissions and turns do not pair
        // off. Two taps in the air at once wait for the clock rather
        // than let one arrival — or two — stand for both.
        val moves = IssuedMoves(restlessMs = 1_000L)
        moves.issue(from = cover, nowMs = 0L)
        moves.issue(from = cover, nowMs = 10L)
        moves.onPosition(bodyTop, nowMs = 20L)
        moves.onPosition(bodySection, nowMs = 30L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 40L))
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 1_010L)))
    }

    @Test
    fun `a second position for one turn does not open the door early`() {
        // The same emissions, but for a single turn: the first one that
        // differs is its arrival, and the second changes nothing.
        val moves = IssuedMoves()
        moves.issue(from = cover)
        moves.onPosition(bodyTop)
        val since = moves.mark()
        moves.onPosition(bodySection)
        assertTrue(moves.unchangedSince(since))
    }

    @Test
    fun `a turn that moved nothing is not waited for`() {
        val moves = IssuedMoves()
        val token = moves.issue(from = cover)
        assertEquals(IssuedMoves.NONE, moves.mark())
        moves.cancel(token)
        assertTrue(moves.unchangedSince(moves.mark()))
    }

    @Test
    fun `a cancelled move still retires a mark taken before it`() {
        val moves = IssuedMoves()
        val since = moves.mark()
        val token = moves.issue(from = cover)
        moves.cancel(token)
        assertFalse(moves.unchangedSince(since))
    }

    @Test
    fun `a cancelled chapter step leaves a turn still outstanding`() {
        // `turn()` in a scrolled book announces, then hands over to
        // `stepChapter()` at the edge; the step declining is not the
        // turn having landed.
        val moves = IssuedMoves(restlessMs = 1_000L)
        moves.issue(from = cover, nowMs = 0L)
        val step = moves.issue(from = cover, to = to("EPUB/body.xhtml"), nowMs = 10L)
        moves.cancel(step)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 20L))
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 1_010L)))
    }

    @Test
    fun `a cancelled turn leaves a jump still outstanding`() {
        // The mirror of the case above, and the one that costs a reader
        // their page: a jump is in the air, a turn is announced behind
        // it, and the turn's last-page probe comes back to find the
        // page gone. Giving up on the turn must not be read as the jump
        // having landed.
        val moves = IssuedMoves(settleTimeoutMs = 3_000L, restlessMs = 1_000L)
        val jump = moves.issue(
            from = cover,
            to = to("EPUB/body.xhtml", fragment = "sec18"),
            nowMs = 0L,
        )
        val turn = moves.issue(from = cover, nowMs = 10L)
        moves.cancel(turn)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 1_020L))
        // Only the jump's own deadline lets go of it.
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 3_000L)))
        assertNotEquals(IssuedMoves.NONE, jump)
    }

    @Test
    fun `an answer arriving late cannot end a wait opened since`() {
        // A probe abandoned by one turn reports back after the reader
        // has already asked for another.
        val moves = IssuedMoves(restlessMs = 1_000L)
        val stale = moves.issue(from = cover, nowMs = 0L)
        moves.issue(from = cover, nowMs = 900L)
        moves.cancel(stale)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 950L))
    }

    @Test
    fun `a jump arriving does not leave its token able to end another`() {
        val moves = IssuedMoves(restlessMs = 1_000L)
        val jump = moves.issue(from = cover, to = to("EPUB/body.xhtml", 0.5), nowMs = 0L)
        moves.onPosition(at("EPUB/body.xhtml", 0.5), nowMs = 10L)
        moves.issue(from = at("EPUB/body.xhtml", 0.5), nowMs = 20L)
        moves.cancel(jump)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 30L))
    }

    @Test
    fun `a hand on the page holds for as long as it keeps moving`() {
        val moves = IssuedMoves(restlessMs = 1_000L)
        moves.scrolled(nowMs = 0L)
        moves.scrolled(nowMs = 800L)
        moves.scrolled(nowMs = 1_600L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 2_000L))
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 2_600L)))
    }

    @Test
    fun `a hand on the page is not let go of by a position`() {
        val moves = IssuedMoves(restlessMs = 1_000L)
        moves.scrolled(nowMs = 0L)
        moves.onPosition(bodySection, nowMs = 10L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 20L))
    }

    @Test
    fun `a move that never lands is given up on rather than held forever`() {
        val moves = IssuedMoves(settleTimeoutMs = 3_000L)
        moves.issue(from = cover, to = section, nowMs = 1_000L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 3_999L))
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 4_000L)))
    }

    @Test
    fun `a mark taken while a move was in the air is refused even once it lands`() {
        // A restore that began with no bearings does not acquire them by
        // waiting: the anchor it took names the page the reader left.
        val moves = IssuedMoves()
        moves.issue(from = cover, to = section)
        val since = moves.mark()
        moves.onPosition(bodySection)
        assertFalse(moves.unchangedSince(since))
    }

    @Test
    fun `a mark taken while a move was in the air is refused even once it expires`() {
        val moves = IssuedMoves(settleTimeoutMs = 3_000L)
        moves.issue(from = cover, to = section, nowMs = 0L)
        val since = moves.mark(nowMs = 0L)
        assertFalse(moves.unchangedSince(since))
        assertFalse(moves.unchangedSince(since))
    }

    @Test
    fun `a second move in a run is outstanding again`() {
        val moves = IssuedMoves()
        moves.issue(from = cover, to = to("EPUB/body.xhtml", progression = 0.05))
        moves.onPosition(bodyTop)
        assertTrue(moves.unchangedSince(moves.mark()))
        moves.issue(from = bodyTop, to = section)
        assertEquals(IssuedMoves.NONE, moves.mark())
    }

    @Test
    fun `a turn while a jump is in the air waits for both`() {
        val moves = IssuedMoves(settleTimeoutMs = 3_000L, restlessMs = 1_000L)
        moves.issue(from = cover, to = section, nowMs = 0L)
        moves.issue(from = cover, nowMs = 10L)
        moves.onPosition(bodySection, nowMs = 20L)
        assertEquals(IssuedMoves.NONE, moves.mark(nowMs = 30L))
        assertTrue(moves.unchangedSince(moves.mark(nowMs = 1_010L)))
    }

    @Test
    fun `a position reported with no move outstanding changes nothing`() {
        val moves = IssuedMoves()
        val since = moves.mark()
        moves.onPosition(bodySection)
        assertTrue(moves.unchangedSince(since))
    }
}
