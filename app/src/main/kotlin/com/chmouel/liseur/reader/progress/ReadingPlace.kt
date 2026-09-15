package com.chmouel.liseur.reader.progress

import org.readium.r2.shared.publication.Locator
import kotlin.math.abs

/**
 * How close two within-resource progressions must be to be the same
 * page. A progression is computed from the laid-out document rather
 * than measured off it, so two readings of one page agree exactly and
 * anything wider than rounding noise is a different page.
 */
const val LOCATOR_EPSILON = 0.000001

/**
 * Whether two locators name the same page.
 *
 * The exact anchor decides it wherever both sides carry one. It names
 * the first word visible on the screen, so it does not move when the
 * layout does, and it does not round: Readium's integer positions are
 * far coarser than a screen, and several screens in a row share one.
 * Asking them instead is what let a bookmark match pages the reader had
 * long since turned past.
 *
 * Without an anchor the question falls to the resource and the place in
 * it. Positions that contradict each other settle it on their own; past
 * that it is the progression, which is finer.
 */
fun samePage(one: Locator?, other: Locator?): Boolean {
    one ?: return false
    other ?: return false
    if (one.href != other.href) return false

    val anchor = ExactLocatorAnchor.anchorIn(one)
    val otherAnchor = ExactLocatorAnchor.anchorIn(other)
    if (anchor != null && otherAnchor != null) return anchor == otherAnchor

    val position = one.locations.position
    val otherPosition = other.locations.position
    if (position != null && otherPosition != null && position != otherPosition) return false

    val progression = one.locations.progression
    val otherProgression = other.locations.progression
    if (progression != null && otherProgression != null) {
        return abs(progression - otherProgression) < LOCATOR_EPSILON
    }
    return position != null && otherPosition != null
}

/**
 * Whether this locator says where in its resource it is.
 *
 * One that does not is still worth keeping, but it can only be placed
 * by how far into the book it is. calibre-web sends marks like that: a
 * percentage and no more.
 */
fun Locator.namesItsPage(): Boolean =
    ExactLocatorAnchor.anchorIn(this) != null ||
        locations.progression != null ||
        locations.position != null
