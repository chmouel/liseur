package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.settings.FooterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the middle of the footer says, and above all when it stays
 * quiet: an estimate the app cannot stand behind must degrade to the
 * chapter's name, and a chapter with no name to nothing, never to a
 * stock figure dressed up as knowledge.
 */
class FooterMiddleTest {

    private val progress = ReaderProgress(
        position = 42,
        totalPositions = 300,
        totalProgression = 0.14f,
        chapterTitle = "The Sign of Four",
        minutesLeftInChapter = 12,
        minutesLeftInBook = 340,
        positionsLeftInChapter = 17,
        isSpeedMeasured = true,
    )

    @Test
    fun `smart shows chapter time once the pace is measured`() {
        assertEquals(
            FooterMiddle.TimeInChapter(12),
            footerMiddle(progress, FooterMode.SMART),
        )
    }

    @Test
    fun `smart falls back to the chapter title before that`() {
        assertEquals(
            FooterMiddle.Chapter("The Sign of Four"),
            footerMiddle(progress.copy(isSpeedMeasured = false), FooterMode.SMART),
        )
    }

    @Test
    fun `smart says nothing when there is nothing true to say`() {
        assertNull(
            footerMiddle(
                progress.copy(isSpeedMeasured = false, chapterTitle = null),
                FooterMode.SMART,
            ),
        )
    }

    @Test
    fun `book time is shown even before the pace settles`() {
        // The mode was asked for by name; a rough figure is what it means.
        assertEquals(
            FooterMiddle.TimeInBook(340),
            footerMiddle(progress.copy(isSpeedMeasured = false), FooterMode.TIME_LEFT_BOOK),
        )
    }

    @Test
    fun `a nameless chapter leaves the title mode empty`() {
        assertNull(footerMiddle(progress.copy(chapterTitle = null), FooterMode.CHAPTER_TITLE))
    }

    @Test
    fun `pages left is shown before any pace is measured`() {
        // The whole point of the mode: it needs no reading speed.
        assertEquals(
            FooterMiddle.PagesInChapter(17),
            footerMiddle(progress.copy(isSpeedMeasured = false), FooterMode.PAGES_LEFT_CHAPTER),
        )
    }

    @Test
    fun `the chapter's last page is kept as a count of zero`() {
        // Wording it is the footer's business; dropping it here would
        // blank the slot on the one page of the chapter that has
        // something plain to say.
        assertEquals(
            FooterMiddle.PagesInChapter(0),
            footerMiddle(progress.copy(positionsLeftInChapter = 0), FooterMode.PAGES_LEFT_CHAPTER),
        )
    }

    @Test
    fun `an unknown chapter counts nothing rather than counting the book`() {
        assertNull(
            footerMiddle(
                progress.copy(positionsLeftInChapter = null),
                FooterMode.PAGES_LEFT_CHAPTER,
            ),
        )
    }

    @Test
    fun `smart still falls back to the title, not to pages`() {
        // Pages left is a mode the reader asks for by name. Smart
        // promises time, and degrades to something that is not a
        // number at all rather than to a different number.
        assertEquals(
            FooterMiddle.Chapter("The Sign of Four"),
            footerMiddle(progress.copy(isSpeedMeasured = false), FooterMode.SMART),
        )
    }

    @Test
    fun `empty and hidden draw nothing in the middle`() {
        assertNull(footerMiddle(progress, FooterMode.EMPTY))
        assertNull(footerMiddle(progress, FooterMode.NONE))
    }
}
