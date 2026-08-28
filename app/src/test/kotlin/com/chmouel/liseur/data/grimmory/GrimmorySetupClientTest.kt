package com.chmouel.liseur.data.grimmory

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
 * The Grimmory setup client against a real socket.
 *
 * MockWebServer speaks plain HTTP, which is the case worth covering: a
 * self-hosted Grimmory on a home network, where the app tries HTTPS,
 * fails, and comes back only because the reader said it could.
 */
class GrimmorySetupClientTest {

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

    private val opdsUser = RemoteCredentials.Basic("liseur-opds", "opds-secret")

    /** What a real Grimmory v3.3.3 answers `/komga/api/v2/users/me` with. */
    private fun enqueueMe(id: String = "1") {
        server.enqueue(
            MockResponse(
                body = """
                {"id":"$id","email":"liseur-opds@grimmory.local","roles":["USER"],
                 "sharedAllLibraries":true,"labelsAllow":[],"labelsExclude":[]}
                """.trimIndent(),
            ),
        )
    }

    /**
     * Addressed as `http://` on purpose: HTTPS is always tried first, so
     * anything else spends the enqueued response on a handshake against
     * a socket that speaks none.
     */
    private fun connect(
        url: String = "http://${address()}",
        credentials: RemoteCredentials = opdsUser,
    ): SetupResult = runBlocking {
        GrimmorySetupClient().connect(url, credentials, allowHttp = true)
    }

    @Test
    fun `an opds user signs in over plain http once it is allowed`() {
        enqueueMe()

        val result = runBlocking {
            GrimmorySetupClient().connect(address(), opdsUser, allowHttp = true)
        }

        val capabilities = (result as SetupResult.Success).capabilities
        assertEquals("http://${address()}", capabilities.baseUrl)
        assertEquals("1", capabilities.accountId)
    }

    @Test
    fun `the probe goes to the shim and carries basic auth`() {
        enqueueMe()

        connect()

        val request = server.takeRequest()
        assertEquals("/komga/api/v2/users/me", request.target)
        // The bare /api/v2/users/me is Grimmory's own API, which refuses
        // an OPDS user with a 401 -- and one 401 ends the candidate walk
        // with the password blamed for what is really the address.
        assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
    }

    @Test
    fun `an api key is not a credential this server has any use for`() {
        // Grimmory has no API key mechanism anywhere. Sending one would
        // only produce a puzzling 401.
        val result = connect(credentials = RemoteCredentials.ApiKey("a-key"))

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `downloads are offered even though the shim reports no such role`() {
        // Grimmory hardcodes every account to `["USER"]`, so gating on
        // FILE_DOWNLOAD the way the Komga client does would refuse every
        // download on a server that in fact serves them.
        enqueueMe()

        val result = connect()

        assertTrue((result as SetupResult.Success).capabilities.canDownload)
    }

    @Test
    fun `the reader is named as they typed rather than as grimmory spells it`() {
        // The DTO carries `liseur-opds@grimmory.local`, an address that
        // exists nowhere and belongs on no screen.
        enqueueMe()

        val result = connect()

        assertEquals("liseur-opds", (result as SetupResult.Success).capabilities.displayName)
    }

    @Test
    fun `something that answers but is not the shim is not a server`() {
        // A 200 proves nothing; a landing page is a 200. Only the shim
        // answers this route with an account carrying roles.
        server.enqueue(MockResponse(body = """{"hello":"world"}"""))

        val result = connect()

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a refused sign-in is reported as such`() {
        server.enqueue(MockResponse(code = 401))

        val result = connect()

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a rejection from a deeper path does not stand for the whole host`() {
        // A pasted address is walked up its parents, and what sits on
        // the deeper one need not be Grimmory at all -- a proxy with its
        // own password answers 401 to credentials that are perfectly
        // good one level up. Stopping there blames the reader for an
        // address that had not been tried yet.
        server.enqueue(MockResponse(code = 401))
        enqueueMe()

        val result = connect(url = "http://${address()}/private")

        assertEquals("http://${address()}", (result as SetupResult.Success).capabilities.baseUrl)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a rejection is still what gets reported when nothing answers`() {
        // Both candidates fail, one because the password was refused and
        // one because there is nothing there. The refusal is the more
        // useful of the two to show, whichever order they arrive in.
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 404))

        val result = connect(url = "http://${address()}/private")

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `the shim being switched off arrives as a refused sign-in`() {
        // Grimmory's interceptor answers 403 with a body naming no
        // cause, so this is indistinguishable from a bad password on the
        // wire. The wording shown to the reader has to offer both.
        server.enqueue(MockResponse(code = 403))

        val result = connect()

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `an address with no shim behind it is not blamed on the password`() {
        server.enqueue(MockResponse(code = 404))

        val result = connect()

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `an address that is not one is refused before any request`() {
        val result = connect(url = "  ")

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `plain http is offered rather than taken`() {
        val result = runBlocking {
            GrimmorySetupClient().connect(address(), opdsUser, allowHttp = false)
        }

        val reason = (result as SetupResult.Failure).reason
        assertTrue(reason is SetupFailure.Unreachable && reason.httpMayWork)
    }

    @Test
    fun `a server pasted at its shim connects to the same root`() {
        // The candidate walk tries the address as typed first, which
        // doubles the prefix and 404s, then its parent, which is the
        // root. The `/komga` segment is not stripped on the way in: a
        // Grimmory genuinely proxied under it has to stay reachable,
        // and only the server can say which of the two this is.
        server.enqueue(MockResponse(code = 404))
        enqueueMe()

        val result = connect(url = "http://${address()}/komga")

        assertEquals("http://${address()}", (result as SetupResult.Success).capabilities.baseUrl)
        assertEquals("/komga/komga/api/v2/users/me", server.takeRequest().target)
        assertEquals("/komga/api/v2/users/me", server.takeRequest().target)
    }

    /**
     * A schemeless address is tried over HTTPS first and only then over
     * plain HTTP, so the answer that matters is the second one. Reading
     * the first would report a refused password as a server that "did
     * not answer securely" and send the reader to fix the wrong thing.
     */
    @Test
    fun `a refused password over plain http is not reported as an https problem`() {
        server.enqueue(MockResponse(code = 401))

        val result = runBlocking {
            GrimmorySetupClient().connect(address(), opdsUser, allowHttp = true)
        }

        assertEquals("$result", SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    /** Same, for the 403 a switched-off Komga API arrives as. */
    @Test
    fun `a switched off shim over plain http is not reported as an https problem`() {
        server.enqueue(MockResponse(code = 403))

        val result = runBlocking {
            GrimmorySetupClient().connect(address(), opdsUser, allowHttp = true)
        }

        assertEquals("$result", SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }
}
