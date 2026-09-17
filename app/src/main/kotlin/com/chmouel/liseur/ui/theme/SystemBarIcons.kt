package com.chmouel.liseur.ui.theme

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Points the system-bar icons at whatever is actually behind them.
 *
 * `enableEdgeToEdge()` sets the icon colour once, from the configuration
 * the activity happened to be created in. It is never consulted again, so
 * a theme the reader changes while the app is running leaves black icons
 * on a black bar until something recreates the activity.
 *
 * This says it in a way that survives the change: keyed on [dark], so it
 * is reapplied whenever the answer moves, whether that is the app theme
 * being switched in Settings or the reader's page differing from it.
 *
 * What the bars looked like on the way in is captured and put back on the
 * way out, so an activity that borrows them — the reader, whose page
 * colours are its own — hands them back rather than leaving the next one
 * wearing them.
 *
 * The window this speaks for is the one it is composed in, not the
 * activity's: a sheet is a window of its own, laid out over the bars, and
 * while it is up its flags are the ones the system reads. See
 * [hostWindow].
 *
 * @param dark whether what sits under the bars is dark, so the icons on
 *   top of it have to be light.
 */
@Composable
internal fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        view.hostWindow()?.let { WindowCompat.getInsetsController(it, view) }
    }
    // Read before anything below has had a chance to change them.
    val original = remember(controller) {
        controller?.let { it.isAppearanceLightStatusBars to it.isAppearanceLightNavigationBars }
    }
    DisposableEffect(controller, dark) {
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {}
    }
    DisposableEffect(controller, original) {
        onDispose {
            if (controller != null && original != null) {
                controller.isAppearanceLightStatusBars = original.first
                controller.isAppearanceLightNavigationBars = original.second
            }
        }
    }
}

/**
 * The window whose bars this view's content is actually drawn under.
 *
 * A dialog or a sheet is not part of the activity's window: Compose puts
 * it in one of its own, laid out over the system bars, and the system
 * reads the appearance flags of the topmost window that spans them. So
 * setting the activity's flags from inside a sheet changes nothing on
 * screen until the sheet goes away.
 *
 * The view a dialog composes into hangs off a parent that names its
 * window; anything else belongs to the activity.
 */
private fun View.hostWindow(): Window? =
    (parent as? DialogWindowProvider)?.window ?: (context as? Activity)?.window

/**
 * Whether a surface is dark enough that the icons drawn over it have to
 * be light.
 *
 * Material asks this of a sheet's ink; asked of its paper it is the same
 * question the other way up, and the paper is what [SystemBarIcons] is
 * told about. The threshold is Material's own, so a sheet and the bars
 * above it cannot come to different conclusions about the same colour.
 */
internal fun isDarkSurface(color: Color): Boolean = color.luminance() < 0.5f
