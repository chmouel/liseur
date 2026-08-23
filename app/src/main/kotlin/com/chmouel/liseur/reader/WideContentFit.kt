package com.chmouel.liseur.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Keeps content that is wider than the page from painting over the next one.
 *
 * A table is never narrower than the sum of its columns' smallest possible
 * widths, whatever a stylesheet asks for, so Readium's `max-width: 100%` on
 * tables quietly fails on a four-column table at a large font size. Readium
 * then sets `overflow: visible` on the root to stop the same overflow being
 * clipped, but in paginated mode the root is the multi-column box whose
 * columns *are* the pages — so the overflow is not clipped, it is painted on
 * top of the page after it. That is the ghosting the reader sees.
 *
 * The fix is to measure and only then constrain: a table that genuinely does
 * not fit is given `table-layout: fixed`, which lets the columns be squeezed;
 * a long index that merely *looks* wide because it is spread over four pages
 * is left exactly as its author wrote it.
 *
 * Injected at runtime rather than into the resource, because Readium decides
 * whether to link its default stylesheet by looking for `<style` in the
 * resource: shipping one ahead of it would make an unstyled book look styled
 * and cost it Readium's typography entirely.
 */
internal object WideContentFit {
    internal enum class Result {
        /** Something was constrained, or the stylesheet was installed: the page may have moved. */
        CHANGED,

        /** Nothing to do; the layout is untouched. */
        STABLE,

        /** The book's Content-Security-Policy refused the stylesheet. */
        BLOCKED,

        /** No reflowable page to talk to, or the script did not answer. */
        FAILED,
    }

    /**
     * Runs in the book's own document, repeatedly, so it must end where it
     * started when there is nothing to do.
     *
     * Ownership is a token minted per document rather than a fixed class or
     * id: a publisher is free to ship `class="liseur-wide"`, and stripping it
     * from their table — or letting it suppress our own work — would be our
     * bug, not theirs. Only attributes carrying this document's token are ever
     * read or removed.
     *
     * Width is measured from `getClientRects()`, never `getBoundingClientRect()`:
     * across columns the latter returns the union of every fragment, so a
     * perfectly well-behaved 60-row index reports 1382px against a 372px page
     * and would be constrained for no reason. Only the widest single fragment
     * says anything about fitting.
     *
     * The marker is cleared before measuring, or a second run would measure the
     * width we imposed on the first and keep it forever.
     */
    const val SCRIPT: String = """
        (function () {
          var ATTR = "data-liseur-fit";
          var state = window.__liseurFit || (window.__liseurFit = {});
          var installed = false;
          var tok = state.token ||
                    (state.token = "f" + Math.random().toString(36).slice(2, 10));
          var SEL = 'table[' + ATTR + '="' + tok + '"]';

          if (!state.styleEl || !state.styleEl.isConnected) {
            var css = document.createElement("style");
            css.textContent =
              "p,li,dd,dt,td,th,blockquote,figcaption{overflow-wrap:break-word}" +
              SEL + "{table-layout:fixed !important;width:100% !important}" +
              SEL + " td," + SEL + " th{overflow-wrap:anywhere}" +
              'pre[' + ATTR + '="' + tok + '"]{white-space:pre-wrap !important}';
            document.head.appendChild(css);
            state.styleEl = css;
            installed = true;
            if (!css.sheet) return "blocked";
          }

          function contentWidth(el) {
            var s = getComputedStyle(el);
            return el.clientWidth - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight);
          }

          var changed = installed;
          for (var pass = 0; pass < 3; pass++) {
            var els = document.querySelectorAll("table,pre");
            var had = [], i;
            for (i = 0; i < els.length; i++) {
              had.push(els[i].getAttribute(ATTR) === tok);
              if (had[i]) els[i].removeAttribute(ATTR);
            }
            var passChanged = false;
            for (i = 0; i < els.length; i++) {
              var el = els[i], parent = el.parentElement;
              var avail = parent ? contentWidth(parent) : 0;
              var rects = el.getClientRects(), widest = 0;
              for (var j = 0; j < rects.length; j++) {
                if (rects[j].width > widest) widest = rects[j].width;
              }
              var want = avail > 0 && widest > avail + 1;
              if (want) el.setAttribute(ATTR, tok);
              if (want !== had[i]) passChanged = true;
            }
            if (!passChanged) break;
            changed = true;
          }
          return changed ? "changed" : "stable";
        })();
    """

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so a
     * plain string arrives wearing quotes.
     */
    internal fun parse(result: String?): Result {
        val value = result?.trim()?.removeSurrounding("\"")?.trim()
        return when (value) {
            "changed" -> Result.CHANGED
            "stable" -> Result.STABLE
            "blocked" -> Result.BLOCKED
            else -> Result.FAILED
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    internal suspend fun apply(navigator: EpubNavigatorFragment): Result =
        parse(runCatching { navigator.evaluateJavascript(SCRIPT) }.getOrNull())
}
