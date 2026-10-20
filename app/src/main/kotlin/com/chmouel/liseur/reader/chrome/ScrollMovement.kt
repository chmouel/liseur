package com.chmouel.liseur.reader.chrome

/**
 * Tells the reader scrolling from the page being moved for them.
 *
 * The scroll watcher compares the offsets of two polls, so a page the
 * book moved on its own, the opening restoration or a remote place being
 * adopted, would look like a drag at the next poll and be saved on pause
 * as a jump the reader made. A sample taken while the page is moving on
 * its own (the opening still being restored, or a move that saves
 * nothing still waiting to be reported), the first one after that, or
 * the first one after the navigator reported such a move, starts a fresh
 * baseline instead.
 *
 * Main thread only, like [HeldPlace].
 */
internal class ScrollMovement {

    private var last: Int? = null
    private var lastAutomatic = false
    private var movedForReader = false
    private var automaticMoveInProgress = false

    /** The page was moved for the reader; the next difference is not theirs. */
    fun movedForReader() {
        movedForReader = true
    }

    /** Keeps every scroll sample during an asynchronous automatic move from looking user-driven. */
    fun beginAutomaticMove() {
        automaticMoveInProgress = true
    }

    /** Ends the automatic move and gives the next sample a fresh baseline. */
    fun endAutomaticMove() {
        automaticMoveInProgress = false
        movedForReader = true
    }

    /** Forgets the baseline, as when the reader comes back to the book. */
    fun reset() {
        last = null
        lastAutomatic = false
        movedForReader = false
        automaticMoveInProgress = false
    }

    /** Whether [offset] is the reader scrolling since the previous sample. */
    fun sample(offset: Int?, automatic: Boolean): Boolean {
        if (offset == null) return false
        if (automaticMoveInProgress) {
            last = offset
            lastAutomatic = automatic
            return false
        }
        val previous = last
        val notTheReader = automatic || lastAutomatic || movedForReader
        last = offset
        lastAutomatic = automatic
        movedForReader = false
        return !notTheReader && previous != null && previous != offset
    }
}
