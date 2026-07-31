package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.remote.CatalogRefresh
import com.chmouel.liseur.data.remote.CatalogSnapshot
import com.chmouel.liseur.data.remote.SyncSnapshot
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
    private var catalogResult = CatalogRefresh.None
    private val offered = mutableListOf<SyncSnapshot?>()

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
            catalogResult
        },
        syncPositions = { _, snapshot ->
            syncs++
            offered += snapshot
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
    fun `pulling the shelf down does all three, positions last`() = runTest {
        refresher(this).all()
        advanceUntilIdle()

        // The scan and the catalog run side by side — neither waits on
        // the other — so only the ending is promised: the positions ask
        // comes after both, with the catalog's answer in hand.
        assertEquals(1, scans)
        assertEquals(1, catalogs)
        assertEquals(1, syncs)
        assertEquals("positions", order.last())
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

        assertEquals(1, scans)
        assertEquals(1, catalogs)
        assertEquals(1, syncs)
        assertEquals("positions", order.last())
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

    /**
     * The listing the refresh just read is handed to the sync rather
     * than fetched again: on Komga the two are the same request, and
     * one pull of the shelf should not walk the catalog twice.
     */
    @Test
    fun `what the catalog walk read is handed to the sync`() = runTest {
        val snapshot = object : CatalogSnapshot {}
        catalogResult = CatalogRefresh(completed = true, accountKey = "acc", snapshot = snapshot)

        refresher(this).all()
        advanceUntilIdle()

        assertEquals(listOf(SyncSnapshot("acc", snapshot)), offered)
    }

    /**
     * A walk that stopped short saw only part of the library, so the
     * sync asks the server itself rather than trusting it.
     */
    @Test
    fun `a walk that did not finish is not handed on`() = runTest {
        catalogResult = CatalogRefresh(
            completed = false,
            accountKey = "acc",
            snapshot = object : CatalogSnapshot {},
        )

        refresher(this).all()
        advanceUntilIdle()

        assertEquals(listOf(null), offered)
    }
}
