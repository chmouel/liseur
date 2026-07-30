package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.remote.DeviceIdentity
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The progression client against the answers a real Komga gives.
 *
 * Every status here was provoked on a running server first: the 400 for
 * a position it cannot place, the 409 for one older than what it holds,
 * and the 204-with-no-body for a book nobody has opened.
 */
class KomgaProgressionClientTest {

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

    private fun baseUrl() = "http://127.0.0.1:${server.port}"

    private val key = RemoteCredentials.ApiKey("a-key")
    private val device = DeviceIdentity("device-1", "Test Phone")
    private val book = "0R57273D2Z517"

    private fun locator(href: String = "OEBPS/Text/titl001.xhtml", progression: Double = 0.5) =
        JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put(
                "locations",
                JSONObject().put("progression", progression).put("totalProgression", 0.0025),
            )

    private fun push() = runBlocking {
        KomgaProgressionClient().push(baseUrl(), key, book, locator(), MODIFIED, device)
    }

    @Test
    fun `a book nobody has opened reads back as no position, not as an error`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        val result = KomgaProgressionClient().read(baseUrl(), key, book)

        assertNull((result as RemoteResult.Ok).value)
    }

    @Test
    fun `a book the server has never heard of is a failure`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        val result = KomgaProgressionClient().read(baseUrl(), key, book)

        assertEquals(SyncFailure.NotFound, result.failure)
    }

    @Test
    fun `a saved position is sent the way komga wants it`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        assertEquals(PushOutcome.Accepted, push().let { (it as RemoteResult.Ok).value })

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/books/$book/progression", request.target)

        val body = JSONObject(request.body!!.utf8())
        assertEquals("device-1", body.getJSONObject("device").getString("id"))
        assertEquals("Test Phone", body.getJSONObject("device").getString("name"))
        assertEquals("OEBPS/Text/titl001.xhtml", body.getJSONObject("locator").getString("href"))
        assertEquals(KomgaTime.format(MODIFIED), body.getString("modified"))
    }

    @Test
    fun `a position the server holds newer of is left alone rather than overwritten`() =
        runBlocking {
            server.enqueue(MockResponse(code = 409))

            assertEquals(PushOutcome.Stale, (push() as RemoteResult.Ok).value)
            // Nothing else was tried: the server's copy is the good one.
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a refused position is moved to the nearest one the server admits to`() = runBlocking {
        server.enqueue(MockResponse(code = 400, body = "Invalid progression"))
        server.enqueue(MockResponse(body = POSITIONS))
        server.enqueue(MockResponse(code = 204))

        assertEquals(PushOutcome.Accepted, (push() as RemoteResult.Ok).value)

        server.takeRequest()
        assertEquals("/api/v1/books/$book/positions", server.takeRequest().target)

        val retry = JSONObject(server.takeRequest().body!!.utf8()).getJSONObject("locator")
        assertEquals("OEBPS/Text/titl001.xhtml", retry.getString("href"))
        // 0.333... is the last page at or before where the reader is.
        assertEquals(0.33333334, retry.getJSONObject("locations").getDouble("progression"), 1e-7)
    }

    @Test
    fun `a position refused twice is reported rather than retried forever`() = runBlocking {
        server.enqueue(MockResponse(code = 400))
        server.enqueue(MockResponse(body = POSITIONS))
        server.enqueue(MockResponse(code = 400))

        assertEquals(PushOutcome.Unplaceable, (push() as RemoteResult.Ok).value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a locator with nowhere to point never reaches the server`() = runBlocking {
        val result = KomgaProgressionClient()
            .push(baseUrl(), key, book, JSONObject(), MODIFIED, device)

        assertEquals(PushOutcome.Unplaceable, (result as RemoteResult.Ok).value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `finishing a book is said without a locator`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        KomgaProgressionClient().markCompleted(baseUrl(), key, book)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/books/$book/read-progress", request.target)
        assertEquals(true, JSONObject(request.body!!.utf8()).getBoolean("completed"))
    }

    @Test
    fun `a book can be marked unread again`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        KomgaProgressionClient().clear(baseUrl(), key, book)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/books/$book/read-progress", request.target)
    }

    @Test
    fun `a server that breaks is reported as a server that broke`() = runBlocking {
        server.enqueue(MockResponse(code = 500))

        assertEquals(SyncFailure.ServerError(500), push().failure)
    }

    private companion object {
        const val MODIFIED = 1_785_398_878_123L

        val POSITIONS = """
        {"total":3,"positions":[
          {"href":"OEBPS/Text/titlepage.xhtml","type":"application/xhtml+xml",
           "locations":{"progression":0.0,"position":1,"totalProgression":0.000856898}},
          {"href":"OEBPS/Text/titl001.xhtml","type":"application/xhtml+xml",
           "locations":{"progression":0.0,"position":2,"totalProgression":0.001713796}},
          {"href":"OEBPS/Text/titl001.xhtml","type":"application/xhtml+xml",
           "locations":{"progression":0.33333334,"position":3,"totalProgression":0.002570694}}
        ]}
        """.trimIndent()
    }
}
