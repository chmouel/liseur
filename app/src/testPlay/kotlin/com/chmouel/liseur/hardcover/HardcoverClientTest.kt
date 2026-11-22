package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private fun client() = HardcoverClient(endpoint = "http://127.0.0.1:${server.port}/v1/graphql", spacingMillis = 0)

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
    fun `a pasted token no header can carry is refused without a request`() = runBlocking {
        assertEquals(RemoteResult.Failed(SyncFailure.Unauthorised), client().me("to\nk"))
        assertEquals(RemoteResult.Failed(SyncFailure.Unauthorised), client().me("tök"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an edition is looked up by either spelling of its isbn`() = runBlocking {
        answer(
            """{"editions":[{"id":5,"book_id":9,"title":"The Hobbit","edition_format":"ebook",
            "pages":300,"isbn_13":"9780547928227","isbn_10":null,"release_year":2012,"users_count":48,
            "book":{"title":"The Hobbit"},"publisher":{"name":"Mariner"}}]}""",
        )

        val result = client().editionsByIsbn("tok", Isbn("9780547928227", "054792822X"))

        val edition = (result as RemoteResult.Ok).value.single()
        assertEquals(
            HardcoverEdition(
                id = 5, bookId = 9, title = "The Hobbit", bookTitle = "The Hobbit",
                format = "ebook", pages = 300, isbn13 = "9780547928227", isbn10 = null,
                releaseYear = 2012, publisher = "Mariner", usersCount = 48,
            ),
            edition,
        )
        val sent = sent()
        assertTrue(sent.getString("query").contains("users_count"))
        val variables = sent.getJSONObject("variables")
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
    fun `the latest read comes back with its pages, dates and edition`() = runBlocking {
        answer(
            """{"user_books":[{"id":7,"status_id":2,"rating":3.5,"edition_id":5,"user_book_reads":[
            {"id":3,"progress_pages":120,"started_at":"2026-03-01","finished_at":null,
            "edition_id":8,"edition":{"pages":600}}]}]}""",
        )

        val result = client().shelfState("tok", userId = 42, bookId = 9)

        assertEquals(
            HardcoverShelfState(7, 2, 3.5, HardcoverRead(3, 120, "2026-03-01", null, 8, 600), editionId = 5),
            (result as RemoteResult.Ok).value,
        )
        val variables = sent().getJSONObject("variables")
        assertEquals(42, variables.getInt("userId"))
        assertEquals(9, variables.getInt("bookId"))
    }

    @Test
    fun `adding a book sends one object and answers the new entry`() = runBlocking {
        answer("""{"insert_user_book":{"error":null,"user_book":{"id":7}}}""")

        val result = client().addToLibrary("tok", bookId = 9, editionId = 5, statusId = 3, rating = 4.5)

        assertEquals(7L, (result as RemoteResult.Ok).value)
        val entry = sent().getJSONObject("variables").getJSONObject("object")
        assertEquals(9, entry.getInt("book_id"))
        assertEquals(5, entry.getInt("edition_id"))
        assertEquals(3, entry.getInt("status_id"))
        assertEquals(4.5, entry.getDouble("rating"), 0.0)
    }

    @Test
    fun `an entry update sends only what changes, and a cleared rating as null`() = runBlocking {
        answer("""{"update_user_book":{"error":null,"user_book":{"id":7}}}""")
        answer("""{"update_user_book":{"error":null,"user_book":{"id":7}}}""")

        client().updateEntry("tok", 7, HardcoverEntryStep.Update(statusId = 3, rating = null))
        client().updateEntry("tok", 7, HardcoverEntryStep.Update(null, null, clearRating = true))

        val status = sent().getJSONObject("variables")
        assertEquals(7, status.getInt("id"))
        assertEquals("""{"status_id":3}""", status.getJSONObject("object").toString())
        val cleared = sent().getJSONObject("variables").getJSONObject("object")
        assertEquals(listOf("rating"), cleared.keys().asSequence().toList())
        assertTrue(cleared.isNull("rating"))
    }

    @Test
    fun `a read update sends only the fields given`() = runBlocking {
        answer("""{"update_user_book_read":{"error":null,"user_book_read":{"id":3}}}""")

        val result = client().updateRead("tok", 3, HardcoverReadStep(pages = 150, finishedAt = "2026-04-18"))

        assertTrue(result is RemoteResult.Ok)
        val body = sent()
        assertTrue(body.getString("query").contains("update_user_book_read"))
        val read = body.getJSONObject("variables").getJSONObject("object")
        assertEquals(setOf("progress_pages", "finished_at"), read.keys().asSequence().toSet())
        assertEquals(150, read.getInt("progress_pages"))
        assertEquals("2026-04-18", read.getString("finished_at"))
    }

    @Test
    fun `a mutation that answers with an error did not happen`() = runBlocking {
        answer("""{"update_user_book":{"error":"Not allowed","user_book":null}}""")

        val result = client().updateEntry("tok", 7, HardcoverEntryStep.Update(3, null))

        assertEquals(SyncFailure.Malformed, result.failure)
    }

    @Test
    fun `an expired token inside a graphql error asks for a new token`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"errors":[{"message":"Could not verify JWT","extensions":{"code":"invalid-jwt"}}]}""",
            ),
        )

        assertEquals(SyncFailure.Unauthorised, client().me("tok").failure)
    }

    @Test
    fun `a token missing a scope is told apart`() = runBlocking {
        server.enqueue(MockResponse(code = 403, body = "{}"))

        assertEquals(SyncFailure.Forbidden, client().me("tok").failure)
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

    @Test
    fun `an edition carries its cover and its authors, the edition image first`() = runBlocking {
        answer(
            """{"editions":[
              {"id":1,"book_id":9,"title":"A","image":{"url":"https://assets.hardcover.app/e.jpg"},
               "book":{"title":"B","cached_image":{"url":"https://assets.hardcover.app/b.jpg"},
               "cached_contributors":[{"author":{"name":"Ann"},"contribution":"Author"},
               {"author":{"name":"Tr"},"contribution":"Translator"},{"author":{"name":"Bo"},"contribution":null}]}},
              {"id":2,"book_id":9,"title":"B","image":null,
               "book":{"cached_image":{"url":"https://assets.hardcover.app/b.jpg"},
               "cached_contributors":[{"author":{"name":"Tr"},"contribution":"Translator"}]}}
            ]}""",
        )

        val editions = (client().editionsOfBooks("tok", listOf(9)) as RemoteResult.Ok).value

        assertEquals("https://assets.hardcover.app/e.jpg", editions[0].coverUrl)
        assertEquals(listOf("Ann", "Bo"), editions[0].authors)
        assertEquals("https://assets.hardcover.app/b.jpg", editions[1].coverUrl)
        assertEquals(listOf("Tr"), editions[1].authors)
    }

    @Test
    fun `a cover on another host or over http is dropped`() = runBlocking {
        answer(
            """{"editions":[{"id":1,"book_id":9,"title":"A","image":{"url":"http://assets.hardcover.app/e.jpg"},
              "book":{"cached_image":{"url":"https://evil.example/b.jpg"},"cached_contributors":[]}}]}""",
        )

        val edition = (client().editionsOfBooks("tok", listOf(9)) as RemoteResult.Ok).value.single()

        assertEquals(null, edition.coverUrl)
        assertEquals(emptyList<String>(), edition.authors)
    }

    @Test
    fun `a book is found by id or by slug`() = runBlocking {
        answer("""{"books":[{"id":9}]}""")
        answer("""{"books":[]}""")

        assertEquals(listOf(9L), (client().booksByRef("tok", HardcoverRef.BookId(9)) as RemoteResult.Ok).value)
        assertEquals(9, sent().getJSONObject("variables").getInt("id"))
        assertEquals(emptyList<Long>(), (client().booksByRef("tok", HardcoverRef.Slug("x-1")) as RemoteResult.Ok).value)
        assertEquals("x-1", sent().getJSONObject("variables").getString("slug"))
    }

    @Test
    fun `edition details parse title, authors, description, series and cover fallbacks`() = runBlocking {
        answer(
            """{"editions":[{"id":5,"title":"Edition Title","subtitle":"Ignored Subtitle",
              "image":null,"cached_image":"{\"url\":\"https://assets.hardcover.app/ec.jpg\"}",
              "book":{"title":"Book Title","subtitle":"Ignored Book Subtitle","description":"A synopsis.",
              "cached_image":{"url":"https://assets.hardcover.app/bc.jpg"},
              "cached_contributors":[{"author":{"name":"Ann Leckie"},"contribution":"Author"},
              {"author":{"name":"Translator"},"contribution":"Translator"}],
              "featured_book_series":{"position":1.5,"series":{"name":"Imperial Radch"}},
              "book_series":[{"position":2.0,"series":{"name":"Other Series"}}]}}]}""",
        )

        val details = (client().editionDetails("tok", 5) as RemoteResult.Ok).value

        assertEquals(
            HardcoverEditionDetails(
                editionId = 5,
                title = "Edition Title",
                author = "Ann Leckie",
                description = "A synopsis.",
                coverUrl = "https://assets.hardcover.app/ec.jpg",
                seriesName = "Imperial Radch",
                seriesPosition = 1.5,
            ),
            details,
        )
        assertEquals(5, sent().getJSONObject("variables").getInt("id"))
    }

    @Test
    fun `edition details fall back to book title, book cover and first book series`() = runBlocking {
        answer(
            """{"editions":[{"id":5,"title":"","image":null,"cached_image":null,
              "book":{"title":"Book Title","description":null,
              "cached_image":{"url":"https://assets.hardcover.app/bc.jpg"},
              "cached_contributors":"[{\"author\":{\"name\":\"First\"}},{\"author\":{\"name\":\"Second\"}}]",
              "featured_book_series":null,
              "book_series":[{"position":null,"series":{"name":"Saga"}}]}}]}""",
        )

        val details = (client().editionDetails("tok", 5) as RemoteResult.Ok).value

        assertEquals(
            HardcoverEditionDetails(
                editionId = 5,
                title = "Book Title",
                author = "First, Second",
                description = null,
                coverUrl = "https://assets.hardcover.app/bc.jpg",
                seriesName = "Saga",
                seriesPosition = null,
            ),
            details,
        )
    }

    @Test
    fun `a rejected edition image still lets a valid fallback cover through`() = runBlocking {
        answer(
            """{"editions":[{"id":5,"title":"T","image":{"url":"https://elsewhere.example/c.jpg"},
              "cached_image":null,
              "book":{"title":"T","cached_image":{"url":"https://assets.hardcover.app/bc.jpg"},
              "cached_contributors":[]}}]}""",
        )

        val details = (client().editionDetails("tok", 5) as RemoteResult.Ok).value

        assertEquals("https://assets.hardcover.app/bc.jpg", details?.coverUrl)
    }

    @Test
    fun `edition details answer null when the edition is missing`() = runBlocking {
        answer("""{"editions":[]}""")

        assertNull((client().editionDetails("tok", 5) as RemoteResult.Ok).value)
    }

    @Test
    fun `cancelling after the headers arrive stops a slow body read`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"data":{"me":[{"id":42,"username":"reader"}]}}""")
                .throttleBody(5, 1, TimeUnit.SECONDS)
                .build(),
        )
        var result: Any? = null
        val job = launch(Dispatchers.IO) { result = client().me("tok") }
        while (server.requestCount < 1) delay(10)
        delay(300)

        val started = System.nanoTime()
        job.cancelAndJoin()

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertEquals(1, server.requestCount)
        assertTrue(job.isCancelled)
        assertNull(result)
    }
}
