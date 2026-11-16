package com.chmouel.liseur.domain

/** What the app knows about the book it would drop you back into. */
data class ResumeCandidate(
    /** The book's permanent identity, used for its reading position. */
    val identity: String,
    /** The file to open. */
    val fileUrl: String,
    val totalProgression: Double?,
    /** Marked read, by hand or by having been finished. */
    val finished: Boolean = false,
    /** The recording this place came from covers only part of the book. */
    val partialCoverage: Boolean = false,
    /** What someone said about the book, which outranks its position. */
    val override: FinishedOverride = FinishedOverride.NONE,
)

/**
 * Whether opening the app should go straight back to a book.
 *
 * Reading apps that always land on the library make you find your book
 * again every single time; ones that always resume trap you in a book you
 * have finished. So: resume when you were last reading, and stop when you
 * either left from the library or reached the end.
 *
 * Whether that end was reached is [readingStatusFor]'s answer rather than
 * a bare threshold here: a partial recording stops short of `1.0` by
 * design, and the library already calls such a book unfinished.
 */
fun shouldResume(candidate: ResumeCandidate?, leftFromReader: Boolean): Boolean {
    if (candidate == null || !leftFromReader) return false
    if (candidate.finished) return false
    return readingStatusFor(candidate.totalProgression, candidate.override, candidate.partialCoverage).isNextUp()
}

fun ReadingStatus.isNextUp(): Boolean = when (this) {
    ReadingStatus.READY_TO_READ, ReadingStatus.WANT_TO_READ, ReadingStatus.READING -> true
    ReadingStatus.FINISHED, ReadingStatus.PAUSED, ReadingStatus.DID_NOT_FINISH -> false
}

/**
 * Books rarely end on the last page — acknowledgements, indexes and the
 * publisher's back matter all come after the story does, and nobody wants
 * to be dropped back into a colophon.
 */
const val FINISHED_PROGRESSION = 0.97
