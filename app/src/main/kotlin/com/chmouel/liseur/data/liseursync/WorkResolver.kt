package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkAmbiguity
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.WorkIdentifier
import com.chmouel.liseur.domain.WorkIdentifiers
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** What came of asking a server which book a file holds. */
sealed interface WorkResolution {

    /** A name to sync under. */
    data class Named(val alias: WorkAlias) : WorkResolution

    /**
     * A name, but only because the title and author looked similar.
     *
     * Nothing is exchanged under it until the reader confirms it: two
     * translations of the same novel, or two editions with different
     * text, match each other this way and would otherwise trade
     * positions that land in the wrong place.
     */
    data class NeedsConfirming(val alias: WorkAlias) : WorkResolution

    /**
     * The identifiers named two different books, and the server changed
     * nothing. Merging them is the reader's decision.
     */
    data class Ambiguous(val candidates: List<String>) : WorkResolution

    /** Nothing about this book can be said yet; try again later. */
    data class Unresolved(val cause: IOException?) : WorkResolution
}

/**
 * Working out what a sync server calls a book.
 *
 * A work id is the server's own name for a book and means nothing
 * anywhere else, so it is cached per peer and re-asked for only when
 * there is nothing cached. Sending every identifier we have rather than
 * only the strongest is deliberate: the server registers all of them
 * against whichever one matched, which is how a re-encoded copy and the
 * original converge on one identity over time.
 */
