package com.chmouel.liseur.data.grimmory

import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteCredentials
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Grimmory catalog client against payload shapes captured from a
 * real Grimmory v3.3.3.
 *
 * Most of what is asserted here is about *refusing* to call a walk
 * complete. That is not fussiness: a complete walk is what licenses the
 * repository to delete every catalogued book it did not see, and those
 * books take their reading positions with them. A parser that guesses
 * cheerfully loses a reader's place in a library.
 */
class GrimmoryCatalogClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun baseUrl() = "http://127.0.0.1:${server.port}"

    private val credentials = RemoteCredentials.Basic("opds-user", "secret")

    /** One entry, in the shape Grimmory's shim really answers with. */
    private fun entry(
        id: String,
        mediaType: String? = EPUB,
        profile: String = "EPUB",
    ): String {
        val media = buildString {
            append("""{"status":"READY",""")
            if (mediaType != null) append(""""mediaType":"$mediaType",""")
            append(""""mediaProfile":"$profile","pagesCount":0}""")
        }
        return """
        {"id":"$id","seriesId":"1-unknown-series","seriesTitle":"Unknown Series",
         "libraryId":"1","name":"Book $id","url":"/komga/api/v1/books/$id",
         "number":1,"sizeBytes":1024,"lastModified":"2026-08-27T19:45:47Z",
         "media":$media,
         "metadata":{"title":"Title $id","authors":[{"name":"A Writer","role":"writer"}]},
         "deleted":false,"oneshot":false}
        """.trimIndent()
    }

    /**
     * A page envelope in Grimmory's own shape.
     *
     * The counts default to describing exactly what was passed, so a
     * test that says nothing about them gets a page the client should
     * believe. Every one of them can be overridden or omitted, because
     * each is load-bearing: they are what catches a page that is short
     * of what the server said it was sending.
     */
    private fun page(
        vararg entries: String,
        number: Int,
        totalPages: Int,
        last: Boolean = number >= totalPages - 1,
        size: Int = entries.size,
        totalElements: Int = entries.size,
        numberOfElements: Int = entries.size,
        content: String = "[${entries.joinToString(",")}]",
        omit: Set<String> = emptySet(),
    ): MockResponse {
        val fields = buildList {
            if ("content" !in omit) add(""""content":$content""")
            if ("number" !in omit) add(""""number":$number""")
            if ("totalPages" !in omit) add(""""totalPages":$totalPages""")
            if ("last" !in omit) add(""""last":$last""")
            if ("size" !in omit) add(""""size":$size""")
            if ("totalElements" !in omit) add(""""totalElements":$totalElements""")
            if ("numberOfElements" !in omit) add(""""numberOfElements":$numberOfElements""")
        }
        return MockResponse(body = "{${fields.joinToString(",")}}")
    }

    private fun walk(): Pair<Boolean, List<String>> {
        val seen = mutableListOf<String>()
        val result = runBlocking {
            GrimmoryCatalogClient().allBooks(baseUrl(), credentials) { batch ->
                seen += batch.map(RemoteBook::remoteId)
            }
        }
        return result.complete to seen
    }

    @Test
    fun `the listing route is the one Grimmory implements`() = runBlocking {
        server.enqueue(page(entry("1"), number = 0, totalPages = 1))

        GrimmoryCatalogClient().allBooks(baseUrl(), credentials)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // Komga's own client posts to /api/v1/books/list, which Grimmory
        // answers 501. Getting this wrong is an empty library and no
        // error worth showing.
        assertTrue(request.target.startsWith("/komga/api/v1/books?"))
        assertTrue(request.target.contains("page=0"))
    }

    @Test
    fun `every page is walked and reported as it lands`() {
        server.enqueue(page(entry("1"), entry("2"), number = 0, totalPages = 2, totalElements = 3))
        server.enqueue(page(entry("3"), number = 1, totalPages = 2, size = 2, totalElements = 3))

        val (complete, seen) = walk()

        assertTrue(complete)
        assertEquals(listOf("1", "2", "3"), seen)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `covers and downloads are addressed through the shim's prefix`() {
        server.enqueue(page(entry("42"), number = 0, totalPages = 1))

        val books = mutableListOf<RemoteBook>()
        runBlocking {
            GrimmoryCatalogClient().allBooks(baseUrl(), credentials) { books += it }
        }

        // A wrong download href fails loudly. A wrong cover href is a
        // silent grey box, which is why it is asserted here rather than
        // left to be noticed.
        assertEquals("/komga/api/v1/books/42/thumbnail", books.single().coverHref)
        assertEquals("/komga/api/v1/books/42/file", books.single().downloadHref)
    }

    @Test
    fun `a book that only claims to be an epub is not shelved`() {
        // Grimmory reports MOBI and AZW3 with a mediaProfile of "EPUB",
        // so trusting that field puts books on the shelf that fail to
        // open once they have been downloaded.
        server.enqueue(
            page(
                entry("1", mediaType = "application/x-mobipocket-ebook", profile = "EPUB"),
                entry("2", mediaType = EPUB),
                number = 0,
                totalPages = 1,
            ),
        )

        val (complete, seen) = walk()

        assertTrue(complete)
        assertEquals(listOf("2"), seen)
    }

    @Test
    fun `a page of nothing readable does not end the walk`() {
        // A shelf of comics filters to empty. Stopping here would leave
        // the rest of the catalog unseen and, being "complete", deleted.
        server.enqueue(
            page(
                entry("1", mediaType = "application/x-cbz", profile = "DIVINA"),
                number = 0,
                totalPages = 2,
                totalElements = 2,
            ),
        )
        server.enqueue(page(entry("2"), number = 1, totalPages = 2, totalElements = 2))

        val (complete, seen) = walk()

        assertTrue(complete)
        assertEquals(listOf("2"), seen)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a page that does not say it is the last is not believed to be`() {
        // The lenient default treats a missing `last` as the end, which
        // suits the routes it was written for and would be a deletion
        // here.
        server.enqueue(page(entry("1"), number = 0, totalPages = 1, omit = setOf("last")))

        assertFalse(walk().first)
    }

    @Test
    fun `a page that does not say which page it is is not believed`() {
        server.enqueue(page(entry("1"), number = 0, totalPages = 1, omit = setOf("number")))

        assertFalse(walk().first)
    }

    @Test
    fun `a page that does not say how many pages there are is not believed`() {
        server.enqueue(page(entry("1"), number = 0, totalPages = 1, omit = setOf("totalPages")))

        assertFalse(walk().first)
    }

    @Test
    fun `a page answering for somewhere else in the catalog is not believed`() {
        // Asked for page 0, told about page 3. Nothing here describes
        // what was requested, so nothing can be concluded.
        server.enqueue(page(entry("1"), number = 3, totalPages = 4, last = false))

        assertFalse(walk().first)
    }

    @Test
    fun `last disagreeing with the page count is not believed`() {
        server.enqueue(page(entry("1"), number = 0, totalPages = 5, last = true))

        assertFalse(walk().first)
    }

    @Test
    fun `a promise of more pages answered with nothing is not believed`() {
        // Neither half of this can be trusted: paging on would spin, and
        // stopping would call a truncated catalog complete.
        server.enqueue(page(number = 0, totalPages = 4, last = false, totalElements = 40))

        assertFalse(walk().first)
    }

    @Test
    fun `an entry with an unusable id costs the walk its completeness`() {
        server.enqueue(
            page(entry("1"), entry("../../etc/passwd"), number = 0, totalPages = 1),
        )

        val (complete, seen) = walk()

        // The good book is still streamed -- there is no reason to throw
        // it away -- but the walk is not a statement about what the
        // server holds any more.
        assertEquals(listOf("1"), seen)
        assertFalse(complete)
    }

    @Test
    fun `an entry with no media type at all costs the walk its completeness`() {
        // The dangerous shape: if a future Grimmory stopped sending
        // mediaType, every book would filter out and an empty shelf
        // would read as "the library was emptied".
        server.enqueue(
            page(entry("1", mediaType = null), entry("2"), number = 0, totalPages = 1),
        )

        val (complete, seen) = walk()

        assertEquals(listOf("2"), seen)
        assertFalse(complete)
    }

    @Test
    fun `a body with no content array at all is not an empty library`() {
        // The shape that mattered most: absent, null, or some other
        // type all parse to no books, which is exactly what a library
        // someone emptied looks like. Believing any of them prunes the
        // whole shelf on a body this client never understood.
        val bodies = listOf(
            page(number = 0, totalPages = 1, omit = setOf("content")),
            page(number = 0, totalPages = 1, content = "null"),
        )

        for (body in bodies) {
            server.enqueue(body)

            assertFalse(walk().first)
        }
    }

    @Test
    fun `a content field that is not an array is not an empty library`() {
        server.enqueue(page(number = 0, totalPages = 1, content = """{"1":"a book"}"""))

        assertFalse(walk().first)
    }

    @Test
    fun `an entry that is not even an object costs the walk its completeness`() {
        // Dropped before parsing rather than by it, so it is only ever
        // visible in the length of the array itself.
        server.enqueue(
            page(
                number = 0,
                totalPages = 1,
                content = "[${entry("1")},42]",
                totalElements = 2,
                numberOfElements = 2,
            ),
        )

        val (complete, seen) = walk()

        assertEquals(listOf("1"), seen)
        assertFalse(complete)
    }

    @Test
    fun `a page shorter than the server said it sent is not believed`() {
        // Internally consistent and still wrong: one row was lost in
        // transit, and it would be pruned as a book that had vanished.
        server.enqueue(page(entry("1"), number = 0, totalPages = 1, numberOfElements = 2))

        assertFalse(walk().first)
    }

    @Test
    fun `a page that does not say how many entries it sent is not believed`() {
        server.enqueue(
            page(entry("1"), number = 0, totalPages = 1, omit = setOf("numberOfElements")),
        )

        assertFalse(walk().first)
    }

    @Test
    fun `a walk that saw fewer books than the catalog holds is not believed`() {
        server.enqueue(
            page(entry("1"), number = 0, totalPages = 1, size = 9, totalElements = 9),
        )

        assertFalse(walk().first)
    }

    @Test
    fun `a page before the last that is not full is not believed`() {
        // The server said its pages hold three, and sent two. The third
        // is not on any other page either.
        server.enqueue(
            page(entry("1"), entry("2"), number = 0, totalPages = 2, size = 3, totalElements = 6),
        )

        assertFalse(walk().first)
    }

    @Test
    fun `a page size the server clamped is still believed`() {
        // 200 was asked for and 2 came back, on a server entitled to
        // decide its own page size. Measuring fullness against the
        // request rather than the answer would block pruning for good.
        server.enqueue(page(entry("1"), entry("2"), number = 0, totalPages = 2, totalElements = 3))
        server.enqueue(page(entry("3"), number = 1, totalPages = 2, size = 2, totalElements = 3))

        assertTrue(walk().first)
    }

    @Test
    fun `a catalog of books none of which can be read is not an emptied library`() {
        // A library of nothing but comics, which is legitimate -- and
        // indistinguishable from every EPUB in it having quietly changed
        // its media type. A server that really holds no EPUBs loses
        // nothing by the caution, having nothing to prune.
        server.enqueue(
            page(
                entry("1", mediaType = "application/x-cbz", profile = "DIVINA"),
                number = 0,
                totalPages = 1,
            ),
        )

        val (complete, seen) = walk()

        assertTrue(seen.isEmpty())
        assertFalse(complete)
    }

    @Test
    fun `a media type this build has never heard of stops the walk`() {
        // The hole a "not an EPUB means a comic" rule leaves open: one
        // ordinary EPUB alongside a hundred whose type was respelled
        // would keep the walk complete and delete the hundred. An
        // unknown type is this client saying so instead.
        server.enqueue(
            page(
                entry("1"),
                entry("2", mediaType = "application/x-not-invented-yet"),
                number = 0,
                totalPages = 1,
            ),
        )

        val (complete, seen) = walk()

        assertEquals(listOf("1"), seen)
        assertFalse(complete)
    }

    @Test
    fun `an unknown type on an early page does not hide the later ones`() {
        // One audiobook Grimmory renamed the type of, sitting on page 0
        // of a library that spans several. Stopping the walk there would
        // hide every book behind it: the shelf would lose them, and the
        // reader would go looking for a server fault. Forfeiting the
        // pruning is the whole cost.
        server.enqueue(
            page(
                entry("1"),
                entry("2", mediaType = "application/x-not-invented-yet"),
                number = 0,
                totalPages = 2,
                size = 2,
                totalElements = 4,
            ),
        )
        server.enqueue(
            page(
                entry("3"),
                entry("4"),
                number = 1,
                totalPages = 2,
                size = 2,
                totalElements = 4,
            ),
        )

        val (complete, seen) = walk()

        assertEquals(listOf("1", "3", "4"), seen)
        assertFalse(complete)
    }

    @Test
    fun `the formats Grimmory serves besides epub are known and skipped`() {
        // Every arm of KomgaMapper.getMediaType() but EPUB and its
        // null-type fallback. A format missing here reads as a response
        // this build cannot parse and costs the pruning, so the list
        // moves when Grimmory grows a format.
        val others = listOf(
            "application/pdf",
            "application/x-cbz",
            "application/fictionbook2+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "audio/" + "*",
        )
        for (other in others) {
            server.enqueue(
                page(entry("1"), entry("2", mediaType = other), number = 0, totalPages = 1),
            )

            val (complete, seen) = walk()

            assertEquals(other, listOf("1"), seen)
            assertTrue(other, complete)
        }
    }

    @Test
    fun `a type that names no format is unknown rather than unreadable`() {
        // An EPUB is a zip, so a mislabelled one arrives looking exactly
        // like a catch-all type. Filing those under "not a book" would
        // prune the books that got mislabelled; left unknown they stop
        // the walk, which is the harmless way to be wrong.
        for (vague in listOf("application/zip", "application/octet-stream")) {
            server.enqueue(page(entry("1", mediaType = vague), number = 0, totalPages = 1))

            assertFalse(walk().first)
        }
    }

    @Test
    fun `a media type carrying parameters is still the type it names`() {
        server.enqueue(
            page(entry("1", mediaType = "application/epub+zip; charset=binary"), number = 0, totalPages = 1),
        )

        val (complete, seen) = walk()

        assertEquals(listOf("1"), seen)
        assertTrue(complete)
    }

    @Test
    fun `a catalog that shrinks underneath the walk is not believed`() {
        // Page 0 describes three books over two pages; page 1 comes back
        // saying there were only two all along, and that it is the last.
        // Every count agrees with itself, and the third book -- never
        // sent -- would be deleted as one that had gone away.
        server.enqueue(page(entry("1"), number = 0, totalPages = 3, size = 1, totalElements = 3))
        server.enqueue(page(entry("2"), number = 1, totalPages = 2, size = 1, totalElements = 2))

        val (complete, seen) = walk()

        assertEquals(listOf("1", "2"), seen)
        assertFalse(complete)
    }

    @Test
    fun `the same book counted twice does not stand in for another`() {
        // Three books counted, three entries sent, two of them the same
        // one. The counts are impeccable and a book was still never
        // sent -- and it is the one that would be pruned.
        server.enqueue(page(entry("1"), entry("2"), number = 0, totalPages = 2, size = 2, totalElements = 3))
        server.enqueue(page(entry("2"), number = 1, totalPages = 2, size = 2, totalElements = 3))

        assertFalse(walk().first)
    }

    @Test
    fun `a page count that does not follow from the book count is not believed`() {
        // Two hundred books at one a page is not one page, whatever the
        // envelope says about being the last.
        server.enqueue(page(entry("1"), number = 0, totalPages = 1, size = 1, totalElements = 200))

        assertFalse(walk().first)
    }

    @Test
    fun `pagination fields that are present but null are not answers`() {
        // `has()` is true for a JSON null, while optInt answers 0 and
        // optBoolean answers its default -- together spelling "page 0 of
        // 0, and the last", which is a body that said nothing being read
        // as one that said the catalog ends here.
        server.enqueue(
            MockResponse(
                body = """{"content":[${entry("1")}],"number":null,"totalPages":null,
                "last":null,"size":1,"totalElements":1,"numberOfElements":1}""",
            ),
        )

        assertFalse(walk().first)
    }

    @Test
    fun `search asks the server nothing`() = runBlocking {
        // The shim has no search route. Library search is local, over
        // the catalog this client has already walked into the database.
        val found = GrimmoryCatalogClient().search(baseUrl(), credentials, "moby dick")

        assertTrue(found.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val EPUB = "application/epub+zip"
    }
}
