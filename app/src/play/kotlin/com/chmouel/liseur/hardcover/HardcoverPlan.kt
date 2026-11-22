package com.chmouel.liseur.hardcover

import com.chmouel.liseur.domain.ReadingStatus
import kotlin.math.roundToInt

/** Hardcover's `user_books.status_id` values. */
object HardcoverStatus {
    const val WANT_TO_READ = 1
    const val CURRENTLY_READING = 2
    const val READ = 3
    const val PAUSED = 4
    const val DID_NOT_FINISH = 5
}

/** One read of a book on Hardcover. Dates are `YYYY-MM-DD`, as Hardcover keeps them. */
data class HardcoverRead(
    val id: Long,
    val pages: Int?,
    val startedAt: String?,
    val finishedAt: String?,
    /** The edition the read is logged against, and its page count. */
    val editionId: Long? = null,
    val editionPages: Int? = null,
)

/** What Hardcover holds for one book in the reader's library. */
data class HardcoverShelfState(
    /** The library entry's id, or null when the book is not in the library. */
    val userBookId: Long?,
    val statusId: Int?,
    /** 0.5 to 5 in halves, or null when unrated. */
    val rating: Double?,
    /** The latest read, open or finished. */
    val read: HardcoverRead?,
    val editionId: Long? = null,
)

/** What this device knows about the book, from stored state only. */
data class LocalReading(
    val status: ReadingStatus,
    /** True when someone chose the status, rather than the position implying it. */
    val chosen: Boolean,
    val progression: Double?,
    /** The day the first reading session here began. */
    val startedOn: String?,
    /** The day the book was finished, when it is. */
    val finishedOn: String?,
    /** Hardcover's scale: 1 to 5 in halves, or null when unrated here. */
    val rating: Double?,
)

/** The one write to the library entry a push may need. */
sealed interface HardcoverEntryStep {
    data class Add(val statusId: Int, val rating: Double?) : HardcoverEntryStep

    /** Null fields are left as they are; [clearRating] sends an explicit null. */
    data class Update(
        val statusId: Int?,
        val rating: Double?,
        val clearRating: Boolean = false,
        val editionId: Long? = null,
    ) : HardcoverEntryStep
}

/** The one write to a read a push may need; null fields are left as they are. */
data class HardcoverReadStep(
    val pages: Int? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val editionId: Long? = null,
)

/**
 * What to tell Hardcover about a book, worked out from both sides so a
 * push sends only the difference and a second push sends nothing.
 *
 * Hardcover keeps the entry and its reads in step by itself: a book set
 * to currently reading gets a read, a book set to read has it finished,
 * and each change writes a journal entry. So a push changes the status
 * first, and only then corrects the read Hardcover made (the pages, and
 * the real dates instead of today).
 *
 * Things this refuses to do, because each would undo something the
 * reader did on purpose:
 *
 * - A book already read on Hardcover stays read. Opening a finished book
 *   to look something up is not a re-read.
 * - A book paused or put down on Hardcover moves only for a status chosen
 *   here, never because a page was turned.
 * - Want to read, paused and did not finish are only ever chosen, so they
 *   go out only as choices. A book that is merely unread here says
 *   nothing, and is never taken off the Hardcover shelf.
 * - Progress never goes backwards, and a book not finished never reports
 *   its last page, which would finish the read on the server.
 * - A rating is cleared on Hardcover only when this device set it there.
 */
object HardcoverPlan {

    fun targetStatus(local: LocalReading): Int? = when (local.status) {
        ReadingStatus.READING -> HardcoverStatus.CURRENTLY_READING
        ReadingStatus.FINISHED -> HardcoverStatus.READ
        ReadingStatus.WANT_TO_READ -> HardcoverStatus.WANT_TO_READ.takeIf { local.chosen }
        ReadingStatus.PAUSED -> HardcoverStatus.PAUSED.takeIf { local.chosen }
        ReadingStatus.DID_NOT_FINISH -> HardcoverStatus.DID_NOT_FINISH.takeIf { local.chosen }
        ReadingStatus.READY_TO_READ -> null
    }

