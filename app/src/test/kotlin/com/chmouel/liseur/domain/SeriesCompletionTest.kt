package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesCompletionTest {

    private fun book(
        title: String,
        index: Double,
        finished: Boolean = true,
        series: String = "The Expanse",
    ) = Book(
        url = "https://example.test/$title",
        title = title,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        downloadState = DownloadState.DOWNLOADED,
        finishedAt = if (finished) 1 else null,
        seriesName = series,
        seriesIndex = index,
    )

    private fun shelf(vararg books: Book) = listOf(*books).groupedIntoSeries().single()

    @Test
    fun `an unfinished volume means the series is in progress`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0, finished = false))
        assertEquals(SeriesCompletion.IN_PROGRESS, seriesCompletion(s, extras(status = "ENDED", total = 2)))
    }

    @Test
    fun `ended with an exact total and no gaps is complete`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(
            SeriesCompletion.COMPLETE,
            seriesCompletion(s, extras(status = "ENDED", total = 2)),
        )
    }

    @Test
    fun `ongoing with every known volume read is caught up`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(
            SeriesCompletion.CAUGHT_UP,
            seriesCompletion(s, extras(status = "ONGOING", total = 9)),
        )
    }

    @Test
    fun `hiatus with every known volume read is caught up`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(
            SeriesCompletion.CAUGHT_UP,
            seriesCompletion(s, extras(status = "HIATUS")),
        )
    }

    @Test
    fun `no server extras is only every book here`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(SeriesCompletion.ALL_KNOWN_READ, seriesCompletion(s, extras = null))
    }

    @Test
    fun `an abandoned series is only every book here`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(
            SeriesCompletion.ALL_KNOWN_READ,
            seriesCompletion(s, extras(status = "ABANDONED", total = 2)),
        )
    }

    @Test
    fun `ended with a mismatched total is not complete`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(
            SeriesCompletion.ALL_KNOWN_READ,
            seriesCompletion(s, extras(status = "ENDED", total = 14)),
        )
    }

    @Test
    fun `a gap in the shelf is not complete or caught up`() {
        val s = shelf(book("One", 1.0), book("Three", 3.0))
        assertEquals(
            SeriesCompletion.ALL_KNOWN_READ,
            seriesCompletion(s, extras(status = "ENDED", total = 2)),
        )
        assertEquals(
            SeriesCompletion.ALL_KNOWN_READ,
            seriesCompletion(s, extras(status = "ONGOING")),
        )
    }

    @Test
    fun `ended without a total stays conservative`() {
        val s = shelf(book("One", 1.0), book("Two", 2.0))
        assertEquals(
            SeriesCompletion.ALL_KNOWN_READ,
            seriesCompletion(s, extras(status = "ENDED")),
        )
    }

    private fun extras(status: String? = null, total: Int? = null) =
        SeriesExtras(status = status, totalBookCount = total)
}
