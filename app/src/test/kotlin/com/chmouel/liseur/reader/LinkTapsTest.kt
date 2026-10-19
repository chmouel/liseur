package com.chmouel.liseur.reader

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.chmouel.liseur.reader.progress.RestorePoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LinkTapsTest {

    private fun link(fragment: String? = "n1") =
        Link(href = Url("EPUB/ch1.xhtml" + (fragment?.let { "#$it" } ?: ""))!!)

    private fun place(): Locator = requireNotNull(
        Locator.fromJSON(
            org.json.JSONObject()
                .put("href", "EPUB/ch1.xhtml")
                .put("type", "application/xhtml+xml")
                .put("locations", org.json.JSONObject().put("progression", 0.4)),
        ),
    )

    /** A lookup whose completion the test holds and releases. */
    private class Lookup {
        val gate = CompletableDeferred<Unit>()
        var answer: String? = "the note"
        var asked = 0
        val noteAt: suspend (Link) -> String? = {
            asked++
            gate.await()
            answer
        }
    }

    /** What the screen would have armed: the capture, and the guards. */
    private class Screen(
        val taps: LinkTaps,
        val moves: IssuedMoves,
    ) {
        var place: Locator? = null
        var drop = false
        val captured = mutableListOf<Int>()
        var customStillCurrent: (() -> Boolean)? = null

        init {
            taps.captureOrigin = linkCapture@{ request, generation, _ ->
                captured.add(request)
                if (drop) return@linkCapture null
                LinkTaps.Origin(place) {
                    customStillCurrent?.invoke()
                        ?: (request == taps.latestRequest() && moves.unchangedSince(generation))
                }
            }
        }
    }

    @Test
    fun `a whole resource is followed with the captured place`() = runTest {
        val footnotes = mutableListOf<String>()
        val followed = mutableListOf<Pair<Link, Locator?>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, _ -> footnotes.add(html) },
            onFollow = { l, origin -> followed.add(l to origin.place) },
        )
        val screen = Screen(taps, moves)
        screen.place = place()

        taps.fromReadium(link(fragment = null))
        advanceUntilIdle()

        assertEquals(1, followed.size)
        assertEquals(place(), followed.single().second)
        // A chapter is never a note: the target is never read.
        assertEquals(0, lookup.asked)
        assertTrue(footnotes.isEmpty())
    }

    @Test
    fun `a note target pops the card once the reader is still there`() = runTest {
        val footnotes = mutableListOf<String>()
        val followed = mutableListOf<Pair<Link, Locator?>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, _ -> footnotes.add(html) },
            onFollow = { l, origin -> followed.add(l to origin.place) },
        )
        Screen(taps, moves)

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        lookup.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("the note"), footnotes)
        assertTrue(followed.isEmpty())
    }

    @Test
    fun `an unreadable target is followed like any reference`() = runTest {
        val footnotes = mutableListOf<String>()
        val followed = mutableListOf<Pair<Link, Locator?>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, _ -> footnotes.add(html) },
            onFollow = { l, origin -> followed.add(l to origin.place) },
        )
        val screen = Screen(taps, moves)
        screen.place = place()

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        lookup.answer = null
        lookup.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, followed.size)
        assertEquals(place(), followed.single().second)
        assertTrue(footnotes.isEmpty())
    }

    @Test
    fun `a chapter departure during the lookup drops the tap`() = runTest {
        val footnotes = mutableListOf<String>()
        val followed = mutableListOf<Pair<Link, Locator?>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, _ -> footnotes.add(html) },
            onFollow = { l, origin -> followed.add(l to origin.place) },
        )
        val screen = Screen(taps, moves)
        screen.customStillCurrent = { false }

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        lookup.gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(footnotes.isEmpty())
        assertTrue(followed.isEmpty())
    }

    @Test
    fun `a page turn during the lookup drops the tap`() = runTest {
        val footnotes = mutableListOf<String>()
        val followed = mutableListOf<Pair<Link, Locator?>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, _ -> footnotes.add(html) },
            onFollow = { l, origin -> followed.add(l to origin.place) },
        )
        Screen(taps, moves)

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        // The reader turns the page while the target is being read. The
        // generation the tap registered is no longer current, and the
        // guard runs immediately before the effect, not just before the
        // lookup.
        moves.issue(from = RestorePoint("EPUB/ch1.xhtml", 0.4, false))
        lookup.gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(footnotes.isEmpty())
        assertTrue(followed.isEmpty())
    }

    @Test
    fun `only the latest of two overlapping taps may act, whichever answers first`() = runTest {
        // The winning tap's outcome is a navigation in one round and a
        // card in the other; the loser applies in neither, whichever of
        // the two lookups answers first.
        for (winnerAnswersANote in listOf(false, true)) {
            for (firstToAnswer in 0..1) {
                val footnotes = mutableListOf<String>()
                val followed = mutableListOf<Link>()
                val lookups = List(2) { Lookup() }
                lookups[1].answer = if (winnerAnswersANote) "the note" else null
                var asked = 0
                val moves = IssuedMoves()
                val taps = LinkTaps(
                    scope = this,
                    moves = moves,
                    noteAt = { asked++; lookups[asked - 1].noteAt(it) },
                    onFootnote = { html, _, _ -> footnotes.add(html) },
                    onFollow = { l, _ -> followed.add(l) },
                )
                Screen(taps, moves)

                taps.fromBridge(link(), "EPUB/ch1.xhtml")
                taps.fromBridge(link(), "EPUB/ch1.xhtml")
                // The earlier request's lookup answers last in one run and
                // first in the other: only the later tap may act either way.
                lookups[1 - firstToAnswer].gate.complete(Unit)
                lookups[firstToAnswer].gate.complete(Unit)
                advanceUntilIdle()

                assertEquals(if (winnerAnswersANote) 0 else 1, followed.size)
                assertEquals(if (winnerAnswersANote) 1 else 0, footnotes.size)
            }
        }
    }

    @Test
    fun `a same-document tap without an exact place still resolves a note`() = runTest {
        val footnotes = mutableListOf<Pair<String, LinkTaps.LinkOrigin>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, origin -> footnotes.add(html to origin) },
            onFollow = { _, _ -> },
        )
        Screen(taps, moves)

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        lookup.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, lookup.asked)
        assertEquals("the note", footnotes.single().first)
        assertNull(footnotes.single().second.place)
        assertFalse(footnotes.single().second.mayFallbackToPublishedPlace)
    }

    @Test
    fun `a same-document tap without an exact place navigates without a return fallback`() = runTest {
        val followed = mutableListOf<LinkTaps.LinkOrigin>()
        val lookup = Lookup().apply { answer = null }
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { _, _, _ -> },
            onFollow = { _, origin -> followed.add(origin) },
        )
        Screen(taps, moves)

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        lookup.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, lookup.asked)
        assertNull(followed.single().place)
        assertFalse(followed.single().mayFallbackToPublishedPlace)
    }

    @Test
    fun `a Readium-resolved note keeps its captured origin`() = runTest {
        val footnotes = mutableListOf<Pair<String, LinkTaps.LinkOrigin>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, origin -> footnotes.add(html to origin) },
            onFollow = { _, _ -> },
        )
        val screen = Screen(taps, moves)
        screen.place = place()

        taps.fromReadiumFootnote(link(), "known note")
        advanceUntilIdle()

        assertEquals("known note", footnotes.single().first)
        assertEquals(place(), footnotes.single().second.place)
        assertTrue(footnotes.single().second.mayFallbackToPublishedPlace)
        assertEquals(0, lookup.asked)
    }

    @Test
    fun `a same-document tap with a stale capture is dropped`() = runTest {
        val footnotes = mutableListOf<String>()
        val followed = mutableListOf<Link>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { html, _, _ -> footnotes.add(html) },
            onFollow = { l, _ -> followed.add(l) },
        )
        val screen = Screen(taps, moves)
        screen.drop = true

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        advanceUntilIdle()

        assertTrue(footnotes.isEmpty())
        assertTrue(followed.isEmpty())
        assertEquals(0, lookup.asked)
    }

    @Test
    fun `a link readium handed over navigates without a captured place`() = runTest {
        val followed = mutableListOf<Pair<Link, LinkTaps.LinkOrigin>>()
        val lookup = Lookup()
        val moves = IssuedMoves()
        val taps = LinkTaps(
            scope = this,
            moves = moves,
            noteAt = lookup.noteAt,
            onFootnote = { _, _, _ -> },
            onFollow = { l, origin -> followed.add(l to origin) },
        )
        Screen(taps, moves)
        // This door retains its published-place fallback when capture fails.

        taps.fromReadium(link(fragment = null))
        advanceUntilIdle()

        assertEquals(
            listOf(link(fragment = null) to LinkTaps.LinkOrigin(null, true)),
            followed,
        )
    }

    @Test
    fun `a tap arriving with no screen armed is dropped`() = runTest {
        val followed = mutableListOf<Link>()
        val taps = LinkTaps(
            scope = this,
            moves = IssuedMoves(),
            noteAt = { null },
            onFootnote = { _, _, _ -> },
            onFollow = { l, _ -> followed.add(l) },
        )
        // No captureOrigin armed.

        taps.fromBridge(link(), "EPUB/ch1.xhtml")
        advanceUntilIdle()

        assertTrue(followed.isEmpty())
    }
}

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class FollowLinkTest {

    private fun place(): Locator = requireNotNull(
        Locator.fromJSON(
            org.json.JSONObject()
                .put("href", "EPUB/ch1.xhtml")
                .put("type", "application/xhtml+xml")
                .put("locations", org.json.JSONObject().put("progression", 0.4)),
        ),
    )

    @Test
    fun `a refused go announces nothing and cancels the issued move`() {
        val moves = IssuedMoves()
        val jumps = mutableListOf<Pair<Locator?, Boolean>>()
        val from = place()

        followLink(
            link = Link(href = Url("EPUB/notes.xhtml#n1")!!),
            from = from,
            moves = moves,
            nowMs = { 0L },
            currentPlace = { from },
            destinationOf = { null },
            go = { false },
            onJump = { origin, mayFallback -> jumps.add(origin to mayFallback) },
        )

        assertTrue(jumps.isEmpty())
        // The wait the refused go opened is closed: a later restore is not
        // left refusing because of a jump that never happened, and an
        // earlier return offer was never replaced to begin with.
        assertTrue(moves.mark(nowMs = 1_000) != IssuedMoves.NONE)
    }

    @Test
    fun `an accepted go announces the way back after the navigation`() {
        val moves = IssuedMoves()
        val jumps = mutableListOf<Pair<Locator?, Boolean>>()
        val order = mutableListOf<String>()
        val from = place()

        followLink(
            link = Link(href = Url("EPUB/notes.xhtml#n1")!!),
            from = from,
            moves = moves,
            nowMs = { 0L },
            currentPlace = { from },
            destinationOf = { null },
            go = {
                order.add("go")
                true
            },
            onJump = { origin, mayFallback ->
                order.add("jump")
                jumps.add(origin to mayFallback)
            },
        )

        assertEquals(listOf("go", "jump"), order)
        assertEquals(listOf(from to true), jumps)
    }

    @Test
    fun `acceptance arms the marker before any arrival can be labelled`() {
        val moves = IssuedMoves()
        val jumps = mutableListOf<Pair<Locator?, Boolean>>()
        val from = place()

        followLink(
            link = Link(href = Url("EPUB/notes.xhtml")!!),
            from = from,
            moves = moves,
            nowMs = { 0L },
            currentPlace = { from },
            destinationOf = { null },
            go = {
                // Not something the navigator does — it lays a resource
                // out and reports only afterwards — but if it ever did,
                // the arrival must not beat the marker that labels it.
                moves.onPosition(RestorePoint("EPUB/notes.xhtml", 0.0, false), nowMs = 0L)
                true
            },
            onJump = { origin, mayFallback -> jumps.add(origin to mayFallback) },
        )

        // The jump was announced regardless: the marker is armed in the
        // same turn, after go, and never after the arrival it labels.
        assertEquals(listOf(from to true), jumps)
    }

    @Test
    fun `a jump without a captured place falls back to the current one`() {
        val moves = IssuedMoves()
        val from = place()
        val jumps = mutableListOf<Pair<Locator?, Boolean>>()

        followLink(
            link = Link(href = Url("EPUB/notes.xhtml#n1")!!),
            from = null,
            moves = moves,
            nowMs = { 0L },
            currentPlace = { from },
            destinationOf = { null },
            go = { true },
            onJump = { origin, mayFallback -> jumps.add(origin to mayFallback) },
        )
        // The move is held against the current place when no capture
        // supplied one, so a restore taken now is refused.
        assertTrue(moves.mark(nowMs = 0L) == IssuedMoves.NONE)
        assertEquals(listOf(null to true), jumps)
    }

    @Test
    fun `a jump without a captured place is recorded without offering a return`() {
        val moves = IssuedMoves()
        val from = place()
        var followed = false
        val jumps = mutableListOf<Pair<Locator?, Boolean>>()

        followLink(
            link = Link(href = Url("EPUB/notes.xhtml#n1")!!),
            from = null,
            moves = moves,
            nowMs = { 0L },
            currentPlace = { from },
            destinationOf = { null },
            go = {
                followed = true
                true
            },
            onJump = { origin, mayFallback -> jumps.add(origin to mayFallback) },
            mayFallbackToPublishedPlace = false,
        )

        assertTrue(followed)
        assertEquals(listOf(null to false), jumps)
    }
}
