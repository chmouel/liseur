package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.settings.FooterField

/**
 * What one edge of the reading footer has to say, or nothing.
 *
 * Every figure here is derived from the page on screen and thrown away
 * when it is drawn. None of it is stored, sent, or counted as reading.
 *
 * [Pages] and [PagesLeftInBook] carry [exact] for the same reason the
 * right edge always has: a reflowable book's length is estimated from
 * the resources the reader has passed through, so the figure is a good
 * guess rather than a count, and the screen reader is told so. See
 * `docs/adr/0036-the-footer-counts-screens.md`.
 */
sealed interface FooterFigure {
    /** A percentage of the book, read or left. */
    data class BookPercent(val percent: Int, val left: Boolean) : FooterFigure

    /** A percentage of the chapter, read or left. */
    data class ChapterPercent(val percent: Int, val left: Boolean) : FooterFigure

    /** `137/892` in the book. */
    data class Pages(val page: Int, val pages: Int, val exact: Boolean) : FooterFigure

    /** How many pages of the book are still ahead. */
    data class PagesLeftInBook(val pages: Int, val exact: Boolean) : FooterFigure

    /** `4/12` in the chapter. */
    data class PagesInChapter(val page: Int, val pages: Int) : FooterFigure

    /** How many pages are left before the next chapter, zero included. */
    data class PagesLeftInChapter(val pages: Int) : FooterFigure

    /** Minutes left in the book at the measured pace. */
    data class TimeInBook(val minutes: Int) : FooterFigure

    /** Minutes left in the chapter at the measured pace. */
    data class TimeInChapter(val minutes: Int) : FooterFigure

    /** The stable Readium position, the one figure typography cannot move. */
    data class Location(val position: Int, val total: Int) : FooterFigure

    /** Milliseconds since the epoch, for the edge to format as a time of day. */
    data class Clock(val atMillis: Long) : FooterFigure

    /** Percentage of charge left, and whether it is going up. */
    data class Battery(val percent: Int, val charging: Boolean) : FooterFigure
}

/**
 * What the device was saying when the footer was last drawn.
 *
 * Both readings are nullable and both are allowed to be absent: a
 * device that will not say how much charge it has leaves the edge
 * empty rather than showing a zero it invented. They are passed in
 * rather than read here so the arithmetic stays free of Android and
 * testable off a device, like everything else in this package.
 */
data class FooterDevice(
    val nowMillis: Long? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
)

/**
 * Chooses what one edge of the footer draws, or null for nothing.
 *
 * Nothing is the answer whenever the figure cannot be stood behind,
 * which is the rule ADR-36 set for the right edge and which every
 * field inherits: a reflowable page still laying itself out has no
 * screens to count, a chapter that could not be resolved has no
 * countdown, and a book whose positions are unusable has no page.
 * An edge left blank for a moment reads as an edge; a figure swapped
 * for one that counts something else reads as a fault.
 *
 * The time fields wait for a measured pace, which the middle's own
 * [com.chmouel.liseur.data.settings.FooterMode.TIME_LEFT_BOOK] does
 * not. The middle has room to fall back to the chapter's name and room
 * for the words that hedge a guess; an edge has neither, and a stock
 * figure sitting where a measured one goes is read as measured.
 *
 * As in ADR-36 the chapter is the resource on screen, so a chapter
 * split across files restarts at its second file.
 */
