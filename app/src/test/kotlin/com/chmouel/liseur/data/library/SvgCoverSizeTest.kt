package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How big a vector cover is drawn.
 *
 * A vector has no size, so one is chosen, and the choice is the whole
 * rule: too small and the shelf shows a blurred stamp, too large and an
 * arbitrary file decides how much memory the import costs. Pinned here
 * because it is arithmetic and needs no device to check.
 */
class SvgCoverSizeTest {
    private fun size(
        documentWidth: Float = -1f,
        documentHeight: Float = -1f,
        viewBoxWidth: Float = -1f,
        viewBoxHeight: Float = -1f,
    ) = svgCoverSize(documentWidth, documentHeight, viewBoxWidth, viewBoxHeight)

    /** A stated size is a stated shape, so it is believed over the viewBox. */
    @Test
    fun `the document's own dimensions come first`() {
        val size = size(
            documentWidth = 600f,
            documentHeight = 800f,
            viewBoxWidth = 100f,
            viewBoxHeight = 100f,
        )
        assertEquals(CoverSize(1200, 1600), size)
    }

    /**
     * AndroidSVG answers -1 for a dimension written as a percentage or
     * left out, which is most cover SVGs: they state a viewBox and let
     * the viewport decide.
     */
    @Test
    fun `the viewBox is the fallback`() {
        assertEquals(CoverSize(1200, 1600), size(viewBoxWidth = 300f, viewBoxHeight = 400f))
    }

    /** Half a size is no size: a width alone says nothing about the shape. */
    @Test
    fun `a partial size falls through to the viewBox`() {
        assertEquals(
            CoverSize(1200, 1600),
            size(documentWidth = 600f, viewBoxWidth = 300f, viewBoxHeight = 400f),
        )
    }

    @Test
    fun `a document with no geometry at all is not drawn`() {
        assertNull(size())
        assertNull(size(documentWidth = 600f))
        assertNull(size(viewBoxWidth = 300f))
    }

    @Test
    fun `nothing is drawn from a zero or negative dimension`() {
        assertNull(size(documentWidth = 0f, documentHeight = 800f))
        assertNull(size(viewBoxWidth = -20f, viewBoxHeight = 400f))
    }

    /**
     * Rounded, not truncated, and never to nothing: a bitmap of zero
     * pixels cannot be allocated.
     */
    @Test
    fun `an extreme shape still has both edges`() {
        assertEquals(CoverSize(SVG_COVER_EDGE, 1), size(documentWidth = 4000f, documentHeight = 1f))
    }

    /** Small art is drawn up and large art is drawn down: the long edge is the rule. */
    @Test
    fun `the long edge is the target whichever way round the cover is`() {
        assertEquals(
            CoverSize(SVG_COVER_EDGE, 800),
            size(documentWidth = 2000f, documentHeight = 1000f),
        )
        assertEquals(CoverSize(80, SVG_COVER_EDGE), size(documentWidth = 5f, documentHeight = 100f))
    }

    /**
     * The dangerous inputs. An infinite or undefined dimension arrives
     * as a float like any other, and left in it would be rounded to
     * `Int.MAX_VALUE` pixels — an allocation chosen by the file.
     */
    @Test
    fun `an unusable number is treated as an absent one`() {
        assertNull(size(documentWidth = Float.POSITIVE_INFINITY, documentHeight = 800f))
        assertNull(size(documentWidth = Float.NaN, documentHeight = Float.NaN))
        assertEquals(
            CoverSize(1200, 1600),
            size(
                documentWidth = Float.POSITIVE_INFINITY,
                documentHeight = 800f,
                viewBoxWidth = 300f,
                viewBoxHeight = 400f,
            ),
        )
    }

    @Test
    fun `a nonsensical target draws nothing`() {
        assertNull(svgCoverSize(600f, 800f, -1f, -1f, edge = 0))
    }
}
