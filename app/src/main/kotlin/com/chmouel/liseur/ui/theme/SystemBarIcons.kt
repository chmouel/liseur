package com.chmouel.liseur.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
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
 * @param dark whether what sits under the bars is dark, so the icons on
 *   top of it have to be light.
 */
@Composable
internal fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
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
