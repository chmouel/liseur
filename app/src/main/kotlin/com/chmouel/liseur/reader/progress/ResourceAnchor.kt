package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.reader.ResourceAddress
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/**
 * The place in a book a peer named, kept when the exact word cannot be.
 *
 * An exact anchor ([ExactLocatorAnchor]) names a passage and survives a
 * different font, a different column count and a different screen. When
 * it cannot be resolved — the other client never captured one, the quote
 * has moved, the selector no longer matches — what is left is still far
 * better than a percentage: the resource the reader was in, and how far
 * through it they were.
 *
 * That matters most in the books this exists for. A whole-book
 * progression is a fraction of *the whole book*, and the two clients
 * compute it differently: this one interpolates Readium's synthetic
 * positions, the web reader interpolates the server's position list by
 * byte weight. The two disagree by a little on every book, and by more
 * on one whose resources are unevenly sized — which is the ordinary
 * shape of an EPUB 2 with few, large spine items. Turning that fraction
 * back into a place lands in roughly the right region of the book. The
 * resource and its own progression land in the right chapter, at the
 * right point of it, whoever wrote them down.
 *
 * So this is deliberately *not* a smaller exact anchor. It is the
 * coarser rung below one, and everything that reads it must go on
 * calling the result approximate.
 */
object ResourceAnchor {

    /**
     * The resource-relative place in [locatorJson], with nothing else.
     *
     * Everything an anchor could be mistaken for is dropped rather than
     * carried: the text quote and its selector (which no longer
     * describe a passage this device can verify), the CFI fragments
     * (which this client cannot resolve at all), and the synthetic
     * `position` (which is the *writing* client's pagination — Readium's
     * count and the web reader's are two different numbers for the same
     * spot, so a foreign one indexes into the wrong place here).
     *
     * Null when there is no usable resource-relative place, which is not
     * a failure: it simply means the whole-book progression is the best
     * that can be done.
     */
    fun sanitize(locatorJson: String?): Locator? =
        parse(locatorJson)?.let(::sanitize)

    fun sanitize(locator: Locator?): Locator? {
        val locator = locator ?: return null
        if (locator.href.toString().isBlank()) return null
        // The within-resource fraction is the whole point: without it
        // there is no "how far into the chapter" to restore, and the
        // resource alone would send the reader to its first paragraph
        // when they were most of the way through it.
        val progression = fraction(locator.locations.progression) ?: return null
        // copy() keeps the href and the media type and replaces the rest
        // outright, which is what makes this a whitelist: a locations
        // field invented after this was written is dropped by default
        // rather than carried by an omission here.
        return locator.copy(
            locations = Locator.Locations(
                progression = progression,
                totalProgression = fraction(locator.locations.totalProgression),
            ),
            text = Locator.Text(),
        )
    }

    /**
     * Whether [locatorJson] names a resource-relative place at all.
     *
     * Cheaper than sanitising when the answer is all that is wanted, and
     * it asks exactly the same question, so the two cannot disagree.
     */
    fun isResourceJson(locatorJson: String?): Boolean = sanitize(locatorJson) != null

    /**
     * The sanitised place in [locator], if this publication has that
     * resource.
     *
     * [readingOrder] is the opened publication's spine paths. A locator
     * that names a resource this book does not have is not a place in
     * it — it came from another edition, or from a client that spells
     * the path differently — and following it would leave the navigator
     * nowhere. Checked here rather than at the sync boundary because
     * only the open publication can answer it.
     */
    fun targetIn(locator: Locator?, readingOrder: Collection<String>): Locator? {
        val resource = sanitize(locator) ?: return null
        val target = ResourceAddress.canonicalPath(resource.href.toString()) ?: return null
        val matches = readingOrder.filter {
            ResourceAddress.canonicalPath(it) == target
        }
        if (matches.size != 1) return null
        val localHref = matches.single().let { Url(it) ?: Url("/$it") } ?: return null
        return resource.copy(href = localHref)
    }

    /**
     * Where to reopen: the exact passage, else the chapter, else the
     * percentage.
     *
     * The order is the point, and it lives here so that opening a book,
     * accepting a catch-up offer and recovering from a quote that would
     * not verify cannot drift apart. The percentage is last because it
     * is the only rung that can land in the wrong chapter.
     */
    fun resumeTarget(
        saved: Locator?,
        totalProgression: Double?,
        readingOrder: Collection<String>,
        byProgression: (Double) -> Locator?,
    ): Locator? = saved?.takeIf(ExactLocatorAnchor::isExact)
        ?: approximateTarget(saved, totalProgression, readingOrder, byProgression)
        ?: saved

    /**
     * The rungs below the exact one, for a caller whose exact attempt
     * has already been made and failed.
     */
    fun approximateTarget(
        saved: Locator?,
        totalProgression: Double?,
        readingOrder: Collection<String>,
        byProgression: (Double) -> Locator?,
    ): Locator? = targetIn(saved, readingOrder)
        ?: (totalProgression ?: saved?.locations?.totalProgression)
            ?.takeIf(::isFraction)
            ?.let(byProgression)

    /** A spine path, without the fragment or query an anchor carries. */
    fun path(href: String): String = href.substringBefore('#').substringBefore('?')

    /** Whether a value can be used as a whole-book or resource fraction. */
    fun isFraction(value: Double?): Boolean =
        value?.let(::fraction) != null

    /**
     * A number that was really written down, told apart from one that
     * was not.
     *
     * Out of range is refused rather than clamped. A peer that sends
     * `1.7` has not told this device the reader is at the end of the
     * chapter; it has told it something impossible, and treating that as
     * the end would move the reader on the strength of a bug. Zero is
     * legitimate and must survive: it is the top of the chapter.
     */
    private fun fraction(value: Double?): Double? {
        val value = value ?: return null
        if (!value.isFinite()) return null
        // Written this way round so a NaN is refused, as in SyncOps.
        if (!(value >= 0.0 && value <= 1.0)) return null
        return value
    }

    private fun parse(json: String?): Locator? {
        if (json.isNullOrEmpty() || json == "{}") return null
        return runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
    }
}
