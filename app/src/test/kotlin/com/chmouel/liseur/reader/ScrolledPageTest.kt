package com.chmouel.liseur.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a book is being read by scrolling.
 *
 * Two answers, deliberately, and the point of this class is that they
 * stay two. [chromeScrolls] follows the book's vertical text;
 * [containerScrolls] must not, because that arrives after the navigator
 * has been laid out and following it late reflows the page under the
 * reader. Anyone tempted to collapse these into one function should fail
 * `the container ignores vertical text` first.
 */
class ScrolledPageTest {

    @Test
    fun `a reflowable book scrolls when the reader asked it to`() {
        assertTrue(chromeScrolls(reflowable = true, scrollMode = true, verticalText = false))
        assertTrue(containerScrolls(reflowable = true, scrollMode = true))
    }

    @Test
    fun `a reflowable book left paginated does not`() {
        assertFalse(chromeScrolls(reflowable = true, scrollMode = false, verticalText = false))
        assertFalse(containerScrolls(reflowable = true, scrollMode = false))
    }

    @Test
    fun `vertical text scrolls the chrome whatever the reader chose`() {
        // Readium cannot paginate lines that run down the page.
        assertTrue(chromeScrolls(reflowable = true, scrollMode = false, verticalText = true))
    }

    @Test
    fun `the container ignores vertical text`() {
        // Not an oversight. `verticalText` is false until the navigator
        // has read the publication, so a container that followed it
        // would take the footer's reserved band back out from under a
        // book already laid out with it — a reflow on open, on every
        // vertical book. Merging this with chromeScrolls reintroduces
        // exactly that.
        assertFalse(containerScrolls(reflowable = true, scrollMode = false))
        assertTrue(chromeScrolls(reflowable = true, scrollMode = false, verticalText = true))
    }

    @Test
    fun `a fixed layout book never scrolls, however it is asked`() {
        // Readium paginates it whatever the preference says, so chrome
        // that scrolled would sit over a page that turns.
        for (scroll in listOf(false, true)) {
            for (vertical in listOf(false, true)) {
                assertFalse(
                    "scrollMode=$scroll verticalText=$vertical",
                    chromeScrolls(reflowable = false, scrollMode = scroll, verticalText = vertical),
                )
            }
            assertFalse(
                "scrollMode=$scroll",
                containerScrolls(reflowable = false, scrollMode = scroll),
            )
        }
    }
}
