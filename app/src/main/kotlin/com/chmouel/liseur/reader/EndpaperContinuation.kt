package com.chmouel.liseur.reader

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.domain.SeriesCompletion
import com.chmouel.liseur.domain.SeriesExtras
import com.chmouel.liseur.domain.groupedIntoSeries
import com.chmouel.liseur.domain.seriesCompletion
import com.chmouel.liseur.domain.seriesContinuation
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.domain.seriesKey

/**
 * Whether the next volume can be opened, fetched, or only named.
 *
 * The file being here is one state among several, not a precondition
 * for making the offer. A download that cannot succeed is named as
 * unavailable rather than queued.
 */
sealed interface NextVolumeAvailability {
    data class Ready(val fileUrl: String) : NextVolumeAvailability
    data object Remote : NextVolumeAvailability
    data object Queued : NextVolumeAvailability
    data class Downloading(val fraction: Float?) : NextVolumeAvailability
    data object Failed : NextVolumeAvailability
    data object Unavailable : NextVolumeAvailability
}

/** A WorkManager snapshot, without dragging the repository into tests. */
data class DownloadSnapshot(
    val queued: Boolean = false,
    val fraction: Float? = null,
    val running: Boolean = false,
)

data class NextUp(
    val book: Book,
    val volume: String?,
    val availability: NextVolumeAvailability,
) {
    val id: String get() = book.url
    val title: String get() = book.title
}

/**
 * What the endpaper can say once the last page has been turned.
 *
 * [seriesName] and [finishedVolume] name the book that just ended, so
 * the colophon can say which volume of the series this was.
 * [missingIndex] is the volume that should come next and is not in the
 * library, when that can be proved. [next] is a later volume that is
 * here and can be opened, even across that hole. [noNextInLibrary] is
 * the conservative sentence when neither can be said and the series is
 * still in progress. [seriesCompletion] is how the series itself reads
 * when there is no next book and no named missing volume.
 */
data class EndpaperContinuation(
    val next: NextUp?,
    val seriesCompletion: SeriesCompletion,
    val seriesName: String? = null,
    val finishedVolume: String? = null,
    val missingIndex: Double? = null,
    val noNextInLibrary: Boolean = false,
)

internal fun shouldOfferEndpaperContinuation(
    finished: Boolean,
    endpaperReached: Boolean,
): Boolean = finished && endpaperReached

internal fun nextVolumeAvailability(
    openableUrl: String?,
    downloadState: DownloadState,
    download: DownloadSnapshot?,
    canDownload: Boolean,
): NextVolumeAvailability {
    openableUrl?.let { return NextVolumeAvailability.Ready(it) }
    if (download?.queued == true || downloadState == DownloadState.QUEUED) {
        return NextVolumeAvailability.Queued
    }
    if (download?.running == true || downloadState == DownloadState.DOWNLOADING) {
        return NextVolumeAvailability.Downloading(download?.fraction)
    }
    if (downloadState == DownloadState.FAILED) {
        return if (canDownload) NextVolumeAvailability.Failed else NextVolumeAvailability.Unavailable
    }
    if (!canDownload) return NextVolumeAvailability.Unavailable
    return NextVolumeAvailability.Remote
}

internal fun endpaperContinuation(
    current: Book,
    library: List<Book>,
    progressions: Map<String, Double>,
    dismissed: Boolean,
    endpaperReached: Boolean,
    downloads: Map<String, DownloadSnapshot>,
    canDownload: Boolean,
    extras: SeriesExtras?,
): EndpaperContinuation? {
    if (!shouldOfferEndpaperContinuation(current.finished, endpaperReached)) return null
    val seriesName = current.seriesName?.takeIf { it.isNotBlank() }
    val finishedVolume = seriesName?.let { seriesIndexLabel(current.seriesIndex) }
    val shelf = library.groupedIntoSeries(progressions).firstOrNull {
        seriesKey(it.name) == seriesKey(current.seriesName)
    }
    val completion = shelf?.let { seriesCompletion(it, extras) } ?: SeriesCompletion.IN_PROGRESS
    if (dismissed) {
        return EndpaperContinuation(
            next = null,
            seriesCompletion = SeriesCompletion.IN_PROGRESS,
            seriesName = seriesName,
            finishedVolume = finishedVolume,
        )
    }
    val offer = seriesContinuation(current, library, extras)
    val nextUp = offer.next?.let { nextBook ->
        val snapshot = downloads[nextBook.url]
        NextUp(
            book = nextBook,
            volume = seriesIndexLabel(nextBook.seriesIndex),
            availability = nextVolumeAvailability(
                openableUrl = nextBook.openableUrl,
                downloadState = nextBook.downloadState,
                download = snapshot,
                canDownload = canDownload,
            ),
        )
    }
    val hasOffer = nextUp != null || offer.missingIndex != null
    return EndpaperContinuation(
        next = nextUp,
        seriesName = seriesName,
        finishedVolume = finishedVolume,
        missingIndex = offer.missingIndex,
        noNextInLibrary = !hasOffer && completion == SeriesCompletion.IN_PROGRESS,
        seriesCompletion = if (hasOffer) SeriesCompletion.IN_PROGRESS else completion,
    )
}

internal fun readyToOpen(
    continuation: EndpaperContinuation?,
    continueAfterDownload: Boolean,
): NextUp? {
    if (!continueAfterDownload) return null
    val next = continuation?.next ?: return null
    return next.takeIf { it.availability is NextVolumeAvailability.Ready }
}
