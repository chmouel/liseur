package com.chmouel.liseur.reader.chrome

import androidx.activity.BackEventCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class PredictiveBackMotion(
    val scale: Float,
    val alpha: Float,
    val translationX: Float,
)

fun resolvePredictiveBackMotion(
    progress: Float,
    swipeEdge: Int = BackEventCompat.EDGE_LEFT,
    maxTranslationPx: Float = 0f,
    eInk: Boolean = false,
): PredictiveBackMotion {
    val p = progress.coerceIn(0f, 1f)
    if (eInk) {
        return PredictiveBackMotion(scale = 1f, alpha = 1f, translationX = 0f)
    }
    val scale = 1f - 0.1f * p
    val alpha = 1f - 0.35f * p
    val translationX = when (swipeEdge) {
        BackEventCompat.EDGE_RIGHT -> -maxTranslationPx * p
        else -> maxTranslationPx * p
    }
    return PredictiveBackMotion(
        scale = scale,
        alpha = alpha,
        translationX = translationX,
    )
}

fun Modifier.predictiveBackLayer(
    progress: () -> Float,
    swipeEdge: () -> Int,
    maxTranslationDp: Dp = 24.dp,
    eInk: Boolean = false,
): Modifier = graphicsLayer {
    val motion = resolvePredictiveBackMotion(
        progress = progress(),
        swipeEdge = swipeEdge(),
        maxTranslationPx = maxTranslationDp.toPx(),
        eInk = eInk,
    )
    scaleX = motion.scale
    scaleY = motion.scale
    alpha = motion.alpha
    translationX = motion.translationX
}
