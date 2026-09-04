package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceAddressTest {
    @Test
    fun `readium package url matches the href it serves`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/epub/text/chapter-81.xhtml",
                "epub/text/chapter-81.xhtml",
            ),
        )
    }

    @Test
    fun `a different resource does not match`() {
        assertFalse(
            ResourceAddress.shows(
                "https://readium_package/epub/text/chapter-1.xhtml",
                "epub/text/chapter-81.xhtml",
            ),
        )
    }

    @Test
    fun `a fragment on either side is not part of the resource`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/book.xhtml#p42",
                "OPS/book.xhtml",
            ),
        )
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/book.xhtml",
                "OPS/book.xhtml#p42",
            ),
        )
    }

    @Test
    fun `a query string is not part of the resource`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/book.xhtml?v=2",
                "OPS/book.xhtml",
            ),
        )
    }

    @Test
    fun `a percent escaped name is the name it stands for`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/chapter%20one.xhtml",
                "OPS/chapter one.xhtml",
            ),
        )
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/caf%C3%A9.xhtml",
                "OPS/café.xhtml",
            ),
        )
    }

    @Test
    fun `a plus stays a plus`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/a+b.xhtml",
                "OPS/a+b.xhtml",
            ),
        )
        assertFalse(
            ResourceAddress.shows(
                "https://readium_package/OPS/a+b.xhtml",
                "OPS/a b.xhtml",
            ),
        )
    }

    @Test
    fun `a stray percent is left alone rather than swallowing the name`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/100%.xhtml",
                "OPS/100%.xhtml",
            ),
        )
    }

    @Test
    fun `a leading slash on the href is not a difference`() {
        assertTrue(
            ResourceAddress.shows(
                "https://readium_package/OPS/book.xhtml",
                "/OPS/book.xhtml",
            ),
        )
    }

    @Test
    fun `no url at all shows nothing`() {
        assertFalse(ResourceAddress.shows(null, "OPS/book.xhtml"))
        assertFalse(ResourceAddress.shows("", "OPS/book.xhtml"))
        assertFalse(ResourceAddress.shows("about:blank", "OPS/book.xhtml"))
    }

    @Test
    fun `the origin alone names no resource`() {
        assertFalse(ResourceAddress.shows("https://readium_package/", "OPS/book.xhtml"))
        assertFalse(ResourceAddress.shows("https://readium_package/OPS/book.xhtml", ""))
    }

    @Test
    fun `a resource served from elsewhere still matches on its path`() {
        assertTrue(
            ResourceAddress.shows(
                "http://127.0.0.1:8080/pub/OPS/book.xhtml",
                "pub/OPS/book.xhtml",
            ),
        )
    }

    @Test
    fun `an image url becomes the href the publication spells it with`() {
        assertEquals(
            "EPUB/images/map.jpg",
            ResourceAddress.href("https://readium_package/EPUB/images/map.jpg"),
        )
        assertEquals(
            "EPUB/images/map.jpg",
            ResourceAddress.href("https://readium_package/EPUB/images/map.jpg#top"),
        )
    }

    @Test
    fun `an href keeps its escapes, because it is handed back as a url`() {
        // Comparison decodes them, so that two spellings of one filename
        // are one resource. Reading the file does not: a url with a raw
        // space in it does not parse.
        assertEquals(
            "EPUB/images/a%20map.jpg",
            ResourceAddress.href("https://readium_package/EPUB/images/a%20map.jpg"),
        )
    }

    @Test
    fun `nothing addressable is no href`() {
        assertNull(ResourceAddress.href(null))
        assertNull(ResourceAddress.href(""))
        assertNull(ResourceAddress.href("https://readium_package/"))
    }
}
