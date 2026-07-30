package com.chmouel.liseur.data.komga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KomgaUrlTest {

    @Test
    fun `a bare host gets https`() {
        assertEquals("https://books.example", KomgaUrl.normaliseBaseUrl("books.example"))
    }

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("https://books.example", KomgaUrl.normaliseBaseUrl("https://books.example/"))
    }

    @Test
    fun `a query and a fragment are not part of the address`() {
        assertEquals(
            "https://books.example",
            KomgaUrl.normaliseBaseUrl("https://books.example/?tab=keys#top"),
        )
    }

    @Test
    fun `nonsense is refused`() {
        assertNull(KomgaUrl.normaliseBaseUrl(""))
        assertNull(KomgaUrl.normaliseBaseUrl("   "))
        assertNull(KomgaUrl.normaliseBaseUrl("ftp://books.example"))
        assertNull(KomgaUrl.normaliseBaseUrl("https://:8080"))
    }

    @Test
    fun `the page the api key was copied from is offered alongside the server`() {
        // Komga tells the reader to fetch a key from this exact page, so
        // this is the address that actually gets pasted.
        val candidates = KomgaUrl.baseUrlCandidates("http://civuole.lan:25600/account/api-keys")

        assertEquals(
            listOf(
                "http://civuole.lan:25600/account/api-keys",
                "http://civuole.lan:25600/account",
                "http://civuole.lan:25600",
            ),
            candidates,
        )
    }

    @Test
    fun `a reverse proxied server is tried before the bare host`() {
        val candidates = KomgaUrl.baseUrlCandidates("https://example.com/komga")

        assertEquals(listOf("https://example.com/komga", "https://example.com"), candidates)
    }

    @Test
    fun `a plain host is the only candidate`() {
        assertEquals(listOf("https://books.example"), KomgaUrl.baseUrlCandidates("books.example"))
    }

    @Test
    fun `a deeply nested path does not spawn endless candidates`() {
        val candidates = KomgaUrl.baseUrlCandidates("https://h/a/b/c/d/e/f/g/h")

        assertTrue(candidates.size <= 6)
        assertEquals("https://h/a/b/c/d/e/f/g/h", candidates.first())
    }

    @Test
    fun `api paths join without doubling the slash`() {
        assertEquals(
            "https://books.example/api/v1/books",
            KomgaUrl.api("https://books.example/", "/api/v1/books"),
        )
        assertEquals(
            "https://example.com/komga/api/v1/books",
            KomgaUrl.api("https://example.com/komga", "api/v1/books"),
        )
    }
}
