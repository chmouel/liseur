package com.chmouel.liseur.domain

/**
 * Moving a book from one place in a list to another.
 *
 * The whole of what a drag or a pair of arrows does, kept away from
 * anything that knows what a book is so it can be tested in a line.
 * Out-of-range indices are returned unchanged rather than throwing: a
 * fling that ends past the end of the list is a gesture, not a bug.
 */
fun <T> List<T>.movedItem(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * The numbers to write for a shelf drafted into this order, or nothing
 * when there is nothing to write.
 *
 * Nothing when the draft is the order it started in — Done on a shelf
 * nobody moved must not renumber it, or opening the mode and closing it
 * again would silently take a series' half-numbers away.
 *
 * Otherwise **every** volume, numbered 1…n, and not merely the ones
 * whose number changed. A row left alone keeps no override, which means
 * its source still owns it, which means the next catalog refresh can
 * move it back out of the order just set; the shelf would rearrange
 * itself hours later for no reason the reader could see. Writing the
 * whole sequence is what makes the order hold.
 *
 * That numbering is lossy, deliberately: a series running 1, 1.5, 2
 * comes out 1, 2, 3, and the gaps that let the shelf say "book 3 is
 * missing" close up. The novella keeps its place and loses its number.
 * The alternative — slotting a moved book in at 2.5 — keeps both, and
 * produces numbers (2.5, 2.75, 2.875) that no reader can tell from a
 * real one.
 */
fun renumbered(draft: List<String>, original: List<String>): List<Pair<String, Double>> {
    if (draft == original) return emptyList()
    return draft.mapIndexed { i, url -> url to (i + 1).toDouble() }
}
