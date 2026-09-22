package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.PriorConnection
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
 * Signing in to BookOrbit over a real socket.
 *
 * MockWebServer speaks plain HTTP here, which is exactly the case that
 * matters for a self-hosted server with no certificate: the app has to
 * try HTTPS, fail, and offer the other scheme.
 */
class BookOrbitSetupClientTest {

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

    /** A typed plain-HTTP address, so a test is not also a TLS probe. */
    private fun httpAddress() = "http://${address()}"

    private fun loginBody(
        id: Long = 1,
        username: String = "reader",
        name: String = "A Reader",
        permissions: List<String> = listOf("*"),
    ) = """
        {"accessToken":"access-one","accessTokenExpiresAt":"2099-01-01T00:00:00.000Z",
         "refreshToken":"refresh-one","refreshTokenExpiresAt":"2099-01-08T00:00:00.000Z",
         "sessionId":5,
         "user":{"id":$id,"username":"$username","name":"$name",
           "permissions":[${permissions.joinToString(",") { "\"$it\"" }}]}}
    """.trimIndent()

    private fun enqueue(status: Int = 200, body: String = "") =
        server.enqueue(MockResponse(code = status, body = body))

    private val password = RemoteCredentials.Basic("reader", "hunter2")

    @Test
    fun `offers plain http when https cannot be reached`() = runBlocking {
        val result = BookOrbitSetupClient().connect(address(), password)

        val failure = (result as SetupResult.Failure).reason
        assertTrue(failure is SetupFailure.Unreachable)
        assertTrue((failure as SetupFailure.Unreachable).httpMayWork)
    }

    @Test
    fun `signs in over plain http and reads the permissions`() = runBlocking {
        enqueue(body = loginBody(permissions = listOf("library_download", "library_edit_metadata")))
        server.enqueue(MockResponse(body = """{"version":"v3.0.0"}"""))

        val result = BookOrbitSetupClient().connect(httpAddress(), password)

        val capabilities = (result as SetupResult.Success).capabilities
        assertEquals(httpAddress(), capabilities.baseUrl)
        assertEquals("1", capabilities.accountId)
        assertEquals("A Reader", capabilities.displayName)
        assertTrue(capabilities.canDownload)
        assertTrue(capabilities.canManageLibrary)
        assertFalse(capabilities.canUpload)
        assertFalse(capabilities.canDelete)
        assertEquals("access-one", capabilities.orbitAccessToken)
        assertEquals("refresh-one", capabilities.orbitRefreshToken)

        val login = server.takeRequest()
        assertEquals("/api/v1/auth/login", login.url.encodedPath)
        assertTrue(login.body!!.utf8().contains("\"clientKind\":\"native\""))
    }

    @Test
    fun `a superuser's star is every permission`() = runBlocking {
        enqueue(body = loginBody(permissions = listOf("*")))

        val result = BookOrbitSetupClient().connect(httpAddress(), password)

        val capabilities = (result as SetupResult.Success).capabilities
        assertTrue(capabilities.canDownload)
        assertTrue(capabilities.canUpload)
        assertTrue(capabilities.canDelete)
    }

    @Test
    fun `a refused password is the reader's mistake, not the address's`() = runBlocking {
        enqueue(status = 401, body = """{"message":"Invalid credentials"}""")

        val result = BookOrbitSetupClient().connect(httpAddress(), password)

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a throttled sign-in is reported as one and not retried`() = runBlocking {
        enqueue(status = 429, body = """{"message":"Too Many Requests"}""")

        val result = BookOrbitSetupClient().connect(httpAddress(), password)

        assertEquals(SetupFailure.RateLimited, (result as SetupResult.Failure).reason)
        // One attempt: retrying a rate-limited sign-in is how an account
        // gets locked.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 200 that is not BookOrbit is not a connection`() = runBlocking {
        enqueue(body = """{"hello":"world"}""")

        val result = BookOrbitSetupClient().connect(httpAddress(), password)

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a native login without a renewable token is malformed`() = runBlocking {
        enqueue(
            body = """
                {"accessToken":"access-one","accessTokenExpiresAt":"2099-01-01T00:00:00.000Z",
                 "sessionId":5,"user":{"id":1,"username":"reader","permissions":["*"]}}
            """.trimIndent(),
        )

        val result = BookOrbitSetupClient().connect(httpAddress(), password)

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
    }

    @Test
    fun `a reconnect without the live session cannot spend a refresh token`() = runBlocking {
        // The real composition root gives SetupClient the shared
        // BookOrbitSession. A standalone setup client must refuse rather
        // than rotate the token behind that session's back.
        val result = BookOrbitSetupClient().reconnect(
            rawUrl = httpAddress(),
            credentials = RemoteCredentials.Deferred,
            allowHttp = true,
            prior = PriorConnection(baseUrl = httpAddress(), deviceId = null, refreshToken = "refresh-one"),
        )

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a refresh token is never spent against another address`() = runBlocking {
        // The prior connection names somebody else's server, so the only
        // credential offered is Deferred and there is nothing to sign
        // with. It must not be sent anywhere.
        val result = BookOrbitSetupClient().reconnect(
            rawUrl = httpAddress(),
            credentials = RemoteCredentials.Deferred,
            allowHttp = true,
            prior = PriorConnection(
                baseUrl = "127.0.0.1:1",
                deviceId = null,
                refreshToken = "refresh-one",
            ),
        )

        assertEquals(SetupFailure.BadCredentials, (result as SetupResult.Failure).reason)
        assertEquals(0, server.requestCount)
    }
}
