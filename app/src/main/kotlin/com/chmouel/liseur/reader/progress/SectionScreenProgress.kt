package com.chmouel.liseur.reader.progress

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Which screenful of the open resource is on the screen, and how many
 * screenfuls that resource has at the size it is laid out now.
 *
 * Both numbers are about the resource being read and nothing larger.
 * They start again at 1 in the next one.
 */
data class SectionScreens(val screen: Int, val screens: Int)

/**
 * A page counted the way a thumb counts it: one per turn.
 *
 * The number on the footer's right edge used to be a Readium position,
 * a fixed slice of the book about a kilobyte long. That number is
 * stable — it means the same thing at every font size, which is why
 * bookmarks, the scrubber and the go-to dialog still speak in it — but
 * it is not a screen, and a short chapter read large can spend three or
 * four turns on one of them. A footer that says 42 for four turns
 * running reads as a stuck number rather than as arithmetic.
 *
 * A screenful is a thing only the laid-out document knows, so it is
 * measured there, and only for the resource actually on screen: the
 * screens of a chapter Readium has not built yet cannot be counted, and
 * estimating them from positions would be the old number wearing the
 * new one's clothes. See `docs/adr/0029-what-a-page-is-in-the-footer.md`.
 *
 * The geometry is Readium's own. In paginated mode it turns the page by
 * scrolling the root element sideways by exactly one viewport width —
 * `Android.getViewportWidth() / devicePixelRatio`, snapped to a multiple
 * of itself — so the resource's extent over that width is the number of
 * turns in it, and the offset over that width is how many have been
 * made. Two columns share one viewport width, so a spread counts as the
 * single turn it is. A book set in vertical lines is scrolled rather
 * than paginated, and a right-to-left book scrolls to negative offsets;
 * the first is declined and the second is read as the distance it is.
 */
object SectionScreenProgress {

    /**
     * The three numbers the arithmetic needs, as the document reports
     * them: the width of one screenful, the width of the whole resource,
     * and how far into it the screen has been carried.
     */
    internal data class Geometry(
        val screenWidth: Double,
        val span: Double,
        val offset: Double,
    )

    /**
     * Runs in the book's own document and touches nothing in it.
     *
     * `clientWidth` on the root element is the viewport, by a special
     * case in CSSOM View that ignores the margins Readium CSS puts
     * around the text — which is what makes it the same width Readium
     * turns by, rather than the width of the column the text sits in.
     *
     * The scroll mode and writing mode are asked as Readium asks them,
     * because a caller that has got the book wrong should be answered
     * with nothing rather than with the width of a page that is not
     * there.
     */
    internal const val SCRIPT: String = """
        (function () {
          var doc = document.documentElement;
          var e = document.scrollingElement || doc;
          if (!doc || !e) { return null; }
          var view = doc.style.getPropertyValue("--USER__view").trim();
          var scroll = doc.style.getPropertyValue("--USER__scroll").trim();
          if (view === "readium-scroll-on" || scroll === "readium-scroll-on") { return null; }
          var writing = window.getComputedStyle(doc).getPropertyValue("writing-mode") || "";
          if (writing.indexOf("vertical") === 0) { return null; }
          var width = e.clientWidth || window.innerWidth;
          var span = e.scrollWidth;
          var at = typeof window.scrollX === "number" ? window.scrollX : e.scrollLeft;
          if (!(width > 0) || !(span > 0)) { return null; }
          return width + " " + span + " " + Math.abs(at || 0);
        })();
    """

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so
     * three numbers arrive as one string wearing quotes and a document
     * with nothing to say arrives as the four characters `null`.
     */
    internal fun parse(result: String?): Geometry? {
        val text = result?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty() || text == "null") return null
        val parts = text.split(' ')
        if (parts.size != 3) return null
        val screenWidth = parts[0].toDoubleOrNull() ?: return null
        val span = parts[1].toDoubleOrNull() ?: return null
        val offset = parts[2].toDoubleOrNull() ?: return null
        return Geometry(screenWidth, span, offset)
    }

    /**
     * The screen the reader is on, counted from 1, and how many there
     * are — or null when the document has not described a page anyone
     * could count.
     *
     * The width is measured twice over. `clientWidth` is an integer and
     * the width Readium turns by is not — a device whose pixel ratio is
     * 2.625 turns by 411.43 CSS pixels and reports 411 — so counting the
     * offset against the reported width drifts a little further from the
     * truth with every screen. The count of screens does not drift,
     * because it is rounded once from a span hundreds of times larger
     * than the error; dividing the span back by that count recovers the
     * width the document is really built in, and the offset measured
     * against *that* lands on the right screen however far in it is.
     */
    internal fun screens(geometry: Geometry): SectionScreens? {
        val (reported, span, offset) = geometry
        if (!reported.isFinite() || reported < MIN_SCREEN_PX) return null
        if (!span.isFinite() || span <= 0.0) return null
        if (!offset.isFinite() || offset < 0.0) return null
        val counted = (span / reported).roundToInt()
        if (counted > MAX_SCREENS) return null
        val screens = counted.coerceAtLeast(1)
        val width = span / screens
        if (!width.isFinite() || width <= 0.0) return null
        val index = (offset / width).roundToInt().coerceIn(0, screens - 1)
        return SectionScreens(screen = index + 1, screens = screens)
    }

    /** What [web] is showing, or null when it cannot say. */
    suspend fun of(web: WebView): SectionScreens? =
        parse(evaluate(web))?.let(::screens)

    /**
     * Swallows its own failures, cancellation excepted: a page mid-build
     * has no width to report and a web view being torn down may not
     * answer at all, and neither is a reason for the reader to see a
     * number invented for the occasion.
     */
    private suspend fun evaluate(web: WebView): String? =
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    web.evaluateJavascript(SCRIPT) { if (cont.isActive) cont.resume(it) }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /**
     * Below this a "screenful" is not a screen. A viewport this narrow
     * is a view being measured rather than one being read, and dividing
     * by it turns a chapter into thousands of pages.
     */
    private const val MIN_SCREEN_PX = 16.0

    /**
     * A resource with more screens than this in it has not been measured,
     * it has been mismeasured. Nothing is shown rather than a number
     * with four digits in it that moves by one every few turns.
     */
    private const val MAX_SCREENS = 20_000

    private const val EVAL_TIMEOUT_MS = 400L
}
