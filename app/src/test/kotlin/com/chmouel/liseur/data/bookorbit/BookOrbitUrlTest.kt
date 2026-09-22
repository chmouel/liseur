package com.chmouel.liseur.data.bookorbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reader's typed address turns into.
 *
 * A BookOrbit address is pasted from a browser as often as it is typed,
 * so the awkward cases — a route instead of an origin, a reverse proxy's
 * prefix, a missing scheme — are settled here rather than on a real
 * server.
 */
class BookOrbitUrlTest {

    @Test
    fun `a bare host gets https`() {
        assertEquals("https://orbit.example.com", BookOrbitUrl.normaliseBaseUrl("orbit.example.com"))
    }

    @Test
    fun `a pasted api route is not mistaken for a server`() {
        // What a reader has in their address bar after looking at the
        // API docs.
        assertEquals(
            listOf("https://orbit.example.com"),
            BookOrbitUrl.baseUrlCandidates("https://orbit.example.com/api/v1/auth/login"),
        )
    }

    @Test
    fun `a pasted api prefix keeps the host`() {
        assertEquals(
            listOf("https://orbit.example.com"),
            BookOrbitUrl.baseUrlCandidates("https://orbit.example.com/api/v1"),
        )
    }

    @Test
    fun `a pasted unversioned api prefix keeps the host`() {
        assertEquals(
            listOf("https://orbit.example.com"),
            BookOrbitUrl.baseUrlCandidates("https://orbit.example.com/api"),
        )
    }

    @Test
    fun `a reverse proxy prefix is kept as a candidate before the origin`() {
        assertEquals(
            listOf("https://example.com/bookorbit", "https://example.com"),
            BookOrbitUrl.baseUrlCandidates("https://example.com/bookorbit"),
        )
    }

    @Test
    fun `nonsense is not an address`() {
        assertNull(BookOrbitUrl.normaliseBaseUrl(""))
        assertNull(BookOrbitUrl.normaliseBaseUrl("ftp://example.com"))
        assertTrue(BookOrbitUrl.baseUrlCandidates("").isEmpty())
    }

    @Test
    fun `a route is built under the api prefix`() {
        assertEquals(
            "https://orbit.example.com/api/v1/books/query",
            BookOrbitUrl.api("https://orbit.example.com", "/books/query"),
        )
        assertEquals(
            "https://example.com/bookorbit/api/v1/app-info",
            BookOrbitUrl.api("https://example.com/bookorbit", "app-info"),
        )
    }

    @Test
    fun `the file id comes back out of a link we wrote`() {
        assertEquals(352L, BookOrbitUrl.fileIdOf(BookOrbitUrl.downloadHref(352)))
        assertEquals(352L, BookOrbitUrl.fileIdOf("/api/v1/books/files/352/download"))
    }

    @Test
    fun `a cover carries its account scope without putting it in the request path`() {
        val href = BookOrbitUrl.coverHref(113, "bo_scope_113")

        assertEquals("bo_scope_113", BookOrbitUrl.coverRemoteId(href))
        assertTrue(BookOrbitUrl.isScopedCover(href))
        assertEquals("/api/v1/books/113/thumbnail", href.substringBefore('#'))
    }

    @Test
    fun `a link that is not ours has no file id`() {
        assertNull(BookOrbitUrl.fileIdOf(null))
        assertNull(BookOrbitUrl.fileIdOf(""))
        assertNull(BookOrbitUrl.fileIdOf("/api/v1/books/113/file"))
        assertNull(BookOrbitUrl.fileIdOf("https://elsewhere.example/epub.epub"))
    }
}
