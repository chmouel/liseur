package com.chmouel.liseur.reader

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.StyleRes
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme

/**
 * The style that tells a book's pages whether they are light or dark.
 *
 * A page's CSS `prefers-color-scheme` does not come from Readium or from
 * any web setting. The system WebView answers it from
 * `android:isLightTheme` on the theme of the context the WebView was
 * created with, which is the reader activity. `Theme.Liseur` only sets
 * that attribute through `values-night`, so every book saw the system's
 * night mode. Standard Ebooks inverts its black-on-transparent title
 * and logo art when that query says dark, and on a light page that
 * leaves the art white on white (issue #256).
 *
 * The attribute exists from API 29. Below that the styles are empty and
 * the page follows the system, as before.
 */
@StyleRes
internal fun pageColorSchemeStyle(page: ReaderTheme): Int =
    if (page.isDarkPage) R.style.PageColorScheme_Dark else R.style.PageColorScheme_Light

/**
 * Makes [page] the colour scheme the book's pages report.
 *
 * Forcing the style onto the activity's theme covers every WebView
 * Readium creates or reuses afterwards, and it holds across a system
 * night-mode switch, which rebases the theme but keeps forced styles.
 * Pages already on screen read the attribute again only on a
 * configuration change, so they are sent one. That repaints them in
 * place, without a reload, so the reading position is left alone.
 */
internal fun Activity.showPagesAs(page: ReaderTheme) {
    theme.applyStyle(pageColorSchemeStyle(page), true)
    window.decorView.forEachWebView { it.dispatchConfigurationChanged(resources.configuration) }
}

private fun View.forEachWebView(action: (WebView) -> Unit) {
    when (this) {
        is WebView -> action(this)
        is ViewGroup -> for (i in 0 until childCount) getChildAt(i).forEachWebView(action)
    }
}
