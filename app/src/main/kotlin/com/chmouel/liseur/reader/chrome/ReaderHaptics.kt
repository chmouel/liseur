package com.chmouel.liseur.reader.chrome

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class ReaderHaptic {
    THRESHOLD_ACTIVATE,
    THRESHOLD_DEACTIVATE,
    SEGMENT_TICK,
    SEGMENT_FREQUENT_TICK,
    CONFIRM,
    REJECT,
}

fun readerHapticConstant(event: ReaderHaptic, sdkInt: Int = Build.VERSION.SDK_INT): Int =
    when (event) {
        ReaderHaptic.THRESHOLD_ACTIVATE ->
            if (sdkInt >= 34) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.CONTEXT_CLICK
            }

        ReaderHaptic.THRESHOLD_DEACTIVATE ->
            if (sdkInt >= 34) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }

        ReaderHaptic.SEGMENT_TICK ->
            if (sdkInt >= 34) {
                HapticFeedbackConstants.SEGMENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }

        ReaderHaptic.SEGMENT_FREQUENT_TICK ->
            if (sdkInt >= 34) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }

        ReaderHaptic.CONFIRM ->
            if (sdkInt >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }

        ReaderHaptic.REJECT ->
            if (sdkInt >= 30) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
    }

fun View.performReaderHaptic(event: ReaderHaptic) {
    performHapticFeedback(readerHapticConstant(event))
}

fun thresholdTransitionHaptic(
    wasArmed: Boolean,
    armed: Boolean,
    stillHeld: Boolean = true,
): ReaderHaptic? = when {
    !wasArmed && armed -> ReaderHaptic.THRESHOLD_ACTIVATE
    wasArmed && !armed && stillHeld -> ReaderHaptic.THRESHOLD_DEACTIVATE
    else -> null
}

fun scrubberMoveHaptic(
    from: Float,
    to: Float,
    chapterTicks: List<Float>,
): ReaderHaptic? {
    val start = from.coerceIn(0f, 1f)
    val end = to.coerceIn(0f, 1f)
    if (start == end) return null
    val lo = min(start, end)
    val hi = max(start, end)
    val movingForward = end > start
    val crossedTick = chapterTicks.any { tick ->
        tick in 0.01f..0.99f && if (movingForward) tick > lo && tick <= hi else tick >= lo && tick < hi
    }
    if (crossedTick) return ReaderHaptic.SEGMENT_TICK
    val fromPercent = (start * 100f).toInt().coerceIn(0, 100)
    val toPercent = (end * 100f).toInt().coerceIn(0, 100)
    return if (fromPercent != toPercent) ReaderHaptic.SEGMENT_FREQUENT_TICK else null
}

fun pinchResizeStepHaptic(previousSize: Double?, currentSize: Double?): ReaderHaptic? {
    if (currentSize == null || currentSize == previousSize) return null
    return ReaderHaptic.SEGMENT_TICK
}

fun pageCurlReleaseHaptic(
    committed: Boolean,
    maxTravel: Float,
    meaningfulTravel: Float,
): ReaderHaptic? = when {
    committed -> ReaderHaptic.CONFIRM
    meaningfulTravel > 0f && maxTravel >= meaningfulTravel -> ReaderHaptic.REJECT
    else -> null
}
