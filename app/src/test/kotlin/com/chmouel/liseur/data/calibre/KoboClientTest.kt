package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.domain.ReadingState
import com.chmouel.liseur.domain.ReadingStatus
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Kobo half of calibre-web, answered by a server that replies the way
 * the real one does — including the shapes that are easy to get wrong: a
 * reading state buried inside a new entitlement, a feed that arrives in
 * more than one piece, and every way of saying no.
 */
class KoboClientTest {

    private lateinit var server: MockWebServer
    private val client = KoboClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() = server.close()

    private fun base() = "http://127.0.0.1:${server.port}/kobo/sometoken"

    private fun pull(token: String? = null) =
        runBlocking { client.pullReadingStates(base(), token) }

    private fun json(body: String, code: Int = 200, headers: Headers = Headers.headersOf()) =
        MockResponse.Builder()
            .code(code)
            .headers(headers)
            .body(body)
            .build()

    @Test
    fun `reads a changed reading state`() {
        server.enqueue(
            json(
                """
                [{"ChangedReadingState": {"ReadingState": {
                  "EntitlementId": "uuid-1",
                  "CurrentBookmark": {"ProgressPercent": 42.5},
                  "StatusInfo": {"Status": "Reading"},
                  "LastModified": "2024-05-01T10:00:00Z"
                }}}]
                """.trimIndent(),
            ),
        )

        val page = (pull() as KoboResult.Ok).value
        val state = page.states.getValue("uuid-1")
        assertEquals(0.425, state.progression!!, 1e-9)
        assertEquals(ReadingStatus.READING, state.status)
        assertTrue(state.updatedAt > 0)
    }

    @Test
    fun `reads a reading state inlined in a new entitlement`() {
        server.enqueue(
            json(
                """
                [{"NewEntitlement": {"ReadingState": {
                  "EntitlementId": "uuid-2",
                  "CurrentBookmark": {"ProgressPercent": 100},
                  "StatusInfo": {"Status": "Finished"}
                }}}]
                """.trimIndent(),
            ),
        )

        val page = (pull() as KoboResult.Ok).value
        assertEquals(ReadingStatus.FINISHED, page.states.getValue("uuid-2").status)
    }

    /**
     * Stopping at the first page would silently drop everything after it,
     * and the token would still move past it all.
     */
    @Test
    fun `follows the feed to the end and keeps the last token`() {
        server.enqueue(
            json(
                """[{"ChangedReadingState": {"ReadingState": {"EntitlementId": "a",
                   "CurrentBookmark": {"ProgressPercent": 10},
                   "StatusInfo": {"Status": "Reading"}}}}]""",
                headers = Headers.headersOf(
                    "x-kobo-sync", "continue",
                    "x-kobo-synctoken", "token-1",
                ),
            ),
        )
        server.enqueue(
            json(
                """[{"ChangedReadingState": {"ReadingState": {"EntitlementId": "b",
                   "CurrentBookmark": {"ProgressPercent": 20},
                   "StatusInfo": {"Status": "Reading"}}}}]""",
                headers = Headers.headersOf("x-kobo-synctoken", "token-2"),
            ),
        )

        val page = (pull(token = "token-0") as KoboResult.Ok).value
        assertEquals(setOf("a", "b"), page.states.keys)
        assertEquals("token-2", page.syncToken)

        assertEquals("token-0", server.takeRequest().headers["x-kobo-synctoken"])
        assertEquals("token-1", server.takeRequest().headers["x-kobo-synctoken"])
    }

    @Test
    fun `a forbidden account is not mistaken for being offline`() {
        server.enqueue(json("", code = 403))
        assertEquals(SyncFailure.Forbidden, (pull() as KoboResult.Failed).reason)
    }

    @Test
    fun `a refused sign-in is reported as such`() {
        server.enqueue(json("", code = 401))
        val reason = (pull() as KoboResult.Failed).reason
        assertEquals(SyncFailure.Unauthorised, reason)
        assertTrue(!reason.worthRetrying)
    }

