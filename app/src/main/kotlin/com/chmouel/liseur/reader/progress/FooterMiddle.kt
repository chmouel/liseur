package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.settings.FooterMode

/** What the middle of the reading footer should draw, if anything. */
sealed interface FooterMiddle {
    data class TimeInChapter(val minutes: Int) : FooterMiddle
    data class TimeInBook(val minutes: Int) : FooterMiddle
    data class Chapter(val title: String) : FooterMiddle

    /**
     * Pages left before the next chapter. Zero is kept rather than
     * turned into nothing: the reader is on the chapter's last page,
     * which is worth saying, and how to word it is the footer's
     * business rather than this decision's.
     */
    data class PagesInChapter(val pages: Int) : FooterMiddle
}

/**
 * Chooses the footer's middle slot.
 *
 * [FooterMode.SMART] shows time left in the chapter once a pace has
 * been measured, and the chapter title until then — a figure the app
 * cannot yet stand behind is replaced by something true, not by a
 * stock guess and not by a blank. A chapter with no name leaves the
 * slot empty rather than inventing one.
 *
 * [FooterMode.PAGES_LEFT_CHAPTER] counts the pages the reader still
 * has to turn, and says nothing at all when the chapter is unknown:
 * counting to the end of the book under a label that says "chapter" is
 * worse than an empty slot. In a book being read in pages that is a
 * count of screenfuls, measured from the page on screen, so it comes
 * down by one for every turn. A fixed-layout book counts its own
 * pages, which are Readium positions there.
 */
fun footerMiddle(
    progress: ReaderProgress,
    mode: FooterMode,
    reflowable: Boolean = false,
    screens: SectionScreens? = null,
): FooterMiddle? = when (mode) {
    FooterMode.SMART ->
        if (progress.isSpeedMeasured) {
            FooterMiddle.TimeInChapter(progress.minutesLeftInChapter)
        } else {
            progress.chapterTitle?.let(FooterMiddle::Chapter)
        }

    FooterMode.PAGES_LEFT_CHAPTER ->
        pagesLeftInChapter(progress, reflowable, screens)?.let(FooterMiddle::PagesInChapter)

    FooterMode.TIME_LEFT_BOOK -> FooterMiddle.TimeInBook(progress.minutesLeftInBook)

    FooterMode.CHAPTER_TITLE -> progress.chapterTitle?.let(FooterMiddle::Chapter)

    FooterMode.EMPTY, FooterMode.NONE -> null
}

/**
 * How many pages are left before the next chapter, in whatever a page
 * is in this book.
 *
 * Whether there is a chapter to count to is still decided by the
 * stable positions, so every rule about a nameless chapter and a
 * chapter the position lookup cannot resolve is inherited untouched.
 * Only the unit changes: where a screenful can be measured, the count
 * is screenfuls, so that a turn takes exactly one off it.
 *
 * An unmeasured reflowable page reports nothing rather than falling
 * back to the stable count. The two disagree, and a figure that jumps
 * from 8 to 10 and back while a reflow settles is read as a fault.
 */
private fun pagesLeftInChapter(
    progress: ReaderProgress,
    reflowable: Boolean,
    screens: SectionScreens?,
): Int? {
    // No chapter to count to.
    progress.positionsLeftInChapter ?: return null
    if (!reflowable) return progress.positionsLeftInChapter
    return screens?.let { it.screens - it.screen }
}
