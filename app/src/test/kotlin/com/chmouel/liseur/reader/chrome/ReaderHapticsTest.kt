package com.chmouel.liseur.reader.chrome

import android.view.HapticFeedbackConstants
import com.chmouel.liseur.data.settings.PageTurnStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHapticsTest {

    @Test
    fun `maps constants across SDK versions 26 29 30 33 34 and 35`() {
        for (sdk in listOf(26, 29)) {
            assertEquals(
                HapticFeedbackConstants.CONTEXT_CLICK,
                readerHapticConstant(ReaderHaptic.THRESHOLD_ACTIVATE, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.THRESHOLD_DEACTIVATE, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.SEGMENT_TICK, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.SEGMENT_FREQUENT_TICK, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.LONG_PRESS,
                readerHapticConstant(ReaderHaptic.CONFIRM, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.REJECT, sdk),
            )
        }

        for (sdk in listOf(30, 33)) {
            assertEquals(
                HapticFeedbackConstants.CONTEXT_CLICK,
                readerHapticConstant(ReaderHaptic.THRESHOLD_ACTIVATE, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.THRESHOLD_DEACTIVATE, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.SEGMENT_TICK, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CLOCK_TICK,
                readerHapticConstant(ReaderHaptic.SEGMENT_FREQUENT_TICK, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CONFIRM,
                readerHapticConstant(ReaderHaptic.CONFIRM, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.REJECT,
                readerHapticConstant(ReaderHaptic.REJECT, sdk),
            )
        }

        for (sdk in listOf(34, 35)) {
            assertEquals(
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
                readerHapticConstant(ReaderHaptic.THRESHOLD_ACTIVATE, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
                readerHapticConstant(ReaderHaptic.THRESHOLD_DEACTIVATE, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.SEGMENT_TICK,
                readerHapticConstant(ReaderHaptic.SEGMENT_TICK, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
                readerHapticConstant(ReaderHaptic.SEGMENT_FREQUENT_TICK, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.CONFIRM,
                readerHapticConstant(ReaderHaptic.CONFIRM, sdk),
            )
            assertEquals(
                HapticFeedbackConstants.REJECT,
                readerHapticConstant(ReaderHaptic.REJECT, sdk),
            )
        }
    }

    @Test
    fun `threshold transition activates on rising edge and deactivates only while held`() {
        assertNull(thresholdTransitionHaptic(wasArmed = false, armed = false))
        assertEquals(
            ReaderHaptic.THRESHOLD_ACTIVATE,
            thresholdTransitionHaptic(wasArmed = false, armed = true),
        )
        assertNull(thresholdTransitionHaptic(wasArmed = true, armed = true))
        assertEquals(
            ReaderHaptic.THRESHOLD_DEACTIVATE,
            thresholdTransitionHaptic(wasArmed = true, armed = false, stillHeld = true),
        )
        assertNull(
            thresholdTransitionHaptic(wasArmed = true, armed = false, stillHeld = false),
        )
    }

    @Test
    fun `edge pull arms at edge and disarms when backing off without firing on step`() {
        val pull = EdgePull()
        assertFalse(pull.isArmed)

        // Scrolling inside the chapter stays disarmed.
        assertEquals(
            EdgePull.Step.NONE,
            pull.onMove(forwardTravel = 20f, atForwardEdge = false, atBackwardEdge = false, threshold = 100f),
        )
        assertFalse(pull.isArmed)

        // Hitting the forward edge arms the pull.
        val wasArmedAtEdge = pull.isArmed
        assertEquals(
            EdgePull.Step.NONE,
            pull.onMove(forwardTravel = 30f, atForwardEdge = true, atBackwardEdge = false, threshold = 100f),
        )
        assertTrue(pull.isArmed)
        assertEquals(
            ReaderHaptic.THRESHOLD_ACTIVATE,
            thresholdTransitionHaptic(wasArmed = wasArmedAtEdge, armed = pull.isArmed),
        )

        // Scrolling back off the edge while still held disarms with THRESHOLD_DEACTIVATE.
        val wasArmedBeforeBackoff = pull.isArmed
        assertEquals(
            EdgePull.Step.NONE,
            pull.onMove(forwardTravel = 10f, atForwardEdge = false, atBackwardEdge = false, threshold = 100f),
        )
        assertFalse(pull.isArmed)
        assertEquals(
            ReaderHaptic.THRESHOLD_DEACTIVATE,
            thresholdTransitionHaptic(wasArmed = wasArmedBeforeBackoff, armed = pull.isArmed, stillHeld = true),
        )

        // Re-arm and complete the chapter turn: no THRESHOLD_DEACTIVATE when the turn fires.
        pull.onMove(forwardTravel = 20f, atForwardEdge = true, atBackwardEdge = false, threshold = 100f)
        val wasArmedBeforeStep = pull.isArmed
        val step = pull.onMove(forwardTravel = 130f, atForwardEdge = true, atBackwardEdge = false, threshold = 100f)
        assertEquals(EdgePull.Step.FORWARD, step)
        assertFalse(pull.isArmed)
        val stepFired = step != EdgePull.Step.NONE
        assertNull(
            thresholdTransitionHaptic(
                wasArmed = wasArmedBeforeStep,
                armed = pull.isArmed || stepFired,
                stillHeld = !stepFired,
            ),
        )
    }

    @Test
    fun `scrubber move prioritizes chapter tick over whole percent step`() {
        val ticks = listOf(0.25f, 0.50f, 0.75f)

        // Sub-percent move without crossing a tick -> null.
        assertNull(scrubberMoveHaptic(from = 0.101f, to = 0.109f, chapterTicks = ticks))

        // Crossing a whole percent without crossing a chapter tick -> SEGMENT_FREQUENT_TICK.
        assertEquals(
            ReaderHaptic.SEGMENT_FREQUENT_TICK,
            scrubberMoveHaptic(from = 0.109f, to = 0.112f, chapterTicks = ticks),
        )
        assertEquals(
            ReaderHaptic.SEGMENT_FREQUENT_TICK,
            scrubberMoveHaptic(from = 0.122f, to = 0.118f, chapterTicks = ticks),
        )

        // Crossing a chapter tick within the same whole percent -> SEGMENT_TICK.
        assertEquals(
            ReaderHaptic.SEGMENT_TICK,
            scrubberMoveHaptic(from = 0.251f, to = 0.258f, chapterTicks = listOf(0.255f)),
        )

        // Crossing both a chapter tick and a whole percent boundary in one move -> SEGMENT_TICK wins.
        assertEquals(
            ReaderHaptic.SEGMENT_TICK,
            scrubberMoveHaptic(from = 0.242f, to = 0.265f, chapterTicks = ticks),
        )
        assertEquals(
            ReaderHaptic.SEGMENT_TICK,
            scrubberMoveHaptic(from = 0.765f, to = 0.742f, chapterTicks = ticks),
        )
    }

    @Test
    fun `pinch resize step emits segment tick only when snapped size changes`() {
        assertNull(pinchResizeStepHaptic(previousSize = 1.0, currentSize = null))
        assertNull(pinchResizeStepHaptic(previousSize = 1.0, currentSize = 1.0))
        assertEquals(
            ReaderHaptic.SEGMENT_TICK,
            pinchResizeStepHaptic(previousSize = 1.0, currentSize = 1.15),
        )
        assertEquals(
            ReaderHaptic.SEGMENT_TICK,
            pinchResizeStepHaptic(previousSize = null, currentSize = 1.15),
        )
    }

    @Test
    fun `page curl release emits confirm on commit reject on meaningful restore and null on tiny drag`() {
        assertEquals(
            ReaderHaptic.CONFIRM,
            pageCurlReleaseHaptic(committed = true, maxTravel = 30f, meaningfulTravel = 48f),
        )
        assertEquals(
            ReaderHaptic.REJECT,
            pageCurlReleaseHaptic(committed = false, maxTravel = 120f, meaningfulTravel = 48f),
        )
        assertNull(
            pageCurlReleaseHaptic(committed = false, maxTravel = 24f, meaningfulTravel = 48f),
        )
    }

    @Test
    fun `page turn drag emits confirm when curl commits and reject when restored after meaningful drag`() {
        val haptics = mutableListOf<ReaderHaptic>()
        var readyCallback: ((Boolean) -> Unit)? = null
        val drag = PageTurnDrag(
            style = { PageTurnStyle.NONE },
            canTurn = { true },
            interactive = { true },
            isRtl = { false },
            density = { 1f },
            width = { 1000f },
            onTurnPage = {},
            onHaptic = { haptics += it },
            curl = object : PageTurnDrag.Curl {
                override fun begin(forward: Boolean, grabY: Float, onReady: (Boolean) -> Unit) {
                    readyCallback = onReady
                }

                override fun follow(travel: Float, dy: Float) = Unit
                override fun finish(velocity: Float) = Unit
                override fun restore(velocity: Float) = Unit
                override fun abandon() = Unit
            },
        )

        // Commit curl -> CONFIRM.
        drag.offer(pointers = 1, dx = -30f, dy = 0f)
        readyCallback?.invoke(true)
        drag.offer(pointers = 1, dx = -500f, dy = 0f)
        drag.release(velocityX = 0f)
        assertEquals(listOf(ReaderHaptic.CONFIRM), haptics)

        // Meaningful drag restored flat -> REJECT.
        haptics.clear()
        drag.offer(pointers = 1, dx = -30f, dy = 0f)
        readyCallback?.invoke(true)
        drag.offer(pointers = 1, dx = -200f, dy = 0f)
        drag.offer(pointers = 1, dx = -10f, dy = 0f)
        drag.release(velocityX = 0f)
        assertEquals(listOf(ReaderHaptic.REJECT), haptics)

        // Tiny drag restored flat -> no haptic.
        haptics.clear()
        drag.offer(pointers = 1, dx = -20f, dy = 0f)
        readyCallback?.invoke(true)
        drag.release(velocityX = 0f)
        assertTrue(haptics.isEmpty())
    }
}
