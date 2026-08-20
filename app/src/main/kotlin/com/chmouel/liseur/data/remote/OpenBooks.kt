package com.chmouel.liseur.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The books someone is reading right now.
 *
 * A sync run and an open book share the same row, and a run that was
 * given up on does not stop running: it may decide to move the book long
 * after the reader stopped waiting for it. That move would land under a
 * page already on screen, where nobody sees it, and the first page turn
 * would write over it — losing where the other device had got to. So a
 * pull nobody asked to see is only applied while nobody is looking.
 *
 * Entries are counted rather than flagged, so two readers of the same
 * book — or one whose screens overlap during a rotation — cannot free
 * each other's hold by leaving.
 *
 * Nothing here survives the process, deliberately. A dead process has no
 * page on screen to protect, and the state a declined pull preserves is
 * on disk already, waiting for the next open to ask about it.
 */
class OpenBooks {

    private val lock = Mutex()
    private val reading = mutableMapOf<String, Int>()

    /** Says someone is now reading [bookUrl]. Pair with [leave]. */
    suspend fun enter(bookUrl: String) {
        lock.withLock { reading[bookUrl] = (reading[bookUrl] ?: 0) + 1 }
    }

    /** Says a reader of [bookUrl] has gone. */
    suspend fun leave(bookUrl: String) {
        lock.withLock {
            val left = (reading[bookUrl] ?: 0) - 1
            if (left > 0) reading[bookUrl] = left else reading.remove(bookUrl)
        }
    }

    /**
     * Runs [apply] unless [bookUrl] is being read, and answers null when
     * it is.
     *
     * The book is held closed for the duration: a reader arriving while
     * [apply] is mid-write waits in [enter] until the write has landed,
     * and so reads what it wrote rather than what it was replacing. That
     * only stays cheap if [apply] is quick — a database transaction, not
     * a network call.
     */
    suspend fun <T> unlessOpen(bookUrl: String, apply: suspend () -> T): T? =
        lock.withLock {
            if (bookUrl in reading) null else apply()
        }
}
