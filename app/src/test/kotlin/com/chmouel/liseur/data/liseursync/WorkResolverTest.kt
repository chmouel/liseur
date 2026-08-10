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
        assertEquals(listOf("sha256", "partial-md5", "dc", "ta"), kinds)
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
        assertEquals(listOf("dc", "ta"), kinds)
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
    fun `a name once learned is not asked for again`() = runTest {
        answer(200, """{"work_id":"w-1","confidence":"high"}""")
        resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        val again = resolver.resolve(downloaded(), PEER, baseUrl(), TOKEN)

        assertEquals("w-1", (again as WorkResolution.Named).alias.workId)
        assertEquals(1, server.requestCount)
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
