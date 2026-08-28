package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Who gets the reader's catalog password when the server starts moving
 * the request around.
 *
 * Checking where a redirect landed is too late. OkHttp re-sends the
 * `Authorization` header on a same-host hop and drops it on a
 * cross-host one, which is nearly the right rule and is applied after
 * the request has gone. Deciding per hop, before each one is sent, is
 * the only place the question can be asked while the answer still
 * matters.
 */
class OpdsHttpTest {

    private lateinit var catalog: MockWebServer
    private lateinit var elsewhere: MockWebServer

    @Before
    fun start() {
        catalog = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        elsewhere = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun stop() {
        catalog.close()
        elsewhere.close()
    }

    private val credentials = RemoteCredentials.Basic("reader", "secret")

    private fun scope() = OpdsScope.of("http://127.0.0.1:${catalog.port}/opds")!!

    private fun ok(body: String = "hello") = MockResponse(code = 200, body = body)

    private fun moved(to: String) =
        MockResponse(code = 302, headers = Headers.headersOf("Location", to))

    private fun get(url: String) =
        OpdsHttp().get(url.toHttpUrl(), scope(), credentials).also { it.response.close() }

    @Test
    fun `the catalog's own address is signed`() {
        catalog.enqueue(ok())

        get("http://127.0.0.1:${catalog.port}/opds")

        assertNotNull(catalog.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a file beside the feed on the same server is signed`() {
        catalog.enqueue(ok())

        get("http://127.0.0.1:${catalog.port}/get/1.epub")

        assertNotNull(catalog.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a link the feed points at another server is fetched as a stranger`() {
        // OPDS is federated. An open-access link to another archive is a
        // real and useful thing, so it is followed — without the
        // reader's password on it.
        elsewhere.enqueue(ok())

        get("http://127.0.0.1:${elsewhere.port}/free/1.epub")

        assertNull(elsewhere.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a redirect out of the catalog drops the password on the way`() {
        catalog.enqueue(moved("http://127.0.0.1:${elsewhere.port}/free/1.epub"))
        elsewhere.enqueue(ok())

        get("http://127.0.0.1:${catalog.port}/get/1.epub")

        assertNotNull(catalog.takeRequest().headers["Authorization"])
        assertNull(elsewhere.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a redirect within the catalog keeps it`() {
        catalog.enqueue(moved("/get/1-final.epub"))
        catalog.enqueue(ok())

        get("http://127.0.0.1:${catalog.port}/get/1.epub")

        catalog.takeRequest()
        assertNotNull(catalog.takeRequest().headers["Authorization"])
    }

    @Test
    fun `the URL that answered is the one reported back`() {
        // The walk resolves relative links against it, so a redirected
        // feed's links have to move with it.
        catalog.enqueue(moved("/opds/all/"))
        catalog.enqueue(ok())

        val fetch = OpdsHttp().get(
            "http://127.0.0.1:${catalog.port}/opds".toHttpUrl(),
            scope(),
            credentials,
        )
        fetch.response.close()

        assertEquals("http://127.0.0.1:${catalog.port}/opds/all/", fetch.url.toString())
    }

    @Test
    fun `a server redirecting round in a circle is given up on`() {
        repeat(10) { catalog.enqueue(moved("/opds/round/$it")) }

        val thrown = runCatching { get("http://127.0.0.1:${catalog.port}/opds") }
            .exceptionOrNull()

        assertEquals(SyncFailure.Malformed, (thrown as RemoteHttpFailure).reason)
    }

    @Test
    fun `a redirect to nowhere is a broken server, not a crash`() {
        catalog.enqueue(moved("http://"))

        val thrown = runCatching { get("http://127.0.0.1:${catalog.port}/opds") }
            .exceptionOrNull()

        assertEquals(SyncFailure.Malformed, (thrown as RemoteHttpFailure).reason)
    }

    @Test
    fun `an anonymous catalog sends no credential anywhere`() {
        catalog.enqueue(ok())

        OpdsHttp().get(
            "http://127.0.0.1:${catalog.port}/opds".toHttpUrl(),
            scope(),
            RemoteCredentials.Anonymous,
        ).response.close()

        assertNull(catalog.takeRequest().headers["Authorization"])
    }
}
