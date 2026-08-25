package com.chmouel.liseur.reader.chrome

import org.readium.r2.shared.publication.Locator

/**
 * The place a scrolled book was last measured at, kept for the pause.
 *
 * Readium answers a scroll with a debounced location, and the reader is
 * marked inactive before the fragment pauses — so a debounce still in
 * the air when the reader leaves is dropped as movement nobody made,
 * and a page that never stops never lands one at all. Both cases need a
 * place already measured, ready to be published on the way out without
 * asking the document anything.
 *
 * Measuring one takes several round trips into the document, and the
 * reader can move while it is being answered. What comes back is
 * therefore held against the state of things when the asking began: a
 * fresher place [invalidate]s everything that was in flight, and such a
 * measurement is refused rather than allowed to overwrite it. Without
 * that, a reader who jumps *back* within the same chapter can be
 * carried forward again on the way out, by a measurement that was
 * already in the air when they jumped.
 *
 * Refusing an old measurement and throwing away the place already held
 * are two different acts, and the difference is the whole point of
 * [invalidate] existing beside [retire]. A location Readium publishes
 * is not saved as it arrives: it is captured first, and that capture
 * suspends. Clearing the held place the moment the location was
 * announced would leave nothing to publish if the reader left while the
 * capture was still being answered — which is exactly the case this
 * class exists for. So the old place stands until the new one has been
 * taken, and only a place that is genuinely no longer the reader's — a
 * chapter they have left, a navigator that has gone — is retired.
 *
 * Nothing here is synchronised, and nothing here should be: the loops
 * that measure, the collector that publishes and the observer that
 * reads all live on the main thread, the same as [CachedLookup].
 */
internal class HeldPlace {

    private var held: Locator? = null
    private var generation = 0

    /** The state to hold a later measurement against. See [hold]. */
    fun mark(): Int = generation

    /**
     * Refuses every measurement now in flight, keeping the held place.
     *
     * Answers with the mark to hold the measurement being taken *now*
     * against, so a caller that invalidates in order to replace can do
     * both without a second call racing its own.
     */
    fun invalidate(): Int {
        generation++
        return generation
    }

    /**
     * Keeps [place], unless something fresher arrived since [since].
     *
     * Answers whether it was kept, so a caller that also publishes the
     * measurement can decide both from the one question.
     *
     * Keeping is itself something fresher arriving, so it moves the mark
     * on as well: two measurements begun in the same generation are
     * racing, and the one that lands first is the one that stands. The
     * loser is refused rather than merged, because nothing here can tell
     * which of them the reader is nearer to — they were asked for at
     * different moments and answered out of order. Being a poll behind
     * costs a line; being overwritten by an older answer walks the
     * reader backwards, which is the thing this class exists to stop.
     */
    fun hold(place: Locator, since: Int): Boolean {
        if (since != generation) return false
        held = place
        generation++
        return true
    }

    /** Whatever is still worth publishing when the reader leaves. */
    fun current(): Locator? = held

    /**
     * Forgets the place, and refuses any measurement already in flight.
     *
     * For a place that has stopped being the reader's at all: a chapter
     * they have left, a navigator that has been replaced, a book that
     * has stopped being scrolled. Not for a place merely superseded —
     * see [invalidate].
     */
    fun retire() {
        held = null
        generation++
    }
}
