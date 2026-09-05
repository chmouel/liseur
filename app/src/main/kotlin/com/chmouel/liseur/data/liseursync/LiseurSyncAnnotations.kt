package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.AnnotationSyncDao
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import com.chmouel.liseur.data.remote.serverAnswered
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps highlights, notes and bookmarks in step with a liseur-sync
 * server (ADR-0028).
 *
 * Reading positions are a log of things that happened, and the newest
 * one wins. Annotations are not: a highlight is created, recoloured,
 * annotated and deleted, and every one of those is a change to the same
 * record. So this cannot work the way positions do.
 *
 * It also cannot work by queueing. `AGENTS.md` is blunt about that, and
 * right: a queue is a second copy of the truth, and the two drift.
 * Instead [AnnotationSync] records what the server *confirmed*, and the
 * work owed is whatever the live `annotations` table says that the
 * confirmation does not. A mark with no agreement is a create; one
 * whose content has moved on is an edit; an agreement with no mark left
 * is a deletion. Nothing is enqueued, so nothing can be enqueued twice.
 *
 * The one place a copy is unavoidable is a request already on its way.
 * A payload derived from a row cannot be derived again once the reader
 * edits that row, and the server recognises a retry only by its bytes —
 * so an interrupted push would come back a different shape, miss the
 * retry check and be resolved as a conflict, quietly discarding the
 * edit that caused it. The exact bytes sent are therefore written down
 * before the call and replayed verbatim until the server answers.
 *
 * The run goes: settle what is in flight, take what has arrived,
 * reconcile the books that are due, push what has changed, then send
 * the deletions. The order is not arrangement. Reconciling *before*
 * pushing is what stops an edit made offline, to a record another
 * device deleted months ago, from arriving as a create and resurrecting
 * a highlight the reader threw away.
 */
