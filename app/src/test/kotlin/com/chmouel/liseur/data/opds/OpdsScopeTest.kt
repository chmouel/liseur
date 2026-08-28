package com.chmouel.liseur.data.opds

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a Custom catalog's password may go, and what a book from it is
 * called.
 */
class OpdsScopeTest {

    private fun scope(url: String) = OpdsScope.of(url)!!

    private fun signs(root: String, target: String) =
        scope(root).signs(scope(target).root)

    @Test
    fun `the catalog's own origin is signed`() {
        assertTrue(signs("https://books.example/opds", "https://books.example/opds/all"))
    }

    @Test
    fun `a file served beside the feed is still the same server`() {
        // The first design scoped by path prefix. Catalogs routinely
        // serve their files from a sibling path, so that rule broke the
        // ordinary case to defend against another document on the
        // reader's own server.
        assertTrue(signs("https://books.example/opds", "https://books.example/get/1.epub"))
    }

    @Test
    fun `another host does not get the reader's password`() {
        // OPDS is federated: pointing at another server is a feature,
        // not an attack. Following the link is fine. Signing it is not.
        assertFalse(signs("https://books.example/opds", "https://cdn.elsewhere/1.epub"))
    }

    @Test
    fun `a subdomain is another host`() {
        assertFalse(signs("https://books.example/opds", "https://files.books.example/1.epub"))
    }

    @Test
    fun `another port is another origin`() {
        assertFalse(signs("https://books.example/opds", "https://books.example:8443/1.epub"))
    }

    @Test
    fun `the same server in plain HTTP is not the secure one`() {
        assertFalse(signs("https://books.example/opds", "http://books.example/1.epub"))
    }

    @Test
    fun `a book carries the name of the catalog that issued it`() {
        // OPDS entry ids are only unique inside the catalog that wrote
        // them: `1` is legal, and two unrelated servers can both use it.
        // A downloaded book keeps its URL across an account switch, so
        // without this the second Custom server would adopt the first
        // one's rows.
        val one = scope("https://one.example/opds")
        val two = scope("https://two.example/opds")

        assertNotEquals(one.remoteId("1"), two.remoteId("1"))
        assertEquals(one.remoteId("1"), one.remoteId("1"))
        assertNotEquals(one.remoteId("1"), one.remoteId("2"))
    }

    @Test
    fun `an entry id is never spelled into a filename as the server wrote it`() {
        // This value becomes `books.remote_uuid`, and
        // `BookDownloadRepository.fileFor()` writes it straight into a
        // filename. An OPDS id is an arbitrary string the server picks.
        val root = scope("https://books.example/opds")

        val hostile = root.remoteId("../../databases/liseur")

        assertFalse(hostile, '/' in hostile)
        assertFalse(hostile, ".." in hostile)
        assertNotEquals(hostile, root.remoteId("shelf/1"))
    }

    @Test
    fun `the same catalog is always the same name`() {
        assertEquals(
            scope("https://books.example/opds").fingerprint,
            scope("https://books.example/opds/").fingerprint,
        )
    }

    @Test
    fun `two shelves of one catalog are one catalog`() {
        // The fingerprint is taken from the root the reader configured,
        // so every book found by walking it is named the same way
        // whatever depth it turned up at.
        val root = scope("https://books.example/opds")

        assertEquals(root.remoteId("1"), root.remoteId("1"))
    }

    @Test
    fun `something that is not an address is not a catalog`() {
        assertNull(OpdsScope.of("not a url"))
        assertNull(OpdsScope.of("ftp://books.example/opds"))
    }

    @Test
    fun `two shelves at one path are two catalogs`() {
        // A query is how catalogs commonly pick a shelf, a library or a
        // user. Dropped from the fingerprint, both shelves share one
        // namespace and each adopts the other's books.
        assertNotEquals(
            scope("https://books.example/opds?shelf=a").fingerprint,
            scope("https://books.example/opds?shelf=b").fingerprint,
        )
    }

