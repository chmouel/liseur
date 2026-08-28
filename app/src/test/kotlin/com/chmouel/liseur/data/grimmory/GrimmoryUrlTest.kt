package com.chmouel.liseur.data.grimmory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrimmoryUrlTest {

    @Test
    fun `every request goes through the shim's own path prefix`() {
        // The whole reason Grimmory needs a client of its own. The bare
        // `/api/v1/...` is Grimmory's own API, behind its own security
        // chain, and it refuses these credentials.
        assertEquals(
            "https://books.example/komga/api/v1/books",
            GrimmoryUrl.api("https://books.example", "/api/v1/books"),
        )
    }

    @Test
    fun `a bare host gets https`() {
        assertEquals("https://books.example", GrimmoryUrl.normaliseBaseUrl("books.example"))
    }

    @Test
    fun `nonsense is refused`() {
        assertNull(GrimmoryUrl.normaliseBaseUrl(""))
        assertNull(GrimmoryUrl.normaliseBaseUrl("   "))
        assertNull(GrimmoryUrl.normaliseBaseUrl("ftp://books.example"))
    }

    @Test
    fun `the shim's own address and the server's reach the same account`() {
        // A reader who copies the address bar while looking at the shim
        // must not end up with a second account for one server. The
        // pasted spelling is tried first and answers 404, having built
        // `/komga/komga/api/…`; the root behind it is what connects, and
        // is what both spellings then store.
        val pasted = GrimmoryUrl.baseUrlCandidates("https://books.example/komga")
        val plain = GrimmoryUrl.baseUrlCandidates("https://books.example")

        assertEquals("https://books.example/komga", pasted.first())
        assertEquals(plain.first(), pasted.last())
        assertEquals("https://books.example", pasted.last())
    }

    @Test
    fun `a server genuinely proxied under komga is reachable`() {
        // Its shim really is at `/komga/komga/api`, so stripping the
        // segment the reader typed would leave nowhere to connect to.
        val candidates = GrimmoryUrl.baseUrlCandidates("https://books.example/komga")

        assertEquals(
            "https://books.example/komga/komga/api/v2/users/me",
            GrimmoryUrl.api(candidates.first(), "/api/v2/users/me"),
        )
    }

    @Test
    fun `a reverse-proxied server is tried before the bare origin`() {
        // `example.com/books` is a real base URL, and giving up on it in
        // favour of the origin would connect to nothing.
        val candidates = GrimmoryUrl.baseUrlCandidates("https://example.com/books")

        assertEquals("https://example.com/books", candidates.first())
        assertTrue("https://example.com" in candidates)
    }

    @Test
    fun `a proxied server pasted at its shim still keeps the proxy path`() {
        val candidates = GrimmoryUrl.baseUrlCandidates("https://example.com/books/komga")

        assertTrue("https://example.com/books" in candidates)
        assertEquals(
            "https://example.com/books/komga/api/v2/users/me",
            GrimmoryUrl.api("https://example.com/books", "/api/v2/users/me"),
        )
    }

    @Test
    fun `a folder that merely happens to be called komga is kept`() {
        val candidates = GrimmoryUrl.baseUrlCandidates("https://example.com/komga/books")

        assertEquals("https://example.com/komga/books", candidates.first())
    }

    @Test
    fun `http is only ever arrived at deliberately`() {
        assertEquals("http://books.example", GrimmoryUrl.withHttp("https://books.example"))
    }
}