class LiseurSyncAnnotations(
    private val serverDao: RemoteServerDao,
    private val annotationDao: BookAnnotationDao,
    private val syncDao: AnnotationSyncDao,
    private val identityDao: WorkIdentityDao,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {

    /** The account this pass is speaking to. */
    data class Peer(
        val baseUrl: String,
        val credentials: RemoteCredentials,
        val peerId: String,
        val accountKey: String,
        val cursorSeq: Long,
    )

    /**
     * What the pass managed, and what it would like the caller to do.
     *
     * [reresolve] names books whose work id the server did not
     * recognise, each against the *stale* id it was refused under.
     * Re-resolving is the position sync's job and it already does it;
     * saying so is cheaper than doing it twice. The stale id is part of
     * the message because the caller acts on it after the pass, by which
     * time the name may already have been repaired — and deleting
     * whatever it finds there would throw away the good one.
     */
    data class Outcome(
        val pulled: Int = 0,
        val pushed: Int = 0,
        val failure: SyncFailure? = null,
        val unreachable: SyncFailure? = null,
        val reresolve: Map<String, String> = emptyMap(),
        val hasMore: Boolean = false,
    )

    private class Progress {
        var pulled = 0
        var pushed = 0
        var failure: SyncFailure? = null
        var unreachable: SyncFailure? = null
        val reresolve = mutableMapOf<String, String>()
        var hasMore = false
        val stopped: Boolean
            get() = unreachable != null || failure == SyncFailure.Unauthorised ||
                failure == SyncFailure.Forbidden || failure == SyncFailure.InsecureTransport

        fun failed(cause: IOException, reason: SyncFailure) {
            if (failure == null || reason == SyncFailure.Unauthorised ||
                (failure != SyncFailure.Unauthorised &&
                    (reason == SyncFailure.Forbidden || reason == SyncFailure.InsecureTransport))
            ) failure = reason
            if (!cause.serverAnswered()) unreachable = unreachable ?: reason
        }

        fun outcome() = Outcome(pulled, pushed, failure, unreachable, reresolve, hasMore)
    }

    /**
     * Runs one annotation pass.
     *
     * [book] narrows what is *offered*, never what is settled. A
     * book-scoped run exists so that closing a book is cheap, but the
     * requests in flight and the feed cursor belong to the account: a
     * pass that settled only one book's requests would strand the rest
     * until something happened to touch them, and one that moved the
     * cursor past records it had decided not to look at would lose them
     * for good. So the first two phases always run whole.
     */
    suspend fun sync(peer: Peer, book: String?): Outcome {
        val progress = Progress()
        val aliases = identityDao.aliasesFor(peer.peerId).filter { it.usable }
        if (aliases.isEmpty() && syncDao.pendingFor(peer.peerId).isEmpty()) {
            return progress.outcome()
        }

        settle(peer, aliases, progress)
        if (progress.stopped) return progress.outcome()

        pull(peer, aliases, progress)
        if (progress.stopped) return progress.outcome()

        val scope = if (book == null) aliases else aliases.filter { it.bookUrl == book }
        val agreed = reconcile(peer, scope, aliases, progress)
        if (progress.stopped) return progress.outcome()

        push(peer, scope, aliases, agreed, progress)
        if (progress.stopped) return progress.outcome()

        deletes(peer, scope, aliases, progress)
        return progress.outcome()
    }

    // -- 0. Settle what is in flight --------------------------------------

    /**
     * Replays every request the server never answered, before anything
     * new is worked out.
     *
     * A write goes back byte for byte and earns `duplicate` — or
     * `applied`, if it never arrived at all. A delete has no body to
     * replay, so it is rebuilt from the id and the rev it quoted, both
     * of which were fixed when it was first made; the server checks for
     * a tombstone before it checks the rev, so a repeat is a duplicate
     * rather than a conflict.
     *
     * A request that fails again leaves its bytes where they are. That
     * is not a leak, it is the whole mechanism.
     */
    private suspend fun settle(peer: Peer, aliases: List<WorkAlias>, progress: Progress) {
        val pending = syncDao.pendingFor(peer.peerId)
        if (pending.isEmpty()) return

        val writes = pending.filter {
            it.pendingKind == AnnotationSync.PENDING_WRITE && it.pendingJson != null
        }
        for (batch in writes.chunked(AnnotationWire.MAX_BATCH)) {
            if (!postSplitting(peer, batch, aliases, progress)) return
        }

        for (row in pending.filter { it.pendingKind == AnnotationSync.PENDING_DELETE }) {
            if (progress.unreachable != null) return
            // Re-read: the pending set was taken at the top of the pass,
            // and settling the writes above took network time in which
            // this row may have been answered, dropped or replaced.
            val current = syncDao.get(peer.peerId, row.id) ?: continue
            if (current.pendingKind != AnnotationSync.PENDING_DELETE) continue
            sendDelete(peer, current, current.pendingRev, aliases, progress)
        }
    }

    // -- 1. Pull ----------------------------------------------------------

    /**
     * Reads everything the account has changed since the cursor.
     *
     * Each page lands and moves the cursor in one transaction, which is
     * the same rule the op log lives by and for the same reason: a
     * cursor that stepped over a page whose contents were lost has lost
     * them permanently.
     */
    private suspend fun pull(peer: Peer, aliases: List<WorkAlias>, progress: Progress) {
        var cursor = peer.cursorSeq
        var guard = MAX_PAGES
        while (guard-- > 0) {
            if (!stillOurs(peer)) return
            val page = try {
                http.get(
                    LiseurSyncApi.annotationChanges(peer.baseUrl, since = cursor, limit = PAGE),
                    peer.credentials,
                )
            } catch (e: IOException) {
                Log.i(TAG, "Could not read what annotations changed", e)
                progress.failed(e, reasonFor(e))
                return
            }

            val array = page.optJSONArray("annotations")
            val records = AnnotationWire.records(array)
            val next = AnnotationWire.pageReach(array)?.coerceAtLeast(cursor)
                ?: page.optLong("high_water", cursor)

            landPage(peer, records, aliases, next, progress)
            cursor = next

            if (!page.optBoolean("has_more", false)) return
        }
        Log.i(TAG, "Stopped reading annotation changes after $MAX_PAGES pages")
        progress.hasMore = true
    }

    /** Writes a page and moves the cursor, together or not at all. */
    private suspend fun landPage(
        peer: Peer,
        records: List<AnnotationWire.Record>,
        aliases: List<WorkAlias>,
        cursor: Long,
        progress: Progress,
    ) {
        inTransaction {
            if (!stillOurs(peer)) return@inTransaction
            // The feed is state, not history, so a record edited twice
            // mid-page arrives twice; the newest is the one that counts
            // and the rest are noise. Newest by seq, like everywhere
            // else here: a rev restarts at 1 when the server recreates
            // an id whose tombstone was swept, so the highest rev in a
            // page can be the older of the two states.
            val newest = records.groupBy { it.id }
                .mapNotNull { (_, all) -> all.maxByOrNull { it.seq } }
            for (record in newest) {
                if (land(peer, record, aliases)) progress.pulled++
            }
            serverDao.setAnnotationCursor(cursor)
        }
    }

    /**
     * Lays one record over what this device holds, if it should.
     *
     * A record this device has already seen, or has since changed, or
     * has a request out about, is left alone — the push is where a
     * disagreement gets settled, and settling it in two places is how
     * two devices end up taking turns to overwrite each other.
     */
    private suspend fun land(
        peer: Peer,
        record: AnnotationWire.Record,
        aliases: List<WorkAlias>,
    ): Boolean {
        val row = syncDao.get(peer.peerId, record.id)
        // Ordered by seq, not by rev. A rev counts writes to one mark
        // and restarts at 1 when the server recreates an id whose
        // tombstone has been swept; a device holding the old, higher
        // number would read the new record as ancient and never take it
        // again. Seq is the account's own clock and only ever goes up.
        if (row != null && record.seq <= row.seq) return false

        val local = annotationDao.byId(record.id)
        if (row != null && (row.pending || dirty(row, local, aliases))) return false

        if (record.deleted) {
            if (local == null && row == null) return false
            annotationDao.deleteById(record.id)
            syncDao.deleteById(peer.peerId, record.id)
            return true
        }

        val workId = record.workId ?: return false
        val home = home(peer, record, aliases, row?.bookId ?: local?.bookId) ?: return false

        if (row == null && local != null) {
            // Both sides hold this id and nothing here records them ever
            // having agreed — the account was disconnected and paired
            // again, or a backup was restored over it. There is no
            // baseline, so there is no way to tell an edit from an echo,
            // and overwriting would quietly throw away whatever was
            // written while the two were apart.
            //
            // So the server's revision is adopted and its words are not.
            // The row is written down as agreeing with the *server's*
            // copy, which is exactly what leaves it reading as changed:
            // the next push offers the local wording at the rev just
            // learned, and one of the two wins in the open.
            val serverCopy = AnnotationWire.toAnnotation(record, home.bookUrl, null)
            if (AnnotationWire.fingerprint(serverCopy, workId) !=
                AnnotationWire.fingerprint(local, workId)
            ) {
                syncDao.upsert(
                    AnnotationSync(
                        id = record.id,
                        peerId = peer.peerId,
                        bookId = home.bookUrl,
                        workId = workId,
                        rev = record.rev,
                        seq = record.seq,
                        ackedFingerprint = AnnotationWire.fingerprint(serverCopy, workId),
                    ),
                )
                return true
            }
        }

        // The book this mark belongs to may have moved between editions
        // since it was last seen; the mark moves with it.
        val landed = AnnotationWire.toAnnotation(record, home.bookUrl, local)
        annotationDao.upsert(landed)
        syncDao.upsert(
            AnnotationSync(
                id = record.id,
                peerId = peer.peerId,
                bookId = home.bookUrl,
                workId = workId,
                rev = record.rev,
                seq = record.seq,
                // Hashed from what was actually stored, never from what
                // arrived: a stamp carrying more precision than the
                // column holds would make the record look edited the
                // moment it landed, and push it straight back.
                ackedFingerprint = AnnotationWire.fingerprint(landed, workId),
            ),
        )
        return true
    }

    // -- 2. Reconcile ------------------------------------------------------

    /**
     * Asks outright what a work still holds, for the books that are due.
     *
     * Two things need this, and neither can come from the feed. A book
     * downloaded today has a cursor that walked past everything ever
     * said about it. And a deletion whose tombstone has been swept —
     * six months, server side — leaves no evidence at all: a device
     * that was away longer would push its stale copy as a create and
     * bring the highlight back.
     *
     * Budgeted, the way resolving is, so a large library settles over
     * several runs rather than firing hundreds of requests at once —
     * but a book with something to say is never held back by the
     * budget's calendar. The interval is this device's guess at how long
     * a tombstone lasts, and it is only a guess: retention is the
     * server's setting and may be as short as a day. Guessing wrong
     * about a book with nothing to push costs nothing, since a stale
     * agreement that is never acted on is never wrong. Guessing wrong
     * about a book with a create in hand is exactly the resurrection
     * this phase exists to prevent, so those are asked about every time,
     * however recently they were settled.
     *
     * @return what may be pushed from: the works that are agreed, and
     *   the ids this pass actually saw the server holding.
     */
    private suspend fun reconcile(
        peer: Peer,
        scope: List<WorkAlias>,
        aliases: List<WorkAlias>,
        progress: Progress,
    ): Agreed {
        val agreed = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val at = now()
        var budget = MAX_RECONCILES_PER_RUN
        val speaking = withSomethingToSay(peer, scope, at)
        for (alias in scope) {
            if (alias.bookUrl !in speaking &&
                alias.annotationsReconciledAt > at - RECONCILE_INTERVAL_MS
            ) {
                agreed += alias.workId
                continue
            }
            if (budget-- <= 0) continue
            if (progress.stopped) break
            if (!stillOurs(peer)) break

            val asked = syncDao.forWork(peer.peerId, alias.workId)
                .associate { it.id to annotationDao.byId(it.id)?.updatedAt }

            val page = try {
                http.get(
                    LiseurSyncApi.workAnnotations(peer.baseUrl, alias.workId),
                    peer.credentials,
                    expected = setOf(LiseurSyncHttp.NOT_FOUND),
                )
            } catch (gone: LiseurSyncRejection) {
                if (gone.code != LiseurSyncHttp.NOT_FOUND) {
                    Log.i(TAG, "Could not read the annotations on ${alias.workId}", gone)
                    progress.failed(gone, reasonFor(gone))
                    continue
                }
                // The server does not hold that work any more — it was
                // merged into another, or split away. The name is stale,
                // not the book, and asking again every run would stall
                // this book's marks for good. Handing it back to the
                // position sync is what gets it renamed; the marks are
                // still on the server under the work it moved to, and
                // the next pass finds them there.
                Log.i(TAG, "The server no longer holds ${alias.workId}; asking for a new name")
                identityDao.deleteAliasIfStale(alias.bookUrl, peer.peerId, alias.workId)
                progress.reresolve[alias.bookUrl] = alias.workId
                continue
            } catch (e: IOException) {
                Log.i(TAG, "Could not read the annotations on ${alias.workId}", e)
                progress.failed(e, reasonFor(e))
                continue
            }

            val body = page.optJSONArray("annotations")
            val live = AnnotationWire.records(body)
            val present = AnnotationWire.ids(body)
            reconcileWork(peer, alias, live, present, aliases, asked, at, progress)
            agreed += alias.workId
            seen += present
        }
        return Agreed(works = agreed, seen = seen)
    }

    /**
     * What reconciling established.
     *
     * [works] are the works nothing is outstanding on. [seen] are the
     * ids the server was observed to be holding just now — the only
     * evidence that a mark's tombstone has not been swept, and so the
     * only licence to offer an edit to one.
     */
    private data class Agreed(val works: Set<String>, val seen: Set<String>)

    /**
     * The books holding a mark this run would offer.
     *
     * The whole point of asking is to find out whether an id the server
     * once knew is still there, so this has to be the *same* question
     * the push asks. A mark the push would skip — one already agreed,
     * one refused for good, one waiting out a deferral, one no request
     * can even be built from — is not a reason to spend a live-set
     * fetch, and a handful of permanently stuck books would otherwise
     * eat the budget every pass and starve every book behind them.
     */
    private suspend fun withSomethingToSay(
        peer: Peer,
        scope: List<WorkAlias>,
        at: Long,
    ): Set<String> {
        val rows = syncDao.forPeer(peer.peerId).associateBy { it.id }
        return scope.filterTo(mutableSetOf()) { alias ->
            annotationDao.forBook(alias.bookUrl).any { offerable(it, rows[it.id], alias, at) != null }
        }.mapTo(mutableSetOf()) { it.bookUrl }
    }

    /**
     * The request this mark would make, or null if it would make none.
     *
     * One place, so the phase that decides what to ask about and the
     * phase that decides what to send cannot drift apart.
     */
    private fun offerable(
        local: BookAnnotation,
        row: AnnotationSync?,
        alias: WorkAlias,
        at: Long,
    ): AnnotationWire.Item? {
        if (row?.pending == true) return null
        if (row != null && row.retryNotBefore > at) return null
        val item = AnnotationWire.item(
            annotation = local,
            workId = alias.workId,
            baseRev = row?.rev ?: 0,
            editionSha = alias.editionSha,
        ) ?: return null
        if (item.fingerprint == row?.ackedFingerprint) return null
        if (item.fingerprint == row?.rejectedFingerprint) return null
        return item
    }

    private suspend fun reconcileWork(
        peer: Peer,
        alias: WorkAlias,
        live: List<AnnotationWire.Record>,
        present: Set<String>,
        aliases: List<WorkAlias>,
        asked: Map<String, Long?>,
        at: Long,
        progress: Progress,
    ) {
        inTransaction {
            if (!stillOurs(peer)) return@inTransaction
            for (record in live) {
                if (land(peer, record, aliases)) progress.pulled++
            }

            for (row in syncDao.forWork(peer.peerId, alias.workId)) {
                if (row.id in present) continue
                // Absence is only evidence about what the server
                // confirmed, and only while nothing is on its way there.
                // A mark never pushed, or with a request out, is this
                // device having something to say — not the server having
                // forgotten.
                if (row.rev < 1 || row.pending) continue
                // A mark the reader touched while the live set was on
                // its way is not one this answer describes. An edit
                // made *before* the call still goes — that is the whole
                // point of reconciling, and it is what stops an edit to
                // a swept tombstone being pushed back as a create — but
                // one made after it has not been asked about yet.
                val local = annotationDao.byId(row.id)
                if (local != null && local.updatedAt != asked[row.id]) continue
                annotationDao.deleteById(row.id)
                syncDao.deleteById(peer.peerId, row.id)
            }
            identityDao.markAnnotationsReconciled(alias.bookUrl, peer.peerId, at)
        }
    }

    // -- 3. Push ----------------------------------------------------------

    /**
     * Offers everything this device has changed.
     *
     * A work still waiting its turn to be reconciled offers nothing: it
     * is exactly the case where a create might be a resurrection, and
     * the wait is one run.
     */
    private suspend fun push(
        peer: Peer,
        scope: List<WorkAlias>,
        aliases: List<WorkAlias>,
        agreed: Agreed,
        progress: Progress,
    ) {
        val at = now()
        val seen = agreed.seen
        val rows = syncDao.forPeer(peer.peerId).associateBy { it.id }
        val items = mutableListOf<Pair<AnnotationSync, AnnotationWire.Item>>()

        for (alias in scope) {
            if (alias.workId !in agreed.works) continue
            for (local in annotationDao.forBook(alias.bookUrl)) {
                val row = rows[local.id]
                val item = offerable(local, row, alias, at) ?: continue
                // A mark the server once knew is only offered if this
                // pass saw it there. Anything else is a guess about a
                // tombstone that may have been swept, and pushing on a
                // guess is what brings a deleted highlight back. A mark
                // never acknowledged cannot resurrect anything, so it
                // goes whether or not it was in the set.
                if ((row?.rev ?: 0) >= 1 && local.id !in seen) continue

                items += (
                    row ?: AnnotationSync(
                        id = local.id,
                        peerId = peer.peerId,
                        bookId = alias.bookUrl,
                        workId = alias.workId,
                    )
                    ) to item
            }
        }
        if (items.isEmpty()) return

        for (batch in items.chunked(AnnotationWire.MAX_BATCH)) {
            if (progress.unreachable != null) return
            val marked = batch.map { (row, item) ->
                row.copy(
                    bookId = row.bookId,
                    workId = item.workId,
                    pendingKind = AnnotationSync.PENDING_WRITE,
                    pendingJson = item.json,
                    pendingRev = item.baseRev,
                    pendingFingerprint = item.fingerprint,
                )
            }
            // Written before the call, so that a process killed between
            // the two wakes up owing a replay rather than owing nothing.
            inTransaction {
                if (!stillOurs(peer)) return@inTransaction
                syncDao.upsertAll(marked)
            }

            if (!postSplitting(peer, marked, aliases, progress)) return
        }
    }

    /**
     * Sends one batch, or says why it could not.
     *
     * A refusal of the whole request is told apart from a refusal to
     * reach the server, because the two need opposite things. The batch
     * size is the server's to choose — a hundred is only the default,
     * and an administrator may have set it lower — so a request refused
     * outright is worth halving and trying again rather than replaying
     * unchanged until the account is disconnected.
     */
    private suspend fun postBatch(peer: Peer, body: String, progress: Progress): Answer {
        if (!stillOurs(peer)) return Answer.Unreachable
        return try {
            Answer.Results(
                http.postRaw(
                    LiseurSyncApi.url(peer.baseUrl, LiseurSyncApi.ANNOTATIONS),
                    peer.credentials,
                    body,
                    expected = setOf(LiseurSyncHttp.BAD_REQUEST, LiseurSyncHttp.TOO_LARGE),
                ).optJSONArray("results") ?: JSONArray(),
            )
        } catch (refused: LiseurSyncRejection) {
            Log.i(TAG, "The server would not take the batch: ${refused.error}")
            Answer.BatchRefused
        } catch (e: IOException) {
            Log.i(TAG, "Could not send annotations", e)
            progress.failed(e, reasonFor(e))
            Answer.Unreachable
        }
    }

    /** What came back from a push. */
    private sealed interface Answer {
        data class Results(val results: JSONArray) : Answer

        /** The whole request was refused, whatever it held. */
        data object BatchRefused : Answer

        data object Unreachable : Answer
    }

    /**
     * Sends a batch, halving it as long as the server refuses the lot.
     *
     * Down to a single item, which is as far as splitting can go: one
     * item the server will not take at any size is a bad item, and is
     * marked as such so it is not offered again until the reader
     * changes it.
     */
    private suspend fun postSplitting(
        peer: Peer,
        batch: List<AnnotationSync>,
        aliases: List<WorkAlias>,
        progress: Progress,
    ): Boolean {
        val body = AnnotationWire.batchBodyOf(batch.map { it.pendingJson!! })
        return when (val answer = postBatch(peer, body, progress)) {
            is Answer.Results -> {
                progress.pushed += applyResults(peer, batch, answer.results, aliases, progress)
                true
            }

            Answer.Unreachable -> false

            Answer.BatchRefused -> {
                if (batch.size == 1) {
                    inTransaction {
                        if (!stillOurs(peer)) return@inTransaction
                        val row = stillAsking(peer, batch[0]) ?: return@inTransaction
                        syncDao.upsert(row.clearPending(rejected = row.pendingFingerprint))
                    }
                    return true
                }
                val half = batch.size / 2
                postSplitting(peer, batch.take(half), aliases, progress) &&
                    postSplitting(peer, batch.drop(half), aliases, progress)
            }
        }
    }

    /**
     * Reads the server's answers back onto the rows that asked.
     *
     * Matched by id and never by position. The route is documented as
     * not atomic, and a short or reordered array indexed positionally
     * would apply one mark's answer to another — which is a wrong rev
     * written down as fact. Anything unmatched keeps its request, and is
     * replayed next run.
     */
    private suspend fun applyResults(
        peer: Peer,
        rows: List<AnnotationSync>,
        results: JSONArray,
        aliases: List<WorkAlias>,
        progress: Progress,
    ): Int {
        val byId = rows.associateBy { it.id }
        var settled = 0
        inTransaction {
            if (!stillOurs(peer)) return@inTransaction
            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                val sent = byId[result.optString("id")] ?: continue
                val row = stillAsking(peer, sent) ?: continue
                if (applyResult(peer, row, result, aliases, progress)) settled++
            }
        }
        return settled
    }

    private suspend fun applyResult(
        peer: Peer,
        row: AnnotationSync,
        result: JSONObject,
        aliases: List<WorkAlias>,
        progress: Progress,
    ): Boolean = when (result.optString("status")) {
        "applied", "duplicate" -> {
            syncDao.upsert(
                row.copy(
                    rev = result.optLong("rev", row.rev),
                    seq = result.optLong("seq", row.seq),
                    // What the server now holds is what was sent,
                    // whatever the reader has done since. If they edited
                    // it mid-flight the live hash no longer matches this
                    // one, the row reads as changed, and the next push
                    // carries it from the rev just learned.
                    ackedFingerprint = row.pendingFingerprint,
                    pendingKind = null,
                    pendingJson = null,
                    pendingRev = 0,
                    pendingFingerprint = null,
                    rejectedFingerprint = null,
                    retryNotBefore = 0,
                ),
            )
            true
        }

        "conflict" -> {
            conflict(peer, row, result.optJSONObject("server"), aliases)
            true
        }

        "invalid" -> {
            invalid(peer, row, result.optString("reason"), aliases, progress)
            false
        }

        else -> false
    }

    /**
     * Whether the mark still says what the request said it said.
     *
     * The sync row cannot answer this: editing a highlight writes to
     * `annotations`, not to `annotation_sync`. So an answer that arrives
     * after the reader has had another turn is an answer about words
     * nobody holds any more, and letting it overwrite them would lose an
     * edit that was never even offered.
     */
    private suspend fun stillSaying(row: AnnotationSync): Boolean {
        val local = annotationDao.byId(row.id) ?: return false
        return AnnotationWire.fingerprint(local, row.workId) == row.pendingFingerprint
    }

    /**
     * Settles a write the server refused because it holds something
     * else.
     *
     * The server copy wins over the copy that was *sent*. That is a
     * decision, not a shrug: the ADR is explicit that the server orders
     * and never merges, and a client that re-pushed instead would give
     * two devices a way to overwrite each other indefinitely, each
     * certain it was the one being lost. It does not win over a copy the
     * reader wrote after the request left, which the server has not been
     * shown yet and has therefore not ordered against anything: that one
     * is kept, and offered next pass at the rev this refusal taught.
     */
    private suspend fun conflict(
        peer: Peer,
        row: AnnotationSync,
        serverCopy: JSONObject?,
        aliases: List<WorkAlias>,
    ) {
        val record = serverCopy?.let(AnnotationWire::record)
        if (record == null) {
            // A refusal this device cannot read is not one it can act
            // on either. Recorded so the same payload is not offered
            // every run for ever; a later edit changes the hash and
            // tries again.
            Log.i(TAG, "Unreadable conflict for ${row.id}")
            syncDao.upsert(row.clearPending(rejected = row.pendingFingerprint))
            return
        }

        if (record.deleted) {
            if (stillSaying(row)) {
                annotationDao.deleteById(row.id)
                syncDao.deleteById(peer.peerId, row.id)
            } else {
                // Written again since. Keeping the tombstone's rev lets
                // the next push offer it as the deliberate revival it
                // is, rather than as a create at rev 0 the tombstone
                // would refuse.
                syncDao.upsert(
                    row.clearPending().copy(
                        rev = record.rev,
                        seq = record.seq,
                        ackedFingerprint = null,
                    ),
                )
            }
            return
        }

        if (AnnotationWire.sameContent(row.pendingJson, record)) {
            // Ours after all, stamped with a device name that has since
            // changed. Taking the rev is right; overwriting the reader's
            // words with a copy of them would not be wrong so much as
            // pointless, and it would lose an edit made meanwhile.
            syncDao.upsert(
                row.clearPending().copy(
                    rev = record.rev,
                    seq = record.seq,
                    ackedFingerprint = row.pendingFingerprint,
                ),
            )
            return
        }

        val workId = record.workId ?: return
        val home = home(peer, record, aliases, row.bookId) ?: return
        val landed = AnnotationWire.toAnnotation(record, home.bookUrl, annotationDao.byId(row.id))
        val newer = !stillSaying(row)
        if (!newer) annotationDao.upsert(landed)
        syncDao.upsert(
            row.clearPending().copy(
                bookId = home.bookUrl,
                workId = workId,
                rev = record.rev,
                seq = record.seq,
                // Acked against the server's copy either way. When the
                // reader has moved on, that is what leaves the row
                // reading dirty, so the words they actually hold are
                // offered next pass at the rev just learned.
                ackedFingerprint = AnnotationWire.fingerprint(landed, workId),
            ),
        )
    }

    /**
     * Settles a write the server would not take at all.
     *
     * Three different things arrive under one word, and treating them
     * alike goes wrong in both directions — retry a bad shape and the
     * run spins for ever; give up on an unknown work and a book that
     * merely needs renaming never syncs again. So they are told apart by
     * what the server said.
     */
    private suspend fun invalid(
        peer: Peer,
        row: AnnotationSync,
        reason: String,
        aliases: List<WorkAlias>,
        progress: Progress,
    ) {
        Log.i(TAG, "Server would not take ${row.id}: $reason")
        when {
            // Repairable: the name is stale, not the mark. The position
            // sync re-resolves it and the next run offers this again.
            reason.startsWith(REASON_UNKNOWN_WORK) || reason.startsWith(REASON_UNKNOWN_EDITION) -> {
                aliases.filter { it.workId == row.workId }
                    .forEach { progress.reresolve[it.bookUrl] = it.workId }
                syncDao.upsert(row.clearPending())
            }

            // Real, and no re-resolve helps: a work already at its
            // limit, or a phone whose clock reads next week. Both mend
            // themselves in time — one when the reader deletes
            // something, the other when the clock is set — so this waits
            // rather than hammering.
            reason.startsWith(REASON_CAP) || reason.startsWith(REASON_FUTURE) ->
                syncDao.upsert(row.clearPending().copy(retryNotBefore = now() + DEFER_MS))

            // Anything else is a shape this server will never accept,
            // including a word this client does not know. A mark left
            // unsynced is recoverable and shows up in a log; a request
            // repeated for ever against a certain refusal is neither.
            else -> syncDao.upsert(row.clearPending(rejected = row.pendingFingerprint))
        }
    }

    // -- 4. Deletes -------------------------------------------------------

    /** An agreement whose mark is gone is a deletion the server has not heard. */
    private suspend fun deletes(
        peer: Peer,
        scope: List<WorkAlias>,
        aliases: List<WorkAlias>,
        progress: Progress,
    ) {
        val books = scope.mapTo(mutableSetOf()) { it.bookUrl }
        val rows = syncDao.forPeer(peer.peerId)
            .filter { it.bookId in books && !it.pending }
            .filter { annotationDao.byId(it.id) == null }

        for (row in rows) {
            if (progress.unreachable != null) return
            if (row.rev < 1) {
                // Possibly created and never acknowledged; `rev=0` is a
                // 400, so there is nothing to send. Reconciling the work
                // is what settles it: the id is either in the live set,
                // with a rev to quote, or was never stored at all.
                syncDao.deleteById(peer.peerId, row.id)
                continue
            }
            // No URL names a dot segment, so nobody can address this
            // mark — not this client and not any other. Marking it
            // pending would be a promise that cannot be kept: a pending
            // row is skipped by the feed, by reconciliation and by every
            // future push, so the id would never converge again. Left
            // alone it stays an ordinary agreement, and the mark comes
            // back the next time the work is reconciled, which is the
            // truth: it is still on the server.
            if (!LiseurSyncApi.addressable(row.id)) {
                Log.i(TAG, "No URL can name ${row.id}; leaving it to the server")
                continue
            }
            val marked = row.copy(
                pendingKind = AnnotationSync.PENDING_DELETE,
                pendingJson = null,
                pendingRev = row.rev,
                pendingFingerprint = null,
            )
            inTransaction {
                if (!stillOurs(peer)) return@inTransaction
                syncDao.upsert(marked)
            }
            sendDelete(peer, marked, row.rev, aliases, progress)
        }
    }

    private suspend fun sendDelete(
        peer: Peer,
        row: AnnotationSync,
        rev: Long,
        aliases: List<WorkAlias>,
        progress: Progress,
    ) {
        if (!stillOurs(peer)) return
        val answer = try {
            http.delete(
                LiseurSyncApi.deleteAnnotation(peer.baseUrl, row.id, rev),
                peer.credentials,
                expected = setOf(LiseurSyncHttp.CONFLICT, LiseurSyncHttp.NOT_FOUND),
            )
        } catch (refused: LiseurSyncRejection) {
            settleRefusedDelete(peer, row, refused, aliases)
            return
        } catch (e: IOException) {
            Log.i(TAG, "Could not delete annotation ${row.id}", e)
            progress.failed(e, reasonFor(e))
            return
        }

        val tombstone = answer.optLong("rev", rev)
        val seq = answer.optLong("seq", row.seq)
        inTransaction {
            if (!stillOurs(peer)) return@inTransaction
            val current = stillAsking(peer, row) ?: return@inTransaction
            if (annotationDao.byId(row.id) == null) {
                syncDao.deleteById(peer.peerId, row.id)
                return@inTransaction
            }
            // The reader made it again — or a backup restored it —
            // while the delete was in the air. Keeping the agreement at
            // the tombstone's rev is what lets the next push land: the
            // server takes a rev-matching write onto a tombstone as a
            // deliberate revival. Dropping the row would make the same
            // push a create at rev 0, which the tombstone refuses, and
            // the newer copy would lose to a resolution that was never
            // needed.
            syncDao.upsert(
                current.clearPending().copy(
                    rev = tombstone,
                    seq = seq,
                    ackedFingerprint = null,
                ),
            )
        }
        progress.pushed++
    }

    private suspend fun settleRefusedDelete(
        peer: Peer,
        row: AnnotationSync,
        refused: LiseurSyncRejection,
        aliases: List<WorkAlias>,
    ) {
        inTransaction {
            if (!stillOurs(peer)) return@inTransaction
            val row = stillAsking(peer, row) ?: return@inTransaction
            when (refused.code) {
                // Never stored, or its tombstone has been swept. Either
                // way there is nothing left to delete.
                LiseurSyncHttp.NOT_FOUND -> syncDao.deleteById(peer.peerId, row.id)

                // It moved on elsewhere. The delete was made against a
                // mark that no longer exists in that form, so the mark
                // comes back and the reader gets to decide about the one
                // that is actually there.
                LiseurSyncHttp.CONFLICT -> {
                    val record = refused.body?.optJSONObject("server")
                        ?.let(AnnotationWire::record)
                    val home = record?.let { home(peer, it, aliases, row.bookId) }
                    if (record == null || record.deleted || home == null) {
                        syncDao.deleteById(peer.peerId, row.id)
                        return@inTransaction
                    }
                    val landed = AnnotationWire.toAnnotation(record, home.bookUrl, null)
                    // Unless the reader has written it again while the
                    // delete was in the air, in which case theirs is the
                    // newer word and the server has not seen it.
                    if (annotationDao.byId(row.id) == null) annotationDao.upsert(landed)
                    syncDao.upsert(
                        row.clearPending().copy(
                            bookId = home.bookUrl,
                            workId = record.workId ?: row.workId,
                            rev = record.rev,
                            seq = record.seq,
                            ackedFingerprint = AnnotationWire.fingerprint(
                                landed,
                                record.workId ?: row.workId,
                            ),
                        ),
                    )
                }

                else -> syncDao.upsert(row.clearPending())
            }
        }
    }

    // -- Odds and ends ----------------------------------------------------

    /**
     * The row as it stands now, if it is still the one that asked.
     *
     * Every answer is written down through here. A network call is the
     * one place in a pass where the reader gets a turn: in that window a
     * mark can be edited, deleted, or carried off by a different file
     * taking over its path, which drops the agreement on purpose so that
     * no delete is ever sent for a mark that is alive elsewhere. Writing
     * the answer onto the snapshot that was sent would undo any of the
     * three without a word.
     */
    /**
     * Whether the account this pass began for is still the connected one.
     *
     * Asked before every call, not only before every write. A pass runs
     * for as long as the network takes, and a disconnect landing in the
     * middle of one used to be caught only on the way back — by which
     * time this device had already told a server it no longer belongs to
     * about a mark, or asked it to delete one. Refusing to store the
     * answer is too late; the request is the side effect.
     */
    private suspend fun stillOurs(peer: Peer): Boolean =
        serverDao.get()?.let {
            it.accountKey == peer.accountKey && it.credentials == peer.credentials
        } == true

    private suspend fun stillAsking(peer: Peer, sent: AnnotationSync): AnnotationSync? =
        syncDao.get(peer.peerId, sent.id)?.takeIf { it.sameRequestAs(sent) }

    /**
     * Which local book a record belongs to.
     *
     * Aliases are keyed by book, so two copies of the same book — a
     * sideloaded EPUB and one downloaded from the server — can share a
     * work, and an arriving mark has more than one plausible home.
     * Picking the edition it was actually made against is the honest
     * answer; failing that, any consistent rule will do, as long as it
     * is the same one every run.
     *
     * A standalone note carries no edition anchor at all, by design, so
     * for those the first question can never answer. Choosing a copy for
     * a note arriving here the first time is fair enough, but a note
     * this device has already filed somewhere has an answer better than
     * fair: where it already lives. Without it, a conflict, a re-pair or
     * any second landing would walk the note over to whichever copy
     * sorts first, and the reader would watch it move between two copies
     * of one book.
     */
    private suspend fun home(
        peer: Peer,
        record: AnnotationWire.Record,
        aliases: List<WorkAlias>,
        known: String?,
    ): WorkAlias? {
        val candidates = aliases.filter { it.workId == record.workId }
        if (candidates.isEmpty()) return null
        val chosen = candidates
            .firstOrNull { it.editionSha != null && it.editionSha == record.editionSha }
            ?: known?.let { where -> candidates.firstOrNull { it.bookUrl == where } }
            ?: candidates.minByOrNull { it.bookUrl }
            ?: return null
        // The list was read before the call. A different file may have
        // taken over that path since, which clears the name along with
        // the marks — and writing this record against the name it used
        // to have would anchor another book's highlight into text that
        // never contained it. So the name is read again here, inside the
        // transaction that is about to commit.
        val now = identityDao.alias(chosen.bookUrl, peer.peerId) ?: return null
        return now.takeIf { it.workId == record.workId && it.usable }
    }

    /** Whether the mark has moved on from what the server confirmed. */
    private fun dirty(
        row: AnnotationSync,
        local: BookAnnotation?,
        aliases: List<WorkAlias>,
    ): Boolean {
        if (local == null) return row.rev >= 1
        val workId = aliases.firstOrNull { it.bookUrl == local.bookId }?.workId ?: row.workId
        return AnnotationWire.fingerprint(local, workId) != row.ackedFingerprint
    }

    private fun AnnotationSync.clearPending(rejected: String? = null) = copy(
        pendingKind = null,
        pendingJson = null,
        pendingRev = 0,
        pendingFingerprint = null,
        rejectedFingerprint = rejected,
    )

    private fun reasonFor(error: IOException): SyncFailure = when (error) {
        is RemoteHttpFailure -> error.reason
        is LiseurSyncRejection -> failureForCode(error.code)
        else -> SyncFailure.Offline
    }

    companion object {
        private const val TAG = "LiseurSyncAnnotations"

        /** The feed's own page cap, from `docs/openapi.yaml`. */
        const val PAGE = 500

        /** Enough pages to catch up from anything; a guard, not a budget. */
        const val MAX_PAGES = 200

        /**
         * How many works one run will ask about outright.
         *
         * The same bargain resolving makes: a library of hundreds should
         * not open with hundreds of requests, and being a run or two
         * behind on a book nobody is reading costs nothing.
         */
        const val MAX_RECONCILES_PER_RUN = 20

        /**
         * How long a work's live set is taken on trust.
         *
         * Well inside the server's six-month tombstone sweep, so a
         * device that has been away still learns of a deletion while the
         * evidence for it exists.
         */
        const val RECONCILE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        /** How long a refusal that mends itself is left alone. */
        const val DEFER_MS = 24L * 60 * 60 * 1000

        // The server's own words, from internal/store/sqlite/annotations.go
        // and internal/api/annotations.go. Matched by prefix so a
        // reworded tail does not change what this client decides.
        private const val REASON_UNKNOWN_WORK = "unknown work"
        private const val REASON_UNKNOWN_EDITION = "unknown edition"
        private const val REASON_CAP = "annotation cap"
        private const val REASON_FUTURE = "client_ts in the future"
    }
}
