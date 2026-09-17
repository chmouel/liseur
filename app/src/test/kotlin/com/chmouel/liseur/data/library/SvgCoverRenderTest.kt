package com.chmouel.liseur.data.library

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * That an SVG cover actually becomes a picture.
 *
 * The rules around it — what size, which images to fetch — are arithmetic
 * and tested without a device. This is the part that needs a real
 * `Canvas`, and Robolectric draws for real, so the cheapest honest check
 * is to render a cover of a known colour and look at a pixel.
 */
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SvgCoverRenderTest {
    private fun render(xml: String, images: Map<String, Bitmap> = emptyMap()) =
        renderSvgCover(xml.toByteArray(), images)

    @Test
    fun `a drawn cover is drawn`() {
        val cover = render(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 400">
              <rect width="300" height="400" fill="#ff0000"/>
            </svg>
            """.trimIndent(),
        )

        assertNotNull(cover)
        requireNotNull(cover)
        assertEquals(1200, cover.width)
        assertEquals(SVG_COVER_EDGE, cover.height)
        assertEquals(Color.RED, cover.getPixel(cover.width / 2, cover.height / 2))
    }

    /**
     * The wrapper cover, which is what most EPUB 3 SVG covers are: the
     * artwork is a raster and the SVG is the frame around it. The image
     * comes from the map because AndroidSVG asks for it through a
     * callback that cannot go and read the archive itself.
     */
    @Test
    fun `a referenced image is drawn into the cover`() {
        val artwork = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888)
        artwork.eraseColor(Color.BLUE)
        val cover = render(
            """
            <svg xmlns="http://www.w3.org/2000/svg"
                 xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 300 400">
              <image width="300" height="400" xlink:href="cover.png"/>
            </svg>
            """.trimIndent(),
            images = mapOf("cover.png" to artwork),
        )

        requireNotNull(cover)
        assertEquals(Color.BLUE, cover.getPixel(cover.width / 2, cover.height / 2))
    }

    /**
     * SVG has no background and the file this ends up in is a JPEG,
     * which has no alpha. Without the white underneath, every cover
     * drawn on nothing would come out of the import as a black tile.
     */
    @Test
    fun `what is not drawn is white, not transparent`() {
        val cover = render(
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 400">
              <rect x="100" y="150" width="100" height="100" fill="#000000"/>
            </svg>
            """.trimIndent(),
        )

        requireNotNull(cover)
        assertEquals(Color.WHITE, cover.getPixel(5, 5))
    }

    /**
     * A document with no viewBox has no mapping from its coordinates to
     * the viewport, so it has to be given one or it draws at its own
     * size in the corner of a much larger bitmap.
     */
    @Test
    fun `a cover sized in pixels still fills the bitmap`() {
        val cover = render(
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="300" height="400">
              <rect width="300" height="400" fill="#00ff00"/>
            </svg>
            """.trimIndent(),
        )

        requireNotNull(cover)
        assertEquals(1200, cover.width)
        assertEquals(Color.GREEN, cover.getPixel(cover.width - 5, cover.height - 5))
    }

    /** Bytes out of an archive, so "not an SVG" is an ordinary answer. */
    @Test
    fun `rubbish is not a cover`() {
        assertNull(render("this is not an SVG"))
        assertNull(renderSvgCover(ByteArray(0), emptyMap()))
    }

    /** Nothing to scale and nothing to guess from. */
    @Test
    fun `a document with no geometry is not a cover`() {
        assertNull(render("""<svg xmlns="http://www.w3.org/2000/svg"><rect fill="red"/></svg>"""))
    }
}
