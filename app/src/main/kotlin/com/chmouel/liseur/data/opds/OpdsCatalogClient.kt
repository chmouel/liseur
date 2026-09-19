package com.chmouel.liseur.data.opds

import android.util.Log
import com.chmouel.liseur.data.remote.CatalogSource
import com.chmouel.liseur.data.remote.CatalogContinuation
import com.chmouel.liseur.data.remote.CatalogMore
import com.chmouel.liseur.data.remote.CatalogStep
import com.chmouel.liseur.data.remote.CatalogWalk
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ResumableCatalogSource
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    /**
     * How many books this address is offered at, or null for as much of
     * it as the budget reaches.
     *
     * Asked rather than worked out, because the answer is the
     * connection's and nothing about an address can say it: a shelf the
     * reader picked a size for stored it, and any other catalog is
     * walked in full. Suspending because reading it is a database read.
     */
    private val shelfLimit: suspend (String) -> Int? = { null },
) : CatalogSource, ResumableCatalogSource {

    override suspend fun allBooks(
        baseUrl: String,
        credentials: RemoteCredentials,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): CatalogWalk = withContext(Dispatchers.IO) {
        val scope = OpdsScope.of(baseUrl) ?: return@withContext CatalogWalk(complete = false)
        val root = scope.root

        // A shelf Liseur offers is a fixed number of books rather than
        // as much of the catalog as the budget reaches. Null for every
        // address a reader typed, which is all of them but the handful
        // the starter card connects.
        val shelf = shelfLimit(baseUrl)
        var shelved = 0

        val seen = mutableSetOf(root.toString())
        // Breadth-first, so a shallow shelf full of books is read before
        // a deep tree of empty ones. A reader watching the library fill
        // in should see books early even when the budget runs out.
        val queue = ArrayDeque(listOf(Step(root, depth = 0)))
        var requests = 0
        var complete = true
        // Left over from a page a shelf cap cut into, so a resumed
        // load-more re-queues that same feed rather than treating it as
        // fully read; it is fetched again from the top, since a
        // position in it is not something a later request can trust.
        var leftover: CatalogStep? = null
        // A non-root feed that answered with something worth trying
        // again. Its URL stays in `seen`, which would otherwise keep a
        // persisted continuation from ever revisiting it: `loadMore()`
        // only skips `enqueue()`'s `seen` check for steps already
        // sitting in its queue, so this is carried into the
        // continuation directly rather than left for `enqueue()` to
        // re-add.
        val deferred = mutableListOf<CatalogStep>()

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
                // Worth trying again later, the same as `loadMore()`'s
                // own distinction, including a 429: Gutenberg asking to
                // be asked again, not refusing the feed.
                if (e.reason.worthRetrying || (e.reason as? SyncFailure.ServerError)?.code == 429) {
                    deferred += CatalogStep(step.url.toString(), step.depth)
                }
                continue
            }
            val books = if (shelf == null) page.books else page.books.take(shelf - shelved)
            shelved += books.size
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

            // A full shelf stops the walk. Asked after this page's
            // links have been taken in rather than before, because
            // whether anything is left to read is the whole question: a
            // shelf that runs out on the very book the reader asked for
            // has been read to the end, and calling that partial put a
            // notice on it that no refresh could ever clear. Books this
            // page itself held back count as much as an unfollowed
            // link, since `take` is where they were left.
            //
            // Where there is more, this is not a current picture of
            // the catalog and must never be stored as one. It used to
            // count as a whole picture of the *shelf*, which let a book
            // that had dropped out of the top N be deleted; a shelf
            // that can be asked for more books is no longer a rolling
            // top N, so nothing is let go on that reasoning any more.
            if (shelf != null && shelved >= shelf) {
                if (queue.isEmpty() && deferred.isEmpty() && books.size == page.books.size) {
                    Log.i(TAG, "The shelf ran out at $shelved books, inside the $shelf asked for")
                    break
                }
                Log.i(TAG, "Stopped at $shelf books; this shelf is offered at that size")
                complete = false
                if (books.size < page.books.size) {
                    leftover = CatalogStep(step.url.toString(), step.depth)
                }
                break
            }
        }
        // Only a starter shelf resumes a walk, so only a starter shelf
        // needs this recorded: the queue of feeds still owed where the
        // initial capped walk stopped, so the first "Load 50 more" tap
        // carries on rather than reading the root and every feed since
        // all over again. `deferred` goes first: every one of those
        // feeds was encountered before `leftover`, and putting `leftover`
        // ahead of them would let a large or changing page consume a
        // whole batch and requeue itself first on every tap, so the
        // retryable feed behind it would never come up for another try.
        val continuation = shelf?.let {
            CatalogContinuation(
                queue = deferred + listOfNotNull(leftover) +
                    queue.map { step -> CatalogStep(step.url.toString(), step.depth) },
                seen = seen,
            )
        }
        CatalogWalk(complete = complete, continuation = continuation)
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

    override suspend fun loadMore(
        baseUrl: String,
        credentials: RemoteCredentials,
        state: CatalogContinuation?,
        knownRemoteIds: Set<String>,
        limit: Int,
        onPage: suspend (List<RemoteBook>) -> Unit,
    ): CatalogMore = withContext(Dispatchers.IO) {
        val scope = OpdsScope.of(baseUrl) ?: return@withContext CatalogMore(
            added = 0,
            exhausted = true,
            state = CatalogContinuation(emptyList(), emptySet()),
        )
        val root = scope.root.toString()
        val seen = state?.seen?.toMutableSet() ?: mutableSetOf(root)
        val queue = ArrayDeque(
            state?.queue?.takeIf { it.isNotEmpty() } ?: listOf(CatalogStep(root, depth = 0)),
        )
        val known = knownRemoteIds.toMutableSet()
        var added = 0
        var requests = 0
        var fetched = 0
        // A refusal worth retrying, kept apart from a permanent one: if
        // nothing else in this run succeeded, that is still a real
        // failure to report, not an empty shelf.
        var retryableFailure: RemoteHttpFailure? = null
        // The root is the one feed whose loss cannot be read as "no more
        // books" even when the refusal is permanent, because without it
        // this run never reached any part of the catalog at all.
        var rootFailure: RemoteHttpFailure? = null
        // Feeds this run could not read. They are kept rather than
        // dropped, so the next run tries them again instead of deciding
        // the catalog has nothing left in it; and they are held apart
        // from the queue so a feed that keeps failing cannot be retried
        // over and over inside one run.
        val deferred = mutableListOf<CatalogStep>()
        val delivered = mutableListOf<RemoteBook>()

        // `deferred` goes first, same as `allBooks()`: a page cut short by
        // the batch limit is requeued at the front of `queue`, and it can
        // recur run after run. Putting it ahead of `deferred` would let it
        // crowd out an earlier failure indefinitely.
        fun remaining(): List<CatalogStep> = deferred + queue.toList()

        fun enqueue(url: HttpUrl, depth: Int) {
            if (!scope.mayFetch(url)) {
                Log.i(TAG, "A feed pointed somewhere this catalog may not send us")
                return
            }
            if (seen.add(url.toString())) {
                queue.addLast(CatalogStep(url.toString(), depth))
            }
        }

        suspend fun flush() {
            if (delivered.isNotEmpty()) {
                onPage(delivered.toList())
                delivered.clear()
            }
        }

        while (queue.isNotEmpty() && added < limit && requests < MAX_REQUESTS) {
            coroutineContext.ensureActive()
            val step = queue.removeFirst()
            val url = step.url.toHttpUrlOrNull() ?: continue
            requests++
            val page = try {
                fetch(url, scope, credentials)
            } catch (e: RemoteHttpFailure) {
                Log.i(TAG, "A feed in this catalog could not be read while loading more", e)
                if (step.url == root) rootFailure = e
                // A refusal worth retrying may be answering something
                // that will pass later — a timeout, a server error. One
                // that will not is the server's last word on this feed,
                // and keeping it would have the walk retry that word
                // forever, never reaching exhausted. A 429 counts as
                // worth retrying too, same as `LiveSyncConnector`: it is
                // the donation-funded server asking to be asked again
                // later, not a refusal of this feed.
                if (e.reason.worthRetrying || (e.reason as? SyncFailure.ServerError)?.code == 429) {
                    retryableFailure = e
                    deferred += step
                }
                continue
            }
            fetched++

            // The whole page is rescanned every time, rather than
            // dropping by a remembered position: Gutenberg's popularity
            // ordering can shift between requests, so a position from
            // an earlier visit is not a safe cutoff on a page fetched
            // fresh. `known` (seeded from every book already stored) is
            // what skips the ones this walk has already delivered,
            // wherever they now sit on the page.
            val entries = page.books
            var consumed = 0
            for (book in entries) {
                val remote = book.toRemote(scope, page.base)
                consumed++
                if (!known.add(remote.remoteId)) continue
                delivered += remote
                added++
                if (added >= limit) {
                    if (consumed < entries.size) {
                        queue.addFirst(CatalogStep(url = step.url, depth = step.depth))
                        flush()
                        return@withContext CatalogMore(
                            added = added,
                            exhausted = false,
                            state = CatalogContinuation(remaining(), seen),
                        )
                    }
                    break
                }
            }
            flush()

            page.nextUrl?.let { enqueue(it, step.depth) }
            if (step.depth >= MAX_DEPTH) {
                if (page.navigation.isNotEmpty()) {
                    Log.i(TAG, "Stopped at depth $MAX_DEPTH while loading more")
                }
            } else {
                page.navigation.forEach { enqueue(it, step.depth + 1) }
            }
            if (added >= limit) {
                return@withContext CatalogMore(
                    added = added,
                    exhausted = remaining().isEmpty(),
                    state = CatalogContinuation(remaining(), seen),
                )
            }
        }

        // Nothing this run touched could be read. If any of that was a
        // refusal worth retrying, or the root itself, that is the
        // catalog being unreachable rather than a shelf with nothing
        // left on it, and the reader is told so. A permanent refusal of
        // some other feed, with nothing retryable left pending, is not:
        // that feed is simply gone, and the walk may still be exhausted.
        if (fetched == 0 && added == 0) {
            (retryableFailure ?: rootFailure)?.let { throw it }
        }

        CatalogMore(
            added = added,
            exhausted = remaining().isEmpty(),
            state = CatalogContinuation(remaining(), seen),
        )
    }

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
    }
}
