package com.chmouel.liseur.reader.chrome

import android.view.View
import androidx.compose.runtime.withFrameNanos
import android.view.ViewGroup
import android.webkit.WebView
import android.graphics.Rect

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
 * The visible web view, waiting a bounded number of frames for one.
 *
 * Readium lays a resource out after it reports having moved there, so
 * asking the instant the position arrives can be a frame or two early —
 * and on the opening page there is no later position to ask again on.
 * Waiting on frames rather than a clock means this costs nothing on a
 * screen that is not drawing, and gives up rather than holding a
 * coroutine open against a book that will never produce one.
 */
internal suspend fun awaitWebView(root: View, frames: Int = WEB_VIEW_WAIT_FRAMES): WebView? {
    repeat(frames) {
        visibleWebView(root)?.let { return it }
        withFrameNanos { }
    }
    return visibleWebView(root)
}

/** About a second of frames on an ordinary screen. */
private const val WEB_VIEW_WAIT_FRAMES = 60
