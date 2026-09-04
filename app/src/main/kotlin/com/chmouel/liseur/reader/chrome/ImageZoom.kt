package com.chmouel.liseur.reader.chrome

/**
 * The bounds a zoomed image is held inside.
 *
 * Pure Kotlin, no Compose types, so the arithmetic that decides whether
 * a picture can be dragged off the screen is unit-testable. See
 * `docs/adr/0022-pinch-on-the-page.md`.
 */
object ImageZoom {
    /** Fit: the whole picture on screen, which is where the viewer opens. */
    const val MIN_SCALE = 1f

    /**
     * As far in as the reader can go.
     *
     * Past this a scanned plate is mostly its own paper grain, and a
     * vector diagram has long since stopped gaining detail.
     */
    const val MAX_SCALE = 6f

    /** Where a double-tap lands, when it is not going back to fit. */
    const val DOUBLE_TAP_SCALE = 3f

    /** Below this the viewer counts as being at fit, allowing for float drift. */
    const val FIT_EPSILON = 0.01f

    /** How far the picture is dragged down before it is being dismissed, in dp. */
    const val DISMISS_TRAVEL_DP = 96f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * The travel a downward drag has accumulated, given [travelled] so
     * far and one frame's [panY].
     *
     * Counted here rather than read off the picture's own offset, because
     * a picture at fit is clamped back to centre after every frame and
     * so never accumulates anything. Travel back up cancels travel down,
     * so a hand that wanders and returns has asked for nothing, and it
     * never goes negative — a drag that started upwards should not need
     * to be paid back before a later downward one counts.
     */
    fun dragTravel(travelled: Float, panY: Float): Float =
        (travelled + panY).coerceAtLeast(0f)

    /** True when [scale] is close enough to fit that a drag means dismiss. */
    fun atFit(scale: Float): Boolean = scale <= MIN_SCALE + FIT_EPSILON

    /**
     * How far the picture may be moved on one axis before it would leave
     * a gap at the edge of the screen.
     *
     * Zero once the drawn size no longer fills the viewport, which is why
     * a tall picture on a wide screen stays centred sideways however far
     * in the reader has zoomed.
     */
    fun maxPan(viewport: Float, drawn: Float): Float =
        ((drawn - viewport) / 2f).coerceAtLeast(0f)

    /** [offset] held inside [maxPan]. */
    fun clampPan(offset: Float, viewport: Float, drawn: Float): Float {
        val limit = maxPan(viewport, drawn)
        return offset.coerceIn(-limit, limit)
    }

    /**
     * The size a picture of [contentW] by [contentH] is drawn at to fit
     * inside [viewW] by [viewH], as (width, height).
     *
     * A picture smaller than the screen is drawn at the screen's size
     * rather than at its own: the reader opened the viewer to see it
     * bigger, and a postage stamp in the middle of a black screen is not
     * that. Coil is asked to scale it, so the pixels come from the file
     * rather than from a bitmap already thrown away.
     */
    fun fitted(contentW: Float, contentH: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        if (contentW <= 0f || contentH <= 0f || viewW <= 0f || viewH <= 0f) {
            return viewW to viewH
        }
        val ratio = minOf(viewW / contentW, viewH / contentH)
        return contentW * ratio to contentH * ratio
    }
}

/**
 * A pinch or a pan that is being counted rather than drawn.
 *
 * On electronic paper the viewer does not follow the fingers: writing
 * `scale` and the two translations on every pointer change is one
 * full-screen repaint per frame, and a panel that takes a tenth of a
 * second to repaint answers a two-second gesture with a smear. So the
 * gesture is accumulated here and applied once when the last finger
 * leaves — the same commit-on-lift bargain [PinchResize] already makes
 * for the page. See `docs/adr/0022-pinch-on-the-page.md`.
 *
 * The fields are what the viewer will be set to, not the movement since
 * the last frame: [fold] starts from where the picture already is, so
 * the value is always a complete answer and the lift has nothing to
 * work out.
 *
 * Nothing is clamped to the viewport on the way through, only on the
 * way out, because the bounds depend on a drawn size that moves with
 * the scale. A gesture that runs past the edge and comes back therefore
 * banks the overshoot, which on a screen showing none of this is not
 * something the reader can be pushing against.
 */
data class PendingTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val travelDown: Float = 0f,
) {
    /**
     * This gesture with one more frame of [zoom] and pan folded into it.
     *
     * The downward travel is counted only while the picture is at fit on
     * both sides of the frame, exactly as the drawn path counts it: a
     * drag on a zoomed-in plate is how the reader reads the far corner
     * of it, not how they put it away.
     */
    fun fold(zoom: Float, panX: Float, panY: Float): PendingTransform {
        val next = ImageZoom.clampScale(scale * zoom)
        val stayedAtFit = ImageZoom.atFit(scale) && ImageZoom.atFit(next)
        return PendingTransform(
            scale = next,
            offsetX = offsetX + panX,
            offsetY = offsetY + panY,
            travelDown = if (stayedAtFit) ImageZoom.dragTravel(travelDown, panY) else 0f,
        )
    }

    /** Whether the gesture asked, by dragging [travel] downwards, to be done. */
    fun dismisses(travel: Float): Boolean = travelDown > travel
}

