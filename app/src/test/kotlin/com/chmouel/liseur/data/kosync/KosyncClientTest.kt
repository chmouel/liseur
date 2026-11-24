package com.chmouel.liseur.data.kosync

import com.chmouel.liseur.data.remote.RemoteResult
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What one push puts on the wire.
 *
 * kosync's `progress` field is what a KOReader-engine reader opens: a CRe
 * XPath when Liseur could derive one, the percentage otherwise. The
 * percentage is always sent as the number the protocol reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KosyncClientTest {

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

    private suspend fun put(progress: String?, percentage: Double = 0.4): JSONObject {
        server.enqueue(MockResponse(code = 200, body = """{"message":"Progress updated"}"""))
        val result = KosyncClient().putProgress(
            rootUrl = "http://127.0.0.1:${server.port}",
            credentials = KosyncCredentials("ada", KosyncCredentials.keyFor("pw")),
            document = DOCUMENT,
            percentage = percentage,
            progress = progress,
            device = "Test Phone",
            deviceId = "dev-1",
            timestampMs = NOW,
        )
        assertTrue("$result", result is RemoteResult.Ok)
        return JSONObject(server.takeRequest().body!!.utf8())
    }

    @Test
    fun `an exact position is the progress string`() = runTest {
        val sent = put(progress = "/body/DocFragment[3]/body/p[4]/text()[1].17")

        assertEquals("/body/DocFragment[3]/body/p[4]/text()[1].17", sent.getString("progress"))
        assertEquals(0.4, sent.getDouble("percentage"), 0.0)
        assertEquals(DOCUMENT, sent.getString("document"))
        assertEquals(NOW / 1000, sent.getLong("timestamp"))
    }

    @Test
    fun `without one the percentage is the progress string`() = runTest {
        val sent = put(progress = null)

        assertEquals("0.4", sent.getString("progress"))
        assertEquals(0.4, sent.getDouble("percentage"), 0.0)
    }

    @Test
    fun `the percentage sent as a number is clamped`() = runTest {
        val sent = put(progress = null, percentage = 1.4)

        assertEquals("1.0", sent.getString("progress"))
        assertEquals(1.0, sent.getDouble("percentage"), 0.0)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DOCUMENT = "0123456789abcdef0123456789abcdef"
    }
}
