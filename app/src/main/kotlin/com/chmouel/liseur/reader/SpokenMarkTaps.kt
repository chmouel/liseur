package com.chmouel.liseur.reader

import android.view.View
import android.webkit.WebView
import com.chmouel.liseur.reader.chrome.attachedWebViews
import com.chmouel.liseur.translate.PageSentence
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * A tap on the sentence being read aloud, on a page the reader had
 * translated.
 *
 * Untranslated, the sentence's words are a Readium decoration, and the
 * listener on it toggles the chrome — with the player, and its Stop —
 * rather than turning the page under the voice. A translated page marks
 * the words itself, with paint only, so no decoration is ever activated
 * and the tap falls through to the tap zones, which turn the page in
 * their side zones.
 *
 * Registered before the zones, this listener claims the tap while the
 * voice reads the translation and asks the page whether the finger
 * landed on the words it marks: on them, the chrome is toggled, exactly
 * as the decoration's listener does; off them, or when the page does
 * not answer in time, the event is handed to [onZoneTap] — the zones
 * themselves — so the tap means what it would have meant anyway.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class SpokenMarkTaps(
    private val scope: CoroutineScope,
    private val gate: () -> Boolean,
    private val sentence: () -> PageSentence?,
    private val isCurrent: () -> Boolean,
    private val isPinching: () -> Boolean = { false },
    private val onToggleChrome: () -> Unit,
    private val onZoneTap: (TapEvent) -> Unit,
    private val markedOnPage: suspend (TapEvent, PageSentence) -> Boolean,
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
        if (!gate()) return false
        // A tap still being decided takes no second one. Handing it to
        // the zones instead would let one touch both turn the page and
        // toggle the chrome a blink later; queueing it would act on a
        // page the reader has stopped looking at. A decision lasts at
        // most [DECIDE_MS], and a dropped tap can be made again.
        if (deciding) return true
        deciding = true
        scope.launch {
            try {
                val spoken = sentence()
                // The page is given [DECIDE_MS] to answer, all its views
                // counted together: a tap that waits any longer stops
                // feeling like a tap.
                val onMark = spoken != null &&
                    withTimeoutOrNull(DECIDE_MS) { markedOnPage(event, spoken) } == true
                // The reader may have turned away, or a second finger may
                // have turned the tap into a pinch, while the page answered.
                if (!isCurrent() || isPinching()) return@launch
                if (onMark) onToggleChrome() else onZoneTap(event)
            } finally {
                deciding = false
            }
        }
        return true
    }

    private var deciding = false

    companion object {
        /**
         * The longest a tap is held for the page's answer. A tap that
         * waits for the page to get around to replying stops feeling
         * like a tap, so past this it is the zones' to answer, as it
         * would have been at once.
         */
        const val DECIDE_MS = 250L

        /**
         * Whether this listener takes the tap: the page is translated,
         * the voice is reading its translation with a sentence on it to
         * find, and the page paints the mark itself. When a WebView too
         * old to paint one leaves the words to a Readium decoration,
         * that decoration's own listener takes the tap — and two
         * listeners must not toggle the chrome twice.
         */
        fun claims(
            pinching: Boolean,
            pageTranslated: Boolean,
            sentence: PageSentence?,
            decorationOwnsMark: Boolean,
        ): Boolean = !pinching && pageTranslated && sentence != null && !decorationOwnsMark

        /** What the page's answer says: on the marked words, or anything else — off them, another document, no answer at all. */
        fun onMark(answer: String?): Boolean = answer?.trim() == "true"

        /**
         * The tap point, in the publication view's pixels, as fractions
         * of a web view placed at [left], [top] in the same space and
         * [width] by [height], or null when the point is outside it.
         */
        fun fraction(x: Float, y: Float, left: Float, top: Float, width: Float, height: Float): Pair<Float, Float>? {
            if (width <= 0f || height <= 0f) return null
            val fx = (x - left) / width
            val fy = (y - top) / height
            if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return null
            return fx to fy
        }
    }
}

/**
 * Whether [event]'s tap landed on the shown words the page under [root]
 * marks as [sentence]'s. The point arrives in the publication view's
 * pixels and is sent on as a fraction of the web view holding the
 * sentence's chapter, which is the only one asked; the fraction is the
 * same fraction however the page scales its words.
 */
internal suspend fun pageMarksAt(root: View, event: TapEvent, sentence: PageSentence): Boolean {
    val origin = IntArray(2)
    root.getLocationInWindow(origin)
    val x = origin[0] + event.point.x
    val y = origin[1] + event.point.y
    for (web in attachedWebViews(root)) {
        val url = web.url ?: continue
        if (ResourceAddress.canonicalPath(url) != sentence.href) continue
        web.getLocationInWindow(origin)
        val at = SpokenMarkTaps.fraction(
            x, y,
            origin[0].toFloat(), origin[1].toFloat(),
            web.width.toFloat(), web.height.toFloat(),
        ) ?: continue
        val answer = web.evaluate(PageSwaps.markedAt(url, sentence, at.first, at.second))
        if (SpokenMarkTaps.onMark(answer)) return true
    }
    return false
}

// What [script] gives back, or null when the view cannot be asked at all.
private suspend fun WebView.evaluate(script: String): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
