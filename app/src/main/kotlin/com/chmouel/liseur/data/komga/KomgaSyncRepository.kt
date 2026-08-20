package com.chmouel.liseur.data.komga

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncMove
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncSnapshot
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncReport
import com.chmouel.liseur.data.remote.SyncReporting
import com.chmouel.liseur.data.remote.remoteCall
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingBaseline
import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.SyncDecision
import com.chmouel.liseur.domain.needsReconciling
import com.chmouel.liseur.domain.readingStatusFor
import com.chmouel.liseur.domain.reconcileReadingState
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import org.json.JSONException
import org.json.JSONObject

/** What one book's reconciliation did, and why it stopped if it did. */
private data class BookOutcome(
    val moved: SyncMove? = null,
    val failure: SyncFailure? = null,
)

/** The server's side of one book: where it is, and when it got there. */
private data class RemoteSide(
    val state: ReadingState,
    /** The place itself, when it was fetched. Null when it was inferred. */
    val locator: JSONObject? = null,
    /** Whether the server is where it was when both sides last agreed. */
    val unchanged: Boolean = false,
)

/**
 * Keeps reading positions in step with Komga.
 *
 * The reconciliation itself is not written here: `reconcileReadingState`
 * decides what should happen to a book, exactly as it does for
 * calibre-web, and this only fetches what it needs and carries out what
 * it says. That is deliberate — the rules about baselines, dirty rows
 * and who owns a position are the subtlest thing in the app and are
 * worth having in one place.
 *
 * What is different from calibre-web is what travels. Komga keeps a full
 * locator rather than a percentage, so a book pulled from another device
 * reopens on the right word rather than at roughly the right point. The
 * percentage is still what the two sides are compared on, because that
 * is the only thing they can both mean.
 *
 * There is no sync token and nothing destructive to protect, so nothing
 * Komga says has to be landed atomically: it can all be asked for again.
 * What does have to be atomic is the question of whose account is being
 * written for, because signing out during a run must not be undone by
 * what the run had already decided.
 */