fun footerFigure(
    field: FooterField,
    progress: ReaderProgress?,
    reflowable: Boolean,
    screens: SectionScreens?,
    estimate: BookScreenEstimate,
    device: FooterDevice = FooterDevice(),
): FooterFigure? {
    if (field == FooterField.EMPTY) return null
    // The two device fields know nothing about the book and are
    // answered before a missing page can stand in their way.
    when (field) {
        FooterField.CLOCK -> return device.nowMillis?.let(FooterFigure::Clock)
        FooterField.BATTERY -> return device.batteryPercent
            ?.takeIf { it in 0..100 }
            ?.let { FooterFigure.Battery(it, device.charging) }

        else -> Unit
    }
    progress ?: return null
    return when (field) {
        FooterField.PERCENT_READ -> FooterFigure.BookPercent(progress.percent, left = false)

        FooterField.PERCENT_LEFT ->
            FooterFigure.BookPercent(100 - progress.percent, left = true)

        FooterField.PAGE_OF_BOOK ->
            footerPages(reflowable, screens, progress, estimate)
                ?.let { FooterFigure.Pages(it.page, it.pages, it.exact) }

        FooterField.PAGES_LEFT_BOOK ->
            footerPages(reflowable, screens, progress, estimate)
                ?.let {
                    // Clamped rather than refused: the total is an
                    // estimate and the page is counted, so a dense
                    // chapter can put the page past a total that has
                    // not caught up. Zero is true there — this is the
                    // last page the estimate knows about — and a
                    // negative count is not.
                    FooterFigure.PagesLeftInBook(
                        pages = (it.pages - it.page).coerceAtLeast(0),
                        exact = it.exact,
                    )
                }

        FooterField.PAGES_LEFT_CHAPTER ->
            chapterPagesLeft(progress, reflowable, screens)
                ?.let(FooterFigure::PagesLeftInChapter)

        FooterField.PAGE_IN_CHAPTER ->
            // Only measured screens can answer this one. The fallback
            // the percentages use is a share of the resource, and a
            // share is not a page: rounding it into "4 of 12" would
            // print a count of pages that were never laid out.
            screens
                ?.takeIf { reflowable }
                ?.let { FooterFigure.PagesInChapter(it.screen, it.screens) }

        FooterField.PERCENT_READ_CHAPTER ->
            chapterPercent(progress, reflowable, screens)
                ?.let { FooterFigure.ChapterPercent(it, left = false) }

        FooterField.PERCENT_LEFT_CHAPTER ->
            chapterPercent(progress, reflowable, screens)
                ?.let { FooterFigure.ChapterPercent(100 - it, left = true) }

        FooterField.TIME_LEFT_BOOK ->
            progress.takeIf { it.isSpeedMeasured }
                ?.let { FooterFigure.TimeInBook(it.minutesLeftInBook) }

        FooterField.TIME_LEFT_CHAPTER ->
            progress
                // A chapter that could not be resolved leaves
                // minutesLeftInChapter counting to the end of the
                // book, and the reader has no way of telling that
                // from a very long chapter. The positions left are
                // null in exactly that case, which is what
                // FooterMode.PAGES_LEFT_CHAPTER already goes on.
                .takeIf { it.isSpeedMeasured && it.positionsLeftInChapter != null }
                ?.let { FooterFigure.TimeInChapter(it.minutesLeftInChapter) }

        FooterField.LOCATION ->
            progress
                .takeIf { it.totalPositions > 0 }
                ?.let { FooterFigure.Location(it.position, it.totalPositions) }

        FooterField.CLOCK, FooterField.BATTERY, FooterField.EMPTY -> null
    }
}

/**
 * How far through the chapter the reader is, as a percentage.
 *
 * Measured screens answer it exactly in a paginated reflowable book,
 * and the screen on screen counts as read, so the last one says 100%
 * rather than leaving a chapter that has been finished at 92%.
 *
 * Everywhere else — a fixed-layout book, a resource not yet measured —
 * it is where the reading position sits between the resource's own
 * ends. That is a share of the resource rather than a count of its
 * pages, which is honest as a percentage and is why
 * [FooterField.PAGE_IN_CHAPTER] refuses the same fallback. A
 * fixed-layout resource holds a single position, so it reads 100% for
 * as long as it is open, which is what the measured path says about a
 * chapter of one screen, and so does a book of a single page.
 */
private fun chapterPercent(
    progress: ReaderProgress,
    reflowable: Boolean,
    screens: SectionScreens?,
): Int? {
    if (reflowable && screens != null) {
        val counted = screens.screens.takeIf { it > 0 } ?: return null
        return percent(screens.screen.toDouble() / counted)
    }
    val resource = progress.resource ?: return null
    val span = resource.span.takeIf { it > 0.0 } ?: return null
    val positions = progress.totalPositions.takeIf { it > 0 } ?: return null
    // A resource's ends are shares of the book's positions, counted
    // from the first position to one past the last, so the position on
    // screen is what belongs on the same ruler. The whole-book
    // progression is on a different one, running from the first
    // position to the last, and subtracting the two as they came
    // handed a fixed-layout book, which holds one position per
    // resource, the whole book's percentage under a label saying
    // "chapter". It is also a Float, and converting it back landed a
    // hair under the position it came from, which cost a page its
    // last percent.
    //
    // The position counts as read, the same rule the measured path
    // follows, so a chapter that has been turned through says 100%
    // rather than 92%. That end matters for the last resource in the
    // book, where the progression stops short of 1.0: one position
    // less and a two-position final chapter would read half done with
    // the book finished.
    return percent((progress.position.toDouble() / positions - resource.start) / span)
}

private fun percent(fraction: Double): Int? {
    if (!fraction.isFinite()) return null
    return (fraction * 100).toInt().coerceIn(0, 100)
}
