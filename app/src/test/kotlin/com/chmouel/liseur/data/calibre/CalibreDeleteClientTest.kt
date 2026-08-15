package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.ServerDeleteResult
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Deleting a book on the server, against a server that answers like
 * calibre-web does — including the awkward part, where being logged out
 * looks exactly like success.
 */
class CalibreDeleteClientTest {

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

    private fun delete() = runBlocking {
        CalibreDeleteClient().delete(baseUrl(), "reader", "secret", remoteBookId = 42)
    }

    @Test
    fun `deletes when the server took the login and the delete`() {
        server.enqueue(MockResponse(body = LOGIN_PAGE))
        server.enqueue(MockResponse(body = LIBRARY_PAGE))
        server.enqueue(MockResponse(body = LIBRARY_PAGE)) // the CSRF fetch
        server.enqueue(MockResponse(body = """{"location": "/"}"""))

        assertEquals(ServerDeleteResult.Deleted, delete())
    }

    @Test
    fun `refuses to call it deleted when the password was wrong`() {
        server.enqueue(MockResponse(body = LOGIN_PAGE))
        // calibre-web renders the login page again, with a 200.
        server.enqueue(MockResponse(body = LOGIN_PAGE))

        assertEquals(ServerDeleteResult.NotAllowed, delete())
        // The delete was never attempted: only the two login requests ran.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `refuses to call it deleted when the delete lands on the login page`() {
        server.enqueue(MockResponse(body = LOGIN_PAGE))
        server.enqueue(MockResponse(body = LIBRARY_PAGE))
        server.enqueue(MockResponse(body = LIBRARY_PAGE))
        // The session expired between logging in and deleting, so the
        // delete is redirected to a login page that answers 200.
        server.enqueue(MockResponse(body = LOGIN_PAGE))

        assertEquals(ServerDeleteResult.NotAllowed, delete())
    }

    @Test
    fun `reports an account that may not delete`() {
        server.enqueue(MockResponse(body = LOGIN_PAGE))
        server.enqueue(MockResponse(body = LIBRARY_PAGE))
        server.enqueue(MockResponse(body = LIBRARY_PAGE))
        server.enqueue(MockResponse(code = 403))

        assertEquals(ServerDeleteResult.NotAllowed, delete())
    }

    @Test
    fun `does not follow a redirect off the server`() {
        val elsewhere = MockWebServer()
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            // A login that tries to bounce the session somewhere else.
            server.enqueue(MockResponse(body = LOGIN_PAGE))
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = Headers.headersOf(
                        "Location",
                        "http://localhost:${elsewhere.port}/",
                    ),
                ),
            )

            assertEquals(ServerDeleteResult.NotAllowed, delete())
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `keeps a session cookie to the path it was scoped to`() {
        val jar = SessionCookieJar()
        val url = "http://books.example.com/calibre/login".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(Cookie.parse(url, "session=abc; Path=/calibre")!!),
        )

        assertEquals(1, jar.loadForRequest("http://books.example.com/calibre/x".toHttpUrl()).size)
        assertEquals(0, jar.loadForRequest("http://books.example.com/other".toHttpUrl()).size)
        assertEquals(0, jar.loadForRequest("http://elsewhere.example.com/calibre".toHttpUrl()).size)
    }

    @Test
    fun `tells a login page apart from a page that means something`() {
        assertEquals(true, CalibreParsing.isLoginPage(LOGIN_PAGE))
        assertEquals(false, CalibreParsing.isLoginPage(LIBRARY_PAGE))
        assertNull(CalibreParsing.csrfToken(LIBRARY_PAGE))
    }

    private companion object {
        val LOGIN_PAGE = """
            <html><body>
              <form method="POST" action="/login">
                <input name="csrf_token" value="tok123">
                <input name="username" type="text">
                <input name="password" type="password">
              </form>
            </body></html>
        """.trimIndent()

        val LIBRARY_PAGE = """
            <html><body>
              <h1>Books</h1>
              <a href="/logout">Log out</a>
            </body></html>
        """.trimIndent()
    }
}
