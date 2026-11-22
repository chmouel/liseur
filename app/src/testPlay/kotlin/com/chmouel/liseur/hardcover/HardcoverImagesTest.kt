package com.chmouel.liseur.hardcover

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HardcoverImagesTest {
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

    @Test
    fun `only https on the image host with no userinfo and the default port is accepted`() {
        val good = "https://assets.hardcover.app/books/1/cover.jpg"
        assertEquals(good, HardcoverImages.validCoverUrl(good))
        assertEquals(good, HardcoverImages.validCoverUrl(" https://assets.hardcover.app:443/books/1/cover.jpg "))

        assertNull(HardcoverImages.validCoverUrl(null))
        assertNull(HardcoverImages.validCoverUrl(""))
        assertNull(HardcoverImages.validCoverUrl("http://assets.hardcover.app/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("https://user:pw@assets.hardcover.app/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("https://user@assets.hardcover.app/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("https://assets.hardcover.app:8443/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("https://hardcover.app/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("https://assets.hardcover.app.evil.com/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("https://evil.com/assets.hardcover.app/a.jpg"))
        assertNull(HardcoverImages.validCoverUrl("//assets.hardcover.app/a.jpg"))
    }

    @Test
    fun `the client sends no credentials and does not follow a redirect`() {
        server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "/elsewhere")))
        server.enqueue(MockResponse(code = 200, body = "second"))

        val response = HardcoverImages.newClient()
            .newCall(Request.Builder().url("http://127.0.0.1:${server.port}/a.jpg").build())
            .execute()
        response.close()

        assertEquals(302, response.code)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun `the client has no interceptors, authenticator, cache or cookie jar`() {
        val client = HardcoverImages.newClient()

        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(okhttp3.Authenticator.NONE, client.authenticator)
        assertEquals(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertTrue(!client.followRedirects && !client.followSslRedirects)
    }

    @Test
    fun `cover download reads valid responses and rejects non-2xx or oversized bodies`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "jpeg-bytes"))
        server.enqueue(MockResponse(code = 404, body = "missing"))
        server.enqueue(MockResponse(code = 200, body = "too-many-bytes"))

        val url = "http://127.0.0.1:${server.port}/cover.jpg"
        assertArrayEquals("jpeg-bytes".toByteArray(), HardcoverImages.fetchBytes(url, maxBytes = 32))
        assertNull(HardcoverImages.fetchBytes(url, maxBytes = 32))
        assertNull(HardcoverImages.fetchBytes(url, maxBytes = 5))
        assertNull(HardcoverImages.downloadCover("https://evil.example/cover.jpg"))
    }
}
