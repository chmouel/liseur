package com.chmouel.liseur.reader.chrome

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref

/**
 * Kindle-style page turn: the departing page is snapshotted, the book
 * jumps to the next page underneath, and the snapshot slides away like
 * a lifted sheet of paper with a soft shadow along its edge.
 */
@Stable
class PageTurnEffectState(private val scope: CoroutineScope) {

    var page by mutableStateOf<ImageBitmap?>(null)
        private set
    var slideLeft by mutableStateOf(true)
        private set
    val progress = Animatable(0f)

    val isRunning: Boolean get() = page != null

    fun start(bitmap: ImageBitmap, slideLeft: Boolean) {
        this.slideLeft = slideLeft
        page = bitmap
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 350, easing = PageEasing))
            page = null
        }
    }

    private companion object {
        /** Quick lift-off, gentle landing. */
        val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

/** Draws the departing page snapshot sliding over the live navigator. */
@Composable
fun PageTurnOverlay(state: PageTurnEffectState, modifier: Modifier = Modifier) {
    val page = state.page ?: return
    Image(
        bitmap = page,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val direction = if (state.slideLeft) -1f else 1f
                translationX = direction * state.progress.value * size.width
                shadowElevation = 24.dp.toPx()
            },
    )
}

/**
 * Performs page turns for taps and volume keys. When the animation
 * preference is on and the effect is available, the turn uses the
 * sliding snapshot; otherwise it falls back to the navigator's plain
 * slide, or an instant jump when animations are disabled.
 *
 * A scrolled book is a different movement altogether, and [isScrolling]
 * says so: see [scrollScreenful].
 */
@OptIn(ExperimentalReadiumApi::class)
class PageTurner(
    private val effect: PageTurnEffectState,
    private val isAnimated: () -> Boolean,
    private val isEffectSuppressed: () -> Boolean,
    private val isScrolling: () -> Boolean = { false },
    private val isVerticalText: () -> Boolean = { false },
) {
    var navigator: OverflowableNavigator? = null
    var window: Window? = null

    /**
     * The book being read, for the one thing the navigator cannot be
     * asked once page turns are off while scrolling: what comes after
     * the chapter on screen.
     */
    var publication: Publication? = null

    fun turn(forward: Boolean) {
        val nav = navigator ?: return
        if (isScrolling()) {
            scrollScreenful(nav, forward)
            return
        }
        if (!isAnimated()) {
            navigate(nav, forward, animated = false)
            return
        }
        val win = window
        val view = nav.publicationView
        if (win == null || isEffectSuppressed() || effect.isRunning ||
            view.width <= 0 || view.height <= 0
        ) {
            // Rapid taps while a turn is animating jump instantly to
            // keep up with the reader's pace.
            navigate(nav, forward, animated = !effect.isRunning)
            return
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val bounds = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val rtl = nav.overflow.value.readingProgression == ReadingProgression.RTL
        PixelCopy.request(win, bounds, bitmap, { result ->
            val moved = navigate(nav, forward, animated = false)
            if (moved && result == PixelCopy.SUCCESS) {
                effect.start(bitmap.asImageBitmap(), slideLeft = forward != rtl)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun navigate(
        nav: OverflowableNavigator,
        forward: Boolean,
        animated: Boolean,
    ): Boolean =
        if (forward) nav.goForward(animated) else nav.goBackward(animated)

    /**
     * Moves a screenful through a scrolled book, and on to the next
     * chapter once this one runs out.
     *
     * Readium turns a whole chapter at a time while scrolling, which is
     * not what a volume key or a D-pad means, so page turns are switched
     * off in that mode (`disablePageTurnsWhileScrolling`) and the
     * movement is asked of the page itself. The document knows where it
     * is in its own units and stops at its own ends; arithmetic on view
     * heights, densities and web view scale does not.
     */
    private fun scrollScreenful(nav: OverflowableNavigator, forward: Boolean) {
        val web = visibleWebView(nav.publicationView) ?: run {
            navigate(nav, forward, animated = false)
            return
        }
        val script = scrollScreenfulScript(
            forward = forward,
            smooth = isAnimated(),
            vertical = isVerticalText(),
        )
        web.evaluateJavascript(script) { result ->
            if (result?.contains(AT_END) == true) stepChapter(forward)
        }
    }

    /**
     * Opens the chapter before or after the one on screen.
     *
     * Going back lands at the end of the chapter before, because that is
     * where a reader moving backwards is heading; the beginning is a
     * whole chapter further than they asked for.
     *
     * The reading order is searched with Readium's own comparison, the
     * one that answered when this locator was made.
     */
    fun stepChapter(forward: Boolean): Boolean {
        val nav = navigator ?: return false
        val pub = publication ?: return false
        val readingOrder = pub.readingOrder
        val index = readingOrder.indexOfFirstWithHref(nav.currentLocator.value.href)
            ?: return false
        val next = readingOrder.getOrNull(index + if (forward) 1 else -1) ?: return false
        val locator = pub.locatorFromLink(next) ?: return nav.go(next, animated = false)
        return nav.go(
            if (forward) locator else locator.copyWithLocations(progression = 1.0),
            animated = false,
        )
    }
}

/**
 * The one screenful of scrolling a volume key or a D-pad press means in
 * a scrolled book, or [AT_END] when the page is already against the edge
 * it was asked to move towards and the chapter has to change instead.
 *
 * Every measurement is the document's own. `scrollingElement` is what
 * Readium scrolls in this mode, and its `clientHeight` is the part of it
 * the reader can see: `window.innerHeight` is the web view, which is
 * taller by whatever padding Readium CSS puts around the text, and using
 * it would call the end of the chapter a screenful before the last line
 * was read.
 *
 * [vertical] is a book set in vertical lines, which Readium scrolls
 * sideways: the text runs right to left, so the offset it reads is
 * negative and grows more negative further into the chapter. Only the
 * axis changes — how far along the chapter is, and how far it goes, are
 * the same question asked of the other dimension.
 */
internal fun scrollScreenfulScript(
    forward: Boolean,
    smooth: Boolean,
    vertical: Boolean = false,
): String {
    val edge = if (forward) "at >= max - $EDGE_SLACK" else "at <= $EDGE_SLACK"
    val step = if (forward) SCREENFUL else -SCREENFUL
    val behavior = if (smooth) "smooth" else "auto"
    val page = if (vertical) {
        "e.clientWidth || window.innerWidth"
    } else {
        "e.clientHeight || window.innerHeight"
    }
    val span = if (vertical) "e.scrollWidth" else "e.scrollHeight"
    val at = if (vertical) "Math.abs(e.scrollLeft)" else "e.scrollTop"
    val target = if (vertical) "left: -to" else "top: to"
    return """
        (function() {
          var e = document.scrollingElement || document.documentElement;
          var page = $page;
          var max = Math.max(0, $span - page);
          var at = $at;
          if ($edge) { return "$AT_END"; }
          var to = Math.max(0, Math.min(max, at + page * $step));
          e.scrollTo({ $target, behavior: "$behavior" });
          return "$SCROLLED";
        })();
    """.trimIndent()
}

/**
 * A little less than a screen, so the line that was at the edge is still
 * there to be picked up again.
 */
private const val SCREENFUL = 0.9

/**
 * Scroll offsets are fractional, and a chapter read to its last pixel
 * can land a hair short of the end. Two pixels of a chapter are not
 * worth a key press that does nothing.
 */
private const val EDGE_SLACK = 2

internal const val AT_END = "at-end"

private const val SCROLLED = "scrolled"
