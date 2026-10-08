package com.chmouel.liseur.tts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.rememberMotionRemoved
import kotlin.math.PI
import kotlin.math.sin

/**
 * The voice drawing breath while its audio is on the way: five bars
 * that rise and fall in a slow wave, tallest in the middle, like a
 * voice about to speak rather than a busy meter.
 *
 * On electronic paper, or with animations removed, the bars hold their
 * resting shape, which still reads as a voice, and nothing redraws.
 */
@Composable
internal fun VoiceWait(
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    height: Dp = 22.dp,
) {
    val width = barWidth * BARS + barWidth * (BARS - 1)
    val sized = modifier.size(width, height)
    if (LocalEInk.current || rememberMotionRemoved()) {
        Canvas(sized) { drawBars(color, barWidth) { ENVELOPE[it] } }
        return
    }
    val phase by rememberInfiniteTransition(label = "voice-wait").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "breath",
    )
    // Read while drawing, so the wave redraws the bars without recomposing.
    Canvas(sized) {
        drawBars(color, barWidth) { i ->
            val rise = 0.5f + 0.5f * sin(phase - i * PHASE_STEP)
            ENVELOPE[i] * (REST + (1f - REST) * rise)
        }
    }
}

private fun DrawScope.drawBars(color: Color, barWidth: Dp, fraction: (Int) -> Float) {
    val bar = barWidth.toPx()
    val radius = CornerRadius(bar / 2, bar / 2)
    for (i in 0 until BARS) {
        val h = (size.height * fraction(i)).coerceAtLeast(bar)
        drawRoundRect(
            color = color,
            topLeft = Offset(i * bar * 2, (size.height - h) / 2),
            size = Size(bar, h),
            cornerRadius = radius,
        )
    }
}

private const val BARS = 5

/** The resting shape, and how high each bar may rise: a voice is fullest in the middle. */
private val ENVELOPE = floatArrayOf(0.4f, 0.75f, 1f, 0.75f, 0.4f)

/** How low a bar sinks between breaths, as a share of its envelope. */
private const val REST = 0.3f

private const val BREATH_MS = 1_200

/** How far behind its left neighbour each bar breathes, so the wave travels across. */
private const val PHASE_STEP = 0.9f
