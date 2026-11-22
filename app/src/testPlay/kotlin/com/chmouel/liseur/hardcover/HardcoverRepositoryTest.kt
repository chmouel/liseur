package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.ReadingStatus
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A push from end to end against a Hardcover that keeps its own state. */
class HardcoverRepositoryTest {

    private lateinit var server: MockWebServer
    private val hardcover = FakeHardcover()
    private val dao = FakeDao()
    private val books = mutableMapOf<String, Book>()
    private val local = mutableMapOf<String, LocalReading>()
    private val pulledEvaluations = mutableListOf<Triple<String, String, String?>>()
    private var casAccept = true

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = hardcover
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun repository() = HardcoverRepository(
        dao = dao,
        bookFor = { books[it] },
        localFor = { local[it] },
        writeLocalEvaluation = { url, field, expected, value, _ ->
            if (!casAccept) return@HardcoverRepository false
            val current = when (field) {
                PersonalEvaluation.RATING -> local[url]?.ratingRaw
                PersonalEvaluation.REVIEW -> local[url]?.review
                else -> null
            }
            if (current != expected) return@HardcoverRepository false
            pulledEvaluations += Triple(url, field, value)
            local[url]?.let { existing ->
                local[url] = when (field) {
                    PersonalEvaluation.RATING -> existing.copy(
                        rating = HardcoverPlan.ratingFromLocal(value),
                        ratingRaw = value,
                    )
                    PersonalEvaluation.REVIEW -> existing.copy(review = value)
                    else -> existing
                }
            }
            true
        },
        client = HardcoverClient(endpoint = "http://127.0.0.1:${server.port}/v1/graphql", spacingMillis = 0),
        now = { 1_000L },
        today = { TODAY },
        seal = { "sealed:$it" },
        open = { it.tokenCipher.removePrefix("sealed:") },
    )

    private fun signedIn(userId: Long = 42, autoSync: Boolean = true, refused: Boolean = false) {
        dao.account.value = HardcoverAccount(
            userId = userId, username = "reader", tokenCipher = "sealed:tok",
            addedAt = 1, autoSync = autoSync, tokenRefused = refused,
        )
    }

    private fun shelve(identifier: String? = "urn:isbn:9780547928227", title: String = "The Hobbit") {
        books[BOOK] = Book(
            url = BOOK, title = title, author = "J.R.R. Tolkien", coverPath = null,
            source = null, addedAt = 1, lastOpenedAt = null, workId = identifier,
        )
    }

    private fun reading(
        status: ReadingStatus,
        progression: Double? = null,
        chosen: Boolean = false,
        startedOn: String? = null,
        finishedOn: String? = null,
        rating: Double? = null,
        review: String? = null,
    ) {
        local[BOOK] = LocalReading(
            status = status,
            chosen = chosen,
            progression = progression,
            startedOn = startedOn,
            finishedOn = finishedOn,
            rating = rating,
            ratingRaw = rating?.let(HardcoverEvaluationPlan::ratingToLocal),
            review = review,
        )
    }

    @Test
    fun `a token hardcover accepts is kept sealed, and a refused one stores nothing`() = runBlocking {
        assertEquals(HardcoverConnectOutcome.Connected("reader"), repository().connect("Bearer tok"))
        assertEquals("sealed:tok", dao.account.value?.tokenCipher)

        dao.account.value = null
        hardcover.refuse = true
        assertEquals(
            HardcoverConnectOutcome.Failed(SyncFailure.Unauthorised),
            repository().connect("bad"),
        )
        assertNull(dao.account.value)
    }

