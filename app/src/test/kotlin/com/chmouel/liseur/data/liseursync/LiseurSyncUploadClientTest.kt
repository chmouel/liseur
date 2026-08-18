package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerUploadResult
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sending a book to liseur-sync.
 *
 * The server's answer is the whole contract here (ADR-0023), and the
 * codes do not collapse into "worked" and "did not": a 200 means the
 * server already had these bytes and no transfer was needed, a 202 means
 * they are safe but not yet a book, and a 403 is the one answer where
 * offering the action again would be a lie. Getting any of those wrong
 * either loses a book or nags the reader about one that arrived.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncUploadClientTest {

    @get:Rule
    val temp = TemporaryFolder()

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
    fun `only folders that asked for uploads are offered`() = runTest {
        server.enqueue(
            json(
                """{"folders":[
                    {"folder_id":"f-1","name":"Read-only shelf","accepts_uploads":false},
                    {"folder_id":"f-2","name":"Drop box","accepts_uploads":true},
                    {"folder_id":"f-3","name":"Old server","kind":"plain"}
                ]}
                """.trimIndent(),
            ),
        )

        val targets = LiseurSyncUploadClient().targets(base(), token())

        // f-3 has no flag at all, which is what an older server sends:
        // absent is not permission.
        assertEquals(1, targets.size)
        assertEquals("f-2", targets[0].folderId)
        assertEquals("Drop box", targets[0].name)
    }

    @Test
    fun `a created book comes back with the id to adopt`() = runTest {
        server.enqueue(
            json(
                """{"book_id":"b-9","folder_id":"f-2",
                    "relative_path":"Ada - Notes.epub","duplicate":false}
                """.trimIndent(),
                code = 201,
            ),
        )

        val result = upload()

        assertEquals(ServerUploadResult.Uploaded("b-9", alreadyThere = false), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.target!!.endsWith("/v1/folders/f-2/books"))
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `a book the server already had is a success, not a transfer`() = runTest {
        server.enqueue(
            json("""{"book_id":"b-9","folder_id":"f-2","duplicate":true}""", code = 200),
        )

        assertEquals(
            ServerUploadResult.Uploaded("b-9", alreadyThere = true),
            upload(),
        )
    }

    @Test
    fun `bytes that arrived before the catalogue did are pending`() = runTest {
        // Rules 1 and 2 of ADR-0017 let a folder pass conclude nothing
        // yet. The book is not lost and the transfer must not be redone.
        server.enqueue(
            json("""{"folder_id":"f-2","relative_path":"Ada - Notes.epub"}""", code = 202),
        )

        assertEquals(ServerUploadResult.Pending, upload())
    }

    @Test
    fun `each refusal keeps its own meaning`() = runTest {
        for ((code, expected) in listOf(
            403 to ServerUploadResult.NotAllowed,
            413 to ServerUploadResult.TooLarge,
            422 to ServerUploadResult.Rejected,
        )) {
            server.enqueue(json("""{"error":"no"}""", code = code))
            assertEquals("HTTP $code", expected, upload())
        }
    }

    @Test
    fun `a server error is worth trying again`() = runTest {
        server.enqueue(json("""{"error":"the folder went away"}""", code = 500))

        val result = upload()

        assertTrue(result is ServerUploadResult.Failed)
        assertEquals("the folder went away", (result as ServerUploadResult.Failed).message)
    }

    /**
     * The caller reads an empty list as the server refusing, and acts on
     * it by putting the feature away until the reader signs in again. So
     * a connection that dropped must not be able to say that: it throws,
     * and the worker retries.
     */
    @Test
    fun `a server that could not be reached is not a server with no folders`() = runTest {
        server.close()

        var thrown: Throwable? = null
        try {
            LiseurSyncUploadClient().targets(base(), token())
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("listing folders swallowed the failure", thrown)
    }

    /** Half an answer is not an answer either. */
    @Test
    fun `a page that fails does not shorten the list of folders`() = runTest {
        server.enqueue(
            json(
                """{"folders":[{"folder_id":"f-1","name":"Drop box","accepts_uploads":true}],
                    "next_after":"f-1"}
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"error":"gone"}""", code = 500))

        var thrown: Throwable? = null
        try {
            LiseurSyncUploadClient().targets(base(), token())
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("a partial page was passed off as the whole answer", thrown)
    }

    /**
     * A 200 is not by itself an answer. Anything on the way in can serve
     * one — a proxy, a portal — and if its JSON happens to parse, the
     * absent folder list would read as "no folder takes books" and cost
     * the reader the feature.
     */
    @Test
    fun `a body with no folder list is not a server with no folders`() = runTest {
        server.enqueue(json("""{"error":"not the server you wanted"}"""))

        var thrown: Throwable? = null
        try {
            LiseurSyncUploadClient().targets(base(), token())
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull("a body carrying no folders was read as a refusal", thrown)
    }

    /** An empty list, on the other hand, is the server's to send. */
    @Test
    fun `a server with nothing to offer says so, and is believed`() = runTest {
        server.enqueue(json("""{"folders":[]}"""))

        assertTrue(LiseurSyncUploadClient().targets(base(), token()).isEmpty())
    }

    private var books = 0

    private suspend fun upload(): ServerUploadResult {
        val book = temp.newFile("book-${books++}.epub")
        book.writeBytes(ByteArray(64) { it.toByte() })
        return LiseurSyncUploadClient().upload(
            base(), token(), "f-2", book, "Ada - Notes.epub",
        )
    }

    private fun base(): String = server.url("/").toString().trimEnd('/')

    private fun token(): RemoteCredentials = RemoteCredentials.ApiKey("device-secret")

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

}
