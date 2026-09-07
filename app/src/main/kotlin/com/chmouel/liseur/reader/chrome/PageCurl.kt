package com.chmouel.liseur.reader.chrome

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The shape of a page being curled off the one underneath by a finger.
 *
 * The page is a sheet lying flat with its ink up. Its leading edge is
 * pulled in by [travel] pixels, and the sheet folds back over itself
 * around a cylinder whose radius broadens during the turn and whose
 * axis lies along the fold. From
 * the fold outwards a point climbs the near side of the roll (ink still
 * showing, in shadow as it turns vertical), comes over the top (the
 * back of the paper now), and lies flat on the back, upside down, over
 * the part of the page that has not moved. Everything is measured in a
 * frame where the page always moves towards `u = 0`, and mirrored back
 * to screen coordinates at the end, so a backward turn and a
 * right-to-left book are the same arithmetic.
 *
 * The fold sits where it has to for the edge, at the row the finger
 * holds, to land exactly under the finger. That is an inversion rather
 * than a formula, because while the edge is still on the roll the
 * distance it has come is `R sin(d/R) - d`, which is solved for `d`;
 * once it is over the top it is linear. The fold line is tilted by
 * [tilt] so a page pulled from near a corner curls that corner first,
 * and the tilt is ramped in with the travel so that no travel is no
 * curl, whatever the tilt would be. Raised vertices also project away
 * from the finger's row to give the sheet depth; flat vertices do not.
 *
 * The result is a regular grid over the snapshot, as
 * `Canvas.drawBitmapMesh` wants it, with three colour arrays that
 * multiply the snapshot: the ink side, masked to the near quarter of
 * the roll and darkened into the crease; the back, masked to the far
 * quarter and the tail; and paper for the back, to be drawn over it
 * from a one-pixel tile. Per-vertex alpha is what draws the back over
 * the front: a cell that straddles the top of the roll fades between
 * the two.
 */
object PageCurl {

    /** The mesh for one frame, and what the overlay needs to shade around it. */
    class Mesh(val cols: Int, val rows: Int) {
        val vertexCount = (cols + 1) * (rows + 1)
        val vertices = FloatArray(vertexCount * 2)
        val front = IntArray(vertexCount)
        val back = IntArray(vertexCount)
        val paper = IntArray(vertexCount)
        val highlight = IntArray(vertexCount)
        var radius = 0f

        /** A point on the fold line, on screen. */
        var foldX = 0f
        var foldY = 0f

        /** Unit normal to the fold, on screen, pointing at the page being revealed. */
        var normalX = 1f
        var normalY = 0f

        /** How far past the fold, along the normal, the roll's silhouette lies. */
        var silhouette = 0f

        /** How much of a roll there is to cast a shadow, 0 to 1. */
        var lift = 0f

        /** Whether anything is curled at all. */
        var curled = false
    }

    /**
     * Fills [out] for a page [width] by [height] whose leading edge has
     * come in by [travel] (never negative), held at [grabY], moving
     * towards the left of the screen or not, with the paper's own
     * colour [paper] for the back.
     */
    fun mesh(
        width: Float,
        height: Float,
        travel: Float,
        grabY: Float,
        tilt: Float,
        movesLeft: Boolean,
        radius: Float,
        paper: Int,
        out: Mesh,
    ) {
        val t = max(0f, travel)
        // A sheet first bows broadly, then tightens as it is pulled over.
        val bendRadius = bendRadius(t, width, radius)
        out.radius = bendRadius
        val phi = tilt * tiltRamp(t, width)
        val cosP = cos(phi)
        val sinP = sin(phi)
        val edgeDistance = edgeDistance(t / cosP, bendRadius)
        val foldU = width - edgeDistance / cosP
        val halfTurn = (PI * bendRadius).toFloat()
        val quarterTurn = halfTurn / 2f

        out.curled = t > 0f
        val edgeTheta = edgeDistance / bendRadius
        out.lift = min(1f, edgeTheta / (PI / 2).toFloat())
        out.silhouette = bendRadius * sin(min(edgeTheta, (PI / 2).toFloat()))
        out.foldX = toScreen(foldU, width, movesLeft)
        out.foldY = grabY
        out.normalX = if (movesLeft) cosP else -cosP
        out.normalY = sinP

        var i = 0
        for (row in 0..out.rows) {
            val y = height * row / out.rows
            for (col in 0..out.cols) {
                val x = width * col / out.cols
                val u = if (movesLeft) x else width - x
                val d = (u - foldU) * cosP + (y - grabY) * sinP
                var u2 = u
                var y2 = y
                var theta = 0f
                if (d > 0f) {
                    theta = d / bendRadius
                    val shift = if (theta < PI) bendRadius * sin(theta) - d else halfTurn - 2f * d
                    u2 = u + cosP * shift
                    y2 = y + sinP * shift
                    // Perspective belongs only to raised paper. The flat
                    // region and the finger's row retain their exact size.
                    val depth = bendRadius * (1f - cos(min(theta, PI.toFloat())))
                    y2 += (y2 - grabY) * depth / (width * 5f)
                }
                out.vertices[i * 2] = toScreen(u2, width, movesLeft)
                out.vertices[i * 2 + 1] = y2
                val onFront = d <= quarterTurn
                out.front[i] = if (onFront) grey(1f, 1f - CREASE * (1f - cos(theta))) else 0
                out.back[i] = if (onFront) 0 else grey(1f, 1f)
                out.paper[i] = if (onFront) {
                    0
                } else {
                    val over = min(1f, (theta - (PI / 2).toFloat()) / (PI / 2).toFloat())
                    tinted(paper, PAPER_ALPHA, BACK_SHADE + (1f - BACK_SHADE) * over)
                }
                // A small reflected light band makes black paper's bend
                // visible too; multiplying black by shading cannot do that.
                val ridge = sin(theta.coerceIn(0f, PI.toFloat()))
                out.highlight[i] = grey(0.12f * ridge * ridge, 1f)
                i++
            }
        }
    }