    @Test
    fun `a broken server is worth trying again`() {
        server.enqueue(json("", code = 503))
        val reason = (pull() as KoboResult.Failed).reason
        assertEquals(SyncFailure.ServerError(503), reason)
        assertTrue(reason.worthRetrying)
    }

    @Test
    fun `nonsense instead of a feed is reported as malformed`() {
        server.enqueue(json("this is not json"))
        assertEquals(SyncFailure.Malformed, (pull() as KoboResult.Failed).reason)
    }

    /**
     * A timestamp that cannot be read must not take the whole state with
     * it: it becomes zero, which loses to anything read locally.
     */
    @Test
    fun `an unreadable timestamp leaves the position intact`() {
        server.enqueue(
            json(
                """[{"ChangedReadingState": {"ReadingState": {"EntitlementId": "c",
                   "CurrentBookmark": {"ProgressPercent": 55},
                   "StatusInfo": {"Status": "Reading"},
                   "LastModified": "last tuesday"}}}]""",
            ),
        )

        val state = (pull() as KoboResult.Ok).value.states.getValue("c")
        assertEquals(0.55, state.progression!!, 1e-9)
        assertEquals(0L, state.updatedAt)
    }

    @Test
    fun `a book the server has no position for is not a failure`() {
        server.enqueue(json("", code = 404))
        val read = runBlocking { client.readState(base(), "uuid-1") }
        assertNull((read as KoboResult.Ok).value)
    }

    @Test
    fun `reading one book back understands the bare array`() {
        server.enqueue(
            json(
                """[{"EntitlementId": "uuid-1",
                   "CurrentBookmark": {"ProgressPercent": 12.5},
                   "StatusInfo": {"Status": "Reading"}}]""",
            ),
        )
        val read = runBlocking { client.readState(base(), "uuid-1") }
        assertEquals(0.125, (read as KoboResult.Ok).value!!.progression!!, 1e-9)
    }

    /**
     * The server indexes these three keys directly and answers 400 if any
     * is missing, and a `Location` of `{}` is likewise refused, so the
     * exact shape of what is sent is worth pinning down.
     */
    @Test
    fun `writing a position sends the shape calibre-web insists on`() {
        server.enqueue(json("{}"))
        val pushed = runBlocking {
            client.pushState(
                base(),
                "uuid-1",
                ReadingState(progression = 0.5, status = ReadingStatus.READING, updatedAt = 0),
            )
        }
        assertTrue(pushed is KoboResult.Ok)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        val sent = org.json.JSONObject(request.body!!.utf8())
            .getJSONArray("ReadingStates")
            .getJSONObject(0)
        assertEquals(50.0, sent.getJSONObject("CurrentBookmark").getDouble("ProgressPercent"), 1e-9)
        assertTrue(sent.getJSONObject("CurrentBookmark").isNull("Location"))
        assertTrue(sent.isNull("Statistics"))
        assertEquals("Reading", sent.getJSONObject("StatusInfo").getString("Status"))
    }

    @Test
    fun `a refused write says why`() {
        server.enqueue(json("", code = 403))
        val pushed = runBlocking {
            client.pushState(
                base(),
                "uuid-1",
                ReadingState(progression = 0.5, status = ReadingStatus.READING, updatedAt = 0),
            )
        }
        assertEquals(SyncFailure.Forbidden, (pushed as KoboResult.Failed).reason)
    }

    @Test
    fun `a server that is not there is offline, and worth trying again`() {
        val url = base()
        server.close()
        val reason = (runBlocking { client.pullReadingStates(url, null) } as KoboResult.Failed)
            .reason
        assertTrue(reason == SyncFailure.Offline || reason == SyncFailure.Timeout)
        assertTrue(reason.worthRetrying)
    }
}
