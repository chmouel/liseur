package com.chmouel.liseur.data.liseursync

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Getting onto a liseur-sync account, against a real socket.
 *
 * The cases worth pinning down are the ones that decide what the reader
 * is told: a refused password must not read as a wrong address, an
 * instance that refuses plain HTTP must not read as a permission
 * problem, and a server that will not grant statistics must still
 * connect, because syncing positions is the point and statistics are the
 * extra.
 */
class LiseurSyncSetupClientTest {

    private lateinit var server: MockWebServer
    private val client = LiseurSyncSetupClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() = server.close()

    /** What the user typed: a host and port, no scheme. */
    private fun typedAddress() = "127.0.0.1:${server.port}"

    private fun json(code: Int, body: String) = MockResponse(code = code, body = body)

    /** The same address, spelled so no https attempt is made first. */
    private fun plainAddress() = "http://${typedAddress()}"

    private fun signIn(url: String = typedAddress(), allowHttp: Boolean = true) = runBlocking {
        client.signIn(
            rawUrl = url,
            username = "ada",
            password = "hunter2",
            deviceName = "Test Phone",
            wantInsights = true,
            allowHttp = allowHttp,
        )
    }

    @Test
    fun `signing in mints a token to keep`() {
        server.enqueue(json(200, """{"auth_token":"short-lived","expires_in":3600}"""))
        server.enqueue(json(201, """{"token_id":"t1","secret":"device-secret","scope":"sync"}"""))
        server.enqueue(json(201, """{"token_id":"t2","secret":"stats-secret"}"""))

        val result = signIn(plainAddress())

        val connection = (result as SyncSetupResult.Success).connection
        assertEquals("device-secret", connection.token)
        assertEquals("stats-secret", connection.insightsToken)
        assertEquals("ada", connection.username)

        val login = server.takeRequest()
        assertEquals("/v1/login", login.target)
        // The password buys a token and goes no further; the token
        // requests are signed with what the sign-in returned.
        val minted = server.takeRequest()
        assertEquals("Bearer short-lived", minted.headers["Authorization"])
        assertTrue(minted.body?.utf8().orEmpty().contains("\"scope\":\"sync\""))
    }

    @Test
    fun `a server that will not grant statistics still connects`() {
        server.enqueue(json(200, """{"auth_token":"short-lived"}"""))
        server.enqueue(json(201, """{"secret":"device-secret"}"""))
        server.enqueue(json(403, """{"error":"scope not allowed"}"""))

        val connection = (signIn() as SyncSetupResult.Success).connection

        assertEquals("device-secret", connection.token)
        assertNull(connection.insightsToken)
    }

    @Test
    fun `a refused password is not a wrong address`() {
        server.enqueue(json(401, """{"error":"invalid credentials"}"""))

        val result = signIn()

        assertEquals(
            SyncSetupResult.Failure(SyncSetupFailure.BadCredentials),
            result,
        )
    }

    @Test
    fun `an instance refusing plain HTTP says so instead of blaming the account`() {
        // The server states this with a 403, which otherwise reads as
        // "this account may not" and sends the reader looking for a
        // permission when what is wrong is the address.
        server.enqueue(json(403, """{"error":"https required for credentials"}"""))

        val result = signIn()

        assertEquals(
            SyncSetupResult.Failure(SyncSetupFailure.InsecureTransport),
            result,
        )
    }

    @Test
    fun `too many attempts are told apart from a wrong password`() {
        server.enqueue(json(429, """{"error":"slow down"}"""))

        assertEquals(
            SyncSetupResult.Failure(SyncSetupFailure.RateLimited),
            signIn(),
        )
    }

    @Test
    fun `something that is not liseur-sync is not mistaken for it`() {
        server.enqueue(MockResponse(body = "<html>hello</html>"))

        assertEquals(
            SyncSetupResult.Failure(SyncSetupFailure.WrongServer),
            signIn(),
        )
    }

    @Test
    fun `plain HTTP is refused until the reader allows it`() {
        // Nothing is enqueued: HTTPS against a plain-HTTP socket fails,
        // and the offer to retry has to be made rather than taken.
        val result = signIn(allowHttp = false)

        assertEquals(
            SyncSetupResult.Failure(SyncSetupFailure.Unreachable(httpMayWork = true)),
            result,
        )
    }

    @Test
    fun `a pasted token is checked against the call it exists to make`() {
        server.enqueue(json(200, """{"ops":[],"high_water":42,"has_more":false}"""))

        val result = runBlocking {
            client.verifyToken(
                rawUrl = plainAddress(),
                username = "ada",
                token = "pasted",
                deviceName = "Test Phone",
                allowHttp = true,
            )
        }

        val connection = (result as SyncSetupResult.Success).connection
        assertEquals("pasted", connection.token)
        // A token of the wrong scope would pass a liveness probe and
        // then fail at the first sync, so the check is a real sync call.
        val asked = server.takeRequest()
        assertTrue(asked.target.startsWith("/v1/changes"))
        assertEquals("Bearer pasted", asked.headers["Authorization"])
    }

    @Test
    fun `a token the server does not know is refused`() {
        server.enqueue(json(401, """{"error":"unknown token"}"""))

        val result = runBlocking {
            client.verifyToken(
                rawUrl = typedAddress(),
                username = "ada",
                token = "nonsense",
                deviceName = "Test Phone",
                allowHttp = true,
            )
        }

        assertEquals(SyncSetupResult.Failure(SyncSetupFailure.BadCredentials), result)
    }
}
