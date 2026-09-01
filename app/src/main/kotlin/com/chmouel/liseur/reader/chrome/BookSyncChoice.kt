package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.domain.EPSILON

/**
 * What syncing one book by hand should do about the two positions it
 * found.
 *
 * A decision, and only a decision: page numbers need the book laid out,
 * which is the reader's business rather than this one's. Everything here
 * runs on two doubles and a flag, which is what makes it worth testing
 * without a navigator, a database or a server.
 *
 * This is deliberately *not* how a sync running on its own behalf
 * decides. Those take whichever side has read further, because nobody is
 * watching and a question with no one to answer it is a stall. The button
 * is somebody asking, so it gets asked back.
 */
sealed interface BookSyncVerdict {
    /** The server has no position for this book at all. */
    data object NoRemote : BookSyncVerdict

    /** Both sides are in the same place. */
    data object InStep : BookSyncVerdict

    /**
     * Only the server has a position, so there is nothing to choose
     * between: take it.
     */
    data object NoLocal : BookSyncVerdict

    /**
     * The server is behind on this device's own pushes, and knows
     * nothing another device wrote.
     *
     * Not a disagreement between two readers: there is only one
     * position, and the server has an older copy of it. Nothing was
     * preserved to adopt, so the only true answer is to send.
     */
    data object Owed : BookSyncVerdict

    /** Two positions, and a reader who is the only one who knows. */
    data class Ask(val relation: SyncRelation) : BookSyncVerdict
}

/** How the server's position sits against this device's. */
enum class SyncRelation {
    /** The server has read further. */
    AHEAD,

    /** This device has. The case an ordinary sync says nothing about. */
    BEHIND,

    /**
     * The same page, but not the same spot.
     *
     * Two percentages within rounding of each other while the anchors
     * disagree outright. Rare, and worth its own words: two identical
     * page numbers over two different buttons is a riddle, and the
     * excerpt is the only thing that tells the sides apart.
     */
    SAME_PAGE,
}

object BookSyncChoice {

    /**
     * Reads a preview and says what to do about it.
     *
     * [SyncPreview.agrees] settles the easy half — it already knows that
     * an exact anchor agreeing is agreement, and that two percentages
     * within [EPSILON] are as well.
     *
     * A missing local position is not a local position of zero. It means
     * this device has nothing to offer, so offering it as a choice would
     * be inventing a side; the server's answer is simply taken.
     */
    fun decide(preview: SyncPreview): BookSyncVerdict {
        val there = preview.remote ?: return BookSyncVerdict.NoRemote
        if (preview.agrees) return BookSyncVerdict.InStep
        // Asked before the sides are weighed: an answer that was never
        // written down cannot be adopted however far ahead it looks, and
        // offering it would put a button on screen that does nothing.
        if (!preview.resolvable) return BookSyncVerdict.Owed
        val here = preview.local ?: return BookSyncVerdict.NoLocal
        return BookSyncVerdict.Ask(
            when {
                there - here >= EPSILON -> SyncRelation.AHEAD
                here - there >= EPSILON -> SyncRelation.BEHIND
                else -> SyncRelation.SAME_PAGE
            },
        )
    }
}
