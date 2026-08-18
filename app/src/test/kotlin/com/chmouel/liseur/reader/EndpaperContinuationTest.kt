package com.chmouel.liseur.reader

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.domain.SeriesCompletion
import com.chmouel.liseur.domain.SeriesExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpaperContinuationTest {

    private fun book(
        title: String,
        index: Double,
        finished: Boolean = false,
        state: DownloadState = DownloadState.DOWNLOADED,
        localUri: String? = if (state == DownloadState.DOWNLOADED) "file://$title.epub" else null,
        series: String = "The Expanse",
    ) = Book(
        url = "calibre:$title",
        title = title,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        downloadState = state,
        localUri = localUri,
        finishedAt = if (finished) 1 else null,
        seriesName = series,
        seriesIndex = index,
    )

    @Test
    fun `continuation waits for the endpaper even when the book is finished`() {
        assertFalse(shouldOfferEndpaperContinuation(finished = true, endpaperReached = false))
        assertTrue(shouldOfferEndpaperContinuation(finished = true, endpaperReached = true))
        assertFalse(shouldOfferEndpaperContinuation(finished = false, endpaperReached = true))
    }

    @Test
    fun `a file on the device is ready to open`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0).copy(coverPath = "/covers/two.jpg")
        val offer = continuation(one, listOf(one, two))
        assertEquals(NextVolumeAvailability.Ready("file://Two.epub"), offer?.next?.availability)
        assertEquals("Two", offer?.next?.title)
        assertEquals("/covers/two.jpg", offer?.next?.book?.coverPath)
        assertEquals(one, offer?.finished)
    }

    @Test
    fun `time is carried only after a full minute`() {
        val one = book("One", 1.0, finished = true)

        assertNull(continuation(one, listOf(one), timeSpentMs = 59_999)?.timeSpentMs)
        assertEquals(60_000L, continuation(one, listOf(one), timeSpentMs = 60_000)?.timeSpentMs)
    }

    @Test
    fun `an in-progress volume is offered`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0)
        val offer = continuation(
            one,
            listOf(one, two),
            progressions = mapOf(two.url to 0.4),
        )
        assertEquals("Two", offer?.next?.title)
    }

    @Test
    fun `a remote volume is offered as a download`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, state = DownloadState.REMOTE)
        val offer = continuation(one, listOf(one, two), canDownload = true)
        assertEquals(NextVolumeAvailability.Remote, offer?.next?.availability)
    }

    @Test
    fun `a queued download is waiting`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, state = DownloadState.QUEUED)
        val offer = continuation(
            one,
            listOf(one, two),
            downloads = mapOf(two.url to DownloadSnapshot(queued = true)),
        )
        assertEquals(NextVolumeAvailability.Queued, offer?.next?.availability)
    }

    @Test
    fun `a running download carries its fraction`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, state = DownloadState.DOWNLOADING)
        val offer = continuation(
            one,
            listOf(one, two),
            downloads = mapOf(two.url to DownloadSnapshot(running = true, fraction = 0.4f)),
        )
        assertEquals(NextVolumeAvailability.Downloading(0.4f), offer?.next?.availability)
    }

    @Test
    fun `a failed download can be retried`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, state = DownloadState.FAILED)
        val offer = continuation(one, listOf(one, two), canDownload = true)
        assertEquals(NextVolumeAvailability.Failed, offer?.next?.availability)
    }

    @Test
    fun `a forbidden download is named rather than queued`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, state = DownloadState.REMOTE)
        val offer = continuation(one, listOf(one, two), canDownload = false)
        assertEquals(NextVolumeAvailability.Unavailable, offer?.next?.availability)
    }

    @Test
    fun `a failed download without permission is unavailable rather than retried`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, state = DownloadState.FAILED)
        val offer = continuation(one, listOf(one, two), canDownload = false)
        assertEquals(NextVolumeAvailability.Unavailable, offer?.next?.availability)
    }

    @Test
    fun `leaving the endpaper withdraws the offer`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0)
        assertNull(continuation(one, listOf(one, two), endpaperReached = false))
    }

    @Test
    fun `dismissing hides the next volume without claiming the series is done`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0)
        val offer = continuation(one, listOf(one, two), dismissed = true)
        assertNull(offer?.next)
        assertEquals(SeriesCompletion.IN_PROGRESS, offer?.seriesCompletion)
        assertEquals("The Expanse", offer?.seriesName)
        assertEquals("1", offer?.finishedVolume)
    }

    @Test
    fun `a series book names the volume that was just finished`() {
        val one = book("One", 1.0, finished = true)
        val three = book("Three", 3.0)
        val offer = continuation(one, listOf(one, three))
        assertEquals("The Expanse", offer?.seriesName)
        assertEquals("1", offer?.finishedVolume)
    }

    @Test
    fun `a fractional volume keeps its place in the series`() {
        val novella = book("Novella", 1.5, finished = true)
        val two = book("Two", 2.0)
        val offer = continuation(novella, listOf(novella, two))
        assertEquals("1.5", offer?.finishedVolume)
    }

    @Test
    fun `a book with no series does not invent a volume number`() {
        val standalone = book("Standalone", 1.0, finished = true, series = "")
            .copy(seriesName = null, seriesIndex = null)
        val offer = continuation(standalone, listOf(standalone))
        assertNull(offer?.seriesName)
        assertNull(offer?.finishedVolume)
    }

    @Test
    fun `the last volume of an ended series is complete`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, finished = true)
        val offer = continuation(
            two,
            listOf(one, two),
            extras = SeriesExtras(status = "ENDED", totalBookCount = 2),
        )
        assertNull(offer?.next)
        assertNull(offer?.missingIndex)
        assertFalse(offer?.noNextInLibrary == true)
        assertEquals(SeriesCompletion.COMPLETE, offer?.seriesCompletion)
    }

    @Test
    fun `an ongoing series with every known volume read is caught up`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0, finished = true)
        val offer = continuation(
            two,
            listOf(one, two),
            extras = SeriesExtras(status = "ONGOING", totalBookCount = 2),
        )
        assertNull(offer?.next)
        assertNull(offer?.missingIndex)
        assertEquals(SeriesCompletion.CAUGHT_UP, offer?.seriesCompletion)
    }

    @Test
    fun `a missing volume is named and a later book is still offered`() {
        val one = book("One", 1.0, finished = true)
        val three = book("Three", 3.0)
        val offer = continuation(one, listOf(one, three))
        assertEquals("1", offer?.finishedVolume)
        assertEquals(2.0, offer?.missingIndex)
        assertEquals("Three", offer?.next?.title)
        assertEquals(SeriesCompletion.IN_PROGRESS, offer?.seriesCompletion)
    }

    @Test
    fun `the immediate next volume is offered without a missing notice`() {
        val one = book("One", 1.0, finished = true)
        val two = book("Two", 2.0)
        val offer = continuation(one, listOf(one, two))
        assertNull(offer?.missingIndex)
        assertEquals("Two", offer?.next?.title)
    }

    @Test
    fun `a novella does not look like a missing volume`() {
        val one = book("One", 1.0, finished = true)
        val novella = book("Novella", 1.5)
        val offer = continuation(one, listOf(one, novella))
        assertNull(offer?.missingIndex)
        assertEquals("Novella", offer?.next?.title)
    }

    @Test
    fun `several missing volumes name the immediate hole and offer the first later book`() {
        val one = book("One", 1.0, finished = true)
        val four = book("Four", 4.0)
        val five = book("Five", 5.0)
        val offer = continuation(one, listOf(one, four, five))
        assertEquals(2.0, offer?.missingIndex)
        assertEquals("Four", offer?.next?.title)
    }

    @Test
    fun `an authoritative total names a missing volume without a later book`() {
        val one = book("One", 1.0, finished = true)
        val offer = continuation(
            one,
            listOf(one),
            extras = SeriesExtras(status = "ENDED", totalBookCount = 5),
        )
        assertEquals(2.0, offer?.missingIndex)
        assertNull(offer?.next)
        assertEquals(SeriesCompletion.IN_PROGRESS, offer?.seriesCompletion)
    }

    @Test
    fun `without a total the missing number is not invented`() {
        val one = book("One", 1.0, finished = true)
        val offer = continuation(one, listOf(one))
        assertNull(offer?.missingIndex)
        assertNull(offer?.next)
        assertFalse(offer?.noNextInLibrary == true)
        assertEquals(SeriesCompletion.ALL_KNOWN_READ, offer?.seriesCompletion)
    }

    @Test
    fun `an unfinished unnumbered companion is a series still in the library`() {
        val one = book("One", 1.0, finished = true)
        val companion = Book(
            url = "calibre:Companion",
            title = "Companion",
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
            downloadState = DownloadState.DOWNLOADED,
            localUri = "file://Companion.epub",
            seriesName = "The Expanse",
            seriesIndex = null,
        )
        val offer = continuation(one, listOf(one, companion))
        assertNull(offer?.next)
        assertNull(offer?.missingIndex)
        assertTrue(offer?.noNextInLibrary == true)
        assertEquals(SeriesCompletion.IN_PROGRESS, offer?.seriesCompletion)
    }

    @Test
    fun `leaving the endpaper cancels auto-open even after the file arrives`() {
        val two = NextUp(
            book = book("Two", 2.0),
            volume = "2",
            availability = NextVolumeAvailability.Ready("file://Two.epub"),
        )
        assertNull(readyToOpen(EndpaperContinuation(two, SeriesCompletion.IN_PROGRESS), false))
        assertEquals(two, readyToOpen(EndpaperContinuation(two, SeriesCompletion.IN_PROGRESS), true))
    }

    @Test
    fun `a download does not auto-open until the file is ready`() {
        val two = NextUp(
            book = book("Two", 2.0),
            volume = "2",
            availability = NextVolumeAvailability.Remote,
        )
        assertNull(readyToOpen(EndpaperContinuation(two, SeriesCompletion.IN_PROGRESS), true))
        val ready = two.copy(availability = NextVolumeAvailability.Ready("file://Two.epub"))
        assertEquals(ready, readyToOpen(EndpaperContinuation(ready, SeriesCompletion.IN_PROGRESS), true))
    }

    private fun continuation(
        current: Book,
        library: List<Book>,
        progressions: Map<String, Double> = emptyMap(),
        dismissed: Boolean = false,
        endpaperReached: Boolean = true,
        downloads: Map<String, DownloadSnapshot> = emptyMap(),
        canDownload: Boolean = true,
        extras: SeriesExtras? = null,
        timeSpentMs: Long = 0,
    ) = endpaperContinuation(
        current = current,
        library = library,
        progressions = progressions,
        dismissed = dismissed,
        endpaperReached = endpaperReached,
        downloads = downloads,
        canDownload = canDownload,
        extras = extras,
        timeSpentMs = timeSpentMs,
    )
}
