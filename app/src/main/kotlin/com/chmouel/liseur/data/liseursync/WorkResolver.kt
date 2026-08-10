package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkAmbiguity
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.remote.RemoteCredentials
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
        dao.alias(book.url, peerId)?.let { existing ->
            return when {
                existing.usable -> WorkResolution.Named(existing)
                // Already asked and already answered no. Resolving again
                // would put the same question back on the screen.
                existing.confidence == WorkAlias.REJECTED ->
                    WorkResolution.Unresolved(cause = null)

                else -> WorkResolution.NeedsConfirming(existing)
            }
        }

        val fingerprint = fingerprints.of(book)
        val identifiers = WorkIdentifiers.of(
            fingerprint = fingerprint,
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

        val alias = WorkAlias(
            bookUrl = book.url,
            peerId = peerId,
            workId = workId,
            confidence = if (answer.optString("confidence") == WorkAlias.LOW) {
                WorkAlias.LOW
            } else {
                WorkAlias.HIGH
            },
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
