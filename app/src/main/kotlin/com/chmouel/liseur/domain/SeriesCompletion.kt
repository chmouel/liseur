package com.chmouel.liseur.domain

/**
 * Whether a series is finished, as opposed to whether every volume
 * currently on the shelf has been read.
 *
 * The shelf fading when every known book is done is a presentation
 * detail. This is the sentence the reader is owed: that the series is
 * over, that they are caught up, or only that everything here has been
 * read. Optional server metadata is allowed to be late; until it
 * arrives the wording stays conservative and upgrades itself.
 */
enum class SeriesCompletion {
    /** At least one known volume is unfinished. */
    IN_PROGRESS,

    /**
     * The series is over and every volume of it has been read: no
     * gaps, an authoritative ENDED, and a total that is exactly the
     * shelf.
     */
    COMPLETE,

    /**
     * Everything published so far has been read, and more is expected:
     * ONGOING or HIATUS, with no known holes.
     */
    CAUGHT_UP,

    /**
     * Every known volume is finished, and that is all that can be said.
     * Local and calibre libraries with no total, abandoned series, and
     * shelves with missing volumes land here.
     */
    ALL_KNOWN_READ,
}

/**
 * Classifies a shelf once every volume on it has been looked at.
 *
 * Unknown extras are not a guess at COMPLETE or CAUGHT_UP: they are
 * [SeriesCompletion.ALL_KNOWN_READ], and a later cache fill is allowed
 * to promote the answer.
 */
fun seriesCompletion(
    shelf: SeriesShelf,
    extras: SeriesExtras? = null,
): SeriesCompletion {
    if (shelf.volumes.isEmpty() || shelf.volumes.any { !it.finished }) {
        return SeriesCompletion.IN_PROGRESS
    }
    val status = extras?.status?.uppercase()
    val total = extras?.totalBookCount
    val noGaps = shelf.gaps.isEmpty()
    return when {
        status == "ENDED" && noGaps && total != null && total == shelf.volumes.size ->
            SeriesCompletion.COMPLETE
        (status == "ONGOING" || status == "HIATUS") && noGaps ->
            SeriesCompletion.CAUGHT_UP
        else -> SeriesCompletion.ALL_KNOWN_READ
    }
}
