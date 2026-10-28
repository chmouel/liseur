package com.chmouel.liseur.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How strongly each side of a scrolling row should say there is more, `0f..1f`. */
internal data class EdgeCue(val left: Float, val right: Float)

/**
 * The cue for a horizontal row scrolled to [value] of [maxValue].
 *
 * Each side fades in over the first [fadePx] of travel away from its end,
 * so the cue arrives and leaves with the scroll instead of snapping. In a
 * right-to-left layout the row starts at the right, so the sides swap.
 */
internal fun scrollEdgeCue(value: Int, maxValue: Int, fadePx: Float, rtl: Boolean): EdgeCue {
    if (maxValue <= 0 || fadePx <= 0f) return EdgeCue(0f, 0f)
    val backward = (value / fadePx).coerceIn(0f, 1f)
    val forward = ((maxValue - value) / fadePx).coerceIn(0f, 1f)
    return sides(backward, forward, rtl)
}

/** The all-or-nothing cue, for screens that cannot draw anything in between. */
internal fun scrollEdgeCue(canBackward: Boolean, canForward: Boolean, rtl: Boolean): EdgeCue =
    sides(if (canBackward) 1f else 0f, if (canForward) 1f else 0f, rtl)

/** Where a scroll thumb sits along its track, both as fractions of the track. */
internal data class ScrollThumb(val start: Float, val length: Float)

/**
 * The thumb for a row [viewport] wide scrolled to [value] of [maxValue], or
 * null when the row fits. Its length is the share of the row on screen. In
 * a right-to-left layout the row starts at the right, and so does the thumb.
 */
internal fun scrollThumb(value: Int, maxValue: Int, viewport: Float, rtl: Boolean): ScrollThumb? {
    if (maxValue <= 0 || viewport <= 0f) return null
    val length = viewport / (viewport + maxValue)
    val offset = (1f - length) * (value.toFloat() / maxValue).coerceIn(0f, 1f)
    return ScrollThumb(start = if (rtl) 1f - length - offset else offset, length = length)
}

private fun sides(backward: Float, forward: Float, rtl: Boolean) =
    if (rtl) EdgeCue(left = forward, right = backward) else EdgeCue(left = backward, right = forward)

/**
 * Shows that a horizontally scrolling row has more than fits, on the side
 * that has it. Goes before `horizontalScroll(state)` so the cue stays put
 * while the content moves under it.
 *
 * Normally the content fades out, the outermost strip is cleared outright,
 * and a thin thumb under the row shows how much of it is on screen and
 * where. A fade alone was too quiet to notice, above all on a dark page,
 * when the row happened to end between two items. The fade eases in and
 * out as the row nears an end. Electronic paper would print that gradient
 * as dithered grey and repaint the thumb on every pixel of a swipe, so
 * there the strip is cleared and marked with a chevron instead, redrawn
 * only when the row reaches or leaves an end.
 */
fun Modifier.scrollEdgeCue(
    state: ScrollState,
    eInk: Boolean,
    color: Color,
    width: Dp = 48.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val rtl = layoutDirection == LayoutDirection.Rtl
        val strip = CHEVRON_STRIP.toPx().coerceAtMost(size.width / 3)
        if (eInk) {
            val cue = scrollEdgeCue(state.canScrollBackward, state.canScrollForward, rtl)
            if (cue.left > 0f) edgeCue(left = true, 1f, strip, strip, chevron = color)
            if (cue.right > 0f) edgeCue(left = false, 1f, strip, strip, chevron = color)
        } else {
            val fade = width.toPx().coerceIn(strip, size.width / 3)
            val cue = scrollEdgeCue(state.value, state.maxValue, fade, rtl)
            if (cue.left > 0f) edgeCue(left = true, cue.left, fade, strip, chevron = null)
            if (cue.right > 0f) edgeCue(left = false, cue.right, fade, strip, chevron = null)
            scrollThumb(state.value, state.maxValue, size.width, rtl)?.let { thumbBar(it, color) }
        }
    }

private val CHEVRON_STRIP = 20.dp
private val THUMB_HEIGHT = 3.dp
private val THUMB_INSET = 10.dp
private val CHEVRON_INSET = 4.dp

/** A thin track along the foot of the row, with [thumb] marked on it. */
private fun DrawScope.thumbBar(thumb: ScrollThumb, color: Color) {
    val height = THUMB_HEIGHT.toPx()
    val inset = THUMB_INSET.toPx().coerceAtMost(size.width / 4)
    val track = size.width - 2 * inset
    val y = size.height - height
    val radius = CornerRadius(height / 2)
    drawRoundRect(color.copy(alpha = 0.16f), Offset(inset, y), Size(track, height), radius)
    drawRoundRect(
        color.copy(alpha = 0.7f),
        Offset(inset + track * thumb.start, y),
        Size(track * thumb.length, height),
        radius,
    )
}

/**
 * Fades the content over [fade] from one edge, clears the outer [strip]
 * and draws a [chevron] in it when given, all scaled by [strength]. With [fade] equal
 * to [strip] there is no gradient, only the cleared strip.
 */
private fun DrawScope.edgeCue(left: Boolean, strength: Float, fade: Float, strip: Float, chevron: Color?) {
    val stripStart = if (left) 0f else size.width - strip
    if (fade > strip) {
        val gradientStart = if (left) strip else size.width - fade
        val faded = Color.Black.copy(alpha = 1f - strength)
        drawRect(
            Brush.horizontalGradient(
                if (left) listOf(faded, Color.Black) else listOf(Color.Black, faded),
                startX = gradientStart,
                endX = gradientStart + fade - strip,
            ),
            topLeft = Offset(gradientStart, 0f),
            size = Size(fade - strip, size.height),
            blendMode = BlendMode.DstIn,
        )
    }
    drawRect(
        Color.Black,
        topLeft = Offset(stripStart, 0f),
        size = Size(strip, size.height),
        alpha = strength,
        blendMode = BlendMode.DstOut,
    )
    if (chevron == null) return
    // Hugging the outer edge leaves a clear gap before whatever item the
    // edge cuts through, so the chevron never reads as part of it.
    val half = 6.dp.toPx()
    val depth = 6.dp.toPx()
    val inset = CHEVRON_INSET.toPx()
    val cy = size.height / 2
    val tip = if (left) inset else size.width - inset
    val back = if (left) tip + depth else tip - depth
    drawPath(
        Path().apply {
            moveTo(back, cy - half)
            lineTo(tip, cy)
            lineTo(back, cy + half)
        },
        color = chevron,
        alpha = strength,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
