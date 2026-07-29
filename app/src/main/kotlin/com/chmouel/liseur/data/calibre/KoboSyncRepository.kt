package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.CalibreServerDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.SyncDecision
import com.chmouel.liseur.domain.mergeReadingState
import com.chmouel.liseur.domain.needsReconciling
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
 * What came of asking to sync.
 *
 * Worth keeping apart, because only one of these is worth trying again:
 * a phone with no calibre-web account will still have none in ten
 * minutes, and scheduling a backed-off retry for it just burns battery.
 */
sealed interface SyncOutcome {
    /** Positions were exchanged, or both sides already agreed. */
    data object Success : SyncOutcome

    /** Nothing to do and nothing wrong: no account, no sync, nothing to send. */
    data object NotApplicable : SyncOutcome

    /** The server or the network was not there. Ask again later. */
    data object TransientFailure : SyncOutcome
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
     *
     * [SyncOutcome.NotApplicable] covers the settled cases — no account,
     * no Kobo token, a sync already running — which are not failures and
     * will not become successes by being retried.
     */
    suspend fun sync(): SyncOutcome {
        if (!syncing.tryLock()) return SyncOutcome.NotApplicable
        try {
            val server = serverDao.get() ?: run {
                _status.value = PositionSyncStatus.Idle
                return SyncOutcome.NotApplicable
            }
            val token = server.koboToken ?: run {
                _status.value = PositionSyncStatus.Unavailable
                return SyncOutcome.NotApplicable
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
                SyncOutcome.Success
            } catch (e: IOException) {
                Log.i(TAG, "Could not sync reading positions", e)
                _status.value = PositionSyncStatus.Offline
                SyncOutcome.TransientFailure
            }
        } finally {
            syncing.unlock()
        }
    }

    /**
     * Sends one book's position straight away, for the moment a book is
     * closed. A book that is not on the server, or a server without sync,
     * is nothing to worry about: there is simply nowhere to send it.
     */
    suspend fun pushOne(bookUrl: String): SyncOutcome {
        val server = serverDao.get() ?: return SyncOutcome.NotApplicable
        val token = server.koboToken ?: return SyncOutcome.NotApplicable
        val uuid = bookDao.getByUrl(bookUrl)?.remoteUuid ?: return SyncOutcome.NotApplicable
        val local = localState(bookUrl) ?: return SyncOutcome.NotApplicable

        return try {
            if (client.pushState("${server.baseUrl}/kobo/$token", uuid, local)) {
                progressDao.markSynced(bookUrl, System.currentTimeMillis())
                SyncOutcome.Success
            } else {
                SyncOutcome.TransientFailure
            }
        } catch (e: IOException) {
            Log.i(TAG, "Could not send the position for $bookUrl", e)
            SyncOutcome.TransientFailure
        }
    }

    /**
     * Settles each book against what the server reported.
     *
     * The sync feed is incremental: it carries only what changed since the
     * last token. A book it does not mention is not a book with no remote
     * position, it is a book the server has nothing new to say about — so
     * the only reason to send anything is if this device has moved on since
     * it last agreed with the server.
     */
    private suspend fun reconcile(base: String, remote: Map<String, ReadingState>) {
        for (book in bookDao.allRemote()) {
            val uuid = book.remoteUuid ?: continue
            val stored = progressDao.get(book.url)
            val reported = remote[uuid]
            if (!needsReconciling(reported, stored?.updatedAt, stored?.syncedAt)) continue

            when (val decision = mergeReadingState(stored?.asReadingState(), reported)) {
                SyncDecision.InSync -> Unit

                is SyncDecision.Pull ->
                    if (adopt(book.url, decision.state)) {
                        progressDao.markSynced(book.url, System.currentTimeMillis())
                    }

                is SyncDecision.Push ->
                    if (client.pushState(base, uuid, decision.state)) {
                        progressDao.markSynced(book.url, System.currentTimeMillis())
                    }
            }
        }
    }

    private fun ReadingProgress.asReadingState() = ReadingState(
        progression = totalProgression,
        status = status?.let { ReadingStatus.fromWire(it) }
            ?: ReadingStatus.forProgression(totalProgression),
        updatedAt = updatedAt,
    )

    private suspend fun localState(bookUrl: String): ReadingState? =
        progressDao.get(bookUrl)?.asReadingState()

    /**
     * Takes the server's position for a book. False when there was nothing
     * worth recording, so the row is not marked agreed on when it is not.
     *
     * Only a percentage comes over the wire, so the stored locator is
     * left as it was and the percentage is what changes. The reader
     * notices the two no longer agree and works out the real place in
     * the book once it knows how the book is laid out.
     *
     * A remote change can be status alone — someone marking a book read on
     * another device without opening it. That is worth keeping too, so the
     * progression already stored stands in for the one the server omitted.
     */
    private suspend fun adopt(bookUrl: String, state: ReadingState): Boolean {
        val existing = progressDao.get(bookUrl)
        val progression = state.progression ?: existing?.totalProgression ?: return false
        progressDao.upsert(
            ReadingProgress(
                bookUrl = bookUrl,
                locatorJson = existing?.locatorJson ?: EMPTY_LOCATOR,
                totalProgression = progression,
                readingSpeed = existing?.readingSpeed,
                updatedAt = state.updatedAt,
                status = state.status.wireName,
                syncedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    private companion object {
        const val TAG = "KoboSync"
        const val EMPTY_LOCATOR = "{}"
    }
}
