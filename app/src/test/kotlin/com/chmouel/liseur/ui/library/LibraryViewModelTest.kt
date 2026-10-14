package com.chmouel.liseur.ui.library

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelTest {
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
