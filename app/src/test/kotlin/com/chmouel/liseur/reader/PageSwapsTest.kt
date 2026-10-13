package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.PageSentence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The script the reader sends a translated page to ask whether a tap
 * landed on the words it marks as the voice's, checked the way
 * `ImageAtPointTest` checks its script: for what it asks of the
 * document rather than for what the document makes of the question,
 * which only a real WebView can say.
 */
class PageSwapsTest {

    private val sentence = PageSentence(
        text = "It was a dark and stormy night.",
        href = "/EPUB/text/ch1.xhtml",
        selector = "p.first",
        before = "Chapter One ",
    )

    private fun script(fx: Float = 0.5f, fy: Float = 0.5f): String =
        PageSwaps.markedAt("https://book/EPUB/text/ch1.xhtml", sentence, fx, fy)

    /** Only the markedAt half of the page's script, not its neighbours. */
    private fun body(): String = script()
        .substringAfter("markedAt(url, s, fx, fy) {")
        .substringBefore("restore() {")

    @Test
    fun `another document's page answers nothing`() {
        // Readium reuses web views between chapters, so every call names
        // the document it was meant for and a different one must not
        // answer for it.
        assertTrue(script().contains("markedAt("))
        assertTrue(body().contains("if (!here(url)) return null;"))
    }

    @Test
    fun `the script carries the point it was asked about`() {
        val js = script(0.25f, 0.75f)
        assertTrue(js.contains("0.25"))
        assertTrue(js.contains("0.75"))
    }

    @Test
    fun `the point is a fraction of the viewport, not a pixel count`() {
        // A fixed-layout page is drawn at whatever scale fits it, so a
        // fraction of the viewport is the same fraction however the page
        // is scaled; pixels of one kind or the other are not.
        val js = script()
        assertTrue(js.contains("fx * VW"))
        assertTrue(js.contains("fy * VH"))
        assertTrue(js.contains("window.innerWidth"))
        assertTrue(js.contains("document.documentElement.clientWidth"))
    }

    @Test
    fun `the words are found again rather than read out of a painted mark`() {
        // The answer must be the same on a WebView that cannot paint the
        // mark at all, so the sentence is located as `mark` locates it
        // and the CSS Custom Highlight is never asked.
        val body = body()
        assertTrue(body.contains("locate(s.selector, s.before, s.text)"))
        assertTrue(body.contains("shownRange(at)"))
        assertFalse(body.contains("CSS.highlights"))
    }

    @Test
    fun `dictionary language follows edits intersecting the current selection`() {
        val js = PageSwaps.selectionIntersectsAppliedSwap(
            "https://book/EPUB/text/ch1.xhtml",
            sentence,
        )
        assertTrue(js.contains("window.getSelection()"))
        assertTrue(js.contains("edits.get(node)"))
        assertTrue(js.contains("range.compareBoundaryPoints(Range.END_TO_START, changed)"))
        assertTrue(js.contains("range.compareBoundaryPoints(Range.START_TO_END, changed)"))
        val body = js.substringAfter("selectionIntersectsAppliedSwap(url, s) {")
            .substringBefore("markedAt(url, s, fx, fy) {")
        assertTrue(
            body.contains("if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;"),
        )
        assertTrue(body.contains("if (!at) return null;"))
    }

    @Test
    fun `a rect with nothing in it is not a place a finger can land`() {
        assertTrue(body().contains("r.width > 0 && r.height > 0"))
    }

    @Test
    fun `a finger is allowed to miss by a few pixels`() {
        val body = body()
        assertTrue(body.contains("const SLOP = "))
        assertTrue(body.contains("r.left - SLOP"))
        assertTrue(body.contains("r.bottom + SLOP"))
    }
}
