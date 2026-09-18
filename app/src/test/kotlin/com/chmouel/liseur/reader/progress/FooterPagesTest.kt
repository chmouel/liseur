package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which page the footer's right edge shows.
 *
 * The rule under every case here is that a number that counts one
 * thing is never shown under a label for another. A reflowable book
 * with nothing measured yet shows nothing at all, rather than falling
 * back to the stable position it used to print.
 */
class FooterPagesTest {

    private val progress = ReaderProgress(
        position = 42,
        totalPositions = 300,
        totalProgression = 0.14f,
        chapterTitle = "The Sign of Four",
        minutesLeftInChapter = 12,
        minutesLeftInBook = 340,
        positionsLeftInChapter = 17,
        isSpeedMeasured = true,
        resource = BookResource(index = 2, href = "ch03.xhtml", start = 0.10, end = 0.15),
    )

    /** A book of twenty equal resources, each laying out into 12 screens. */
    private val estimate = BookScreenEstimate().recording(0, 2, 0.10, 0.15, 12)

    @Test
    fun `a reflowable book counts screenfuls of the whole book`() {
        // 12 screens for a twentieth of the book puts the book at 240,
        // and a tenth of the way in is 24 screens already behind.
        assertEquals(
            FooterPages(page = 28, pages = 240, exact = false),
            footerPages(reflowable = true, screens = SectionScreens(4, 12), progress, estimate),
        )
    }

    @Test
    fun `a turn inside the resource moves the page by exactly one`() {
        val pages = (1..12).map {
            footerPages(true, SectionScreens(it, 12), progress, estimate)?.page
        }
        assertEquals((25..36).toList(), pages)
    }

    @Test
    fun `the total stands still while the reader walks through a resource`() {
        val totals = (1..12).mapNotNull {
            footerPages(true, SectionScreens(it, 12), progress, estimate)?.pages
        }.distinct()
        assertEquals(listOf(240), totals)
    }

    @Test
    fun `an unmeasured page shows nothing rather than the stable position`() {
        assertNull(footerPages(reflowable = true, screens = null, progress, estimate))
        assertNull(
            footerPages(true, SectionScreens(4, 12), progress, BookScreenEstimate()),
        )
    }

    @Test
    fun `a fixed-layout book keeps its own exact page count`() {
        assertEquals(
            FooterPages(page = 42, pages = 300, exact = true),
            footerPages(reflowable = false, screens = null, progress, BookScreenEstimate()),
        )
    }

    @Test
    fun `no place in the book means no page`() {
        assertNull(footerPages(true, SectionScreens(4, 12), null, estimate))
        assertNull(footerPages(false, null, null, BookScreenEstimate()))
    }

    @Test
    fun `a place with no resource behind it has no page`() {
        // Nothing tied this locator to a file, so there is no stretch
        // of the book to count its screens into.
        assertNull(
            footerPages(true, SectionScreens(4, 12), progress.copy(resource = null), estimate),
        )
    }

    @Test
    fun `another resource's measurement is not borrowed`() {
        // The estimate knows resource 2. A place in resource 7 shows
        // nothing rather than resource 2's screens under its name.
        val elsewhere = progress.copy(
            resource = BookResource(index = 7, href = "ch08.xhtml", start = 0.35, end = 0.40),
        )
        assertNull(footerPages(true, SectionScreens(4, 12), elsewhere, estimate))
    }
}
