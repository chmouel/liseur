package com.chmouel.liseur.reader.chrome

/**
 * A value that is expensive to find and cheap to check.
 *
 * The reader's frame loop needs the web view it is scrolling sixty
 * times a second, and finding it means walking the view tree. Holding
 * on to the answer only works if there is a way to notice it has gone
 * stale, so a holder is given both halves: how to find the value, and
 * how to tell whether the one it has is still the answer. The check is
 * expected to be the cheap half — a comparison rather than a search —
 * and it is only ever asked of a value already held.
 *
 * Nothing here is synchronised, and nothing here should be: this is a
 * plain field read on whichever thread calls it, meant to be confined
 * to a single dispatcher — for the reader, the main one, where both the
 * frame loop and the events that invalidate it already live. A holder
 * shared across threads would need a different design, not a lock
 * bolted onto this one.
 */
internal class CachedLookup<T : Any>(
    private val stillGood: (T) -> Boolean,
    private val lookup: () -> T?,
) {

    private var held: T? = null

    /**
     * The value, found again only if the one held will no longer do.
     *
     * A lookup that comes back empty is not remembered as an answer:
     * the next call asks again, because "not there yet" is a state
     * things come out of.
     */
    fun current(): T? {
        val known = held
        if (known != null && stillGood(known)) return known
        return lookup().also { held = it }
    }

    /**
     * Forgets what is held, so the next [current] goes and looks.
     *
     * For the moments a caller knows about and the predicate cannot see
     * yet — a page turn that has been asked for but not laid out.
     */
    fun invalidate() {
        held = null
    }
}
