package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.CalibreServerDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.SyncDecision
import com.chmouel.liseur.domain.mergeReadingState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the last attempt to sync reading positions went. */
sealed interface PositionSyncStatus {
    data object Idle : PositionSyncStatus
    data object Syncing : PositionSyncStatus
    data class Synced(val at: Long) : PositionSyncStatus
    data object Offline : PositionSyncStatus

    /** The server has no Kobo sync set up, so positions stay on this device. */
    data object Unavailable : PositionSyncStatus
}

/**
 * Keeps reading positions in step with calibre-web.
 *
 * Positions travel as a percentage through the book, which is all the
 * Kobo protocol carries. That is enough to reopen a book on another
 * device at the right page, and it means a position set by the Kobo app,
 * the calibre-web reader or Liseur all mean the same thing.
 */
class KoboSyncRepository(
    private val serverDao: CalibreServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val client: KoboClient = KoboClient(),
) {
    private val _status = MutableStateFlow<PositionSyncStatus>(PositionSyncStatus.Idle)
    val status: StateFlow<PositionSyncStatus> = _status.asStateFlow()

    private val syncing = Mutex()

    /**
     * Reconciles every book that has a position on either side.
     * Returns false when nothing could be done, e.g. the server is not
     * reachable or sync was never set up.
     */
    suspend fun sync(): Boolean {
        if (!syncing.tryLock()) return false
        try {
            val server = serverDao.get() ?: run {
                _status.value = PositionSyncStatus.Idle
                return false
            }
            val token = server.koboToken ?: run {
                _status.value = PositionSyncStatus.Unavailable
                return false
            }

            _status.value = PositionSyncStatus.Syncing
            val base = "${server.baseUrl}/kobo/$token"

            return try {
                val page = client.pullReadingStates(base, server.syncToken)
                reconcile(base, page.states)

                val now = System.currentTimeMillis()
                serverDao.upsert(
                    server.copy(syncToken = page.syncToken, positionSyncedAt = now),
                )
                _status.value = PositionSyncStatus.Synced(now)
                true
            } catch (e: IOException) {
                Log.i(TAG, "Could not sync reading positions", e)
                _status.value = PositionSyncStatus.Offline
                false
            }
        } finally {
            syncing.unlock()
        }
    }

    /**
     * Sends one book's position straight away, for the moment a book is
     * closed. Falls back to nothing if sync is not set up; the next full
     * sync will carry the position instead.
     */
    suspend fun pushOne(bookUrl: String): Boolean {
        val server = serverDao.get() ?: return false
        val token = server.koboToken ?: return false
        val uuid = bookDao.getByUrl(bookUrl)?.remoteUuid ?: return false
        val local = localState(bookUrl) ?: return false

        return try {
            val pushed = client.pushState("${server.baseUrl}/kobo/$token", uuid, local)
            if (pushed) progressDao.markSynced(bookUrl, System.currentTimeMillis())
            pushed
        } catch (e: IOException) {
            Log.i(TAG, "Could not send the position for $bookUrl", e)
            false
        }
    }

    private suspend fun reconcile(base: String, remote: Map<String, ReadingState>) {
        val books = bookDao.allRemote().associateBy { it.remoteUuid }
        val uuids = remote.keys + books.values.mapNotNull { it.remoteUuid }

        for (uuid in uuids) {
            val book = books[uuid] ?: continue
            val local = localState(book.url)
            when (val decision = mergeReadingState(local, remote[uuid])) {
                SyncDecision.InSync -> Unit

                is SyncDecision.Pull -> {
                    adopt(book.url, decision.state)
                    progressDao.markSynced(book.url, System.currentTimeMillis())
                }

                is SyncDecision.Push -> {
                    if (client.pushState(base, uuid, decision.state)) {
                        progressDao.markSynced(book.url, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    private suspend fun localState(bookUrl: String): ReadingState? {
        val stored = progressDao.get(bookUrl) ?: return null
        return ReadingState(
            progression = stored.totalProgression,
            status = stored.status?.let { ReadingStatus.fromWire(it) }
                ?: ReadingStatus.forProgression(stored.totalProgression),
            updatedAt = stored.updatedAt,
        )
    }

    /**
     * Takes the server's position for a book.
     *
     * Only a percentage comes over the wire, so the stored locator is
     * left as it was and the percentage is what changes. The reader
     * notices the two no longer agree and works out the real place in
     * the book once it knows how the book is laid out.
     */
    private suspend fun adopt(bookUrl: String, state: ReadingState) {
        val progression = state.progression ?: return
        val existing = progressDao.get(bookUrl)
        progressDao.upsert(
            com.chmouel.liseur.data.db.ReadingProgress(
                bookUrl = bookUrl,
                locatorJson = existing?.locatorJson ?: EMPTY_LOCATOR,
                totalProgression = progression,
                readingSpeed = existing?.readingSpeed,
                updatedAt = state.updatedAt,
                status = state.status.wireName,
                syncedAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val TAG = "KoboSync"
        const val EMPTY_LOCATOR = "{}"
    }
}
