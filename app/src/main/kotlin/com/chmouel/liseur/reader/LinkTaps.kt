package com.chmouel.liseur.reader

import com.chmouel.liseur.reader.progress.RestorePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a tap on an internal link becomes its effect, whichever door it
 * came in by.
 *
 * A link reaches the app one of two ways: Readium hands it over through
 * the navigator listener, or — for the plain same-document anchor Readium
 * and the WebView both ignore — the page script hands its fragment over
 * through the [com.chmouel.liseur.reader.footnotes.SameDocumentLinkBridge].
 * Both doors meet here, because both must answer the same questions the
 * same way: is this a note worth a card, is the reader still where they
 * were when they tapped, and if it comes to a jump, is there a way back.
 *
 * A tap is *registered* the moment it is accepted — a request number from
 * a monotonic sequence, and the [IssuedMoves] generation — before
 * anything suspends. Only the latest request may apply its result, so of
 * two taps with lookups in flight, whichever answers, only the later one
 * acts; the generation orders a tap against the reader's own page turns
 * in between. The two checks are complementary: the sequence orders
 * sibling taps, the generation orders a tap against the reader.
 *
 * The place the tap started from is captured live, by the reader screen
 * (which owns the capture machinery), after the request is registered and
 * before the routing below runs — so a pill offered back names the
 * passage on screen when the tap landed, not the last place the
 * navigator happened to have published.
 */
internal class LinkTaps(
    private val scope: CoroutineScope,
    private val moves: IssuedMoves,
    private val noteAt: suspend (Link) -> String?,
    private val onFootnote: (html: String, link: Link) -> Unit,
    private val onFollow: (link: Link, from: Locator?) -> Unit,
) {
    /**
     * What a tap started from, and how to ask whether it still may act.
     *
     * The screen bakes the guards into [stillCurrent]: same navigator,
     * same resource, request still the latest, no move issued since. It
     * is asked once more after the note lookup, immediately before the
     * effect — never just before the lookup — so no card pops over a
     * departed chapter and no earlier tap's result overwrites a later
     * one's.
     */
    internal class Origin(
        val place: Locator?,
        val stillCurrent: () -> Boolean,
    )

    /**
     * The live capture, supplied by the reader screen once composed and
     * withdrawn when it goes. Null while there is no screen, and a tap
     * arriving then is dropped rather than guessed at.
     */
    @Volatile
    var captureOrigin: (suspend (request: Int, generation: Int, resourceHref: String?, placeRequired: Boolean) -> Origin?)? =
        null

    private val requests = AtomicInteger()

    /** The request number the latest accepted tap holds. */
    fun latestRequest(): Int = requests.get()

    /**
     * A same-document tap that passed the caller's gates: the emitting
     * resource is known, and the place must be captured or the tap
     * dropped — a pill offering back a place nobody verified is worse
     * than a link that does nothing.
     */
    fun fromBridge(link: Link, resourceHref: String) {
        accept(link, resourceHref, placeRequired = true)
    }

    /**
     * A link Readium handed over. It comes from the navigator itself, so
     * there is no emitting resource to name and the current one is
     * whatever the screen says when it captures; a capture that fails
     * navigates anyway, as this door always has, with the way back
     * falling back to the last published place.
     */
    fun fromReadium(link: Link) {
        accept(link, null, placeRequired = false)
    }

    private fun accept(link: Link, resourceHref: String?, placeRequired: Boolean) {
        val request = requests.incrementAndGet()
        val generation = moves.generation()
        scope.launch {
            val capture = captureOrigin ?: return@launch
            val origin = capture(request, generation, resourceHref, placeRequired)
                ?: return@launch
            followInternal(link, origin)
        }
    }

    /**
     * The routing every internal link gets: a whole resource is a chapter
     * and going there is what was asked for; anything with a fragment
     * might be a note, and only reading the target can say, so the answer
     * is deferred and revalidated before it acts.
     */
    internal suspend fun followInternal(link: Link, origin: Origin) {
        if (link.url().fragment.isNullOrBlank()) {
            onFollow(link, origin.place)
            return
        }
        val note = try {
            noteAt(link)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // An unreadable resource is not worth a message. The link
            // still goes where it said it would.
            null
        }
        if (!origin.stillCurrent()) return
        if (note != null) onFootnote(note, link) else onFollow(link, origin.place)
    }
}

/**
 * Follows [link], announcing the way back only once the navigation has
 * accepted it.
 *
 * The announcement ([onJump]) is not something a dismissal can roll back:
 * it replaces and re-arms any existing return offer, ends an active
 * source peek and resets pace tracking. Announcing first and dismissing
 * on refusal would therefore destroy a still-valid earlier offer — jump
 * A's pill lost when jump B's `go` refuses. Announcing after acceptance
 * leaves all of it untouched on refusal: the issued move is cancelled,
 * nothing is offered for a jump that never happened, and whatever offer,
 * peek or pace was there survives.
 *
 * [go] and [onJump] run in the same main-thread turn, and the navigator
 * reports a position only once the resource is laid out and on screen —
 * never synchronously inside `go` — so the pending jump marker is always
 * armed before the arrival it labels.
 */
internal fun followLink(
    link: Link,
    from: Locator?,
    moves: IssuedMoves,
    nowMs: () -> Long,
    currentPlace: () -> Locator,
    destinationOf: (Link) -> IssuedMoves.Destination?,
    go: (Link) -> Boolean,
    onJump: (Locator?) -> Unit,
) {
    val token = moves.issue(
        from = (from ?: currentPlace()).restorePoint(),
        to = destinationOf(link),
        nowMs = nowMs(),
    )
    if (go(link)) {
        onJump(from)
    } else {
        moves.cancel(token)
    }
}
