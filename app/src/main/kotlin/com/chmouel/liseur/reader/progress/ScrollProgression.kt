package com.chmouel.liseur.reader.progress

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * How far into the open chapter a scrolled page has got.
 *
 * Readium's own answer to "where is the reader" is a debounced one — a
 * hundred milliseconds of stillness — so a page that never stops is
 * never answered for, and the loops that carry or watch such a page ask
 * the document themselves. What they can ask it for is a *place*: the
 * words at the top of the screen and a selector that finds them again.
 * What they cannot ask it for is a *distance*, and a locator without one
 * reads as the start of its resource everywhere downstream — in
 * `BookPositions`, in the footer, and in the percentage every server
 * syncs.
 *
 * The distance is the document's own scroll offset over its own length,
 * which is not an approximation of Readium's convention but the inverse
 * of it: `readium.scrollToPosition(p)` restores a fraction by setting
 * `scrollingElement.scrollTop = scrollHeight * p`, and for a book set in
 * vertical lines `scrollLeft = -scrollWidth * p`.
 */
object ScrollProgression {

    /** The fraction of the open resource above the top of the screen, or null. */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun of(navigator: EpubNavigatorFragment, vertical: Boolean): Double? {
        val answer = try {
            navigator.evaluateJavascript(script(vertical))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return parse(answer)
    }

    /**
     * [vertical] is a book set in vertical lines, which Readium scrolls
     * sideways: the text runs right to left, so the offset it reads is
     * negative and grows more negative further into the chapter. Only
     * the axis changes, the same way it does in `scrollScreenfulScript`.
     */
    internal fun script(vertical: Boolean): String {
        val span = if (vertical) "e.scrollWidth" else "e.scrollHeight"
        val at = if (vertical) "Math.abs(e.scrollLeft)" else "e.scrollTop"
        return """
            (function() {
              var e = document.scrollingElement || document.documentElement;
              var span = $span;
              if (!(span > 0)) { return null; }
              return $at / span;
            })();
        """.trimIndent()
    }

    /**
     * A fraction, or null for every way the document can decline to give
     * one. A page mid-layout has no length to divide by, and a book
     * whose chapter is shorter than the screen never scrolls at all;
     * neither is a reason to file the reader at the top of the chapter.
     */
    internal fun parse(result: String?): Double? {
        val text = result?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty() || text == "null") return null
        val value = text.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.coerceIn(0.0, 1.0)
    }
}
