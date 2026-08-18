package com.chmouel.liseur.data.liseursync

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
                    "library-upload"],
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
        assertEquals("ada", capabilities.displayName)

        // The password went to the login route and nowhere else, and
        // the mint asked for every reader-facing scope in one token.
        val login = server.takeRequest()
        assertTrue(login.target!!.endsWith("/v1/login"))
        val minted = server.takeRequest()
        assertTrue(minted.target!!.endsWith("/v1/tokens"))
        val body = JSONObject(minted.body!!.utf8())
        val asked = body.getJSONArray("scopes")
        assertEquals(5, asked.length())
        assertEquals(
            setOf(
                "sync", "read-insights", "library-read", "library-manage",
                "library-upload",
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

    private suspend fun connect(credentials: RemoteCredentials): SetupResult =
        LiseurSyncServerSetup(deviceName = { "Test phone" })
            .connect("http://127.0.0.1:${server.port}", credentials, allowHttp = false)

    private fun json(body: String) = MockResponse(code = 200, body = body)
}
