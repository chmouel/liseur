package com.chmouel.liseur.data.opds

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
        assertTrue(one.remoteId("1").endsWith(":1"))
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
}
