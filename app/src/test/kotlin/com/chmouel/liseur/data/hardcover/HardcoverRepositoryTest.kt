package com.chmouel.liseur.data.hardcover

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.HardcoverAccount
import com.chmouel.liseur.data.db.HardcoverBookLink
import com.chmouel.liseur.data.db.HardcoverDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.remote.SyncFailure
import java.net.InetAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A push from end to end: which requests it makes, in what order, and
 * the ones it must not make.
 */
class HardcoverRepositoryTest {

    private lateinit var server: MockWebServer
    private val dao = FakeDao()
    private val books = mutableMapOf<String, Book>()
    private val progress = mutableMapOf<String, ReadingProgress>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun repository() = HardcoverRepository(
        dao = dao,
        bookFor = { books[it] },
        progressFor = { progress[it] },
        client = HardcoverClient(endpoint = "http://127.0.0.1:${server.port}/v1/graphql"),
        now = { 1_000L },
        seal = { "sealed:$it" },
        open = { it.tokenCipher.removePrefix("sealed:") },
    )

    private fun answer(data: String) =
        server.enqueue(MockResponse(code = 200, body = """{"data":$data}"""))

    private fun sentQuery(): String = JSONObject(server.takeRequest().body!!.utf8()).getString("query")

    private fun signedIn(autoSync: Boolean = true) {
        dao.account.value = HardcoverAccount(
            userId = 42, username = "reader", tokenCipher = "sealed:tok",
            addedAt = 1, autoSync = autoSync,
        )
    }

    private fun shelve(url: String = BOOK, identifier: String? = "urn:isbn:9780547928227") {
        books[url] = Book(
            url = url, title = "The Hobbit", author = "J.R.R. Tolkien", coverPath = null,
            source = null, addedAt = 1, lastOpenedAt = null, workId = identifier,
        )
    }

    private fun readTo(share: Double, url: String = BOOK) {
        progress[url] = ReadingProgress(
            bookUrl = url, locatorJson = "{}", totalProgression = share, updatedAt = 1,
        )
    }

    private val edition =
        """{"editions":[{"id":5,"book_id":9,"title":"The Hobbit","edition_format":"ebook","pages":300,
        "isbn_13":"9780547928227","isbn_10":null,"release_year":2012,"book":{"title":"The Hobbit"},
        "publisher":null}]}"""

    @Test
    fun `a token hardcover accepts is kept, sealed`() = runBlocking {
        answer("""{"me":[{"id":42,"username":"reader"}]}""")

        val outcome = repository().connect("Bearer tok")

        assertEquals(HardcoverConnectOutcome.Connected("reader"), outcome)
        val stored = dao.account.value!!
        assertEquals(42L, stored.userId)
        assertEquals("sealed:tok", stored.tokenCipher)
    }

