package com.chmouel.liseur.data.grimmory

import android.util.Log
import com.chmouel.liseur.data.komga.KomgaBook
import com.chmouel.liseur.data.komga.KomgaBooks
import com.chmouel.liseur.data.komga.KomgaHrefs
import com.chmouel.liseur.data.komga.KomgaHttp
import com.chmouel.liseur.data.komga.KomgaPage
import com.chmouel.liseur.data.komga.longOrNull
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.CatalogWalk
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches book lists from Grimmory's Komga-compatible shim.
 *
 * Two things differ from talking to a real Komga, and both come from the
 * shim being a compatibility layer rather than the API it imitates.
 *
 * The listing route is `GET /v1/books`, not the `POST /v1/books/list`
 * Komga's own client uses: the shim answers that one 501, so a Komga
 * client points at Grimmory finds an empty library and no error worth
 * showing.
 *
 * And there is no server-side filter, so the whole catalog — comics,
 * PDFs, audiobooks and all — comes over the wire and is sorted out here.
 */
class GrimmoryCatalogClient(private val http: KomgaHttp = KomgaHttp()) : CatalogSource {

    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): CatalogWalk = withContext(Dispatchers.IO) {
        var page = 0
        val walk = Walk()

        while (page < MAX_PAGES) {
            coroutineContext.ensureActive()

            val body = http.getObject(booksUrl(baseUrl, page), credentials)
            val answer = KomgaBooks.parsePage(
                json = body,
                hrefs = HREFS,
                validateId = GrimmoryId::parse,
            )

            // Anything the parser could not make a book of is a response
            // this client does not understand, not a book that went
            // away. Streaming what did parse is useful; calling the walk
            // complete after it is how a library gets deleted.
            val unreadable = answer.entriesOnPage - answer.books.size
            val readable = answer.books.filter { classify(it.mediaType) == Media.READABLE }
            val unknown = answer.books.map { classify(it.mediaType) }.count { it == Media.UNKNOWN }

            onPage(readable.map(KomgaBook::book))
            walk.entriesSeen += answer.entriesOnPage
            walk.readableSeen += readable.size
            answer.books.forEach { walk.ids += it.book.remoteId }

            if (!answer.contentWasArray) {
                // Not a page at all. There is nothing to stream and no
                // reason to believe the paging fields around it, so
                // unlike the two cases below this one does stop.
                Log.w(TAG, "Page $page did not carry a list of books")
                return@withContext CatalogWalk(complete = false)
            }

            if (unreadable > 0) {
                // An entry that is not an object, or carrying an id
                // this client will not address. Its well-formed
                // neighbours have already streamed, and so will the
                // pages after it: stopping here would hide the rest of
                // the library behind one malformed row, which is the
                // same mistake as stopping on an unknown media type.
                walk.unreadableSeen += unreadable
                Log.w(
                    TAG,
                    "Page $page held $unreadable entr(ies) this client could not read; " +
                        "not treating the catalog as complete",
                )
            }

            if (unknown > 0) {
                // A media type nobody here has heard of. It cannot be
                // shelved, and it must not be pruned either: this is
                // exactly how a renamed type would quietly delete the
                // books that adopted it.
                //
                // The walk carries on regardless. Stopping here would
                // let one odd book — an audiobook, a file Grimmory
                // itself could not identify — hide every EPUB on the
                // pages after it, which is a library that browses as
                // half its size for a reason nobody can see. Giving up
                // the pruning is the whole of the caution needed.
                walk.unknownSeen += unknown
                Log.w(
                    TAG,
                    "Page $page held $unknown book(s) of a media type this build does " +
                        "not know, e.g. " +
                        answer.books.first { classify(it.mediaType) == Media.UNKNOWN }.mediaType,
                )
            }

            // The same book twice fills a page the server counted two
            // entries for, so the counts all agree while a book that
            // was never sent is pruned as gone.
            //
            // Entries that never became books are counted back in, or
            // one malformed row would read as a duplicate and stop the
            // walk by the back door -- pruning is already forfeited
            // above, and there is nothing more to give up here.
            if (walk.ids.size + walk.unreadableSeen != walk.entriesSeen) {
                Log.w(TAG, "The catalog listed the same book more than once")
                return@withContext CatalogWalk(complete = false)
            }

            when (pagingVerdict(body, answer, page, walk)) {
                Paging.LAST -> {
                    if (walk.unreadableSeen > 0) {
                        Log.w(
                            TAG,
                            "The catalog held ${walk.unreadableSeen} entr(ies) this client " +
                                "could not read; not treating it as fully understood",
                        )
                        return@withContext CatalogWalk(complete = false)
                    }
                    if (walk.unknownSeen > 0) {
                        Log.w(
                            TAG,
                            "The catalog held ${walk.unknownSeen} book(s) of an unknown " +
                                "media type; not treating it as fully understood",
                        )
                        return@withContext CatalogWalk(complete = false)
                    }
                    // Every book on the server was filtered out. That is
                    // what a changed media type looks like from here,
                    // and it is indistinguishable from a library that
                    // holds nothing Liseur can open — so the walk gives
                    // up the right to prune rather than empty the shelf
                    // on a guess. A server that really holds no EPUBs
                    // loses nothing by this: there is nothing to prune.
                    if (walk.entriesSeen > 0 && walk.readableSeen == 0L) {
                        Log.w(
                            TAG,
                            "The catalog held ${walk.entriesSeen} books and none were " +
                                "readable; not treating that as an empty library",
                        )
                        return@withContext CatalogWalk(complete = false)
                    }
                    return@withContext CatalogWalk(complete = true)
                }

                Paging.UNTRUSTWORTHY -> {
                    Log.w(TAG, "Page $page did not describe its own place in the catalog")
                    return@withContext CatalogWalk(complete = false)
                }

                Paging.MORE -> page++
            }
        }

        Log.i(TAG, "Stopped after $MAX_PAGES pages of catalog")
        CatalogWalk(complete = false)
    }

    /**
     * What has been seen so far, and what the first page said the whole
     * catalog was.
     *
     * The shape of the catalog is pinned to the first answer and held
     * against every page after it. Checking each page only against
     * itself lets a catalog shrink underneath the walk — page 0 saying
     * there are three books, page 1 saying there are two and that it is
     * the last — and the third book, never sent, is then pruned as one
     * that went away.
     */
    private class Walk {
        var entriesSeen = 0L
        var readableSeen = 0L

        /**
         * Books whose media type meant nothing to this build.
         *
         * Counted rather than acted on, because the walk goes on: what
         * an unknown type costs is the right to prune, and nothing
         * else.
         */
        var unknownSeen = 0L

        /**
         * Entries the parser could make no book of at all.
         *
         * Counted for the same reason as [unknownSeen]: what a
         * malformed row costs is the right to prune, not the rest of
         * the pages.
         */
        var unreadableSeen = 0L

        /**
         * Every id the walk has parsed, to catch the same book twice.
         *
         * Ids, not books: the counts cannot tell a page of two distinct
         * books from a page holding one book twice, and the second
         * shape leaves a book the server counted unsent and therefore
         * prunable.
         */
        val ids = mutableSetOf<String>()

        private var totalElements: Long? = null
        private var totalPages: Int? = null
        private var pageSize: Long? = null

        /**
         * Records what the catalog said it was, or checks the answer
         * still says the same thing.
         */
        fun agreesOrPins(elements: Long, pages: Int, size: Long): Boolean {
            val first = totalElements == null
            if (first) {
                totalElements = elements
                totalPages = pages
                pageSize = size
                return true
            }
            return totalElements == elements && totalPages == pages && pageSize == size
        }
    }

    /**
     * Grimmory's shim has no search route, and nothing calls this.
     *
     * Library search is local: the shelf is filtered from the catalog
     * already in the database, and [allBooks] walks every book Grimmory
     * has into it, so there is nothing a remote search would find that a
     * reader cannot already see. Implementing one against Grimmory's
     * OPDS feed would mean an Atom parser and a second pagination
     * scheme, reachable from nowhere.
     *
     * If remote search is ever wired to the UI, Grimmory's
     * `/api/v1/opds/catalog?q=` is the way in.
     */
    override suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook> = emptyList()

    /**
     * What a book's media type says Liseur should do with it.
     *
     * Asked of `mediaType` and never of `mediaProfile`, which looks like
     * the field for the job and is not: the shim maps MOBI and AZW3 to a
     * `mediaProfile` of `"EPUB"` alongside EPUB itself, so trusting it
     * puts books on the shelf that fail to open once downloaded.
     *
     * `mediaType` can be trusted for a further reason worth writing
     * down: the shim reads it from the same primary file that its
     * download route serves, so it describes exactly the bytes that will
     * arrive — which is more than Grimmory's OPDS feed can promise,
     * advertising as it does one link per format.
     *
     * The three answers are deliberate. "Not readable" is a book to skip
     * quietly; [Media.UNKNOWN] is this client admitting it does not know
     * what it was sent, which stops the walk pruning. Treating unknown
     * as "not readable" is how a renamed media type deletes every book
     * that adopted it, and it fails silently: the shelf simply empties.
     */
    private fun classify(mediaType: String?): Media {
        // Parameters trimmed, so `application/epub+zip;charset=binary`
        // is still an EPUB rather than a type nobody recognises.
        val type = mediaType?.substringBefore(';')?.trim()?.lowercase() ?: return Media.UNKNOWN
        return when {
            type == EPUB -> Media.READABLE
            type in NOT_READABLE -> Media.OTHER
            else -> Media.UNKNOWN
        }
    }

    private enum class Media { READABLE, OTHER, UNKNOWN }

    /**
     * What a page said about its own place in the catalog.
     *
     * Nothing here is inferred from the books on the page. A page can
     * legitimately hold no *readable* books — a shelf of comics — and
     * ending the walk there would leave the rest of the catalog unseen
     * and, being "complete", everything in it deleted.
     *
     * The envelope is checked against itself, against the request, and
     * against every page before it. A page can be internally plausible
     * and still be short — 199 rows where the server said 200 — and a
     * walk that ends on that has silently lost a book from every page
     * it did not ask for.
     */
    private fun pagingVerdict(
        body: JSONObject,
        answer: KomgaPage,
        requested: Int,
        walk: Walk,
    ): Paging {
        // Komga's parser assumes a missing `last` means the end, which
        // suits the one-shot routes it was written for. Here it would
        // mean one unrecognised body reads as "the catalog is one page
        // long", so an assumption is not good enough.
        if (!answer.pagingWasExplicit) return Paging.UNTRUSTWORTHY

        // A page that is not the one that was asked for describes some
        // other part of the catalog, and cannot be reasoned about.
        if (answer.number != requested) return Paging.UNTRUSTWORTHY

        val totalPages = answer.totalPages ?: return Paging.UNTRUSTWORTHY
        if (totalPages < 0) return Paging.UNTRUSTWORTHY
        val lastByCount = requested >= totalPages - 1
        if (answer.last != lastByCount) return Paging.UNTRUSTWORTHY

        // What the server said it sent, against what arrived. These are
        // the only fields that can catch a truncated page, so a body
        // without them is one whose length cannot be checked.
        val declared = body.longOrNull("numberOfElements") ?: return Paging.UNTRUSTWORTHY
        if (declared != answer.entriesOnPage.toLong()) return Paging.UNTRUSTWORTHY

        val totalEntries = body.longOrNull("totalElements") ?: return Paging.UNTRUSTWORTHY
        if (totalEntries < 0 || walk.entriesSeen > totalEntries) return Paging.UNTRUSTWORTHY

        // The page size the server chose, which need not be the one that
        // was asked for: a server is entitled to clamp it, and holding
        // that against it would block pruning for good.
        val pageSize = body.longOrNull("size") ?: return Paging.UNTRUSTWORTHY
        if (pageSize <= 0) return Paging.UNTRUSTWORTHY

        // The shape of the catalog, fixed by its first answer. A catalog
        // that changes size underneath the walk is one being written to
        // while it is read, and the pages already taken no longer add up
        // to it — so it is not something to prune against.
        if (!walk.agreesOrPins(totalEntries, totalPages, pageSize)) return Paging.UNTRUSTWORTHY

        // How many pages that many books needs, which is how the server
        // works it out too. Left unchecked, `totalPages` is free to say
        // "one" about a catalog that plainly needs three.
        val pagesNeeded = (totalEntries + pageSize - 1) / pageSize
        if (totalPages.toLong() != pagesNeeded) return Paging.UNTRUSTWORTHY

        if (answer.last) {
            // Every book the server counted has to have been seen. A
            // page short by one is otherwise perfectly well formed, and
            // the book it dropped would be pruned as vanished.
            return if (walk.entriesSeen == totalEntries) Paging.LAST else Paging.UNTRUSTWORTHY
        }

        // A page before the last must be full, or the books missing from
        // it are on no other page either.
        if (answer.entriesOnPage.toLong() != pageSize) return Paging.UNTRUSTWORTHY

        return Paging.MORE
    }

    private fun booksUrl(baseUrl: String, page: Int) =
        GrimmoryUrl.api(baseUrl, BOOKS) + "?page=$page&size=$PAGE_SIZE"

    private enum class Paging { LAST, MORE, UNTRUSTWORTHY }

    private companion object {
        const val TAG = "GrimmoryCatalog"
        const val BOOKS = "/api/v1/books"
        const val EPUB = "application/epub+zip"

        /**
         * Everything else Grimmory is known to serve.
         *
         * Taken from `KomgaMapper.getMediaType()`, which is a `switch`
         * over the seven file types Grimmory has: this is all of them
         * but EPUB. Written out rather than inferred, because the
         * tempting rule — "anything that is not an EPUB is a comic" —
         * cannot tell a comic from an EPUB whose type was spelled
         * differently, and gets the second one wrong by deleting it. A
         * media type on neither list stops the walk from pruning, which
         * costs nothing but a line here when Grimmory grows a format.
         *
         * `application/zip` is *deliberately absent*, though the same
         * method returns it: it is what a book of no recognised type
         * gets, which is Grimmory saying it does not know either. An
         * EPUB is a zip, so filing that under "not a book" would prune
         * a book that is merely mislabelled.
         *
         * The audiobook entry is the literal wildcard subtype, which is
         * not a media type anyone should send. It is what Grimmory
         * sends. (Spelling it in this comment would open a nested block
         * comment, which is the sort of thing it is.)
         */
        val NOT_READABLE = setOf(
            "application/pdf",
            "application/x-cbz",
            "application/fictionbook2+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "audio/*",
        )

        /** Where the shim serves a book's parts from. */
        val HREFS = KomgaHrefs { id, what -> "/komga/api/v1/books/$id/$what" }

        /**
         * Books per request. Grimmory's default is 20, which would be
         * eleven round trips for a modest library.
         */
        const val PAGE_SIZE = 200

        /**
         * A ceiling on the walk, so a server that keeps promising one
         * more page cannot page forever. Hitting it is not completion.
         */
        const val MAX_PAGES = 200
    }
}
