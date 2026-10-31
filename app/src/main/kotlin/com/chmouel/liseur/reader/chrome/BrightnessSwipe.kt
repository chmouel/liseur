package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.EdgeBrightness
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic behind swiping up or down the edge of a paginated page
 * to change the screen brightness.
 *
 * Pure Kotlin, like [PinchResize], so the part that can be got wrong is
 * the part that is tested. See `docs/adr/0064-brightness-on-the-edge.md`.
 *
 * The touch is decided once, on the same event [PageTurnDrag] decides
 * on: the first past the touch slop. A finger that landed in the strip
 * and set off mostly up or down, before a long press could have
 * happened, is a brightness swipe for as long as it lasts. Anything else
 * is left to the page, which keeps every tap, long press and sideways
 * turn that starts in the strip.
 */
object BrightnessSwipe {
    /** The strip's width as a share of the page's. */
    const val STRIP_FRACTION = 0.1f

    /** The strip never narrows past a thumb's reach from the edge. */
    const val MIN_STRIP_DP = 32f

    /** Nor widens into the text on a large screen. */
    const val MAX_STRIP_DP = 56f

    /**
     * How much steeper than sideways a drag must set off. A thumb
     * sweeping a page turn from the edge rises a little as it goes; a
     * brightness swipe goes up or down.
     */
    const val STEEPNESS = 2f

    /** The share of the page's height a swipe crosses to go from dark to full. */
    const val FULL_RANGE_OF_HEIGHT = 0.6f

    /**
     * Whether a finger landing at [x], [y] on a [width] by [height] page
     * is in the strip along [edge].
     *
     * [topExcluded] and [bottomExcluded] are the bands the system keeps
     * for its own swipes — the shade, the home gesture — in pixels from
     * each end. A finger landing there is the system's.
     */
    fun inStrip(
        edge: EdgeBrightness,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        density: Float,
        topExcluded: Float = 0f,
        bottomExcluded: Float = 0f,
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        if (y < topExcluded || y > height - bottomExcluded) return false
        val strip = (width * STRIP_FRACTION).coerceIn(MIN_STRIP_DP * density, MAX_STRIP_DP * density)
        return when (edge) {
            EdgeBrightness.OFF -> false
            EdgeBrightness.LEFT -> x <= strip
            EdgeBrightness.RIGHT -> x >= width - strip
        }
    }

    /**
     * Whether a drag that has travelled [dx], [dy] from where it landed,
     * [elapsedMs] after landing, is a brightness swipe.
     *
     * A finger that has rested past [longPressMs] has already asked for
     * a selection or a picture, and keeps it.
     */
    fun claims(dx: Float, dy: Float, elapsedMs: Long, longPressMs: Long): Boolean =
        elapsedMs < longPressMs && abs(dy) > STEEPNESS * abs(dx)

    /**
     * The brightness a swipe has reached, from [start] after travelling
     * [dy] pixels down a page [height] tall. Up is brighter. Rounded to
     * the percent shown, so the window and the pill agree and a finger
     * resting still writes nothing new.
     */
    fun valueFor(start: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return start
        val raw = start - dy / (height * FULL_RANGE_OF_HEIGHT)
        return (raw.coerceIn(0f, 1f) * 100f).roundToInt() / 100f
    }
}