    /**
     * How far the edge is from the fold, along the fold's normal, when
     * it has come in by [travel] along that normal. Inverts the roll
     * while the edge is on it and is linear once it is over the top.
     */
    internal fun edgeDistance(travel: Float, radius: Float): Float {
        if (travel <= 0f) return 0f
        val halfTurn = (PI * radius).toFloat()
        if (travel >= halfTurn) return (halfTurn + travel) / 2f
        // R sin(d/R) - d falls from 0 to -πR over [0, πR]; find where it
        // equals -travel.
        var lo = 0f
        var hi = halfTurn
        repeat(24) {
            val mid = (lo + hi) / 2f
            val came = mid - radius * sin(mid / radius)
            if (came < travel) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    internal fun bendRadius(travel: Float, width: Float, radius: Float): Float {
        val progress = (travel / width).coerceIn(0f, 1f)
        val bow = sin(PI.toFloat() * progress)
        return radius * (1f + 1.2f * bow)
    }

    /**
     * The travel at which the last of the page has left the screen: the
     * fold, and the roll in front of it, is off the far side at every
     * row. The finishing animation runs to here and no further.
     */
    fun travelToClear(
        width: Float,
        height: Float,
        grabY: Float,
        tilt: Float,
        radius: Float,
    ): Float {
        val cosP = cos(tilt)
        val tanP = tan(tilt)
        val overhang = max(grabY * tanP, (grabY - height) * tanP)
        // A leaning roll projects further along the row than its radius;
        // two radii cover it at any lean the tilt allows.
        val distance = (width + overhang) * cosP + 2f * radius
        return cosP * (2f * distance - (PI * radius).toFloat()) + 1f
    }

    /**
     * How the fold leans, in radians: towards the corner the finger is
     * nearer to, and further as the finger drifts down or up the page.
     */
    fun tilt(grabY: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return 0f
        val fromGrab = (grabY / height - 0.5f) * 2f * GRAB_TILT
        val fromDrag = (dy / height) * DRAG_TILT
        return (fromGrab + fromDrag).coerceIn(-MAX_TILT, MAX_TILT)
    }

    /**
     * Whether a finger lifting with the edge [travel] pixels in and moving
     * at [velocity] pixels a second along the turn (negative is back
     * towards where it started) finishes the turn or puts the page
     * back. A page pulled well past the middle turns; a short pull
     * turns only if it was flung; a fling back puts it back however far
     * it had come.
     */
    fun commits(travel: Float, velocity: Float, width: Float, density: Float): Boolean {
        val fling = FLING_DP_PER_SECOND * density
        if (velocity <= -fling) return false
        if (velocity >= fling && travel >= PageTurnDrag.SWIPE_DP * density) return true
        return travel >= COMMIT_FRACTION * width
    }

    private fun tiltRamp(travel: Float, width: Float): Float =
        if (width <= 0f) 0f else min(1f, travel / (TILT_RAMP_FRACTION * width))

    private fun toScreen(u: Float, width: Float, movesLeft: Boolean): Float =
        if (movesLeft) u else width - u

    private fun grey(alpha: Float, brightness: Float): Int = tinted(WHITE, alpha, brightness)

    private fun tinted(base: Int, alpha: Float, brightness: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b = brightness.coerceIn(0f, 1f)
        val r = (((base shr 16) and 0xFF) * b + 0.5f).toInt()
        val g = (((base shr 8) and 0xFF) * b + 0.5f).toInt()
        val bl = ((base and 0xFF) * b + 0.5f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Alpha of a vertex whose colour is [argb], 0 to 1. */
    internal fun alphaOf(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** How dark the ink side gets as it turns up into the crease. */
    private const val CREASE = 0.45f

    /** How dark the back is where it comes over the top, before it lies flat. */
    private const val BACK_SHADE = 0.78f

    /** How much the paper hides the ink showing through the back. */
    private const val PAPER_ALPHA = 0.9f

    /** Past this share of the width, the tilt is fully in. */
    private const val TILT_RAMP_FRACTION = 0.25f

    /** How far a fresh corner curls ahead of the rest, at most. */
    private const val GRAB_TILT = (12.0 * PI / 180.0).toFloat()

    /** How far a finger drifting the whole page height leans the fold. */
    private const val DRAG_TILT = (25.0 * PI / 180.0).toFloat()

    private const val MAX_TILT = (28.0 * PI / 180.0).toFloat()

    /** How far across the page the edge has to come to turn on a slow release. */
    const val COMMIT_FRACTION = 0.4f

    /** A flick faster than this decides the turn by its direction. */
    const val FLING_DP_PER_SECOND = 300f

    /** The roll's minimum radius; it broadens in the middle of a turn. */
    const val RADIUS_DP = 40f

    const val COLUMNS = 64
    const val ROWS = 32
}
