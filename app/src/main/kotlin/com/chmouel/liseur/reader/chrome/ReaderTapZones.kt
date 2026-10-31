package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.TapLayout
import com.chmouel.liseur.data.settings.TapSideWidth
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.ui.WidthClass
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.math.abs

/**
 * Reader tap zones:
 *
 * ```
 * ┌───────────────────────────┐
 * │          chrome           │  ← top strip reveals the menu
 * ├─────────┬────────┬────────┤
 * │         │        │        │
 * │  back   │ chrome │forward │  ← center also reveals the menu
 * │         │        │        │
 * └─────────┴────────┴────────┘
 * ```
 *
 * drawn for a left-to-right book with both sides turning, the default.
 *
 * Tapping the top strip or the center of the page gently reveals the
 * chrome (menu), as fullscreen reading apps do; the side the book came
 * from goes back a page and the rest goes forward, which is the left of
 * the page on a left-to-right book and the right of it on a
 * right-to-left one. When the chrome is showing, any tap on the page
 * dismisses it. Page turns are delegated to [onTurnPage] so they can
 * run the page-turn effect.
 *
 * Which sides turn pages is the reader's to choose, through [layout]:
 * both (the drawing above), only the left or only the right side, where
 * the upper part goes back and the lower part forward, or neither. The
 * same layout says how wide the sides are. [actionAt] holds the whole
 * shape, so the reading page, the endpaper and the settings preview
 * cannot come to different answers about the same tap.
 *
 * A book read by scrolling has no page to turn, so the whole page
 * becomes the chrome zone and the text is moved by dragging it. Side
 * taps there would scroll by a screenful with no page-turn to make
 * sense of it, and would take the tap the reader has anywhere else.
 *
 * The zones are proportional, which is right for turning pages — a
 * third of the page is a third of the page whatever the page is — but
 * wrong for the chrome. Two fifths of a phone is a thumb's worth of
 * screen; two fifths of a 13" tablet is a hand span, and a reader
 * reaching in to turn the page opens the menu instead. So the chrome
 * zones are also capped in real units, which leaves a phone exactly
 * where it was and stops the dead centre growing without limit.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderTapZones(
    private val navigator: OverflowableNavigator,
    private val isChromeVisible: () -> Boolean,
    private val isScrolling: () -> Boolean = { false },
    private val layout: () -> TapLayout = { TapLayout() },
    private val isPinching: () -> Boolean = { false },
    private val onTurnPage: (forward: Boolean) -> Unit,
    private val onShowChrome: () -> Unit,
    private val onHideChrome: () -> Unit,
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
        // A second finger arriving a beat after the first turns the tap
        // into a pinch, and lifting out of a pinch releases its pointers
        // one at a time. Neither is a tap on the page. See
        // `docs/adr/0022-pinch-on-the-page.md`.
        if (isPinching()) return true
        if (isChromeVisible()) {
            onHideChrome()
            return true
        }

        val view = navigator.publicationView
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        if (width <= 0f || height <= 0f) return false

        val dp = view.resources.displayMetrics.density
        val rtl = navigator.overflow.value.readingProgression == ReadingProgression.RTL

        val action = actionAt(event.point.x, event.point.y, width, height, dp, isScrolling(), layout(), rtl)
        when (action) {
            Action.Chrome -> onShowChrome()
            Action.Inert -> Unit
            is Action.Turn -> onTurnPage(action.forward)
        }
        return true
    }

    /** Which part of the page a tap lands on, with both sides turning, before reading direction. */
    enum class Zone { CHROME, BACK, FORWARD }

    /** What a tap on the page does once the layout and reading direction are applied. */
    sealed interface Action {
        data object Chrome : Action

        /** A side tap with the sides switched off. */
        data object Inert : Action

        data class Turn(val forward: Boolean) : Action
    }

    companion object {
        /** Top strip of the screen that reveals the chrome. */
        const val CHROME_ZONE = 0.14f

        /** Half the width and height of the center box, as a fraction. */
        const val CHROME_HALF_SPAN = 0.2f

        /** Left portion of the screen that turns back a page. */
        const val BACK_ZONE = 0.3f

        /*
         * The ceilings only come into play once the window is wide enough
         * to have the problem they solve — a phone, or a narrow pane on a
         * larger screen, keeps the fractions it always had. Above that the
         * fractions stop describing a target and start describing a region
         * too big to aim at, so they are clamped in absolute terms.
         */

        /** Ceiling on the top strip, so it stays a strip on a tall screen. */
        const val MAX_CHROME_STRIP_DP = 130f

        /** Ceiling on the center box across, where wide screens run away. */
        const val MAX_CHROME_BOX_WIDTH_DP = 200f

        /** Ceiling on the center box down. */
        const val MAX_CHROME_BOX_HEIGHT_DP = 360f

        /**
         * Which zone a point falls in, in pixels, at [density] pixels per dp,
         * with both sides turning.
         *
         * Split out from the tap handling so the shape of the page can be
         * checked at any screen size without a navigator to tap on.
         *
         * [scrolling] answers the whole page at once: there is nothing to
         * turn, so every tap is a tap on the chrome.
         *
         * [sideWidth] is [TapSideWidth.WIDE] for the layout described
         * above, or a percentage: each side is then that much of the page
         * edge, the same on both sides, and the whole band between them is
         * the chrome. The top strip still opens the chrome either way.
         */
        fun zoneAt(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            density: Float,
            scrolling: Boolean = false,
            sideWidth: Int = TapSideWidth.WIDE,
        ): Zone {
            if (scrolling) return Zone.CHROME
            val fx = x / width
            val fy = y / height
            if (fy < strip(width, height, density)) return Zone.CHROME
            if (sideWidth != TapSideWidth.WIDE) {
                val side = sideWidth / 100f
                return when {
                    fx < side -> Zone.BACK
                    fx > 1f - side -> Zone.FORWARD
                    else -> Zone.CHROME
                }
            }
            return when {
                inCentreBox(fx, fy, width, height, density) -> Zone.CHROME
                fx < BACK_ZONE -> Zone.BACK
                else -> Zone.FORWARD
            }
        }

        /**
         * What a tap at a point does under [layout], in pixels at [density]
         * pixels per dp. The one answer the reading page, the endpaper and
         * the settings preview all use.
         *
         * Both sides is [zoneAt] with reading direction applied ([forward]).
         * One side is its own shape: the chosen half of the page outside the
         * centre box, or the chosen edge at a narrower width, split across
         * the middle of the height below the top strip, back above and
         * forward below. Everything else, the other side included, is the
         * chrome. With the sides off, a tap where a side would be does
         * nothing and the chrome zones still open the menu.
         */
        fun actionAt(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            density: Float,
            scrolling: Boolean,
            layout: TapLayout,
            rtl: Boolean,
        ): Action {
            if (scrolling) return Action.Chrome
            val zones = layout.zones
            if (zones == TapZones.BOTH || zones == TapZones.OFF) {
                val zone = zoneAt(x, y, width, height, density, sideWidth = layout.sideWidth)
                val forward = forward(zone, rtl) ?: return Action.Chrome
                return if (zones.turnsPages) Action.Turn(forward) else Action.Inert
            }
            val fx = x / width
            val fy = y / height
            val strip = strip(width, height, density)
            if (fy < strip) return Action.Chrome
            val onLeft = zones == TapZones.LEFT
            val inSide = if (layout.sideWidth == TapSideWidth.WIDE) {
                !inCentreBox(fx, fy, width, height, density) && (if (onLeft) fx < 0.5f else fx >= 0.5f)
            } else {
                val side = layout.sideWidth / 100f
                if (onLeft) fx < side else fx > 1f - side
            }
            if (!inSide) return Action.Chrome
            return Action.Turn(forward = fy >= (strip + 1f) / 2f)
        }

        /**
         * The layout the endpaper turns by: the reader's, except that sides
         * switched off still turn there. The endpaper is the app's own page
         * and its back side is the way into the book again, so it stays
         * live, as it does in scroll mode (ADR 0009).
         */
        fun endpaperLayout(layout: TapLayout): TapLayout =
            if (layout.zones.turnsPages) layout else layout.copy(zones = TapZones.BOTH)

        /** The top strip's height as a fraction, capped on wide windows. */
        private fun strip(width: Float, height: Float, density: Float): Float =
            if (capped(width, density)) {
                minOf(CHROME_ZONE, MAX_CHROME_STRIP_DP * density / height)
            } else {
                CHROME_ZONE
            }

        /** Whether a point is in the centre box that opens the chrome. */
        private fun inCentreBox(fx: Float, fy: Float, width: Float, height: Float, density: Float): Boolean {
            val capped = capped(width, density)
            val halfX = if (capped) {
                minOf(CHROME_HALF_SPAN, MAX_CHROME_BOX_WIDTH_DP * density / width / 2f)
            } else {
                CHROME_HALF_SPAN
            }
            val halfY = if (capped) {
                minOf(CHROME_HALF_SPAN, MAX_CHROME_BOX_HEIGHT_DP * density / height / 2f)
            } else {
                CHROME_HALF_SPAN
            }
            return abs(fx - 0.5f) < halfX && abs(fy - 0.5f) < halfY
        }

        private fun capped(width: Float, density: Float): Boolean =
            width / density >= WidthClass.MEDIUM_MIN_DP

        /**
         * Which way a tapped [zone] turns the page with both sides turning,
         * or null for the chrome. [rtl] is the book's: a right-to-left book
         * turns forward on the left, because that is where the next page
         * is.
         */
        fun forward(zone: Zone, rtl: Boolean): Boolean? = when (zone) {
            Zone.CHROME -> null
            Zone.BACK -> rtl
            Zone.FORWARD -> !rtl
        }
    }
}
