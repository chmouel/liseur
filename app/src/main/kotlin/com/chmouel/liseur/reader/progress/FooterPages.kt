package com.chmouel.liseur.reader.progress

/**
 * The numbers on the right edge of the reading footer: which page of
 * the whole book is on screen, and how many the book holds.
 *
 * [exact] says whether the total was counted or estimated. A
 * fixed-layout book's pages are set by the publisher and Readium gives
 * one position to each, so there the count is the book's own. A
 * reflowable book has no pages until it is laid out, and only the
 * resource being drawn can be laid out, so its total is scaled from the
 * resources the reader has passed through. See
 * `docs/adr/0036-the-footer-counts-screens.md`.
 */
data class FooterPages(val page: Int, val pages: Int, val exact: Boolean)

/**
 * What the footer can say about pages, or null when it should say
 * nothing.
 *
 * A reflowable book whose screens have not been measured yet — a
 * resource still laying itself out, a reflow still moving — shows no
 * page rather than the stable position it used to show. The two numbers
 * count different things, and swapping one in for the other under the
 * same label is how a footer starts lying: it would jump from 3 to 412
 * and back while the page settled.
 */
fun footerPages(
    reflowable: Boolean,
    screens: SectionScreens?,
    progress: ReaderProgress?,
    estimate: BookScreenEstimate,
): FooterPages? {
    progress ?: return null
    if (!reflowable) {
        return progress
            .takeIf { it.totalPositions > 0 }
            ?.let { FooterPages(it.position, it.totalPositions, exact = true) }
    }
    val screen = screens ?: return null
    val here = progress.resource ?: return null
    val total = estimate.totalScreens ?: return null
    val page = estimate.pageAt(here.index, screen.screen) ?: return null
    return FooterPages(page, total, exact = false)
}
