package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncMove
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncReport
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.remote.valueOrNull
import com.chmouel.liseur.domain.EPSILON
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingBaseline
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.SyncDecision
import com.chmouel.liseur.domain.needsReconciling
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.domain.reconcileReadingState

/** What one book's reconciliation did, and why it stopped if it did. */
private data class BookOutcome(
    val moved: SyncMove? = null,
    val failure: SyncFailure? = null,
)

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
    private val serverDao: RemoteServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val client: KoboClient = KoboClient(),
    private val finishedState: FinishedState,
    private val reporting: SyncReporting = SyncReporting(),
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : PositionSync {

    /** Reconciles every book that has a position on either side. */
    override suspend fun syncAll(): SyncOutcome = run(book = null)

    /** Reconciles one book, for the moments someone is waiting on it. */
    override suspend fun syncBook(bookUrl: String): SyncOutcome = run(book = bookUrl)

    /** Whether this book has anywhere to sync to, so the action can stay hidden. */
    override suspend fun canSync(bookUrl: String): Boolean {
        val server = serverDao.get() ?: return false
        if (server.koboToken == null) return false
        return bookDao.getByUrl(bookUrl)?.remoteUuid != null
    }

    /**
     * Asks the server outright where it thinks the reader is in one book,
     * and reports both positions without acting on either.
     *
     * Deliberately not a reconciliation. Someone has asked about this
     * book, so the answer is theirs to make: the ordinary rules would
     * quietly do nothing whenever both sides had moved, and would say
     * nothing at all when the server is *behind*, which is exactly when
     * "sync this book" is being pressed after reading on another device
     * that has not caught up.
     *
     * The server's answer is written down before returning, so choosing
     * later — or not choosing, and coming back to it — works even if the
     * app is killed in between.
     */
    override suspend fun previewBook(bookUrl: String): PreviewOutcome {
        val server = serverDao.get() ?: return PreviewOutcome.NotSynced
        val token = server.koboToken ?: return PreviewOutcome.NotSynced
        val uuid = bookDao.getByUrl(bookUrl)?.remoteUuid ?: return PreviewOutcome.NotSynced
        val base = "${server.baseUrl}/kobo/$token"
        val account = server.accountKey

        val remote = when (val asked = client.readState(base, uuid)) {
            is RemoteResult.Failed -> return PreviewOutcome.Failed(asked.reason)
            is RemoteResult.Ok -> asked.value
        }
        if (remote != null) {
            progressDao.persistPending(
                bookUrl = bookUrl,
                progression = remote.progression,
                status = remote.status.wireName,
                remoteUpdatedAt = remote.updatedAt,
                account = account,
                now = System.currentTimeMillis(),
            )
        }
        return PreviewOutcome.Ready(
            SyncPreview(
                local = progressDao.get(bookUrl)?.totalProgression,
                remote = remote?.progression,
                remoteAt = remote?.updatedAt?.takeIf { it > 0 },
            ),
        )
    }

    /**
     * The unresolved disagreement an ordinary sync left behind, if there
     * is one, without asking the server anything.
     *
     * When both sides have moved, reconciliation preserves both and
     * chooses neither. That is the right call for a background job, but
     * it leaves the reader to open a book at a position the app already
     * knows is disputed. This reports the dispute so it can be put to
     * the person holding the device, who is the only one who knows which
     * device they last read on.
     *
     * Returns null when there is nothing preserved, when what is
     * preserved came from a different account, or when the two sides
     * turn out to agree after all.
     */
    override suspend fun preservedConflict(bookUrl: String): SyncPreview? {
        val server = serverDao.get() ?: return null
        if (server.koboToken == null) return null
        val stored = progressDao.get(bookUrl) ?: return null
        val pending = stored.pendingStateFor(server.accountKey) ?: return null
        val there = pending.progression ?: return null
        return SyncPreview(
            local = stored.totalProgression,
            remote = there,
            remoteAt = stored.remoteUpdatedAt?.takeIf { it > 0 },
        ).takeIf { !it.agrees }
    }

    /**
     * Takes the position the server reported for one book, because
     * someone said to.
     *
     * Still refuses if a page was turned in between, since that page turn
     * is newer than the choice being acted on.
     */
    override suspend fun takeRemotePosition(bookUrl: String, atRevision: Long): ResolveOutcome {
        val server = serverDao.get() ?: return ResolveOutcome.Done
        val stored = progressDao.get(bookUrl) ?: return ResolveOutcome.Done
        val progression = stored.pendingProgression ?: return ResolveOutcome.Done
        val applied = progressDao.applyPull(
            bookUrl = bookUrl,
            expectedRevision = atRevision,
            progression = progression,
            status = ReadingStatus.fromWire(stored.pendingStatus).wireName,
            account = server.accountKey,
            remoteUpdatedAt = stored.pendingUpdatedAt,
            now = System.currentTimeMillis(),
        )
        if (!applied) return ResolveOutcome.Superseded
        finishedState.refreshFromProgress(bookUrl)
        return ResolveOutcome.Done
    }

    /**
     * Sends this device's position for one book, because someone said to,
     * and stops the server's answer from being offered again.
     */
    override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome {
        val server = serverDao.get() ?: return ResolveOutcome.Done
        val token = server.koboToken ?: return ResolveOutcome.Done
        val uuid = bookDao.getByUrl(bookUrl)?.remoteUuid ?: return ResolveOutcome.Done
        // Read afresh, so a page turned while the question was open is
        // what gets sent rather than the position it was asked about.
        val stored = progressDao.get(bookUrl) ?: return ResolveOutcome.Done

        val outcome = apply(
            base = "${server.baseUrl}/kobo/$token",
            uuid = uuid,
            bookUrl = bookUrl,
            account = server.accountKey,
            stored = stored,
            decision = SyncDecision.Push(stored.asReadingState()),
        )
        return outcome.failure?.let { ResolveOutcome.Failed(it) } ?: ResolveOutcome.Done
    }

    private suspend fun run(book: String?): SyncOutcome {
        val server = serverDao.get() ?: run {
            reporting.report(PositionSyncStatus.Idle)
            return SyncOutcome.NotApplicable
        }
        val token = server.koboToken ?: run {
            reporting.report(PositionSyncStatus.Unavailable)
            return SyncOutcome.NotApplicable
        }

        reporting.report(PositionSyncStatus.Syncing)
        val base = "${server.baseUrl}/kobo/$token"
        val account = server.accountKey

        // Anything the server has to say is written down and the token
        // moved past it in one step, before a single decision is made.
        val reported = when (val landed = land(base, server, account)) {
            is RemoteResult.Ok -> landed.value
            is RemoteResult.Failed -> {
                Log.i(TAG, "Could not read reading positions: ${landed.reason.label}")
                reporting.report(PositionSyncStatus.Failed(landed.reason))
                return SyncOutcome.Failure(landed.reason)
            }
        }

        val books = bookDao.allRemote().filter { book == null || it.url == book }
        var firstFailure: SyncFailure? = null
        var pulled = 0
        var pushed = 0
        for (candidate in books) {
            val outcome = reconcileBook(base, account, candidate, reported)
            when (outcome.moved) {
                SyncMove.PULLED -> pulled++
                SyncMove.PUSHED -> pushed++
                SyncMove.UNRESOLVED, null -> Unit
            }
            if (outcome.failure != null && firstFailure == null) firstFailure = outcome.failure
        }

        val now = System.currentTimeMillis()
        reporting.report(
            SyncReport(
                at = now,
                pulled = pulled,
                pushed = pushed,
                // Counted from disk: a disagreement outlives the run that
                // found it, and a restart must not make it look settled.
                unresolved = progressDao.pendingFor(account).size,
            ),
        )
        // Only ever stamp the account that is still connected. If it went
        // away while this ran, writing it back would sign the user in again.
        serverDao.get()
            ?.takeIf { it.accountKey == server.accountKey }
            ?.let { serverDao.upsert(it.copy(positionSyncedAt = now)) }
        return if (firstFailure == null) {
            reporting.report(PositionSyncStatus.Synced(now))
            SyncOutcome.Success
        } else {
            Log.i(TAG, "Some positions did not settle: ${firstFailure.label}")
            reporting.report(PositionSyncStatus.Failed(firstFailure))
            SyncOutcome.Partial(firstFailure)
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
        server: com.chmouel.liseur.data.db.RemoteServer,
        account: String,
    ): RemoteResult<Map<String, ReadingState>> {
        val page = when (val pulled = client.pullReadingStates(base, server.syncToken)) {
            is RemoteResult.Ok -> pulled.value
            is RemoteResult.Failed -> return pulled
        }
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
        return RemoteResult.Ok(landed)
    }

    /**
     * Settles one book, and says what stopped it if anything did.
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
    ): BookOutcome {
        val uuid = book.remoteUuid ?: return BookOutcome()
        var stored = progressDao.get(book.url)
        var remote = landed[book.url] ?: stored?.pendingStateFor(account)

        // A book the feed has never mentioned and that has no agreed
        // position yet is simply unknown, not absent — ask outright, once.
        if (remote == null && stored?.agreedAccountMatches(account) != true) {
            when (val asked = client.readState(base, uuid)) {
                is RemoteResult.Failed -> return BookOutcome(failure = asked.reason)
                is RemoteResult.Ok -> remote = asked.value?.also {
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
        }

        val local = stored?.asReadingState()
        val dirty = stored?.isDirty == true
        if (!needsReconciling(remote != null, stored?.hasPending == true, dirty)) {
            return BookOutcome()
        }

        val decision = reconcileReadingState(
            local = local,
            remote = remote,
            baseline = stored?.baselineFor(account),
            localDirty = dirty,
            localUnreadOverride = stored?.override == FinishedOverride.UNREAD,
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
    ): BookOutcome {
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
                BookOutcome()
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
                // Taking the server's word for it can finish a book, and
                // the library has to agree with the reader about that.
                finishedState.refreshFromProgress(bookUrl)
                BookOutcome(moved = SyncMove.PULLED)
            }

            is SyncDecision.Push -> {
                val sent = stored?.localRevision ?: 0
                when (val pushed = client.pushState(base, uuid, decision.state)) {
                    is RemoteResult.Failed -> BookOutcome(failure = pushed.reason)
                    is RemoteResult.Ok -> {
                        progressDao.ackPush(
                            bookUrl = bookUrl,
                            sentRevision = sent,
                            progression = decision.state.progression,
                            status = decision.state.status.wireName,
                            account = account,
                            now = now,
                        )
                        BookOutcome(moved = SyncMove.PUSHED)
                    }
                }
            }

            is SyncDecision.AdoptStatus -> {
                // Refused means someone here said outright that this book
                // is read, or is not. That outranks a flag on the server,
                // and the disagreement stays on disk to be settled.
                val adopted =
                    progressDao.adoptStatus(bookUrl, decision.status.wireName, account, now)
                if (adopted) finishedState.refreshFromProgress(bookUrl)
                // Refusing is a disagreement someone has to settle, not a
                // quiet no-op, so it is counted as one.
                BookOutcome(moved = if (adopted) SyncMove.PULLED else SyncMove.UNRESOLVED)
            }

            is SyncDecision.Conflict -> {
                // Preserve both, choose neither. The remote state is
                // already on disk; leaving it there is the whole point.
                Log.i(TAG, "Both sides moved for a book; leaving it to be asked about")
                BookOutcome(moved = SyncMove.UNRESOLVED)
            }
        }
    }

    /**
     * Re-counts unsettled disagreements from disk, for when the report
     * is shown by a process that has not run a sync itself.
     */
    override suspend fun refreshUnresolved() {
        val account = serverDao.get()?.accountKey ?: return
        reporting.reportUnresolved(progressDao.pendingFor(account).size)
    }

    /**
     * Who positions on this device belong to, so a book that will not
     * sync can say why rather than simply doing nothing.
     *
     * Positions are bound to the login that produced them. Signing in as
     * a different calibre-web user therefore strands the reading done as
     * the old one — deliberately, since uploading it would put one
     * person's reading in another's account — but nothing has so far
     * said so out loud.
     */
    override suspend fun identity(): SyncIdentity? {
        val server = serverDao.get() ?: return null
        if (server.koboToken == null) return null
        return SyncIdentity(
            login = server.username.orEmpty(),
            strandedBooks = progressDao.ownedByOther(server.accountKey).size,
        )
    }

    private fun ReadingProgress.statusOrDerived(): ReadingStatus =
        status?.let { ReadingStatus.fromWire(it) }
            ?: readingStatusFor(totalProgression, override)

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
