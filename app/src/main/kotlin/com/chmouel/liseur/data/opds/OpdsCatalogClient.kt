package com.chmouel.liseur.data.opds

import android.util.Log
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.CatalogWalk
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import org.xml.sax.SAXException

/**
 * Walks a plain OPDS catalog: any server that answers with a feed.
 *
 * Unlike every other catalog client here, this one is not told where
 * the books are. calibre-web has `/opds/books/letter/00` and Komga has
 * a REST route; a catalog nobody has written a client for has only a
 * root, which may list books, or shelves, or shelves of shelves. So the
 * walk starts at the root and follows navigation entries until it finds
 * feeds with books in them.
 *
 * That is an unbounded errand on a server that means it to be — a
 * facet that links back to itself, a paging chain that never ends, a
 * tree of author-by-letter-by-genre. Three bounds keep it finite: a
 * visited set, a depth limit, and a total request budget. When one of
 * them stops the walk it says so ([CatalogWalk.complete] false), which
 * is what stops the library treating a walk cut short as proof that
 * everything else was deleted.
 */
class OpdsCatalogClient(private val http: OpdsHttp = OpdsHttp()) : CatalogSource {

    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): CatalogWalk = withContext(Dispatchers.IO) {
        val scope = OpdsScope.of(baseUrl) ?: return@withContext CatalogWalk(complete = false)
        val root = scope.root

        val seen = mutableSetOf(root.toString())
        // Breadth-first, so a shallow shelf full of books is read before
        // a deep tree of empty ones. A reader watching the library fill
        // in should see books early even when the budget runs out.
        val queue = ArrayDeque(listOf(Step(root, depth = 0)))
        var requests = 0
        var complete = true

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (requests >= MAX_REQUESTS) {
                Log.i(TAG, "Stopped after $MAX_REQUESTS requests; the catalog is bigger than that")
                complete = false
                break
            }
            val step = queue.removeFirst()
            requests++

            val page = fetch(step.url, scope, credentials)
            onPage(page.books.map { it.toRemote(scope, page.base) })

            // Paging stays at the same depth: `next` is more of this
            // feed, not a step further in.
            page.nextUrl?.let { if (seen.add(it.toString())) queue.addLast(Step(it, step.depth)) }

            if (step.depth >= MAX_DEPTH) {
                if (page.navigation.isNotEmpty()) {
                    Log.i(TAG, "Stopped at depth $MAX_DEPTH; the catalog nests deeper than that")
                    complete = false
                }
                continue
            }
            page.navigation.forEach { url ->
                if (seen.add(url.toString())) queue.addLast(Step(url, step.depth + 1))
            }
        }
        CatalogWalk(complete = complete)
    }

    /**
     * OPDS search is an OpenSearch description document advertised by
     * the feed, not a path that can be guessed, and every server fills
     * it in differently. Searching the books already listed is what the
     * library does for a Custom server; asking the server is a later
     * piece of work.
     */
    override suspend fun search(
        baseUrl: String,
        credentials: RemoteCredentials,
        query: String,
    ): List<RemoteBook> = emptyList()

    private class Step(val url: HttpUrl, val depth: Int)

    /** A page, with every link in it already made absolute. */
    private class Resolved(
        val books: List<OpdsBook>,
        val navigation: List<HttpUrl>,
        val nextUrl: HttpUrl?,
        val base: HttpUrl,
    )

    private fun fetch(url: HttpUrl, scope: OpdsScope, credentials: RemoteCredentials): Resolved {
        val fetched = http.get(url, scope, credentials)
        val page = fetched.response.use { response ->
            if (!response.isSuccessful) throw RemoteHttpFailure(failureForCode(response.code))
            try {
                OpdsParser.parse(response.body.string())
            } catch (e: SAXException) {
                Log.i(TAG, "A catalog feed was not readable XML", e)
                throw RemoteHttpFailure(SyncFailure.Malformed)
            }
        }

        // Against the URL that answered, not the one that was asked
        // for, and not the configured root: a relative href in a feed
        // means what the feed says it means, and a redirect moves that
        // meaning with it.
        val base = fetched.url.resolveOrSelf(page.xmlBase)
        return Resolved(
            books = page.books,
            navigation = page.navigation.mapNotNull { base.resolve(it.href) },
            nextUrl = page.nextHref?.let(base::resolve),
            base = base,
        )
    }

    private fun OpdsBook.toRemote(scope: OpdsScope, base: HttpUrl): RemoteBook {
        val entryBase = base.resolveOrSelf(xmlBase)
        return RemoteBook(
            remoteId = scope.remoteId(entryId),
            title = title,
            author = author,
            coverHref = coverHref?.let { scope.fetchable(entryBase.resolve(it)) },
            downloadHref = downloadHref?.let { scope.fetchable(entryBase.resolve(it)) },
            sizeBytes = sizeBytes,
            updatedAt = updatedAt,
            seriesName = seriesName,
            seriesIndex = seriesIndex,
        )
    }

    private fun HttpUrl.resolveOrSelf(href: String?): HttpUrl =
        href?.let { resolve(it) } ?: this

    private companion object {
        const val TAG = "opds-catalog"

        /**
         * How deep the shelves may nest before the walk stops.
         *
         * Root, a shelf, a sub-shelf and its pages is four; real
         * catalogs sit well inside that. A deeper one is not read
         * wrongly, only partly, and it says so.
         */
        const val MAX_DEPTH = 4

        /**
         * How many requests one refresh may spend.
         *
         * Depth bounds how far in the walk goes, not how wide: an
         * author index is one level deep and ten thousand feeds across.
         * At a page a request this is a library of some tens of
         * thousands of books, and a refresh that ends.
         */
        const val MAX_REQUESTS = 400
    }
}
