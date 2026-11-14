package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.LibraryLayout
import com.chmouel.liseur.domain.LibraryStyle
import com.chmouel.liseur.domain.ShelfGrouping
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelTest {
    private fun book(
        finished: Boolean = false,
        pageCount: Int? = null,
    ) = Book(
        url = "book",
        title = "A book",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        finishedAt = if (finished) 1L else null,
        remotePageCount = pageCount,
    )

    @Test
    fun `mark unread is offered for finished or started books not already overridden unread`() {
        assertTrue(canMarkBookUnread(book(finished = true), progression = null))
        assertTrue(canMarkBookUnread(book(), progression = 0.4, override = FinishedOverride.NONE))
        assertFalse(canMarkBookUnread(book(), progression = 0.4, override = FinishedOverride.UNREAD))
        assertFalse(canMarkBookUnread(book(), progression = null, override = FinishedOverride.NONE))
        assertFalse(canMarkBookUnread(book(), progression = 0.001, override = FinishedOverride.NONE))
    }

    @Test
    fun `the continue reading card is gone once the book is marked unread`() {
        val started = book()
        assertEquals(0.4, continueReadingFor(started, 0.4, FinishedOverride.NONE)?.progression)
        assertNull(continueReadingFor(started, 0.4, FinishedOverride.UNREAD))
        assertNull(continueReadingFor(started, 0.001, FinishedOverride.NONE))
        // No row yet: the card it had while the row loads is the card it keeps.
        assertNotNull(continueReadingFor(started, null, null))
    }

    @Test
    fun `continue reading falls back to the next started book when the most recent is unread`() {
        val peeked = book().copy(id = 1L, url = "file:///peeked.epub", title = "Peeked", lastOpenedAt = 200L)
        val active = book().copy(id = 2L, url = "file:///active.epub", title = "Active", lastOpenedAt = 100L)
        val untouched = book().copy(id = 3L, url = "file:///untouched.epub", title = "Untouched", lastOpenedAt = null)

        val current = continueReadingFrom(
            books = listOf(peeked, active, untouched),
            progressions = mapOf(peeked.url to 0.001, active.url to 0.35),
            overrides = mapOf(peeked.url to FinishedOverride.NONE, active.url to FinishedOverride.NONE),
            readAt = emptyMap(),
        )

        assertEquals("Active", current?.book?.title)
        assertEquals(0.35, current?.progression)
    }

    @Test
    fun `selected account clear failure leaves removal undone and reports the failure`() =
        runTest {
            var removed = false
            var reported = false
            val result =
                removeAfterAccountClear(
                    clearSelected = true,
                    recordClear = { false },
                    onClearFailure = { reported = true },
                    remove = { removed = true },
                )

            assertEquals(AccountClearRemovalResult.Failed, result)
            assertTrue(reported)
            assertFalse(removed)
        }

    @Test
    fun `a persistence exception leaves removal undone and reports its cause`() =
        runTest {
            val cause = IllegalStateException("disk unavailable")
            var reported: Exception? = null
            var removed = false
            val result =
                removeAfterAccountClear(
                    clearSelected = true,
                    recordClear = { throw cause },
                    onClearFailure = { reported = it },
                    remove = { removed = true },
                )

            assertEquals(AccountClearRemovalResult.Failed, result)
            assertSame(cause, reported)
            assertFalse(removed)
        }

    @Test
    fun `recording a selected clear completes before removal`() =
        runTest {
            val events = mutableListOf<String>()
            val result =
                removeAfterAccountClear(
                    clearSelected = true,
                    recordClear = {
                        events += "record"
                        true
                    },
                    onClearFailure = { error("Unexpected clear failure: $it") },
                    remove = { events += "remove" },
                )

            assertEquals(AccountClearRemovalResult.Recorded, result)
            assertEquals(listOf("record", "remove"), events)
        }

    @Test
    fun `an unselected account clear does not block removal`() =
        runTest {
            var recordCalls = 0
            var removed = false
            val result =
                removeAfterAccountClear(
                    clearSelected = false,
                    recordClear = {
                        recordCalls++
                        false
                    },
                    onClearFailure = { error("No clear was requested") },
                    remove = { removed = true },
                )

            assertEquals(AccountClearRemovalResult.NotRequested, result)
            assertEquals(0, recordCalls)
            assertTrue(removed)
        }

    @Test
    fun theSeriesTabShowsSeriesShelvesWithoutTouchingTheSavedLayout() {
        val byAuthor = LibraryLayout(style = LibraryStyle.SHELVES, shelfGrouping = ShelfGrouping.AUTHOR)
        val onSeries = LibraryUiState(layout = byAuthor, seriesView = true)
        // Anything written back from the screen's layout keeps the reader's grouping.
        assertEquals(ShelfGrouping.AUTHOR, onSeries.layout.shelfGrouping)
        assertEquals(ShelfGrouping.SERIES, onSeries.shownShelfGrouping)
        assertEquals(ShelfGrouping.AUTHOR, onSeries.copy(seriesView = false).shownShelfGrouping)
    }
}
