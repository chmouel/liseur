package com.chmouel.liseur.data.komga

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerKind
import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Komga authenticates with `X-API-Key`, which OkHttp does not strip on
 * cross-origin redirects. Both API calls and book downloads must refuse
 * redirects so the key never leaves for another host.
 */
class KomgaHttpTest {

    private lateinit var komga: MockWebServer
    private lateinit var attacker: MockWebServer

    @Before
    fun start() {
        komga = MockWebServer()
        komga.start(InetAddress.getByName("127.0.0.1"), 0)
        attacker = MockWebServer()
        attacker.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        komga.close()
        attacker.close()
    }

    private val credentials = RemoteCredentials.ApiKey("komga-secret-key")
    private fun komgaUrl(path: String) = "http://127.0.0.1:${komga.port}$path"
    private fun attackerUrl(path: String) = "http://127.0.0.1:${attacker.port}$path"

    @Test
    fun `api calls do not follow redirects or forward the api key`() {
        val http = KomgaHttp()
        val redirect = MockResponse(
            code = 307,
            headers = Headers.headersOf("Location", attackerUrl("/steal")),
        )

        komga.enqueue(redirect)
        assertThrows(RemoteHttpFailure::class.java) {
            http.getObject(komgaUrl("/api/v1/users/me"), credentials)
        }

        komga.enqueue(redirect)
        assertThrows(RemoteHttpFailure::class.java) {
            http.getObjectOrNull(komgaUrl("/api/v1/books/1/read-progress"), credentials)
        }

        komga.enqueue(redirect)
        assertThrows(RemoteHttpFailure::class.java) {
            http.postObject(komgaUrl("/api/v1/books/list"), credentials, JSONObject())
        }

        komga.enqueue(redirect)
        assertThrows(RemoteHttpFailure::class.java) {
            http.send(komgaUrl("/api/v1/books/1/read-progress"), credentials, "PATCH", JSONObject())
        }

        assertEquals(0, attacker.requestCount)
    }

    @Test
    fun `file download client disables redirects and never forwards the api key`() {
        val source = KomgaFileSource()
        val transport = source.downloadHttp()
        assertFalse(transport.client.followRedirects)
        assertFalse(transport.client.followSslRedirects)

        komga.enqueue(
            MockResponse(
                code = 302,
                headers = Headers.headersOf("Location", attackerUrl("/book.epub")),
            ),
        )
        val server = RemoteServer(
            kind = ServerKind.KOMGA,
            baseUrl = "http://127.0.0.1:${komga.port}",
            catalogUrl = "http://127.0.0.1:${komga.port}",
            username = null,
            passwordCipher = null,
            apiKeyCipher = null,
            accountId = null,
            userId = null,
            koboTokenCipher = null,
            canDownload = true,
            addedAt = 1L,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = null,
        )
        val book = Book(
            url = "komga://book-1",
            title = "Test",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 1L,
            lastOpenedAt = 1L,
            remoteUuid = "book-1",
        )
        val request = source.downloadRequest(server, credentials, book)!!.build()
        transport.client.newCall(request).execute().use { response ->
            assertEquals(302, response.code)
        }
        assertEquals(0, attacker.requestCount)
    }
}
