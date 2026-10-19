package com.chmouel.liseur.reader.footnotes

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

/**
 * A same-document link tap reaching the same funnel as every other link.
 *
 * Readium has two doors into the app's link handling, and a plain
 * same-document anchor — `<a href="#fn1">`, the note a few pages later in
 * the same chapter file, the classic EPUB2 spelling — goes through neither.
 * Readium's footnote pass only selects `a[epub:type=noteref]` with the
 * attribute value spelled exactly that way, and Chromium never calls
 * `shouldOverrideUrlLoading` for a same-document fragment navigation, by
 * design. The WebView therefore jumps silently: no card, no way back, Back
 * out of the reader, and the note saved as the reader's place.
 *
 * So the document itself is asked, in a listener installed after
 * Readium's own, to hand the tap over instead. The listener is bubble
 * phase and registered last, so a click meets, in order: our capture-phase
 * token mint (below), the publisher's handlers, Readium's own
 * (selection check, decoration hit-test, footnote pass), then ours. A
 * click Readium or the publisher consumed has `defaultPrevented` set by
 * then and is left alone; so is a click dismissing a selection, an anchor
 * Readium itself handles, and anything pointing anywhere but this
 * document — those are the WebView's to follow, and Readium intercepts
 * cross-document loads as it always has.
 *
 * The script must be plain text with nothing interpolated, so the harness
 * in `hack/verify-same-document-links` can lift it out of this file and
 * run the very lines the app ships.
 *
 * [EpubNavigatorFragment.evaluateJavascript] is reflowable-only and
 * the page repair pass does not run for fixed layout, so a fixed-layout
 * book keeps today's behaviour.
 */
internal object SameDocumentLinks {

    internal enum class Result {
        /** The listeners went in on this pass. */
        INSTALLED,

        /** A previous pass had already put them in. */
        PRESENT,

        /** No reflowable page to talk to, or the script did not answer. */
        FAILED,
    }

    const val SCRIPT: String = """
        (function () {
          var state = window.__liseurLinks || (window.__liseurLinks = {});
          if (state.installed) return "present";
          state.installed = true;
          // First thing in every click dispatch: the token the Kotlin
          // side correlates a consumed decoration with. Capture phase, so
          // it precedes every handler anyone else registers.
          document.addEventListener("click", function () {
            try { LiseurLinks.beginClick(); } catch (e) { }
          }, true);
          // The interception itself: bubble phase, registered after
          // Readium's load-time handler, so a click Readium consumed has
          // already been preventDefaulted by the time we look.
          document.addEventListener("click", function (e) {
            if (e.defaultPrevented) return;
            var selection = window.getSelection && window.getSelection();
            if (selection && !selection.isCollapsed) return;
            var target = e.target;
            var anchor = target && target.closest ? target.closest("a") : null;
            if (!anchor) return;
            // Readium itself handles exactly this spelling (its selector
            // is a[epub:type=noteref], attribute value verbatim); every
            // other spelling of a note reference is left to us.
            if (anchor.getAttribute("epub:type") === "noteref") return;
            var href = anchor.getAttribute("href");
            if (!href) return;
            var resolved, here;
            try {
              resolved = new URL(href, document.baseURI);
              // The document's own address, not the base URI, so a
              // <base> pointing elsewhere cannot make another chapter's
              // link look local.
              here = new URL(location.href);
            } catch (err) {
              return;
            }
            if (resolved.origin !== here.origin) return;
            if (resolved.pathname !== here.pathname) return;
            if (resolved.search !== here.search) return;
            // Raw and undecoded: the Kotlin side's Url.fragment decodes,
            // and decoding here as well would corrupt an id that
            // literally contains %.
            var fragment = resolved.hash.slice(1);
            if (!fragment) return;
            e.preventDefault();
            try { LiseurLinks.follow(fragment); } catch (e2) { }
          }, false);
          return "installed";
        })();
    """

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so a
     * plain string arrives wearing quotes.
     */
    internal fun parse(result: String?): Result {
        val value = result?.trim()?.removeSurrounding("\"")?.trim()
        return when (value) {
            "installed" -> Result.INSTALLED
            "present" -> Result.PRESENT
            else -> Result.FAILED
        }
    }

