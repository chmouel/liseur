package com.chmouel.liseur.reader.chrome

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
 * drawn for a left-to-right book on the standard preset.
 *
 * Tapping the top strip or the center of the page gently reveals the
 * chrome (menu), as fullscreen reading apps do; the side the book came
 * from goes back a page and the rest goes forward, which is the left of
 * the page on a left-to-right book and the right of it on a
 * right-to-left one. When the chrome is showing, any tap on the page
 * dismisses it. Page turns are delegated to [onTurnPage] so they can
 * run the page-turn effect.
 *
 * Which side is which is the reader's to choose: [isSwapped] is the
 * Settings → Reading preset, and puts the forward turn under the other
 * thumb for a reader holding the phone in the other hand. It composes
 * with reading direction rather than replacing it: on a left-to-right
 * book that moves forward to the left, and on a right-to-left one,
 * which already turns forward there, it moves forward back to the
 * right. The chrome zones do not move — only the two sides trade
 * places.
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
    private val isSwapped: () -> Boolean = { false },
    private val onTurnPage: (forward: Boolean) -> Unit,
    private val onShowChrome: () -> Unit,
    private val onHideChrome: () -> Unit,
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
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

        val zone = zoneAt(event.point.x, event.point.y, width, height, dp, isScrolling())
        when (val forward = forward(zone, rtl, isSwapped())) {
            null -> onShowChrome()
            else -> onTurnPage(forward)
        }
        return true
    }

    /** What a tap on the page means, before reading direction is applied. */
    enum class Zone { CHROME, BACK, FORWARD }

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
         * Which zone a point falls in, in pixels, at [density] pixels per dp.
         *
         * Split out from the tap handling so the shape of the page can be
         * checked at any screen size without a navigator to tap on.
         *
         * [scrolling] answers the whole page at once: there is nothing to
         * turn, so every tap is a tap on the chrome.
         */
        fun zoneAt(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            density: Float,
            scrolling: Boolean = false,
        ): Zone {
            if (scrolling) return Zone.CHROME
            val fx = x / width
            val fy = y / height
            val capped = width / density >= WidthClass.MEDIUM_MIN_DP
            val strip = if (capped) {
                minOf(CHROME_ZONE, MAX_CHROME_STRIP_DP * density / height)
            } else {
                CHROME_ZONE
            }
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
            val inBox = abs(fx - 0.5f) < halfX && abs(fy - 0.5f) < halfY
            return when {
                fy < strip || inBox -> Zone.CHROME
                fx < BACK_ZONE -> Zone.BACK
                else -> Zone.FORWARD
            }
        }

        /**
         * Which way a tapped [zone] turns the page, or null for the chrome.
         *
         * [zoneAt] answers in sides of the screen; this is the one place
         * that turns a side into a direction, so the reading page and the
         * endpaper cannot come to different answers about the same tap.
         *
         * The two things that reorder the sides compose rather than
         * overrule each other. [rtl] is the book's: a right-to-left book
         * turns forward on the left, because that is where the next page
         * is. [swapped] is the reader's, and means "the other thumb"
         * whatever the book does — so on an RTL book it puts forward back
         * on the right. Both at once is the standard layout again, which
         * is why this is an equality and not a pair of branches.
         */
        fun forward(zone: Zone, rtl: Boolean, swapped: Boolean): Boolean? = when (zone) {
            Zone.CHROME -> null
            Zone.BACK -> rtl != swapped
            Zone.FORWARD -> rtl == swapped
        }
    }
}
