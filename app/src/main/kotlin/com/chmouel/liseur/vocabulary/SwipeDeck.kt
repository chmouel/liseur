package com.chmouel.liseur.vocabulary

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.chrome.performReaderHaptic
import com.chmouel.liseur.reader.chrome.thresholdTransitionHaptic
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.rememberMotionRemoved
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign

/** How far a card has to travel before letting go of it rates the word. */
internal data class SwipeThresholds(
    /** Share of the card's width that rates on release at any speed. */
    val commit: Float,
    /** A shorter share that rates when flung; null means a fling alone never does. */
    val fling: Float?,
)

/** The review screen is where rating happens, so a quick flick is enough there. */
internal val ReviewSwipe = SwipeThresholds(commit = 0.35f, fling = 0.25f)

/** Home scrolls past the card, so only a deliberate drag rates it. */
internal val HomeSwipe = SwipeThresholds(commit = 0.40f, fling = null)

private const val SWIPE_HYSTERESIS = 0.05f
private const val MAX_TILT_DEGREES = 12f

/**
 * The rating a released drag gives: left is Again, right is Good, short of
 * the line none. A card still [armed] rates, so letting go keeps the
 * promise the hint and haptic made.
 */
internal fun swipeRating(
    offset: Float,
    armed: Boolean,
    velocity: Float,
    width: Float,
    minFlingVelocity: Float,
    thresholds: SwipeThresholds,
): Recall? {
    if (width <= 0f || offset == 0f) return null
    val travelled = abs(offset) / width
    val flung = thresholds.fling != null && travelled >= thresholds.fling &&
        abs(velocity) >= minFlingVelocity && sign(velocity) == sign(offset)
    if (!armed && travelled < thresholds.commit && !flung) return null
    return if (offset < 0f) Recall.AGAIN else Recall.GOOD
}

/**
 * Whether the card shows that letting go will rate it. It drops the
 * promise a little short of where it made it, so a finger resting on the
 * line does not make it flicker and buzz.
 */
internal fun swipeArmed(wasArmed: Boolean, offset: Float, width: Float, thresholds: SwipeThresholds): Boolean {
    if (width <= 0f) return false
    val travelled = abs(offset) / width
    return travelled >= if (wasArmed) thresholds.commit - SWIPE_HYSTERESIS else thresholds.commit
}

/** Whether a drag past touch slop is sideways enough to be the card's rather than the page scroll's. */
internal fun swipeClaims(dx: Float, dy: Float): Boolean = abs(dx) > abs(dy) * 1.5f

@Composable
internal fun rememberIntervalFormat(): MeasureFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
}

internal fun MeasureFormat.formatInterval(minutes: Int): String = format(
    when {
        minutes % 1440 == 0 -> Measure(minutes / 1440, MeasureUnit.DAY)
        minutes % 60 == 0 -> Measure(minutes / 60, MeasureUnit.HOUR)
        else -> Measure(minutes, MeasureUnit.MINUTE)
    },
)

/**
 * A word card on top of a small deck. Once [enabled], dragging it left
 * rates the word Again and right rates it Good; it tilts as it goes, flies
 * off when let go past the line and springs back otherwise. [onSwipe]
 * answers whether the rating was taken, and a refused one brings the card
 * back. Without motion (e-ink, animations removed) the card stays still
 * and the caller's buttons do the rating; TalkBack gets both ratings as
 * actions either way. [card] is told whether this device swipes at all,
 * so it can say so, and gets those actions to put on the node TalkBack
 * focuses. [onFlight] tells the caller when a released card is on its
 * way out, so its own rating buttons can wait for that rating.
 *
 * [depth] is how many cards to draw underneath, at most two, so a last
 * word does not pretend more are waiting. [layers] are their near and far
 * colors; null draws none.
 */
