package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.RemoteCredentials
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Walking a liseur-sync catalog.
 *
 * What the shelf draws comes off one page shape (ADR-0015), so the
 * mapping is pinned hard: the author is picked by role rather than
 * guessed, the file fields are read flat off the book (ADR-0017), and a
 * walk that never ends is stopped rather than trusted.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncCatalogClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun open() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun close() {
        server.close()
    }

    @Test
    fun `every folder is walked, page by page`() = runTest {
        server.enqueue(
            json(
                """{"folders":[
                    {"folder_id":"f-1","name":"Main","kind":"calibre"},
                    {"folder_id":"f-2","name":"Shared","kind":"plain"}]}
                """.trimIndent(),
            ),
        )
        // f-1 answers a full page, so a cursor is followed.
        server.enqueue(
            json("""{"books":[${book("b-1")}],"next_cursor":"c-1"}"""),
        )
        server.enqueue(json("""{"books":[${book("b-2")}]}"""))
        server.enqueue(json("""{"books":[${book("b-3")}]}"""))

        val pages = mutableListOf<List<String>>()
        val walk = client().allBooks(BASE, credentials) { page ->
            pages += page.map { it.remoteId }
        }

        assertTrue(walk.complete)
        assertEquals(listOf(listOf("b-1"), listOf("b-2"), listOf("b-3")), pages)
        val targets = (0 until server.requestCount).map { server.takeRequest().target!! }
        assertTrue(targets[0].contains("/v1/folders?"))
        assertTrue(targets[1].contains("/v1/folders/f-1/books"))
        assertTrue(targets[2].contains("cursor=c-1"))
        assertTrue(targets[3].contains("/v1/folders/f-2/books"))
    }

    @Test
    fun `the folder listing itself is paged to the end`() = runTest {
        // The listing pages like everything else, and a walk that read
        // only the first page would silently hide a folder's books.
        server.enqueue(
            json("""{"folders":[{"folder_id":"f-1","name":"Main","kind":"plain"}],"next_after":"a-1"}"""),
        )
        server.enqueue(json("""{"folders":[{"folder_id":"f-2","name":"More","kind":"plain"}]}"""))
        server.enqueue(json("""{"books":[${book("b-1")}]}"""))
        server.enqueue(json("""{"books":[${book("b-2")}]}"""))

        val seen = mutableListOf<String>()
        val walk = client().allBooks(BASE, credentials) { page -> seen += page.map { it.remoteId } }

        assertTrue(walk.complete)
        assertEquals(listOf("b-1", "b-2"), seen)
        val targets = (0 until server.requestCount).map { server.takeRequest().target!! }
        assertTrue(targets[1].contains("after=a-1"))
    }

    @Test
    fun `a page is never asked for more than the server allows`() = runTest {
        // The catalog routes share one cap; asking above it is a 400,
        // not a bigger page.
        server.enqueue(json("""{"folders":[]}"""))

        client().allBooks(BASE, credentials) {}

        assertTrue(server.takeRequest().target!!.contains("limit=200"))
    }

    @Test
    fun `a walk that never ends is stopped and called incomplete`() = runTest {
        server.enqueue(json("""{"folders":[{"folder_id":"f-1","name":"Main","kind":"plain"}]}"""))
        repeat(LiseurSyncCatalogClient.MAX_PAGES) {
            server.enqueue(json("""{"books":[${book("b-x")}],"next_cursor":"c"}"""))
        }

        val walk = client().allBooks(BASE, credentials) {}

        assertFalse(walk.complete)
    }

    @Test
    fun `a book carries its author, series, digest and size off the page`() = runTest {
        server.enqueue(json("""{"folders":[{"folder_id":"f-1","name":"Main","kind":"calibre"}]}"""))
        server.enqueue(
            json(
                """{"books":[{
                    "book_id":"b-1","folder_id":"f-1","title":"Iron Gold",
                    "status":"active","created_at":"2026-01-01T00:00:00Z",
                    "updated_at":"2026-08-15T08:30:34.105Z",
                    "cover_url":"/v1/books/b-1/cover",
                    "media_type":"application/epub+zip","filename":"iron.epub",
                    "sha256":"ab12","size_bytes":1126528,
                    "contributors":[
                        {"id":"a-1","name":"Pierce Brown","role":"author"},
                        {"id":"a-2","name":"calibre","role":"bkp"}],
                    "series_source":"shared",
                    "series":[
                        {"id":"s-1","name":"Red Rising","position":4.5,"source":"shared"},
                        {"id":"s-2","name":"Solar War","position":1,"source":"folder"}]}]}
                """.trimIndent(),
            ),
        )

        val page = mutableListOf<com.chmouel.liseur.data.remote.RemoteBook>()
        client().allBooks(BASE, credentials) { page += it }

        val book = page.single()
        assertEquals("Pierce Brown", book.author)
        assertEquals("Red Rising", book.seriesName)
        assertEquals(4.5, book.seriesIndex!!, 0.0)
        assertEquals("s-1", book.seriesId)
        assertEquals("shared", book.seriesSource)
        assertEquals("f-1", book.folderId)
        assertEquals(listOf("Red Rising", "Solar War"), book.series.map { it.name })
        assertEquals(listOf("shared", "folder"), book.series.map { it.source })
        assertEquals(1126528L, book.sizeBytes)
        assertEquals("ab12", book.sha256)
        assertEquals("/v1/books/b-1/cover", book.coverHref)
        assertEquals("/v1/books/b-1/download", book.downloadHref)
        assertEquals(
            SyncOps.parseTime("2026-08-15T08:30:34.105Z"),
            book.updatedAt,
        )
    }

    @Test
    fun `a null series position remains absent rather than becoming NaN`() {
        val memberships = series(
            org.json.JSONArray("""[{"id":"s-1","name":"Dune","position":null}]"""),
        )

        assertNull(memberships.single().position)
    }

    @Test
    fun `an empty personal claim keeps its catalog source and revision`() {
        val catalog = books(
            org.json.JSONArray(
                """[{"book_id":"b-1","title":"Dune","status":"active","series":[],
                    "series_source":"personal",
                    "series_claim_updated_at":"2026-08-17T12:00:00.123456789Z"}]""".trimIndent(),
            ),
        ).single()

        assertEquals("personal", catalog.seriesSource)
        assertEquals(0, catalog.series.size)
        assertEquals(1786968000123L, catalog.seriesClaimUpdatedAt)
    }

    @Test
    fun `personal series writes use the liseur-sync layer routes`() = runTest {
        server.enqueue(
            json(
                """{"book_id":"b-1","source":"personal",
                    "series":[{"id":"s-1","name":"Murderbot","position":2,"source":"personal"}],
                    "folder":[{"id":"s-2","name":"Old","source":"folder"}],
                    "shared":null,
                    "personal":[{"id":"s-1","name":"Murderbot","position":2,"source":"personal"}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            json(
                """{"book_id":"b-1","source":"folder",
                    "series":[{"id":"s-2","name":"Old","source":"folder"}],
                    "folder":[{"id":"s-2","name":"Old","source":"folder"}],
                    "shared":null,"personal":null}
                """.trimIndent(),
            ),
        )

        val book = com.chmouel.liseur.data.db.Book(
            url = "liseur-sync:b-1",
            title = "Exit Strategy",
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
            remoteUuid = "b-1",
        )
        val client = LiseurSyncSeriesClient()

        val written = client.setPersonalSeries(BASE, credentials, book, "Murderbot", 2.0)
        val reset = client.resetPersonalSeries(BASE, credentials, book)

        assertEquals("personal", written?.source)
        assertEquals("Murderbot", written?.personal?.single()?.name)
        assertEquals(null, reset?.personal)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/books/b-1/series", put.target)
        assertTrue(put.body?.utf8()?.contains(""""scope":"personal"""") == true)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/books/b-1/series?scope=personal", delete.target)
    }

    /**
     * The server places a book the renumbering finds unplaced, so a
     * shelf sent under the wrong series id would not merely misnumber
     * it, it would refile every book on it. A shelf whose books do not
     * agree which series they are in is refused rather than guessed at,
     * and a book refiled by hand has no id at all until its claim has
     * been accepted.
     */
    @Test
    fun `renumbering is refused when the shelf does not agree on a series`() = runTest {
        val client = LiseurSyncSeriesClient()
        fun volume(id: String, series: String?) = com.chmouel.liseur.data.db.Book(
            url = "liseur-sync:$id",
            title = id,
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
            remoteUuid = id,
            seriesId = series,
        )

        assertFalse(
            client.reorderPersonalSeries(
                BASE, credentials, listOf(volume("b-1", "s-1"), volume("b-2", "s-2")),
            ),
        )
        assertFalse(
            client.reorderPersonalSeries(
                BASE, credentials, listOf(volume("b-1", null), volume("b-2", "s-1")),
            ),
        )
        assertEquals(0, server.requestCount)

        // A shelf that does agree renumbers through the library-wide
        // route: a series id names one shelf everywhere, so the folder
        // a volume was found in has no part in the address.
        server.enqueue(MockResponse(code = 204))
        assertTrue(
            client.reorderPersonalSeries(
                BASE, credentials, listOf(volume("b-1", "s-1"), volume("b-2", "s-1")),
            ),
        )
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/entities/series/s-1/order", put.target)
    }

    @Test
    fun `a book whose file is missing is kept, with nothing to download`() = runTest {
        // A disconnected disk is not a deleted book: it keeps its place
        // and its reading history, but there are no bytes to fetch.
        server.enqueue(json("""{"folders":[{"folder_id":"f-1","name":"Main","kind":"plain"}]}"""))
        server.enqueue(json("""{"books":[${book("b-1", status = "missing")}]}"""))

        val page = mutableListOf<com.chmouel.liseur.data.remote.RemoteBook>()
        client().allBooks(BASE, credentials) { page += it }

        val book = page.single()
        assertEquals("b-1", book.remoteId)
        assertNull(book.downloadHref)
    }

    @Test
    fun `a book with no author or series still renders`() = runTest {
        server.enqueue(json("""{"folders":[{"folder_id":"f-1","name":"Main","kind":"plain"}]}"""))
        server.enqueue(json("""{"books":[${book("b-1")}]}"""))

        val page = mutableListOf<com.chmouel.liseur.data.remote.RemoteBook>()
        client().allBooks(BASE, credentials) { page += it }

        val book = page.single()
        assertNull(book.author)
        assertNull(book.seriesName)
        assertNull(book.sizeBytes)
    }

    @Test
    fun `search asks every folder and joins the answers`() = runTest {
        server.enqueue(
            json(
                """{"folders":[
                    {"folder_id":"f-1","name":"Main","kind":"plain"},
                    {"folder_id":"f-2","name":"Shared","kind":"plain"}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"books":[${book("b-1")}],"facets":[],"truncated":false}"""))
        server.enqueue(json("""{"books":[${book("b-2")}],"facets":[],"truncated":false}"""))

        val found = client().search(BASE, credentials, "dune")

        assertEquals(listOf("b-1", "b-2"), found.map { it.remoteId })
        val second = (0 until server.requestCount).map { server.takeRequest().target!! }[1]
        assertTrue(second.contains("/v1/folders/f-1/search"))
        assertTrue(second.contains("q=dune"))
    }

    private fun client() = LiseurSyncCatalogClient()

    private fun book(id: String, status: String = "active") =
        """{"book_id":"$id","folder_id":"f-1","title":"A Book","status":"$status",
            "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z",
            "media_type":"application/epub+zip","filename":"a.epub","sha256":"ff01",
            "size_bytes":0,"contributors":[],"series_source":"folder","series":[]}
        """.trimIndent()

    private fun json(body: String) = MockResponse(code = 200, body = body)

    private companion object {
        val credentials = RemoteCredentials.Bearer("token")
    }

    private val BASE: String get() = "http://127.0.0.1:${server.port}"
}
