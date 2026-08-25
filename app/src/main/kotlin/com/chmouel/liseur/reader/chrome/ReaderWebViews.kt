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

/**
 * The same web view, held on to between frames.
 *
 * A reader carrying itself down the page asks this question sixty times
 * a second, and [visibleWebView] answers it by walking the whole view
 * tree. So the answer is kept, and what is asked each frame instead is
 * the cheap half of the same question: is the view still attached, and
 * does it still cover the middle of the reader — one rect against one
 * point, no traversal and no allocation.
 *
 * The check has to be that question and not a weaker one. Readium keeps
 * the neighbouring chapters attached to its pager, so a chapter the
 * reader has left goes on being attached and, as far as `isShown` is
 * concerned, shown. Only covering the centre means *current*, because
 * that is what [visibleWebView] means by it.
 *
 * When nothing covers the centre — mid-transition — [visibleWebView]
 * falls back to the largest visible view, which by construction fails
 * the check, so the cache spends those frames looking the answer up
 * again. That is the cost paid on every frame before this existed, and
 * it stops as soon as the new chapter settles under the centre.
 *
 * Not thread-safe, by [CachedLookup]: views belong to the main thread
 * anyway.
 */
internal fun visibleWebViewCache(root: View): CachedLookup<WebView> {
    val origin = IntArray(2)
    val bounds = Rect()
    return CachedLookup(
        stillGood = { web ->
            if (!web.isAttachedToWindow) {
                false
            } else {
                root.getLocationOnScreen(origin)
                val x = origin[0] + root.width / 2
                val y = origin[1] + root.height / 2
                web.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
            }
        },
        lookup = { visibleWebView(root) },
    )
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
