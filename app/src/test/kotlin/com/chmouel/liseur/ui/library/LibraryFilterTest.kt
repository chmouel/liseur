package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which books each view of the library shows.
 *
 * The rule that matters is that an archived book is out of every view but
 * its own. Get that wrong and putting a book away does nothing useful:
 * it still turns up in Unread, which is exactly where the reader was
 * trying to stop meeting it.
 */
class LibraryFilterTest {

    private fun book(
        archived: Boolean = false,
        finished: Boolean = false,
        downloaded: Boolean = true,
    ) = Book(
        url = "book",
        title = "A book",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        downloadState = if (downloaded) DownloadState.DOWNLOADED else DownloadState.REMOTE,
        finishedAt = if (finished) 1 else null,
        archivedAt = if (archived) 1 else null,
    )

    @Test
    fun `a book on the shelf is in every view but the archive`() {
        val on = book()
        assertTrue(LibraryFilter.ALL.accepts(on))
        assertTrue(LibraryFilter.DOWNLOADED.accepts(on))
        assertTrue(LibraryFilter.UNREAD.accepts(on))
        assertFalse(LibraryFilter.ARCHIVED.accepts(on))
    }

    @Test
    fun `an archived book is in no view but the archive`() {
        val away = book(archived = true)
        assertFalse(LibraryFilter.ALL.accepts(away))
        assertFalse(LibraryFilter.DOWNLOADED.accepts(away))
        assertFalse(LibraryFilter.UNREAD.accepts(away))
        assertTrue(LibraryFilter.ARCHIVED.accepts(away))
    }

    @Test
    fun `putting away an unread book takes it out of Unread`() {
        assertTrue(LibraryFilter.UNREAD.accepts(book(finished = false)))
        assertFalse(LibraryFilter.UNREAD.accepts(book(finished = false, archived = true)))
    }

    @Test
    fun `an archived book is still in the archive once it is read`() {
        assertTrue(LibraryFilter.ARCHIVED.accepts(book(archived = true, finished = true)))
    }

    @Test
    fun `a read book is off Unread and still on the shelf`() {
        val read = book(finished = true)
        assertTrue(LibraryFilter.ALL.accepts(read))
        assertFalse(LibraryFilter.UNREAD.accepts(read))
    }

    @Test
    fun `a book not yet on the device is off Downloaded`() {
        assertFalse(LibraryFilter.DOWNLOADED.accepts(book(downloaded = false)))
    }
}
