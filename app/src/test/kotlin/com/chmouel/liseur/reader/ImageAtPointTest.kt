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
        val script = ImageAtPoint.script(137.5f, 42f)
        assertTrue(script.contains("elementFromPoint(137.5, 42.0)"))
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
    fun `the walk up from the point is bounded`() {
        // An unbounded walk ends at body, which contains every image in
        // the chapter, so every tap would find one.
        assertTrue(ImageAtPoint.script(0f, 0f).contains("i < 4"))
    }
}
