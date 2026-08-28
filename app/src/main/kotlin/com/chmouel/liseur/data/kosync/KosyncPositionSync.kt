package com.chmouel.liseur.data.kosync

import android.util.Log
import com.chmouel.liseur.data.NetworkAvailability
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.KosyncPeerDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.SyncPeerState
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.DeviceIdentity
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncMove
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncReport
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.remote.SyncSnapshot
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
    /** Set when the server said nothing at all — the cue to stop the run. */
    val unreachable: SyncFailure? = null,
)

/**
 * Keeps reading positions in step with a kosync server (issue #95).
 *
 * A partner alongside the catalog server, not a kind of it: Grimmory is
 * browsed through its Komga shim, which carries no reading position,
 * while its kosync endpoint does — so the two are configured separately
 * and this peer runs after the catalog's in `CompositePositionSync`.
 *
 * It runs only where [ServerKind.hostsKosyncPeer] says the pairing
 * belongs. Where a server carries positions itself, a second source for
 * the same book is a conflict the reader can neither see nor resolve,
 * so a peer that outlived the server it was paired with stays quiet
 * rather than syncing on its own authority.
 *
 * kosync names a book by KOReader's partial MD5 of the file, so only a
 * book whose bytes are on this device can be spoken about, and the same
 * book downloaded from somewhere else is a different document. Positions
 * travel as a percentage; there is no locator to carry, so a pull lands
 * at roughly the right page the way calibre-web's does.
 *
 * There is no feed and no cursor: each run asks about each candidate
 * outright. The candidates are the catalog server's downloaded books,
 * which is a bounded set, and a server that stops answering ends the
 * walk early rather than paying a connect timeout per remaining book.
 *
 * Agreements live in `sync_peer_state` under this account's key, so the
 * catalog server's baselines and this partner's cannot overwrite each
 * other. The merge itself is `reconcileReadingState`, unchanged: a
 * fourth kind of partner is not a fourth set of rules.
 */
