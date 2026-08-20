package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.NetworkAvailability
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.SyncPeerState
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncIdentity
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
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps reading positions in step with a liseur-sync server.
 *
 * The shape is different from the other catalog servers'. calibre-web
 * and Komga each hold one current position per book and answer "where
 * am I"; this one holds an append-only log and answers "what has
 * happened since `seq`". That is what lets several devices talk to it
 * without any of them being authoritative, and it is why the cursor is
 * the only piece of state that must never be wrong: everything else can
 * be asked for again, but reading that arrived while the cursor moved
 * past it is gone.
 *
 * So the rule throughout is that the cursor advances in the same
 * transaction that writes what the page contained, and never before.
 *
 * The reconciliation itself is `reconcileReadingState`, unchanged and
 * shared with the other servers. What is decided about a reader's place
 * is subtle enough to be worth having in exactly one place, and a third
 * kind of server is not a third set of rules.
 */
class LiseurSyncPositionSync(
    private val serverDao: RemoteServerDao,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val peerStateDao: SyncPeerStateDao,
    private val identityDao: WorkIdentityDao,
    private val sessionDao: ReadingSessionDao,
    private val works: WorkResolver,
    private val deviceKey: suspend () -> String,
    private val finishedState: FinishedState,
    private val reporting: SyncReporting = SyncReporting(),
    private val networkAvailability: NetworkAvailability = NetworkAvailability { true },
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : PositionSync {

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = run(book = null)

    override suspend fun syncBook(bookUrl: String): SyncOutcome = run(book = bookUrl)

    override suspend fun canSync(bookUrl: String): Boolean {
        val account = account() ?: return false
        // Every book can, in principle: the server resolves a file by
        // its hashes and does not care where the file came from, which
        // is what makes it the one account a side-loaded book can sync
        // against too. The only question is whether the book is here.
        return bookDao.getByUrl(bookUrl) != null
    }

    /**
     * Asks the server outright where it thinks the reader is in one book,
     * and reports both positions without acting on either.
     *
     * The server's answer is written down before returning. Acting on it
     * goes through `takeRemotePosition`/`keepLocalPosition`, which are
     * only ever routed here with a disagreement on disk — an answer that
     * was merely returned would make the choice that follows a no-op.
     */
    override suspend fun previewBook(bookUrl: String): PreviewOutcome {
        val account = account() ?: return PreviewOutcome.NotSynced
        val book = bookDao.getByUrl(bookUrl) ?: return PreviewOutcome.NotSynced
        val alias = works.cached(book, account.peerId) ?: return PreviewOutcome.NotSynced
        if (!networkAvailability.isAvailable()) return PreviewOutcome.Failed(SyncFailure.Offline)

        val head = try {
            latestOp(account.baseUrl, account.credentials, alias.workId)
        } catch (e: IOException) {
            return PreviewOutcome.Failed(reasonFor(e))
        } ?: return PreviewOutcome.NotSynced

        if (head.deviceId == null || head.deviceId != account.deviceId) {
            forAccount(account) { land(account, bookUrl, head) }
        }

        val stored = progressDao.get(bookUrl)
        val exact = head.locatorJson.takeIf {
            head.editionSha != null &&
                head.editionSha == alias.editionSha &&
                ExactLocatorAnchor.isExactJson(it)
        }
        return PreviewOutcome.Ready(
            SyncPreview(
                local = stored?.totalProgression,
                remote = head.progression,
                remoteAt = head.clientTs.takeIf { it > 0 },
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

    override suspend fun preservedConflict(bookUrl: String): SyncPreview? {
        val account = account() ?: return null
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return null
        if (!state.hasPending) return null
        val there = state.pendingProgression ?: return null
        val alias = identityDao.alias(bookUrl, account.peerId)
        val exact = state.exactLocatorFor(alias)
        return SyncPreview(
            local = progressDao.get(bookUrl)?.totalProgression,
            remote = there,
            remoteAt = state.remoteUpdatedAt?.takeIf { it > 0 },
            excerpt = ExactLocatorAnchor.excerpt(exact),
            confidence = if (exact != null) {
                ResumeConfidence.EXACT
            } else {
                ResumeConfidence.APPROXIMATE
            },
            exactPositionAgreement = ExactLocatorAnchor.agreement(
                progressDao.get(bookUrl)?.locatorJson,
                exact,
            ),
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
        val account = account() ?: return ResolveOutcome.Done
        val state = peerStateDao.get(bookUrl, account.peerId) ?: return ResolveOutcome.Done
        val progression = state.pendingProgression ?: return ResolveOutcome.Done

        val alias = bookDao.getByUrl(bookUrl)
            ?.let { works.cached(it, account.peerId) }
        val locator = state.exactLocatorFor(alias)

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
     * Keeps what is here and stops the server's answer being offered
     * again.
     *
     * Nothing is sent from here. Clearing the disagreement leaves the
     * book dirty, so the next run pushes the position that is actually
     * on the device — including any page turned while the question was
     * on screen.
     */
    override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome {
        val account = account() ?: return ResolveOutcome.Done
        peerStateDao.clearPending(bookUrl, account.peerId)
        return ResolveOutcome.Done
    }

    override suspend fun refreshUnresolved() {
        val account = account() ?: return
        reporting.reportUnresolved(peerStateDao.countPending(account.peerId))
    }

    override suspend fun identity(): SyncIdentity? {
        val account = account() ?: return null
        // Every book here can be named to this server, by its hashes if
        // not by a catalog id, so nothing is ever stranded.
        return SyncIdentity(login = account.login, strandedBooks = 0)
    }

    // -- The account ------------------------------------------------------

    /**
     * The connected liseur-sync account, as a sync run saw it.
     *
     * Read once from `remote_server` and carried through the run, so a
     * sign-out landing mid-run cannot split it between two accounts.
     * Every write checks the row again, in the same transaction.
     */
    private class Account(
        val baseUrl: String,
        val credentials: RemoteCredentials,
        /** The key this account's per-book agreements are stored under. */
        val peerId: String,
        /** The server's own name for this device, when it said one. */
        val deviceId: String?,
        /**
         * This device, as this device knows itself.
         *
         * Part of every op id, so that two phones sitting at the same
         * revision of the same book do not name the same op and silence
         * each other. It is the local identity rather than the server's
         * precisely so that it exists before the server has said
         * anything.
         */
        val deviceKey: String,
        /** How far through the op log this device has reconciled. */
        val cursorSeq: Long,
        /** Who is signed in, for telling accounts apart in the UI. */
        val login: String,
        /** The whole row's identity stamp, for the mid-run change guard. */
        val accountKey: String,
    )

    /**
     * The connected account, or null when there is none or it is not
     * liseur-sync's.
     *
     * A row whose token cannot be read back — a backup restored onto
     * another phone — reports as [PositionSyncStatus.Unavailable] only
     * from [run]; the quieter callers simply answer "not synced".
     */
    private suspend fun server(): RemoteServer? =
        serverDao.get()?.takeIf { it.kind == ServerKind.LISEUR_SYNC }

    private suspend fun account(): Account? {
        val server = server() ?: return null
        val credentials = server.credentials ?: return null
        return Account(
            baseUrl = server.baseUrl,
            credentials = credentials,
            peerId = server.accountKey,
            deviceId = server.accountId,
            deviceKey = deviceKey(),
            cursorSeq = server.syncCursorSeq,
            login = server.username?.takeIf { it.isNotBlank() } ?: "liseur-sync",
            accountKey = server.accountKey,
        )
    }

    // -- The run ----------------------------------------------------------

    private suspend fun run(book: String?): SyncOutcome {
        val server = server() ?: run {
            reporting.report(PositionSyncStatus.Idle)
            return SyncOutcome.NotApplicable
        }
        val credentials = server.credentials ?: run {
            // The token cannot be read back: a database restored onto
            // another phone arrives with ciphertext this Keystore cannot
            // open, and there is nothing to do about it here.
            reporting.report(PositionSyncStatus.Unavailable)
            return SyncOutcome.NotApplicable
        }
        if (!networkAvailability.isAvailable()) {
            reporting.report(PositionSyncStatus.Failed(SyncFailure.Offline))
            return SyncOutcome.Failure(SyncFailure.Offline)
        }
        val account = Account(
            baseUrl = server.baseUrl,
            credentials = credentials,
            peerId = server.accountKey,
            deviceId = server.accountId,
            deviceKey = deviceKey(),
            cursorSeq = server.syncCursorSeq,
            login = server.username?.takeIf { it.isNotBlank() } ?: "liseur-sync",
            accountKey = server.accountKey,
        )

        reporting.report(PositionSyncStatus.Syncing)
        val books = if (book == null) {
            bookDao.allOnce()
        } else {
            listOfNotNull(bookDao.getByUrl(book))
        }

        var firstFailure: SyncFailure? = null

        // Names first: an op is about a work id, so a book with no name
        // can neither receive what arrived nor send what is owed.
        val named = name(account, books, single = book != null)
        firstFailure = firstFailure ?: named.failure

        val pull = pull(account)
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

        // Books whose name was refreshed after the server disowned it,
        // once each. A second refusal for the same book in the same run
        // is left for the next scheduled sync rather than chased.
        val recovered = mutableSetOf<String>()

        val pushed = push(account, pushes, recovered) { firstFailure = firstFailure ?: it }

        // After the positions, because where the reader is now matters
        // more than how long they took to get there, and a run cut short
        // by a dead network should have spent what it had on the former.
        uploadSessions(account, recovered) { firstFailure = firstFailure ?: it }

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
        )

        return if (firstFailure == null) {
            forAccount(account) { serverDao.setPositionSyncedAt(at) }
            reporting.report(PositionSyncStatus.Synced(at))
            SyncOutcome.Success
        } else {
            Log.i(TAG, "Some positions did not settle: ${firstFailure.label}")
            reporting.report(PositionSyncStatus.Failed(firstFailure))
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
        account: Account,
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
                        works.resolve(candidate, account.peerId, account.baseUrl, account.credentials)
                    if (refreshed is WorkResolution.Named) alias = refreshed.alias
                }
                out += candidate to alias
                // A name that arrived without the one-off question —
                // a doubtful match confirmed by hand — still owes
                // it: whatever the server heard before the name was
                // usable is behind the cursor.
                if (!alias.seeded && budget > 0) {
                    budget--
                    seed(account, candidate, alias)
                }
                continue
            }
            // A guess the file or the catalog id could settle is worth
            // asking about again; any other unusable alias has asked
            // its question and waits for the answer.
            if (cached != null && !works.retryable(cached, candidate)) continue
            if (budget <= 0) continue
            budget--
            when (
                val resolved =
                    works.resolve(candidate, account.peerId, account.baseUrl, account.credentials)
            ) {
                is WorkResolution.Named -> {
                    out += candidate to resolved.alias
                    // Everything that happened to this book before it had
                    // a name is behind the cursor, so it is asked for
                    // once, directly.
                    seed(account, candidate, resolved.alias)
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
        account: Account,
        book: Book,
        alias: WorkAlias,
    ) {
        val head = try {
            latestOp(account.baseUrl, account.credentials, alias.workId)
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
    private suspend fun pull(account: Account): SyncFailure? {
        var cursor = account.cursorSeq
        var guard = MAX_PAGES
        while (guard-- > 0) {
            val page = try {
                http.get(
                    LiseurSyncApi.changes(account.baseUrl, since = cursor, limit = PAGE),
                    account.credentials,
                    expected = setOf(LiseurSyncHttp.GONE),
                )
            } catch (gone: LiseurSyncRejection) {
                return resync(account)
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
    private suspend fun resync(account: Account): SyncFailure? {
        Log.i(TAG, "The cursor fell behind the server's horizon; starting from a snapshot")
        val snapshot = try {
            http.get(LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.HEADS), account.credentials)
        } catch (e: IOException) {
            return reasonFor(e)
        }
        apply(account, ops(snapshot.optJSONArray("ops")), snapshot.optLong("snapshot_seq"))
        return null
    }

    /** Lands a page and moves the cursor, together or not at all. */
    private suspend fun apply(account: Account, ops: List<SyncOp>, cursor: Long) {
        val byWork = identityDao.aliasesFor(account.peerId).associateBy { it.workId }
        inTransaction {
            if (serverDao.get()?.accountKey != account.accountKey) return@inTransaction
            val newestByWork = ops
                .filterNot { it.deviceId != null && it.deviceId == account.deviceId }
                .groupBy(SyncOp::workId)
                .mapNotNull { (_, candidates) -> candidates.maxByOrNull(SyncOp::seq) }
            for (op in newestByWork) {
                val bookUrl = byWork[op.workId]?.takeIf { it.usable }?.bookUrl ?: continue
                land(account, bookUrl, op)
            }
            serverDao.setSyncCursor(cursor)
        }
    }

    private suspend fun land(account: Account, bookUrl: String, op: SyncOp) {
        peerStateDao.persistPending(
            bookUrl = bookUrl,
            peerId = account.peerId,
            progression = op.progression,
            status = readingStatusFor(op.progression).wireName,
            remoteUpdatedAt = op.clientTs.takeIf { it > 0 },
            locatorJson = op.locatorJson,
            editionSha = op.editionSha,
        )
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
        account: Account,
        named: Pair<Book, WorkAlias>,
    ): Reconciled {
        val (book, alias) = named
        val stored = progressDao.get(book.url)
        val state = peerStateDao.get(book.url, account.peerId)
        val exactRemote = state?.exactLocatorFor(alias)
        val dirty = state?.isDirty(stored?.localRevision ?: 0)
            ?: ((stored?.localRevision ?: 0) > 0)

        if (!needsReconciling(false, state?.hasPending == true, dirty)) return Reconciled.Nothing

        val decision = reconcileReadingState(
            local = stored?.asReadingState(),
            remote = state?.pendingState(),
            baseline = state?.baseline(),
            localDirty = dirty,
            localUnreadOverride = stored?.override == FinishedOverride.UNREAD,
            exactPositionAgreement = ExactLocatorAnchor.agreement(
                stored?.locatorJson,
                exactRemote,
            ),
        )
        return act(account, book, alias, stored, state, exactRemote, decision)
    }

    private suspend fun act(
        account: Account,
        book: Book,
        alias: WorkAlias,
        stored: ReadingProgress?,
        state: SyncPeerState?,
        exactRemote: String?,
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
                        locatorJson = exactRemote,
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
     *
     * A batch the server refuses wholesale because it no longer holds
     * one of the works settles nothing: the stale name is refreshed and
     * the batch retried within the run, so a cleanup on the server side
     * never strands a position that was dirty when it happened.
     */
    private suspend fun push(
        account: Account,
        pushes: List<PendingPush>,
        recovered: MutableSet<String>,
        onFailure: (SyncFailure) -> Unit,
    ): Int {
        var pushed = 0
        val queue = ArrayDeque(pushes.chunked(SyncOps.MAX_BATCH))
        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()
            // A null answer means a recovery rebuilt the batch and it
            // goes back on the queue: nothing from the rejected request
            // is settled.
            val answer = try {
                http.post(
                    LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.OPS),
                    account.credentials,
                    JSONObject().put(
                        "ops",
                        JSONArray().apply { batch.forEach { put(SyncOps.toJson(it.op)) } },
                    ),
                    expected = setOf(LiseurSyncHttp.BAD_REQUEST),
                )
            } catch (rejection: LiseurSyncRejection) {
                when (val recovery = recoverPush(account, batch, rejection, recovered, onFailure)) {
                    is PushRecovery.Retry -> {
                        if (recovery.batch.isNotEmpty()) queue.addFirst(recovery.batch)
                        null
                    }

                    PushRecovery.Exhausted -> {
                        onFailure(SyncFailure.StaleIdentity)
                        return pushed
                    }

                    PushRecovery.Ordinary -> {
                        Log.i(TAG, "The server refused a positions batch")
                        onFailure(SyncFailure.ServerError(rejection.code))
                        return pushed
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Could not send positions", e)
                onFailure(reasonFor(e))
                return pushed
            } ?: continue

            val accepted = accepted(answer)
            for (item in batch) {
                if (item.op.opId !in accepted) {
                    // The server refused this one on its own terms — an
                    // id reused with a different payload. Leaving the
                    // book dirty is right: the next run asks again with
                    // whatever it holds then.
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

    /** What came of trying to recover a rejected ops batch. */
    private sealed interface PushRecovery {
        /** Retry these instead of the rejected batch. */
        data class Retry(val batch: List<PendingPush>) : PushRecovery

        /** Not a refusal recovery answers; the caller fails the old way. */
        data object Ordinary : PushRecovery

        /** This run already refreshed the book once; stop asking. */
        data object Exhausted : PushRecovery
    }

    /**
     * Answers a batch the server refused for naming a deleted work.
     *
     * Only the book the refusal names is refreshed, and only when the
     * refusal's `op_id` and `work_id` match an op this batch actually
     * sent — anything else is a malformed answer, not a stale identity,
     * and deletes nothing. The refreshed name is reseeded and the push
     * rebuilt from current stored state, so the retried op carries a
     * newly derived id under the new work. A book that comes back
     * unnameable, or whose reading now points the other way, is simply
     * dropped from the retry: its position stays dirty and loses
     * nothing.
     */
    private suspend fun recoverPush(
        account: Account,
        batch: List<PendingPush>,
        rejection: LiseurSyncRejection,
        recovered: MutableSet<String>,
        onFailure: (SyncFailure) -> Unit,
    ): PushRecovery {
        if (!rejection.isUnknownWork) return PushRecovery.Ordinary
        val opId = rejection.opId ?: return PushRecovery.Ordinary
        val workId = rejection.workId ?: return PushRecovery.Ordinary
        val stale = batch.firstOrNull { it.op.opId == opId }
            ?.takeIf { it.op.workId == workId && it.alias.workId == workId }
            ?: return PushRecovery.Ordinary

        if (!recovered.add(stale.bookUrl)) {
            // The name fetched in this very run was deleted again before
            // the retry landed. Forget it and stop: the next run names
            // the book afresh, with the position still dirty.
            forAccount(account) {
                identityDao.deleteAliasIfStale(stale.bookUrl, account.peerId, workId)
            }
            return PushRecovery.Exhausted
        }

        val replacement = when (val refreshed = refreshStaleIdentity(account, stale.bookUrl, workId)) {
            is IdentityRefresh.Refreshed ->
                (reconcile(account, refreshed.book to refreshed.alias) as? Reconciled.Owed)?.push

            is IdentityRefresh.Failed -> {
                onFailure(reasonFor(refreshed.cause))
                null
            }

            IdentityRefresh.Unnameable -> null
        }
        return PushRecovery.Retry(
            batch.mapNotNull { if (it.bookUrl == stale.bookUrl) replacement else it },
        )
    }

    /** What came of asking the server for a name to replace a stale one. */
    private sealed interface IdentityRefresh {
        /** A usable fresh name, already seeded. */
        data class Refreshed(val book: Book, val alias: WorkAlias) : IdentityRefresh

        /** The book cannot be named without the reader, or at all yet. */
        data object Unnameable : IdentityRefresh

        /** Asking failed; everything stays as it was and retried later. */
        data class Failed(val cause: IOException) : IdentityRefresh
    }

    /**
     * Forgets the name the server just disowned and asks for a fresh one.
     *
     * The delete is conditional on the stale work id, so a delayed
     * refusal cannot remove a name a faster recovery already wrote.
     * Resolution then goes the ordinary way — the catalog route for one
     * of this server's own books, the hashes and fingerprints for any
     * other — and a usable answer is seeded before it is used, because
     * everything the refreshed work heard before this device named it
     * sits behind the cursor, where the delta pull never looks.
     */
    private suspend fun refreshStaleIdentity(
        account: Account,
        bookUrl: String,
        staleWorkId: String,
    ): IdentityRefresh {
        forAccount(account) {
            identityDao.deleteAliasIfStale(bookUrl, account.peerId, staleWorkId)
        }
        val book = bookDao.getByUrl(bookUrl) ?: return IdentityRefresh.Unnameable
        return when (
            val resolved = works.resolve(book, account.peerId, account.baseUrl, account.credentials)
        ) {
            is WorkResolution.Named -> {
                seed(account, book, resolved.alias)
                IdentityRefresh.Refreshed(book, resolved.alias)
            }

            is WorkResolution.NeedsConfirming, is WorkResolution.Ambiguous ->
                IdentityRefresh.Unnameable

            is WorkResolution.Unresolved ->
                resolved.cause?.let(IdentityRefresh::Failed) ?: IdentityRefresh.Unnameable
        }
    }

    // -- Sessions ---------------------------------------------------------

    /**
     * A session ready to send, with everything a retry needs.
     *
     * A refusal that names a stale work asks for the batch to be rebuilt
     * under the book's fresh name, which takes the stored row, the wire
     * id to match the refusal against, and the name this attempt used.
     */
    private data class PreparedSession(
        val localId: Long,
        val wireId: String,
        val bookUrl: String,
        val workId: String,
        val json: JSONObject,
    )

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
     *
     * The one refusal that is recovered instead is a stale work name:
     * the book is resolved afresh, its sessions rebuilt under the new
     * name, and the batch retried. Nothing is marked uploaded until the
     * server has actually taken it.
     */
    private suspend fun uploadSessions(
        account: Account,
        recovered: MutableSet<String>,
        onFailure: (SyncFailure) -> Unit,
    ) {
        val sessions = sessionDao.awaitingUpload(SessionUploads.MAX_BATCH)
        if (sessions.isEmpty()) return
        val byBook = identityDao.aliasesFor(account.peerId)
            .filter { it.usable }
            .associateBy { it.bookUrl }

        var sending = sessions.mapNotNull { session ->
            val alias = byBook[session.bookUrl] ?: return@mapNotNull null
            SessionUploads.toJson(
                session = session,
                deviceKey = account.deviceKey,
                workId = alias.workId,
                editionSha = alias.editionSha,
            )?.let {
                PreparedSession(
                    localId = session.id,
                    wireId = SessionUploads.sessionIdFor(account.deviceKey, session.id),
                    bookUrl = session.bookUrl,
                    workId = alias.workId,
                    json = it,
                )
            }
        }
        if (sending.isEmpty()) return

        var answered = false
        while (!answered) {
            answered = try {
                http.post(
                    LiseurSyncApi.url(account.baseUrl, LiseurSyncApi.SESSIONS),
                    account.credentials,
                    JSONObject().put(
                        "sessions",
                        JSONArray().apply { sending.forEach { put(it.json) } },
                    ),
                    expected = setOf(LiseurSyncHttp.BAD_REQUEST),
                )
                true
            } catch (rejection: LiseurSyncRejection) {
                when (val recovery = recoverSessions(account, sending, rejection, recovered)) {
                    is SessionRecovery.Retry -> {
                        recovery.failure?.let(onFailure)
                        if (recovery.sending.isEmpty()) return
                        sending = recovery.sending
                        false
                    }

                    SessionRecovery.Exhausted -> {
                        onFailure(SyncFailure.StaleIdentity)
                        return
                    }

                    SessionRecovery.Ordinary -> {
                        // A refusal that names no stale work is the same
                        // dead end as any other malformed batch.
                        Log.i(TAG, "The server will never take these sessions; not asking again")
                        true
                    }
                }
            } catch (rejected: RemoteHttpFailure) {
                if (!rejected.reason.isFinal()) {
                    Log.i(TAG, "Could not send reading sessions", rejected)
                    onFailure(rejected.reason)
                    return
                }
                Log.i(TAG, "The server will never take these sessions; not asking again")
                true
            } catch (e: IOException) {
                Log.i(TAG, "Could not send reading sessions", e)
                onFailure(reasonFor(e))
                return
            }
        }

        forAccount(account) { sessionDao.markUploaded(sending.map { it.localId }, now()) }
    }

    /** What came of trying to recover a rejected session batch. */
    private sealed interface SessionRecovery {
        /**
         * Retry these instead of the rejected batch; [failure] is a
         * re-resolution that failed along the way, reported but not
         * allowed to stop the books whose names are still good.
         */
        data class Retry(val sending: List<PreparedSession>, val failure: SyncFailure? = null) :
            SessionRecovery

        /** Not a refusal recovery answers; the caller fails the old way. */
        data object Ordinary : SessionRecovery

        /** This run already refreshed the book once; stop asking. */
        data object Exhausted : SessionRecovery
    }

    /**
     * Answers a session batch the server refused for naming a deleted
     * work.
     *
     * Only the book the refusal names is refreshed, and only when the
     * refusal's `session_id` and `work_id` match an entry this batch
     * actually sent — anything else is a malformed answer, not a stale
     * identity, and deletes nothing. The affected payloads are rebuilt
     * from their stored rows under the fresh name; the wire session id
     * derives from the local row rather than the work, so it survives
     * as the idempotency key it was. A book that comes back unnameable
     * keeps its sessions pending while the rest of the batch is retried
     * without it.
     */
    private suspend fun recoverSessions(
        account: Account,
        sending: List<PreparedSession>,
        rejection: LiseurSyncRejection,
        recovered: MutableSet<String>,
    ): SessionRecovery {
        if (!rejection.isUnknownWork) return SessionRecovery.Ordinary
        val sessionId = rejection.sessionId ?: return SessionRecovery.Ordinary
        val workId = rejection.workId ?: return SessionRecovery.Ordinary
        val culprit = sending.firstOrNull { it.wireId == sessionId }
            ?.takeIf { it.workId == workId }
            ?: return SessionRecovery.Ordinary

        if (!recovered.add(culprit.bookUrl)) {
            // The name fetched in this very run was deleted again before
            // the retry landed. Forget it and stop: the sessions stay
            // pending for the next run.
            forAccount(account) {
                identityDao.deleteAliasIfStale(culprit.bookUrl, account.peerId, workId)
            }
            return SessionRecovery.Exhausted
        }

        val rest = sending.filter { it.bookUrl != culprit.bookUrl }
        val refreshed = when (val outcome = refreshStaleIdentity(account, culprit.bookUrl, workId)) {
            is IdentityRefresh.Refreshed -> outcome
            is IdentityRefresh.Failed -> return SessionRecovery.Retry(rest, reasonFor(outcome.cause))
            IdentityRefresh.Unnameable -> return SessionRecovery.Retry(rest)
        }
        val rebuilt = sending.filter { it.bookUrl == culprit.bookUrl }.mapNotNull { entry ->
            val session = sessionDao.get(entry.localId) ?: return@mapNotNull null
            SessionUploads.toJson(
                session = session,
                deviceKey = account.deviceKey,
                workId = refreshed.alias.workId,
                editionSha = refreshed.alias.editionSha,
            )?.let { entry.copy(workId = refreshed.alias.workId, json = it) }
        }
        return SessionRecovery.Retry(rest + rebuilt)
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
    private suspend fun forAccount(account: Account, work: suspend () -> Unit) {
        inTransaction {
            if (serverDao.get()?.accountKey == account.accountKey) work()
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

    /** Exact placement is safe only for byte-identical editions. */
    private fun SyncPeerState.exactLocatorFor(alias: WorkAlias?): String? =
        pendingLocatorJson.takeIf {
            pendingEditionSha != null &&
                pendingEditionSha == alias?.editionSha &&
                ExactLocatorAnchor.isExactJson(it)
        }

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
