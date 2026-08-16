package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.SeriesNameTaken
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
 * Renaming a series on a liseur-sync server (ADR-0020).
 *
 * A rename is a layer over the name the server's scan read, so the
 * answer carries both, and the one refusal that means something — the
 * name is taken — has to arrive as itself rather than as a failed
 * request.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncSeriesClientTest {

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
    fun `a rename is a personal claim on the entity's name`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"id":"s-1","name":"The Expanse","scanned_name":"Expanse, The",
                    "name_source":"personal","book_count":9}
                """.trimIndent(),
            ),
        )

        val renamed = LiseurSyncSeriesClient().renameSeries(
            BASE, credentials, "s-1", "The Expanse",
        )

        assertEquals("The Expanse", renamed?.name)
        assertEquals("Expanse, The", renamed?.scannedName)
        assertEquals(true, renamed?.renamed)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/entities/series/s-1/name", request.target)
        val body = request.body!!.utf8()
        assertTrue(body.contains(""""scope":"personal""""))
        assertTrue(body.contains(""""name":"The Expanse""""))
    }

    @Test
    fun `a name another shelf already has is refused, not retried`() = runTest {
        server.enqueue(
            MockResponse(code = 409, body = """{"error":"series name already in use"}"""),
        )

        val client = LiseurSyncSeriesClient()
        try {
            client.renameSeries(BASE, credentials, "s-1", "Taken")
            error("a taken name should not have been accepted")
        } catch (expected: SeriesNameTaken) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    @Test
    fun `a revert drops the personal layer and answers with the scanned name`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"id":"s-1","name":"Expanse, The","scanned_name":"Expanse, The",
                    "name_source":"folder","book_count":9}
                """.trimIndent(),
            ),
        )

        val reverted = LiseurSyncSeriesClient().resetSeriesName(BASE, credentials, "s-1")

        assertEquals("Expanse, The", reverted?.name)
        assertEquals(false, reverted?.renamed)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/entities/series/s-1/name?scope=personal", request.target)
    }

    private companion object {
        val credentials = RemoteCredentials.Bearer("token")
    }

    private val BASE: String get() = "http://127.0.0.1:${server.port}"
}
