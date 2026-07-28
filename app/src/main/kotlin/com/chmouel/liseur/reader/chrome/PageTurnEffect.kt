package com.chmouel.liseur.reader.chrome

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Kindle-style page turn: the departing page is snapshotted, the book
 * jumps to the next page underneath, and the snapshot slides away like
 * a lifted sheet of paper with a soft shadow along its edge.
 */
@Stable
class PageTurnEffectState(private val scope: CoroutineScope) {

    var page by mutableStateOf<ImageBitmap?>(null)
        private set
    var slideLeft by mutableStateOf(true)
        private set
    val progress = Animatable(0f)

    val isRunning: Boolean get() = page != null

    fun start(bitmap: ImageBitmap, slideLeft: Boolean) {
        this.slideLeft = slideLeft
        page = bitmap
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 350, easing = PageEasing))
            page = null
        }
    }

    private companion object {
        /** Quick lift-off, gentle landing. */
        val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

/** Draws the departing page snapshot sliding over the live navigator. */
@Composable
fun PageTurnOverlay(state: PageTurnEffectState, modifier: Modifier = Modifier) {
    val page = state.page ?: return
    Image(
        bitmap = page,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val direction = if (state.slideLeft) -1f else 1f
                translationX = direction * state.progress.value * size.width
                shadowElevation = 24.dp.toPx()
            },
    )
}

/**
 * Performs page turns for taps and volume keys. When the animation
 * preference is on and the effect is available, the turn uses the
 * sliding snapshot; otherwise it falls back to the navigator's plain
 * slide, or an instant jump when animations are disabled.
 */
@OptIn(ExperimentalReadiumApi::class)
class PageTurner(
    private val effect: PageTurnEffectState,
    private val isAnimated: () -> Boolean,
    private val isEffectSuppressed: () -> Boolean,
) {
    var navigator: OverflowableNavigator? = null
    var window: Window? = null

    fun turn(forward: Boolean) {
        val nav = navigator ?: return
        if (!isAnimated()) {
            navigate(nav, forward, animated = false)
            return
        }
        val win = window
        val view = nav.publicationView
        if (win == null || isEffectSuppressed() || effect.isRunning ||
            view.width <= 0 || view.height <= 0
        ) {
            // Rapid taps while a turn is animating jump instantly to
            // keep up with the reader's pace.
            navigate(nav, forward, animated = !effect.isRunning)
            return
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val bounds = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val rtl = nav.overflow.value.readingProgression == ReadingProgression.RTL
        PixelCopy.request(win, bounds, bitmap, { result ->
            val moved = navigate(nav, forward, animated = false)
            if (moved && result == PixelCopy.SUCCESS) {
                effect.start(bitmap.asImageBitmap(), slideLeft = forward != rtl)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun navigate(
        nav: OverflowableNavigator,
        forward: Boolean,
        animated: Boolean,
    ): Boolean =
        if (forward) nav.goForward(animated) else nav.goBackward(animated)
}