class KomgaSyncRepository(
    private val serverDao: RemoteServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val finishedState: FinishedState,
    private val device: DeviceIdentityRepository,
    private val reporting: SyncReporting = SyncReporting(),
    private val catalog: KomgaCatalogClient = KomgaCatalogClient(),
    private val positions: KomgaProgressionClient = KomgaProgressionClient(),
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : PositionSync {

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome =
        run(book = null, snapshot = snapshot)

    override suspend fun syncBook(bookUrl: String): SyncOutcome = run(book = bookUrl)

    override suspend fun canSync(bookUrl: String): Boolean {
        serverDao.get() ?: return false
        return bookDao.getByUrl(bookUrl)?.remoteUuid != null
    }

    /**
     * Asks the server outright where it thinks the reader is in one book,
     * and reports both positions without acting on either.
     *
     * Deliberately not a reconciliation: someone has asked about this
     * book, so the answer is theirs to make. The server's answer is
     * written down before returning, so choosing later — or not choosing,
     * and coming back to it — works even if the app is killed in between.
     */
    override suspend fun previewBook(bookUrl: String): PreviewOutcome {
        val server = serverDao.get() ?: return PreviewOutcome.NotSynced
        val credentials = server.credentials ?: return PreviewOutcome.NotSynced
        val id = bookDao.getByUrl(bookUrl)?.remoteUuid ?: return PreviewOutcome.NotSynced

        val remote = when (val asked = fetchRemote(server, credentials, id)) {
            is RemoteResult.Failed -> return PreviewOutcome.Failed(asked.reason)
            is RemoteResult.Ok -> asked.value
        }
        val remoteLocatorJson = remote?.locator
            ?.let(KomgaLocator::toReadium)
            ?.toString()
        if (remote != null) {
            forAccount(server) {
                progressDao.persistPending(
                    bookUrl = bookUrl,
                    progression = remote.state.progression,
                    status = remote.state.status.wireName,
                    remoteUpdatedAt = remote.state.updatedAt,
                    account = server.accountKey,
                    now = System.currentTimeMillis(),
                    locatorJson = remoteLocatorJson,
                )
            }
        }
        val stored = progressDao.get(bookUrl)
        val exact = remoteLocatorJson?.takeIf(ExactLocatorAnchor::isExactJson)
        return PreviewOutcome.Ready(
            SyncPreview(
                local = stored?.totalProgression,
                remote = remote?.state?.progression,
                remoteAt = remote?.state?.updatedAt?.takeIf { it > 0 },
                excerpt = ExactLocatorAnchor.excerpt(exact),
                confidence = if (exact != null) {
                    ResumeConfidence.EXACT
                } else {
                    ResumeConfidence.APPROXIMATE
                },
                exactPositionAgreement = ExactLocatorAnchor.agreement(
                    stored?.locatorJson,
                    exact,
                ),
            ),
        )
    }

    /**
     * The unresolved disagreement an ordinary sync left behind, if there
     * is one, without asking the server anything.
     */
    override suspend fun preservedConflict(bookUrl: String): SyncPreview? {
        val server = serverDao.get() ?: return null
        val stored = progressDao.get(bookUrl) ?: return null
        val pending = stored.pendingStateFor(server.accountKey) ?: return null
        val there = pending.progression ?: return null
        return SyncPreview(
            local = stored.totalProgression,
            remote = there,
            remoteAt = stored.remoteUpdatedAt?.takeIf { it > 0 },
            excerpt = ExactLocatorAnchor.excerpt(stored.pendingLocatorJson),
            confidence = if (ExactLocatorAnchor.isExactJson(stored.pendingLocatorJson)) {
                ResumeConfidence.EXACT
            } else {
                ResumeConfidence.APPROXIMATE
            },
            exactPositionAgreement = ExactLocatorAnchor.agreement(
                stored.locatorJson,
                stored.pendingLocatorJson,
            ),
        ).takeIf { !it.agrees }
    }

    /**
     * Takes the position the server reported for one book, because
     * someone said to.
     *
     * The exact place is the one persisted with the pending percentage,
     * so a restart cannot pair the choice with a later server answer.
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
            locatorJson = stored.pendingLocatorJson.takeIf(ExactLocatorAnchor::isExactJson),
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
        val credentials = server.credentials ?: return ResolveOutcome.Done
        val id = bookDao.getByUrl(bookUrl)?.remoteUuid ?: return ResolveOutcome.Done
        // Read afresh, so a page turned while the question was open is
        // what gets sent rather than the position it was asked about.
        val stored = progressDao.get(bookUrl) ?: return ResolveOutcome.Done

        val outcome = apply(
            server = server,
            credentials = credentials,
            id = id,
            bookUrl = bookUrl,
            stored = stored,
            remote = null,
            decision = SyncDecision.Push(stored.asReadingState()),
        )
        return outcome.failure?.let { ResolveOutcome.Failed(it) } ?: ResolveOutcome.Done
    }

    override suspend fun refreshUnresolved() {
        val account = serverDao.get()?.accountKey ?: return
        reporting.reportUnresolved(progressDao.pendingFor(account).size)
    }

    override suspend fun identity(): SyncIdentity? {
        val server = serverDao.get() ?: return null
        return SyncIdentity(
            login = server.username.orEmpty(),
            strandedBooks = progressDao.ownedByOther(server.accountKey).size,
        )
    }

    private suspend fun run(book: String?, snapshot: SyncSnapshot? = null): SyncOutcome {
        val server = serverDao.get() ?: run {
            reporting.report(PositionSyncStatus.Idle)
            return SyncOutcome.NotApplicable
        }
        val credentials = server.credentials ?: run {
            reporting.report(PositionSyncStatus.Unavailable)
            return SyncOutcome.NotApplicable
        }

        reporting.report(PositionSyncStatus.Syncing)
        val books = bookDao.allRemote().filter { book == null || it.url == book }

        // One pass over the catalog says which books moved, because every
        // book carries its reading progress inline. Asking per book would
        // be a request each; asking for one book is a request either way.
        val progress = when (
            val listed = listProgress(server, credentials, books, snapshot.forThis(server))
        ) {
            is RemoteResult.Ok -> listed.value
            is RemoteResult.Failed -> {
                Log.i(TAG, "Could not read reading positions: ${listed.reason.label}")
                reporting.report(PositionSyncStatus.Failed(listed.reason))
                return SyncOutcome.Failure(listed.reason)
            }
        }

        var firstFailure: SyncFailure? = null
        var pulled = 0
        var pushed = 0
        for (candidate in books) {
            val outcome = reconcileBook(server, credentials, candidate, progress)
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
                unresolved = progressDao.pendingFor(server.accountKey).size,
            ),
        )
        return if (firstFailure == null) {
            // Only a run that settled everything counts as having synced,
            // and only ever for the account that is still connected: if it
            // went away while this ran, writing it back would sign the user
            // in again. A half-finished run leaves the old time standing,
            // so whatever is waiting on it knows to come back.
            inTransaction {
                if (serverDao.get()?.accountKey == server.accountKey) {
                    serverDao.setPositionSyncedAt(now)
                }
            }
            reporting.report(PositionSyncStatus.Synced(now))
            SyncOutcome.Success
        } else {
            Log.i(TAG, "Some positions did not settle: ${firstFailure.label}")
            reporting.report(PositionSyncStatus.Failed(firstFailure))
            SyncOutcome.Partial(firstFailure)
        }
    }

    /** Where the server says every book of interest has got to. */
    private suspend fun listProgress(
        server: RemoteServer,
        credentials: RemoteCredentials,
        books: List<Book>,
        reusable: KomgaCatalogSnapshot?,
    ): RemoteResult<Map<String, KomgaReadProgress>> = remoteCall {
        val single = books.singleOrNull()?.remoteUuid
        val listed = when {
            // The refresh that led here has just read this, progress and
            // all. Asking again would be the whole catalog over the wire
            // twice for one pull of the shelf.
            reusable != null -> reusable.books
            single != null -> listOf(catalog.book(server.baseUrl, credentials, single))
            else -> catalog.allKomgaBooks(server.baseUrl, credentials).books
        }
        listed.mapNotNull { entry ->
            entry.progress?.let { entry.book.remoteId to it }
        }.toMap()
    }

    /**
     * A snapshot this run may use: Komga's own, read for the account
     * that is still connected. Anyone else's is refused rather than
     * applied, since reading progress belongs to whoever it was read for.
     */
    private fun SyncSnapshot?.forThis(server: RemoteServer): KomgaCatalogSnapshot? {
        val offered = this ?: return null
        if (offered.accountKey != server.accountKey) {
            Log.i(TAG, "A catalog snapshot from another account was offered; asking again")
            return null
        }
        return offered.catalog as? KomgaCatalogSnapshot
    }

    /** Settles one book, and says what stopped it if anything did. */
    private suspend fun reconcileBook(
        server: RemoteServer,
        credentials: RemoteCredentials,
        book: Book,
        progress: Map<String, KomgaReadProgress>,
    ): BookOutcome {
        val id = book.remoteUuid ?: return BookOutcome()
        val account = server.accountKey
        var stored = progressDao.get(book.url)

        var remote = when (val side = remoteSide(server, credentials, id, progress[id], stored)) {
            is RemoteResult.Failed -> return BookOutcome(failure = side.reason)
            is RemoteResult.Ok -> side.value
        }

        // Neither side has moved since they last agreed: the server is
        // where it was, nothing has been read here, and nothing was left
        // unsettled. Writing the same numbers back would settle what is
        // already settled and have the whole library redraw for it.
        if (remote?.unchanged == true &&
            stored != null &&
            !stored.isDirty &&
            !stored.hasPending &&
            stored.override == FinishedOverride.NONE
        ) {
            return BookOutcome()
        }

        // Something unsettled from an earlier run is still the server's
        // word, and outlives the run that heard it.
        if (remote == null) {
            remote = stored?.pendingStateFor(account)?.let {
                RemoteSide(
                    state = it,
                    locator = stored.pendingLocatorJson?.let { json ->
                        runCatching { JSONObject(json) }.getOrNull()
                    },
                )
            }
        } else {
            forAccount(server) {
                progressDao.persistPending(
                    bookUrl = book.url,
                    progression = remote.state.progression,
                    status = remote.state.status.wireName,
                    remoteUpdatedAt = remote.state.updatedAt,
                    account = account,
                    now = System.currentTimeMillis(),
                    locatorJson = remote.locator?.let(KomgaLocator::toReadium)?.toString(),
                )
            }
            stored = progressDao.get(book.url)
        }

        val dirty = stored?.isDirty == true
        if (!needsReconciling(remote != null, stored?.hasPending == true, dirty)) {
            return BookOutcome()
        }

        val exactRemote = remote?.locator
            ?.let(KomgaLocator::toReadium)
            ?.toString()
            ?.takeIf(ExactLocatorAnchor::isExactJson)
        val decision = reconcileReadingState(
            local = stored?.asReadingState(),
            remote = remote?.state,
            baseline = stored?.baselineFor(account),
            localDirty = dirty,
            localUnreadOverride = stored?.override == FinishedOverride.UNREAD,
            exactPositionAgreement = ExactLocatorAnchor.agreement(
                stored?.locatorJson,
                exactRemote,
            ),
        )
        return apply(server, credentials, id, book.url, stored, remote, decision)
    }

    /**
     * What the server's position amounts to, fetching the locator only
     * when it is worth a request.
     *
     * A book whose `readDate` is the one already agreed with has not
     * moved on the server, whatever else has happened here, so its
     * position is already known and asking again would say the same
     * thing. Anything else is fetched.
     */
    private suspend fun remoteSide(
        server: RemoteServer,
        credentials: RemoteCredentials,
        id: String,
        progress: KomgaReadProgress?,
        stored: ReadingProgress?,
    ): RemoteResult<RemoteSide?> {
        if (progress == null) return RemoteResult.Ok(null)

        val unchanged = stored != null &&
            stored.agreedAccount == server.accountKey &&
            stored.remoteUpdatedAt != null &&
            stored.remoteUpdatedAt == progress.readDate
        if (unchanged) {
            return RemoteResult.Ok(
                RemoteSide(
                    ReadingState(
                        progression = stored.agreedProgression,
                        status = statusOf(progress, stored.agreedProgression),
                        updatedAt = progress.readDate,
                    ),
                    unchanged = true,
                ),
            )
        }
        return fetchRemote(server, credentials, id, progress)
    }

    /** The server's position for one book, asked for outright. */
    private suspend fun fetchRemote(
        server: RemoteServer,
        credentials: RemoteCredentials,
        id: String,
        known: KomgaReadProgress? = null,
    ): RemoteResult<RemoteSide?> {
        val locator = when (val read = positions.read(server.baseUrl, credentials, id)) {
            is RemoteResult.Failed -> return read
            is RemoteResult.Ok -> read.value
        } ?: return RemoteResult.Ok(
            // No locator, but the catalog may still say the book was
            // finished — marked read in Komga's own interface, say,
            // without anyone ever opening it. That is worth adopting on
            // its own, so it is reported as a status with no position.
            known?.takeIf { it.completed }?.let {
                RemoteSide(
                    state = ReadingState(
                        progression = null,
                        status = statusOf(it, null),
                        updatedAt = it.readDate ?: 0L,
                    ),
                    locator = null,
                )
            },
        )

        // The catalog's readDate is the only timestamp Komga reports for
        // a position that can be trusted; the one beside the locator
        // comes back with an offset applied twice. When it has not been
        // fetched, one is asked for rather than guessed at.
        val progress = known ?: when (
            val book = remoteCall { catalog.book(server.baseUrl, credentials, id) }
        ) {
            is RemoteResult.Failed -> return book
            is RemoteResult.Ok -> book.value.progress
        }

        val progression = KomgaLocator.totalProgression(locator)
        return RemoteResult.Ok(
            RemoteSide(
                state = ReadingState(
                    progression = progression,
                    status = statusOf(progress, progression),
                    updatedAt = progress?.readDate ?: 0L,
                ),
                locator = locator,
            ),
        )
    }

    private suspend fun apply(
        server: RemoteServer,
        credentials: RemoteCredentials,
        id: String,
        bookUrl: String,
        stored: ReadingProgress?,
        remote: RemoteSide?,
        decision: SyncDecision,
    ): BookOutcome {
        // A push talks to the server and cannot be held inside a
        // transaction; everything else is a write for an account that
        // may no longer be connected, so it is checked and done at once.
        if (decision is SyncDecision.Push) {
            return if (stillConnected(server)) {
                push(server, credentials, id, bookUrl, stored, remote, decision.state)
            } else {
                BookOutcome()
            }
        }
        var outcome = BookOutcome()
        inTransaction {
            if (serverDao.get()?.accountKey == server.accountKey) {
                outcome = write(server, bookUrl, stored, remote, decision)
            }
        }
        return outcome
    }

    private suspend fun stillConnected(server: RemoteServer): Boolean =
        serverDao.get()?.accountKey == server.accountKey

    /**
     * Writes only while [server] is still the connected account, asking
     * and writing in one go so that nothing can sign out in between.
     */
    private suspend fun forAccount(server: RemoteServer, work: suspend () -> Unit) {
        inTransaction {
            if (serverDao.get()?.accountKey == server.accountKey) work()
        }
    }

    private suspend fun write(
        server: RemoteServer,
        bookUrl: String,
        stored: ReadingProgress?,
        remote: RemoteSide?,
        decision: SyncDecision,
    ): BookOutcome {
        val account = server.accountKey
        val now = System.currentTimeMillis()
        return when (decision) {
            SyncDecision.InSync -> {
                // Agreeing is worth recording: it is what later tells a
                // reread apart from the other device having moved on.
                if (stored != null) {
                    progressDao.settleAgreed(
                        bookUrl = bookUrl,
                        inspectedRevision = stored.localRevision,
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
                        // This is what Komga can do that calibre-web
                        // cannot: reopen the book exactly where the other
                        // device left it rather than at a percentage.
                        locatorJson = remote?.locator
                            ?.let(KomgaLocator::toReadium)
                            ?.toString()
                            ?.takeIf(ExactLocatorAnchor::isExactJson),
                    )
                }
                finishedState.refreshFromProgress(bookUrl)
                BookOutcome(moved = SyncMove.PULLED)
            }

            is SyncDecision.Push -> BookOutcome()

            is SyncDecision.AdoptStatus -> {
                // Refused means someone here said outright that this book
                // is read, or is not. That outranks a flag on the server,
                // and the disagreement stays on disk to be settled.
                val adopted =
                    progressDao.adoptStatus(bookUrl, decision.status.wireName, account, now)
                if (adopted) finishedState.refreshFromProgress(bookUrl)
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
     * Sends this device's position, and says so plainly when the book is
     * finished.
     *
     * Komga works out for itself whether a position means the book is
     * done, but it can only do that from a place in the text. Someone who
     * marks a book read without reading to the end has said something the
     * position cannot express, so it is said separately.
     */
    private suspend fun push(
        server: RemoteServer,
        credentials: RemoteCredentials,
        id: String,
        bookUrl: String,
        stored: ReadingProgress?,
        remote: RemoteSide?,
        state: ReadingState,
    ): BookOutcome {
        val sent = stored?.localRevision ?: 0
        val finished = state.status == ReadingStatus.FINISHED
        // Komga has no way to say "not finished" other than forgetting the
        // book, so an explicit mark-unread starts by wiping what it holds.
        // Anything pushed after this lands on a clean slate.
        val unread = !finished && stored?.override == FinishedOverride.UNREAD
        if (unread) {
            when (val cleared = positions.clear(server.baseUrl, credentials, id)) {
                is RemoteResult.Failed -> return BookOutcome(failure = cleared.reason)
                is RemoteResult.Ok -> Unit
            }
        }

        // Komga refuses anything not strictly newer than what it holds,
        // so a device whose clock is behind the server's would never be
        // able to push at all.
        val modifiedAt = maxOf(System.currentTimeMillis(), (remote?.state?.updatedAt ?: 0L) + 1)

        val placed = stored?.locatorJson?.let { json ->
            val locator = try {
                JSONObject(json)
            } catch (e: JSONException) {
                Log.w(TAG, "Stored position is not a locator", e)
                return@let null
            }
            positions.push(server.baseUrl, credentials, id, locator, modifiedAt, device.current())
        }

        when (placed) {
            is RemoteResult.Failed -> return BookOutcome(failure = placed.reason)
            is RemoteResult.Ok -> when (placed.value) {
                // The server already holds something at least as new.
                // Overwriting it would be wrong, so the row stays dirty
                // and the next run pulls and reconciles instead.
                PushOutcome.Stale -> {
                    Log.i(TAG, "Server holds a newer position; leaving this one to be reconciled")
                    return BookOutcome(moved = SyncMove.UNRESOLVED)
                }
                // Nowhere in the book matched, and the nearest place the
                // server knows could not be found either. Saying the book
                // is finished still works, and is worth doing.
                PushOutcome.Unplaceable -> if (!finished && !unread) {
                    Log.i(TAG, "Komga would not place this position")
                    return BookOutcome(moved = SyncMove.UNRESOLVED)
                }
                PushOutcome.Accepted -> Unit
            }
            null -> if (!finished && !unread) return BookOutcome()
        }

        if (finished) {
            when (val marked = positions.markCompleted(server.baseUrl, credentials, id)) {
                is RemoteResult.Failed -> return BookOutcome(failure = marked.reason)
                is RemoteResult.Ok -> Unit
            }
        }

        // The pushing is over and the account may have gone in the
        // meantime; what is written down about it has to belong to
        // whoever is connected now, or to no one.
        var acked = false
        forAccount(server) {
            progressDao.ackPush(
                bookUrl = bookUrl,
                sentRevision = sent,
                progression = state.progression,
                status = state.status.wireName,
                account = server.accountKey,
                now = System.currentTimeMillis(),
            )
            acked = true
        }
        return BookOutcome(moved = if (acked) SyncMove.PUSHED else null)
    }

    /**
     * What Komga's flag means in this app's vocabulary.
     *
     * Komga only says whether a book is finished. Having a position at
     * all means it has been started, and having none means it has not.
     */
    private fun statusOf(progress: KomgaReadProgress?, progression: Double?): ReadingStatus = when {
        progress?.completed == true -> ReadingStatus.FINISHED
        progression != null -> ReadingStatus.READING
        else -> ReadingStatus.READY_TO_READ
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

    private companion object {
        const val TAG = "KomgaSync"
    }
}
