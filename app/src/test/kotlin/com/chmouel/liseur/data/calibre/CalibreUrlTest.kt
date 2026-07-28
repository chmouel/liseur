package com.chmouel.liseur.data.calibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibreUrlTest {

    @Test
    fun `adds https when no scheme was typed`() {
        assertEquals("https://books.example.com", CalibreUrl.normaliseBaseUrl("books.example.com"))
    }

    @Test
    fun `keeps an explicit scheme and drops a trailing slash`() {
        assertEquals(
            "http://192.168.1.10:8083",
            CalibreUrl.normaliseBaseUrl("http://192.168.1.10:8083/"),
        )
    }

    @Test
    fun `keeps a path prefix because calibre-web is often proxied under one`() {
        assertEquals(
            "https://example.com/calibre",
            CalibreUrl.normaliseBaseUrl("  example.com/calibre/  "),
        )
    }

    @Test
    fun `rejects what cannot be a server`() {
        assertNull(CalibreUrl.normaliseBaseUrl(""))
        assertNull(CalibreUrl.normaliseBaseUrl("ftp://example.com"))
        assertNull(CalibreUrl.normaliseBaseUrl("/opds"))
    }

    @Test
    fun `resolves relative links`() {
        assertEquals(
            "https://books.example.com/opds/download/74/epub/",
            CalibreUrl.resolve("https://books.example.com", "/opds/download/74/epub/"),
        )
    }

    @Test
    fun `rebuilds absolute links onto the base url`() {
        // calibre-web behind a proxy can advertise plain http.
        assertEquals(
            "https://books.example.com/kobo/abc/download/2/kepub",
            CalibreUrl.resolve(
                "https://books.example.com",
                "http://books.example.com/kobo/abc/download/2/kepub",
            ),
        )
    }

    @Test
    fun `does not repeat the proxy path prefix`() {
        assertEquals(
            "https://example.com/calibre/opds/download/2/epub/",
            CalibreUrl.resolve("https://example.com/calibre", "/calibre/opds/download/2/epub/"),
        )
        assertEquals(
            "https://example.com/calibre/opds",
            CalibreUrl.resolve("https://example.com/calibre", "http://inner:8083/calibre/opds"),
        )
    }

    @Test
    fun `handles a link that is just the server root`() {
        assertEquals(
            "https://example.com/calibre",
            CalibreUrl.resolve("https://example.com/calibre", "https://example.com/"),
        )
    }
}
