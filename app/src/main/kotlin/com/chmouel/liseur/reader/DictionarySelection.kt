package com.chmouel.liseur.reader

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import com.chmouel.liseur.reader.chrome.visibleWebView
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

internal class DictionarySelection private constructor(
    private val web: WebView,
    private val url: String,
    private val key: String,
) {
    var displayedText: String = ""
        private set

    private var pressStarted: Long? = null
    private var pressX = 0f
    private var pressY = 0f

    private fun stillShows(nav: EpubNavigatorFragment): Boolean =
        web.isAttachedToWindow && web.url == url && nav.view?.let(::visibleWebView) === web

    suspend fun restore(nav: EpubNavigatorFragment, stillWanted: () -> Boolean): Boolean {
        if (!stillShows(nav) || !stillWanted()) return false
        web.requestFocus()
        val result = evaluate(web, """
            (function () {
              var saved = window[$key];
              if (!saved || !saved.range.startContainer.isConnected ||
                  !saved.range.endContainer.isConnected || saved.range.toString() !== saved.text) return false;
              var selection = window.getSelection();
              if (!selection) return false;
              var range = selection.rangeCount ? selection.getRangeAt(0) : null;
              if (range && range.compareBoundaryPoints(Range.START_TO_START, saved.range) === 0 &&
                  range.compareBoundaryPoints(Range.END_TO_END, saved.range) === 0) {
                delete window[$key];
                return true;
              }
              var rects = saved.range.getClientRects();
              for (var i = 0; i < rects.length; i++) {
                var r = rects[i];
                if (r.width > 0 && r.height > 0 && r.left >= 0 && r.right <= innerWidth &&
                    r.top >= 0 && r.bottom <= innerHeight) {
                  selection.removeAllRanges();
                  return [(r.left + r.right) / 2, (r.top + r.bottom) / 2, innerWidth];
                }
              }
              return false;
            })();
        """.trimIndent())
        if (result == "true") return stillShows(nav) && stillWanted()
        val point = runCatching { JSONArray(result ?: return false) }.getOrNull() ?: return false
        if (point.length() != 3 || !stillShows(nav) || !stillWanted()) return false
        val scale = web.width / point.getDouble(2)
        pressX = (point.getDouble(0) * scale).toFloat()
        pressY = (point.getDouble(1) * scale).toFloat()
        pressStarted = SystemClock.uptimeMillis()
        try {
            // DOM ranges alone do not reactivate Chromium's native draggable handles
            touch(MotionEvent.ACTION_DOWN)
            // Chromium adds a show-press interval before recognizing a long press
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 250)
            if (!stillShows(nav) || !stillWanted() || pressStarted == null) return false
            touch(MotionEvent.ACTION_UP)
            pressStarted = null
            if (!stillWanted()) return false
            return evaluate(web, """
                (function () {
                  var saved = window[$key];
                  delete window[$key];
                  if (!saved || !saved.range.startContainer.isConnected ||
                      !saved.range.endContainer.isConnected || saved.range.toString() !== saved.text) return false;
                  var selection = window.getSelection();
                  if (!selection || selection.isCollapsed) return false;
                  selection.removeAllRanges();
                  selection.addRange(saved.range);
                  return true;
                })();
            """.trimIndent()) == "true" && stillShows(nav) && stillWanted()
        } finally {
            // A newer real touch owns the stream and must not receive our CANCEL
            if (stillWanted()) cancelPress() else pressStarted = null
        }
    }

    private fun touch(action: Int) {
        val started = pressStarted ?: return
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = pressX
            y = pressY
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            started, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            web.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun cancelPress() {
        touch(MotionEvent.ACTION_CANCEL)
        pressStarted = null
    }

    fun discard() {
        cancelPress()
        if (web.isAttachedToWindow && web.url == url) {
            web.evaluateJavascript("delete window[$key];", null)
        }
    }

    companion object {
        suspend fun save(nav: EpubNavigatorFragment, href: String): DictionarySelection? {
            val web = visibleWebView(nav.view ?: return null) ?: return null
            val url = web.url ?: return null
            if (!ResourceAddress.shows(url, href)) return null
            val key = JSONObject.quote("__liseurDictionary_${UUID.randomUUID()}")
            val saved = DictionarySelection(web, url, key)
            var retained = false
            try {
                val result = evaluate(web, """
                    (function () {
                      var selection = window.getSelection();
                      if (!selection || selection.isCollapsed || !selection.rangeCount) return false;
                      var range = selection.getRangeAt(0).cloneRange();
                      window[$key] = {range: range, text: range.toString()};
                      return range.toString();
                    })();
                """.trimIndent())
                val text = TranslatedPages.decodeSelectionText(result) ?: return null
                if (!saved.stillShows(nav)) return null
                saved.displayedText = text
                retained = true
                return saved
            } finally {
                if (!retained) saved.discard()
            }
        }

        private suspend fun evaluate(web: WebView, script: String): String? = withTimeoutOrNull(500L) {
            suspendCancellableCoroutine { continuation ->
                web.evaluateJavascript(script) {
                    if (continuation.isActive) continuation.resume(it) { _, _, _ -> }
                }
            }
        }
    }
}