    /**
     * The entry write, or null when the entry already says it.
     * [pushedRating] is the rating this device last sent, if any, and
     * [chosenEdition] the edition the reader picked here and not yet sent.
     */
    fun entryStep(
        remote: HardcoverShelfState,
        local: LocalReading,
        pushedRating: Double?,
        chosenEdition: Long? = null,
    ): HardcoverEntryStep? {
        val target = targetStatus(local)
        if (remote.userBookId == null) {
            return target?.let { HardcoverEntryStep.Add(it, local.rating) }
        }
        val status = target?.takeIf { it != remote.statusId && mayMove(remote.statusId, it, local.chosen) }
        val rating = local.rating?.takeIf { it != remote.rating }
        val clearRating = local.rating == null && remote.rating != null && pushedRating != null &&
            remote.rating == pushedRating
        val edition = chosenEdition?.takeIf { it != remote.editionId }
        if (status == null && rating == null && !clearRating && edition == null) return null
        return HardcoverEntryStep.Update(status, rating, clearRating, edition)
    }

    private fun mayMove(from: Int?, to: Int, chosen: Boolean): Boolean = when (from) {
        HardcoverStatus.READ -> false
        HardcoverStatus.PAUSED, HardcoverStatus.DID_NOT_FINISH -> chosen
        else -> true
    }

    /** The status the entry has once [step] went through. */
    fun statusAfter(remote: HardcoverShelfState, step: HardcoverEntryStep?): Int? = when (step) {
        is HardcoverEntryStep.Add -> step.statusId
        is HardcoverEntryStep.Update -> step.statusId ?: remote.statusId
        null -> remote.statusId
    }

    /**
     * The read write, given the read as Hardcover has it after the status
     * went out. [justFinished] is true when this push set the book read,
     * so the finish date Hardcover just stamped (today) is replaced by the
     * real one; a book read before keeps whatever date it has, unless it
     * has none.
     *
     * Pages count in the read's own edition. A read on another edition
     * than [editionId] moves to it only when [editionChosen] says the
     * reader picked that edition here; its pages then restart in it.
     */
    fun readStep(
        status: Int?,
        read: HardcoverRead?,
        local: LocalReading,
        editionPages: Int?,
        justFinished: Boolean,
        editionId: Long? = null,
        editionChosen: Boolean = false,
    ): HardcoverReadStep? {
        if (read == null) return null
        val moveTo = editionId?.takeIf { editionChosen && it != read.editionId }
        // A paused or abandoned read still moves to the edition picked for it.
        if (status != HardcoverStatus.CURRENTLY_READING && status != HardcoverStatus.READ) {
            return moveTo?.let { HardcoverReadStep(editionId = it) }
        }
        val pageCount = if (moveTo != null || read.editionId == null || read.editionId == editionId) {
            editionPages
        } else {
            read.editionPages
        }
        val reading = status == HardcoverStatus.CURRENTLY_READING && read.finishedAt == null
        val pages = if (reading) {
            pagesFor(local.progression ?: 0.0, pageCount, finished = false)
                ?.takeIf { moveTo != null || it > (read.pages ?: 0) }
        } else null
        // Hardcover dates a read it starts today; the first session here is
        // when it really began. An older read keeps its own start.
        val finishedHere = status == HardcoverStatus.READ && local.status == ReadingStatus.FINISHED
        val started = local.startedOn?.takeIf { day ->
            (reading || justFinished || (finishedHere && read.startedAt == null)) &&
                (read.startedAt == null || read.startedAt > day) &&
                (justFinished || read.finishedAt == null || day <= read.finishedAt)
        }
        val finished = if (finishedHere) {
            local.finishedOn?.takeIf { day ->
                if (justFinished) day != read.finishedAt else read.finishedAt == null
            }
        } else null
        if (pages == null && started == null && finished == null && moveTo == null) return null
        return HardcoverReadStep(pages, started, finished, moveTo)
    }

    /**
     * The page a share of the book lands on in an edition of [editionPages],
     * or null without a usable count. A book that is not finished never
     * reports its last page, so rounding 99.6% up cannot finish the read.
     */
    fun pagesFor(progression: Double, editionPages: Int?, finished: Boolean): Int? {
        if (editionPages == null || editionPages <= 0) return null
        if (finished) return editionPages
        val share = progression.coerceIn(0.0, 1.0)
        return (share * editionPages).roundToInt().coerceIn(0, (editionPages - 1).coerceAtLeast(0))
    }

    /** Liseur keeps ratings as half stars 2..10; Hardcover as 1..5 in halves. */
    fun ratingFromLocal(value: String?): Double? =
        value?.toIntOrNull()?.takeIf { it in 2..10 }?.let { it / 2.0 }
}
