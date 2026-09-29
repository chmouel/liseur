package com.chmouel.liseur.data.opds

import android.util.Log
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.CatalogWalk
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.BrowseCategory
import com.chmouel.liseur.data.remote.BrowseListing
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
class OpdsCatalogClient(
    private val http: OpdsHttp = OpdsHttp(),
) : CatalogSource {

    /** Reads a single OPDS shelf and its pagination, leaving child shelves for navigation. */
    suspend fun browseSection(
        baseUrl: String,
        credentials: RemoteCredentials,
        sectionUrl: String = baseUrl,
        /** How many feed pages to read; when more remain, the listing carries the next one. */
        pageLimit: Int = MAX_REQUESTS,
        /** Maximum requests including Gutenberg's single-book feeds. */
        requestBudget: Int = MAX_REQUESTS,
    ): BrowseListing = withContext(Dispatchers.IO) {
        val budget = requestBudget.coerceAtMost(MAX_REQUESTS)
        if (budget <= 0 || pageLimit <= 0) return@withContext BrowseListing(complete = false)
        val scope = OpdsScope.of(baseUrl) ?: return@withContext BrowseListing(complete = false)
        var next: HttpUrl? = sectionUrl.toHttpUrlOrNull()
            ?.takeIf(scope::mayFetch) ?: return@withContext BrowseListing(complete = false)
        val seen = mutableSetOf<String>()
        val books = mutableListOf<RemoteBook>()
        val categories = linkedMapOf<String, BrowseCategory>()
        var requests = 0
        var complete = true
        while (next != null && requests < minOf(pageLimit, budget) && seen.add(next.toString())) {
            requests++
            coroutineContext.ensureActive()
            val fetched = http.get(next, scope, credentials)
            val page = fetched.response.use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureForCode(response.code))
                try {
                    OpdsParser.parse(response.body.string())
                } catch (e: SAXException) {
                    throw RemoteHttpFailure(SyncFailure.Malformed)
                }
            }
            val base = fetched.url.resolveOrSelf(page.xmlBase)
            books += page.books.map { it.toRemote(scope, base) }
            page.navigation.forEach { link ->
                val url = base.resolve(link.href)?.takeIf(scope::mayFetch)
                if (url == null) complete = false
                url?.let {
                    categories[it.toString()] = BrowseCategory(
                        id = it.toString(),
                        title = link.title?.takeIf(String::isNotBlank) ?: it.encodedPath,
                    )
                }
            }
            next = page.nextHref?.let { href ->
                base.resolve(href)?.takeIf(scope::mayFetch).also { if (it == null) complete = false }
            }
        }
        // Gutenberg lists each book as a link to a feed of its own.
        // Shown as they are, every book would be a folder holding one
        // book, so those feeds are read here and their book put in the
        // folder's place. One that cannot be read stays a folder.
        val bookFeeds = categories.keys.mapNotNull { id -> id.toHttpUrlOrNull()?.takeIf(::isBookFeed) }
            .take(budget - requests)
        if (bookFeeds.isNotEmpty()) {
            requests += bookFeeds.size
            val gate = Semaphore(BOOK_FEED_PARALLELISM)
            val expanded = coroutineScope {
                bookFeeds.map { url ->
                    async {
                        gate.withPermit {
                            try {
                                val feed = fetch(url, scope, credentials)
                                url to feed.books.map { it.toRemote(scope, feed.base) }
                            } catch (e: RemoteHttpFailure) {
                                Log.i(TAG, "A book feed could not be read; leaving it as a folder", e)
                                null
                            } catch (e: IOException) {
                                Log.i(TAG, "A book feed could not be read; leaving it as a folder", e)
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            expanded.filterNotNull().forEach { (url, found) ->
                if (found.isNotEmpty()) {
                    categories.remove(url.toString())
                    books += found
                }
            }
        }
        val more = next?.takeIf { (pageLimit < MAX_REQUESTS || budget < MAX_REQUESTS) && it.toString() !in seen }
        BrowseListing(
            categories.values.toList(),
            books.distinctBy(RemoteBook::remoteId),
            complete && (next == null || more != null),
            nextPage = more?.toString(),
            requests = requests,
        )
    }

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
            val isRoot = requests == 0
            requests++

            // The root's answer is the catalog's answer: a refused
            // sign-in or a server in trouble has to reach the reader,
            // and there is nothing to carry on with anyway. A page
            // reached from it is one page. A two-hundred-book shelf is
            // two hundred requests to somebody else's server, and one
            // stale link among them — Gutenberg's author feeds point at
            // Wikipedia — used to lose the whole refresh, and with it
            // every book the walk had already handed over.
            val page = try {
                fetch(step.url, scope, credentials)
            } catch (e: RemoteHttpFailure) {
                if (isRoot) throw e
                Log.i(TAG, "A feed in this catalog could not be read; walking on", e)
                // What was behind it was not seen, so this is no longer
                // a whole picture of the catalog and nothing missing
                // from it may be read as deleted.
                complete = false
                continue
            }
            val books = page.books
            onPage(books.map { it.toRemote(scope, page.base) })

            // A link the fetch rule refuses is skipped, not followed
            // and not fatal. Handing it to `OpdsHttp` would throw and
            // take the whole refresh with it, so one federated shelf on
            // another host would cost the reader their entire library.
            // The walk says it is incomplete instead, which is what
            // stops the books behind that link being read as deleted.
            fun enqueue(url: HttpUrl, depth: Int) {
                if (!scope.mayFetch(url)) {
                    Log.i(TAG, "A feed pointed somewhere this catalog may not send us")
                    complete = false
                    return
                }
                if (seen.add(url.toString())) queue.addLast(Step(url, depth))
            }

            // Paging stays at the same depth: `next` is more of this
            // feed, not a step further in.
            page.nextUrl?.let { enqueue(it, step.depth) }

            if (step.depth >= MAX_DEPTH) {
                if (page.navigation.isNotEmpty()) {
                    Log.i(TAG, "Stopped at depth $MAX_DEPTH; the catalog nests deeper than that")
                    complete = false
                }
            } else {
                page.navigation.forEach { enqueue(it, step.depth + 1) }
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
            if (!response.isSuccessful) {
                // A walk of a hundred-odd requests will meet an
                // occasional bad answer from a busy public server, and
                // one of them ends the refresh. Without the code there
                // is nothing to tell a shelf that has moved from a
                // server having a bad minute.
                Log.i(TAG, "A catalog feed at ${url.encodedPath} answered ${response.code}")
                throw RemoteHttpFailure(failureForCode(response.code))
            }
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

    internal companion object {
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

        /** How many single-book feeds a browsed section reads at once. */
        const val BOOK_FEED_PARALLELISM = 4

        private val BOOK_FEED_PATH = Regex("/ebooks/\\d+\\.opds")

        /**
         * Whether [url] is a feed holding one Project Gutenberg book,
         * which its listings link to instead of listing the book itself.
         */
        fun isBookFeed(url: HttpUrl): Boolean =
            url.host.removePrefix("www.") == "gutenberg.org" && BOOK_FEED_PATH.matches(url.encodedPath)
    }
}