    @Test
    fun `a token hardcover refuses stores nothing`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"error":"invalid_token"}"""))

        val outcome = repository().connect("nope")

        assertEquals(HardcoverConnectOutcome.Failed(SyncFailure.Unauthorised), outcome)
        assertNull(dao.account.value)
    }

    @Test
    fun `a first push matches by isbn, adds the book, then logs the page`() = runBlocking {
        signedIn()
        shelve()
        readTo(0.5)
        answer(edition)
        answer("""{"user_books":[]}""")
        answer("""{"insert_user_book":{"error":null,"user_book":{"id":7,"status_id":2}}}""")
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":null}]}]}""")
        answer("""{"update_user_book_read":{"error":null,"user_book_read":{"id":3,"progress_pages":150}}}""")

        val outcome = repository().push(BOOK, asked = false)

        assertEquals(HardcoverPushOutcome.Pushed, outcome)
        assertTrue(sentQuery().contains("editions("))
        assertTrue(sentQuery().contains("user_books("))
        assertTrue(sentQuery().contains("insert_user_book("))
        assertTrue(sentQuery().contains("user_books("))
        val logged = JSONObject(server.takeRequest().body!!.utf8())
        assertTrue(logged.getString("query").contains("update_user_book_read("))
        assertEquals(150, logged.getJSONObject("variables").getInt("pages"))
        val link = dao.links.getValue(BOOK)
        assertEquals(5L, link.editionId)
        assertEquals(9L, link.hardcoverBookId)
        assertEquals(300, link.editionPages)
        assertEquals(1_000L, link.pushedAt)
    }

    @Test
    fun `a remembered match is not looked up again, and agreement writes nothing`() = runBlocking {
        signedIn()
        shelve()
        readTo(0.5)
        dao.links[BOOK] = HardcoverBookLink(BOOK, hardcoverBookId = 9, editionId = 5, editionPages = 300)
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":150}]}]}""")

        val outcome = repository().push(BOOK, asked = false)

        assertEquals(HardcoverPushOutcome.UpToDate, outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `finishing sets the book to read`() = runBlocking {
        signedIn()
        shelve()
        readTo(1.0)
        dao.links[BOOK] = HardcoverBookLink(BOOK, hardcoverBookId = 9, editionId = 5, editionPages = 300)
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":280}]}]}""")
        answer("""{"update_user_book":{"error":null,"user_book":{"id":7,"status_id":3}}}""")

        val outcome = repository().push(BOOK, asked = false)

        assertEquals(HardcoverPushOutcome.Pushed, outcome)
        server.takeRequest()
        val status = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(3, status.getJSONObject("variables").getInt("statusId"))
    }

    @Test
    fun `a book with no isbn is matched by title and author, then remembered`() = runBlocking {
        signedIn()
        shelve(identifier = "urn:uuid:2f1b0a3c-6f0e-4d0a-9d55-0a1b2c3d4e5f")
        readTo(0.5)
        answer(
            """{"search":{"results":{"hits":[
            {"document":{"id":"9","title":"The Hobbit","author_names":["J.R.R. Tolkien"]}}]}}}""",
        )
        answer(
            """{"books":[{"default_ebook_edition":{"id":5,"book_id":9,"title":"The Hobbit",
            "edition_format":"ebook","pages":300},"default_physical_edition":null,"editions":[]}]}""",
        )
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":150}]}]}""")

        val outcome = repository().push(BOOK, asked = false)

        assertEquals(HardcoverPushOutcome.UpToDate, outcome)
        assertTrue(sentQuery().contains("search("))
        assertTrue(sentQuery().contains("default_ebook_edition"))
        val link = dao.links.getValue(BOOK)
        assertEquals(5L, link.editionId)
        assertEquals(9L, link.hardcoverBookId)
        assertEquals(300, link.editionPages)

        // Next time the match is known: no search, no edition lookup.
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":150}]}]}""")
        repository().push(BOOK, asked = false)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a book with no isbn and no agreeing hit waits for the reader`() = runBlocking {
        signedIn()
        shelve(identifier = "urn:uuid:2f1b0a3c-6f0e-4d0a-9d55-0a1b2c3d4e5f")
        readTo(0.5)
        answer(
            """{"search":{"results":{"hits":[
            {"document":{"id":"6","title":"The Silmarillion","author_names":["J.R.R. Tolkien"]}}]}}}""",
        )

        val outcome = repository().push(BOOK, asked = true)

        assertEquals(HardcoverPushOutcome.NeedsMatch, outcome)
        assertEquals(1, server.requestCount)
        assertNull(dao.links[BOOK])
    }

    @Test
    fun `an isbn hardcover does not know falls back to title and author`() = runBlocking {
        signedIn()
        shelve()
        readTo(0.5)
        answer("""{"editions":[]}""")
        answer(
            """{"search":{"results":{"hits":[
            {"document":{"id":"9","title":"The Hobbit","author_names":["J.R.R. Tolkien"]}}]}}}""",
        )
        answer(
            """{"books":[{"default_ebook_edition":null,"default_physical_edition":{"id":8,"book_id":9,
            "title":"The Hobbit","pages":310},"editions":[]}]}""",
        )
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":200}]}]}""")

        repository().push(BOOK, asked = false)

        assertEquals(8L, dao.links.getValue(BOOK).editionId)
    }

    @Test
    fun `a book kept off hardcover is never sent, even when asked`() = runBlocking {
        signedIn()
        shelve()
        readTo(0.5)
        repository().setExcluded(BOOK, true)

        assertEquals(HardcoverPushOutcome.SwitchedOff, repository().push(BOOK, asked = true))
        assertEquals(false, repository().pushesOnClose(BOOK))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with automatic sync off only an asked-for push goes out`() = runBlocking {
        signedIn(autoSync = false)
        shelve()
        readTo(0.5)
        dao.links[BOOK] = HardcoverBookLink(BOOK, hardcoverBookId = 9, editionId = 5, editionPages = 300)

        assertEquals(false, repository().pushesOnClose(BOOK))
        assertEquals(HardcoverPushOutcome.SwitchedOff, repository().push(BOOK, asked = false))
        assertEquals(0, server.requestCount)

        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":150}]}]}""")
        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = true))
    }

    @Test
    fun `no account means nothing to do`() = runBlocking {
        shelve()

        assertEquals(HardcoverPushOutcome.NotConnected, repository().push(BOOK, asked = true))
        assertEquals(false, repository().pushesOnClose(BOOK))
    }

    @Test
    fun `a failure part way is reported and leaves the push unrecorded`() = runBlocking {
        signedIn()
        shelve()
        readTo(0.5)
        dao.links[BOOK] = HardcoverBookLink(BOOK, hardcoverBookId = 9, editionId = 5, editionPages = 300)
        answer("""{"user_books":[{"id":7,"status_id":2,"user_book_reads":[{"id":3,"progress_pages":10}]}]}""")
        server.enqueue(MockResponse(code = 503, body = "{}"))

        val outcome = repository().push(BOOK, asked = false)

        assertEquals(HardcoverPushOutcome.Failed(SyncFailure.ServerError(503)), outcome)
        assertNull(dao.links.getValue(BOOK).pushedAt)
    }

    @Test
    fun `disconnecting forgets the account and every match`() = runBlocking {
        signedIn()
        dao.links[BOOK] = HardcoverBookLink(BOOK, hardcoverBookId = 9, editionId = 5)

        repository().disconnect()

        assertNull(dao.account.value)
        assertTrue(dao.links.isEmpty())
    }

    @Test
    fun `a restored account keeps the matches of the same user and drops another user's`() = runBlocking {
        signedIn()
        dao.links[BOOK] = HardcoverBookLink(BOOK, hardcoverBookId = 9, editionId = 5)
        val same = dao.account.value!!.copy(autoSync = false)

        repository().restoreBackup(same)

        assertEquals(false, dao.account.value!!.autoSync)
        assertEquals(1, dao.links.size)

        repository().restoreBackup(same.copy(userId = 99, username = "someone"))

        assertEquals(99L, dao.account.value!!.userId)
        assertTrue(dao.links.isEmpty())

        repository().restoreBackup(null)
        assertNull(dao.account.value)
    }

    private class FakeDao : HardcoverDao {
        val account = MutableStateFlow<HardcoverAccount?>(null)
        val links = mutableMapOf<String, HardcoverBookLink>()
        private val linkChanges = MutableStateFlow(0)

        override fun observeAccount(id: Long): Flow<HardcoverAccount?> = account
        override suspend fun account(id: Long): HardcoverAccount? = account.value
        override suspend fun upsertAccount(account: HardcoverAccount) {
            this.account.value = account
        }

        override suspend fun setAutoSync(autoSync: Boolean, id: Long) {
            account.value = account.value?.copy(autoSync = autoSync)
        }

        override suspend fun deleteAccount(id: Long) {
            account.value = null
        }

        override fun observeLink(bookUrl: String): Flow<HardcoverBookLink?> =
            linkChanges.map { links[bookUrl] }

        override suspend fun link(bookUrl: String): HardcoverBookLink? = links[bookUrl]
        override suspend fun upsertLink(link: HardcoverBookLink) {
            links[link.bookUrl] = link
            linkChanges.value++
        }

        override suspend fun forgetBooks(bookUrls: List<String>) {
            bookUrls.forEach(links::remove)
            linkChanges.value++
        }

        override suspend fun setPushedAt(bookUrl: String, at: Long) {
            links[bookUrl]?.let { links[bookUrl] = it.copy(pushedAt = at) }
            linkChanges.value++
        }

        override suspend fun deleteLinks() {
            links.clear()
            linkChanges.value++
        }
    }

    private companion object {
        const val BOOK = "file:///books/hobbit.epub"
    }
}
