package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.ReaderPrefs
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The maths behind pinching the page to change the reading font size.
 *
 * Pure Kotlin on purpose: no Android types, no Compose, so the part of
 * the gesture that can be got wrong is the part that is unit-tested. See
 * `docs/adr/0022-pinch-on-the-page.md`.
 *
 * The size the gesture lands on is one of the positions the Size slider
 * already offers, derived here from the same range and the same step
 * count, so the sheet and the gesture cannot drift into offering
 * different sizes for the same book.
 */
object PinchResize {
    /**
     * How many sizes the slider offers: its `steps` plus the two ends.
     *
     * The slider is declared with `steps = 17` in
     * `ui/reading/ReadingAppearanceControls.kt`, which Material reads as
     * seventeen notches *between* the ends.
     */
    const val POSITIONS = 18

    /**
     * How far the fingers must travel before the gesture is a resize.
     *
     * A thumb and a finger holding the phone drift against each other by
     * a few percent without anybody meaning anything by it, and a resize
     * silently changes how every page looks from then on. Expressed as a
     * ratio of the span the gesture started with, so it costs the same
     * movement whether the fingers began an inch apart or a hand's
     * width.
     */
    const val DEAD_ZONE = 0.08

    /** The smallest span worth measuring a ratio against, in pixels. */
    private const val MIN_START_SPAN = 24f

    private val step: Double
        get() = (ReaderPrefs.MAX_FONT_SIZE - ReaderPrefs.MIN_FONT_SIZE) / (POSITIONS - 1)

    /** The size at [index], counting from the smallest. */    fun sizeAt(index: Int): Double {
        val clamped = index.coerceIn(0, POSITIONS - 1)
        return ReaderPrefs.MIN_FONT_SIZE + clamped * step
    }

    /** The position [size] belongs to, clamped to the offered range. */
    fun positionOf(size: Double): Int {
        val raw = (size - ReaderPrefs.MIN_FONT_SIZE) / step
        return raw.roundToInt().coerceIn(0, POSITIONS - 1)
    }

    /** [size] rounded onto the nearest position the slider offers. */
    fun snap(size: Double): Double = sizeAt(positionOf(size))

    /**
     * The size a pinch has landed on, or null while it has landed on
     * nothing.
     *
     * Null means "do not commit and do not show a preview": either the
     * fingers have not left the dead zone yet, or the span is too small
     * to divide by. It is not the same as landing back on [startSize],
     * which is a real answer and worth drawing, because a reader who
     * pinches too far and comes back deserves to see that they are home.
     *
     * The ratio is applied to the size the *gesture* started from rather
     * than to the size the last frame produced, so the value cannot walk
     * away from the fingers: putting them back where they began puts the
     * size back where it began.
     */
    fun targetFor(startSize: Double, startSpan: Float, currentSpan: Float): Double? {
        if (startSpan < MIN_START_SPAN || currentSpan <= 0f) return null
        val ratio = currentSpan / startSpan
        if (abs(ratio - 1.0) < DEAD_ZONE) return null
        return snap(startSize * ratio)
    }

    /** How far apart two fingers are. */
    fun spanOf(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(x2 - x1, y2 - y1)

    /**
     * How long after the fingers lift a page turn is still refused.
     *
     * Lifting out of a pinch releases two pointers a few milliseconds
     * apart, and the last of them looks exactly like a tap on whichever
     * side of the page it happened to be on. A gesture should not turn a
     * page on its way out.
     */
    const val GUARD_MS = 350L
}

/**
 * What a pinch began with: the gap between the fingers and the size the
 * page was set in.
 *
 * Both are read once, when the second finger lands, and the ratio is
 * applied to them rather than to the last frame's answer, so putting the
 * fingers back where they started puts the size back where it started.
 */
data class PinchStart(val span: Float, val size: Double)
