package com.chmouel.liseur.data.calibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreParsingTest {

    // Shaped like the real thing: calibre-web breaks a link across lines.
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <title>Calibre-Web</title>
          <entry>
            <title>Le vieil homme et la mer</title>
            <id>urn:uuid:3121b620-0edc-4178-b435-8ef66e069f9b</id>
            <link rel="http://opds-spec.org/image" href="/opds/cover/74"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/74/epub/"
                  length="156172" title="EPUB" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `recognises an opds catalog`() {
        assertTrue(CalibreParsing.isOpdsFeed(feed))
    }

    @Test
    fun `does not mistake a login page for a catalog`() {
        assertFalse(CalibreParsing.isOpdsFeed("<html><body><form>Login</form></body></html>"))
    }

    @Test
    fun `finds a download link even when the tag spans lines`() {
        assertEquals("/opds/download/74/epub/", CalibreParsing.firstAcquisitionHref(feed))
    }

    @Test
    fun `ignores cover links when looking for a download`() {
        val coversOnly = feed.replace("acquisition", "image")
        assertNull(CalibreParsing.firstAcquisitionHref(coversOnly))
    }

    @Test
    fun `reads the csrf token from a login form`() {
        val html = """<input type="hidden" name="csrf_token" value="IjMxNDc0.amkmJw.7MC86">"""
        assertEquals("IjMxNDc0.amkmJw.7MC86", CalibreParsing.csrfToken(html))
    }

    @Test
    fun `reads the csrf token when the attributes are the other way round`() {
        val html = """<input value="abc123" name="csrf_token" type="hidden">"""
        assertEquals("abc123", CalibreParsing.csrfToken(html))
    }

    @Test
    fun `reads the user id off the profile page`() {
        val html = """<a href="/kobo_auth/generate_auth_token/4">Create/View</a>"""
        assertEquals(4, CalibreParsing.userId(html))
    }

    @Test
    fun `reads the sync token out of a kobo url`() {
        val html = """<p>https://books.example.com/kobo/0A1B2C3D4E5F60718293A4B5C6D7E8F9</p>"""
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f9", CalibreParsing.koboToken(html))
    }

    @Test
    fun `does not accept a token of the wrong length`() {
        assertNull(CalibreParsing.koboToken("https://books.example.com/kobo/deadbeef"))
    }
}
