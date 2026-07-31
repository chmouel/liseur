package com.chmouel.liseur.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the three expensive things happen, and how often.
 *
 * Starting the app used to walk the server's whole catalog before the
 * reader had asked for anything, on top of a full position sync the
 * application itself had already requested. The point of these tests is
 * that a cold start touches the disk and nothing else.
 */
class LibraryRefreshTest {

    private val order = mutableListOf<String>()
    private var scans = 0
    private var catalogs = 0
    private var syncs = 0
    private var clock = 1_000L
    private var scanGate: CompletableDeferred<Unit>? = null

    private fun refresher(scope: kotlinx.coroutines.CoroutineScope) = LibraryRefresh(
        scope = scope,
        scanFolders = {
            scans++
            order += "scan"
            scanGate?.await()
        },
        refreshCatalog = {
            catalogs++
            order += "catalog"
        },
        syncPositions = {
            syncs++
            order += "positions"
        },
        now = { clock },
    )

    @Test
    fun `starting up looks at the folders and asks the server nothing`() = runTest {
        refresher(this).scanQuietly()
        advanceUntilIdle()

        assertEquals(listOf("scan"), order)
        assertEquals(0, catalogs)
        assertEquals(0, syncs)
    }

    @Test
    fun `pulling the shelf down does all three, in order`() = runTest {
        refresher(this).all()
        advanceUntilIdle()

        assertEquals(listOf("scan", "catalog", "positions"), order)
    }

    @Test
    fun `pulling again while it is still going does not start a second run`() = runTest {
        scanGate = CompletableDeferred()
        val refresh = refresher(this)

        refresh.all()
        advanceUntilIdle()
        refresh.all()
        advanceUntilIdle()
        scanGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("scan", "catalog", "positions"), order)
    }

    /**
     * Startup and the library's first `ON_RESUME` arrive together. Two
     * walks of the same folders is the cost the reader waits for twice.
     */
    @Test
    fun `startup and coming straight back scan once between them`() = runTest {
        scanGate = CompletableDeferred()
        val refresh = refresher(this)

        refresh.scanQuietly()
        advanceUntilIdle()
        clock += 120_000L
        refresh.scanIfStale()
        advanceUntilIdle()
        scanGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, scans)
    }

    @Test
    fun `coming back a moment later does not scan again`() = runTest {
        val refresh = refresher(this)

        refresh.scanQuietly()
        advanceUntilIdle()
        clock += 1_000L
        refresh.scanIfStale()
        advanceUntilIdle()

        assertEquals(1, scans)
    }

    /** A pull is a fresh look however recently the shelf was scanned. */
    @Test
    fun `a pull scans even inside the quiet window`() = runTest {
        val refresh = refresher(this)

        refresh.scanQuietly()
        advanceUntilIdle()
        clock += 1_000L
        refresh.all()
        advanceUntilIdle()

        assertEquals(2, scans)
    }

    @Test
    fun `the spinner is only for the gesture that asked for one`() = runTest {
        scanGate = CompletableDeferred()
        val refresh = refresher(this)

        refresh.scanQuietly()
        advanceUntilIdle()
        assertEquals(false, refresh.refreshing.value)

        refresh.all()
        advanceUntilIdle()
        assertEquals(true, refresh.refreshing.value)

        scanGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, refresh.refreshing.value)
    }
}
