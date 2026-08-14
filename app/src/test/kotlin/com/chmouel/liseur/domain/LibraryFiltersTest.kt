package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which books a set of filters lets through.
 *
 * Two rules carry the feature. An archived book is out of every view but
 * its own: get that wrong and putting a book away does nothing useful,
 * because it still turns up in Unread, which is exactly where the reader
 * was trying to stop meeting it. And options on one axis are *or* while
 * axes are *and*: get that wrong and ticking two boxes empties the
 * shelf, which is the failure the chips were replaced to avoid.
 */
class LibraryFiltersTest {

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

    private fun filters(vararg options: LibraryFilterOption) =
        LibraryFilters(options = options.toSet())

    @Test
    fun `nothing ticked shows the whole shelf`() {
        assertTrue(LibraryFilters.None.accepts(book()))
        assertTrue(LibraryFilters.None.accepts(book(finished = true)))
        assertTrue(LibraryFilters.None.accepts(book(downloaded = false)))
    }

    @Test
    fun `an archived book is in no view but the archive`() {
        val away = book(archived = true)
        assertFalse(LibraryFilters.None.accepts(away))
        assertFalse(filters(LibraryFilterOption.DOWNLOADED).accepts(away))
        assertFalse(filters(LibraryFilterOption.UNREAD).accepts(away))
        assertTrue(filters(LibraryFilterOption.ARCHIVED).accepts(away))
    }

    @Test
    fun `the archive holds nothing but archived books`() {
        assertFalse(filters(LibraryFilterOption.ARCHIVED).accepts(book()))
        assertTrue(
            filters(LibraryFilterOption.ARCHIVED).accepts(book(archived = true, finished = true)),
        )
    }

    @Test
    fun `the archive can be narrowed like the shelf`() {
        val away = book(archived = true, finished = true)
        assertTrue(
            filters(LibraryFilterOption.ARCHIVED, LibraryFilterOption.FINISHED).accepts(away),
        )
        assertFalse(
            filters(LibraryFilterOption.ARCHIVED, LibraryFilterOption.UNREAD).accepts(away),
        )
    }

    @Test
    fun `reading state is a three way split`() {
        val unread = book()
        val started = book()
        val done = book(finished = true)

        assertTrue(filters(LibraryFilterOption.UNREAD).accepts(unread, progression = null))
        assertFalse(filters(LibraryFilterOption.UNREAD).accepts(started, progression = 0.4))
        assertTrue(filters(LibraryFilterOption.IN_PROGRESS).accepts(started, progression = 0.4))
        assertFalse(filters(LibraryFilterOption.IN_PROGRESS).accepts(unread))
        assertTrue(filters(LibraryFilterOption.FINISHED).accepts(done))
        assertFalse(filters(LibraryFilterOption.FINISHED).accepts(started, progression = 0.4))
    }

    @Test
    fun `opening a book is not reading it`() {
        // A locator is written the moment a book is opened, so a glance
        // must not move it out of Unread.
        assertTrue(filters(LibraryFilterOption.UNREAD).accepts(book(), progression = 0.001))
        assertFalse(filters(LibraryFilterOption.IN_PROGRESS).accepts(book(), progression = 0.001))
    }

    @Test
    fun `a finished book is finished however far in the locator sits`() {
        val done = book(finished = true)
        assertTrue(filters(LibraryFilterOption.FINISHED).accepts(done, progression = 0.3))
        assertFalse(filters(LibraryFilterOption.IN_PROGRESS).accepts(done, progression = 0.3))
    }

    @Test
    fun `two options on one axis mean either`() {
        val both = filters(LibraryFilterOption.DOWNLOADED, LibraryFilterOption.NOT_DOWNLOADED)
        assertTrue(both.accepts(book(downloaded = true)))
        assertTrue(both.accepts(book(downloaded = false)))
    }

    @Test
    fun `options on different axes mean both`() {
        val downloadedAndUnread =
            filters(LibraryFilterOption.DOWNLOADED, LibraryFilterOption.UNREAD)
        assertTrue(downloadedAndUnread.accepts(book(downloaded = true)))
        assertFalse(downloadedAndUnread.accepts(book(downloaded = false)))
        assertFalse(downloadedAndUnread.accepts(book(downloaded = true, finished = true)))
    }

    @Test
    fun `a book not yet on the device is off Downloaded`() {
        assertFalse(filters(LibraryFilterOption.DOWNLOADED).accepts(book(downloaded = false)))
        assertTrue(filters(LibraryFilterOption.NOT_DOWNLOADED).accepts(book(downloaded = false)))
    }

    @Test
    fun `toggling puts an option in and takes it out again`() {
        val on = LibraryFilters.None.toggle(LibraryFilterOption.UNREAD)
        assertEquals(setOf(LibraryFilterOption.UNREAD), on.options)
        assertEquals(emptySet<LibraryFilterOption>(), on.toggle(LibraryFilterOption.UNREAD).options)
    }

    @Test
    fun `a selection survives being written down`() {
        val chosen = filters(LibraryFilterOption.FINISHED, LibraryFilterOption.DOWNLOADED)
        assertEquals(chosen.options, LibraryFilters.parse(chosen.serialise()))
    }

    @Test
    fun `the same selection always writes the same string`() {
        val one = filters(LibraryFilterOption.FINISHED, LibraryFilterOption.DOWNLOADED)
        val other = filters(LibraryFilterOption.DOWNLOADED, LibraryFilterOption.FINISHED)
        assertEquals(one.serialise(), other.serialise())
    }

    @Test
    fun `an unknown option is dropped rather than thrown`() {
        // A filter written by a newer version has to degrade into a
        // wider shelf, never into a crash.
        assertEquals(
            setOf(LibraryFilterOption.UNREAD),
            LibraryFilters.parse("unread,borrowed,"),
        )
        assertEquals(emptySet<LibraryFilterOption>(), LibraryFilters.parse(null))
    }
}
