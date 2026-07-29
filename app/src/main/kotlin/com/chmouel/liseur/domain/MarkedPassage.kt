package com.chmouel.liseur.domain

import kotlin.math.abs

/**
 * Whether a passage the reader has just selected is one they already
 * marked, or a new one next to it.
 *
 * The distinction decides whether picking a colour edits the mark that is
 * there or adds another, and whether the popup offers to delete something
 * the reader is not pointing at. Getting it wrong loses a highlight.
 *
 * Position alone is not enough to tell them apart. A reading position is
 * only ever accurate to about a page — that is all calibre-web exchanges
 * — so two passages a paragraph apart have, as far as the numbers go, the
 * same position. What separates them is the words: a selection is the
 * same mark as one already stored when it is in the same resource, close
 * to it, and made of the same text.
 */

/**
 * How far apart two points in one resource can be and still be the same
 * passage. A chapter is one resource, so a fiftieth of it is a paragraph
 * or two: enough to allow for a word being picked out of a longer
 * highlight, far too little to reach the next marked passage.
 */
private const val WITHIN_RESOURCE = 0.02

/** One selected passage, in the terms this decision needs. */
data class MarkedPassage(
    val href: String,
    /** How far into its own resource, 0..1, not into the book. */
    val progression: Double?,
    /** The words themselves, if the locator carries them. */
    val text: String?,
)

/** Whether [selection] points at the passage [mark] already covers. */
fun isSamePassage(selection: MarkedPassage, mark: MarkedPassage): Boolean {
    if (selection.href != mark.href) return false

    val near = selection.progression != null && mark.progression != null &&
        abs(selection.progression - mark.progression) < WITHIN_RESOURCE

    val a = selection.text?.trim()?.lowercase().orEmpty()
    val b = mark.text?.trim()?.lowercase().orEmpty()
    if (a.isEmpty() || b.isEmpty()) {
        // Nothing to compare but the numbers, and those are only good to
        // about a page. Being in the same resource and all but on top of
        // each other is the most that can be claimed.
        return near
    }

    // A word picked out of a longer highlight is that highlight; a
    // different sentence nearby is not.
    return near && (a.contains(b) || b.contains(a))
}
