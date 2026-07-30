package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The setup client against a real socket.
 *
 * MockWebServer only speaks plain HTTP here, which is exactly the shape of
 * the case these tests care about: a self-hosted calibre-web with no
 * certificate, where the app has to try HTTPS, fail, and come back.
 */
class CalibreSetupClientTest {

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

    /** What the user typed: a host and port, no scheme. */
    private fun typedAddress() = "127.0.0.1:${server.port}"

    private fun reader(password: String) = RemoteCredentials.Basic("reader", password)

    private fun enqueueCatalog() {
        server.enqueue(MockResponse(body = OPDS_FEED))
    }

    @Test
    fun `offers plain http when https cannot be reached`() = runBlocking {
        val result = CalibreSetupClient().connect(typedAddress(), reader("secret"))

        val failure = (result as SetupResult.Failure).reason
        assertTrue(failure is SetupFailure.Unreachable)
        assertTrue((failure as SetupFailure.Unreachable).httpMayWork)
    }

    @Test
    fun `connects over plain http once the user allows it`() = runBlocking {
        // The catalog probe, then the download-rights probe's book feed.
        enqueueCatalog()
        server.enqueue(MockResponse(code = 404))

        val result = CalibreSetupClient().connect(
            typedAddress(),
            reader("secret"),
            allowHttp = true,
        )

        val success = result as SetupResult.Success
        assertEquals("http://${typedAddress()}", success.capabilities.baseUrl)
    }

    @Test
    fun `does not downgrade a server that answers over https`() = runBlocking {
        // A saved account refreshes with allowHttp on, so an https server
        // that is merely slow must not end up saved as http.
        enqueueCatalog()
        server.enqueue(MockResponse(code = 404))

        val result = CalibreSetupClient().connect(
            "http://${typedAddress()}",
            reader("secret"),
            allowHttp = true,
        )

        val success = result as SetupResult.Success
        assertEquals("http://${typedAddress()}", success.capabilities.baseUrl)
        // The URL was taken at its word: no https attempt was ever made.
        assertEquals("/opds", server.takeRequest().target)
    }

    @Test
    fun `reports bad credentials rather than offering http`() = runBlocking {
        server.enqueue(MockResponse(code = 401))

        val result = CalibreSetupClient().connect(
            "http://${typedAddress()}",
            reader("wrong"),
        )

        assertEquals(
            SetupFailure.BadCredentials,
            (result as SetupResult.Failure).reason,
        )
    }

    private companion object {
        val OPDS_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>calibre-web</title>
            </feed>
        """.trimIndent()
    }
}
