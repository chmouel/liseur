package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerDeleteResult
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a book from liseur-sync.
 *
 * This is the one action in the app that destroys somebody's bytes and
 * has no trash behind it (ADR-0025), so the answers matter twice over.
 * A 404 has to read as done rather than failed, or a retry after a lost
 * reply tells the reader their book is still there when it is not; and
 * the reading has to be left alone unless they asked, because another
 * device may still be reading the same book.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncDeleteClientTest {

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
    fun `the book goes, and the reading is left alone unless asked`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals(ServerDeleteResult.Deleted, delete(forgetReading = false))

        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/v1/books/remote-1", sent.url.encodedPath)
        // Absent, not false: the reading is the reader's and the server
        // keeps it unless they said otherwise.
        assertTrue(
            "the reading was forgotten without being asked about",
            sent.url.queryParameter("forget_reading") == null,
        )
    }

    @Test
    fun `asking to forget the reading says so`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals(ServerDeleteResult.Deleted, delete(forgetReading = true))

        assertEquals("true", server.takeRequest().url.queryParameter("forget_reading"))
    }

    /**
     * A book the server does not have is a book the reader no longer has
     * to think about. The first attempt of a retried delete very likely
     * succeeded, and reporting that as a failure would leave them
     * hunting for a book that is already gone.
     */
    @Test
    fun `a book the server has never heard of is a book that is gone`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(ServerDeleteResult.Deleted, delete())
    }

    @Test
    fun `a refusal is not a failure to retry`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())

        assertEquals(ServerDeleteResult.NotAllowed, delete())
    }

    /**
     * A 409 is the server explaining something the reader can act on — a
     * Calibre library open on the desktop, most often — so its sentence
     * is carried through rather than replaced with a status code.
     */
    @Test
    fun `a conflict carries the server's own reason`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":"the Calibre library is open somewhere else"}""")
                .build(),
        )

        val result = delete()

        assertTrue(result is ServerDeleteResult.Failed)
        assertEquals(
            "the Calibre library is open somewhere else",
            (result as ServerDeleteResult.Failed).message,
        )
    }

    /** A book with no name on the server cannot be deleted from it. */
    @Test
    fun `a book the server never knew is not sent anywhere`() = runTest {
        val result = LiseurSyncDeleteClient()
            .delete(base(), token(), book(remoteUuid = null), false)

        assertTrue(result is ServerDeleteResult.Failed)
        assertEquals(0, server.requestCount)
    }

    private suspend fun delete(forgetReading: Boolean = false): ServerDeleteResult =
        LiseurSyncDeleteClient().delete(base(), token(), book(), forgetReading)

    private fun base(): String = server.url("/").toString().trimEnd('/')

    private fun token(): RemoteCredentials = RemoteCredentials.Bearer("device-secret")

    private fun book(remoteUuid: String? = "remote-1"): Book = Book(
        url = "liseursync:remote-1",
        title = "Notes",
        author = "Ada",
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        remoteUuid = remoteUuid,
        downloadState = DownloadState.DOWNLOADED,
    )
}
