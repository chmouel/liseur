package com.chmouel.liseur.translate

/**
 * Whether one book is read translated, kept so that it opens translated
 * again, in the same languages, until the reader stops it.
 *
 * [remember] and [forget] return at once and take effect in the order
 * they were called, from any handle on the same book; [saved] answers
 * only once every change asked before it has been made.
 */
interface PageTranslationModes {
    data class Mode(val source: String?, val target: String)

    suspend fun saved(): Mode?

    fun remember(mode: Mode)

    /** [onFailed] is called, off the main thread, when the saved mode could not be removed: the book may open translated again. */
    fun forget(onFailed: () -> Unit)
}

/** Kept only while the book is open: the reader's when nothing is saved. */
class MemoryPageTranslationModes : PageTranslationModes {
    @Volatile private var mode: PageTranslationModes.Mode? = null

    override suspend fun saved() = mode

    override fun remember(mode: PageTranslationModes.Mode) {
        this.mode = mode
    }

    override fun forget(onFailed: () -> Unit) {
        mode = null
    }
}