@Composable
internal fun SwipeDeck(
    depth: Int,
    layers: Pair<Color, Color>?,
    shape: Shape,
    enabled: Boolean,
    thresholds: SwipeThresholds,
    streak: Int,
    onSwipe: suspend (Recall) -> Boolean,
    onFlight: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    card: @Composable (swipes: Boolean, actions: Modifier) -> Unit,
) {
    val still = LocalEInk.current || rememberMotionRemoved()
    val swipeable = enabled && !still
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    // The pointer handler outlives a window resize, so it reads the width through this.
    val windowWidth by rememberUpdatedState(LocalWindowInfo.current.containerSize.width)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnFlight by rememberUpdatedState(onFlight)
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var left by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var flying by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val enter = remember { Animatable(if (still) 1f else 0.94f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(160)) }
    val minFling = with(density) { 800.dp.toPx() }

    fun release(velocity: Float) {
        val rating = swipeRating(offset, armed, velocity, width.toFloat(), minFling, thresholds)
        armed = false
        settle = scope.launch {
            if (rating == null) {
                animate(offset, 0f, velocity, spring()) { value, _ -> offset = value }
                return@launch
            }
            flying = true
            currentOnFlight(true)
            try {
                var accepted = false
                try {
                    animate(offset, sign(offset) * width * 1.5f, velocity, tween(180)) { value, _ -> offset = value }
                } finally {
                    // The finger has lifted, so the rating stands even if the card leaves the screen mid-flight.
                    accepted = withContext(NonCancellable) { currentOnSwipe(rating) }
                }
                if (!accepted) animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
            } finally {
                flying = false
                currentOnFlight(false)
            }
        }
    }

    fun springBack() {
        armed = false
        settle = scope.launch { animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value } }
    }

    val again = stringResource(R.string.vocabulary_again)
    val good = stringResource(R.string.vocabulary_good)
    val actions = Modifier.semantics {
        if (enabled && !flying) {
            customActions = listOf(
                CustomAccessibilityAction(again) { scope.launch { withContext(NonCancellable) { currentOnSwipe(Recall.AGAIN) } }; true },
                CustomAccessibilityAction(good) { scope.launch { withContext(NonCancellable) { currentOnSwipe(Recall.GOOD) } }; true },
            )
        }
    }
    val progress = if (width > 0) (abs(offset) / width / thresholds.commit).coerceIn(0f, 1f) else 0f
    Box(modifier) {
        if (layers != null) {
            val (near, far) = layers
            if (depth >= 2) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(horizontal = 28.dp)
                        .background(far, shape),
                )
            }
            if (depth >= 1) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(top = 5.dp, start = 14.dp, end = 14.dp)
                        .graphicsLayer {
                            scaleX = 1f + progress * 0.04f
                            translationY = progress * 5.dp.toPx()
                        }
                        .background(near, shape),
                )
            }
        }
        Box(
            Modifier
                .padding(top = if (layers != null) 10.dp else 0.dp)
                .onSizeChanged { width = it.width }
                .onGloballyPositioned { left = it.positionInWindow().x }
                .pointerInput(swipeable, thresholds) {
                    if (!swipeable) return@pointerInput
                    // The screen's sides belong to the system back gesture.
                    val edge = 24.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x = left + down.position.x
                        if (flying || x < edge || (windowWidth > 0 && x > windowWidth - edge)) return@awaitEachGesture
                        var moved = Offset.Zero
                        lateinit var claim: PointerInputChange
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: return@awaitEachGesture
                            if (!change.pressed || change.isConsumed) return@awaitEachGesture
                            moved += change.positionChange()
                            if (moved.getDistance() > viewConfiguration.touchSlop) {
                                if (!swipeClaims(moved.x, moved.y)) return@awaitEachGesture
                                change.consume()
                                claim = change
                                break
                            }
                        }
                        settle?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        fun follow(dx: Float, change: PointerInputChange) {
                            offset += dx
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val nowArmed = swipeArmed(armed, offset, width.toFloat(), thresholds)
                            thresholdTransitionHaptic(armed, nowArmed)?.let(view::performReaderHaptic)
                            armed = nowArmed
                        }
                        // The card follows the finger from where it went down, slop included.
                        follow(moved.x, claim)
                        var finished = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                // The lift's own sample lets a drag that stopped before release read as slow.
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                finished = true
                                break
                            }
                            follow(change.positionChange().x, change)
                            change.consume()
                        }
                        // A cancelled drag never rates, however far the card went.
                        if (finished) release(tracker.calculateVelocity().x) else springBack()
                    }
                }
                .graphicsLayer {
                    translationX = offset
                    rotationZ = if (width > 0) offset / width * MAX_TILT_DEGREES else 0f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleX = enter.value
                    scaleY = enter.value
                    alpha = enter.value
                },
        ) {
            card(!still, actions)
            if (offset != 0f) {
                val rating = if (offset < 0f) Recall.AGAIN else Recall.GOOD
                SwipeHint(
                    rating = rating,
                    label = if (rating == Recall.AGAIN) again else good,
                    streak = streak,
                    modifier = Modifier
                        .align(if (rating == Recall.GOOD) Alignment.TopStart else Alignment.TopEnd)
                        .padding(12.dp)
                        .graphicsLayer {
                            // Opaque early so the pill never blends with the text under it.
                            alpha = (progress * 2f).coerceAtMost(1f)
                            scaleX = 0.85f + 0.15f * progress
                            scaleY = scaleX
                        },
                )
            }
        }
    }
}

/** What letting go will do, said with an icon and the next interval as well as a color. */
@Composable
private fun SwipeHint(rating: Recall, label: String, streak: Int, modifier: Modifier = Modifier) {
    val format = rememberIntervalFormat()
    val interval = format.formatInterval(scheduleVocabulary(streak, rating, 0).intervalMinutes)
    val again = rating == Recall.AGAIN
    val colors = recallColors(rating)
    val container = colors.container
    val content = colors.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            if (again) Icons.Outlined.Replay else Icons.Outlined.Check,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "$label · $interval",
            color = content,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
