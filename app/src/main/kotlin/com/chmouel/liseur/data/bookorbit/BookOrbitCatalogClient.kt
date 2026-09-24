package com.chmouel.liseur.data.bookorbit

import android.util.Log
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingDao
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.CatalogSnapshot
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.CatalogWalk
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Walking a BookOrbit shelf.
 *
 * The catalog is one route, `POST /books/query`, which already scopes
 * itself to the libraries the signed-in account may see, so there is no
 * library loop and no per-shelf walk. Pages start at zero and are capped
 * at two hundred books.
 *
 * Two things this client does that a plain mapper would not:
 *
 * 1. **It refuses to list a book it cannot read.** BookOrbit holds
 *    audiobooks, comics and podcasts beside its EPUBs, and a book with
 *    no EPUB file has nothing this app could open. Those are skipped
 *    rather than shown as a tile that fails on tap.
 * 2. **It remembers which file a book is read as.** The file id is what
 *    a download, a position and a session all name, and the server is
 *    free to make a different EPUB primary at the next scan. Keeping the
 *    choice in `book_orbit_binding` is what stops a refresh from
 *    silently moving a reader's place to another edition.
 */
class BookOrbitCatalogClient(
    private val bindings: BookOrbitBindingDao,
    private val serverDao: RemoteServerDao,
    private val http: BookOrbitHttp,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    /**
     * The library URL already holding each of these remote ids.
     *
     * A book uploaded from this device keeps its own URL and only gains
     * the server's id, so its binding lives under that URL rather than
     * the one this client would derive. Asked once per page.
     */
    private val localUrls: suspend (List<String>) -> Map<String, String> = { emptyMap() },
) : CatalogSource {

    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): CatalogWalk {
        val server = serverDao.get()?.takeIf { it.kind == ServerKind.BOOKORBIT }
            ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        val context = BookOrbitRequestContext.from(server)
            ?.takeIf { com.chmouel.liseur.data.remote.RemoteUrl.sameAddress(it.baseUrl, baseUrl) }
            ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        val books = mutableListOf<RemoteBook>()
        val cards = mutableListOf<BookOrbitCardRef>()
        var page = 0
        var complete = false
        var useFilter = true

        while (page < MAX_PAGES) {
            coroutineContext.ensureActive()
            val answer = try {
                BookOrbitBooks.parsePage(
                    fetchPage(context, page, qi = null, filtered = useFilter),
                )
            } catch (e: RemoteHttpFailure) {
                if (page == 0 && useFilter && e.reason == SyncFailure.ServerError(400)) {
                    // Restart the whole walk without the optional filter.
                    // Remember that answer for every later page: retrying
                    // only page zero would make a large shelf fail on page
                    // one with the very filter it already refused.
                    useFilter = false
                    BookOrbitBooks.parsePage(
                        fetchPage(context, page = 0, qi = null, filtered = false),
                    )
                } else {
                    throw e
                }
            }
            if (answer.cards.isEmpty()) {
                complete = true
                break
            }
            val held = heldUrls(context, server, answer.cards)
            val mapped = answer.cards.mapNotNull { card ->
                cardFor(context, server, card, persistBinding = true, held)?.also { cards += it.ref }
                    ?.let { it.book }
            }
            books += mapped
            onPage(mapped)

            val size = answer.size.takeIf { it > 0 } ?: answer.cards.size
            page++
            if (page * size >= answer.total) {
                complete = true
                break
            }
        }
        if (!complete) Log.i(TAG, "Stopped after $MAX_PAGES pages of catalog")
        return CatalogWalk(
            complete = complete,
            snapshot = BookOrbitCatalogSnapshot(books, cards),
        )
    }

    override suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook> {
        val server = serverDao.get()?.takeIf { it.kind == ServerKind.BOOKORBIT }
            ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        val context = BookOrbitRequestContext.from(server)
            ?.takeIf { com.chmouel.liseur.data.remote.RemoteUrl.sameAddress(it.baseUrl, baseUrl) }
            ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        val found = BookOrbitBooks.parsePage(
            fetchPage(context, page = 0, qi = query, size = SEARCH_SIZE, filtered = false),
        ).cards
        val held = heldUrls(context, server, found)
        return found.mapNotNull { card ->
            // A search result is not in the library yet. Looking at one
            // must not choose an edition that a later adoption is forced
            // to keep.
            cardFor(context, server, card, persistBinding = false, held)?.book
        }
    }

    /** See [localUrls]. Empty without a stable account id, like [cardFor]. */
    private suspend fun heldUrls(
        context: BookOrbitRequestContext,
        server: RemoteServer,
        cards: List<BookOrbitCard>,
    ): Map<String, String> {
        val accountId = server.accountId ?: return emptyMap()
        val ids = cards.map { BookOrbitScope.remoteId(context.baseUrl, accountId, it.bookId) }
        return if (ids.isEmpty()) emptyMap() else localUrls(ids)
    }

    /**
     * One book as the library will hold it, or null when it has no EPUB.
     *
     * The binding is consulted before the card is believed: once a book
     * has been read as one file, that is the file it stays. A book whose
     * bound file has gone from the server keeps its identity and loses
     * its download link, rather than quietly adopting whatever replaced
     * it.
     */
    private suspend fun cardFor(
        context: BookOrbitRequestContext,
        server: RemoteServer,
        card: BookOrbitCard,
        persistBinding: Boolean,
        held: Map<String, String> = emptyMap(),
    ): CardResult? {
        val offered = BookOrbitBooks.chosenFile(card) ?: return null
        val accountId = server.accountId
        val remoteId = if (accountId != null) {
            BookOrbitScope.remoteId(context.baseUrl, accountId, card.bookId)
        } else {
            // Without a stable account id there is no identity that
            // survives a login change, so the book is left out rather
            // than given one that could be adopted by somebody else.
            return null
        }
        val bookUrl = held[remoteId] ?: ServerKind.BOOKORBIT.remoteUrl(remoteId)
        var binding = bindings.get(server.accountKey, bookUrl)
        if (binding == null && persistBinding) {
            val proposed = BookOrbitBinding(
                accountKey = server.accountKey,
                bookUrl = bookUrl,
                bookId = card.bookId,
                fileId = offered.id,
                fileFormat = offered.format,
                fileSize = offered.sizeBytes,
                fileName = null,
                revision = 0,
                state = BookOrbitBindingState.SELECTED.name,
                updatedAt = System.currentTimeMillis(),
            )
            inTransaction {
                if (!context.matches(serverDao.get())) throw AccountChanged()
                binding = bindings.bindIfUnbound(proposed)
            }
        }

        // The server can promote another EPUB, but it cannot thereby
        // change the edition a downloaded locator belongs to. If the
        // chosen id vanished, remember that fact and offer no link.
        if (binding != null && binding?.fileId != null) {
            val bound = card.files.firstOrNull { it.id == binding?.fileId && it.isEpub }
            val state = if (bound == null) BookOrbitBindingState.MISSING else BookOrbitBindingState.SELECTED
            if (persistBinding && binding?.stateValue != state) {
                val updated = binding!!.copy(
                    fileFormat = bound?.format ?: binding!!.fileFormat,
                    fileSize = bound?.sizeBytes ?: binding!!.fileSize,
                    state = state.name,
                    updatedAt = System.currentTimeMillis(),
                )
                inTransaction {
                    if (!context.matches(serverDao.get())) throw AccountChanged()
                    bindings.write(updated)
                }
                binding = updated
            }
        }

        val file = when {
            binding == null || binding.stateValue == BookOrbitBindingState.UNBOUND -> offered
            binding.stateValue == BookOrbitBindingState.MISSING -> null
            else -> BookOrbitFile(
                id = binding.fileId ?: offered.id,
                format = binding.fileFormat ?: offered.format,
                role = "primary",
                sizeBytes = binding.fileSize ?: offered.sizeBytes,
            )
        }

        val book = RemoteBook(
            remoteId = remoteId,
            title = card.title,
            author = card.author,
            coverHref = BookOrbitUrl.coverHref(card.bookId, remoteId),
            downloadHref = file?.let { BookOrbitUrl.downloadHref(it.id) },
            sizeBytes = file?.sizeBytes,
            updatedAt = card.updatedAt ?: card.addedAt,
            pageCount = card.pageCount,
            seriesName = card.seriesName,
            seriesIndex = card.seriesIndex,
            seriesId = card.seriesId,
        )
        return CardResult(
            book = book,
            ref = BookOrbitCardRef(
                remoteId = remoteId,
                bookId = card.bookId,
                fileId = file?.id,
                percentage = card.percentage,
                status = card.readStatus,
            ),
        )
    }

    private suspend fun fetchPage(
        context: BookOrbitRequestContext,
        page: Int,
        qi: String?,
        size: Int = PAGE_SIZE,
        filtered: Boolean,
    ): JSONObject {
        val url = BookOrbitUrl.api(context.baseUrl, "/books/query")
        return http.postObject(context, url, queryBody(page, size, qi, filtered))
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
    }

    private fun queryBody(page: Int, size: Int, qi: String?, filtered: Boolean): JSONObject {
        val body = JSONObject()
            .put("sort", org.json.JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
            .put("pagination", JSONObject().put("page", page).put("size", size))
            .put("collapseSeries", false)
        qi?.takeIf { it.isNotBlank() }?.let { body.put("q", it) }
        if (filtered) body.put("filter", epubFilter())
        return body
    }

    /**
     * A rule that keeps books holding an EPUB.
     *
     * `format` is implemented as "any of this book's files is", so a
     * book with an EPUB beside an audiobook is kept, which is what an
     * EPUB reader wants. The rule is still only an optimisation: the
     * chosen-file check is what decides.
     */
    private fun epubFilter(): JSONObject = JSONObject()
        .put("type", "group")
        .put("join", "AND")
        .put(
            "rules",
            org.json.JSONArray().put(
                JSONObject()
                    .put("type", "rule")
                    .put("field", "format")
                    .put("operator", "includesAny")
                    .put("value", org.json.JSONArray().put("epub")),
            ),
        )

    private data class CardResult(val book: RemoteBook, val ref: BookOrbitCardRef)

    private class AccountChanged : java.io.IOException("The connected BookOrbit account changed")

    private companion object {
        const val TAG = "bookorbit-catalog"
        const val PAGE_SIZE = 200
        const val SEARCH_SIZE = 50

        /**
         * How far a walk will go before it calls the shelf endless.
         *
         * Two hundred pages of two hundred books is far past any
         * personal library, and the server caps the window anyway. A
         * walk that stops here is reported incomplete, so nothing acts
         * on it as if it had seen everything.
         */
        const val MAX_PAGES = 200
    }
}

/**
 * One book a walk has just seen, kept so the position sync that follows
 * a refresh need not list the shelf again.
 *
 * This is a shortcut and never an authority: the percentage is the
 * server's aggregate across files and the status is a summary. Both are
 * read again for the file actually being synced.
 */
data class BookOrbitCardRef(
    val remoteId: String,
    val bookId: Long,
    val fileId: Long?,
    val percentage: Double?,
    val status: BookOrbitStatus?,
)

/** A BookOrbit catalog walk, as the merge reads it. */
class BookOrbitCatalogSnapshot(
    val books: List<RemoteBook>,
    val cards: List<BookOrbitCardRef>,
) : CatalogSnapshot
