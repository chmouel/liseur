package com.chmouel.liseur.data.remote

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Signing cover requests, and — more to the point — not signing the
 * ones that only look like cover requests.
 *
 * The dangerous case is a redirect. OkHttp carries a request's headers
 * onto the follow-up, and although it drops `Authorization` when the
 * host changes it has no way of knowing that `X-API-Key` is a secret
 * too. A server that answers a cover with a 302 elsewhere would
 * otherwise be handing the account's key to whoever it named.
 */
class RemoteAuthInterceptorTest {

    private lateinit var ours: MockWebServer
    private lateinit var theirs: MockWebServer

    @Before
    fun start() {
        ours = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        theirs = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun stop() {
        ours.close()
        theirs.close()
    }

    @Test
    fun `signs a request to our own server`() {
        ours.enqueue(MockResponse(body = "cover"))
        get(ours.url("/api/v1/books/1/thumbnail").toString())

        assertEquals(KEY, ours.takeRequest().headers[RemoteCredentials.ApiKey.HEADER])
    }

    @Test
    fun `leaves someone else's server unsigned`() {
        theirs.enqueue(MockResponse(body = "cover"))
        get(theirs.url("/api/v1/books/1/thumbnail").toString())

        assertNull(theirs.takeRequest().headers[RemoteCredentials.ApiKey.HEADER])
    }

    @Test
    fun `a redirect to another origin does not carry the api key`() {
        ours.enqueue(redirectTo(theirs.url("/stolen").toString()))
        theirs.enqueue(MockResponse(body = "cover"))

        get(ours.url("/api/v1/books/1/thumbnail").toString())

        assertEquals(KEY, ours.takeRequest().headers[RemoteCredentials.ApiKey.HEADER])
        val followed = theirs.takeRequest()
        assertNull(followed.headers[RemoteCredentials.ApiKey.HEADER])
        assertNull(followed.headers["Authorization"])
    }

    @Test
    fun `a redirect to another origin does not carry a password either`() {
        ours.enqueue(redirectTo(theirs.url("/stolen").toString()))
        theirs.enqueue(MockResponse(body = "cover"))

        get(
            url = ours.url("/api/v1/books/1/thumbnail").toString(),
            credentials = RemoteCredentials.Basic("reader", "hunter2"),
        )

        assertNull(theirs.takeRequest().headers["Authorization"])
        assertNull(ours.takeRequest().headers[RemoteCredentials.ApiKey.HEADER])
    }

    @Test
    fun `a redirect within our own server stays signed`() {
        ours.enqueue(redirectTo(ours.url("/api/v1/books/1/thumbnail/small").toString()))
        ours.enqueue(MockResponse(body = "cover"))

        get(ours.url("/api/v1/books/1/thumbnail").toString())

        ours.takeRequest()
        assertEquals(KEY, ours.takeRequest().headers[RemoteCredentials.ApiKey.HEADER])
    }

    @Test
    fun `a header the caller set itself is not trusted`() {
        theirs.enqueue(MockResponse(body = "cover"))

        val client = RemoteAuthInterceptor.imageLoaderClient { url ->
            RemoteCredentials.ApiKey(KEY).takeIf { url.startsWith(ours.url("/").toString()) }
        }
        client.newCall(
            Request.Builder()
                .url(theirs.url("/cover"))
                .header(RemoteCredentials.ApiKey.HEADER, KEY)
                .build(),
        ).execute().close()

        assertNull(theirs.takeRequest().headers[RemoteCredentials.ApiKey.HEADER])
    }

    private fun redirectTo(location: String) =
        MockResponse(code = 302, headers = Headers.headersOf("Location", location))

    private fun get(
        url: String,
        credentials: RemoteCredentials = RemoteCredentials.ApiKey(KEY),
    ) {
        val origin = requireNotNull(RemoteOrigin.of(ours.url("/").toString()))
        val client = RemoteAuthInterceptor.imageLoaderClient { requested ->
            credentials.takeIf { origin.covers(requested) }
        }
        client.newCall(Request.Builder().url(url).build()).execute().close()
    }

    private companion object {
        const val KEY = "a-secret-api-key"
    }
}
