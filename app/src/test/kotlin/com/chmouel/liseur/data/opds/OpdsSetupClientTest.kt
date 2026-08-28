package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What "this address is a catalog" means when there is no shim to
 * recognise and no capability route to ask.
 *
 * The test is that the address answers with a feed. Anything can answer
 * 200 with HTML, and a login page arriving cheerfully is the ordinary
 * way for a typed address to be wrong.
 */
class OpdsSetupClientTest {

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

    private fun url() = "http://127.0.0.1:${server.port}/opds"

    private fun connect(): SetupResult = runBlocking {
        OpdsSetupClient().connect(url(), RemoteCredentials.Anonymous, allowHttp = true)
    }

    private fun feed(title: String = "The Shelf") = MockResponse(
        code = 200,
        headers = Headers.headersOf("Content-Type", "application/atom+xml"),
        body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>$title</title></feed>""",
    )

    @Test
    fun `an address answering with a feed is a catalog`() {
        server.enqueue(feed())

        val result = connect() as SetupResult.Success

        assertEquals("The Shelf", result.capabilities.displayName)
        assertTrue(result.capabilities.canDownload)
    }

    @Test
    fun `a catalog that has not named itself is called after its host`() {
        server.enqueue(
            MockResponse(code = 200, body = """<feed xmlns="http://www.w3.org/2005/Atom"/>"""),
        )

        assertEquals("127.0.0.1", (connect() as SetupResult.Success).capabilities.displayName)
    }

    @Test
    fun `a shelf of shelves is downloadable, because the books are a walk away`() {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    <feed xmlns="http://www.w3.org/2005/Atom"><title>Root</title>
                    <entry><id>nav</id><title>Fiction</title>
                    <link rel="subsection" href="/opds/fiction"
                          type="application/atom+xml;profile=opds-catalog"/></entry></feed>
                """.trimIndent(),
            ),
        )

        assertTrue((connect() as SetupResult.Success).capabilities.canDownload)
    }

    @Test
    fun `a web page that answers happily is not a catalog`() {
        server.enqueue(MockResponse(code = 200, body = "<html><body>Please sign in</body></html>"))

        assertEquals(SetupFailure.WrongServer, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `a refused sign-in is reported as one, not as the wrong address`() {
        server.enqueue(MockResponse(code = 401))

        assertEquals(SetupFailure.BadCredentials, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `a catalog that will not show itself to this reader is the same complaint`() {
        server.enqueue(MockResponse(code = 403))

        assertEquals(SetupFailure.BadCredentials, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `nothing at that path is the wrong address`() {
        server.enqueue(MockResponse(code = 404))

        assertEquals(SetupFailure.WrongServer, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `the address that answered is what gets stored`() {
        // Not the one that was typed. A root that redirected once will
        // redirect on every refresh otherwise, and the origin rule would
        // be reasoning about an address nothing uses.
        server.enqueue(
            MockResponse(code = 302, headers = Headers.headersOf("Location", "/opds/v1.2")),
        )
        server.enqueue(feed())

        assertEquals(
            "http://127.0.0.1:${server.port}/opds/v1.2",
            (connect() as SetupResult.Success).capabilities.baseUrl,
        )
    }

    @Test
    fun `an address that is not a web address at all is refused before anything is sent`() {
        val result = runBlocking {
            OpdsSetupClient().connect("not an address", RemoteCredentials.Anonymous, true)
        }

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
    }
}
