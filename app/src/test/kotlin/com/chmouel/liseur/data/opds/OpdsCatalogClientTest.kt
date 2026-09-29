package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Walking a catalog nobody has written a client for.
 *
 * Every other catalog client here is told where the books are:
 * calibre-web has `/opds/books/letter/00`, Komga has a REST route. A
 * plain OPDS server has only a root, which may hold books, or shelves,
 * or shelves of shelves, so the walk has to find them — and stop.
 */
class OpdsCatalogClientTest {

    private lateinit var server: MockWebServer
    private val pages = mutableMapOf<String, String>()
    private val codes = mutableMapOf<String, Int>()
    private val asked = mutableListOf<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath +
                    (request.url.encodedQuery?.let { "?$it" } ?: "")
                asked += path
                codes[path]?.let { return MockResponse(code = it) }
                val body = pages[path] ?: return MockResponse(code = 404)
                return MockResponse(
                    code = 200,
                    headers = Headers.headersOf(
                        "Content-Type",
                        "application/atom+xml;profile=opds-catalog",
                    ),
                    body = body,
                )
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun root() = "http://127.0.0.1:${server.port}/opds"

    private fun feed(body: String, attributes: String = "") =
        """<feed xmlns="http://www.w3.org/2005/Atom" $attributes><title>Shelf</title>$body</feed>"""

    private fun navigation(title: String, href: String) =
        """
        <entry><id>nav-$href</id><title>$title</title>
        <link rel="subsection" href="$href"
              type="application/atom+xml;profile=opds-catalog"/></entry>
        """.trimIndent()

    private fun book(id: String, href: String = "/get/$id.epub") =
        """
        <entry><id>$id</id><title>Book $id</title>
        <link rel="http://opds-spec.org/acquisition" href="$href"
              type="application/epub+zip"/></entry>
        """.trimIndent()

    private fun walk(
        credentials: RemoteCredentials = RemoteCredentials.Anonymous,
    ): Pair<Boolean, List<com.chmouel.liseur.data.remote.RemoteBook>> {
        val found = mutableListOf<com.chmouel.liseur.data.remote.RemoteBook>()
        val result = runBlocking {
            OpdsCatalogClient().allBooks(root(), credentials) { found += it }
        }
        return result.complete to found
    }

    @Test
    fun `browsing a shelf exposes its children without importing their books`() = runBlocking {
        pages["/opds"] = feed(navigation("Science fiction", "/opds/scifi") + book("root"))
        pages["/opds/scifi"] = feed(book("child"))

        val client = OpdsCatalogClient()
        val top = client.browseSection(root(), RemoteCredentials.Anonymous)
        assertTrue(top.complete)
        assertEquals(listOf("Book root"), top.books.map { it.title })
        assertEquals(listOf("Science fiction"), top.categories.map { it.title })
        assertEquals(listOf("/opds"), asked)

        val child = client.browseSection(root(), RemoteCredentials.Anonymous, top.categories.single().id)
        assertEquals(listOf("Book child"), child.books.map { it.title })
    }

    @Test
    fun `a paged browse stops after its page and says where the rest is`() = runBlocking {
        val next = """<link rel="next" href="/opds?page=2"
                         type="application/atom+xml;profile=opds-catalog"/>"""
        pages["/opds"] = feed(book("1") + next)
        pages["/opds?page=2"] = feed(book("2"))

        val client = OpdsCatalogClient()
        val first = client.browseSection(root(), RemoteCredentials.Anonymous, pageLimit = 1)
        assertTrue(first.complete)
        assertEquals(listOf("Book 1"), first.books.map { it.title })
        assertEquals("${root()}?page=2", first.nextPage)
        assertEquals(listOf("/opds"), asked)

        val second = client.browseSection(root(), RemoteCredentials.Anonymous, first.nextPage!!, pageLimit = 1)
        assertEquals(listOf("Book 2"), second.books.map { it.title })
        assertNull(second.nextPage)

        val whole = client.browseSection(root(), RemoteCredentials.Anonymous)
        assertEquals(listOf("Book 1", "Book 2"), whole.books.map { it.title })
        assertNull(whole.nextPage)
    }

