package com.chmouel.liseur.reader.chrome

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
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
import org.readium.r2.shared.publication.Locator
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
 *
 * The page past the last page of the book is not another leaf: Readium
 * paginates leftover CSS columns, so [goForward] keeps succeeding on
 * empty air. [onReachedEnd] is that extra turn, onto the endpaper.
 */
@OptIn(ExperimentalReadiumApi::class)
class PageTurner(
    private val effect: PageTurnEffectState,
    private val isAnimated: () -> Boolean,
    private val isEffectSuppressed: () -> Boolean,
    private val isScrolling: () -> Boolean = { false },
    private val isVerticalText: () -> Boolean = { false },
    private val showingEnd: () -> Boolean = { false },
    private val onReachedEnd: () -> Unit = {},
    private val onLeaveEnd: () -> Unit = {},
    /**
     * Said as a turn is asked for, before the page has moved and long
     * before Readium says it has, carrying the position being left so
     * the arrival can be told from Readium republishing it. What is
     * listening is the screen's record of the moves the reader has been
     * sent on, which is what stops a layout change settling over the
     * page they turned to.
     */
    private val onMoveIssued: (from: Locator?, to: Locator?) -> Int = { _, _ -> 0 },
    /**
     * Said when a turn that was announced turns out to move nothing —
     * the navigator declining a `go`, the last page turning out to be
     * the last. Without it the screen would go on waiting for an
     * arrival that was never on its way, and refuse restores until it
     * gave up. Given the token the announcement answered with, so that
     * a probe coming back late cannot end a wait opened since.
     */
    private val onMoveDropped: (token: Int) -> Unit = {},
) {
    var navigator: OverflowableNavigator? = null
    var window: Window? = null

    /**
     * The book being read, for the one thing the navigator cannot be
     * asked once page turns are off while scrolling: what comes after
     * the chapter on screen.
     */
    var publication: Publication? = null

    /** True while a snapshot of the last page is being taken for the endpaper. */
    private var pendingEnd = false

    /**
     * Bumped on every turn so an in-flight last-page probe cannot act
     * after the reader has already moved.
     */
    private var probeGeneration = 0L

    fun turn(forward: Boolean) {
        if (showingEnd() || pendingEnd) {
            if (!forward && showingEnd()) onLeaveEnd()
            return
        }
        val generation = ++probeGeneration
        val nav = navigator ?: return
        val token = onMoveIssued(nav.currentLocator.value, null)
        if (isScrolling()) {
            scrollScreenful(nav, forward, generation, token)
            return
        }
        if (forward && isOnLastResource(nav)) {
            val probed = nav.currentLocator.value
            askIfLastContentVisible(nav) { atEnd ->
                if (!probeStillHolds(generation, nav, probed)) {
                    onMoveDropped(token)
                    return@askIfLastContentVisible
                }
                if (atEnd) {
                    onMoveDropped(token)
                    revealEnd()
                } else {
                    turnPaginated(nav, forward = true, token = token)
                }
            }
            return
        }
        turnPaginated(nav, forward, token)
    }

    private fun turnPaginated(nav: OverflowableNavigator, forward: Boolean, token: Int) {
        if (!isAnimated()) {
            navigate(nav, forward, animated = false, token = token)
            return
        }
        val win = window
        val view = nav.publicationView
        if (win == null || isEffectSuppressed() || effect.isRunning ||
            view.width <= 0 || view.height <= 0
        ) {
            // Rapid taps while a turn is animating jump instantly to
            // keep up with the reader's pace.
            navigate(nav, forward, animated = !effect.isRunning, token = token)
            return
        }
        copyPage(win, view) { bitmap ->
            val moved = navigate(nav, forward, animated = false, token = token)
            val rtl = nav.overflow.value.readingProgression == ReadingProgression.RTL
            if (moved && bitmap != null) {
                effect.start(bitmap, slideLeft = forward != rtl)
            }
        }
    }

    /**
     * The extra turn after the last line: lift the last page off and
     * leave the endpaper underneath, the same motion every other turn
     * already makes.
     */
    private fun revealEnd() {
        if (showingEnd() || pendingEnd) return
        pendingEnd = true
        val nav = navigator
        val win = window
        val view = nav?.publicationView
        val show = {
            pendingEnd = false
            onReachedEnd()
        }
        if (nav == null || win == null || view == null || !isAnimated() ||
            isEffectSuppressed() || effect.isRunning ||
            view.width <= 0 || view.height <= 0
        ) {
            show()
            return
        }
        val rtl = nav.overflow.value.readingProgression == ReadingProgression.RTL
        copyPage(win, view) { bitmap ->
            show()
            if (bitmap != null) effect.start(bitmap, slideLeft = !rtl)
        }
    }

    private fun copyPage(
        win: Window,
        view: View,
        onCopied: (ImageBitmap?) -> Unit,
    ) {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val bounds = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(win, bounds, bitmap, { result ->
            onCopied(bitmap.takeIf { result == PixelCopy.SUCCESS }?.asImageBitmap())
        }, Handler(Looper.getMainLooper()))
    }

    private fun navigate(
        nav: OverflowableNavigator,
        forward: Boolean,
        animated: Boolean,
        token: Int,
    ): Boolean {
        val moved = if (forward) nav.goForward(animated) else nav.goBackward(animated)
        if (!moved) onMoveDropped(token)
        // The last resource can still refuse to move even when the page
        // itself was not sure it was finished; that refusal is the end.
        if (forward && !moved && isOnLastResource(nav)) revealEnd()
        return moved
    }

    private fun isOnLastResource(nav: OverflowableNavigator): Boolean {
        val pub = publication ?: return false
        val index = pub.readingOrder.indexOfFirstWithHref(nav.currentLocator.value.href)
        return isLastReadingResource(index, pub.readingOrder.lastIndex)
    }

    /**
     * Asks the page whether its last line (or last picture) is already
     * on screen. Scroll width cannot be trusted for this: leftover CSS
     * columns inflate it past the text, which is how the last page
     * keeps flipping.
     */
    private fun askIfLastContentVisible(
        nav: OverflowableNavigator,
        onResult: (Boolean) -> Unit,
    ) {
        val web = visibleWebView(nav.publicationView) ?: run {
            onResult(false)
            return
        }
        web.evaluateJavascript(lastContentVisibleScript()) { result ->
            onResult(atEndOfBook(lastResource = true, pageReport = result))
        }
    }

    /**
     * True when the page that was asked is still the page on screen.
     *
     * `evaluateJavascript` answers later, and a backward turn or a
     * chapter step in between would otherwise paint the endpaper over
     * wherever the reader has gone.
     */
    private fun probeStillHolds(
        generation: Long,
        nav: OverflowableNavigator,
        probed: Locator,
    ): Boolean {
        if (generation != probeGeneration) return false
        if (navigator !== nav) return false
        return sameProbeLocator(nav.currentLocator.value, probed)
    }

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
    private fun scrollScreenful(
        nav: OverflowableNavigator,
        forward: Boolean,
        generation: Long,
        token: Int,
    ) {
        val web = visibleWebView(nav.publicationView) ?: run {
            navigate(nav, forward, animated = false, token = token)
            return
        }
        val script = scrollScreenfulScript(
            forward = forward,
            smooth = isAnimated(),
            vertical = isVerticalText(),
        )
        web.evaluateJavascript(script) { result ->
            if (generation != probeGeneration || navigator !== nav) return@evaluateJavascript
            if (result?.contains(AT_END) != true) return@evaluateJavascript
            // The page was already against its edge, so the screenful
            // this turn announced never moved. Dropped before stepping,
            // because the step announces the move that does happen and
            // leaving both open holds every restore for the wait of the
            // one that did not.
            onMoveDropped(token)
            stepChapter(forward)
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
        if (showingEnd() || pendingEnd) {
            if (!forward && showingEnd()) onLeaveEnd()
            return false
        }
        probeGeneration++
        val nav = navigator ?: return false
        val pub = publication ?: return false
        val readingOrder = pub.readingOrder
        val here = nav.currentLocator.value
        val index = readingOrder.indexOfFirstWithHref(here.href) ?: return false
        val next = readingOrder.getOrNull(index + if (forward) 1 else -1) ?: run {
            if (forward) revealEnd()
            return false
        }
        // Announced where the chapter is known and not before: the
        // returns above move nobody, and a step announced for one of
        // them would have the screen waiting on an arrival that is not
        // coming.
        val locator = pub.locatorFromLink(next) ?: run {
            val stepToken = onMoveIssued(here, null)
            return nav.go(next, animated = false).also { if (!it) onMoveDropped(stepToken) }
        }
        val target = if (forward) locator else locator.copyWithLocations(progression = 1.0)
        val stepToken = onMoveIssued(here, target)
        return nav.go(target, animated = false).also { if (!it) onMoveDropped(stepToken) }
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

/**
 * Whether the resource on screen is the last one in the book.
 *
 * [indexInReadingOrder] is null when the href is not in the spine at
 * all; [lastIndex] is -1 when the spine is empty. Neither is the end
 * of a book — there is no book there.
 */
internal fun isLastReadingResource(indexInReadingOrder: Int?, lastIndex: Int): Boolean =
    lastIndex >= 0 && indexInReadingOrder == lastIndex

/**
 * The last page of the last resource is the end of the book; the last
 * page of a middle chapter is only the end of that chapter, and more
 * content still waiting in the last resource is still a page.
 */
internal fun atEndOfBook(lastResource: Boolean, pageReport: String?): Boolean =
    lastResource && pageReport?.contains(AT_END) == true

/**
 * Whether a last-page probe still describes the page on screen.
 *
 * Href and progression are both required: the last resource is one
 * file, and a backward turn inside it would otherwise look like the
 * same place.
 */
internal fun sameProbeLocator(current: Locator, probed: Locator): Boolean =
    sameProbePlace(
        currentHref = current.href.toString(),
        currentProgression = current.locations.progression,
        probedHref = probed.href.toString(),
        probedProgression = probed.locations.progression,
    )

internal fun sameProbePlace(
    currentHref: String,
    currentProgression: Double?,
    probedHref: String,
    probedProgression: Double?,
): Boolean {
    if (currentHref != probedHref) return false
    if (currentProgression == null && probedProgression == null) return true
    if (currentProgression == null || probedProgression == null) return false
    return kotlin.math.abs(currentProgression - probedProgression) < PROBE_PROGRESSION_SLACK
}

/**
 * Whether the last line (or last picture) of this document is already
 * on the page the reader can see.
 *
 * Scroll width is the wrong question in paginated mode: leftover CSS
 * columns make the document wider than its text, so a page that has
 * already shown every word still looks as if it can turn. The last
 * *rendered* content node's box is the book's own answer.
 *
 * Trailing nodes that never paint — `<script>`, `<style>`, hidden
 * footnotes — are skipped. An empty rectangle is not the end of the
 * book; it is a node that does not count, and the walker keeps going.
 */
internal fun lastContentVisibleScript(): String = """
    (function() {
      var w = window.innerWidth, h = window.innerHeight;
      var slack = $EDGE_SLACK;
      // No body yet is a page still loading, not a finished book.
      if (!document.body) { return "$SCROLLED"; }
      function isHidden(node) {
        var el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
        while (el && el !== document.documentElement) {
          var tag = el.tagName;
          if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEMPLATE") {
            return true;
          }
          if (el.hidden) return true;
          var s = window.getComputedStyle(el);
          if (s && (s.display === "none" || s.visibility === "hidden")) return true;
          el = el.parentElement;
        }
        return false;
      }
      function rectOf(node) {
        if (node.nodeType === Node.TEXT_NODE) {
          var range = document.createRange();
          range.selectNodeContents(node);
          var rects = range.getClientRects();
          return rects.length ? rects[rects.length - 1] : range.getBoundingClientRect();
        }
        return node.getBoundingClientRect();
      }
      var walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
        {
          acceptNode: function(n) {
            if (n.nodeType === Node.ELEMENT_NODE) {
              var tag = n.tagName;
              if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEMPLATE") {
                return NodeFilter.FILTER_REJECT;
              }
              if (isHidden(n)) return NodeFilter.FILTER_REJECT;
              if (tag === "IMG" || tag === "SVG" || tag === "CANVAS" || tag === "VIDEO") {
                return NodeFilter.FILTER_ACCEPT;
              }
              return NodeFilter.FILTER_SKIP;
            }
            if (!(n.nodeValue && n.nodeValue.trim()) || isHidden(n)) {
              return NodeFilter.FILTER_REJECT;
            }
            return NodeFilter.FILTER_ACCEPT;
          }
        }
      );
      var lastRect = null, n;
      while (n = walker.nextNode()) {
        var r = rectOf(n);
        if (r.width < 1 && r.height < 1) continue;
        lastRect = r;
      }
      if (!lastRect) { return "$AT_END"; }
      if (lastRect.left < w - slack && lastRect.right > slack &&
          lastRect.top < h - slack && lastRect.bottom > slack) {
        return "$AT_END";
      }
      return "$SCROLLED";
    })();
""".trimIndent()

/** Progression slack for deciding two locators are still the same page. */
private const val PROBE_PROGRESSION_SLACK = 0.0005
