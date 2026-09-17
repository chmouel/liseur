package com.chmouel.liseur.reader

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a book is on screen.
 *
 * Settings sync needs to know, because applying a pulled font size or
 * margin reflows the page under whoever is reading it. That is the same
 * rule an incoming reading position already follows — it waits for
 * resume rather than turning an open book's page — and this is how the
 * sync layer, which knows nothing about the reader, gets to ask.
 *
 * A counter rather than a flag: a rotation stops the old activity after
 * starting the new one, and a flag would read as closed for the moment
 * in between.
 */
object ReaderPresence {

    private val open = AtomicInteger(0)

    val isOpen: Boolean get() = open.get() > 0

    fun opened() {
        open.incrementAndGet()
    }

    fun closed() {
        open.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}