    @Test
    fun `a Gutenberg section shows the books behind its single-book feeds`() = runBlocking {
        pages["/ebooks/search.opds/"] = feed(
            navigation("Pride and Prejudice", "/ebooks/1342.opds") +
                navigation("Moby Dick", "/ebooks/2701.opds") +
                navigation("By author", "/ebooks/author/68.opds"),
        )
        pages["/ebooks/1342.opds"] = feed(book("urn:gutenberg:1342:2"))
        codes["/ebooks/2701.opds"] = 503
        val http = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()
        val base = "http://www.gutenberg.org:${server.port}"

        val page = OpdsCatalogClient(OpdsHttp(http)).browseSection(
            "$base/ebooks.opds/",
            RemoteCredentials.Anonymous,
            "$base/ebooks/search.opds/",
            pageLimit = 1,
        )

        assertEquals(listOf("Book urn:gutenberg:1342:2"), page.books.map { it.title })
        // A book feed that would not load stays a folder; other links are left alone.
        assertEquals(listOf("Moby Dick", "By author"), page.categories.map { it.title })
        assertFalse("/ebooks/author/68.opds" in asked)
        assertEquals(3, page.requests)
    }

    @Test
    fun `Gutenberg book feeds stay within the remaining request budget`() = runBlocking {
        pages["/ebooks/search.opds/"] = feed(
            (1..4).joinToString("") { navigation("Book $it", "/ebooks/$it.opds") },
        )
        (1..4).forEach { pages["/ebooks/$it.opds"] = feed(book("book-$it")) }
        val http = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()
        val base = "http://www.gutenberg.org:${server.port}"

        val page = OpdsCatalogClient(OpdsHttp(http)).browseSection(
            "$base/ebooks.opds/",
            RemoteCredentials.Anonymous,
            "$base/ebooks/search.opds/",
            pageLimit = 1,
            requestBudget = 3,
        )

        assertEquals(3, page.requests)
        assertEquals(3, asked.size)
        assertEquals(listOf("Book book-1", "Book book-2"), page.books.map { it.title })
        assertEquals(listOf("Book 3", "Book 4"), page.categories.map { it.title })
    }

    @Test
    fun `only Gutenberg's numbered book feeds count as single-book feeds`() {
        fun feed(url: String) = OpdsCatalogClient.isBookFeed(url.toHttpUrl())
        assertTrue(feed("https://www.gutenberg.org/ebooks/1342.opds"))
        assertTrue(feed("https://gutenberg.org/ebooks/84.opds"))
        assertFalse(feed("https://www.gutenberg.org/ebooks/author/68.opds"))
        assertFalse(feed("https://www.gutenberg.org/ebooks.opds/"))
        assertFalse(feed("https://books.example/ebooks/1342.opds"))
    }

    @Test
    fun `a page that continues into itself is not offered again`() = runBlocking {
        pages["/opds"] = feed(
            book("1") + """<link rel="next" href="/opds"
                         type="application/atom+xml;profile=opds-catalog"/>""",
        )

        val page = OpdsCatalogClient().browseSection(root(), RemoteCredentials.Anonymous, pageLimit = 1)
        assertNull(page.nextPage)
        assertFalse(page.complete)
    }

    /**
     * The same walk, but from a root that looks like it is on the
     * internet.
     *
     * Every other test here starts at 127.0.0.1, and a catalog already
     * inside the house is allowed to name its neighbours, so no fetch
     * rule ever fires. Pointing a public name at the loopback server is
     * what makes the refusing branch reachable at all.
     */
    private fun walkAsPublic(): Pair<Boolean, List<com.chmouel.liseur.data.remote.RemoteBook>> {
        val client = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()
        val found = mutableListOf<com.chmouel.liseur.data.remote.RemoteBook>()
        val result = runBlocking {
            OpdsCatalogClient(OpdsHttp(client)).allBooks(
                "http://books.example:${server.port}/opds",
                RemoteCredentials.Anonymous,
            ) { found += it }
        }
        return result.complete to found
    }

    @Test
    fun `an ordinary catalog is not capped`() {
        pages["/opds"] = feed((0 until 60).joinToString("") { book("b$it") })

        val (complete, books) = walk()

        assertEquals(60, books.size)
        assertTrue(complete)
    }

    @Test
    fun `books on the root are read without walking anywhere`() {
        pages["/opds"] = feed(book("1") + book("2"))

        val (complete, books) = walk()

        assertTrue(complete)
        assertEquals(listOf("Book 1", "Book 2"), books.map { it.title })
    }

