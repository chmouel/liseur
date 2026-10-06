package com.chmouel.liseur.readaloud

import com.chmouel.liseur.reader.OpenBookHandle
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import org.readium.r2.shared.publication.Locator

/**
 * Saves where read-aloud has got to, the way a page turn would be saved.
 *
 * An utterance locator from Readium's content iterator carries no
 * position and a whole-book progression guessed between resources, so
 * it goes through the book's own [OpenBookHandle.prepareLocator] and
 * positions exactly as the reader's moves do; one that does not resolve
 * is skipped and the next checkpoint tries again. No reading pace or
 * reading time is recorded: listening teaches neither.
 */
class ListeningCheckpoints(
    private val publisher: ReadingPositionPublisher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Writes [locator], heard at [spokenAt], while [generation] still owns
     * the place. [share] asks for a sync of its own; the periodic
     * checkpoints during playback do not.
     *
     * @return whether the checkpoint was queued.
     */
    fun save(
        handle: OpenBookHandle,
        generation: Long,
        locator: Locator,
        spokenAt: Long,
        share: Boolean,
    ): Boolean {
        if (!handle.listeningHolds(generation)) return false
        val prepared = handle.prepareLocator(locator)
        val stable = handle.positionsOrNull?.resolve(prepared) ?: return false
        val settles = handle.listeningSettles
        val accepted = publisher.publish(
            PositionUpdate(
                bookUrl = handle.bookId,
                locatorJson = prepared.toJSON().toString(),
                progression = stable.progression,
                readingSecondsPerPosition = null,
                readingPaceSamples = null,
                readingPaceElapsedMs = null,
                readingPaceEvidence = null,
                updatedAt = now(),
                bookOrbitPull = settles?.pull,
                bookOrbitDeclined = settles?.declined,
                readAt = spokenAt,
                signalSync = share,
            ),
        )
        if (accepted) {
            handle.noteListened()
            handle.awaitingPageCapture = true
            handle.lastHeard = locator
            if (settles?.declined != null) handle.listeningSettles = settles.copy(declined = null)
        }
        return accepted
    }
}
