package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.RemoteCredentials
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downloading a book whose address the catalog chose.
 *
 * The acquisition URL was read out of a feed, so it can name any host in
 * the world. `OpdsHttp` asks that question for every request it makes
 * itself, but this one is handed to the general download worker, which
 * follows its own redirects and has never heard of a catalog. Whatever
 * is going to be asked has to be asked here.
 */
class OpdsFileSourceTest {

    private val catalog = "https://books.example/opds"

    private fun request(href: String?, root: String = catalog) =
        OpdsFileSource().downloadRequest(
            server = server(root),
            credentials = RemoteCredentials.Basic("ada", "pw"),
            book = book(href),
        )

    private fun server(baseUrl: String) = com.chmouel.liseur.data.db.RemoteServer(
        kind = com.chmouel.liseur.data.remote.ServerKind.CUSTOM,
        baseUrl = baseUrl,
        username = null,
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 0,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )

    @Test
    fun `a file on the catalog is downloaded, signed`() {
        val built = request("/get/1.epub")

        assertNotNull(built)
        assertEquals(
            "https://books.example/get/1.epub",
            built!!.build().url.toString(),
        )
        assertNotNull(built.build().header("Authorization"))
    }

    @Test
    fun `a file on another server is downloaded as a stranger`() {
        // Federation is the point of OPDS. An open-access copy elsewhere
        // is fetched, just not with the reader's password on it.
        val built = request("https://archive.example/free/1.epub")

        assertNotNull(built)
        assertNull(built!!.build().header("Authorization"))
    }

    @Test
    fun `a secure catalog does not hand out a plaintext download`() {
        assertNull(request("http://files.example/1.epub"))
    }

    @Test
    fun `a catalog on the internet does not download from the house`() {
        assertNull(request("http://192.168.1.9/1.epub"))
        assertNull(request("https://nas.local/1.epub"))
    }

    @Test
    fun `a catalog in the house still may`() {
        val root = "http://192.168.1.5:8080/opds"

        assertNotNull(request("http://192.168.1.9/1.epub", root))
    }

    @Test
    fun `a book with no file is not a download`() {
        assertNull(request(null))
    }

    private fun book(href: String?) = Book(
        url = "custom:abc123:1",
        title = "A Memory Called Empire",
        author = "Arkady Martine",
        coverPath = null,
        source = null,
        addedAt = 0L,
        lastOpenedAt = 0L,
        localUri = null,
        fileModifiedAt = 0L,
        remoteUuid = "1",
        downloadHref = href,
    )

    /** Runs [href]'s download through the real transport, with [hops] standing in for the network. */
    private fun followed(href: String, hops: Map<String, String>): Pair<Result<Int>, List<okhttp3.Request>> {
        val sent = mutableListOf<okhttp3.Request>()
        val client = OpdsFileSource().downloadHttp().client.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                sent += req
                val location = hops[req.url.toString()]
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(if (location != null) 302 else 200)
                    .message("")
                    .apply { location?.let { header("Location", it) } }
                    .body("epub".toResponseBody())
                    .build()
            }
            .build()
        val result = runCatching {
            client.newCall(request(href)!!.build()).execute().use { it.code }
        }
        return result to sent
    }

    @Test
    fun `a redirect to plain http is not followed`() {
        val (result, sent) = followed(
            "/get/1.epub",
            mapOf("https://books.example/get/1.epub" to "http://books.example/get/1.epub"),
        )

        assertTrue(result.exceptionOrNull() is com.chmouel.liseur.data.remote.RemoteHttpFailure)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a redirect into the house is not followed`() {
        val (result, sent) = followed(
            "/get/1.epub",
            mapOf("https://books.example/get/1.epub" to "https://192.168.1.9/1.epub"),
        )

        assertTrue(result.isFailure)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a redirect to another host is followed without the password`() {
        val (result, sent) = followed(
            "/get/1.epub",
            mapOf("https://books.example/get/1.epub" to "https://cdn.example/1.epub"),
        )

        assertEquals(200, result.getOrThrow())
        assertNotNull(sent[0].header("Authorization"))
        assertEquals("https://cdn.example/1.epub", sent[1].url.toString())
        assertNull(sent[1].header("Authorization"))
    }
}
