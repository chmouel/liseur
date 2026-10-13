package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import com.chmouel.liseur.data.remote.serverAnswered
import com.chmouel.liseur.translate.PulledTranslation
import com.chmouel.liseur.translate.SavedTranslationForPush
import com.chmouel.liseur.translate.SavedTranslations
import com.chmouel.liseur.translate.TranslationSyncState
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the sentence translation cache in step with a liseur-sync
 * server (ADR-0052).
 *
 * A sentence translated by page translation is cached on this phone
 * under a one-way hash of the translation identity and the sentence.
 * The server holds the same rows for the account, keyed by the same
 * hash, so a second device reading the same book pulls them and calls
 * no translation service. The sentence itself never travels: only the
 * hash and the translation do.
 *
 * Nothing here is a queue. What is owed is derived from stored state,
 * exactly as the sync invariants ask: the push sends every sentence
 * saved above a watermark the server's own acknowledgements moved, and
 * the pull resumes from a cursor that only ever advances in the same
 * breath as the page it covers. A run interrupted anywhere is simply
 * repeated; an identical push changes nothing on the server, and a
 * repeated pull page is merged by ignoring what is already here.
 *
 * The pass runs inside the position sync, like the annotation pass: a
 * translation is worth syncing exactly when a position is, and a
 * second worker would mean a second set of retries racing the first.
 */
