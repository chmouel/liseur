package com.chmouel.liseur.reader.chrome

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.graphics.Rect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The web view showing the chapter being read.
 *
 * Readium keeps the neighbouring chapters attached to the pager, so
 * being a web view is not enough: the one covering the middle of the
 * reader is the one the reader is reading. The widest visible one is
 * the fallback for the moment when nothing covers that point, which is
 * better than doing nothing at all.
 */
internal fun visibleWebView(root: View): WebView? {
    val origin = IntArray(2)
    root.getLocationOnScreen(origin)
    val x = origin[0] + root.width / 2
    val y = origin[1] + root.height / 2
    val visible = mutableListOf<Pair<WebView, Rect>>()
    collectVisibleWebViews(root, visible)
    return visible.firstOrNull { (_, rect) -> rect.contains(x, y) }?.first
        ?: visible.maxByOrNull { (_, rect) -> rect.width() * rect.height() }?.first
}

private fun collectVisibleWebViews(view: View, into: MutableList<Pair<WebView, Rect>>) {
    when {
        view is WebView -> {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect) && !rect.isEmpty) into += view to rect
        }

        view is ViewGroup ->
            for (i in 0 until view.childCount) collectVisibleWebViews(view.getChildAt(i), into)
    }
}

/**
 * Every time the reader's views are laid out.
 *
 * Readium reports having moved to a resource before it has laid that
 * resource out, so anything that needs the web view rather than the
 * position cannot be driven by positions alone — and on the page a book
 * opens at there is no second position coming to try again on. Layout is
 * the signal that actually says a view now exists, and unlike a bounded
 * wait it cannot run out on a device that took longer than expected.
 *
 * It fires often and says nothing about what changed, so it is a prompt
 * to go and look, not news.
 */
internal fun layoutPasses(root: View): Flow<Unit> = callbackFlow {
    val observer = root.viewTreeObserver
    val listener = ViewTreeObserver.OnGlobalLayoutListener { trySend(Unit) }
    observer.addOnGlobalLayoutListener(listener)
    awaitClose {
        // The observer a view hands out is replaced when it is moved
        // between windows, and the dead one throws if written to.
        val current = root.viewTreeObserver
        if (current.isAlive) current.removeOnGlobalLayoutListener(listener)
    }
}.conflate()
