package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.remote.RemoteCredentials
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class KomgaCatalogClientTest {

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

    private fun page(vararg ids: String, last: Boolean) = MockResponse(
        body = JSONObject()
            .put("last", last)
            .put(
                "content",
                jsonArrayOf(
                    *ids.map { JSONObject().put("id", it).put("name", "$it.epub") }.toTypedArray(),
                ),
            )
            .toString(),
    )

    @Test
    fun `only epubs the server has finished analysing are asked for`() = runBlocking {
        server.enqueue(page("b1", last = true))

        KomgaCatalogClient().allBooks(baseUrl(), key)

        val request = server.takeRequest()
        assertTrue(request.target.startsWith("/api/v1/books/list?"))
        assertEquals("POST", request.method)

        val condition = JSONObject(request.body!!.utf8()).getJSONObject("condition")
        val filters = condition.getJSONArray("allOf")
        assertEquals("EPUB", filters.getJSONObject(0).getJSONObject("mediaProfile").get("value"))
        assertEquals("READY", filters.getJSONObject(1).getJSONObject("mediaStatus").get("value"))
    }

    @Test
    fun `every page is walked and reported as it lands`() = runBlocking {
        server.enqueue(page("b1", "b2", last = false))
        server.enqueue(page("b3", last = true))

        val seen = mutableListOf<List<String>>()
        val books = KomgaCatalogClient().allBooks(baseUrl(), key) { batch ->
            seen += batch.map { it.remoteId }
        }

        assertEquals(listOf("b1", "b2", "b3"), books.map { it.remoteId })
        assertEquals(listOf(listOf("b1", "b2"), listOf("b3")), seen)
        assertTrue(server.takeRequest().target.contains("page=0"))
        assertTrue(server.takeRequest().target.contains("page=1"))
    }

    @Test
    fun `an empty page ends the walk even if the server never says last`() = runBlocking {
        server.enqueue(MockResponse(body = """{"content":[],"last":false}"""))

        assertTrue(KomgaCatalogClient().allBooks(baseUrl(), key).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a search is one request and still only asks for readable books`() = runBlocking {
        server.enqueue(page("b1", last = true))

        val found = KomgaCatalogClient().search(baseUrl(), key, "moby dick")

        assertEquals(listOf("b1"), found.map { it.remoteId })
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("moby dick", body.getString("fullTextSearch"))
        assertTrue(body.has("condition"))
    }
}
