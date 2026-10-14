package com.chmouel.liseur.reader

import org.readium.r2.shared.publication.Locator

/**
 * The reader following a character profile's source to an earlier page.
 *
 * Looking at a source is not reading there. While a peek lasts, the page
 * shown is not saved, synced, timed or taken as the furthest place: the
 * saved place stays [origin], where the reader asked. A peek ends when
 * the reader genuinely reads on from the source (a page turn or scroll of
 * their own, followed by a move, or a deliberate jump), or when a Return
 * is seen to arrive back at [origin].
 *
 * @param returning the navigation a Return is waiting on, so an answer
 *   for an older one is not taken for this one's.
 * @param userTurned whether the reader has turned a page or scrolled
 *   since the source was shown: the next move after that is reading.
 */
data class SourcePeek(
    val origin: Locator,
    val phase: Phase = Phase.PEEKING,
    val returning: Int = 0,
    val userTurned: Boolean = false,
) {
    enum class Phase { PEEKING, RETURNING }

    /**
     * Whether a position emission of [event] ends the peek. Only one the
     * reader caused by reading does: arrivals, reflows, the chrome
     * coming up and anything during a Return only show the page.
     * [moved] is whether the place differs from the one last shown.
     */
    fun endedBy(event: NavigatorPositionEvent, moved: Boolean): Boolean =
        phase == Phase.PEEKING && userTurned && moved && event == NavigatorPositionEvent.READER_MOVEMENT

    /** The reader turned a page or scrolled; ignored during a Return, which is not reading. */
    fun turned(): SourcePeek = if (phase == Phase.PEEKING) copy(userTurned = true) else this

    /** A Return set off; the answer must name the same [returning]. */
    fun returningNow(): SourcePeek = copy(phase = Phase.RETURNING, returning = returning + 1, userTurned = false)

    /** A Return that did not arrive: still a peek, and still recoverable. */
    fun returnFailed(generation: Int): SourcePeek =
        if (phase == Phase.RETURNING && returning == generation) copy(phase = Phase.PEEKING) else this

    /** Whether a Return answered as arrived ends this peek. */
    fun returnedBy(generation: Int): Boolean = phase == Phase.RETURNING && returning == generation
}
