package com.chmouel.liseur.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The window during which the rendered page is being rebuilt underneath the
 * reader.
 *
 * Readium announces a locator whenever the page it is showing changes, and it
 * cannot tell a page the reader turned to from one that arrived because the
 * text was re-laid out. Arming a single pending event before a reflow only
 * covers the first announcement; a reflow that settles in two steps announces
 * twice, and the second reads as a page turn. Worse, an armed event that is
 * never spent stays armed, and the reader's *next* real page turn is then
 * discarded as a reflow and their place is not saved.
 *
 * So the reflow is a scope rather than a token: while it is open, every
 * announcement nobody has claimed is a reflow, however many there are, and
 * `finally` closes it even if the work inside throws or is cancelled.
 *
 * The mutex is not about the flag. Two reflows overlapping would each capture
 * the place to return to from a layout the other is still moving, and then
 * navigate against it; taking them in turn is what keeps the anchor meaningful.
 *
 * Both the flag and its readers live on the main dispatcher, where the
 * navigator's locators are collected, so it needs no synchronisation of its own.
 */
class ReflowScope {
    private val mutex = Mutex()

    var active: Boolean = false
        private set

    suspend fun <T> within(block: suspend () -> T): T =
        mutex.withLock {
            active = true
            try {
                block()
            } finally {
                active = false
            }
        }
}
