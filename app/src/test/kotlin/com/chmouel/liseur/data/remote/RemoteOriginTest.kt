package com.chmouel.liseur.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the account's credentials stop.
 *
 * Cover URLs are handed to the app by the server itself, so this is the
 * check that stands between a compromised or hostile server and the
 * password or API key it would like to be given.
 */
class RemoteOriginTest {

    private val origin = requireNonNull(RemoteOrigin.of("https://books.example"))

    @Test
    fun `covers its own urls`() {
        assertTrue(origin.covers("https://books.example/api/v1/books/1/thumbnail"))
        assertTrue(origin.covers("https://books.example"))
        assertTrue(origin.covers("https://books.example/"))
    }

    @Test
    fun `a longer hostname is a different host`() {
        assertFalse(origin.covers("https://books.example.evil.test/api/v1/books/1/thumbnail"))
        assertFalse(origin.covers("https://books.examples/x"))
        assertFalse(origin.covers("https://evil.test/https://books.example/x"))
    }

    @Test
    fun `a subdomain is a different host`() {
        assertFalse(origin.covers("https://other.books.example/x"))
    }

    @Test
    fun `plain http is not the same server`() {
        assertFalse(origin.covers("http://books.example/x"))
    }

    @Test
    fun `another port is not the same server`() {
        assertFalse(origin.covers("https://books.example:8443/x"))
        val explicit = requireNonNull(RemoteOrigin.of("https://books.example:8443"))
        assertTrue(explicit.covers("https://books.example:8443/x"))
        assertFalse(explicit.covers("https://books.example/x"))
    }

    @Test
    fun `the default port is the port`() {
        val spelled = requireNonNull(RemoteOrigin.of("https://books.example:443"))
        assertTrue(spelled.covers("https://books.example/x"))
    }

    @Test
    fun `a path is matched by segment, not by prefix`() {
        val nested = requireNonNull(RemoteOrigin.of("https://books.example/api"))
        assertTrue(nested.covers("https://books.example/api/v1/books"))
        assertTrue(nested.covers("https://books.example/api"))
        assertFalse(nested.covers("https://books.example/apifoo/v1"))
        assertFalse(nested.covers("https://books.example/other"))
        assertFalse(nested.covers("https://books.example/"))
    }

    @Test
    fun `a trailing slash does not make a different server`() {
        val slashed = requireNonNull(RemoteOrigin.of("https://books.example/api/"))
        assertTrue(slashed.covers("https://books.example/api/v1/books"))
        assertTrue(slashed.covers("https://books.example/api"))
        assertFalse(slashed.covers("https://books.example/apifoo"))
    }

    @Test
    fun `what is not a url is not an origin`() {
        assertNull(RemoteOrigin.of("not a url"))
        assertNull(RemoteOrigin.of("ftp://books.example"))
        assertFalse(origin.covers("not a url"))
    }

    private fun requireNonNull(origin: RemoteOrigin?): RemoteOrigin =
        requireNotNull(origin) { "expected a usable origin" }
}
