package com.chmouel.liseur.reader

import com.chmouel.liseur.data.bookorbit.BookOrbitPullOffer
import com.chmouel.liseur.reader.progress.BookPositions
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * One opened book shared by everything that reads it: the reader on
 * screen and a read-aloud session that can outlive it.
 *
 * Each holder hands [release] the work it would have done on closing —
 * leaving the sync fence, adopting a verified server place, asking for
 * a sync, letting go of private EPUB copies. None of it runs until the
 * last holder has gone, so a reader closed while audio plays keeps the
 * book fenced and pushes nothing, and the publication is closed once.
 */
class OpenBookHandle internal constructor(
    val bookId: String,
    val publication: Publication,
    positions: BookPositions? = null,
    private val onClosed: (OpenBookHandle) -> Unit,
) {
    private val lock = Any()
    private var holders = 1
    private var closed = false
    private val closings = mutableListOf<() -> Unit>()
    private val positionsLock = Mutex()

    @Volatile
    private var positions: BookPositions? = positions

    /**
     * Whether listening has moved the saved place since the book was
     * opened. A server place verified at opening is then no longer the
     * untouched opening it was, and must not be adopted on close.
     */
    @Volatile
    var listened: Boolean = false
        private set

    /** The book's positions, computed once for every holder. */
    suspend fun positions(): BookPositions =
        positions ?: positionsLock.withLock {
            positions ?: BookPositions.of(publication).also { positions = it }
        }

    /** The positions if they have been computed yet. */
    val positionsOrNull: BookPositions? get() = positions

    /** Adds the layout-independent whole-book progression to a locator. */
    fun prepareLocator(locator: Locator): Locator {
        val stable = positions?.resolve(locator) ?: return locator
        return ExactLocatorAnchor.withStableProgression(locator, stable.progression)
    }

    fun noteListened() {
        listened = true
    }

    /**
     * Who saves the place. While read-aloud plays it does, and the
     * reader's own moves are dropped; [ownership] changes at every
     * handover so a write measured under an earlier owner can be told
     * apart from one measured now.
     */
    @Volatile
    var listeningOwnsPlace: Boolean = false
        private set

    @Volatile
    var ownership: Long = 0
        private set

    /** Read-aloud takes the place over; returns the generation it holds. */
    fun claimForListening(): Long = synchronized(lock) {
        ownership++
        listeningOwnsPlace = true
        ownership
    }

    /**
     * Read-aloud gives the place back, if [generation] is still the one
     * it holds. False when somebody has taken it since.
     */
    fun handBack(generation: Long): Boolean = synchronized(lock) {
        if (!listeningOwnsPlace || generation != ownership) return false
        ownership++
        listeningOwnsPlace = false
        true
    }

    /** Whether [generation] still owns the place for read-aloud. */
    fun listeningHolds(generation: Long): Boolean =
        listeningOwnsPlace && ownership == generation

    /**
     * The saved place came from listening, so it is neither an exact
     * anchor nor carries a verified BookOrbit CFI until the reader shows
     * that page and measures it.
     */
    @Volatile
    var awaitingPageCapture: Boolean = false

    /** The utterance the last listening checkpoint saved. */
    @Volatile
    var lastHeard: Locator? = null

    /**
     * BookOrbit offers the reader had open when listening started, which
     * listening settles the way a jump does: a verified opening pull is
     * accepted by the first checkpoint and a catch-up offer declined.
     */
    @Volatile
    var listeningSettles: ListeningSettlement? = null

    /** Takes another hold, or answers false once the book has closed. */
    internal fun acquire(): Boolean = synchronized(lock) {
        if (closed) return false
        holders++
        true
    }

    /**
     * Lets go of one hold. [onClose] is this holder's closing work,
     * deferred to the last release and then run in release order, after
     * the publication is closed.
     */
    fun release(onClose: (() -> Unit)? = null) {
        val run = synchronized(lock) {
            check(!closed) { "Released a closed book" }
            onClose?.let(closings::add)
            holders--
            if (holders > 0) return
            closed = true
            closings.toList()
        }
        onClosed(this)
        publication.close()
        run.forEach { it() }
    }
}

data class ListeningSettlement(
    val pull: BookOrbitPullOffer?,
    val declined: BookOrbitPullOffer?,
)

/** The books open right now, so a second holder reuses the first one's. */
class OpenBookHandles {
    private val lock = Mutex()
    private val open = mutableMapOf<String, OpenBookHandle>()

    /** The live handle for [bookId], held once more, or null. */
    fun acquire(bookId: String): OpenBookHandle? = synchronized(open) {
        open[bookId]?.takeIf { it.acquire() }
    }

    /**
     * Holds [bookId]'s live handle, or opens the book with [openBook] when
     * nobody holds it. The new hold goes to [keep] before any suspension,
     * so a caller cancelled on the way cannot lose it; when [keep] declines
     * it, it is released at once. Null when [openBook] fails or [keep]
     * declined.
     */
    suspend fun open(
        bookId: String,
        keep: (OpenBookHandle) -> Boolean,
        openBook: suspend () -> Publication?,
    ): OpenBookHandle? = lock.withLock {
        val handle = acquire(bookId) ?: openBook()?.let { publication ->
            OpenBookHandle(bookId, publication) { closed ->
                synchronized(open) { if (open[bookId] === closed) open.remove(bookId) }
            }.also { synchronized(open) { open[bookId] = it } }
        } ?: return@withLock null
        if (keep(handle)) handle else null.also { handle.release() }
    }
}
