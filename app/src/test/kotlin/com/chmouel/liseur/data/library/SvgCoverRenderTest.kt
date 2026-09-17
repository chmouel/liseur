package com.chmouel.liseur.data.library

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

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

    /**
     * AndroidSVG unzips an SVGZ by itself, with nothing counting what
     * comes out, so the cap on the bytes read from the book would be a
     * cap on the compressed size of an arbitrary tree. A cover already
     * inside a zip gains nothing from being zipped again.
     */
    @Test
    fun `a compressed cover is refused rather than expanded`() {
        val xml = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 400">
              <rect width="300" height="400" fill="#ff0000"/>
            </svg>
        """.trimIndent()
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
        }.toByteArray()

        assertNotNull(render(xml))
        assertNull(renderSvgCover(gzipped, emptyMap()))
    }

    /**
     * An inline image never reaches the resolver: AndroidSVG decodes it
     * with no subsampling, which is the one decode in this path that is
     * not bounded. A header claiming sixty thousand pixels a side is a
     * few bytes and a 13 GB allocation.
     */
    @Test
    fun `an enormous inline image is not drawn`() {
        assertNull(render(svgAround(pngHeader(60_000, 60_000))))
        assertNotNull(render(svgAround(pngHeader(300, 400))))
    }

    private fun svgAround(png: ByteArray): String {
        val encoded = Base64.encodeToString(png, Base64.NO_WRAP)
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 400">
              <image width="300" height="400" href="data:image/png;base64,$encoded"/>
            </svg>
        """.trimIndent()
    }

    /**
     * Enough PNG for `inJustDecodeBounds` to read a size off. A real one
     * would be complete too: sixty thousand pixels square of one colour
     * compresses to a few hundred kilobytes, well inside the cap on the
     * bytes, and asks for thirteen gigabytes when it is decoded.
     */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { data ->
            data.writeInt(width)
            data.writeInt(height)
            data.writeByte(8)
            data.writeByte(2)
            data.writeByte(0)
            data.writeByte(0)
            data.writeByte(0)
        }
        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { it.write(ByteArray(16)) }
        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        chunk(png, "IHDR", header.toByteArray())
        chunk(png, "IDAT", pixels.toByteArray())
        chunk(png, "IEND", ByteArray(0))
        return png.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, body: ByteArray) {
        val typed = type.toByteArray() + body
        DataOutputStream(out).apply {
            writeInt(body.size)
            write(typed)
            writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
    }
}