class WorkResolver(
    private val dao: WorkIdentityDao,
    private val fingerprints: BookFingerprintStore,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * The cached name for [book], if it has one that may be used.
     *
     * Never talks to the server, so the sync loop can ask about a whole
     * library without a request per book.
     */
    suspend fun cached(book: Book, peerId: String): WorkAlias? =
        dao.alias(book.url, peerId)?.takeIf { it.usable }

    /** Accepts a match the server was unsure about. */
    suspend fun confirm(book: Book, peerId: String) = dao.confirm(book.url, peerId)

    /** Refuses one, for good. */
    suspend fun reject(bookUrl: String, peerId: String) = dao.reject(bookUrl, peerId)

    /**
     * Whether [alias] is a guess that [book]'s file could settle.
     *
     * True for a low-confidence match resolved before the book had a
     * file on this device: the server never saw the file's hashes, so
     * asking again now that they exist can turn the guess into a
     * certainty — or into the merge question it really was.
     */
    fun strengthenable(alias: WorkAlias, book: Book): Boolean =
        alias.awaitingAnswer && alias.editionSha == null && book.openableUrl != null

    /**
     * Whether asking the server again could settle [alias] without the
     * reader: the file has arrived since ([strengthenable]), or the
     * guess predates the catalog-id identifier and one exists to send —
     * the other device may have registered it in the meantime, turning
     * the guess into a match.
     */
    fun retryable(alias: WorkAlias, book: Book): Boolean =
        strengthenable(alias, book) ||
            (alias.awaitingAnswer && !alias.sourceSent && sourceOf(book) != null)

    /**
     * Whether [alias] still owes the server [book]'s catalog id.
     *
     * The catalog's own name for a book is the one identifier another
     * device holds before downloading anything, so an alias resolved
     * before it was sent re-resolves once to register it — that is what
     * lets the next fresh install match on it instead of asking the
     * reader.
     */
    fun owesSource(alias: WorkAlias, book: Book): Boolean =
        alias.usable && !alias.sourceSent && sourceOf(book) != null

    /**
     * The catalog server's id for [book], or null for a book this
     * server does not hold.
     *
     * Usually the book's own URL already is that id — `komga:<id>`,
     * `calibre:<uuid>` — and it is the same string on every device
     * connected to the same catalog. A local book's URL names a path on
     * this device and means nothing anywhere else, so it is never sent.
     *
     * A book uploaded from here is the exception. It keeps the URL its
     * reading history hangs off and gains the server's id as a link
     * instead (ADR-0023), so the URL still says "local" about a book the
     * server now catalogs. Reading only the URL would leave it resolving
     * on its title forever: the server would never learn that the work
     * the device has been syncing all along *is* the book it was just
     * sent, and the library would show the two side by side — one with
     * the file, one with the reading.
     */
    fun sourceOf(book: Book): String? =
        book.url.takeIf { url -> ServerKind.entries.any { it.remoteId(url) != null } }
            ?: book.remoteUuid?.let { ServerKind.LISEUR_SYNC.remoteUrl(it) }

    /**
     * This server's own id for [book], however the book came by it.
     *
     * Both halves of [sourceOf]'s story, narrowed to the server being
     * talked to: a catalog book names it in its URL, an uploaded one
     * carries it as a link.
     */
    fun catalogIdOf(book: Book): String? =
        ServerKind.LISEUR_SYNC.remoteId(book.url) ?: book.remoteUuid

    /**
     * Asks the server what to call [book], caching whatever it says.
     *
     * A book this server catalogs is resolved through the catalog route
     * instead: the server reads the identifiers off its own record, so
     * the answer carries the file's hashes even when the file itself was
     * never downloaded here, and two devices browsing the same library
     * name it identically. A book uploaded from here counts — that route
     * is also what tells the server which work its new book belongs to.
     *
     * Any other book with no file on the device still resolves, on its
     * title and author: that is weaker, and the server will say so, but
     * refusing to sync a book until it has been downloaded would leave
     * the reader's place stranded on whichever device happened to hold
     * the file.
     */
    suspend fun resolve(
        book: Book,
        peerId: String,
        baseUrl: String,
        credentials: RemoteCredentials,
    ): WorkResolution {
        val existing = dao.alias(book.url, peerId)
        existing?.let {
            when {
                // A usable name that never told the server which catalog
                // entry it is resolves once more to hand that over; any
                // other usable name is simply used.
                it.usable ->
                    if (!owesSource(it, book)) return WorkResolution.Named(it)
                // Already asked and already answered no. Resolving again
                // would put the same question back on the screen.
                it.confidence == WorkAlias.REJECTED ->
                    return WorkResolution.Unresolved(cause = null)
                // A guess made while the book was catalog-only, when a
                // title and an author were all there was to offer. The
                // file, or the catalog id, may be able to answer the
                // question instead of the reader now.
                retryable(it, book) -> Unit
                else -> return WorkResolution.NeedsConfirming(it)
            }
        }

        val catalogBookId = catalogIdOf(book)
        if (catalogBookId != null) {
            return resolveCatalog(book, catalogBookId, existing, peerId, baseUrl, credentials)
        }
        return resolveByIdentifiers(book, existing, peerId, baseUrl, credentials)
    }

    /**
     * Names a book this server already catalogs.
     *
     * No identifiers are sent: the server collects them from the
     * catalog, which knows the file's digests and embedded ids even
     * when this device has only browsed and not downloaded. The answer
     * is idempotent, so asking again on a reinstall returns the same
     * work rather than a fresh one.
     */
    private suspend fun resolveCatalog(
        book: Book,
        bookId: String,
        existing: WorkAlias?,
        peerId: String,
        baseUrl: String,
        credentials: RemoteCredentials,
    ): WorkResolution {
        val answer = try {
            http.post(
                url = LiseurSyncApi.resolveBook(baseUrl, bookId),
                credentials = credentials,
                json = JSONObject().apply {
                    // The reader's earlier yes to a doubtful match
                    // travels along, as it does on the identifier path.
                    if (existing?.confirmed == true) put("confirmed", true)
                },
                expected = setOf(LiseurSyncHttp.CONFLICT),
            )
        } catch (rejection: LiseurSyncRejection) {
            return ambiguous(book, peerId, rejection)
        } catch (e: IOException) {
            Log.i(TAG, "Could not resolve a catalog book yet", e)
            return WorkResolution.Unresolved(e)
        }

        return applyCatalogAnswer(book, existing, peerId, answer)
    }

    /**
     * Files what the server said about a catalog book.
     *
     * Shared by the one-book route and the batch, so the two cannot
     * make a different alias out of the same answer — the mirror of the
     * server sharing one resolver behind both.
     */
    private suspend fun applyCatalogAnswer(
        book: Book,
        existing: WorkAlias?,
        peerId: String,
        answer: JSONObject,
    ): WorkResolution {
        val workId = answer.optString("work_id").takeIf { it.isNotEmpty() }
            ?: return WorkResolution.Unresolved(cause = null)
        dao.clearAmbiguity(book.url, peerId)

        // The digest the server matched on, when it said which: kept so
        // ops can carry the edition without the file ever being hashed
        // here.
        val identifiers = answer.optJSONArray("identifiers")
        val editionSha = (0 until (identifiers?.length() ?: 0))
            .mapNotNull { identifiers?.optJSONObject(it) }
            .firstOrNull { it.optString("kind") == "sha256" }
            ?.optString("value")
            ?.takeIf { it.isNotEmpty() }

        val sameWork = existing?.workId == workId
        val confidence = if (answer.optString("confidence") == WorkAlias.LOW) {
            WorkAlias.LOW
        } else {
            WorkAlias.HIGH
        }
        val alias = WorkAlias(
            bookUrl = book.url,
            peerId = peerId,
            workId = workId,
            confidence = confidence,
            confirmed = sameWork && existing?.confirmed == true,
            seeded = sameWork && existing?.seeded == true,
            annotationsReconciledAt = if (sameWork) {
                existing?.annotationsReconciledAt ?: 0
            } else {
                0
            },
            // A low answer registers nothing server-side, so it still
            // owes the one re-resolve that carries the reader's yes —
            // the same debt the identifier path tracks with this flag.
            sourceSent = confidence == WorkAlias.HIGH,
            editionSha = editionSha ?: existing?.editionSha,
            resolvedAt = now(),
        )
        dao.upsert(alias)

        return if (alias.usable) {
            WorkResolution.Named(alias)
        } else {
            WorkResolution.NeedsConfirming(alias)
        }
    }

    /**
     * Names as many of [books] as one request can, before they are asked
     * about one at a time.
     *
     * A device that has just signed in has a name here for none of its
     * books, and a book with no name can neither send a position nor
     * receive one — so the whole shelf waits on this. Asking per book
     * was hundreds of round trips; `POST /v1/books/resolve` is one.
     *
     * Returns what it filed, keyed by book url, so the caller can lay it
     * over the aliases it already read rather than reading them again.
     * The per-book pass that follows then finds these books named and
     * asks nothing. That way this is only ever an optimisation: a server
     * without the route, or a request that fails, leaves the per-book
     * path to do exactly what it did before — which is also why a
     * failure is not reported as trouble, since the route behind it is
     * about to ask the same question and its answer is the one worth
     * having.
     *
     * [known] is every alias already on file for this peer. It is passed
     * in rather than looked up a book at a time because the caller has
     * just read the lot in one query, and a shelf of four hundred is
     * four hundred point queries every run otherwise — including the
     * runs where nothing at all is outstanding.
     *
     * Only books whose question the batch can carry are sent.
     * `confirmed` there applies to every id at once, so a book holding
     * the reader's yes to a doubtful match never joins one: the
     * single-book route can say yes for that book alone. That filter is
     * also why a lone pending book is batched like any other — there is
     * no yes left in the queue to lose, and one id costs the one request
     * either route would have spent.
     */
    suspend fun prefetchCatalog(
        books: List<Book>,
        known: Map<String, WorkAlias>,
        peerId: String,
        baseUrl: String,
        credentials: RemoteCredentials,
    ): Map<String, WorkAlias> {
        val pending = books.mapNotNull { book ->
            val bookId = catalogIdOf(book) ?: return@mapNotNull null
            val existing = known[book.url]
            // The same questions `resolve` asks before spending a
            // request, and for the same reasons: a name already good
            // enough, an answer already given, or a question already in
            // front of the reader.
            val worth = when {
                existing == null -> true
                existing.confirmed -> false
                existing.usable -> owesSource(existing, book)
                existing.confidence == WorkAlias.REJECTED -> false
                else -> retryable(existing, book)
            }
            if (worth) Triple(book, bookId, existing) else null
        }
        val filed = mutableMapOf<String, WorkAlias>()
        if (pending.isEmpty()) return filed

        var room = MAX_RESOLVE_BATCH
        var sent = 0
        while (sent < pending.size) {
            val chunk = pending.subList(sent, minOf(sent + room, pending.size))
            val answers = try {
                http.post(
                    url = LiseurSyncApi.resolveBooks(baseUrl),
                    credentials = credentials,
                    json = JSONObject().put(
                        "book_ids",
                        JSONArray().apply { chunk.forEach { put(it.second) } },
                    ),
                    expected = setOf(LiseurSyncHttp.BAD_REQUEST, LiseurSyncHttp.TOO_LARGE),
                ).optJSONArray("results")
            } catch (rejection: LiseurSyncRejection) {
                // A batch this server will not take at this size is cut
                // to what it said it would and asked again; the books
                // are still owed, so nothing moves on. Any other refusal
                // is the per-book route's to report.
                val cut = roomFor(chunk.size, rejection)
                if (cut == null) {
                    Log.i(TAG, "Could not name the shelf in one request; asking book by book", rejection)
                    return filed
                }
                Log.i(TAG, "The server will not name ${chunk.size} books at once; asking $cut at a time")
                room = cut
                continue
            } catch (e: IOException) {
                Log.i(TAG, "Could not name the shelf in one request; asking book by book", e)
                return filed
            } ?: return filed

            for (index in 0 until answers.length()) {
                val answer = answers.optJSONObject(index) ?: continue
                // Results come back in the order they were asked for,
                // but the id is matched rather than the position
                // trusted: filing one book's work under another's url
                // would sync the wrong reading into it.
                val entry = chunk.firstOrNull { it.second == answer.optString("book_id") }
                    ?: continue
                // A book refused on its own is left entirely alone. The
                // per-book pass asks again and takes the answer through
                // the one path that knows how to put an ambiguity to
                // the reader.
                if (answer.optString("error").isNotEmpty()) continue
                val resolution = applyCatalogAnswer(entry.first, entry.third, peerId, answer)
                aliasOf(resolution)?.let { filed[entry.first.url] = it }
            }
            sent += chunk.size
        }
        return filed
    }

    /**
     * How many books to offer after a refusal about the size of the last
     * attempt, or nothing when the refusal was about something else.
     *
     * A limit the server named is only taken if it is genuinely smaller.
     * A bound that would leave the chunk the size it already was is a
     * server disagreeing with itself, and asking the identical question
     * again is how that becomes a loop rather than a refusal.
     */
    private fun roomFor(tried: Int, rejection: LiseurSyncRejection): Int? {
        val aboutSize = rejection.code == LiseurSyncHttp.TOO_LARGE ||
            rejection.errorCode == LiseurSyncRejection.BATCH_TOO_LARGE
        if (!aboutSize || tried <= 1) return null
        return rejection.limit?.takeIf { it < tried } ?: (tried / 2)
    }

    /** The name a resolution settled on, whether or not it may be used yet. */
    private fun aliasOf(resolution: WorkResolution): WorkAlias? = when (resolution) {
        is WorkResolution.Named -> resolution.alias
        is WorkResolution.NeedsConfirming -> resolution.alias
        else -> null
    }

    private suspend fun resolveByIdentifiers(
        book: Book,
        existing: WorkAlias?,
        peerId: String,
        baseUrl: String,
        credentials: RemoteCredentials,
    ): WorkResolution {
        val fingerprint = fingerprints.of(book)
        val sourceId = sourceOf(book)
        val identifiers = WorkIdentifiers.of(
            fingerprint = fingerprint,
            sourceId = sourceId,
            dcIdentifier = WorkIdentifiers.dcFrom(book.workId, book.title, book.author),
            title = book.title,
            author = book.author,
        )
        if (identifiers.isEmpty()) return WorkResolution.Unresolved(cause = null)

        val answer = try {
            http.post(
                url = LiseurSyncApi.url(baseUrl, LiseurSyncApi.RESOLVE),
                credentials = credentials,
                // The reader's earlier yes to this match travels along:
                // the server registers stronger identifiers on a fuzzy
                // hit only on somebody's word that it is the same book.
                json = request(identifiers, book, confirmed = existing?.confirmed == true),
                expected = setOf(LiseurSyncHttp.CONFLICT),
            )
        } catch (rejection: LiseurSyncRejection) {
            return ambiguous(book, peerId, rejection)
        } catch (e: IOException) {
            Log.i(TAG, "Could not resolve a book yet", e)
            return WorkResolution.Unresolved(e)
        }

        val workId = answer.optString("work_id").takeIf { it.isNotEmpty() }
            ?: return WorkResolution.Unresolved(cause = null)

        // A resolve that succeeds settles any earlier disagreement: the
        // server has just told us, with these identifiers, which book
        // this is.
        dao.clearAmbiguity(book.url, peerId)

        // A re-resolve of a name already in use — to register the catalog
        // id, or to let the file settle a guess — must not lose what the
        // reader and the seed pass already established about it.
        val sameWork = existing?.workId == workId
        val confidence = if (answer.optString("confidence") == WorkAlias.LOW) {
            WorkAlias.LOW
        } else {
            WorkAlias.HIGH
        }
        val alias = WorkAlias(
            bookUrl = book.url,
            peerId = peerId,
            workId = workId,
            confidence = confidence,
            confirmed = sameWork && existing?.confirmed == true,
            seeded = sameWork && existing?.seeded == true,
            annotationsReconciledAt = if (sameWork) {
                existing?.annotationsReconciledAt ?: 0
            } else {
                0
            },
            // The server registers nothing on a fuzzy, unconfirmed hit,
            // so a low answer leaves the catalog id still owed.
            sourceSent = sourceId != null && confidence == WorkAlias.HIGH,
            editionSha = fingerprint?.sha256,
            resolvedAt = now(),
        )
        dao.upsert(alias)

        return if (alias.usable) {
            WorkResolution.Named(alias)
        } else {
            WorkResolution.NeedsConfirming(alias)
        }
    }

    private suspend fun ambiguous(
        book: Book,
        peerId: String,
        rejection: LiseurSyncRejection,
    ): WorkResolution {
        val works = rejection.body?.optJSONArray("works").let(::strings)
        if (works.size < 2) return WorkResolution.Unresolved(cause = rejection)

        dao.upsert(
            WorkAmbiguity(
                bookUrl = book.url,
                peerId = peerId,
                workIds = works.joinToString("\n"),
                noticedAt = now(),
            ),
        )
        return WorkResolution.Ambiguous(works)
    }

    private fun request(
        identifiers: List<WorkIdentifier>,
        book: Book,
        confirmed: Boolean,
    ) = JSONObject().apply {
        put(
            "identifiers",
            JSONArray().apply {
                identifiers.forEach {
                    put(JSONObject().put("kind", it.kind).put("value", it.value))
                }
            },
        )
        put("title", book.title)
        book.author?.let { put("author", it) }
        if (confirmed) put("confirmed", true)
    }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf(String::isNotEmpty) }

    private companion object {
        const val TAG = "liseur-sync-works"

        /**
         * How many books one batch resolve may name.
         *
         * The server's own limit, which it will refuse above rather than
         * trim; matching it here keeps a large library from having to
         * learn that the hard way.
         */
        const val MAX_RESOLVE_BATCH = 500
    }
}
