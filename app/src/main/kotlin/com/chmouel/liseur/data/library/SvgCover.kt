package com.chmouel.liseur.data.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Turning a book's SVG cover into a picture of a cover.
 *
 * The shelf shows bitmaps, and the platform has never been able to make
 * one from an SVG: `BitmapFactory` does not know the format, so Readium's
 * `publication.cover()` comes back empty for a book whose `cover-image`
 * is one. The same book reads perfectly, because a page is a WebView and
 * a WebView draws SVG — which is exactly why the blank tile looks like a
 * bug rather than a limitation (#233).
 *
 * The rules that decide what the reader ends up looking at live here,
 * away from the repository and, where they can be, away from Android, so
 * that they can be tested without a device — as [coverSampleSize] and
 * [isNamedCover] already are.
 */

/** A size in pixels to draw a cover at. */
internal data class CoverSize(val width: Int, val height: Int)

/**
 * How big to draw a vector cover.
 *
 * A vector has no size of its own, so one has to be chosen, and the
 * choice is bounded at both ends: a cover drawn at the 100 by 150 user
 * units some documents declare would be a thumbnail of a thumbnail, and
 * one drawn at whatever an arbitrary file asks for is an allocation
 * decided by the file.
 *
 * So the long edge is scaled to [SVG_COVER_EDGE] whichever way round the
 * document is, which fixes the area at no more than that squared — well
 * inside the budget the raster path subsamples down to, so the two
 * routes cannot produce wildly different files for the same book.
 */
internal fun svgCoverSize(
    documentWidth: Float,
    documentHeight: Float,
    viewBoxWidth: Float,
    viewBoxHeight: Float,
    edge: Int = SVG_COVER_EDGE,
): CoverSize? {
    // The document's own dimensions first, and the viewBox only when it
    // has none: `width="600" height="800"` and a viewBox of `0 0 300
    // 400` are the same shape, but a document that states a size has
    // stated its aspect ratio outright, while a viewBox is the
    // coordinate system the artwork happens to be drawn in.
    val stated = usable(documentWidth) && usable(documentHeight)
    val width = if (stated) documentWidth else viewBoxWidth
    val height = if (stated) documentHeight else viewBoxHeight
    // Neither. AndroidSVG answers -1 for a dimension given as a
    // percentage or not given at all, and a document with no viewBox
    // either has no geometry to draw: there is nothing to scale and
    // nothing to guess from.
    if (!usable(width) || !usable(height) || edge <= 0) return null
    // In Double, and clamped. A float ratio is not safe here: a document
    // is allowed to state `width="1e-40"`, which is finite and positive
    // and whose reciprocal is not, so the scale overflows to infinity
    // and both edges round to `Int.MAX_VALUE` — the allocation this is
    // here to prevent, reached through arithmetic rather than through a
    // dimension that looks wrong. Every finite pair has a Double ratio,
    // and the long edge is the bound by construction.
    val scale = edge.toDouble() / maxOf(width, height).toDouble()
    return CoverSize(
        width = (width * scale).roundToInt().coerceIn(1, edge),
        height = (height * scale).roundToInt().coerceIn(1, edge),
    )
}

/**
 * A dimension that can be scaled from. Infinities and NaN are as absent
 * as -1 is, and more dangerous: left in, an infinite width becomes
 * `Int.MAX_VALUE` pixels the moment it is rounded.
 */
private fun usable(value: Float) = value.isFinite() && value > 0f

/**
 * The images an SVG refers to, in the order they are written.
 *
 * A cover SVG is very often a wrapper: a `<svg>` whose only content is
 * an `<image>` pointing at the JPEG next to it, which is how a book ends
 * up declaring a vector cover that is really a photograph. Without
 * resolving those, such a cover renders as a blank page — worse than the
 * blank tile it was meant to replace.
 *
 * Parsed from the bytes rather than a string so that the XML declaration
 * decides the encoding, which is the only thing that can. `data:` URIs
 * are dropped because AndroidSVG decodes those itself, and the list is
 * capped because a cover needs one image and an arbitrary file can name
 * thousands.
 *
 * An `<image>` may legally carry both `href` and `xlink:href`, and
 * AndroidSVG takes the last one it reads rather than preferring either.
 * Guessing which it will ask for risks reading the wrong file and
 * drawing the wrapper blank, so both are read: an image that is not
 * asked for costs one of the four slots and nothing else.
 */
internal fun svgImageHrefs(bytes: ByteArray, limit: Int = MAX_SVG_IMAGES): List<String> =
    runCatching {
        Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
            .select("image")
            .asSequence()
            .flatMap { image -> image.attributes().asSequence() }
            .filter { it.key == "href" || it.key.endsWith(":href") }
            .map { it.value }
            .filter { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            .distinct()
            .take(limit)
            .toList()
    }.getOrElse { emptyList() }

/**
 * Draws [bytes] as a cover, resolving any `<image>` it refers to from
 * [images], keyed by the href exactly as the document wrote it.
 *
 * Opaque white underneath, because SVG has no background of its own and
 * the file this ends up in is a JPEG: left transparent, every cover
 * drawn on nothing would be a cover drawn on black.
 */
internal fun renderSvgCover(bytes: ByteArray, images: Map<String, Bitmap>): Bitmap? = runCatching {
    prepareSvgRendering()
    val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
    val documentWidth = svg.documentWidth
    val documentHeight = svg.documentHeight
    val viewBox = svg.documentViewBox
    val size = svgCoverSize(
        documentWidth = documentWidth,
        documentHeight = documentHeight,
        viewBoxWidth = viewBox?.width() ?: -1f,
        viewBoxHeight = viewBox?.height() ?: -1f,
    ) ?: return@runCatching null
    // Without a viewBox there is no mapping from the artwork's
    // coordinates to the page, so a document that states its size in
    // pixels is drawn at that size and the rest of the bitmap is left
    // blank. Saying that its own dimensions are its coordinate system is
    // what lets it scale.
    if (viewBox == null) svg.setDocumentViewBox(0f, 0f, documentWidth, documentHeight)
    // And the stated size has to go, for the same reason: it is the box
    // the artwork is fitted into, so a cover declaring `width="300"`
    // would be drawn 300 pixels wide in the corner of the tile however
    // large the tile is.
    svg.setDocumentWidth(size.width.toFloat())
    svg.setDocumentHeight(size.height.toFloat())
    val bitmap = createBitmap(size.width, size.height)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    SvgImages.resolving(images) {
        svg.renderToCanvas(canvas, RectF(0f, 0f, size.width.toFloat(), size.height.toFloat()))
    }
    bitmap
}.getOrNull()

private val svgPrepared: Unit by lazy {
    SVG.setInternalEntitiesEnabled(false)
    SVG.registerExternalFileResolver(SvgImages)
}

/**
 * The two global settings AndroidSVG has, applied once.
 *
 * Internal entities are turned off because a cover has no use for a DTD
 * and entity expansion is how a few kilobytes of XML become a heap dump.
 * The resolver is registered rather than passed because 1.4 has nowhere
 * to pass one: see [SvgImages].
 *
 * Once means once for the process, and covers are drawn on whichever IO
 * thread is free, so the second caller has to wait for the first rather
 * than be waved past a flag raised before the settings were written. A
 * lazy is that barrier: it publishes after its initialiser has run, not
 * before.
 */
private fun prepareSvgRendering() {
    svgPrepared
}

/**
 * The one resolver AndroidSVG is allowed to ask, answering for whichever
 * book is being drawn on the calling thread.
 *
 * `registerExternalFileResolver` is static: there is no per-document
 * resolver in 1.4, so a resolver that closed over one publication would
 * be the resolver for every publication, and two books drawn at once
 * could hand each other their artwork. The map is therefore held per
 * thread and set around the render itself, which is synchronous, so the
 * thread that puts the images there is the thread that asks for them.
 *
 * It answers from a map and nothing else. A resolver that could read
 * from the archive would be a callback that blocks, and one that could
 * read from anywhere else would be a way out of the book.
 */
private object SvgImages : SVGExternalFileResolver() {
    private val current = ThreadLocal<Map<String, Bitmap>>()

    override fun resolveImage(filename: String?): Bitmap? =
        filename?.let { current.get()?.get(it) }

    fun <T> resolving(images: Map<String, Bitmap>, block: () -> T): T {
        current.set(images)
        return try {
            block()
        } finally {
            current.remove()
        }
    }
}

/**
 * The long edge a vector cover is drawn at.
 *
 * Twice the tallest shelf tile on a large screen, so the artwork holds
 * up when the same file is shown full width on the details screen, and
 * small enough that the bitmap behind it is a few megabytes rather than
 * a decision the book gets to make.
 */
internal const val SVG_COVER_EDGE = 1600

/** How many referenced images are worth reading for one cover. */
internal const val MAX_SVG_IMAGES = 4
