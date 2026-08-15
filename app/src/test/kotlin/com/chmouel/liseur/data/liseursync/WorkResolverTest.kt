package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.remote.RemoteCredentials
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Working out what a sync server calls a book.
 *
 * Two answers here decide whether a reader's place ends up in the right
 * book: a match the server only guessed at must not silently start
 * exchanging positions, and identifiers that name two different books
 * must leave both alone until somebody says which it is.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class WorkResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var resolver: WorkResolver
    private lateinit var file: File

    @Before
    fun open() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        file = File.createTempFile("book", ".epub").apply { writeBytes(ByteArray(4096) { 7 }) }
        resolver = WorkResolver(
            dao = db.workIdentityDao(),
            fingerprints = BookFingerprintStore(context, db.workIdentityDao()) { NOW },
            now = { NOW },
        )
    }

    @After
    fun close() {
        server.close()
        db.close()
        file.delete()
    }

    @Test
    fun `a confident answer becomes the name to sync under`() = runTest {
        answer(200, """{"work_id":"w-1","confidence":"high","created":false}""")

        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals("w-1", (result as WorkResolution.Named).alias.workId)
        assertTrue(result.alias.usable)
        assertEquals(db.workIdentityDao().alias(BOOK, PEER)?.workId, "w-1")
    }

    @Test
    fun `every identifier is offered, not only the strongest`() = runTest {
        // The server registers all of them against whichever matched,
        // which is how a re-encoded copy and the original converge.
        answer(201, """{"work_id":"w-1","confidence":"high","created":true}""")

        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        val body = JSONObject(server.takeRequest().body!!.utf8())
        val kinds = (0 until body.getJSONArray("identifiers").length()).map {
            body.getJSONArray("identifiers").getJSONObject(it).getString("kind")
        }
        assertEquals(listOf("sha256", "partial-md5", "source", "dc", "ta"), kinds)
        assertEquals("A Memory Called Empire", body.getString("title"))
    }

    @Test
    fun `a book with no file is named on what the catalog knows`() = runTest {
        // Refusing to sync until a book is downloaded would strand the
        // reader's place on whichever device holds the file.
        answer(200, """{"work_id":"w-2","confidence":"high"}""")

        val result = resolver.resolve(remoteOnly(), PEER, baseUrl(), TOKEN)

        assertEquals("w-2", (result as WorkResolution.Named).alias.workId)
        assertNull(result.alias.editionSha)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val kinds = (0 until body.getJSONArray("identifiers").length()).map {
            body.getJSONArray("identifiers").getJSONObject(it).getString("kind")
        }
        assertEquals(listOf("source", "dc", "ta"), kinds)
        // The catalog id is the book's own URL: the one string another
        // device connected to the same catalog is guaranteed to share.
        assertEquals(
            BOOK,
            body.getJSONArray("identifiers").getJSONObject(0).getString("value"),
        )
    }

    @Test
    fun `a name that predates the catalog id re-resolves once to register it`() = runTest {
        // An alias resolved before the source identifier existed never
        // told the server which catalog entry this is — the identifier a
        // fresh install matches on. It owes one re-resolve, and what the
        // reader and the seed pass established must survive it.
        db.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = BOOK,
                peerId = PEER,
                workId = "w-1",
                confidence = WorkAlias.LOW,
                confirmed = true,
                seeded = true,
                sourceSent = false,
                editionSha = "aa",
                resolvedAt = NOW,
            ),
        )
        answer(200, """{"work_id":"w-1","confidence":"high"}""")

        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        val alias = (result as WorkResolution.Named).alias
        assertTrue(alias.sourceSent)
        assertTrue(alias.confirmed)
        assertTrue(alias.seeded)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val kinds = (0 until body.getJSONArray("identifiers").length()).map {
            body.getJSONArray("identifiers").getJSONObject(it).getString("kind")
        }
        assertTrue("source" in kinds)
        // The reader's earlier yes travels with the request, so the
        // server may register the stronger identifiers on a fuzzy hit.
        assertTrue(body.getBoolean("confirmed"))

        // The debt is paid: the next resolve uses the cache.
        val again = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)
        assertEquals("w-1", (again as WorkResolution.Named).alias.workId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a guessed match waits for the reader before anything is exchanged`() = runTest {
        // "low" means title and author were all the server had, which
        // matches two translations of the same novel just as happily as
        // two copies of one file.
        answer(200, """{"work_id":"w-3","confidence":"low"}""")

        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        val alias = (result as WorkResolution.NeedsConfirming).alias
        assertEquals(WorkAlias.LOW, alias.confidence)
        assertTrue(!alias.usable)
        assertNull(resolver.cached(downloaded(), PEER))

        resolver.confirm(downloaded(), PEER)

        assertEquals("w-3", resolver.cached(downloaded(), PEER)?.workId)
    }

    @Test
    fun `a match the reader rejected is never asked about again`() = runTest {
        answer(200, """{"work_id":"w-3","confidence":"low"}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        resolver.reject(BOOK, PEER)

        // No second request, and no second question: a deleted alias
        // would be resolved again and the reader asked forever.
        val again = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)
        assertTrue(again is WorkResolution.Unresolved)
        assertEquals(1, server.requestCount)
        assertNull(resolver.cached(downloaded(), PEER))
    }

    @Test
    fun `books awaiting an answer are the ones to ask about`() = runTest {
        answer(200, """{"work_id":"w-3","confidence":"low"}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals(1, db.workIdentityDao().observeAwaitingAnswer(PEER).first().size)

        resolver.confirm(downloaded(), PEER)

        // Answered, so it stops being a question.
        assertTrue(db.workIdentityDao().observeAwaitingAnswer(PEER).first().isEmpty())
    }

    @Test
    fun `a name once learned is not asked for again`() = runTest {
        answer(200, """{"work_id":"w-1","confidence":"high"}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        val again = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals("w-1", (again as WorkResolution.Named).alias.workId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a guess made without the file is settled by the file, not the reader`() = runTest {
        // Catalog-only, so a title and an author were all there was to
        // offer, and the server could only guess.
        answer(200, """{"work_id":"w-6","confidence":"low"}""")
        resolver.resolve(remoteOnly(), PEER, baseUrl(), TOKEN)

        // The book has been downloaded since. Its hashes name it
        // exactly, so they answer the question instead of the reader.
        answer(200, """{"work_id":"w-6","confidence":"high"}""")
        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals("w-6", (result as WorkResolution.Named).alias.workId)
        assertTrue(result.alias.usable)
        server.takeRequest()
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val kinds = (0 until body.getJSONArray("identifiers").length()).map {
            body.getJSONArray("identifiers").getJSONObject(it).getString("kind")
        }
        assertTrue("sha256" in kinds)
        assertTrue(db.workIdentityDao().observeAwaitingAnswer(PEER).first().isEmpty())
    }

    @Test
    fun `a guess made from the file itself still waits for the reader`() = runTest {
        // Here the server saw the hashes and still only matched on the
        // title. It is re-asked — the other device may have registered
        // the catalog id since — but an answer that is still a guess
        // leaves the question with the reader.
        answer(200, """{"work_id":"w-7","confidence":"low"}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        answer(200, """{"work_id":"w-7","confidence":"low"}""")
        val again = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertTrue(again is WorkResolution.NeedsConfirming)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `identifiers that name two books are left for the reader to settle`() = runTest {
        answer(409, """{"error":"identifiers resolve to multiple works","works":["w-4","w-5"]}""")

        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals(listOf("w-4", "w-5"), (result as WorkResolution.Ambiguous).candidates)
        // Nothing was named, so nothing may be exchanged.
        assertNull(db.workIdentityDao().alias(BOOK, PEER))
        assertEquals(1, db.workIdentityDao().ambiguityCount(PEER))
    }

    @Test
    fun `settling the disagreement clears it`() = runTest {
        answer(409, """{"error":"multiple","works":["w-4","w-5"]}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        answer(200, """{"work_id":"w-4","confidence":"high"}""")
        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals("w-4", (result as WorkResolution.Named).alias.workId)
        assertEquals(0, db.workIdentityDao().ambiguityCount(PEER))
    }

    @Test
    fun `a server that cannot be reached leaves the book unnamed`() = runTest {
        // Not an error the reader is shown: the next run asks again, and
        // meanwhile nothing about the book has been decided wrongly.
        answer(500, """{"error":"nope"}""")

        val result = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertTrue(result is WorkResolution.Unresolved)
        assertNull(db.workIdentityDao().alias(BOOK, PEER))
    }

    @Test
    fun `a file rewritten in place is hashed again`() = runTest {
        answer(200, """{"work_id":"w-1","confidence":"high"}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)
        val first = db.workIdentityDao().fingerprint(BOOK)!!

        file.writeBytes(ByteArray(4096) { 9 })
        // A new modification time is what says the bytes changed; the
        // path did not.
        BookFingerprintStore(
            ApplicationProvider.getApplicationContext(),
            db.workIdentityDao(),
        ) { NOW }.of(downloaded(modifiedAt = NOW + 1))

        val second = db.workIdentityDao().fingerprint(BOOK)!!
        assertTrue(first.sha256 != second.sha256)
        assertEquals(NOW + 1, second.fileModifiedAt)
    }

    private fun answer(code: Int, body: String) =
        server.enqueue(MockResponse(code = code, body = body))

    @Test
    fun `a book from this server's own catalog is resolved by the server`() = runTest {
        // No identifiers are sent: the catalog knows the file's digests
        // even here, where the file was never downloaded, and two
        // devices browsing it name the book identically.
        answer(
            200,
            """{"book_id":"b-1","work_id":"w-9","confidence":"high","created":true,
                "identifiers":[{"kind":"sha256","value":"ab12cd"}]}
            """.trimIndent(),
        )

        val book = downloaded().copy(
            url = "liseur-sync:b-1",
            localUri = null,
            downloadState = DownloadState.REMOTE,
        )
        val result = resolver.resolve(book, PEER, baseUrl(), TOKEN)

        assertEquals("w-9", (result as WorkResolution.Named).alias.workId)
        // The server's own digest is kept: ops carry the edition without
        // the file ever being hashed here.
        assertEquals("ab12cd", result.alias.editionSha)
        // And there is no source left to hand over: the book *is* the
        // catalog entry.
        assertTrue(result.alias.sourceSent)

        val asked = server.takeRequest()
        assertTrue(asked.target!!.endsWith("/v1/books/b-1/resolve"))
        assertEquals("{}", JSONObject(asked.body!!.utf8()).toString())
    }

    @Test
    fun `a doubtful catalog match is confirmed the same way as any other`() = runTest {
        answer(200, """{"book_id":"b-1","work_id":"w-9","confidence":"low"}""")
        val book = downloaded().copy(url = "liseur-sync:b-1")

        val first = resolver.resolve(book, PEER, baseUrl(), TOKEN)
        assertTrue(first is WorkResolution.NeedsConfirming)
        // The first ask sent nothing: no identifiers, and no yes yet.
        server.takeRequest()

        resolver.confirm(book, PEER)
        answer(200, """{"book_id":"b-1","work_id":"w-9","confidence":"high"}""")
        val second = resolver.resolve(book, PEER, baseUrl(), TOKEN)

        assertTrue(second is WorkResolution.Named)
        // The earlier yes travelled with the second ask.
        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).getBoolean("confirmed"))
    }

    private fun baseUrl() = "http://127.0.0.1:${server.port}"

    private fun downloaded(modifiedAt: Long = NOW) = Book(
        url = BOOK,
        title = "A Memory Called Empire",
        author = "Arkady Martine",
        coverPath = null,
        source = null,
        addedAt = NOW,
        lastOpenedAt = null,
        localUri = file.toURI().toString(),
        workId = "urn:isbn:9780765387561",
        fileModifiedAt = modifiedAt,
    )

    private fun remoteOnly() = downloaded().copy(
        localUri = null,
        downloadState = DownloadState.REMOTE,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOK = "calibre:2f9b"
        const val PEER = "liseursync|https://sync.example|ada"
        val TOKEN = RemoteCredentials.Bearer("device-secret")
    }
}
