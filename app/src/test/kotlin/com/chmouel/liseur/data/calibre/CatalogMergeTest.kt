package com.chmouel.liseur.data.calibre

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

    private val entry = OpdsBook(
        uuid = "abc-123",
        bookId = 42,
        title = "Moby Dick",
        author = "Herman Melville",
        coverHref = "/opds/cover/42",
        downloadHref = "/opds/download/42/epub",
        sizeBytes = 1_000_000,
        updatedAt = 2_000L,
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
}
