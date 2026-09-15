package com.chmouel.liseur.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the system has been asked to stop animating things.
 *
 * Android spells that request as scale factors rather than as a switch:
 * Accessibility -> Remove animations sets all of them to zero at once,
 * and developer options can set each on its own. Anything at zero is a
 * reader who has said they do not want motion, so either is enough.
 *
 * Written as "not above zero" rather than "equal to zero", because the
 * value comes out of a settings table that anything with the right
 * permission can write and is parsed with `Float.parseFloat`, which
 * answers for `NaN` and for a negative number as readily as for a
 * scale. Neither of those is a slower animation, and neither compares
 * usefully with `<=`.
 */
fun motionRemoved(animatorScale: Float, transitionScale: Float): Boolean =
    !(animatorScale > 0f) || !(transitionScale > 0f)

/** The same question, asked of the system this build is running on. */
fun motionRemoved(resolver: ContentResolver): Boolean = motionRemoved(
    animatorScale = scaleOf(resolver, Settings.Global.ANIMATOR_DURATION_SCALE),
    transitionScale = scaleOf(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE),
)

private fun scaleOf(resolver: ContentResolver, key: String): Float =
    Settings.Global.getFloat(resolver, key, 1f)

/**
 * Follows [motionRemoved] while the screen is up.
 *
 * Worth observing rather than reading once: the setting is two taps away
 * in Settings, and a reader who turns it off mid-book should not have to
 * close the book to be believed. Both keys are watched, since the
 * accessibility switch writes both and developer options writes either.
 *
 * Resuming re-reads them as well, and that is not belt and braces. On
 * Android 8 and 9 the settings provider notifies the system user only,
 * so a Liseur running in a work profile or a second user hears nothing;
 * and going to Settings to change this only pauses the reader, which is
 * not enough to bring the composition back. Coming back from Settings
 * is exactly when the answer has usually just changed.
 */
@Composable
fun rememberMotionRemoved(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var removed by remember(resolver) { mutableStateOf(motionRemoved(resolver)) }
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                removed = motionRemoved(resolver)
            }
        }
        listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
        ).forEach { key ->
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        }
        val onResume = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) removed = motionRemoved(resolver)
        }
        lifecycle.addObserver(onResume)
        // The setting may have moved while this screen was away, and the
        // observer only hears what happens from here on.
        removed = motionRemoved(resolver)
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(onResume)
        }
    }
    return removed
}
