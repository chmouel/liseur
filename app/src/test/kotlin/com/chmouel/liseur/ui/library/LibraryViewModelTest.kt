package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelTest {
    private fun book(finished: Boolean = false, pageCount: Int? = null) = Book(
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
        assertTrue(canMarkBookUnread(book(), progression = 0.4, unreadOverride = false))
        assertFalse(canMarkBookUnread(book(), progression = 0.4, unreadOverride = true))
        assertFalse(canMarkBookUnread(book(), progression = null, unreadOverride = false))
        assertFalse(canMarkBookUnread(book(), progression = 0.001, unreadOverride = false))
    }

    @Test
    fun `selected account clear failure leaves removal undone and reports the failure`() = runTest {
        var removed = false
        var reported = false
        val result = removeAfterAccountClear(
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
    fun `a persistence exception leaves removal undone and reports its cause`() = runTest {
        val cause = IllegalStateException("disk unavailable")
        var reported: Exception? = null
        var removed = false
        val result = removeAfterAccountClear(
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
    fun `recording a selected clear completes before removal`() = runTest {
        val events = mutableListOf<String>()
        val result = removeAfterAccountClear(
            clearSelected = true,
            recordClear = { events += "record"; true },
            onClearFailure = { error("Unexpected clear failure: $it") },
            remove = { events += "remove" },
        )

        assertEquals(AccountClearRemovalResult.Recorded, result)
        assertEquals(listOf("record", "remove"), events)
    }

    @Test
    fun `an unselected account clear does not block removal`() = runTest {
        var recordCalls = 0
        var removed = false
        val result = removeAfterAccountClear(
            clearSelected = false,
            recordClear = { recordCalls++; false },
            onClearFailure = { error("No clear was requested") },
            remove = { removed = true },
        )

        assertEquals(AccountClearRemovalResult.NotRequested, result)
        assertEquals(0, recordCalls)
        assertTrue(removed)
    }
}
