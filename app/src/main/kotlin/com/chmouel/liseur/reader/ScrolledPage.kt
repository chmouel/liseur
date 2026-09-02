package com.chmouel.liseur.reader

/**
 * Whether a book is being read by scrolling — asked twice, because the
 * two askers need different answers.
 *
 * Three things decide it. What the reader chose (`scrollMode`), what the
 * book's lines do (`verticalText`: Readium cannot paginate text that
 * runs down the page, so such a book scrolls whatever the setting says),
 * and whether the book has anything to scroll at all (`reflowable`: a
 * fixed-layout book is placed page by page and Readium paginates it
 * whatever the setting says).
 *
 * See `docs/adr/0020-fixed-layout-reading-settings.md`.
 */

/**
 * What the reader sees around the page: the tap zones, the footer, the
 * edge turner, auto-scroll, and which rows the sheets offer.
 *
 * This is the full answer, [verticalText] included, because the chrome
 * has to describe the page that is actually there.
 */
fun chromeScrolls(reflowable: Boolean, scrollMode: Boolean, verticalText: Boolean): Boolean =
    reflowable && (scrollMode || verticalText)

/**
 * What the navigator fragment is laid out inside: the insets it is given
 * and the band reserved under it for the footer.
 *
 * **Deliberately without [chromeScrolls]'s `verticalText` term, and not
 * to be merged back into it.** Whether a book's text is vertical is only
 * known once the navigator has read the publication, so it arrives false
 * and turns true a moment later; a container that followed it would take
 * the footer's band back out from under a book that had already been
 * laid out with it, reflowing the page under the reader on open. The
 * comment on the fragment's padding says the same thing from the other
 * side: an unused 38dp is the cheaper of the two.
 *
 * Both terms here are settled before the navigator exists, so this
 * answer never changes while a book is open.
 */
fun containerScrolls(reflowable: Boolean, scrollMode: Boolean): Boolean =
    reflowable && scrollMode
