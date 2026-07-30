package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Komga setup client against a real socket.
 *
 * MockWebServer only speaks plain HTTP here, which is exactly the shape
 * of the case these tests care about: a self-hosted Komga with no
 * certificate, where the app has to try HTTPS, fail, and come back.
 */
class KomgaSetupClientTest {

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

    private fun address() = "127.0.0.1:${server.port}"

    private val key = RemoteCredentials.ApiKey("a-key")

    private fun enqueueMe(vararg roles: String, id: String = "0R571X1D6ZEDC") {
        server.enqueue(
            MockResponse(
                body = """
                {"id":"$id","email":"reader@example.com",
                 "roles":[${roles.joinToString(",") { "\"$it\"" }}],
                 "sharedAllLibraries":true}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `offers plain http when https cannot be reached`() = runBlocking {
        val result = KomgaSetupClient().connect(address(), key)

        val failure = (result as SetupResult.Failure).reason
        assertTrue(failure is SetupFailure.Unreachable)
        assertTrue((failure as SetupFailure.Unreachable).httpMayWork)
    }

    @Test
    fun `connects over plain http once the user allows it`() = runBlocking {
        enqueueMe("USER", "FILE_DOWNLOAD")

        val result = KomgaSetupClient().connect(address(), key, allowHttp = true)

        val success = result as SetupResult.Success
        assertEquals("http://${address()}", success.capabilities.baseUrl)
        assertEquals("0R571X1D6ZEDC", success.capabilities.accountId)
        assertEquals("reader@example.com", success.capabilities.displayName)
        assertTrue(success.capabilities.canDownload)
    }

    @Test
    fun `an account without the download role is connected but cannot fetch files`() = runBlocking {
        enqueueMe("USER")

        val result = KomgaSetupClient().connect("http://${address()}", key)

        assertFalse((result as SetupResult.Success).capabilities.canDownload)
    }

    @Test
    fun `the api key travels in the header komga reads`() = runBlocking {
        enqueueMe("USER")

        KomgaSetupClient().connect("http://${address()}", key)

        assertEquals("a-key", server.takeRequest().headers["X-API-Key"])
    }

    @Test
    fun `a refused key is reported as such rather than as a wrong address`() = runBlocking {
        server.enqueue(MockResponse(code = 401))

        val result = KomgaSetupClient().connect("http://${address()}", key)

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `something that is not komga is not mistaken for it`() = runBlocking {
        // A 200 with no roles: any web server can manage that much.
        server.enqueue(MockResponse(body = """{"hello":"world"}"""))

        val result = KomgaSetupClient().connect("http://${address()}", key)

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `the address copied from komga's own api key page still connects`() = runBlocking {
        // The web interface serves its own HTML for any route it does not
        // know, so the first candidate comes back as a page, not JSON.
        server.enqueue(MockResponse(body = "<!doctype html><html></html>"))
        server.enqueue(MockResponse(body = "<!doctype html><html></html>"))
        enqueueMe("USER", "FILE_DOWNLOAD")

        val result = KomgaSetupClient()
            .connect("http://${address()}/account/api-keys", key)

        val success = result as SetupResult.Success
        assertEquals("http://${address()}", success.capabilities.baseUrl)
        assertEquals("/account/api-keys/api/v2/users/me", server.takeRequest().target)
        assertEquals("/account/api/v2/users/me", server.takeRequest().target)
        assertEquals("/api/v2/users/me", server.takeRequest().target)
    }

    @Test
    fun `a reverse proxied server is found under its prefix`() = runBlocking {
        enqueueMe("USER")

        val result = KomgaSetupClient().connect("http://${address()}/komga", key)

        assertEquals("http://${address()}/komga", (result as SetupResult.Success).capabilities.baseUrl)
        assertEquals("/komga/api/v2/users/me", server.takeRequest().target)
    }
}
