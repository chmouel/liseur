package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAtPointTest {

    @Test
    fun `a miss is nothing`() {
        assertNull(ImageAtPoint.parse("null"))
        assertNull(ImageAtPoint.parse(null))
        assertNull(ImageAtPoint.parse(""))
        assertNull(ImageAtPoint.parse("   "))
    }

    @Test
    fun `a malformed answer is a miss rather than a crash`() {
        assertNull(ImageAtPoint.parse("not json at all"))
        assertNull(ImageAtPoint.parse("{"))
        assertNull(ImageAtPoint.parse("\"a string\""))
    }

    @Test
    fun `an answer without a source is a miss`() {
        assertNull(ImageAtPoint.parse("""{"alt":"a map","width":800,"height":600}"""))
        assertNull(ImageAtPoint.parse("""{"src":"","alt":"a map"}"""))
    }

    @Test
    fun `an image comes back whole`() {
        val hit = ImageAtPoint.parse(
            """{"src":"https://readium_package/EPUB/images/map.jpg",
                "alt":"A map of the island.","width":1600,"height":1200}""",
        )
        assertEquals("https://readium_package/EPUB/images/map.jpg", hit?.src)
        assertEquals("A map of the island.", hit?.alt)
        assertEquals(1600, hit?.width)
        assertEquals(1200, hit?.height)
    }

    @Test
    fun `a caption the book did not write is absent rather than empty`() {
        val hit = ImageAtPoint.parse("""{"src":"a.png","alt":"","width":300,"height":300}""")
        assertNull(hit?.alt)
    }

    @Test
    fun `the script reads currentSrc before src`() {
        // With srcset, any screen above 1x displays the 2x file while src
        // still names the 1x one, so building a viewer from src would open
        // a lower-resolution copy of the picture on the page.
        val script = ImageAtPoint.script(10f, 20f)
        val current = script.indexOf("currentSrc")
        val plain = script.indexOf("img.src")
        assertTrue(current in 0 until plain)
    }

    @Test
    fun `the script carries the point it was asked about`() {
        val script = ImageAtPoint.script(0.25f, 0.5f)
        assertTrue(script.contains("var px = 0.25 * VW"))
        assertTrue(script.contains("var py = 0.5 * VH"))
        assertTrue(script.contains("elementFromPoint(px, py)"))
    }

    @Test
    fun `the point is a fraction of the viewport, not a pixel count`() {
        // A fixed-layout page is drawn at whatever scale fits the screen,
        // so device pixels over the display density are not CSS pixels
        // there. A fraction is the same fraction in either kind of book.
        val script = ImageAtPoint.script(0.5f, 0.5f)
        assertTrue(script.contains("window.innerWidth"))
        assertTrue(script.contains("document.documentElement.clientWidth"))
    }

    @Test
    fun `the script refuses images too small to be worth a viewer`() {
        val script = ImageAtPoint.script(0f, 0f)
        assertTrue(script.contains("var MIN = ${ImageAtPoint.MIN_RENDERED_PX}"))
        assertTrue(script.contains("box.width < MIN || box.height < MIN"))
    }

    @Test
    fun `the script knows the decorative roles by name`() {
        // Size alone will not do it: a Standard Ebooks publisher logo is
        // drawn well above the threshold and is still furniture.
        val script = ImageAtPoint.script(0f, 0f)
        assertTrue(script.contains("\"publisher-logo\""))
        assertTrue(script.contains("\"ornament\""))
    }

    @Test
    fun `an ancestor can say the picture is furniture`() {
        // A title page's img says nothing; its section says titlepage.
        val script = ImageAtPoint.script(0f, 0f)
        assertTrue(script.contains("up.parentElement"))
        assertTrue(script.contains("\"titlepage\""))
        // The class matters as much as the attribute: epub:type is
        // rewritten into a class by the time the document is on screen.
        assertTrue(script.contains("getAttribute(\"class\")"))
    }

    @Test
    fun `the walk up from the point is bounded`() {
        // An unbounded walk ends at body, which contains every image in
        // the chapter, so every tap would find one.
        assertTrue(ImageAtPoint.script(0f, 0f).contains("i < 4"))
    }

    /**
     * The guard that keeps a pinch on a paragraph from opening whatever
     * illustration happens to live elsewhere in the same section.
     */
    @Test
    fun `an image found by searching a container must be under the point`() {
        val script = ImageAtPoint.script(0.5f, 0.5f)
        assertTrue(script.contains("function covers("))
        assertTrue(script.contains("covers(inner[k], px, py)"))
        assertTrue(script.contains("x >= r.left && x <= r.right && y >= r.top && y <= r.bottom"))
    }

    /** The whole stack under the point, not a guess at what is on top. */
    @Test
    fun `the script reads the hit stack before it walks`() {
        assertTrue(ImageAtPoint.script(0f, 0f).contains("elementsFromPoint(px, py)"))
    }

    /**
     * The document belongs to the book, so everything it hands back is a
     * length a file chose. An inlined `data:` plate runs to megabytes.
     */
    @Test
    fun `an absurd answer is refused rather than parsed`() {
        val src = "data:image/png;base64," + "A".repeat(200_000)
        assertNull(ImageAtPoint.parse("""{"src":"$src","alt":"","width":9,"height":9}"""))
    }

    @Test
    fun `an answer far larger than the script can produce is refused`() {
        val alt = "x".repeat(6_000)
        assertNull(
            ImageAtPoint.parse(
                """{"src":"http://h/i.png","alt":"$alt","width":9,"height":9}""",
            ),
        )
    }

    @Test
    fun `a long but plausible caption survives`() {
        val alt = "x".repeat(400)
        val hit = ImageAtPoint.parse(
            """{"src":"http://h/i.png","alt":"$alt","width":9,"height":9}""",
        )
        assertEquals(alt, hit?.alt)
    }

    @Test
    fun `the book's own caption is preferred to its alternative text`() {
        val hit = ImageAtPoint.parse(
            """{"src":"http://h/i.png","alt":"A photograph of a ship.",""" +
                """"caption":"Plate IV. The Hispaniola.","width":9,"height":9}""",
        )
        assertEquals("Plate IV. The Hispaniola.", hit?.caption)
        assertEquals("A photograph of a ship.", hit?.alt)
    }

    @Test
    fun `a picture with no figure of its own still describes itself`() {
        val hit = ImageAtPoint.parse(
            """{"src":"http://h/i.png","alt":"A map.","caption":"","width":9,"height":9}""",
        )
        assertNull(hit?.caption)
        assertEquals("A map.", hit?.alt)
    }

    @Test
    fun `the script reads a figcaption out of the figure the picture sits in`() {
        val js = ImageAtPoint.script(0.5f, 0.5f)
        assertTrue(js.contains("figcaption"))
        assertTrue(js.contains("\"figure\""))
    }
}
