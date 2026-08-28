package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            baseUrl = root,
            credentials = RemoteCredentials.Basic("ada", "pw"),
            book = book(href),
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
}
