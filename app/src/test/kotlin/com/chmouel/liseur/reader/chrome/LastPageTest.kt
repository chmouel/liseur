package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastPageTest {

    @Test
    fun `the last resource in a book is the last one`() {
        assertTrue(isLastReadingResource(indexInReadingOrder = 3, lastIndex = 3))
        assertTrue(isLastReadingResource(indexInReadingOrder = 0, lastIndex = 0))
    }

    @Test
    fun `a middle resource is not the end of the book`() {
        assertFalse(isLastReadingResource(indexInReadingOrder = 2, lastIndex = 3))
        assertFalse(isLastReadingResource(indexInReadingOrder = 0, lastIndex = 1))
    }

    @Test
    fun `a href that is not in the spine is not the last page`() {
        assertFalse(isLastReadingResource(indexInReadingOrder = null, lastIndex = 3))
    }

    @Test
    fun `an empty spine has no last page`() {
        assertFalse(isLastReadingResource(indexInReadingOrder = 0, lastIndex = -1))
        assertFalse(isLastReadingResource(indexInReadingOrder = null, lastIndex = -1))
    }

    @Test
    fun `the last page of the last resource is the end of the book`() {
        assertTrue(atEndOfBook(lastResource = true, pageReport = "\"at-end\""))
        assertTrue(atEndOfBook(lastResource = true, pageReport = "at-end"))
    }

    @Test
    fun `the last page of a middle chapter is not the end of the book`() {
        assertFalse(atEndOfBook(lastResource = false, pageReport = "\"at-end\""))
    }

    @Test
    fun `more content still waiting in the last resource is not the end`() {
        assertFalse(atEndOfBook(lastResource = true, pageReport = "\"scrolled\""))
        assertFalse(atEndOfBook(lastResource = true, pageReport = null))
    }

    @Test
    fun `the last-content script looks at the page, not at leftover columns`() {
        val script = lastContentVisibleScript()
        assertTrue(script.contains("getBoundingClientRect"))
        assertTrue(script.contains("NodeFilter.SHOW_TEXT"))
        assertFalse(script.contains("scrollWidth"))
        assertTrue(script.contains("\"$AT_END\""))
        // A missing body is a page that has not loaded yet, not the end
        // of the book: "scrolled" lets the turn fall through until the
        // document can actually be inspected.
        assertTrue(script.contains("""if (!document.body) { return "scrolled"; }"""))
        assertFalse(script.contains("""if (!document.body) { return "$AT_END"; }"""))
    }

    @Test
    fun `the last-content script skips nodes that never paint`() {
        val script = lastContentVisibleScript()
        assertTrue(script.contains("SCRIPT"))
        assertTrue(script.contains("STYLE"))
        assertTrue(script.contains("isHidden"))
        assertTrue(script.contains("FILTER_REJECT"))
        assertTrue(script.contains("if (r.width < 1 && r.height < 1) continue"))
        assertFalse(script.contains("if (r.width < 1 && r.height < 1) { return"))
        // aria-hidden only hides from the accessibility tree; a visible
        // illustration marked that way still has to count as the last page.
        assertFalse(script.contains("aria-hidden"))
    }

    @Test
    fun `a probe is still the same page at the same place`() {
        assertTrue(
            sameProbePlace(
                currentHref = "chapter-end.xhtml",
                currentProgression = 0.95,
                probedHref = "chapter-end.xhtml",
                probedProgression = 0.95,
            ),
        )
        assertTrue(
            sameProbePlace(
                currentHref = "chapter-end.xhtml",
                currentProgression = null,
                probedHref = "chapter-end.xhtml",
                probedProgression = null,
            ),
        )
    }

    @Test
    fun `a probe is stale after a turn inside the last resource`() {
        assertFalse(
            sameProbePlace(
                currentHref = "chapter-end.xhtml",
                currentProgression = 0.80,
                probedHref = "chapter-end.xhtml",
                probedProgression = 0.95,
            ),
        )
    }

    @Test
    fun `a probe is stale after leaving the resource it inspected`() {
        assertFalse(
            sameProbePlace(
                currentHref = "chapter-1.xhtml",
                currentProgression = 0.95,
                probedHref = "chapter-end.xhtml",
                probedProgression = 0.95,
            ),
        )
    }
}