class LiseurSyncTranslations(
    private val serverDao: RemoteServerDao,
    private val identityDao: WorkIdentityDao,
    /**
     * The store, reached lazily: the pass opens it on its first run,
     * which for a phone that has never translated anything is the
     * point — the pull fills the cache before the reader ever has to
     * call a translation service, not only after.
     */
    private val saved: suspend () -> SavedTranslations,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The account this pass is speaking to. */
    data class Peer(
        val baseUrl: String,
        val credentials: RemoteCredentials,
        val peerId: String,
        val accountKey: String,
    )

    /**
     * What the pass managed, and what it would like the caller to do.
     *
     * [reresolve] names books whose work id the server did not
     * recognise, each against the stale id it was refused under — the
     * same message the annotation pass leaves, for the same recovery.
     */
    data class Outcome(
        val pulled: Int = 0,
        val pushed: Int = 0,
        val failure: SyncFailure? = null,
        val unreachable: SyncFailure? = null,
        val reresolve: Map<String, String> = emptyMap(),
    )

    private class Progress {
        var pulled = 0
        var pushed = 0
        var failure: SyncFailure? = null
        var unreachable: SyncFailure? = null
        val reresolve = mutableMapOf<String, String>()
        // A 429 is answered, so it never sets [unreachable], but letting
        // the pass run on would send its remaining pages and batches into
        // a server that just asked it to slow down.
        private var rateLimited = false
        val stopped: Boolean
            get() = unreachable != null || rateLimited || failure == SyncFailure.Unauthorised ||
                failure == SyncFailure.Forbidden || failure == SyncFailure.InsecureTransport

        fun failed(cause: IOException, reason: SyncFailure) {
            if (failure == null || reason == SyncFailure.Unauthorised ||
                (failure != SyncFailure.Unauthorised &&
                    (reason == SyncFailure.Forbidden || reason == SyncFailure.InsecureTransport))
            ) failure = reason
            if (!cause.serverAnswered()) unreachable = unreachable ?: reason
            if (reason is SyncFailure.ServerError && reason.code == 429) rateLimited = true
        }

        fun outcome() = Outcome(pulled, pushed, failure, unreachable, reresolve)
    }

    /**
     * Servers found not to serve the translation routes, so they are
     * asked once per session. The guard is a 404 with no body — the
     * mux's own answer for a path no route names — which is how
     * [LiseurSyncSettings] tells an older server too; a 404 that says
     * `work not found` has a body, and is the server knowing the routes
     * but not the book.
     */
    private val unsupported = mutableSetOf<String>()

    /**
     * Runs one translation pass.
     *
     * [book] narrows the run to one book, for the runs that fire from
     * the reader; a full run offers every book the account has a
     * usable name for. A book with no name yet is skipped rather than
     * resolved here: naming is the position sync's job, and the next
     * run finds the book once it has one.
     */
    suspend fun sync(peer: Peer, book: String?): Outcome {
        // The bookkeeping of peers the connected account is not goes
        // before anything else this pass does, and from the account
        // connected now rather than the one this pass began for: a
        // disconnect that landed between a past pass's ownership check
        // and its write left a row nothing else collects, and a pass
        // running on after its account was renamed must not take the
        // new name's progress for dead (see
        // [SavedTranslations.forgetPeersBeyond]).
        saved().forgetPeersBeyond { setOfNotNull(serverDao.get()?.accountKey) }
        if (peer.baseUrl in unsupported) return Outcome()
        val progress = Progress()
        val aliases = identityDao.aliasesFor(peer.peerId).filter { it.usable }
        val scope = if (book == null) aliases else aliases.filter { it.bookUrl == book }
        for (alias in scope) {
            if (progress.stopped) break
            if (!stillOurs(peer)) break
            // Where this peer stands under this work name: a book whose
            // work was re-resolved starts over from nothing (see
            // [SavedTranslations.ensureWork]).
            val state = saved().ensureWork(peer.peerId, alias.bookUrl, alias.workId)
            pull(peer, alias.bookUrl, alias.workId, state.pullCursor, progress)
            // A server with no translation routes has none to push to
            // either; the run is over for this peer, not just the pull.
            if (peer.baseUrl in unsupported) break
            if (progress.stopped) break
            // A book whose name the server disowned is not pushed under
            // that name; the next run offers it under the fresh one.
            if (alias.bookUrl in progress.reresolve) continue
            push(peer, alias.bookUrl, alias.workId, state, progress)
        }
        // A disconnect that landed mid-book can leave this pass's own
        // last writes — a landed page's cursor, an acknowledged push's
        // watermark, a book's fresh state — as the only trace of a peer
        // that is gone. Its numbers are nobody's now, so they go; the
        // sentences stay, as they do when one account goes.
        if (!stillOurs(peer)) saved().forgetPeer(peer.peerId)
        return progress.outcome()
    }

    // -- Pull --------------------------------------------------------------

    /**
     * Takes what the account holds for the book that this phone has
     * not, then leaves the cursor where the last page stopped.
     *
     * Each page lands and moves the cursor in one step inside the
     * store, the rule the position sync's cursor lives by: a cursor
     * that stepped over a page whose contents were lost has lost them
     * permanently. A cursor the server refuses is dropped and the pull
     * restarted from the beginning — re-reading costs requests, and
     * skipping rows loses them.
     */
    private suspend fun pull(peer: Peer, bookUrl: String, workId: String, from: String, progress: Progress) {
        val store = saved()
        var cursor = from
        val ticket = store.pullTicket(bookUrl)
        var restarted = false
        var guard = MAX_PAGES
        while (guard-- > 0) {
            if (!stillOurs(peer)) return
            val page = try {
                http.get(
                    LiseurSyncApi.workTranslations(peer.baseUrl, workId, PAGE, cursor),
                    peer.credentials,
                    expected = setOf(LiseurSyncHttp.BAD_REQUEST, LiseurSyncHttp.NOT_FOUND),
                )
            } catch (e: LiseurSyncRejection) {
                when {
                    isMissingRoute(e) -> {
                        unsupported += peer.baseUrl
                        Log.i(TAG, "Server does not serve translations; not asking again this session")
                        return
                    }

                    e.code == LiseurSyncHttp.NOT_FOUND -> {
                        // The server no longer holds that work; the same
                        // stale name the op log recovers from.
                        Log.i(TAG, "The server no longer holds $workId; asking for a new name")
                        identityDao.deleteAliasIfStale(bookUrl, peer.peerId, workId)
                        progress.reresolve[bookUrl] = workId
                        return
                    }

                    // A cursor this server will not take. Dropping it and
                    // starting over is the recovery the protocol names,
                    // once per book per run: a server that refuses every
                    // cursor is one whose answer is worth hearing twice.
                    e.code == LiseurSyncHttp.BAD_REQUEST && !restarted -> {
                        restarted = true
                        if (cursor.isNotEmpty()) {
                            Log.i(TAG, "Restarting $bookUrl's translation pull from the beginning")
                            store.restartPull(peer.peerId, bookUrl)
                            cursor = ""
                        }
                        continue
                    }

                    else -> {
                        Log.i(TAG, "The server would not send the translations of $workId", e)
                        progress.failed(e, reasonFor(e))
                        return
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Could not read the translations of $workId", e)
                progress.failed(e, reasonFor(e))
                return
            }

            val rows = pulledRows(page.optJSONArray("translations"))
            val next = page.optString("next_cursor")
            if (rows.isEmpty() && next.isEmpty()) return
            progress.pulled += store.landPulled(
                peer.peerId, bookUrl, ticket, rows,
                next.ifEmpty { cursor },
            )
            if (!page.optBoolean("has_more", false)) return
            if (next.isEmpty()) return
            cursor = next
        }
        Log.i(TAG, "Stopped reading translations after $MAX_PAGES pages")
    }

    /** The page's rows, unreadable ones aside; a row this device cannot read does not hold the cursor back. */
    private fun pulledRows(array: JSONArray?): List<PulledTranslation> {
        if (array == null) return emptyList()
        val rows = mutableListOf<PulledTranslation>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val key = row.optString("key")
            val translation = row.optString("translation")
            if (key.isNotEmpty() && translation.isNotEmpty()) rows += PulledTranslation(key, translation)
        }
        return rows
    }

    // -- Push --------------------------------------------------------------

    /**
     * Sends every sentence saved above the peer's watermark, in
     * batches, moving the watermark only after the server acknowledges
     * a batch — so an interrupted push is simply repeated, and the
     * identical batch is a no-op on the server. The watermark is the
     * `(saved_at, rowid)` pair of the last row acknowledged, because a
     * page's sentences share a millisecond and the time alone cannot
     * say which of them were sent.
     */
    private suspend fun push(peer: Peer, bookUrl: String, workId: String, state: TranslationSyncState, progress: Progress) {
        val store = saved()
        var watermark = state
        while (true) {
            if (progress.stopped || !stillOurs(peer)) return
            val batch = store.unsynced(bookUrl, watermark, BATCH)
            if (batch.isEmpty()) return
            val taken = post(peer, bookUrl, workId, batch, progress)
            if (taken < 0) return
            // Only now, with the server's answer: a batch whose
            // acknowledgement was lost is sent again, and the server
            // writes nothing the second time.
            val acked = batch.last()
            store.recordPushed(peer.peerId, bookUrl, acked)
            watermark = watermark.copy(pushedSavedAt = acked.savedAt, pushedRowid = acked.rowId)
            progress.pushed += taken
        }
    }

    /**
     * Sends one batch, halving it as long as the server refuses the
     * lot: the batch size is the server's to choose — its own cap, or a
     * proxy's — and a request refused for its size is worth splitting
     * rather than replaying unchanged until the account is disconnected.
     * Down to a single item, which is as far as splitting can go.
     *
     * A single item refused for a shape this protocol names — a key
     * that is not the hash this client derives, a value over the
     * server's bound, a NUL, a missing field — is set aside: nothing
     * here can mend it, the sentence stays cached locally, and the run
     * moves on rather than repeating a certain refusal. Anything else
     * the server says about a single item is a refusal this pass does
     * not understand, and is surfaced as a failure rather than quietly
     * written off as sent.
     *
     * @return how many rows the server took, or -1 when the push must
     *   stop for this book.
     */
    private suspend fun post(
        peer: Peer,
        bookUrl: String,
        workId: String,
        batch: List<SavedTranslationForPush>,
        progress: Progress,
    ): Int {
        if (!stillOurs(peer)) return -1
        val body = JSONObject()
        val items = JSONArray()
        for (row in batch) {
            items.put(JSONObject().put("key", row.key).put("translation", row.translation))
        }
        body.put("translations", items)
        return try {
            http.post(
                LiseurSyncApi.pushWorkTranslations(peer.baseUrl, workId),
                peer.credentials,
                body,
                expected = setOf(LiseurSyncHttp.BAD_REQUEST, LiseurSyncHttp.TOO_LARGE),
            )
            batch.size
        } catch (refused: LiseurSyncRejection) {
            when {
                refused.isUnknownWork -> {
                    // The name is stale, not the sentences. The position
                    // sync re-resolves the book and the next run offers
                    // them again.
                    Log.i(TAG, "The server no longer holds $workId; asking for a new name")
                    identityDao.deleteAliasIfStale(bookUrl, peer.peerId, workId)
                    progress.reresolve[bookUrl] = workId
                    -1
                }

                batch.size > 1 -> {
                    Log.i(TAG, "The server would not take the batch; halving it: ${refused.error}")
                    val half = batch.size / 2
                    val first = post(peer, bookUrl, workId, batch.take(half), progress)
                    if (first < 0) {
                        -1
                    } else {
                        val rest = post(peer, bookUrl, workId, batch.drop(half), progress)
                        if (rest < 0) -1 else first + rest
                    }
                }

                refused.errorCode in REFUSED_FOR_GOOD -> {
                    Log.i(TAG, "The server would not take a translation: ${refused.error}")
                    0
                }

                else -> {
                    Log.i(TAG, "The server refused a translation for a reason this pass does not know: ${refused.error}")
                    progress.failed(refused, reasonFor(refused))
                    -1
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Could not send translations", e)
            progress.failed(e, reasonFor(e))
            -1
        }
    }

    // -- Odds and ends -----------------------------------------------------

    /** A 404 with no body is the mux's own answer for a route that is not there. */
    private fun isMissingRoute(e: LiseurSyncRejection): Boolean =
        e.code == LiseurSyncHttp.NOT_FOUND && e.body == null

    /**
     * Whether the account this pass began for is still the connected
     * one, asked before every call: a pass runs for as long as the
     * network takes, and a disconnect landing mid-run must not have
     * this device telling a server it no longer belongs to about its
     * reading.
     */
    private suspend fun stillOurs(peer: Peer): Boolean =
        serverDao.get()?.let {
            it.accountKey == peer.accountKey && it.credentials == peer.credentials
        } == true

    private fun reasonFor(error: IOException): SyncFailure = when (error) {
        is RemoteHttpFailure -> error.reason
        is LiseurSyncRejection -> failureForCode(error.code)
        else -> SyncFailure.Offline
    }

    private companion object {
        private const val TAG = "LiseurSyncTranslations"

        /** The feed's own page cap, from `docs/openapi.yaml`. */
        const val PAGE = 500

        /** Enough pages to catch up from anything; a guard, not a budget. */
        const val MAX_PAGES = 200

        /** The server's own push bound (`ops.max_batch`); split below it when a server sets less. */
        const val BATCH = 500

        /**
         * The item refusals this protocol names, from
         * `docs/openapi.yaml`: the shapes a single cached translation
         * can have that no retry and no smaller batch will mend.
         */
        val REFUSED_FOR_GOOD = setOf("bad_key", "too_large", "bad_value", "missing_field")
    }
}
