package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.CalibreServerDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.domain.ReadingBaseline
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.SyncDecision
import com.chmouel.liseur.domain.needsReconciling
import com.chmouel.liseur.domain.reconcileReadingState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Worth keeping apart, because only some of these are worth trying
 * again: a phone with no calibre-web account will still have none in ten
 * minutes, and scheduling a backed-off retry for it just burns battery.
 */
sealed interface SyncOutcome {
    /** Positions were exchanged, or both sides already agreed. */
    data object Success : SyncOutcome

    /**
     * Some books settled and some did not. The ones that did not are
     * still marked as having reading the server has not seen, so the next
     * run picks them up. Reported apart from success so a retry is
     * scheduled and the settings screen does not claim all is well.
     */
    data object Partial : SyncOutcome

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
 *
 * Nothing here takes a lock. Running one sync at a time, and letting
 * callers wait for the one already in flight, belongs to
 * `PositionSyncCoordinator`; a lock here as well would only be a second
 * opinion about the same thing.
 *
 * [inTransaction] exists because one thing genuinely must be atomic:
 * writing down what the server reported, and moving the sync token past
 * it. The token is destructive — once it has moved, the server will never
 * mention that change again — so a crash between the two loses a
 * position with no trace.
 */
class KoboSyncRepository(
    private val serverDao: CalibreServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val client: KoboClient = KoboClient(),
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val _status = MutableStateFlow<PositionSyncStatus>(PositionSyncStatus.Idle)
    val status: StateFlow<PositionSyncStatus> = _status.asStateFlow()

    /** Reconciles every book that has a position on either side. */
    suspend fun syncAll(): SyncOutcome = run(book = null)

    /** Reconciles one book, for the moments someone is waiting on it. */
    suspend fun syncBook(bookUrl: String): SyncOutcome = run(book = bookUrl)

    private suspend fun run(book: String?): SyncOutcome {
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
        val account = server.accountKey

        return try {
            // Anything the server has to say is written down and the token
            // moved past it in one step, before a single decision is made.
            val reported = land(base, server, account)

            val books = bookDao.allRemote().filter { book == null || it.url == book }
            var allSettled = true
            for (candidate in books) {
                if (!reconcileBook(base, account, candidate, reported)) allSettled = false
            }

            val now = System.currentTimeMillis()
            serverDao.upsert(serverDao.get()?.copy(positionSyncedAt = now) ?: server)
            _status.value = PositionSyncStatus.Synced(now)
            if (allSettled) SyncOutcome.Success else SyncOutcome.Partial
        } catch (e: IOException) {
            Log.i(TAG, "Could not sync reading positions", e)
            _status.value = PositionSyncStatus.Offline
            SyncOutcome.TransientFailure
        }
    }

    /**
     * Pulls the feed and puts everything it reported beyond reach of a
     * crash, together with the token that will stop it being reported
     * again. If the writing fails the token is not moved, so the server
     * says it all over again next time.
     *
     * Returns which books the server actually mentioned, so a book it was
     * silent about is not mistaken for a book with no position.
     */
    private suspend fun land(
        base: String,
        server: com.chmouel.liseur.data.db.CalibreServer,
        account: String,
    ): Map<String, ReadingState> {
        val page = client.pullReadingStates(base, server.syncToken)
        val byUuid = bookDao.allRemote().mapNotNull { b -> b.remoteUuid?.let { it to b } }.toMap()
        val landed = mutableMapOf<String, ReadingState>()
        val now = System.currentTimeMillis()

        inTransaction {
            for ((uuid, state) in page.states) {
                val known = byUuid[uuid] ?: continue
                progressDao.persistPending(
                    bookUrl = known.url,
                    progression = state.progression,
                    status = state.status.wireName,
                    remoteUpdatedAt = state.updatedAt,
                    account = account,
                    now = now,
                )
                landed[known.url] = state
            }
            serverDao.upsert(server.copy(syncToken = page.syncToken))
        }
        return landed
    }

    /**
     * Settles one book, and says whether it ended up settled.
     *
     * The states this works from are whatever was just landed *plus*
     * whatever is still sitting on the row from an earlier run. That
     * second half matters: the token has already moved past those, so if
     * they were only ever read out of the feed, a run interrupted between
     * committing the token and acting on it would lose them for good.
     */
    private suspend fun reconcileBook(
        base: String,
        account: String,
        book: Book,
        landed: Map<String, ReadingState>,
    ): Boolean {
        val uuid = book.remoteUuid ?: return true
        var stored = progressDao.get(book.url)
        var remote = landed[book.url] ?: stored?.pendingStateFor(account)

        // A book the feed has never mentioned and that has no agreed
        // position yet is simply unknown, not absent — ask outright, once.
        if (remote == null && stored?.agreedAccountMatches(account) != true) {
            remote = client.readState(base, uuid)?.also {
                progressDao.persistPending(
                    bookUrl = book.url,
                    progression = it.progression,
                    status = it.status.wireName,
                    remoteUpdatedAt = it.updatedAt,
                    account = account,
                    now = System.currentTimeMillis(),
                )
                stored = progressDao.get(book.url)
            }
        }

        val local = stored?.asReadingState()
        val dirty = stored?.isDirty == true
        if (!needsReconciling(remote != null, stored?.hasPending == true, dirty)) return true

        val decision = reconcileReadingState(
            local = local,
            remote = remote,
            baseline = stored?.baselineFor(account),
            localDirty = dirty,
        )
        return apply(base, uuid, book.url, account, stored, decision)
    }

    private suspend fun apply(
        base: String,
        uuid: String,
        bookUrl: String,
        account: String,
        stored: ReadingProgress?,
        decision: SyncDecision,
    ): Boolean {
        val now = System.currentTimeMillis()
        return when (decision) {
            SyncDecision.InSync -> {
                // Agreeing is worth recording: it is what later tells a
                // reread apart from the other device having moved on.
                if (stored != null) {
                    progressDao.settleAgreed(
                        bookUrl = bookUrl,
                        progression = stored.totalProgression,
                        status = stored.statusOrDerived().wireName,
                        account = account,
                        now = now,
                    )
                }
                true
            }

            is SyncDecision.Pull -> {
                val progression = decision.state.progression ?: stored?.totalProgression
                if (progression == null) {
                    progressDao.clearPending(bookUrl)
                } else {
                    // Refused means a page was turned here while this was
                    // being decided, which makes it a disagreement rather
                    // than a handover. The remote state stays put for the
                    // next run, and now for someone to be asked about.
                    progressDao.applyPull(
                        bookUrl = bookUrl,
                        expectedRevision = stored?.localRevision ?: 0,
                        progression = progression,
                        status = decision.state.status.wireName,
                        account = account,
                        remoteUpdatedAt = decision.state.updatedAt,
                        now = now,
                    )
                }
                true
            }

            is SyncDecision.Push -> {
                val sent = stored?.localRevision ?: 0
                if (!client.pushState(base, uuid, decision.state)) {
                    false
                } else {
                    progressDao.ackPush(
                        bookUrl = bookUrl,
                        sentRevision = sent,
                        progression = decision.state.progression,
                        status = decision.state.status.wireName,
                        account = account,
                        now = now,
                    )
                    true
                }
            }

            is SyncDecision.AdoptStatus -> {
                progressDao.adoptStatus(bookUrl, decision.status.wireName, account, now)
                true
            }

            is SyncDecision.Conflict -> {
                // Preserve both, choose neither. The remote state is
                // already on disk; leaving it there is the whole point.
                Log.i(TAG, "Both sides moved for $bookUrl; leaving it to be asked about")
                true
            }
        }
    }

    private fun ReadingProgress.statusOrDerived(): ReadingStatus =
        status?.let { ReadingStatus.fromWire(it) }
            ?: ReadingStatus.forProgression(totalProgression)

    private fun ReadingProgress.asReadingState() = ReadingState(
        progression = totalProgression,
        status = statusOrDerived(),
        updatedAt = updatedAt,
    )

    /** The unsettled remote state, but only if this account reported it. */
    private fun ReadingProgress.pendingStateFor(account: String): ReadingState? {
        if (pendingAccount != account) return null
        return ReadingState(
            progression = pendingProgression,
            status = ReadingStatus.fromWire(pendingStatus),
            updatedAt = pendingUpdatedAt ?: 0L,
        )
    }

    /**
     * The last state agreed with *this* account. Someone else's baseline
     * is worse than none: it would have this device diff its reading
     * against a stranger's.
     */
    private fun ReadingProgress.baselineFor(account: String): ReadingBaseline? {
        if (agreedAccount != account) return null
        return ReadingBaseline(
            progression = agreedProgression,
            status = ReadingStatus.fromWire(agreedStatus),
        )
    }

    private fun ReadingProgress.agreedAccountMatches(account: String) = agreedAccount == account

    private companion object {
        const val TAG = "KoboSync"
    }
}
