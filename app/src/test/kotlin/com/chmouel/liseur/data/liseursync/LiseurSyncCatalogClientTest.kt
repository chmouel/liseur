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
 * guessed, and a walk that never ends is stopped rather than trusted.
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
    fun `every readable library is walked, page by page`() = runTest {
        server.enqueue(
            json(
                """{"libraries":[
                    {"library_id":"lib-1","name":"Main","role":"manage"},
                    {"library_id":"lib-2","name":"Shared","role":"read"}]}
                """.trimIndent(),
            ),
        )
        // lib-1 answers a full page, so a cursor is followed.
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
        assertTrue(targets[1].contains("/v1/libraries/lib-1/books"))
        assertTrue(targets[2].contains("cursor=c-1"))
        assertTrue(targets[3].contains("/v1/libraries/lib-2/books"))
    }

    @Test
    fun `a walk that never ends is stopped and called incomplete`() = runTest {
        server.enqueue(json("""{"libraries":[{"library_id":"lib-1","name":"Main","role":"read"}]}"""))
        repeat(LiseurSyncCatalogClient.MAX_PAGES) {
            server.enqueue(json("""{"books":[${book("b-x")}],"next_cursor":"c"}"""))
        }

        val walk = client().allBooks(BASE, credentials) {}

        assertFalse(walk.complete)
    }

    @Test
    fun `a book carries its author, series and file size off the page`() = runTest {
        server.enqueue(json("""{"libraries":[{"library_id":"lib-1","name":"Main","role":"read"}]}"""))
        server.enqueue(
            json(
                """{"books":[{
                    "book_id":"b-1","library_id":"lib-1","title":"Iron Gold",
                    "status":"active","created_at":"2026-01-01T00:00:00Z",
                    "updated_at":"2026-08-15T08:30:34.105Z",
                    "cover_url":"/v1/books/b-1/cover",
                    "contributors":[
                        {"id":"a-1","name":"Pierce Brown","role":"author"},
                        {"id":"a-2","name":"calibre","role":"bkp"}],
                    "series":[{"id":"s-1","name":"Red Rising","position":4.5}],
                    "files":[{"file_id":"f-1","media_type":"application/epub+zip",
                        "filename":"iron.epub","sha256":"ab12","size_bytes":1126528}]}]}
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
        assertEquals(1126528L, book.sizeBytes)
        assertEquals("/v1/books/b-1/cover", book.coverHref)
        assertEquals("/v1/books/b-1/download", book.downloadHref)
        assertEquals(
            SyncOps.parseTime("2026-08-15T08:30:34.105Z"),
            book.updatedAt,
        )
    }

    @Test
    fun `a book with no author or series still renders`() = runTest {
        server.enqueue(json("""{"libraries":[{"library_id":"lib-1","name":"Main","role":"read"}]}"""))
        server.enqueue(json("""{"books":[${book("b-1")}]}"""))

        val page = mutableListOf<com.chmouel.liseur.data.remote.RemoteBook>()
        client().allBooks(BASE, credentials) { page += it }

        val book = page.single()
        assertNull(book.author)
        assertNull(book.seriesName)
        assertNull(book.sizeBytes)
    }

    @Test
    fun `search asks every library and joins the answers`() = runTest {
        server.enqueue(
            json(
                """{"libraries":[
                    {"library_id":"lib-1","name":"Main","role":"read"},
                    {"library_id":"lib-2","name":"Shared","role":"read"}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"books":[${book("b-1")}],"facets":[],"truncated":false}"""))
        server.enqueue(json("""{"books":[${book("b-2")}],"facets":[],"truncated":false}"""))

        val found = client().search(BASE, credentials, "dune")

        assertEquals(listOf("b-1", "b-2"), found.map { it.remoteId })
        val second = (0 until server.requestCount).map { server.takeRequest().target!! }[1]
        assertTrue(second.contains("q=dune"))
    }

    private fun client() = LiseurSyncCatalogClient()

    private fun book(id: String) =
        """{"book_id":"$id","library_id":"lib-1","title":"A Book","status":"active",
            "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z",
            "contributors":[],"series":[],"files":[]}
        """.trimIndent()

    private fun json(body: String) = MockResponse(code = 200, body = body)

    private companion object {
        val credentials = RemoteCredentials.Bearer("token")
    }

    private val BASE: String get() = "http://127.0.0.1:${server.port}"
}