    /**
     * Puts the listeners into this document. Idempotent, and the state
     * object lives on the document's `window`, so a web view reused for
     * the next chapter starts clean.
     *
     * The answer is an acknowledgment only: unlike [FootnoteLayout] there
     * is no stylesheet to refuse and nothing here can move the page, so
     * the caller does not fold it into any page-moved decision.
     */
    @OptIn(ExperimentalReadiumApi::class)
    internal suspend fun apply(navigator: EpubNavigatorFragment): Result {
        val answer = try {
            navigator.evaluateJavascript(SCRIPT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: Exception) {
            null
        }
        return parse(answer)
    }
}

/**
 * The link a same-document tap leads to, or null for a blank fragment.
 *
 * The payload is a fragment and only ever a fragment: it is never parsed
 * as a URL, never resolved, never appended to a path. The destination is
 * the emitting resource's own address with its fragment *replaced* by the
 * payload, encoded form preserved verbatim — so no spelling of the payload
 * can leave the resource it arrived from, and this route can never carry
 * a scheme, a host or another chapter, let alone reach the external-link
 * confirmation's side of the door.
 */
internal fun sameDocumentDestination(resource: Link, fragment: String): Link? {
    if (fragment.isBlank()) return null
    val href = resource.url().removeFragment().toString() + "#" + fragment
    return Url(href)?.let { Link(href = it) }
}

/**
 * Click tokens, correlated per click rather than by a clock.
 *
 * Readium hands a decoration's activation to the app *before* the click's
 * default action runs, and a consumed activation does not stop the click:
 * without something to correlate them, one tap could open the annotation
 * sheet and navigate. The capture-phase listener mints a token at the
 * start of every click dispatch on the WebView's bridge thread; a
 * decoration listener that consumes marks the token; the follow call,
 * later in the same dispatch on the same thread, asks whether the current
 * click's token was marked. Dispatch order does the synchronising — no
 * elapsed-time assumption, no cross-thread visibility question, and two
 * separate rapid clicks each get their own token and are judged
 * independently.
 */
internal class SameDocumentClicks {
    private val next = AtomicInteger()
    private val current = AtomicInteger()
    private val consumed = ConcurrentHashMap<Int, Boolean>()

    /** The bridge thread, capture phase of every click. */
    fun beginClick(): Int {
        val token = next.incrementAndGet()
        current.set(token)
        return token
    }

    /** The bridge thread, from a decoration listener that consumed this click. */
    fun markConsumed() {
        consumed[current.get()] = true
    }

    /** The bridge thread, later in the same click: was it consumed by a decoration? */
    fun currentClickConsumed(): Boolean = consumed[current.get()] == true
}

/**
 * The `LiseurLinks` JavaScript interface, one per resource: the factory
 * Readium calls it with is the resource the interface was injected into,
 * so the callback carries the *emitting* document, not the navigator's
 * published position, which is documented to lag the shown resource.
 */
internal class SameDocumentLinkBridge(
    private val resource: Link,
    private val clicks: SameDocumentClicks,
    private val onSameDocumentLink: (resource: Link, fragment: String) -> Unit,
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {
    @JavascriptInterface
    fun beginClick() {
        clicks.beginClick()
    }

    @JavascriptInterface
    fun follow(fragment: String) {
        if (fragment.isEmpty()) return
        // Decided here, on the bridge thread, before any main-thread hop:
        // the decoration that consumed this click ran on this same thread,
        // in this same dispatch, so the verdict cannot be outpaced.
        if (clicks.currentClickConsumed()) return
        post(Runnable { onSameDocumentLink(resource, fragment) })
    }
}
