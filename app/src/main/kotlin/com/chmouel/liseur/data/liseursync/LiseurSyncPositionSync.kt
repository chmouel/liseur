package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.db.SyncAccountDao
import com.chmouel.liseur.data.db.SyncPeerState
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ResolveOutcome
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
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps reading positions in step with a liseur-sync server.
 *
 * The shape is different from a catalog server's. calibre-web and Komga
 * each hold one current position per book and answer "where am I"; this
 * one holds an append-only log and answers "what has happened since
 * `seq`". That is what lets several devices talk to it without any of
 * them being authoritative, and it is why the cursor is the only piece
 * of state that must never be wrong: everything else can be asked for
 * again, but reading that arrived while the cursor moved past it is
 * gone.
 *
 * So the rule throughout is that the cursor advances in the same
 * transaction that writes what the page contained, and never before.
 *
 * The reconciliation itself is `reconcileReadingState`, unchanged and
 * shared with the catalog servers. What is decided about a reader's
 * place is subtle enough to be worth having in exactly one place, and a
 * third kind of server is not a third set of rules.
 */
class LiseurSyncPositionSync(
    private val accountDao: SyncAccountDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val peerStateDao: SyncPeerStateDao,
    private val identityDao: WorkIdentityDao,
    private val sessionDao: ReadingSessionDao,
    private val works: WorkResolver,
    private val finishedState: FinishedState,
    private val reporting: SyncReporting = SyncReporting(),
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : PeerPositionSync {

    override val peerId: String get() = PeerPositionSync.LISEUR_SYNC

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = run(book = null)

    override suspend fun syncBook(bookUrl: String): SyncOutcome = run(book = bookUrl)

    override suspend fun canSync(bookUrl: String): Boolean {
        val account = accountDao.get() ?: return false
        if (account.credentials == null) return false
        // Every book can, in principle: this server holds no files and
        // does not care where a book came from. That is the whole reason
        // it exists, so the only question is whether the book is here.
        return bookDao.getByUrl(bookUrl) != null
    }

    /**
     * Asks the server outright where it thinks the reader is in one book,
     * and reports both positions without acting on either.
     *
     * The server's answer is written down before returning, exactly as
     * the catalog peers do. Acting on the answer goes through
     * `takeRemotePosition`/`keepLocalPosition`, and those are only ever
     * routed to a peer that has a disagreement on disk — an answer that
     * was merely returned would make the choice that follows a no-op.
     */
    override suspend fun previewBook(bookUrl: String): PreviewOutcome {
        val account = accountDao.get() ?: return PreviewOutcome.NotSynced
        val credentials = account.credentials ?: return PreviewOutcome.NotSynced
        val book = bookDao.getByUrl(bookUrl) ?: return PreviewOutcome.NotSynced
        val alias = works.cached(book, account.peerId) ?: return PreviewOutcome.NotSynced

        val head = try {
            latestOp(account.baseUrl, credentials, alias.workId)
        } catch (e: IOException) {
            return PreviewOutcome.Failed(reasonFor(e))
        } ?: return PreviewOutcome.NotSynced

        if (head.deviceId == null || head.deviceId != account.deviceId) {
            forAccount(account) { land(account, bookUrl, head) }
        }

        val stored = progressDao.get(bookUrl)
        return PreviewOutcome.Ready(
            SyncPreview(
                local = stored?.totalProgression,
                remote = head.progression,
                remoteAt = head.clientTs.takeIf { it > 0 },
            ),
        )
    }

    override suspend fun preservedConflict(bookUrl: String): SyncPreview? {
        val account = accountDao.get() ?: return null
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return null
        if (!state.hasPending) return null
        val there = state.pendingProgression ?: return null
        return SyncPreview(
            local = progressDao.get(bookUrl)?.totalProgression,
            remote = there,
            remoteAt = state.remoteUpdatedAt?.takeIf { it > 0 },
        ).takeIf { !it.agrees }
    }

    /**
     * Takes the position the server reported, because someone said to.
     *
     * The locator the other device recorded comes with it, which is what
     * this server can do that calibre-web cannot: the book reopens on
     * the word it was left on rather than at roughly the right place.
     */
    override suspend fun takeRemotePosition(bookUrl: String, atRevision: Long): ResolveOutcome {
        val account = accountDao.get() ?: return ResolveOutcome.Done
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return ResolveOutcome.Done
        val progression = state.pendingProgression ?: return ResolveOutcome.Done

        val locator = account.credentials?.let { credentials ->
            val alias = bookDao.getByUrl(bookUrl)?.let { works.cached(it, account.peerId) }
            alias?.let {
                runCatching { latestOp(account.baseUrl, credentials, it.workId) }
                    .getOrNull()?.locatorJson
            }
        }

        var applied = false
        val status = ReadingStatus.fromWire(state.pendingStatus)
        inTransaction {
            applied = progressDao.applyPeerPull(
                bookUrl = bookUrl,
                expectedRevision = atRevision,
                progression = progression,
                status = status.wireName,
                now = now(),
                locatorJson = locator,
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
     * Keeps what is here and stops the server's answer being offered
     * again.
     *
     * Nothing is sent from here. Clearing the disagreement leaves the
     * book dirty, so the next run pushes the position that is actually
     * on the device — including any page turned while the question was
     * on screen.
     */
    override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome {
        val account = accountDao.get() ?: return ResolveOutcome.Done
        peerStateDao.clearPending(bookUrl, account.peerId)
        return ResolveOutcome.Done
    }

    override suspend fun refreshUnresolved() {
        val account = accountDao.get() ?: return
        reporting.reportUnresolved(peerStateDao.countPending(account.peerId), peerId)
    }

    override suspend fun identity(): SyncIdentity? {
        val account = accountDao.get() ?: return null
        // Nothing is ever stranded here. This server holds no catalog, so
        // a book is never bound to it the way a downloaded book is bound
        // to the account that fetched it.
        return SyncIdentity(login = account.username, strandedBooks = 0)
    }

    // -- The run ----------------------------------------------------------

    private suspend fun run(book: String?): SyncOutcome {
        val account = accountDao.get() ?: run {
            reporting.report(PositionSyncStatus.Idle, peerId)
            return SyncOutcome.NotApplicable
        }
        val credentials = account.credentials ?: run {
            // The token cannot be read back: a database restored onto
            // another phone arrives with ciphertext this Keystore cannot
            // open, and there is nothing to do about it here.
            reporting.report(PositionSyncStatus.Unavailable, peerId)
            return SyncOutcome.NotApplicable
        }

        reporting.report(PositionSyncStatus.Syncing, peerId)
        val books = if (book == null) {
            bookDao.allOnce()
        } else {
            listOfNotNull(bookDao.getByUrl(book))
        }

        var firstFailure: SyncFailure? = null

        // Names first: an op is about a work id, so a book with no name
        // can neither receive what arrived nor send what is owed.
        val named = name(account, credentials, books, single = book != null)
        firstFailure = firstFailure ?: named.failure

        val pull = pull(account, credentials)
        firstFailure = firstFailure ?: pull

        var pulled = 0
        val pushes = mutableListOf<PendingPush>()
        for (candidate in named.aliases) {
            when (val outcome = reconcile(account, candidate)) {
                is Reconciled.Pulled -> pulled++
                is Reconciled.Owed -> pushes += outcome.push
                Reconciled.Nothing -> Unit
            }
        }

        val pushed = push(account, credentials, pushes) { firstFailure = firstFailure ?: it }

        // After the positions, because where the reader is now matters
        // more than how long they took to get there, and a run cut short
        // by a dead network should have spent what it had on the former.
        uploadSessions(account, credentials) { firstFailure = firstFailure ?: it }

        val at = now()
        reporting.report(
            SyncReport(
                at = at,
                pulled = pulled,
                pushed = pushed,
                // Counted from disk: a disagreement outlives the run that
                // found it, and a restart must not make it look settled.
                unresolved = peerStateDao.countPending(account.peerId),
            ),
            peerId,
        )

        return if (firstFailure == null) {
            forAccount(account) { accountDao.setSyncedAt(at) }
            reporting.report(PositionSyncStatus.Synced(at), peerId)
            SyncOutcome.Success
        } else {
            Log.i(TAG, "Some positions did not settle: ${firstFailure.label}")
            reporting.report(PositionSyncStatus.Failed(firstFailure), peerId)
            if (pulled > 0 || pushed > 0) {
                SyncOutcome.Partial(firstFailure)
            } else {
                SyncOutcome.Failure(firstFailure)
            }
        }
    }

    /** Books with a name on this server, and whatever stopped the rest. */
    private data class Named(
        val aliases: List<Pair<Book, WorkAlias>>,
        val failure: SyncFailure?,
    )

    /**
     * Makes sure the books of interest have a name on this server.
     *
     * Resolving costs a request and, the first time, reading the whole
     * file to hash it. So a full run resolves only a handful of unnamed
     * books each time, newest reading first, and the rest catch up over
     * the following runs — whereas asking about one book on purpose
     * resolves that book whatever it costs, because somebody is waiting.
     */
    private suspend fun name(
        account: SyncAccount,
        credentials: RemoteCredentials,
        books: List<Book>,
        single: Boolean,
    ): Named {
        val known = identityDao.aliasesFor(account.peerId).associateBy { it.bookUrl }
        val out = mutableListOf<Pair<Book, WorkAlias>>()
        var failure: SyncFailure? = null
        var budget = if (single) books.size else MAX_RESOLVES_PER_RUN

        val ordered = books.sortedByDescending { it.lastOpenedAt ?: 0 }
        for (candidate in ordered) {
            val cached = known[candidate.url]
            if (cached != null && cached.usable) {
                var alias = cached
                // A name that predates the catalog-id identifier owes
                // the server one re-resolve, so the next fresh install
                // can match on the catalog entry instead of asking the
                // reader. Failing is fine: the cached name still works,
                // and the debt stands until it is paid.
                if (works.owesSource(cached, candidate) && budget > 0) {
                    budget--
                    val refreshed =
                        works.resolve(candidate, account.peerId, account.baseUrl, credentials)
                    if (refreshed is WorkResolution.Named) alias = refreshed.alias
                }
                out += candidate to alias
                // A name that arrived without the one-off question —
                // a doubtful match confirmed by hand — still owes
                // it: whatever the server heard before the name was
                // usable is behind the cursor.
                if (!alias.seeded && budget > 0) {
                    budget--
                    seed(account, credentials, candidate, alias)
                }
                continue
            }
            // A guess made while the book was catalog-only can be
            // settled by the file, now that there is one, without
            // troubling the reader; any other unusable alias has asked
            // its question and waits for the answer.
            if (cached != null && !works.strengthenable(cached, candidate)) continue
            if (budget <= 0) continue
            budget--
            when (
                val resolved =
                    works.resolve(candidate, account.peerId, account.baseUrl, credentials)
            ) {
                is WorkResolution.Named -> {
                    out += candidate to resolved.alias
                    // Everything that happened to this book before it had
                    // a name is behind the cursor, so it is asked for
                    // once, directly.
                    seed(account, credentials, candidate, resolved.alias)
                }

                is WorkResolution.NeedsConfirming, is WorkResolution.Ambiguous -> Unit
                is WorkResolution.Unresolved ->
                    failure = failure ?: resolved.cause?.let(::reasonFor)
            }
        }
        return Named(out, failure)
    }

    /**
     * Fetches where a newly named book stands, once.
     *
     * Everything the server knows about it happened before this device
     * had a name for it, which means it sits behind the cursor and the
     * ordinary delta pull will never mention it. Without this, a book
     * read on another phone and then added here would stay at page one
     * until somebody happened to read it on the other phone again.
     */
    private suspend fun seed(
        account: SyncAccount,
        credentials: RemoteCredentials,
        book: Book,
        alias: WorkAlias,
    ) {
        val head = try {
            latestOp(account.baseUrl, credentials, alias.workId)
        } catch (e: IOException) {
            // Not marked seeded: the question was asked and never
            // answered, so it is still owed.
            Log.i(TAG, "Could not fetch a newly named book's position", e)
            return
        }
        forAccount(account) {
            head?.let { land(account, book.url, it) }
            // An empty answer is still an answer: the server has heard
            // nothing about this book, and there is nothing to recover.
            identityDao.markSeeded(book.url, account.peerId)
        }
    }

    /**
     * Reads everything that has happened since the cursor.
     *
     * Each page is written and the cursor advanced in one transaction:
     * a cursor that moved past a page whose contents were lost is
     * reading nobody will ever see again, and unlike everything else
     * here it cannot be asked for a second time.
     */
    private suspend fun pull(account: SyncAccount, credentials: RemoteCredentials): SyncFailure? {
        var cursor = account.cursorSeq
        var guard = MAX_PAGES
        while (guard-- > 0) {
            val page = try {
                http.get(
                    LiseurSyncApi.changes(account.baseUrl, since = cursor, limit = PAGE),
                    credentials,
                    expected = setOf(LiseurSyncHttp.GONE),
                )
            } catch (gone: LiseurSyncRejection) {
                return resync(account, credentials)
            } catch (e: IOException) {
                Log.i(TAG, "Could not read what changed", e)
                return reasonFor(e)
            }

            val ops = ops(page.optJSONArray("ops"))
            val highWater = page.optLong("high_water", cursor)
            val next = ops.maxOfOrNull { it.seq }?.coerceAtLeast(cursor) ?: highWater

            apply(account, ops, next)
            cursor = next

            if (!page.optBoolean("has_more", false)) return null
        }
        // A server that always says there is more would otherwise keep
        // this run going forever. Stopping is safe: the cursor is
        // durable and the next run carries on from it.
        Log.i(TAG, "Stopped reading changes after $MAX_PAGES pages")
        return null
    }

    /**
     * Rebuilds from the server's snapshot after the cursor fell too far
     * behind.
     *
     * The server compacts its log, and a device that has been away long
     * enough is told its cursor is worthless rather than being handed a
     * partial history. The snapshot is the newest position per book and
     * per device, which is exactly the baseline to start again from.
     */
    private suspend fun resync(
        account: SyncAccount,
        credentials: RemoteCredentials,
    ): SyncFailure? {
        Log.i(TAG, "The cursor fell behind the server's horizon; starting from a snapshot")
        val snapshot = try {
            http.get(LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.HEADS), credentials)
        } catch (e: IOException) {
            return reasonFor(e)
        }
        apply(account, ops(snapshot.optJSONArray("ops")), snapshot.optLong("snapshot_seq"))
        return null
    }

    /** Lands a page and moves the cursor, together or not at all. */
    private suspend fun apply(account: SyncAccount, ops: List<SyncOp>, cursor: Long) {
        val byWork = identityDao.aliasesFor(account.peerId).associateBy { it.workId }
        inTransaction {
            if (accountDao.get()?.peerId != account.peerId) return@inTransaction
            for (op in ops) {
                if (op.deviceId != null && op.deviceId == account.deviceId) continue
                val bookUrl = byWork[op.workId]?.takeIf { it.usable }?.bookUrl ?: continue
                land(account, bookUrl, op)
            }
            accountDao.setCursor(cursor)
        }
    }

    private suspend fun land(account: SyncAccount, bookUrl: String, op: SyncOp) {
        peerStateDao.persistPending(
            bookUrl = bookUrl,
            peerId = account.peerId,
            progression = op.progression,
            status = readingStatusFor(op.progression).wireName,
            remoteUpdatedAt = op.clientTs.takeIf { it > 0 },
        )
        op.locatorJson?.let { pendingLocators[bookUrl] = it }
    }

    // -- Settling one book ------------------------------------------------

    private sealed interface Reconciled {
        data object Nothing : Reconciled
        data object Pulled : Reconciled
        data class Owed(val push: PendingPush) : Reconciled
    }

    /** A position this device owes the server, ready to be sent. */
    private data class PendingPush(
        val bookUrl: String,
        val alias: WorkAlias,
        val revision: Long,
        val op: SyncOp,
    )

    private suspend fun reconcile(
        account: SyncAccount,
        named: Pair<Book, WorkAlias>,
    ): Reconciled {
        val (book, alias) = named
        val stored = progressDao.get(book.url)
        val state = peerStateDao.get(book.url, account.peerId)
        val dirty = state?.isDirty(stored?.localRevision ?: 0)
            ?: ((stored?.localRevision ?: 0) > 0)

        if (!needsReconciling(false, state?.hasPending == true, dirty)) return Reconciled.Nothing

        val decision = reconcileReadingState(
            local = stored?.asReadingState(),
            remote = state?.pendingState(),
            baseline = state?.baseline(),
            localDirty = dirty,
            localUnreadOverride = stored?.override == FinishedOverride.UNREAD,
        )
        return act(account, book, alias, stored, state, decision)
    }

    private suspend fun act(
        account: SyncAccount,
        book: Book,
        alias: WorkAlias,
        stored: ReadingProgress?,
        state: SyncPeerState?,
        decision: SyncDecision,
    ): Reconciled {
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
                return Reconciled.Nothing
            }

            is SyncDecision.Pull -> {
                val progression = decision.state.progression ?: return Reconciled.Nothing
                val expected = stored?.localRevision ?: 0
                var applied = false
                forAccount(account) {
                    applied = progressDao.applyPeerPull(
                        bookUrl = book.url,
                        expectedRevision = expected,
                        progression = progression,
                        status = decision.state.status.wireName,
                        now = at,
                        locatorJson = pendingLocators.remove(book.url),
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
                    // decided, so it is a disagreement rather than a
                    // handover. What the server said stays on disk.
                    return Reconciled.Nothing
                }
                finishedState.refreshFromProgress(book.url)
                return Reconciled.Pulled
            }

            is SyncDecision.AdoptStatus -> {
                var adopted = false
                forAccount(account) {
                    adopted = progressDao.adoptPeerStatus(book.url, decision.status.wireName, at)
                    if (adopted) peerStateDao.clearPending(book.url, account.peerId)
                }
                if (adopted) finishedState.refreshFromProgress(book.url)
                return if (adopted) Reconciled.Pulled else Reconciled.Nothing
            }

            is SyncDecision.Conflict -> {
                Log.i(TAG, "Both sides moved for a book; leaving it to be asked about")
                return Reconciled.Nothing
            }

            is SyncDecision.Push -> {
                val revision = stored?.localRevision ?: return Reconciled.Nothing
                val progression = decision.state.progression ?: return Reconciled.Nothing
                return Reconciled.Owed(
                    PendingPush(
                        bookUrl = book.url,
                        alias = alias,
                        revision = revision,
                        op = SyncOp(
                            opId = SyncOps.opIdFor(account.deviceKey, alias.workId, revision),
                            workId = alias.workId,
                            editionSha = alias.editionSha,
                            // From the stored row, never from the clock:
                            // a retry has to produce the same payload or
                            // the server calls it a different op with a
                            // reused name.
                            clientTs = stored.updatedAt,
                            progression = progression.coerceIn(0.0, 1.0),
                            locatorJson = stored.locatorJson,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Sends everything owed, in batches the server will accept.
     *
     * `duplicate` counts as success, and is the ordinary answer to a run
     * that was cut off after the server had already stored the op. That
     * is the whole reason the ids are derived rather than drawn.
     */
    private suspend fun push(
        account: SyncAccount,
        credentials: RemoteCredentials,
        pushes: List<PendingPush>,
        onFailure: (SyncFailure) -> Unit,
    ): Int {
        var pushed = 0
        for (batch in pushes.chunked(SyncOps.MAX_BATCH)) {
            val answer = try {
                http.post(
                    LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.OPS),
                    credentials,
                    JSONObject().put(
                        "ops",
                        JSONArray().apply { batch.forEach { put(SyncOps.toJson(it.op)) } },
                    ),
                )
            } catch (e: IOException) {
                Log.i(TAG, "Could not send positions", e)
                onFailure(reasonFor(e))
                return pushed
            }

            val accepted = accepted(answer)
            for (item in batch) {
                if (item.op.opId !in accepted) {
                    // The server refused this one on its own terms —
                    // an id reused with a different payload, or an
                    // unknown work. Leaving the book dirty is right: the
                    // next run asks again with whatever it holds then.
                    Log.i(TAG, "The server would not take a position")
                    continue
                }
                pushed++
                forAccount(account) {
                    peerStateDao.settle(
                        bookUrl = item.bookUrl,
                        peerId = account.peerId,
                        ackedRevision = item.revision,
                        progression = item.op.progression,
                        status = readingStatusFor(item.op.progression).wireName,
                        now = now(),
                    )
                }
            }
        }
        return pushed
    }

    // -- Sessions ---------------------------------------------------------

    /**
     * Sends finished reading, once each.
     *
     * Only sessions for books this server already has a name for, and
     * only ones that know where in the book they happened; the rest
     * wait, which costs nothing because a session is a fact about the
     * past and is no less true next run.
     *
     * A refusal on the server's own terms — an id it already holds
     * carrying something else, or a payload it will not parse — is
     * marked done rather than retried. Neither will ever be accepted,
     * and a batch that can never succeed would sit at the head of the
     * queue forever and stop every later session behind it.
     */
    private suspend fun uploadSessions(
        account: SyncAccount,
        credentials: RemoteCredentials,
        onFailure: (SyncFailure) -> Unit,
    ) {
        val sessions = sessionDao.awaitingUpload(SessionUploads.MAX_BATCH)
        if (sessions.isEmpty()) return
        val byBook = identityDao.aliasesFor(account.peerId)
            .filter { it.usable }
            .associateBy { it.bookUrl }

        val sending = sessions.mapNotNull { session ->
            val alias = byBook[session.bookUrl] ?: return@mapNotNull null
            SessionUploads.toJson(
                session = session,
                deviceKey = account.deviceKey,
                workId = alias.workId,
                editionSha = alias.editionSha,
            )?.let { session.id to it }
        }
        if (sending.isEmpty()) return

        try {
            http.post(
                LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.SESSIONS),
                credentials,
                JSONObject().put(
                    "sessions",
                    JSONArray().apply { sending.forEach { put(it.second) } },
                ),
            )
        } catch (rejected: RemoteHttpFailure) {
            if (!rejected.reason.isFinal()) {
                Log.i(TAG, "Could not send reading sessions", rejected)
                onFailure(rejected.reason)
                return
            }
            Log.i(TAG, "The server will never take these sessions; not asking again")
        } catch (e: IOException) {
            Log.i(TAG, "Could not send reading sessions", e)
            onFailure(reasonFor(e))
            return
        }

        forAccount(account) { sessionDao.markUploaded(sending.map { it.first }, now()) }
    }

    /**
     * Whether asking again could ever produce a different answer.
     *
     * A malformed batch stays malformed, and an id the server already
     * holds under a different payload will never become free. Both are
     * settled by giving up on them rather than by trying harder.
     */
    private fun SyncFailure.isFinal(): Boolean =
        this is SyncFailure.Malformed ||
            (this is SyncFailure.ServerError && code in FINAL_CODES)

    private fun accepted(answer: JSONObject): Set<String> {
        val results = answer.optJSONArray("results") ?: return emptySet()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            item.optString("op_id").takeIf {
                it.isNotEmpty() && item.optString("status") in ACCEPTED
            }
        }.toSet()
    }

    // -- Odds and ends ----------------------------------------------------

    /** The newest thing anybody said about one book. */
    private suspend fun latestOp(
        baseUrl: String,
        credentials: RemoteCredentials,
        workId: String,
    ): SyncOp? = ops(
        http.get(LiseurSyncApi.positions(baseUrl, workId, limit = 1), credentials)
            .optJSONArray("ops"),
    ).firstOrNull()

    private fun ops(array: JSONArray?): List<SyncOp> =
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            array?.optJSONObject(index)?.let(SyncOps::fromJson)
        }

    /**
     * Runs a write only while this is still the connected account.
     *
     * Asking and writing in one transaction, so that disconnecting
     * halfway through a run cannot be undone by what the run had already
     * decided.
     */
    private suspend fun forAccount(account: SyncAccount, work: suspend () -> Unit) {
        inTransaction {
            if (accountDao.get()?.peerId == account.peerId) work()
        }
    }

    private fun reasonFor(error: IOException): SyncFailure =
        (error as? RemoteHttpFailure)?.reason ?: SyncFailure.Offline

    private fun ReadingProgress.asReadingState() = ReadingState(
        progression = totalProgression,
        status = statusOrDerived(),
        updatedAt = updatedAt,
    )

    private fun ReadingProgress.statusOrDerived(): ReadingStatus =
        status?.let(ReadingStatus::fromWire) ?: readingStatusFor(totalProgression, override)

    private fun SyncPeerState.pendingState(): ReadingState? {
        if (!hasPending) return null
        return ReadingState(
            progression = pendingProgression,
            status = ReadingStatus.fromWire(pendingStatus),
            updatedAt = pendingUpdatedAt ?: 0,
        )
    }

    private fun SyncPeerState.baseline(): ReadingBaseline? {
        if (agreedProgression == null && agreedStatus == null) return null
        return ReadingBaseline(
            progression = agreedProgression,
            status = ReadingStatus.fromWire(agreedStatus),
        )
    }

    /**
     * Locators from ops that have not been acted on yet.
     *
     * Held in memory rather than on disk on purpose. A locator is only
     * ever a nicety — the progression is what the two sides are compared
     * on, and a book pulled without one simply reopens at roughly the
     * right place instead of on the exact word. Giving every unsettled
     * op a column would be a schema change to make a good outcome
     * slightly better, and losing one costs nothing that a page turn
     * does not fix.
     */
    private val pendingLocators = mutableMapOf<String, String>()

    private companion object {
        /** Refusals no amount of retrying will turn into an acceptance. */
        val FINAL_CODES = setOf(400, 409, 422)

        const val TAG = "liseur-sync-positions"
        const val PAGE = 500
        const val MAX_PAGES = 200
        const val MAX_RESOLVES_PER_RUN = 25
        val ACCEPTED = setOf("applied", "duplicate")
    }
}
