package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.remote.CatalogRefresh
import com.chmouel.liseur.data.remote.SyncSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When the library looks again, and at how much.
 *
 * Opening the app and pulling the shelf down are not the same request,
 * and treating them as one is what made a cold start as expensive as a
 * refresh: everything already known was in the database and on screen,
 * and the catalog was walked from the top anyway. Starting up looks at
 * the folders on the device and nothing else; the gesture that means
 * "look again" is the only thing that asks the server.
 *
 * Nothing here knows what a repository is, so the ordering can be tested
 * without a database, a server or a phone.
 */
class LibraryRefresh(
    private val scope: CoroutineScope,
    private val scanFolders: suspend () -> Unit,
    private val refreshCatalog: suspend () -> CatalogRefresh,
    private val syncPositions: suspend (requestedAt: Long, snapshot: SyncSnapshot?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _refreshing = MutableStateFlow(false)

    /** Whether to show the gesture's spinner. A quiet scan never does. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val scanState = Mutex()
    private var scan: Deferred<Unit>? = null
    private var lastScanAt = 0L

    /** Startup: pick up files added or removed while the app was away. */
    fun scanQuietly() {
        scope.launch { runCatching { scanOnce() } }
    }

    /**
     * Coming back to the library, look at the folders again unless that
     * was just done. Returning from the reader is the usual way to land
     * here, and rescanning every time would spin the disk for nothing.
     */
    fun scanIfStale() {
        if (now() - lastScanAt < RESCAN_DEBOUNCE_MS) return
        scope.launch { runCatching { scanOnce() } }
    }

    /**
     * Pull-to-refresh: the folders and the server's books, side by side,
     * then where you got to once both are in.
     *
     * The last of those used to be missing, which made the gesture look
     * broken: pulling down brought new books but left a book you had
     * read on another device sitting at the old page.
     */
    fun all() {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try {
                // The folders and the server know nothing of each other,
                // and the gesture should not wait on a slow disk walk
                // before the network is even asked.
                val scan = launch { runCatching { scanOnce() } }
                val catalog = runCatching { refreshCatalog() }.getOrDefault(CatalogRefresh.None)
                scan.join()
                // Asked as of now rather than as of the gesture, because
                // the catalog has only just been read: a sync already
                // running started before that and cannot answer for what
                // it has not seen. What it did see is offered along, so
                // the same listing is not fetched twice.
                runCatching { syncPositions(now(), catalog.forSync()) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * One scan at a time, however many callers there are.
     *
     * Startup and the library's first `ON_RESUME` arrive together, and a
     * pull landing on top of either should wait for the walk already
     * under way rather than start a second one over the same folders.
     */
    private suspend fun scanOnce() {
        val running = scanState.withLock {
            scan?.takeIf { it.isActive } ?: scope.async { scanFolders() }.also {
                scan = it
                lastScanAt = now()
            }
        }
        running.await()
    }

    private companion object {
        const val RESCAN_DEBOUNCE_MS = 60_000L
    }
}