    @Test
    fun `a first push matches by isbn, adds the book and logs the page`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5, startedOn = "2026-03-01")

        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))

        val entry = checkNotNull(hardcover.entry)
        assertEquals(HardcoverStatus.CURRENTLY_READING, entry.status)
        assertEquals(150, entry.read?.pages)
        assertEquals("2026-03-01", entry.read?.startedAt)
        assertTrue(checkNotNull(dao.links[BOOK]).matched)
        assertEquals(1_000L, dao.links[BOOK]?.pushedAt)
    }

    @Test
    fun `a second push writes nothing and looks nothing up`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        repository().push(BOOK, asked = false)
        hardcover.queries.clear()

        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = false))
        assertEquals(listOf("user_books"), hardcover.queries)
    }

    @Test
    fun `finishing sets the book read with the real dates and rating`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5, startedOn = "2026-03-01")
        repository().push(BOOK, asked = false)
        reading(
            ReadingStatus.FINISHED, progression = 1.0, startedOn = "2026-03-01",
            finishedOn = "2026-04-18", rating = 4.5,
        )

        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))

        val entry = checkNotNull(hardcover.entry)
        assertEquals(HardcoverStatus.READ, entry.status)
        assertEquals(4.5, entry.rating)
        assertEquals("2026-04-18", entry.read?.finishedAt)
        assertEquals("2026-03-01", entry.read?.startedAt)
        assertEquals(4.5, dao.links[BOOK]?.pushedRating)
    }

    @Test
    fun `a book already read on hardcover is not reopened`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.FINISHED, finishedOn = "2026-04-18")
        repository().push(BOOK, asked = false)
        reading(ReadingStatus.READING, progression = 0.1)

        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = false))
        assertEquals(HardcoverStatus.READ, hardcover.entry?.status)
    }

    @Test
    fun `an unread book with no match sends nothing at all`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READY_TO_READ)

        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = false))
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `a hit with another subtitle waits for the reader to choose`() = runBlocking {
        signedIn()
        shelve(identifier = null, title = "The Hobbit: Illustrated")
        hardcover.hits = listOf(Triple(9L, "The Hobbit: An Unexpected Journey", "J.R.R. Tolkien"))
        reading(ReadingStatus.READING, progression = 0.5)

        assertEquals(HardcoverPushOutcome.NeedsMatch, repository().push(BOOK, asked = false))
        assertNull(hardcover.entry)
    }

    @Test
    fun `a book with no isbn is matched by title and author`() = runBlocking {
        signedIn()
        shelve(identifier = null)
        hardcover.hits = listOf(Triple(9L, "The Hobbit", "Tolkien, J. R. R."))
        reading(ReadingStatus.READING, progression = 0.5)

        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))
        assertEquals(5L, dao.links[BOOK]?.editionId)
    }

    @Test
    fun `a book kept off hardcover is never sent, even when asked`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        repository().setExcluded(BOOK, true)

        assertEquals(HardcoverPushOutcome.SwitchedOff, repository().push(BOOK, asked = true))
        assertFalse(repository().queue(BOOK))
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `with automatic sync off only an asked-for push goes out`() = runBlocking {
        signedIn(autoSync = false)
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)

        assertFalse(repository().queue(BOOK))
        assertEquals(HardcoverPushOutcome.SwitchedOff, repository().push(BOOK, asked = false))
        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = true))
    }

    @Test
    fun `a refused token stops automatic pushes until one goes through`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        hardcover.refuse = true

        assertEquals(
            HardcoverPushOutcome.Failed(SyncFailure.Unauthorised),
            repository().push(BOOK, asked = false),
        )
        assertTrue(dao.account.value!!.tokenRefused)
        hardcover.queries.clear()
        assertEquals(
            HardcoverPushOutcome.Failed(SyncFailure.Unauthorised),
            repository().push(BOOK, asked = false),
        )
        assertFalse(repository().queue(BOOK))
        assertTrue(hardcover.queries.isEmpty())

        hardcover.refuse = false
        // A push that never reaches Hardcover proves nothing about the token.
        val repository = repository()
        repository.willForget(listOf(BOOK))
        assertEquals(HardcoverPushOutcome.UpToDate, repository.push(BOOK, asked = true))
        assertTrue(dao.account.value!!.tokenRefused)
        repository.keep(listOf(BOOK))

        assertEquals(HardcoverPushOutcome.Pushed, repository.push(BOOK, asked = true))
        assertFalse(dao.account.value!!.tokenRefused)
    }

    @Test
    fun `two pushes at once add the book once`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        val repository = repository()

        (1..3).map { async(Dispatchers.Default) { repository.push(BOOK, asked = true) } }.awaitAll()

        assertEquals(1, hardcover.inserts)
    }

    @Test
    fun `disconnecting keeps the matches for the same account, another account drops them`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        repository().push(BOOK, asked = false)
        repository().setExcluded("other", true)

        repository().disconnect()
        assertNull(dao.account.value)
        repository().connect("tok")
        assertNotNull(dao.links[BOOK])
        assertTrue(dao.links["other"]!!.excluded)

        repository().disconnect()
        hardcover.userId = 7
        repository().connect("tok2")
        assertTrue(dao.links.isEmpty())
    }

    @Test
    fun `a rating on an unread book already on the hardcover shelf goes out`() = runBlocking {
        signedIn()
        shelve()
        hardcover.entry = FakeHardcover.Entry(7, HardcoverStatus.WANT_TO_READ, null, null)
        reading(ReadingStatus.READY_TO_READ, rating = 4.0)

        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))
        assertEquals(4.0, hardcover.entry?.rating)
        assertEquals(HardcoverStatus.WANT_TO_READ, hardcover.entry?.status)
    }

    @Test
    fun `a finish cut short still gets its real dates and keeps the rating it sent`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5, startedOn = "2026-03-01")
        repository().push(BOOK, asked = false)
        reading(
            ReadingStatus.FINISHED, progression = 1.0, startedOn = "2026-03-01",
            finishedOn = "2026-04-18", rating = 4.5,
        )
        hardcover.failReads = true

        assertTrue(repository().push(BOOK, asked = false) is HardcoverPushOutcome.Failed)
        assertEquals(HardcoverStatus.READ, hardcover.entry?.status)
        assertEquals(TODAY, hardcover.entry?.read?.finishedAt)
        assertEquals(4.5, dao.links[BOOK]?.pushedRating)

        hardcover.failReads = false
        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))
        assertEquals("2026-04-18", hardcover.entry?.read?.finishedAt)
        assertEquals("2026-03-01", hardcover.entry?.read?.startedAt)
        assertFalse(dao.links[BOOK]!!.datesPending)
    }

    @Test
    fun `an edition chosen here moves the entry and its read, one found alone keeps hardcover's`() = runBlocking {
        signedIn()
        shelve()
        hardcover.entry = FakeHardcover.Entry(
            7, HardcoverStatus.CURRENTLY_READING, null,
            FakeHardcover.Read(3, 100, "2026-03-01", null, editionId = 8), editionId = 8,
        )
        reading(ReadingStatus.READING, progression = 0.5, startedOn = "2026-03-01")

        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))
        assertEquals(8L, hardcover.entry?.editionId)
        assertEquals(300, hardcover.entry?.read?.pages)

        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val chosen = HardcoverEdition(
            id = 5, bookId = 9, title = "The Hobbit", bookTitle = null, format = "ebook", pages = 300,
            isbn13 = null, isbn10 = null, releaseYear = null, publisher = null,
        )
        assertEquals(
            EditionChoice.Chosen,
            repository().chooseThenPush(candidates, BOOK, chosen, push = false, asked = false),
        )
        assertEquals(HardcoverPushOutcome.Pushed, repository().push(BOOK, asked = false))
        assertEquals(5L, hardcover.entry?.editionId)
        assertEquals(5L, hardcover.entry?.read?.editionId)
        assertEquals(150, hardcover.entry?.read?.pages)
        assertFalse(dao.links[BOOK]!!.editionChosen)
    }

    @Test
    fun `another edition of the same book keeps the rating sent and the dates owed`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(
            bookUrl = BOOK, hardcoverBookId = 9, editionId = 8, pushedRating = 4.5, datesPending = true,
        )
        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = HardcoverEdition(
            id = 5, bookId = 9, title = "The Hobbit", bookTitle = null, format = "ebook", pages = 300,
            isbn13 = null, isbn10 = null, releaseYear = null, publisher = null,
        )
        assertEquals(
            EditionChoice.Chosen,
            repository().chooseThenPush(candidates, BOOK, edition, push = false, asked = false),
        )
        assertEquals(4.5, dao.links[BOOK]?.pushedRating)
        assertTrue(dao.links[BOOK]!!.datesPending)

        assertEquals(
            EditionChoice.Chosen,
            repository().chooseThenPush(candidates, BOOK, edition.copy(id = 6, bookId = 10), push = false, asked = false),
        )
        assertNull(dao.links[BOOK]?.pushedRating)
        assertFalse(dao.links[BOOK]!!.datesPending)
    }

    @Test
    fun `a book being forgotten is not pushed until its removal settles`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        val repository = repository()
        repository.willForget(listOf(BOOK))

        assertEquals(HardcoverPushOutcome.UpToDate, repository.push(BOOK, asked = false))
        assertTrue(hardcover.queries.isEmpty())

        repository.keep(listOf(BOOK))
        assertEquals(HardcoverPushOutcome.Pushed, repository.push(BOOK, asked = false))
        assertNotNull(dao.links[BOOK])

        repository.willForget(listOf(BOOK))
        repository.forgetBooks(listOf(BOOK))
        assertNull(dao.links[BOOK])
        assertTrue(dao.forgetting.isEmpty())
    }

    @Test
    fun `a removal that starts while the push reads the book sends nothing`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        val repository = HardcoverRepository(
            dao = dao,
            bookFor = { books[it] },
            localFor = {
                dao.forgetting += it
                local[it]
            },
            client = HardcoverClient(endpoint = "http://127.0.0.1:${server.port}/v1/graphql", spacingMillis = 0),
            now = { 1_000L },
            seal = { "sealed:$it" },
            open = { it.tokenCipher.removePrefix("sealed:") },
        )

        assertEquals(HardcoverPushOutcome.UpToDate, repository.push(BOOK, asked = true))
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `a removal a stopped process left unsettled drops the match`() = runBlocking {
        signedIn()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        repository().willForget(listOf(BOOK))

        val restarted = repository()
        assertEquals(HardcoverPushOutcome.UpToDate, restarted.push(BOOK, asked = true))
        restarted.settleForgetting()
        assertNull(dao.links[BOOK])
        assertTrue(dao.forgetting.isEmpty())
    }

    @Test
    fun `with no sure hit the picker offers editions of several hits, in search order`() = runBlocking {
        signedIn()
        shelve(identifier = null, title = "Bilbo")
        hardcover.hits = listOf(Triple(9L, "The Hobbit", "J.R.R. Tolkien"), Triple(11L, "Bilbo's Last Song", "J.R.R. Tolkien"))

        val offered = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        assertEquals(listOf(9L, 11L), offered.editions.map { it.bookId })
    }

    @Test
    fun `choosing with push and asked reports even when automatic sync is off`() = runBlocking {
        signedIn(autoSync = false)
        shelve(identifier = null, title = "Bilbo")
        hardcover.hits = listOf(Triple(9L, "The Hobbit", "J.R.R. Tolkien"))
        reading(ReadingStatus.READING, progression = 0.5, startedOn = "2026-03-01")

        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()
        assertEquals(
            EditionChoice.Pushed(HardcoverPushOutcome.Pushed),
            repository().chooseThenPush(candidates, BOOK, edition, push = true, asked = true),
        )
        assertEquals(HardcoverStatus.CURRENTLY_READING, hardcover.entry?.status)
        assertEquals(edition.id, dao.links[BOOK]?.editionId)
    }

    @Test
    fun `choosing without push writes the link and queues nothing when automatic sync is off`() = runBlocking {
        signedIn(autoSync = false)
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)

        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()
        hardcover.queries.clear()
        assertEquals(
            EditionChoice.Chosen,
            repository().chooseThenPush(candidates, BOOK, edition, push = false, asked = false),
        )
        assertEquals(edition.id, dao.links[BOOK]?.editionId)
        assertTrue(dao.pending.isEmpty())
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `choosing without push on an excluded book updates the link and keeps it excluded`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        repository().setExcluded(BOOK, true)

        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()
        hardcover.queries.clear()
        assertEquals(
            EditionChoice.Chosen,
            repository().chooseThenPush(candidates, BOOK, edition, push = false, asked = false),
        )
        assertEquals(edition.id, dao.links[BOOK]?.editionId)
        assertTrue(dao.links[BOOK]!!.excluded)
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `choosing with push on an excluded book returns switched off without calling hardcover`() = runBlocking {
        signedIn()
        shelve()
        reading(ReadingStatus.READING, progression = 0.5)
        repository().setExcluded(BOOK, true)

        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()
        hardcover.queries.clear()
        assertEquals(
            EditionChoice.SwitchedOff,
            repository().chooseThenPush(candidates, BOOK, edition, push = true, asked = true),
        )
        assertEquals(edition.id, dao.links[BOOK]?.editionId)
        assertTrue(dao.links[BOOK]!!.excluded)
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `choosing after the account changed writes no link`() = runBlocking {
        signedIn(userId = 42)
        shelve()
        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()

        signedIn(userId = 7)
        assertEquals(
            EditionChoice.OtherAccount,
            repository().chooseThenPush(candidates, BOOK, edition, push = true, asked = true),
        )
        assertNull(dao.links[BOOK])
    }

    @Test
    fun `choosing after the book is removed writes no link`() = runBlocking {
        signedIn()
        shelve()
        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()

        repository().willForget(listOf(BOOK))
        assertEquals(
            EditionChoice.AlreadyGone,
            repository().chooseThenPush(candidates, BOOK, edition, push = true, asked = true),
        )
        assertNull(dao.links[BOOK])

        repository().forgetBooks(listOf(BOOK))
        books.remove(BOOK)
        assertEquals(
            EditionChoice.AlreadyGone,
            repository().chooseThenPush(candidates, BOOK, edition, push = false, asked = false),
        )
        assertNull(dao.links[BOOK])
    }

    @Test
    fun `choosing after a replacement file settled at the same url writes no link`() = runBlocking {
        signedIn()
        shelve()
        val candidates = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        val edition = candidates.editions.single()

        repository().willForget(listOf(BOOK))
        repository().forgetBooks(listOf(BOOK))
        books[BOOK] = checkNotNull(books[BOOK]).copy(workId = "urn:isbn:9780261102217", fileModifiedAt = 99L)

        assertEquals(
            EditionChoice.AlreadyGone,
            repository().chooseThenPush(candidates, BOOK, edition, push = true, asked = true),
        )
        assertNull(dao.links[BOOK])
    }

    private fun lookedUp(input: String): List<HardcoverEdition> =
        (runBlocking { repository().lookup(input) } as com.chmouel.liseur.data.remote.RemoteResult.Ok).value

    @Test
    fun `typed text is searched and the top books editions are listed`() {
        signedIn()
        hardcover.hits = listOf(Triple(9L, "The Hobbit", "J.R.R. Tolkien"))

        val editions = lookedUp("  Qumran ")

        assertEquals(listOf(90L), editions.map { it.id })
        assertEquals(listOf("search", "editions_of_books"), hardcover.queries)
    }

    @Test
    fun `an isbn, hyphenated or ending in X, asks for editions by isbn only`() {
        signedIn()

        assertEquals(listOf(5L), lookedUp("978-0-547-92822-7").map { it.id })
        assertEquals(listOf("isbn"), hardcover.queries)
        hardcover.queries.clear()
        assertTrue(lookedUp("ISBN 080442957X").isEmpty())
        assertEquals(listOf("isbn"), hardcover.queries)
    }

    @Test
    fun `a book id and a book link slug find the book, then its editions`() {
        signedIn()

        assertEquals(listOf(90L), lookedUp("9").map { it.id })
        assertEquals(listOf("book_by_id", "editions_of_books"), hardcover.queries)
        hardcover.queries.clear()

        assertEquals(listOf(90L), lookedUp("https://hardcover.app/books/the-hobbit/editions/5?x=1").map { it.id })
        assertEquals(listOf("book_by_slug", "editions_of_books"), hardcover.queries)
        assertEquals(listOf("9", "the-hobbit"), hardcover.sentRefs)
    }

    @Test
    fun `a one digit book id is looked up, a one character text is not`() {
        signedIn()

        lookedUp("7")
        assertEquals(listOf("book_by_id"), hardcover.queries)
        hardcover.queries.clear()

        assertTrue(lookedUp("a").isEmpty())
        assertTrue(hardcover.queries.isEmpty())
    }

    @Test
    fun `blank input, another sites link and an overflowing id make no request`() {
        signedIn()

        assertTrue(lookedUp("   ").isEmpty())
        assertTrue(lookedUp("https://example.com/books/x?q=secret").isEmpty())
        assertTrue(lookedUp("example.com/hardcover.app/books/x").isEmpty())
        assertTrue(lookedUp("99999999999").isEmpty())
        assertTrue(lookedUp("0").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a lookup with a refused token reports it`() = runBlocking {
        signedIn()
        hardcover.refuse = true

        assertEquals(
            com.chmouel.liseur.data.remote.RemoteResult.Failed(SyncFailure.Unauthorised),
            repository().lookup("Qumran"),
        )
    }

    @Test
    fun `a lookup with no account makes no request`() = runBlocking {
        assertEquals(
            com.chmouel.liseur.data.remote.RemoteResult.Failed(SyncFailure.Unauthorised),
            repository().lookup("Qumran"),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `candidates record the isbn or the title and author that found them`() = runBlocking {
        signedIn()
        shelve()
        val byIsbn = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        assertEquals("9780547928227", byIsbn.query)

        shelve(identifier = "uuid:1234")
        hardcover.hits = listOf(Triple(9L, "The Hobbit", "J.R.R. Tolkien"))
        val bySearch = (repository().candidates(BOOK) as com.chmouel.liseur.data.remote.RemoteResult.Ok).value
        assertEquals("The Hobbit J.R.R. Tolkien", bySearch.query)
    }

    @Test
    fun `a lookup cancelled while waiting its turn sends nothing and returns no result`() = runBlocking {
        signedIn()
        hardcover.hits = listOf(Triple(9L, "The Hobbit", "J.R.R. Tolkien"))
        val spaced = HardcoverRepository(
            dao = dao,
            bookFor = { books[it] },
            localFor = { local[it] },
            client = HardcoverClient(
                endpoint = "http://127.0.0.1:${server.port}/v1/graphql",
                spacingMillis = 60_000,
            ),
            open = { it.tokenCipher.removePrefix("sealed:") },
        )
        var result: Any? = null
        val job = launch(Dispatchers.IO) { result = spaced.lookup("Qumran") }
        while (server.requestCount < 1) delay(10)
        delay(200)

        job.cancelAndJoin()

        assertEquals(1, server.requestCount)
        assertNull(result)
    }

    @Test
    fun `a lookup cancelled during a slow answer stops the call and returns no result`() = runBlocking {
        signedIn()
        hardcover.delayMillis = 3_000
        var result: Any? = null
        val job = launch(Dispatchers.IO) { result = repository().lookup("Qumran") }
        while (server.requestCount < 1) delay(10)

        job.cancelAndJoin()
        delay(300)

        assertEquals(1, server.requestCount)
        assertNull(result)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `edition details ask for the matched edition and answer null when unmatched`() = runBlocking {
        signedIn()
        val repository = repository()

        assertEquals(RemoteResult.Ok(null), repository.editionDetails(BOOK))
        assertTrue(hardcover.queries.isEmpty())

        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        val result = repository.editionDetails(BOOK)

        assertEquals(
            RemoteResult.Ok(
                HardcoverEditionDetails(
                    editionId = 5,
                    title = "The Hobbit",
                    author = "J.R.R. Tolkien",
                    description = "In a hole in the ground there lived a hobbit.",
                    coverUrl = "https://assets.hardcover.app/books/9/cover.jpg",
                    seriesName = "Middle-earth",
                    seriesPosition = 1.0,
                ),
            ),
            result,
        )
        assertEquals(listOf("edition_details"), hardcover.queries)
    }

    @Test
    fun `a push pulls a remote rating and review when local has none and sets the base`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        hardcover.entry = FakeHardcover.Entry(
            id = 7,
            status = HardcoverStatus.CURRENTLY_READING,
            rating = 4.5,
            read = null,
            editionId = 5,
            review = "Wonderful read",
        )
        reading(ReadingStatus.READING)

        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = false))
        assertEquals(
            listOf(
                Triple(BOOK, PersonalEvaluation.RATING, "9"),
                Triple(BOOK, PersonalEvaluation.REVIEW, "Wonderful read"),
            ),
            pulledEvaluations,
        )
        assertEquals(
            HardcoverEvaluationBase("9", "4.5"),
            dao.evaluations[BOOK to PersonalEvaluation.RATING]?.base,
        )
        assertEquals(
            HardcoverEvaluationBase("Wonderful read", "Wonderful read"),
            dao.evaluations[BOOK to PersonalEvaluation.REVIEW]?.base,
        )
    }

    @Test
    fun `a cas failure when pulling leaves the base untouched so the next run decides`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        hardcover.entry = FakeHardcover.Entry(
            id = 7,
            status = HardcoverStatus.CURRENTLY_READING,
            rating = 4.5,
            read = null,
            editionId = 5,
        )
        reading(ReadingStatus.READING)
        casAccept = false

        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = false))
        assertTrue(pulledEvaluations.isEmpty())
        assertNull(dao.evaluations[BOOK to PersonalEvaluation.RATING]?.base)
    }

    @Test
    fun `a conflict writes nothing for that field, stores the conflict and can be resolved either way`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        hardcover.entry = FakeHardcover.Entry(
            id = 7,
            status = HardcoverStatus.CURRENTLY_READING,
            rating = 3.0,
            read = null,
            editionId = 5,
            review = "Hardcover review",
        )
        reading(ReadingStatus.READING, rating = 4.5, review = "Liseur review")

        assertEquals(HardcoverPushOutcome.UpToDate, repository().push(BOOK, asked = false))
        assertEquals(3.0, hardcover.entry?.rating)
        assertEquals("Hardcover review", hardcover.entry?.review)
        assertTrue(pulledEvaluations.isEmpty())

        val ratingConflict = checkNotNull(dao.evaluations[BOOK to PersonalEvaluation.RATING])
        assertTrue(ratingConflict.conflict)
        assertEquals("9", ratingConflict.conflictLocal)
        assertEquals("3.0", ratingConflict.conflictRemote)

        val reviewConflict = checkNotNull(dao.evaluations[BOOK to PersonalEvaluation.REVIEW])
        assertTrue(reviewConflict.conflict)
        assertEquals("Liseur review", reviewConflict.conflictLocal)
        assertEquals("Hardcover review", reviewConflict.conflictRemote)

        // Resolve rating by keeping Liseur's value.
        assertEquals(
            HardcoverConflictResolution.Resolved,
            repository().resolveConflictKeepLocal(BOOK, PersonalEvaluation.RATING),
        )
        assertEquals(4.5, hardcover.entry?.rating)
        val ratingResolved = checkNotNull(dao.evaluations[BOOK to PersonalEvaluation.RATING])
        assertFalse(ratingResolved.conflict)
        assertEquals(HardcoverEvaluationBase("9", "4.5"), ratingResolved.base)

        // Resolve review by using Hardcover's value.
        assertEquals(
            HardcoverConflictResolution.Resolved,
            repository().resolveConflictUseRemote(BOOK, PersonalEvaluation.REVIEW),
        )
        assertEquals(listOf(Triple(BOOK, PersonalEvaluation.REVIEW, "Hardcover review")), pulledEvaluations)
        val reviewResolved = checkNotNull(dao.evaluations[BOOK to PersonalEvaluation.REVIEW])
        assertFalse(reviewResolved.conflict)
        assertEquals(HardcoverEvaluationBase("Hardcover review", "Hardcover review"), reviewResolved.base)
    }

    @Test
    fun `a failed read-back after a write still records what this device sent`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        hardcover.entry = FakeHardcover.Entry(7, HardcoverStatus.CURRENTLY_READING, 3.0, null, editionId = 5)
        dao.upsertEvaluation(
            HardcoverEvaluation(BOOK, PersonalEvaluation.RATING, "6", "3.0", hasBase = true),
        )
        reading(ReadingStatus.READING, rating = 4.0)
        hardcover.failShelfAfterWrite = true

        assertTrue(repository().push(BOOK, asked = false) is HardcoverPushOutcome.Failed)
        assertEquals(4.0, hardcover.entry?.rating)
        assertEquals(HardcoverEvaluationBase("8", "4.0"), dao.evaluations[BOOK to PersonalEvaluation.RATING]?.base)

        // A later local edit is a plain local change, not a conflict with this device's own write.
        hardcover.failShelfAfterWrite = false
        reading(ReadingStatus.READING, rating = 2.5)
        repository().push(BOOK, asked = false)
        assertEquals(2.5, hardcover.entry?.rating)
        assertFalse(dao.evaluations[BOOK to PersonalEvaluation.RATING]!!.conflict)
    }

    @Test
    fun `re-adding a book without the conflicted field keeps the conflict`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        dao.upsertEvaluation(
            HardcoverEvaluation(
                BOOK, PersonalEvaluation.REVIEW, "Old", "Old", hasBase = true,
                conflict = true, conflictLocal = "Mine", conflictRemote = "Theirs",
            ),
        )
        hardcover.entry = null
        reading(ReadingStatus.READING, review = "Mine")

        repository().push(BOOK, asked = false)

        assertNull(hardcover.entry?.review)
        assertTrue(dao.evaluations[BOOK to PersonalEvaluation.REVIEW]!!.conflict)
    }

    @Test
    fun `using hardcover's value after liseur's moved reports it and shows the new value`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        hardcover.entry = FakeHardcover.Entry(7, HardcoverStatus.CURRENTLY_READING, 3.0, null, editionId = 5)
        dao.upsertEvaluation(
            HardcoverEvaluation(
                BOOK, PersonalEvaluation.RATING, "4", "2.0", hasBase = true,
                conflict = true, conflictLocal = "8", conflictRemote = "3.0",
            ),
        )
        reading(ReadingStatus.READING, rating = 4.5)
        casAccept = false

        assertEquals(
            HardcoverConflictResolution.Stale,
            repository().resolveConflictUseRemote(BOOK, PersonalEvaluation.RATING),
        )
        val row = dao.evaluations[BOOK to PersonalEvaluation.RATING]!!
        assertTrue(row.conflict)
        assertEquals("9", row.conflictLocal)
    }

    @Test
    fun `opening book details checks a book at most once in ten minutes`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        hardcover.entry = FakeHardcover.Entry(7, HardcoverStatus.CURRENTLY_READING, null, null, editionId = 5)
        reading(ReadingStatus.READING)
        val repository = repository()

        assertNotNull(repository.checkFromDetails(BOOK))
        val asked = hardcover.queries.size
        assertNull(repository.checkFromDetails(BOOK))
        assertEquals(asked, hardcover.queries.size)
    }

    @Test
    fun `forgetting books or moving to another hardcover book deletes evaluation rows`() = runBlocking {
        signedIn()
        shelve()
        dao.links[BOOK] = HardcoverBookLink(bookUrl = BOOK, hardcoverBookId = 9, editionId = 5)
        dao.upsertEvaluation(
            HardcoverEvaluation(
                bookUrl = BOOK,
                field = PersonalEvaluation.RATING,
                baseLocal = "8",
                baseRemote = "4.0",
                hasBase = true,
            ),
        )

        val candidates = (repository().candidates(BOOK) as RemoteResult.Ok).value
        val otherBookEdition = HardcoverEdition(
            id = 6, bookId = 10, title = "Other", bookTitle = null, format = "ebook", pages = 300,
            isbn13 = null, isbn10 = null, releaseYear = null, publisher = null,
        )
        assertEquals(
            EditionChoice.Chosen,
            repository().chooseThenPush(candidates, BOOK, otherBookEdition, push = false, asked = false),
        )
        assertNull(dao.evaluations[BOOK to PersonalEvaluation.RATING])

        dao.upsertEvaluation(
            HardcoverEvaluation(
                bookUrl = BOOK,
                field = PersonalEvaluation.REVIEW,
                baseLocal = "Text",
                baseRemote = "Text",
                hasBase = true,
            ),
        )
        repository().forgetBooks(listOf(BOOK))
        assertTrue(dao.evaluations.isEmpty())
    }

    @Test
    fun `unlinking drops the book link and queued push without contacting hardcover`() = runBlocking {
        signedIn()
        val repository = repository()
        dao.links[BOOK] = HardcoverBookLink(
            bookUrl = BOOK,
            hardcoverBookId = 9,
            editionId = 5,
            editionTitle = "The Hobbit",
        )
        assertTrue(repository.queue(BOOK))
        assertNotNull(dao.links[BOOK])
        assertNotNull(repository.nextPending())
        dao.upsertEvaluation(HardcoverEvaluation(BOOK, PersonalEvaluation.RATING, "8", "4.0", hasBase = true))

        repository.unlink(BOOK)
        assertTrue(dao.evaluations.isEmpty())

        assertNull(dao.links[BOOK])
        assertNull(repository.nextPending())
        assertFalse(repository.remembers(BOOK))
        assertTrue(hardcover.queries.isEmpty())
        assertEquals(0, server.requestCount)
    }

    /** Hardcover with one reader's library of at most one book, the way the server answers. */
    private class FakeHardcover : Dispatcher() {
        data class Read(
            val id: Long,
            var pages: Int?,
            var startedAt: String?,
            var finishedAt: String?,
            var editionId: Long? = null,
        )
        data class Entry(
            val id: Long,
            var status: Int,
            var rating: Double?,
            var read: Read?,
            var editionId: Long? = null,
            var review: String? = null,
        )

        @Volatile var refuse = false
        @Volatile var delayMillis = 0L
        @Volatile var failReads = false
        @Volatile var failShelfAfterWrite = false
        @Volatile private var wroteEntry = false
        @Volatile var userId = 42L
        @Volatile var hits: List<Triple<Long, String, String>> = emptyList()
        @Volatile var entry: Entry? = null
        @Volatile var inserts = 0
        val queries: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val sentRefs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private var nextRead = 3L

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (refuse) return MockResponse(code = 401, body = "{}")
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val body = JSONObject(request.body!!.utf8())
            val query = body.getString("query")
            val variables = body.optJSONObject("variables") ?: JSONObject()
            val data = JSONObject()
            when {
                "me {" in query -> {
                    queries += "me"
                    data.put("me", JSONArray().put(JSONObject().put("id", userId).put("username", "reader")))
                }
                "isbn_13: {_eq" in query -> {
                    queries += "isbn"
                    val known = variables.getString("isbn13") == "9780547928227"
                    data.put("editions", JSONArray().apply { if (known) put(edition()) })
                }
                "book_id: {_in" in query -> {
                    queries += "editions_of_books"
                    val ids = variables.getJSONArray("bookIds")
                    val rows = JSONArray()
                    // Most read first across both books, the way Hardcover orders them.
                    (ids.length() - 1 downTo 0).forEach { i ->
                        val bookId = ids.getLong(i)
                        rows.put(edition().put("id", bookId * 10).put("book_id", bookId))
                    }
                    data.put("editions", rows)
                }
                "search(" in query -> {
                    queries += "search"
                    val documents = JSONArray()
                    hits.forEach { (id, title, author) ->
                        documents.put(
                            JSONObject().put(
                                "document",
                                JSONObject().put("id", id.toString()).put("title", title)
                                    .put("author_names", JSONArray().put(author)),
                            ),
                        )
                    }
                    data.put("search", JSONObject().put("results", JSONObject().put("hits", documents)))
                }
                "slug: {_eq" in query -> {
                    queries += "book_by_slug"
                    sentRefs += variables.getString("slug")
                    val known = variables.getString("slug") == "the-hobbit"
                    data.put("books", JSONArray().apply { if (known) put(JSONObject().put("id", 9)) })
                }
                "\$id: Int!) { books(" in query -> {
                    queries += "book_by_id"
                    sentRefs += variables.getInt("id").toString()
                    val known = variables.getInt("id") == 9
                    data.put("books", JSONArray().apply { if (known) put(JSONObject().put("id", 9)) })
                }
                "default_ebook_edition" in query -> {
                    queries += "defaults"
                    data.put(
                        "books",
                        JSONArray().put(
                            JSONObject().put("default_ebook_edition", edition())
                                .put("default_physical_edition", JSONObject.NULL)
                                .put("editions", JSONArray()),
                        ),
                    )
                }
                "featured_book_series" in query -> {
                    queries += "edition_details"
                    val id = variables.getLong("id")
                    val rows = JSONArray()
                    if (id == 5L) {
                        rows.put(
                            JSONObject()
                                .put("id", 5)
                                .put("title", "The Hobbit")
                                .put("image", JSONObject().put("url", "https://assets.hardcover.app/books/9/cover.jpg"))
                                .put(
                                    "book",
                                    JSONObject()
                                        .put("title", "The Hobbit")
                                        .put("description", "In a hole in the ground there lived a hobbit.")
                                        .put(
                                            "cached_contributors",
                                            JSONArray().put(
                                                JSONObject().put("author", JSONObject().put("name", "J.R.R. Tolkien"))
                                                    .put("contribution", "Author"),
                                            ),
                                        )
                                        .put(
                                            "featured_book_series",
                                            JSONObject().put("position", 1.0)
                                                .put("series", JSONObject().put("name", "Middle-earth")),
                                        ),
                                ),
                        )
                    }
                    data.put("editions", rows)
                }
                "insert_user_book" in query -> {
                    queries += "insert"
                    inserts++
                    val o = variables.getJSONObject("object")
                    val rev = if (o.has("review_markdown") && !o.isNull("review_markdown")) o.getString("review_markdown") else null
                    entry = Entry(
                        7,
                        o.getInt("status_id"),
                        o.optDouble("rating").takeUnless { it.isNaN() },
                        null,
                        editionId = o.getLong("edition_id"),
                        review = rev,
                    ).also { applyStatus(it) }
                    data.put("insert_user_book", JSONObject().put("error", JSONObject.NULL).put("user_book", JSONObject().put("id", 7)))
                }
                "update_user_book_read" in query -> {
                    queries += "update_read"
                    if (failReads) return MockResponse(code = 500, body = "{}")
                    val o = variables.getJSONObject("object")
                    entry?.read?.let { read ->
                        if (o.has("progress_pages")) read.pages = o.getInt("progress_pages")
                        if (o.has("started_at")) read.startedAt = o.getString("started_at")
                        if (o.has("finished_at")) read.finishedAt = o.getString("finished_at")
                        if (o.has("edition_id")) read.editionId = o.getLong("edition_id")
                    }
                    data.put("update_user_book_read", JSONObject().put("error", JSONObject.NULL))
                }
                "update_user_book(" in query -> {
                    queries += "update"
                    wroteEntry = true
                    val o = variables.getJSONObject("object")
                    val current = checkNotNull(entry)
                    if (o.has("rating")) current.rating = if (o.isNull("rating")) null else o.getDouble("rating")
                    if (o.has("review_markdown")) {
                        current.review = if (o.isNull("review_markdown")) null else o.getString("review_markdown")
                    }
                    if (o.has("edition_id")) current.editionId = o.getLong("edition_id")
                    if (o.has("status_id")) {
                        current.status = o.getInt("status_id")
                        applyStatus(current)
                    }
                    data.put("update_user_book", JSONObject().put("error", JSONObject.NULL))
                }
                "user_books(" in query -> {
                    queries += "user_books"
                    if (failShelfAfterWrite && wroteEntry) return MockResponse(code = 500, body = "{}")
                    val rows = JSONArray()
                    entry?.let { e ->
                        val reads = JSONArray()
                        e.read?.let {
                            reads.put(
                                JSONObject().put("id", it.id).put("progress_pages", it.pages ?: JSONObject.NULL)
                                    .put("started_at", it.startedAt ?: JSONObject.NULL)
                                    .put("finished_at", it.finishedAt ?: JSONObject.NULL)
                                    .put("edition_id", it.editionId ?: JSONObject.NULL)
                                    .put(
                                        "edition",
                                        it.editionId?.let { id -> JSONObject().put("pages", PAGES.getValue(id)) }
                                            ?: JSONObject.NULL,
                                    ),
                            )
                        }
                        rows.put(
                            JSONObject().put("id", e.id).put("status_id", e.status)
                                .put("rating", e.rating ?: JSONObject.NULL).put("user_book_reads", reads)
                                .put("edition_id", e.editionId ?: JSONObject.NULL)
                                .put("review_markdown", e.review ?: JSONObject.NULL)
                                .put("review_raw", e.review ?: JSONObject.NULL),
                        )
                    }
                    data.put("user_books", rows)
                }
                else -> error("unexpected query $query")
            }
            return MockResponse(code = 200, body = JSONObject().put("data", data).toString())
        }

        /** Hardcover starts a read for currently reading and finishes it for read, dated today. */
        private fun applyStatus(entry: Entry) {
            when (entry.status) {
                HardcoverStatus.CURRENTLY_READING ->
                    if (entry.read == null || entry.read?.finishedAt != null) {
                        entry.read = Read(nextRead++, null, TODAY, null, entry.editionId)
                    }
                HardcoverStatus.READ -> {
                    val read = entry.read ?: Read(nextRead++, null, TODAY, null, entry.editionId).also { entry.read = it }
                    if (read.finishedAt == null) read.finishedAt = TODAY
                }
            }
        }

        private fun edition() = JSONObject().put("id", 5).put("book_id", 9).put("title", "The Hobbit")
            .put("edition_format", "ebook").put("pages", 300)
    }

    private class FakeDao : HardcoverDao {
        val account = MutableStateFlow<HardcoverAccount?>(null)
        val links = mutableMapOf<String, HardcoverBookLink>()
        val pending = mutableMapOf<String, HardcoverPending>()
        var owner: Long? = null
        private val linkChanges = MutableStateFlow(0)

        override fun observeAccount(id: Long): Flow<HardcoverAccount?> = account
        override suspend fun account(id: Long): HardcoverAccount? = account.value
        override suspend fun upsertAccount(account: HardcoverAccount) {
            this.account.value = account
        }

        override suspend fun setAutoSync(autoSync: Boolean, id: Long) {
            account.value = account.value?.copy(autoSync = autoSync)
        }

        override suspend fun setTokenRefused(refused: Boolean, id: Long) {
            account.value = account.value?.copy(tokenRefused = refused)
        }

        override suspend fun deleteAccount(id: Long) {
            account.value = null
        }

        override fun observeLink(bookUrl: String): Flow<HardcoverBookLink?> = linkChanges.map { links[bookUrl] }
        override suspend fun link(bookUrl: String): HardcoverBookLink? = links[bookUrl]
        override suspend fun upsertLink(link: HardcoverBookLink) {
            links[link.bookUrl] = link
            linkChanges.value++
        }

        override suspend fun forgetLinks(bookUrls: List<String>) {
            bookUrls.forEach(links::remove)
        }

        override suspend fun setPushed(bookUrl: String, at: Long, rating: Double?) {
            links[bookUrl]?.let { links[bookUrl] = it.copy(pushedAt = at, pushedRating = rating, datesPending = false, editionChosen = false) }
        }

        override suspend fun setPushedRating(bookUrl: String, rating: Double?) {
            links[bookUrl]?.let { links[bookUrl] = it.copy(pushedRating = rating) }
        }

        override suspend fun markDatesPending(bookUrl: String) {
            links[bookUrl]?.let { links[bookUrl] = it.copy(datesPending = true) }
        }

        override suspend fun setImportedCover(bookUrl: String, url: String, sha256: String) {
            links[bookUrl]?.let { links[bookUrl] = it.copy(importedCoverUrl = url, importedCoverSha256 = sha256) }
        }

        override suspend fun linksOwner(id: Long): Long? = owner
        override suspend fun setLinksOwner(owner: HardcoverLinksOwner) {
            this.owner = owner.userId
        }

        override suspend fun deleteLinksOwner() {
            owner = null
        }

        override suspend fun deleteLinks() = links.clear()
        override suspend fun queue(pending: HardcoverPending) {
            this.pending[pending.bookUrl] = pending
        }

        override suspend fun nextPending(): HardcoverPending? = pending.values.minByOrNull { it.queuedAt }
        override suspend fun settle(bookUrl: String, queuedAt: Long) {
            if (pending[bookUrl]?.queuedAt == queuedAt) pending.remove(bookUrl)
        }

        override suspend fun forgetPending(bookUrls: List<String>) {
            bookUrls.forEach(pending::remove)
        }

        override suspend fun clearPending() = pending.clear()

        val forgetting = mutableSetOf<String>()
        val evaluations = mutableMapOf<Pair<String, String>, HardcoverEvaluation>()
        private val evalChanges = MutableStateFlow(0)

        override suspend fun markForgetting(rows: List<HardcoverForgetting>) {
            rows.forEach { forgetting += it.bookUrl }
        }

        override suspend fun unmarkForgetting(bookUrls: List<String>) {
            forgetting -= bookUrls.toSet()
        }

        override suspend fun isForgetting(bookUrl: String) = bookUrl in forgetting
        override suspend fun forgetting() = forgetting.toList()

        override fun observeEvaluations(bookUrl: String): Flow<List<HardcoverEvaluation>> =
            evalChanges.map {
                evaluations.values.filter { it.bookUrl == bookUrl }.sortedBy { it.field }
            }

        override suspend fun evaluation(bookUrl: String, field: String): HardcoverEvaluation? =
            evaluations[bookUrl to field]

        override suspend fun upsertEvaluation(evaluation: HardcoverEvaluation) {
            evaluations[evaluation.bookUrl to evaluation.field] = evaluation
            evalChanges.value++
        }

        override suspend fun forgetEvaluations(bookUrls: List<String>) {
            val urls = bookUrls.toSet()
            evaluations.keys.removeAll { it.first in urls }
            evalChanges.value++
        }

        override suspend fun deleteEvaluations() {
            evaluations.clear()
            evalChanges.value++
        }
    }

    private companion object {
        const val BOOK = "file:///books/hobbit.epub"
        const val TODAY = "2026-05-01"
        val PAGES = mapOf(5L to 300, 8L to 600, 90L to 300)
    }
}
