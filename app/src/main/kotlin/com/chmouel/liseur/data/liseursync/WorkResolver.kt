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
     * The catalog server's id for [book], or null for a local file.
     *
     * The book's own URL already is that id — `komga:<id>`,
     * `calibre:<uuid>` — and it is the same string on every device
     * connected to the same catalog. A local book's URL names a path on
     * this device and means nothing anywhere else, so it is never sent.
     */
    fun sourceOf(book: Book): String? =
        book.url.takeIf { url -> ServerKind.entries.any { it.remoteId(url) != null } }

    /**
     * Asks the server what to call [book], caching whatever it says.
     *
     * A book with no file on the device still resolves, on its own
     * identifier and its title: that is weaker, and the server will say
     * so, but refusing to sync a book until it has been downloaded would
     * leave the reader's place stranded on whichever device happened to
     * hold the file.
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
                // file is here now, and its own identifiers can answer
                // the question instead of the reader.
                strengthenable(it, book) -> Unit
                else -> return WorkResolution.NeedsConfirming(it)
            }
        }

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
                json = request(identifiers, book),
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
        val alias = WorkAlias(
            bookUrl = book.url,
            peerId = peerId,
            workId = workId,
            confidence = if (answer.optString("confidence") == WorkAlias.LOW) {
                WorkAlias.LOW
            } else {
                WorkAlias.HIGH
            },
            confirmed = sameWork && existing?.confirmed == true,
            seeded = sameWork && existing?.seeded == true,
            sourceSent = sourceId != null,
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

    private fun request(identifiers: List<WorkIdentifier>, book: Book) = JSONObject().apply {
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
    }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf(String::isNotEmpty) }

    private companion object {
        const val TAG = "liseur-sync-works"
    }
}