    @Test
    fun `a book is named for the catalog it came from`() {
        pages["/opds"] = feed(book("1"))
        val expected = OpdsScope.of(root())!!.remoteId("1")

        assertEquals(expected, walk().second.single().remoteId)
    }

    @Test
    fun `an entry id cannot spell its way out of the books directory`() {
        // The name a catalog gives a book becomes `remote_uuid`, which
        // `BookDownloadRepository.fileFor()` writes straight into a
        // filename. An id is an arbitrary string the server chooses.
        pages["/opds"] = feed(book("../../databases/liseur") + book("shelf/1"))

        val ids = walk().second.map { it.remoteId }

        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
        ids.forEach { id ->
            assertFalse(id, '/' in id)
            assertFalse(id, ".." in id)
        }
    }

    @Test
    fun `a shelf the fetch rule refuses costs that shelf, not the library`() {
        // A public catalog naming a LAN address is refused. Handing it
        // to OpdsHttp anyway would throw and take the whole refresh
        // with it, so one bad link would empty the reader's library.
        pages["/opds"] = feed(
            navigation("Inside the house", "http://192.168.1.1/shelf") +
                navigation("Fiction", "/opds/fiction"),
        )
        pages["/opds/fiction"] = feed(book("1"))

        val (complete, books) = walkAsPublic()

        assertEquals(listOf("Book 1"), books.map { it.title })
        // The books behind the refused link were never seen, so the
        // library must not read their absence as a deletion.
        assertFalse(complete)
    }

    @Test
    fun `shelves are walked into until the books turn up`() {
        pages["/opds"] = feed(navigation("Fiction", "/opds/fiction"))
        pages["/opds/fiction"] = feed(navigation("Fantasy", "/opds/fiction/fantasy"))
        pages["/opds/fiction/fantasy"] = feed(book("1"))

        val (complete, books) = walk()

        assertTrue(complete)
        assertEquals(listOf("Book 1"), books.map { it.title })
    }

    @Test
    fun `a feed that links to itself is not walked twice`() {
        pages["/opds"] = feed(navigation("Round we go", "/opds") + book("1"))

        val (complete, books) = walk()

        assertTrue(complete)
        assertEquals(1, books.size)
        assertEquals(listOf("/opds"), asked)
    }

    @Test
    fun `paging follows the chain the feed gives`() {
        pages["/opds"] = feed(
            book("1") +
                """<link rel="next" href="/opds?page=2"
                         type="application/atom+xml;profile=opds-catalog"/>""",
        )
        pages["/opds?page=2"] = feed(book("2"))

        val (complete, books) = walk()

        assertTrue(complete)
        assertEquals(listOf("Book 1", "Book 2"), books.map { it.title })
    }

    @Test
    fun `a catalog nested deeper than the walk goes says so`() {
        // Cut short is a smaller library. Claiming it was the whole one
        // is what makes the next prune delete the rest.
        var depth = 0
        while (depth <= 6) {
            pages[path(depth)] = feed(navigation("Deeper", path(depth + 1)))
            depth++
        }

        assertFalse(walk().first)
    }

    @Test
    fun `a catalog wider than the request budget says so`() {
        // Depth bounds how far in the walk goes, not how wide: an author
        // index is one level deep and ten thousand feeds across.
        val shelves = (1..500).joinToString("") { navigation("Shelf $it", "/opds/s$it") }
        pages["/opds"] = feed(shelves)
        (1..500).forEach { pages["/opds/s$it"] = feed(book("$it")) }

        val (complete, books) = walk()

        assertFalse(complete)
        assertTrue(books.isNotEmpty())
        assertTrue("spent ${asked.size} requests", asked.size <= 400)
    }

    @Test
    fun `a link is resolved against the feed it was written in`() {
        // Not against the configured root. `RemoteUrl.resolve` re-roots
        // an absolute href onto the base, which is right for a
        // reverse-proxied calibre-web and would silently retarget a
        // CDN acquisition link here.
        pages["/opds"] = feed(navigation("Fiction", "/opds/fiction"))
        pages["/opds/fiction"] = feed(
            book("nested", "1.epub") +
                book("up", "../get/2.epub") +
                book("rooted", "/get/3.epub") +
                book("absolute", "https://cdn.example/4.epub"),
        )
        val base = "http://127.0.0.1:${server.port}"

        val hrefs = walk().second.associate { it.title to it.downloadHref }

        assertEquals("$base/opds/1.epub", hrefs["Book nested"])
        assertEquals("$base/get/2.epub", hrefs["Book up"])
        assertEquals("$base/get/3.epub", hrefs["Book rooted"])
        assertEquals("https://cdn.example/4.epub", hrefs["Book absolute"])
    }

