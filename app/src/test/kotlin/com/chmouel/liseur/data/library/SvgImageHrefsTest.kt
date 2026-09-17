package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which images a cover SVG has to have read for it before it is drawn.
 *
 * The wrapper cover — a `<svg>` whose whole content is an `<image>`
 * pointing at the JPEG beside it — is the reason this exists. AndroidSVG
 * asks for those through a callback that cannot suspend, so they are
 * fetched up front, and what "those" means is decided here.
 */
class SvgImageHrefsTest {
    private fun hrefs(xml: String) = svgImageHrefs(xml.toByteArray())

    /** The old spelling, which is what an EPUB 3 wrapper almost always uses. */
    @Test
    fun `an xlink href is an image`() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg"
                 xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 600 800">
              <image width="600" height="800" xlink:href="cover.jpg"/>
            </svg>
        """.trimIndent()
        assertEquals(listOf("cover.jpg"), hrefs(xml))
    }

    /** And the SVG 2 spelling, which newer tools write instead. */
    @Test
    fun `a plain href is an image`() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 800">
              <image href="images/front.png"/>
            </svg>
        """.trimIndent()
        assertEquals(listOf("images/front.png"), hrefs(xml))
    }

    /**
     * AndroidSVG decodes a data URI itself, so reading one out of the
     * archive is a lookup that could never succeed.
     */
    @Test
    fun `an inline image is left to the renderer`() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
              <image href="data:image/png;base64,iVBORw0KGgo="/>
            </svg>
        """.trimIndent()
        assertEquals(emptyList<String>(), hrefs(xml))
    }

    /** One image, named twice, is still one thing to read. */
    @Test
    fun `the same image is only read once`() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 800">
              <image href="cover.jpg"/>
              <image href="cover.jpg" x="10"/>
            </svg>
        """.trimIndent()
        assertEquals(listOf("cover.jpg"), hrefs(xml))
    }

    /**
     * A cover needs one image. The cap is what stops a file that names
     * thousands from turning one shelf tile into a thousand reads.
     */
    @Test
    fun `only so many images are worth reading`() {
        val images = (1..50).joinToString("\n") { """<image href="$it.jpg"/>""" }
        val found = hrefs("""<svg xmlns="http://www.w3.org/2000/svg">$images</svg>""")
        assertEquals(MAX_SVG_IMAGES, found.size)
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg"), found)
    }

    /** The ordinary case: a cover drawn rather than photographed. */
    @Test
    fun `a vector cover refers to nothing`() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 800">
              <rect width="600" height="800" fill="#123456"/>
              <text x="300" y="400">A Book</text>
            </svg>
        """.trimIndent()
        assertEquals(emptyList<String>(), hrefs(xml))
    }

    /** Bytes from an archive, so "not an SVG at all" is an ordinary answer. */
    @Test
    fun `rubbish is not an error`() {
        assertEquals(emptyList<String>(), hrefs("not xml <<<"))
        assertEquals(emptyList<String>(), svgImageHrefs(ByteArray(0)))
    }

    /** An empty href points at nothing, whichever attribute it is written in. */
    @Test
    fun `an empty reference is not an image`() {
        val xml = """<svg xmlns="http://www.w3.org/2000/svg"><image href=""/></svg>"""
        assertEquals(emptyList<String>(), hrefs(xml))
    }
}
