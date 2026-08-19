package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState

/** Why a bulk download stopped before it had fetched everything. */
enum class BulkStopReason(val id: String) {
    /** The reader asked for it to stop. */
    CANCELLED("cancelled"),

    /** The device ran out of room, or would have. */
    OUT_OF_SPACE("out_of_space"),

    /** The account was disconnected or swapped out from under the batch. */
    ACCOUNT_CHANGED("account_changed"),
    ;

    companion object {
        fun fromId(id: String?): BulkStopReason? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a bulk download run is, as far as anything outside WorkManager
 * needs to know.
 *
 * [total] is what was actually accepted by WorkManager, not what was
 * selected: `KEEP` silently discards a request whose unique name a tap
 * on a cover already claimed, and counting those would leave a batch
 * that could never reach its own denominator.
 *
 * [done] and [failed] are derived from the tagged work while the batch
 * is live and read from here once [settled] — WorkManager prunes
 * finished work, and a summary that vanishes with it is no summary.
 */
data class BulkBatch(
    val id: String,
    val total: Int,
    val settled: Boolean = false,
    val done: Int = 0,
    val failed: Int = 0,
    val stopReason: BulkStopReason? = null,
)

/** Whether a bulk download looks like it will fit. */
enum class SpaceVerdict {
    /** Comfortably, with the reserve and then some left over. */
    FITS,

    /** It should fit, but leaves little behind. Worth saying so. */
    TIGHT,

    /** Not without filling the device. */
    WILL_NOT_FIT,

    /** No book reported a size, so there is nothing honest to say. */
    UNKNOWN,
}

/**
 * What "download everything" would cost, as far as anyone can tell
 * before starting.
 *
 * [bytes] is null exactly when [verdict] is [SpaceVerdict.UNKNOWN]. It
 * is an estimate built from what servers claim, so it is presented as
 * approximate and never treated as a promise.
 */
data class BulkDownloadEstimate(
    val count: Int,
    val bytes: Long?,
    val freeBytes: Long,
    val verdict: SpaceVerdict,
)

/**
 * How much room a bulk download is required to leave behind.
 *
 * `setRequiresStorageNotLow` already keeps WorkManager from starting on
 * a device Android considers low, but that is Android's threshold and
 * it will not stop a run in progress. This one is the reader's, and the
 * same number is used by the estimate below and by the workers as they
 * write, so the preflight and the run cannot disagree.
 */
const val BULK_DOWNLOAD_RESERVE_BYTES: Long = 256L * 1024 * 1024

/**
 * The largest single book size worth believing, at 4 GiB.
 *
 * Sizes come off a catalog feed, which is input from outside the app.
 * A mis-parsed or hostile figure that sails through would either scare
 * the reader off a download that would have fitted, or poison the
 * median for every book that reported nothing.
 */
private const val MAX_PLAUSIBLE_BOOK_BYTES: Long = 4L * 1024 * 1024 * 1024

/**
 * The books a "download all" run should fetch.
 *
 * Four things have to hold, and the last two are the interesting ones.
 * A book with no `remoteUuid` has nowhere on disk to land, and one with
 * no `downloadHref` cannot be fetched at all — liseur-sync sets it null
 * for books its watched folders no longer hold. Enqueueing either
 * schedules work that is certain to fail, so they are excluded here
 * rather than discovered one failure at a time.
 */
fun booksToDownload(books: List<Book>): List<Book> = books.filter { book ->
    (book.downloadState == DownloadState.REMOTE || book.downloadState == DownloadState.FAILED) &&
        !book.archived &&
        book.remoteUuid != null &&
        !book.downloadHref.isNullOrBlank()
}

/**
 * Prices a bulk download against the room actually left on the volume
 * the books are written to.
 *
 * Books whose size the server did not report — or reported
 * implausibly — are charged the median of the ones it did, which is
 * steadier than the mean when one outsized volume sits in a shelf of
 * novels. If nothing reported a size at all there is no median to
 * borrow, and the answer is [SpaceVerdict.UNKNOWN] rather than a number
 * with nothing behind it.
 */
fun estimateBulkDownload(sizes: List<Long?>, freeBytes: Long): BulkDownloadEstimate {
    val known = sizes.filter { it != null && it in 1..MAX_PLAUSIBLE_BOOK_BYTES }
        .map { it as Long }
        .sorted()
    if (known.isEmpty()) {
        return BulkDownloadEstimate(
            count = sizes.size,
            bytes = null,
            freeBytes = freeBytes,
            verdict = SpaceVerdict.UNKNOWN,
        )
    }
    val median = known[known.size / 2]
    // Saturating rather than wrapping: a total that overflowed into a
    // negative would read as "plenty of room" at exactly the moment
    // there is none.
    var total = 0L
    sizes.forEach { size ->
        val charge = size?.takeIf { it in 1..MAX_PLAUSIBLE_BOOK_BYTES } ?: median
        total = if (total > Long.MAX_VALUE - charge) Long.MAX_VALUE else total + charge
    }
    val required = if (total > Long.MAX_VALUE - BULK_DOWNLOAD_RESERVE_BYTES) {
        Long.MAX_VALUE
    } else {
        total + BULK_DOWNLOAD_RESERVE_BYTES
    }
    val verdict = when {
        required > freeBytes -> SpaceVerdict.WILL_NOT_FIT
        // It fits, but not by much. Worth a different word, because a
        // batch that lands with 300 MB to spare is one photo album away
        // from the reader wondering where their storage went.
        required + BULK_DOWNLOAD_RESERVE_BYTES > freeBytes -> SpaceVerdict.TIGHT
        else -> SpaceVerdict.FITS
    }
    return BulkDownloadEstimate(
        count = sizes.size,
        bytes = total,
        freeBytes = freeBytes,
        verdict = verdict,
    )
}
