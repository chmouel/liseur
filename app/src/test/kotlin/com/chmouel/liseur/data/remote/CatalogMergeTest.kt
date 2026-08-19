package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a catalog refresh is allowed to change.
 *
 * The catalog knows a book's title, author, cover and where to download
 * it. It knows nothing about this phone, so a refresh must leave the rest
 * of the row exactly as it found it.
 */
class CatalogMergeTest {

    private val entry = RemoteBook(
        remoteId = "abc-123",
        title = "Moby Dick",
        author = "Herman Melville",
        coverHref = "/opds/cover/42",
        downloadHref = "/opds/download/42/epub",
        sizeBytes = 1_000_000,
        updatedAt = 2_000L,
        calibreBookId = 42,
    )

    private val downloadedAndFinished = Book(
        id = 7,
        url = "calibre:abc-123",
        title = "Moby Dick",
        author = "Herman Melville",
        coverPath = "/data/covers/abc.jpg",
        source = null,
        addedAt = 100L,
        lastOpenedAt = 900L,
        localUri = "file:///data/books/abc-123.epub",
        remoteUuid = "abc-123",
        remoteBookId = 42,
        downloadState = DownloadState.DOWNLOADED,
        downloadedAt = 500L,
        fileModifiedAt = 400L,
        finishedAt = 800L,
    )

    @Test
    fun `a refresh does not make a finished book unread`() {
        val merged = mergeCatalogEntry(
            entry,
            downloadedAndFinished,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals(800L, merged.finishedAt)
        assertEquals(500L, merged.downloadedAt)
        assertEquals(400L, merged.fileModifiedAt)
    }

    @Test
    fun `a refresh keeps the book on the device`() {
        val merged = mergeCatalogEntry(
            entry,
            downloadedAndFinished,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals(DownloadState.DOWNLOADED, merged.downloadState)
        assertEquals("file:///data/books/abc-123.epub", merged.localUri)
        assertEquals("/data/covers/abc.jpg", merged.coverPath)
        assertEquals(7L, merged.id)
        assertEquals(100L, merged.addedAt)
        assertEquals(900L, merged.lastOpenedAt)
    }

    @Test
    fun `a refresh does take the catalog's own fields`() {
        val renamed = entry.copy(
            title = "Moby-Dick; or, The Whale",
            author = "Melville, Herman",
            updatedAt = 3_000L,
        )

        val merged = mergeCatalogEntry(
            renamed,
            downloadedAndFinished,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals("Moby-Dick; or, The Whale", merged.title)
        assertEquals("Melville, Herman", merged.author)
        assertEquals(3_000L, merged.remoteUpdatedAt)
        assertEquals("https://books.example.com/opds/cover/42", merged.coverUrl)
    }

    @Test
    fun `a book seen for the first time starts on the server`() {
        val merged = mergeCatalogEntry(
            entry,
            existing = null,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals(DownloadState.REMOTE, merged.downloadState)
        assertEquals(9_999L, merged.addedAt)
        assertEquals(0L, merged.id)
        assertNull(merged.finishedAt)
        assertNull(merged.localUri)
    }

    @Test
    fun `a refresh clears series metadata removed from the catalog`() {
        val previouslyCategorised = downloadedAndFinished.copy(
            seriesName = "The Old Series",
            seriesIndex = 4.0,
            seriesId = "old-series",
        )

        val merged = mergeCatalogEntry(
            entry,
            previouslyCategorised,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertNull(merged.seriesName)
        assertNull(merged.seriesIndex)
        assertNull(merged.seriesId)
    }

    @Test
    fun `a refresh falls back only to series metadata read from the file`() {
        val fromFile = downloadedAndFinished.copy(
            seriesName = "Stale Catalog Series",
            seriesIndex = 9.0,
            fileSeriesName = "The File Series",
            fileSeriesIndex = 2.0,
        )

        val merged = mergeCatalogEntry(
            entry,
            fromFile,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals("The File Series", merged.seriesName)
        assertEquals(2.0, merged.seriesIndex)
    }

    @Test
    fun `a refresh leaves a book the reader filed themselves alone`() {
        val refiled = downloadedAndFinished.copy(
            seriesName = "My Shelf",
            seriesIndex = 1.0,
            fileSeriesName = "The File Series",
            fileSeriesIndex = 2.0,
            userSeriesName = "My Shelf",
            userSeriesIndex = 1.0,
            seriesOverridden = true,
            indexOverridden = true,
        )

        val merged = mergeCatalogEntry(
            entry.copy(seriesName = "Catalog Series", seriesIndex = 8.0),
            refiled,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals("My Shelf", merged.seriesName)
        assertEquals(1.0, merged.seriesIndex)
        assertEquals(true, merged.seriesOverridden)
        // What the catalog says is still taken down, so undoing the
        // filing has the server's answer to go back to.
        assertEquals("Catalog Series", merged.catalogSeriesName)
        assertEquals(8.0, merged.catalogSeriesIndex)
    }

    @Test
    fun `a book filed by hand with no number keeps none through a refresh`() {
        // Volume 4 of one series filed into another is not volume 4 of
        // the one it was filed into, and the catalog's number is about
        // the series the book just left.
        val refiled = downloadedAndFinished.copy(
            seriesName = "My Shelf",
            seriesIndex = null,
            fileSeriesName = "The File Series",
            fileSeriesIndex = 4.0,
            userSeriesName = "My Shelf",
            userSeriesIndex = null,
            seriesOverridden = true,
            indexOverridden = true,
        )

        val merged = mergeCatalogEntry(
            entry.copy(seriesName = "Catalog Series", seriesIndex = 8.0),
            refiled,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals("My Shelf", merged.seriesName)
        assertEquals(null, merged.seriesIndex)
    }

    @Test
    fun `carries the size the server reported`() {
        // Nothing reads it until a bulk download is priced, and by then
        // the catalog walk is long over: dropping it here is dropping it
        // for good.
        val merged = mergeCatalogEntry(
            entry,
            null,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals(1_000_000L, merged.sizeBytes)
    }

    @Test
    fun `keeps a size a later page forgot to mention`() {
        // Only calibre-web's feed carries a length on every entry. A
        // refresh from a server that mentions it once should not make
        // the estimate worse each time it runs.
        val known = downloadedAndFinished.copy(sizeBytes = 1_000_000L)

        val merged = mergeCatalogEntry(
            entry.copy(sizeBytes = null),
            known,
            url = "calibre:abc-123",
            baseUrl = "https://books.example.com",
            now = 9_999L,
        )

        assertEquals(1_000_000L, merged.sizeBytes)
    }
}
