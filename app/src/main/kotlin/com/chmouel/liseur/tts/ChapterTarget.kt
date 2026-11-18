package com.chmouel.liseur.tts

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.Url

/**
 * Where a chapter skip in the player lands: a resource of the publication's
 * reading order, not an entry of its table of contents, which can point into
 * the middle of the resource the voice is already in.
 */
internal object ChapterTarget {

    /**
     * The resource a step [forward] from the one being read at
     * [currentHref] opens: the next one, or for a step back the start of
     * the one being read, or the one before when the voice is already
     * [atStart]. Null when there is nowhere to go, or when [currentHref] is
     * not in the reading order.
     */
    fun of(readingOrder: List<Link>, currentHref: Url, atStart: Boolean, forward: Boolean): Link? {
        val index = readingOrder.indexOfFirstWithHref(currentHref) ?: return null
        if (forward) return readingOrder.getOrNull(index + 1)
        return if (atStart) readingOrder.getOrNull(index - 1) else readingOrder[index]
    }
}
