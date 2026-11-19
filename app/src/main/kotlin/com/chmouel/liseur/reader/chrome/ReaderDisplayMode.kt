package com.chmouel.liseur.reader.chrome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.chmouel.liseur.ui.LocalEInk

const val ULTRA_DIM_THRESHOLD = 0.15f
const val MAX_ULTRA_DIM_ALPHA = 0.65f
private const val MIN_WINDOW_BRIGHTNESS = 0.01f

data class ResolvedScreenBrightness(
    val windowBrightness: Float,
    val dimAlpha: Float,
)

/**
 * Splits a [0, 1] brightness override into a hardware backlight level and a
 * software scrim alpha. Below [ULTRA_DIM_THRESHOLD] the hardware backlight is
 * already near its minimum on most panels, so the remaining travel dims via
 * [ResolvedScreenBrightness.dimAlpha] while ramping the window attribute down
 * to 0.
 */
fun resolveScreenBrightness(
    brightness: Float?,
    eInk: Boolean = false,
): ResolvedScreenBrightness {
    if (brightness == null) {
        return ResolvedScreenBrightness(
            windowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            dimAlpha = 0f,
        )
    }
    val clamped = brightness.coerceIn(0f, 1f)
    if (eInk) {
        return ResolvedScreenBrightness(
            windowBrightness = clamped,
            dimAlpha = 0f,
        )
    }
    if (clamped < ULTRA_DIM_THRESHOLD) {
        val fraction = clamped / ULTRA_DIM_THRESHOLD
        return ResolvedScreenBrightness(
            windowBrightness = fraction * MIN_WINDOW_BRIGHTNESS,
            dimAlpha = (1f - fraction) * MAX_ULTRA_DIM_ALPHA,
        )
    }
    val fraction = (clamped - ULTRA_DIM_THRESHOLD) / (1f - ULTRA_DIM_THRESHOLD)
    return ResolvedScreenBrightness(
        windowBrightness = MIN_WINDOW_BRIGHTNESS + fraction * (1f - MIN_WINDOW_BRIGHTNESS),
        dimAlpha = 0f,
    )
}

/**
 * Returns 1.0f to cap HDR headroom to SDR when ultra-dim is active and no
 * full-screen image is open, preventing embedded HDR images or WebView
 * surfaces from punching through the software dim layer.
 */
fun desiredHdrHeadroom(dimAlpha: Float, imageViewerOpen: Boolean): Float =
    if (dimAlpha > 0f && !imageViewerOpen) 1.0f else 0.0f

fun resolveWindowColorMode(
    imageViewerOpen: Boolean,
    isWideColorGamutDisplay: Boolean,
): Int =
    if (imageViewerOpen && isWideColorGamutDisplay) {
        ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
    } else {
        ActivityInfo.COLOR_MODE_DEFAULT
    }

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun UltraDimOverlay(
    dimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    if (dimAlpha <= 0f || LocalEInk.current) return
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = dimAlpha.coerceIn(0f, 1f))),
    )
}