    @Test
    fun `a secure catalog does not name a plaintext file`() {
        // Not a redirect: an absolute `http://` link in an https feed
        // starts a fresh call, so the per-call downgrade check never
        // sees it.
        val root = scope("https://books.example/opds")

        assertFalse(root.mayFetch("http://files.example/1.epub".toHttpUrl()))
        assertTrue(root.mayFetch("https://files.example/1.epub".toHttpUrl()))
    }

    @Test
    fun `a catalog on the internet does not reach into the house`() {
        val root = scope("https://books.example/opds")

        assertFalse(root.mayFetch("https://192.168.1.1/admin".toHttpUrl()))
        assertFalse(root.mayFetch("https://localhost:8080/".toHttpUrl()))
        assertFalse(root.mayFetch("https://nas.local/x".toHttpUrl()))
        assertTrue(root.mayFetch("https://archive.example/1.epub".toHttpUrl()))
    }

    @Test
    fun `a catalog in the house may name its neighbours`() {
        // Self-hosting is the ordinary case here, and a server already
        // on the reader's network gains nothing by naming another one.
        val root = scope("http://192.168.1.5:8080/opds")

        assertTrue(root.mayFetch("http://192.168.1.9/covers/1.jpg".toHttpUrl()))
        assertTrue(root.mayFetch("http://nas.local/1.epub".toHttpUrl()))
    }

    @Test
    fun `the catalog's own address is always reachable`() {
        val root = scope("http://192.168.1.5:8080/opds")

        assertTrue(root.mayFetch("http://192.168.1.5:8080/get/1.epub".toHttpUrl()))
    }

    @Test
    fun `an address dressed as IPv6 is still the address it is`() {
        // `::ffff:192.168.1.1` and `::ffff:c0a8:0101` are both
        // 192.168.1.1. Read as an IPv6 prefix, neither matches
        // anything private, and the LAN is open again.
        val root = scope("https://books.example/opds")

        assertFalse(root.mayFetch("https://[::ffff:192.168.1.1]/x".toHttpUrl()))
        assertFalse(root.mayFetch("https://[::ffff:c0a8:0101]/x".toHttpUrl()))
        assertFalse(root.mayFetch("https://[::ffff:127.0.0.1]/x".toHttpUrl()))
        assertFalse(root.mayFetch("https://[0:0:0:0:0:ffff:169.254.169.254]/x".toHttpUrl()))
        assertFalse(root.mayFetch("https://[::192.168.1.1]/x".toHttpUrl()))
    }

    @Test
    fun `a real IPv6 address is read as one`() {
        val root = scope("https://books.example/opds")

        assertFalse(root.mayFetch("https://[fd00::1]/x".toHttpUrl()))
        assertFalse(root.mayFetch("https://[fe80::1]/x".toHttpUrl()))
        assertFalse(root.mayFetch("https://[::1]/x".toHttpUrl()))
        assertTrue(root.mayFetch("https://[2001:db8::1]/x".toHttpUrl()))
        assertTrue(root.mayFetch("https://[::ffff:93.184.216.34]/x".toHttpUrl()))
    }

    @Test
    fun `a link is refused before it is written down, not after`() {
        // A cover goes to the image loader and an acquisition to the
        // download worker. Neither asks this class anything, so a
        // refused link stored now is a request made later.
        val root = scope("https://books.example/opds")

        assertNull(root.fetchable("http://books.example/get/1.epub".toHttpUrl()))
        assertNull(root.fetchable("https://192.168.1.1/covers/1.jpg".toHttpUrl()))
        assertNull(root.fetchable(null))
        assertEquals(
            "https://books.example/covers/1.jpg",
            root.fetchable("https://books.example/covers/1.jpg".toHttpUrl()),
        )
        assertEquals(
            "https://cdn.example/1.epub",
            root.fetchable("https://cdn.example/1.epub".toHttpUrl()),
        )
    }
}