    @Test
    fun `a query-only link stays on the page it came from`() {
        pages["/opds"] = feed(navigation("Fiction", "/opds/fiction?sort=title"))
        pages["/opds/fiction?sort=title"] = feed(book("1", "?download=1"))

        assertEquals(
            "http://127.0.0.1:${server.port}/opds/fiction?download=1",
            walk().second.single().downloadHref,
        )
    }

    @Test
    fun `a feed's own base moves every link in it`() {
        pages["/opds"] = feed(book("1", "1.epub"), attributes = """xml:base="https://cdn.example/f/"""")

        assertEquals("https://cdn.example/f/1.epub", walk().second.single().downloadHref)
    }

    @Test
    fun `an entry may move its own links`() {
        pages["/opds"] = feed(
            """
            <entry xml:base="https://files.example/"><id>1</id><title>Book 1</title>
            <link rel="http://opds-spec.org/acquisition" href="1.epub"
                  type="application/epub+zip"/></entry>
            """.trimIndent(),
        )

        assertEquals("https://files.example/1.epub", walk().second.single().downloadHref)
    }

    @Test
    fun `the catalog's own password goes to the catalog`() {
        pages["/opds"] = feed(book("1"))

        walk(RemoteCredentials.Basic("reader", "secret"))

        assertNotNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an anonymous catalog is asked anonymously`() {
        pages["/opds"] = feed(book("1"))

        walk()

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an entry with no readable format is still shown, without a download`() {
        pages["/opds"] = feed(
            """
            <entry><id>1</id><title>Book 1</title>
            <link rel="http://opds-spec.org/acquisition" href="/get/1.pdf"
                  type="application/pdf"/></entry>
            """.trimIndent(),
        )

        assertNull(walk().second.single().downloadHref)
    }

    @Test
    fun `a catalog that answers with something other than a feed is a failure, not an empty library`() {
        pages["/opds"] = "not xml at all"

        val thrown = runCatching { walk() }.exceptionOrNull()

        assertNotNull("an unreadable catalog was read as an empty one", thrown)
    }

    @Test
    fun `search asks the books already listed, not the server`() {
        // OPDS search is an OpenSearch description document advertised
        // by the feed, not a path that can be guessed.
        val hits = runBlocking {
            OpdsCatalogClient().search(root(), RemoteCredentials.Anonymous, "dune")
        }

        assertTrue(hits.isEmpty())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an address that is not http is not a catalog`() {
        val walk = runBlocking {
            OpdsCatalogClient().allBooks("ftp://books.example", RemoteCredentials.Anonymous) {}
        }

        assertFalse(walk.complete)
    }

    @Test
    fun `a feed that cannot be read costs its own page and no more`() {
        // Gutenberg's author feeds carry a link to the author's
        // Wikipedia page. Following one and being refused used to end
        // the refresh and throw away every book already handed over — a
        // whole shelf lost to one stale link on somebody else's server.
        pages["/opds"] = feed(
            navigation("Gone", "/opds/gone") +
                navigation("Here", "/opds/here"),
        )
        pages["/opds/here"] = feed(book("kept"))

        val (complete, books) = walk()

        assertEquals(listOf("Book kept"), books.map { it.title })
        // What was behind the refused feed was not seen, so nothing
        // missing from this walk may be read as deleted.
        assertFalse(complete)
        assertTrue(asked.contains("/opds/here"))
    }

    @Test
    fun `a root that cannot be read is the catalog's own answer`() {
        // Nothing to walk on with, and a refused sign-in or a server in
        // trouble has to reach the reader rather than read as an empty
        // catalog.
        var failure: Throwable? = null
        runBlocking {
            failure = runCatching {
                OpdsCatalogClient().allBooks(root(), RemoteCredentials.Anonymous) {}
            }.exceptionOrNull()
        }

        assertTrue(failure is RemoteHttpFailure)
    }

    private fun path(depth: Int) = if (depth == 0) "/opds" else "/opds/d$depth"
}
