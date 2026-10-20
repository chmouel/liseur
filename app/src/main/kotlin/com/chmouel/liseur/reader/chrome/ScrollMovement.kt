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
 * the first one after the navigator reported such a move without a
 * readable landing, starts a fresh baseline instead. A landing that
 * could be read is the baseline itself, so a drag straight after it
 * still counts.
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

    /** Suppresses scroll samples while an automatic move is being captured. */
    fun beginAutomaticMove() {
        automaticMoveInProgress = true
    }

    /**
     * Ends the automatic move. The page it landed at, [offset], is the
     * baseline a drag after it is measured from; when the landing cannot
     * be read, the next sample becomes the baseline instead.
     */
    fun endAutomaticMove(offset: Int?) {
        automaticMoveInProgress = false
        if (offset == null) {
            movedForReader = true
            return
        }
        last = offset
        lastAutomatic = false
        movedForReader = false
    }

    /** Forgets the baseline, as when the reader comes back to the book. */
    fun reset() {
        last = null
        lastAutomatic = false
        movedForReader = false
        automaticMoveInProgress = false
    }

    /**
     * Whether the page sits at [offset] because the reader scrolled it
     * since the last sample, without taking a sample. A page only moved
     * for the reader, or not yet sampled at all, has not been read on.
     */
    fun readerScrolledTo(offset: Int?): Boolean {
        if (offset == null || automaticMoveInProgress || movedForReader || lastAutomatic) return false
        val previous = last ?: return false
        return previous != offset
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
