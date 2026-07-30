package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * What a refused or broken catalog request comes out as.
 *
 * These are here rather than at the repository because that is where the
 * bug was: every one of these used to leave the client as a plain
 * `IOException`, and the library dutifully reported the lot as "you are
 * offline" -- including a password the server had just told us it did
 * not like.
 */
class CalibreCatalogClientTest {

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

    private fun baseUrl() = "http://127.0.0.1:${server.port}"

    private val credentials = RemoteCredentials.Basic("reader", "secret")

    private fun reasonFor(response: MockResponse): SyncFailure {
        server.enqueue(response)
        return try {
            runBlocking { CalibreCatalogClient().allBooks(baseUrl(), credentials) }
            fail("the client accepted a response it should have refused")
            error("unreachable")
        } catch (e: RemoteHttpFailure) {
            e.reason
        }
    }

    @Test
    fun `a refused sign-in is not mistaken for a missing network`() {
        assertEquals(SyncFailure.Unauthorised, reasonFor(MockResponse(code = 401)))
    }

    @Test
    fun `an account without permission says so`() {
        assertEquals(SyncFailure.Forbidden, reasonFor(MockResponse(code = 403)))
    }

    @Test
    fun `nothing at that address is its own answer`() {
        assertEquals(SyncFailure.NotFound, reasonFor(MockResponse(code = 404)))
    }

    @Test
    fun `a server in trouble is reported with its code`() {
        assertEquals(SyncFailure.ServerError(503), reasonFor(MockResponse(code = 503)))
    }

    /**
     * A login page where a feed was expected. Not XML, so this throws a
     * `SAXException` -- which is not an `IOException`, so before this it
     * escaped the refresh entirely and left the library spinning for
     * good.
     */
    @Test
    fun `an answer that is not a feed does not escape as an unhandled error`() {
        assertEquals(
            SyncFailure.Malformed,
            reasonFor(MockResponse(body = "<!DOCTYPE html><html><body>Sign in")),
        )
    }
}
