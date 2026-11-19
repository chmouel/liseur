package com.chmouel.liseur.reader.chrome

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPredictiveBackTest {

    private val eps = 1e-4f

    @Test
    fun `zero progress keeps full scale and alpha without translation`() {
        val motion = resolvePredictiveBackMotion(
            progress = 0f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 48f,
        )
        assertEquals(1f, motion.scale, eps)
        assertEquals(1f, motion.alpha, eps)
        assertEquals(0f, motion.translationX, eps)
    }

    @Test
    fun `half progress interpolates scale alpha and translation`() {
        val motion = resolvePredictiveBackMotion(
            progress = 0.5f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 48f,
        )
        assertEquals(0.95f, motion.scale, eps)
        assertEquals(0.825f, motion.alpha, eps)
        assertEquals(24f, motion.translationX, eps)
    }

    @Test
    fun `full progress reaches target scale alpha and translation`() {
        val motion = resolvePredictiveBackMotion(
            progress = 1f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 48f,
        )
        assertEquals(0.9f, motion.scale, eps)
        assertEquals(0.65f, motion.alpha, eps)
        assertEquals(48f, motion.translationX, eps)
    }

    @Test
    fun `out-of-range progress is clamped to zero and one`() {
        val below = resolvePredictiveBackMotion(
            progress = -0.5f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 48f,
        )
        assertEquals(1f, below.scale, eps)
        assertEquals(1f, below.alpha, eps)
        assertEquals(0f, below.translationX, eps)

        val above = resolvePredictiveBackMotion(
            progress = 1.5f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 48f,
        )
        assertEquals(0.9f, above.scale, eps)
        assertEquals(0.65f, above.alpha, eps)
        assertEquals(48f, above.translationX, eps)
    }

    @Test
    fun `left edge translates right and right edge translates left`() {
        val leftEdge = resolvePredictiveBackMotion(
            progress = 0.5f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 40f,
        )
        assertEquals(20f, leftEdge.translationX, eps)

        val rightEdge = resolvePredictiveBackMotion(
            progress = 0.5f,
            swipeEdge = BackEventCompat.EDGE_RIGHT,
            maxTranslationPx = 40f,
        )
        assertEquals(-20f, rightEdge.translationX, eps)
    }

    @Test
    fun `e-ink returns identity motion regardless of progress or edge`() {
        val left = resolvePredictiveBackMotion(
            progress = 0.75f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            maxTranslationPx = 48f,
            eInk = true,
        )
        assertEquals(1f, left.scale, eps)
        assertEquals(1f, left.alpha, eps)
        assertEquals(0f, left.translationX, eps)

        val right = resolvePredictiveBackMotion(
            progress = 1f,
            swipeEdge = BackEventCompat.EDGE_RIGHT,
            maxTranslationPx = 48f,
            eInk = true,
        )
        assertEquals(1f, right.scale, eps)
        assertEquals(1f, right.alpha, eps)
        assertEquals(0f, right.translationX, eps)
    }
}
