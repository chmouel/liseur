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
    fun `empty and hidden draw nothing in the middle`() {
        assertNull(footerMiddle(progress, FooterMode.EMPTY))
        assertNull(footerMiddle(progress, FooterMode.NONE))
    }
}
