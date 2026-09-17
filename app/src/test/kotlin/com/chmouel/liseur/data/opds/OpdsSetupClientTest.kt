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
        server.enqueue(MockResponse(code = 200, body = "<html><body>Please sign in</body></html>"))

        assertEquals(SetupFailure.WrongServer, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `a refused sign-in is reported as one, not as the wrong address`() {
        server.enqueue(MockResponse(code = 401))
        // The other spelling of the address is tried before the refusal
        // is believed, so both have to answer.
        server.enqueue(MockResponse(code = 401))

        val result = runBlocking {
            OpdsSetupClient().connect(
                url(),
                RemoteCredentials.Basic("reader", "secret"),
                allowHttp = true,
            )
        }

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a catalog that will not show itself to this reader is the same complaint`() {
        server.enqueue(MockResponse(code = 403))
        server.enqueue(MockResponse(code = 403))

        val result = runBlocking {
            OpdsSetupClient().connect(
                url(),
                RemoteCredentials.Basic("reader", "secret"),
                allowHttp = true,
            )
        }

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a refusal of a request nobody signed does not blame a password`() {
        // Both fields were left empty, which is how an open catalog is
        // connected to. Naming a username the reader never gave sends
        // them looking for a mistake they did not make (#219).
        server.enqueue(MockResponse(code = 403))
        server.enqueue(MockResponse(code = 403))

        assertEquals(SetupFailure.SignInRequired, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `nothing at that path is the wrong address`() {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 404))

        assertEquals(SetupFailure.WrongServer, (connect() as SetupResult.Failure).reason)
    }

    @Test
    fun `the trailing slash an address was typed with is sent and kept`() {
        // `…/search.opds/` and `…/search.opds` are two resources, and
        // Project Gutenberg answers 200 to the first and 403 to the
        // second. Trimming the slash turned an open catalog into a
        // refused sign-in (#219).
        server.enqueue(feed())

        val result = runBlocking {
            OpdsSetupClient().connect("${url()}/", RemoteCredentials.Anonymous, allowHttp = true)
        } as SetupResult.Success

        assertEquals("/opds/", server.takeRequest().target)
        assertEquals("http://127.0.0.1:${server.port}/opds/", result.capabilities.baseUrl)
    }

    @Test
    fun `an address refused without its slash is tried again with one`() {
        // Catalogs publish both spellings and answer to one, and the
        // reader copying an address cannot be expected to know which.
        server.enqueue(MockResponse(code = 403))
        server.enqueue(feed())

        val result = connect() as SetupResult.Success

        assertEquals("/opds", server.takeRequest().target)
        assertEquals("/opds/", server.takeRequest().target)
        assertEquals("http://127.0.0.1:${server.port}/opds/", result.capabilities.baseUrl)
    }

    @Test
    fun `and an address refused with its slash is tried again without`() {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(feed())

        val result = runBlocking {
            OpdsSetupClient().connect("${url()}/", RemoteCredentials.Anonymous, allowHttp = true)
        } as SetupResult.Success

        assertEquals("/opds/", server.takeRequest().target)
        assertEquals("/opds", server.takeRequest().target)
        assertEquals("http://127.0.0.1:${server.port}/opds", result.capabilities.baseUrl)
    }

    @Test
    fun `an address nothing answered is not a spelling mistake`() {
        // No second guess: retrying would double the wait before the
        // offer to try plain HTTP, which is what that case is for.
        val dead = MockWebServer()
        dead.start(InetAddress.getByName("127.0.0.1"), 0)
        val port = dead.port
        dead.close()

        val result = runBlocking {
            OpdsSetupClient().connect(
                "http://127.0.0.1:$port/opds",
                RemoteCredentials.Anonymous,
                allowHttp = true,
            )
        }

        assertTrue((result as SetupResult.Failure).reason is SetupFailure.Unreachable)
    }

    @Test
    fun `a bare host has no second spelling to try`() {
        // `http://host` and `http://host/` are one request. A second
        // attempt at it is not a guess, only a repeat.
        server.enqueue(MockResponse(code = 404))

        val result = runBlocking {
            OpdsSetupClient().connect(
                "http://127.0.0.1:${server.port}",
                RemoteCredentials.Anonymous,
                allowHttp = true,
            )
        }

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
        assertEquals(1, server.requestCount)
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

    @Test
    fun `a catalog that answers setup by pointing elsewhere is refused`() {
        // The redirect destination would be stored as the catalog, and
        // therefore as the origin the password is signed to on every
        // later refresh. The first request was safe; the second would
        // not be.
        val elsewhere = MockWebServer()
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = Headers.headersOf(
                        "Location",
                        "http://127.0.0.1:${elsewhere.port}/opds",
                    ),
                ),
            )
            elsewhere.enqueue(feed())

            val result = runBlocking {
                OpdsSetupClient().connect(
                    url(),
                    RemoteCredentials.Basic("reader", "secret"),
                    allowHttp = true,
                )
            }

            assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `an anonymous catalog may still be moved`() {
        // Nothing is being handed out, so following the redirect costs
        // the reader nothing and spares them retyping the address.
        val elsewhere = MockWebServer()
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = Headers.headersOf(
                        "Location",
                        "http://127.0.0.1:${elsewhere.port}/opds",
                    ),
                ),
            )
            elsewhere.enqueue(feed("Moved"))

            val result = connect() as SetupResult.Success

            assertEquals("Moved", result.capabilities.displayName)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `two shelves of one catalog stay two addresses`() {
        // `?shelf=…` is how catalogs commonly pick a shelf. Trimmed off
        // here, both would be stored as one connection.
        server.enqueue(feed())

        val result = runBlocking {
            OpdsSetupClient().connect(
                "${url()}?shelf=a",
                RemoteCredentials.Anonymous,
                allowHttp = true,
            )
        } as SetupResult.Success

        assertTrue(result.capabilities.baseUrl.endsWith("?shelf=a"))
    }
}
