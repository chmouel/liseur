package com.chmouel.liseur.data.hardcover

import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.HardcoverShelfState
import com.chmouel.liseur.domain.Isbn
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Hardcover client against answers shaped as its documentation
 * shows them.
 *
 * These bodies were written from docs.hardcover.app, not captured from
 * the live API: the search result shape and `insert_user_book` in
 * particular still want checking against a real account.
 */
class HardcoverClientTest {

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

    private fun client() = HardcoverClient(endpoint = "http://127.0.0.1:${server.port}/v1/graphql")

    private fun answer(data: String) = server.enqueue(MockResponse(code = 200, body = """{"data":$data}"""))

    private fun sent(): JSONObject = JSONObject(server.takeRequest().body!!.utf8())

    @Test
    fun `a request is one post, signed with the bearer token`() = runBlocking {
        answer("""{"me":[{"id":42,"username":"reader"}]}""")

        val result = client().me("tok")

        assertEquals(HardcoverUser(42, "reader"), (result as RemoteResult.Ok).value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/graphql", request.target)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("Liseur"))
    }

    @Test
    fun `a token pasted with its bearer prefix is not signed twice`() = runBlocking {
        answer("""{"me":[{"id":42,"username":"reader"}]}""")

        client().me("  Bearer tok\n")

        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an edition is looked up by either spelling of its isbn`() = runBlocking {
        answer(
            """{"editions":[{"id":5,"book_id":9,"title":"The Hobbit","edition_format":"ebook",
            "pages":300,"isbn_13":"9780547928227","isbn_10":null,"release_year":2012,
            "book":{"title":"The Hobbit"},"publisher":{"name":"Mariner"}}]}""",
        )

        val result = client().editionsByIsbn("tok", Isbn("9780547928227", "054792822X"))

        val edition = (result as RemoteResult.Ok).value.single()
        assertEquals(
            HardcoverEdition(
                id = 5, bookId = 9, title = "The Hobbit", bookTitle = "The Hobbit",
                format = "ebook", pages = 300, isbn13 = "9780547928227", isbn10 = null,
                releaseYear = 2012, publisher = "Mariner",
            ),
            edition,
        )
        val variables = sent().getJSONObject("variables")
        assertEquals("9780547928227", variables.getString("isbn13"))
        assertEquals("054792822X", variables.getString("isbn10"))
    }

    @Test
    fun `a book with no isbn10 never asks for a null one`() = runBlocking {
        answer("""{"editions":[]}""")

        client().editionsByIsbn("tok", Isbn("9791032305690", null))

        assertEquals("9791032305690", sent().getJSONObject("variables").getString("isbn10"))
    }

    @Test
    fun `search hits become books, and a hit without an id is dropped`() = runBlocking {
        answer(
            """{"search":{"results":{"hits":[
            {"document":{"id":"9","title":"The Hobbit","author_names":["J.R.R. Tolkien"],"release_year":1937}},
            {"document":{"title":"No id"}}]}}}""",
        )

        val result = client().searchBooks("tok", "the hobbit tolkien")

        assertEquals(
            listOf(HardcoverBook(9, "The Hobbit", listOf("J.R.R. Tolkien"), 1937)),
            (result as RemoteResult.Ok).value,
        )
        assertEquals("the hobbit tolkien", sent().getJSONObject("variables").getString("query"))
    }

    @Test
    fun `a book's default editions are read in one request`() = runBlocking {
        answer(
            """{"books":[{
            "default_ebook_edition":{"id":1,"book_id":9,"title":"E","edition_format":"ebook","pages":null},
            "default_physical_edition":{"id":2,"book_id":9,"title":"P","edition_format":"paperback","pages":320},
            "editions":[{"id":3,"book_id":9,"title":"M","pages":310}]}]}""",
        )

        val result = client().defaultEditions("tok", bookId = 9)

        val defaults = (result as RemoteResult.Ok).value
        assertEquals(1L, defaults.ebook?.id)
        assertEquals(null, defaults.ebook?.pages)
        assertEquals(320, defaults.physical?.pages)
        assertEquals(3L, defaults.mostRead?.id)
        assertEquals(9, sent().getJSONObject("variables").getInt("bookId"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a book with no default editions has none`() = runBlocking {
        answer("""{"books":[{"default_ebook_edition":null,"default_physical_edition":null,"editions":[]}]}""")

        val defaults = (client().defaultEditions("tok", bookId = 9) as RemoteResult.Ok).value

        assertEquals(HardcoverDefaultEditions(null, null, null), defaults)
    }

    @Test
    fun `a book not in the library is an empty shelf state, not a failure`() = runBlocking {
        answer("""{"user_books":[]}""")

        val result = client().shelfState("tok", userId = 42, bookId = 9)

        assertEquals(HardcoverShelfState(null, null, null, null), (result as RemoteResult.Ok).value)
    }

    @Test
    fun `the read in progress is reported with its pages`() = runBlocking {
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":120}]}]}""")

        val result = client().shelfState("tok", userId = 42, bookId = 9)

        assertEquals(HardcoverShelfState(7, 2, 3, 120), (result as RemoteResult.Ok).value)
    }

    @Test
    fun `a started read with no pages logged has none`() = runBlocking {
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":null}]}]}""")

        val result = client().shelfState("tok", userId = 42, bookId = 9)

        assertEquals(HardcoverShelfState(7, 2, 3, null), (result as RemoteResult.Ok).value)
    }

    @Test
    fun `adding a book answers the new library entry`() = runBlocking {
        answer("""{"insert_user_book":{"error":null,"user_book":{"id":7,"status_id":2}}}""")

        val result = client().addToLibrary("tok", bookId = 9, editionId = 5, statusId = 2)

        assertEquals(7L, (result as RemoteResult.Ok).value)
        val variables = sent().getJSONObject("variables")
        assertEquals(9, variables.getInt("bookId"))
        assertEquals(5, variables.getInt("editionId"))
        assertEquals(2, variables.getInt("statusId"))
    }

    @Test
    fun `progress and status go out as their own mutations`() = runBlocking {
        answer("""{"update_user_book_read":{"error":null,"user_book_read":{"id":3,"progress_pages":150}}}""")
        answer("""{"update_user_book":{"error":null,"user_book":{"id":7,"status_id":3}}}""")

        assertTrue(client().setProgress("tok", readId = 3, pages = 150) is RemoteResult.Ok)
        assertTrue(client().setStatus("tok", userBookId = 7, statusId = 3) is RemoteResult.Ok)

        val progress = sent()
        assertTrue(progress.getString("query").contains("update_user_book_read"))
        assertEquals(150, progress.getJSONObject("variables").getInt("pages"))
        val status = sent()
        assertTrue(status.getString("query").contains("update_user_book("))
        assertEquals(3, status.getJSONObject("variables").getInt("statusId"))
    }

    @Test
    fun `a mutation that answers with an error did not happen`() = runBlocking {
        answer("""{"update_user_book":{"error":"Not allowed","user_book":null}}""")

        val result = client().setStatus("tok", userBookId = 7, statusId = 3)

        assertEquals(SyncFailure.Malformed, result.failure)
    }

    @Test
    fun `a graphql errors array is a failure even on http 200`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = """{"errors":[{"message":"field not found"}]}"""))

        assertEquals(SyncFailure.Malformed, client().me("tok").failure)
    }

    @Test
    fun `an expired token and a spent rate limit are told apart`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"error":"invalid_token"}"""))
        server.enqueue(MockResponse(code = 429, body = """{"error":"Too Many Requests"}"""))

        assertEquals(SyncFailure.Unauthorised, client().me("tok").failure)
        assertEquals(SyncFailure.ServerError(429), client().me("tok").failure)
    }

    @Test
    fun `a redirect is refused rather than followed with the token`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1:1/elsewhere").build(),
        )

        assertEquals(SyncFailure.Malformed, client().me("tok").failure)
        assertEquals(1, server.requestCount)
    }
}