class KosyncPositionSync(
    private val kosyncDao: KosyncPeerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val peerStateDao: SyncPeerStateDao,
    private val fingerprints: BookFingerprintStore,
    private val device: suspend () -> DeviceIdentity,
    private val finishedState: FinishedState,
    /**
     * The server the library is connected to right now, or null when
     * nothing is connected.
     *
     * Read on every run rather than captured once: an account switch
     * does not rebuild this object, and a peer that outlived the server
     * it was paired with must go quiet the moment the switch lands.
     *
     * Two things are asked of it, and both have to be asked at the same
     * moment: whether that kind of server may host a pairing at all,
     * and whether it lists books — which decides what the pairing is
     * responsible for.
     */
    private val connectedServer: suspend () -> RemoteServer?,
    private val client: KosyncClient = KosyncClient(),
    private val reporting: SyncReporting = SyncReporting(),
    private val networkAvailability: NetworkAvailability = NetworkAvailability { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : PeerPositionSync {

    override val peerId: String get() = PeerPositionSync.KOSYNC

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = run(book = null)

    override suspend fun syncBook(bookUrl: String): SyncOutcome = run(book = bookUrl)

    override suspend fun canSync(bookUrl: String): Boolean {
        account() ?: return false
        val book = bookDao.getByUrl(bookUrl) ?: return false
        return book.isCandidate
    }

    override suspend fun previewBook(bookUrl: String): PreviewOutcome {
        val account = account() ?: return PreviewOutcome.NotSynced
        val book = bookDao.getByUrl(bookUrl)?.takeIf { it.isCandidate }
            ?: return PreviewOutcome.NotSynced
        val document = fingerprints.of(book)?.partialMd5 ?: return PreviewOutcome.NotSynced
        if (!networkAvailability.isAvailable()) return PreviewOutcome.Failed(SyncFailure.Offline)

        val remote = when (val asked = client.getProgress(account.baseUrl, account.credentials, document)) {
            is RemoteResult.Failed -> return PreviewOutcome.Failed(asked.reason)
            is RemoteResult.Ok -> asked.value
        }
        if (remote != null) {
            forAccount(account) {
                peerStateDao.persistPending(
                    bookUrl = bookUrl,
                    peerId = account.peerId,
                    progression = remote.percentage,
                    status = ReadingStatus.forProgression(remote.percentage).wireName,
                    remoteUpdatedAt = remote.timestamp,
                )
            }
        }
        return PreviewOutcome.Ready(
            SyncPreview(
                local = progressDao.get(bookUrl)?.totalProgression,
                remote = remote?.percentage,
                remoteAt = remote?.timestamp?.takeIf { it > 0 },
            ),
        )
    }

    override suspend fun preservedConflict(bookUrl: String): SyncPreview? {
        val account = account() ?: return null
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return null
        if (!state.hasPending) return null
        val there = state.pendingProgression ?: return null
        return SyncPreview(
            local = progressDao.get(bookUrl)?.totalProgression,
            remote = there,
            remoteAt = state.remoteUpdatedAt?.takeIf { it > 0 },
        ).takeIf { !it.agrees }
    }

    override suspend fun takeRemotePosition(bookUrl: String, atRevision: Long): ResolveOutcome {
        val account = account() ?: return ResolveOutcome.Done
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return ResolveOutcome.Done
        val progression = state.pendingProgression ?: return ResolveOutcome.Done
        val status = ReadingStatus.fromWire(state.pendingStatus)
        var applied = false
        inTransaction {
            applied = progressDao.applyPeerPull(
                bookUrl = bookUrl,
                expectedRevision = atRevision,
                progression = progression,
                status = status.wireName,
                now = now(),
                remoteUpdatedAt = state.pendingUpdatedAt,
            )
            if (applied) {
                peerStateDao.settle(
                    bookUrl = bookUrl,
                    peerId = account.peerId,
                    ackedRevision = atRevision + 1,
                    progression = progression,
                    status = status.wireName,
                    now = now(),
                )
            }
        }
        if (!applied) return ResolveOutcome.Superseded
        finishedState.refreshFromProgress(bookUrl)
        return ResolveOutcome.Done
    }

    /**
     * Keeps what is here, and makes the server agree.
     *
     * Clearing the disagreement is not enough on its own. kosync has no
     * feed, so every run asks the server outright and gets the same
     * answer back; with the old agreement still standing, both sides
     * still read as having moved and the reader is asked the same
     * question on every sync until they happen to turn a page.
     *
     * So the answer that was just rejected is written down as the place
     * the two sides last agreed on. The revision is deliberately left
     * where it was: this device has not sent anything, so the book is
     * still owed to the server. Next run the server looks still and this
     * side looks moved, which is the ordinary shape of a push.
     */
    override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome {
        val account = account() ?: return ResolveOutcome.Done
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return ResolveOutcome.Done
        peerStateDao.settle(
            bookUrl = bookUrl,
            peerId = account.peerId,
            ackedRevision = state.ackedRevision,
            progression = state.pendingProgression ?: state.agreedProgression,
            status = state.pendingStatus ?: state.agreedStatus,
            now = now(),
        )
        return ResolveOutcome.Done
    }

    override suspend fun refreshUnresolved() {
        val account = account() ?: return
        reporting.reportUnresolved(peerStateDao.countPending(account.peerId), peerId)
    }

    override suspend fun identity(): SyncIdentity? {
        val account = account() ?: return null
        // Nothing is stranded by a kosync account change: agreements are
        // per account already, and the reading itself was never stamped.
        return SyncIdentity(login = account.login, strandedBooks = 0)
    }

    // -- The account ------------------------------------------------------

    /** The paired kosync partner, as one run saw it. */
    private class Account(
        val baseUrl: String,
        val credentials: KosyncCredentials,
        /** The key this partner's per-book agreements are stored under. */
        val peerId: String,
        val login: String,
    )

    private suspend fun account(): Account? {
        if (!eligible()) return null
        val peer = kosyncDao.get() ?: return null
        val credentials = peer.credentials ?: return null
        return Account(
            baseUrl = peer.baseUrl,
            credentials = credentials,
            peerId = peer.accountKey,
            login = peer.username,
        )
    }

    /**
     * Whether the connected server is one the pairing belongs to.
     *
     * A saved peer is not permission to sync. Nothing guarantees the
     * peer is removed before another server is connected — an account
     * switch that fails halfway, a crash, or a database restored onto a
     * phone that never made the pairing all leave one behind — and this
     * is the test that keeps such a peer from talking to a server on
     * behalf of a library it knows nothing about.
     */
    private suspend fun eligible(): Boolean =
        connectedServer()?.kind?.hostsKosyncPeer == true

    /** Whether the connected server also lists books. See [candidates]. */
    private suspend fun hasCatalog(): Boolean = connectedServer()?.catalogUrl != null

    // -- The run ----------------------------------------------------------

    private suspend fun run(book: String?): SyncOutcome {
        if (!eligible()) {
            reporting.report(PositionSyncStatus.Idle, peerId)
            return SyncOutcome.NotApplicable
        }
        val peer = kosyncDao.get() ?: run {
            reporting.report(PositionSyncStatus.Idle, peerId)
            return SyncOutcome.NotApplicable
        }
        val credentials = peer.credentials ?: run {
            // The key cannot be read back: a database restored onto
            // another phone arrives with ciphertext this Keystore
            // cannot open.
            reporting.report(PositionSyncStatus.Unavailable, peerId)
            return SyncOutcome.NotApplicable
        }
        if (!networkAvailability.isAvailable()) {
            reporting.report(PositionSyncStatus.Failed(SyncFailure.Offline), peerId)
            return SyncOutcome.Failure(SyncFailure.Offline)
        }
        val account = Account(
            baseUrl = peer.baseUrl,
            credentials = credentials,
            peerId = peer.accountKey,
            login = peer.username,
        )

        reporting.report(PositionSyncStatus.Syncing, peerId)
        val identity = device()
        val books = candidates(book)

        var firstFailure: SyncFailure? = null
        var unreachable: SyncFailure? = null
        var pulled = 0
        var pushed = 0
        for (candidate in books) {
            val outcome = reconcileBook(account, identity, candidate)
            when (outcome.moved) {
                SyncMove.PULLED -> pulled++
                SyncMove.PUSHED -> pushed++
                SyncMove.UNRESOLVED, null -> Unit
            }
            if (outcome.failure != null && firstFailure == null) firstFailure = outcome.failure
            // Nothing more is learned by asking the next book of a
            // server that has stopped answering, and each question
            // costs a whole connect timeout.
            unreachable = outcome.unreachable
            if (unreachable != null) {
                Log.i(TAG, "Stopping the run early: ${unreachable.label}")
                break
            }
        }

        val at = now()
        reporting.report(
            SyncReport(
                at = at,
                pulled = pulled,
                pushed = pushed,
                // Counted from disk: a disagreement outlives the run
                // that found it.
                unresolved = peerStateDao.countPending(account.peerId),
            ),
            peerId,
        )
        val failure = unreachable ?: firstFailure
        return if (failure == null) {
            forAccount(account) { kosyncDao.setPositionSyncedAt(at) }
            reporting.report(PositionSyncStatus.Synced(at), peerId)
            SyncOutcome.Success
        } else {
            Log.i(TAG, "Some positions did not settle: ${failure.label}")
            reporting.report(PositionSyncStatus.Failed(failure), peerId)
            if (pulled > 0 || pushed > 0) SyncOutcome.Partial(failure) else SyncOutcome.Failure(failure)
        }
    }

    /**
     * Settles one book: asks the server where it stands, lands the
     * answer, and acts on the comparison.
     *
     * The server is asked every run because kosync has no feed — silence
     * cannot be told apart from "nothing new" without asking. What was
     * already pending from an earlier run is folded in, so an answer
     * that landed and was never acted on is not lost to a crash.
     */
    private suspend fun reconcileBook(
        account: Account,
        identity: DeviceIdentity,
        book: Book,
    ): BookOutcome {
        // A book whose file cannot be read has no name here; that is
        // not a failure, there is simply nothing to say.
        val document = fingerprints.of(book)?.partialMd5 ?: return BookOutcome()

        val remote = when (val asked = client.getProgress(account.baseUrl, account.credentials, document)) {
            is RemoteResult.Failed -> return BookOutcome(
                failure = asked.reason,
                unreachable = asked.unreachable,
            )
            is RemoteResult.Ok -> asked.value
        }
        if (remote != null) {
            forAccount(account) {
                peerStateDao.persistPending(
                    bookUrl = book.url,
                    peerId = account.peerId,
                    progression = remote.percentage,
                    status = ReadingStatus.forProgression(remote.percentage).wireName,
                    remoteUpdatedAt = remote.timestamp,
                )
            }
        }

        val stored = progressDao.get(book.url)
        val state = peerStateDao.get(book.url, account.peerId)
        val agreed = state?.isDirty(stored?.localRevision ?: 0)
            ?: ((stored?.localRevision ?: 0) > 0)

        // A server that answers with nothing about a book it has already
        // agreed a position for has lost the record: reset, evicted,
        // restored from an older backup. Left alone, this device would
        // sit on a position the server does not have until the reader
        // happened to turn a page, and every other device would pull
        // nothing meanwhile.
        //
        // That is precisely what dirty means — something here the server
        // lacks — so it is said that way, and `reconcileReadingState`
        // pushes for the same reason it pushes anything else.
        val forgotten = remote == null &&
            state?.hasPending != true &&
            state?.baseline() != null &&
            stored != null
        val dirty = agreed || forgotten

        if (!needsReconciling(remote != null, state?.hasPending == true, dirty)) {
            return BookOutcome()
        }

        val decision = reconcileReadingState(
            local = stored?.asReadingState(),
            remote = state?.pendingState(),
            baseline = state?.baseline(),
            localDirty = dirty,
            localUnreadOverride = stored?.override == FinishedOverride.UNREAD,
        )
        return act(account, identity, book, document, stored, state, decision)
    }

    private suspend fun act(
        account: Account,
        identity: DeviceIdentity,
        book: Book,
        document: String,
        stored: ReadingProgress?,
        state: SyncPeerState?,
        decision: SyncDecision,
    ): BookOutcome {
        val at = now()
        when (decision) {
            SyncDecision.InSync -> {
                // Agreeing is worth writing down: it is what later tells
                // a deliberate reread apart from the other device having
                // moved on.
                forAccount(account) {
                    peerStateDao.settle(
                        bookUrl = book.url,
                        peerId = account.peerId,
                        ackedRevision = stored?.localRevision ?: 0,
                        progression = stored?.totalProgression,
                        status = stored?.statusOrDerived()?.wireName,
                        now = at,
                    )
                }
                return BookOutcome()
            }

            is SyncDecision.Pull -> {
                val progression = decision.state.progression ?: return BookOutcome()
                val expected = stored?.localRevision ?: 0
                var applied = false
                forAccount(account) {
                    applied = progressDao.applyUnattendedPeerPull(
                        bookUrl = book.url,
                        expectedRevision = expected,
                        progression = progression,
                        status = decision.state.status.wireName,
                        now = at,
                        remoteUpdatedAt = state?.pendingUpdatedAt,
                    )
                    if (applied) {
                        peerStateDao.settle(
                            bookUrl = book.url,
                            peerId = account.peerId,
                            ackedRevision = expected + 1,
                            progression = progression,
                            status = decision.state.status.wireName,
                            now = at,
                        )
                    }
                }
                if (!applied) {
                    // A page was turned here while this was being
                    // decided: a disagreement rather than a handover.
                    // What the server said stays on disk.
                    return BookOutcome()
                }
                finishedState.refreshFromProgress(book.url)
                return BookOutcome(moved = SyncMove.PULLED)
            }

            is SyncDecision.AdoptStatus -> {
                var adopted = false
                forAccount(account) {
                    adopted = progressDao.adoptPeerStatus(book.url, decision.status.wireName, at)
                    if (adopted) peerStateDao.clearPending(book.url, account.peerId)
                }
                if (adopted) finishedState.refreshFromProgress(book.url)
                return BookOutcome(moved = if (adopted) SyncMove.PULLED else SyncMove.UNRESOLVED)
            }

            is SyncDecision.Conflict -> {
                // Preserve both, choose neither: the remote state is
                // already on disk, and the reader is the only one who
                // knows which device they last read on.
                Log.i(TAG, "Both sides moved for a book; leaving it to be asked about")
                return BookOutcome(moved = SyncMove.UNRESOLVED)
            }

            is SyncDecision.Push -> {
                val revision = stored?.localRevision ?: return BookOutcome()
                val progression = decision.state.progression ?: return BookOutcome()
                val sent = client.putProgress(
                    rootUrl = account.baseUrl,
                    credentials = account.credentials,
                    document = document,
                    percentage = progression,
                    device = identity.name,
                    deviceId = identity.id,
                    // From the stored row, never from the clock, so a
                    // retry says the same thing it said the first time.
                    timestampMs = stored.updatedAt,
                )
                return when (sent) {
                    is RemoteResult.Failed -> BookOutcome(
                        failure = sent.reason,
                        unreachable = sent.unreachable,
                    )
                    is RemoteResult.Ok -> {
                        var acked = false
                        forAccount(account) {
                            peerStateDao.settle(
                                bookUrl = book.url,
                                peerId = account.peerId,
                                // Exactly the revision that was compared:
                                // a page turned since was never weighed
                                // against the partner, so the book stays
                                // owed rather than quietly settled.
                                ackedRevision = revision,
                                progression = progression,
                                status = decision.state.status.wireName,
                                now = now(),
                            )
                            acked = true
                        }
                        BookOutcome(moved = if (acked) SyncMove.PUSHED else null)
                    }
                }
            }
        }
    }

    /**
     * Writes only while [account] is still the paired partner, in one
     * go. The request already left — that side effect is the network's —
     * but nothing lands against an account that was replaced while it
     * was in the air.
     */
    private suspend fun forAccount(account: Account, work: suspend () -> Unit) {
        inTransaction {
            if (kosyncDao.get()?.accountKey == account.peerId) work()
        }
    }

    /**
     * Which books this pairing is responsible for.
     *
     * One function, because the two questions it answers used to be
     * asked in two places — a DAO query that fetched the possibilities
     * and a predicate that narrowed them — and a query asking for a
     * `remote_uuid` had already thrown away every sideloaded book
     * before any predicate could speak for it.
     *
     * With a catalog beside it, the pairing covers that catalog's
     * books: what was downloaded from the connected server. With no
     * catalog at all — a Custom connection that is only a sync address
     * — it covers what is on the phone, which is the only library there
     * is.
     *
     * Named [book] narrows to that one row rather than filtering the
     * whole set down to it. kosync has no list endpoint, so syncing a
     * single book never had a reason to read the shelf.
     */
    private suspend fun candidates(book: String?): List<Book> {
        val catalogued = hasCatalog()
        val found = if (book == null) {
            if (catalogued) bookDao.allRemote() else bookDao.allOpenable()
        } else {
            listOfNotNull(bookDao.getByUrl(book)).filter { it.openableUrl != null }
        }
        return if (catalogued) found.filter { it.isCandidate } else found
    }


    /**
     * Whether kosync can speak about this book at all: it came from the
     * connected catalog server and its bytes are on this device to hash.
     *
     * The URL check is what holds the confirmed scope: a locally added
     * book *adopted* after an upload to liseur-sync also carries a
     * `remoteUuid`, but keeps its own local `url` by design — its
     * positions already travel natively, and kosync leaves it alone.
     */
    private val Book.isCandidate: Boolean
        get() = remoteUuid != null && openableUrl != null && ServerKind.isRemoteUrl(url)

    private fun ReadingProgress.statusOrDerived(): ReadingStatus =
        status?.let { ReadingStatus.fromWire(it) }
            ?: readingStatusFor(totalProgression, override)

    private fun ReadingProgress.asReadingState() = ReadingState(
        progression = totalProgression,
        status = statusOrDerived(),
        updatedAt = updatedAt,
    )

    private fun SyncPeerState.pendingState(): ReadingState? {
        if (!hasPending) return null
        return ReadingState(
            progression = pendingProgression,
            status = ReadingStatus.fromWire(pendingStatus),
            updatedAt = pendingUpdatedAt ?: 0L,
        )
    }

    private fun SyncPeerState.baseline(): ReadingBaseline? {
        if (agreedProgression == null && agreedStatus == null) return null
        return ReadingBaseline(
            progression = agreedProgression,
            status = ReadingStatus.fromWire(agreedStatus),
        )
    }

    private companion object {
        const val TAG = "kosync"
    }
}
