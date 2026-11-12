package com.chmouel.liseur.reader

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer

/**
 * Readium's `publicationView` is `requireView()`, which throws while the
 * fragment has no view: before it is created, or once it is destroyed.
 */
internal val Fragment.publicationViewOrNull: View?
    get() = view

/**
 * [built] while it has a view, null otherwise.
 *
 * AndroidFragment hands over every fragment it adds, including one added
 * while the host is stopped, which stays at CREATED with no view until
 * the host starts again. Everything keyed on the navigator reads its
 * view at once, so it must not see the fragment before then.
 *
 * One state for the screen's lifetime, read where it is used, as the
 * plain navigator state was: a fragment rebuilt in the foreground
 * already has its view and replaces the old one in the same frame.
 * [onView] gets every view the fragment shows, so it also reaches
 * one created after the fragment was handed over, which the caller's
 * AndroidFragment update could not. That view may appear before this
 * starts watching, and the update may already have handled it, so
 * [onView] must be safe to repeat.
 */
@Composable
internal fun <T : Fragment> rememberViewedFragment(
    built: State<T?>,
    onView: (View) -> Unit,
): State<T?> {
    // Fragment.view is not snapshot state; this ticks when it changes.
    val viewChanges = remember { mutableIntStateOf(0) }
    val viewed = remember {
        derivedStateOf(structuralEqualityPolicy()) {
            viewChanges.intValue
            built.value?.takeIf { it.view != null }
        }
    }
    val fragment = built.value
    DisposableEffect(fragment) {
        if (fragment == null) return@DisposableEffect onDispose {}
        var seen: View? = null
        val observer = Observer<LifecycleOwner?> { owner ->
            val view = fragment.view
            if (owner != null && view != null && view !== seen) onView(view)
            seen = view
            viewChanges.intValue++
        }
        fragment.viewLifecycleOwnerLiveData.observeForever(observer)
        onDispose { fragment.viewLifecycleOwnerLiveData.removeObserver(observer) }
    }
    return viewed
}
