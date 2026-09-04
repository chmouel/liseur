package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.remote.PriorConnection
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Getting onto a liseur-sync server.
 *
 * The two things that matter most: a password must never outlive the
 * token it buys, and a pasted token must be believed about what it may
 * do — the buttons the app offers are drawn from that answer.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncServerSetupTest {

    private lateinit var server: MockWebServer

    @Before
    fun open() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun close() {
        server.close()
    }

    @Test
    fun `a password buys one device token with the full scope set`() = runTest {
        server.enqueue(json("""{"auth_token":"hour-token"}"""))
        server.enqueue(
            json(
                """{"token_id":"t-1","device_id":"d-1","scopes":["sync","read-insights",
                    "library-read","library-manage"],"secret":"device-secret"}
                """.trimIndent(),
            ),
        )
        // The fresh token is then asked what it holds — one code path
        // reads scopes, device and account identity for both ways in.
        server.enqueue(
            json(
                """{"id":"t-1","device_id":"d-1","name":"Test phone",
                    "scopes":["sync","read-insights","library-read","library-manage",
                    "library-upload","library-delete"],
                    "account_id":"acc-9"}
                """.trimIndent(),
            ),
        )

        val result = connect(RemoteCredentials.Basic("ada", "correct"))

        val capabilities = (result as SetupResult.Success).capabilities
        assertEquals("device-secret", capabilities.liseurToken)
        assertEquals("d-1", capabilities.accountId)
        assertEquals("acc-9", capabilities.liseurAccountId)
        assertTrue(capabilities.canDownload)
        assertTrue(capabilities.canManageLibrary)
        assertTrue(capabilities.canUpload)
        assertTrue(capabilities.canDelete)
        assertTrue(capabilities.canReadInsights)
        assertEquals("ada", capabilities.displayName)

        // The password went to the login route and nowhere else, and
        // the mint asked for every reader-facing scope in one token.
        val login = server.takeRequest()
        assertTrue(login.target!!.endsWith("/v1/login"))
        val minted = server.takeRequest()
        assertTrue(minted.target!!.endsWith("/v1/tokens"))
        val body = JSONObject(minted.body!!.utf8())
        val asked = body.getJSONArray("scopes")
        assertEquals(6, asked.length())
        assertEquals(
            setOf(
                "sync", "read-insights", "library-read", "library-manage",
                "library-upload", "library-delete",
            ),
            (0 until asked.length()).map { asked.getString(it) }.toSet(),
        )
        assertTrue(server.takeRequest().target!!.endsWith("/v1/token"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a pasted token is believed about its own scopes`() = runTest {
        server.enqueue(
            json(
                """{"id":"t-9","device_id":"d-9","name":"Boox",
                    "scopes":["sync","library-read"]}
                """.trimIndent(),
            ),
        )

        val result = connect(RemoteCredentials.Bearer("pasted-secret"))

        val capabilities = (result as SetupResult.Success).capabilities
        assertEquals("pasted-secret", capabilities.liseurToken)
        assertEquals("d-9", capabilities.accountId)
        assertTrue(capabilities.canDownload)
        // A token minted before this app asked for library-upload does
        // not get the permission by being old. The action stays hidden
        // rather than being offered and refused.
        assertFalse(capabilities.canUpload)
        // Nor does it get to read statistics by being old (ADR-0021).
        // Recorded as a fact rather than found out one 403 at a time on
        // a screen that says nothing about it.
        assertFalse(capabilities.canReadInsights)
        val asked = server.takeRequest()
        assertTrue(asked.target!!.endsWith("/v1/token"))
        assertEquals("Bearer pasted-secret", asked.headers["Authorization"])
    }

    @Test
    fun `a token that cannot sync is not an account`() = runTest {
        server.enqueue(json("""{"id":"t-9","device_id":"d-9","name":"x","scopes":["library-read"]}"""))

        val result = connect(RemoteCredentials.Bearer("pasted-secret"))

        assertTrue(
            (result as SetupResult.Failure).reason is SetupFailure.InsufficientScopes,
        )
    }

    @Test
    fun `admin scope implies library-manage and library-read`() = runTest {
        server.enqueue(
            json(
                """{"id":"t-a","device_id":"d-a","name":"Admin",
                    "scopes":["sync","admin"]}
                """.trimIndent(),
            ),
        )

        val result = connect(RemoteCredentials.Bearer("admin-token"))

        val capabilities = (result as SetupResult.Success).capabilities
        assertTrue(capabilities.canAdmin)
        assertTrue(capabilities.canManageLibrary)
        assertTrue(capabilities.canDownload)
        assertTrue(capabilities.canReadInsights)
    }

    @Test
    fun `library-manage scope implies library-read`() = runTest {
        server.enqueue(
            json(
                """{"id":"t-m","device_id":"d-m","name":"Manager",
                    "scopes":["sync","library-manage"]}
                """.trimIndent(),
            ),
        )

        val result = connect(RemoteCredentials.Bearer("manage-token"))

        val capabilities = (result as SetupResult.Success).capabilities
        assertTrue(capabilities.canManageLibrary)
        assertTrue(capabilities.canDownload)
    }

    @Test
    fun `a refused password is told apart from a wrong address`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"bad credentials"}"""))
        assertEquals(
            SetupFailure.BadCredentials,
            (connect(RemoteCredentials.Basic("ada", "wrong")) as SetupResult.Failure).reason,
        )

        server.enqueue(json("""{"unrelated":true}"""))
        assertEquals(
            SetupFailure.WrongServer,
            (connect(RemoteCredentials.Basic("ada", "correct")) as SetupResult.Failure).reason,
        )
    }

    @Test
    fun `reconnecting asks the server to keep the device id`() = runTest {
        server.enqueue(json("""{"auth_token":"hour-token"}"""))
        server.enqueue(json("""{"token_id":"t-2","device_id":"d-1","scopes":["sync"],"secret":"s-2"}"""))
        server.enqueue(json("""{"id":"t-2","device_id":"d-1","name":"Test phone","scopes":["sync"],"account_id":"acc-9"}"""))

        val result = reconnect(RemoteCredentials.Basic("ada", "correct"), PriorConnection(base(), "d-1"))

        assertEquals("d-1", (result as SetupResult.Success).capabilities.accountId)
        server.takeRequest()
        val minted = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("d-1", minted.getString("device_id"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a device id the server no longer knows is dropped and the mint asked again`() = runTest {
        server.enqueue(json("""{"auth_token":"hour-token"}"""))
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"device_id names no device of this account","code":"unknown_device","device_id":"d-old"}""",
            ),
        )
        server.enqueue(json("""{"token_id":"t-2","device_id":"d-new","scopes":["sync"],"secret":"s-2"}"""))
        server.enqueue(json("""{"id":"t-2","device_id":"d-new","name":"Test phone","scopes":["sync"]}"""))

        val result = reconnect(RemoteCredentials.Basic("ada", "correct"), PriorConnection(base(), "d-old"))

        assertEquals("d-new", (result as SetupResult.Success).capabilities.accountId)
        server.takeRequest()
        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).has("device_id"))
        assertFalse(JSONObject(server.takeRequest().body!!.utf8()).has("device_id"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `any other refused mint is not retried`() = runTest {
        server.enqueue(json("""{"auth_token":"hour-token"}"""))
        server.enqueue(MockResponse(code = 400, body = """{"error":"name required"}"""))

        val result = reconnect(RemoteCredentials.Basic("ada", "correct"), PriorConnection(base(), "d-1"))

        assertEquals(SetupFailure.WrongServer, (result as SetupResult.Failure).reason)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a device id is never offered to a different server`() = runTest {
        server.enqueue(json("""{"auth_token":"hour-token"}"""))
        server.enqueue(json("""{"token_id":"t-2","device_id":"d-x","scopes":["sync"],"secret":"s-2"}"""))
        server.enqueue(json("""{"id":"t-2","device_id":"d-x","name":"Test phone","scopes":["sync"]}"""))

        reconnect(RemoteCredentials.Basic("ada", "correct"), PriorConnection("https://elsewhere.example", "d-1"))

        server.takeRequest()
        assertFalse(JSONObject(server.takeRequest().body!!.utf8()).has("device_id"))
    }

    @Test
    fun `an older server that ignores the field is simply a new device`() = runTest {
        server.enqueue(json("""{"auth_token":"hour-token"}"""))
        server.enqueue(json("""{"token_id":"t-2","device_id":"d-fresh","scopes":["sync"],"secret":"s-2"}"""))
        server.enqueue(json("""{"id":"t-2","device_id":"d-fresh","name":"Test phone","scopes":["sync"]}"""))

        val result = reconnect(RemoteCredentials.Basic("ada", "correct"), PriorConnection(base(), "d-1"))

        assertEquals("d-fresh", (result as SetupResult.Success).capabilities.accountId)
    }

    private fun base() = "http://127.0.0.1:${server.port}"

    private suspend fun connect(credentials: RemoteCredentials): SetupResult =
        LiseurSyncServerSetup(deviceName = { "Test phone" })
            .connect(base(), credentials, allowHttp = false)

    private suspend fun reconnect(credentials: RemoteCredentials, prior: PriorConnection): SetupResult =
        LiseurSyncServerSetup(deviceName = { "Test phone" })
            .reconnect(base(), credentials, allowHttp = false, prior = prior)

    private fun json(body: String) = MockResponse(code = 200, body = body)
}
