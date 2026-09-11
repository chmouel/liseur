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
 * [FooterMode.PAGES_LEFT_CHAPTER] counts Readium positions, and says
 * nothing at all when the chapter is unknown: counting to the end of
 * the book under a label that says "chapter" is worse than an empty
 * slot.
 */
fun footerMiddle(progress: ReaderProgress, mode: FooterMode): FooterMiddle? = when (mode) {
    FooterMode.SMART ->
        if (progress.isSpeedMeasured) {
            FooterMiddle.TimeInChapter(progress.minutesLeftInChapter)
        } else {
            progress.chapterTitle?.let(FooterMiddle::Chapter)
        }

    FooterMode.PAGES_LEFT_CHAPTER ->
        progress.positionsLeftInChapter?.let(FooterMiddle::PagesInChapter)

    FooterMode.TIME_LEFT_BOOK -> FooterMiddle.TimeInBook(progress.minutesLeftInBook)

    FooterMode.CHAPTER_TITLE -> progress.chapterTitle?.let(FooterMiddle::Chapter)

    FooterMode.EMPTY, FooterMode.NONE -> null
}
