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
)

/**
 * Whether opening the app should go straight back to a book.
 *
 * Reading apps that always land on the library make you find your book
 * again every single time; ones that always resume trap you in a book you
 * have finished. So: resume when you were last reading, and stop when you
 * either left from the library or reached the end.
 */
fun shouldResume(candidate: ResumeCandidate?, leftFromReader: Boolean): Boolean {
    if (candidate == null || !leftFromReader) return false
    if (candidate.finished) return false
    val progression = candidate.totalProgression ?: return true
    return progression < FINISHED_PROGRESSION
}

/**
 * Books rarely end on the last page — acknowledgements, indexes and the
 * publisher's back matter all come after the story does, and nobody wants
 * to be dropped back into a colophon.
 */
const val FINISHED_PROGRESSION = 0.97
