package com.chmouel.liseur.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the start handle of a selection on the word that was selected.
 *
 * Long-pressing the *first* word of a paragraph selects it correctly, but
 * the WebView paints the start handle on the paragraph's last automatic
 * hyphen — or nowhere, when that hyphen is in another column. Dragging
 * either handle then re-anchors the selection on that bogus bound, so the
 * reader gets the whole paragraph and cannot shrink it. This is Chromium
 * issue 522869957 (and 41496034 for `&shy;`): a hyphen is generated text
 * whose offsets are relative to itself, `{0, 1}`, and the selection painter
 * compares those against the paragraph's text-content offset, so a
 * selection starting at offset 0 or 1 makes every hyphen in the paragraph
 * claim the start bound, and the last one painted wins. The DOM range is
 * never wrong; only the handles are. Liseur hyphenates justified text by
 * default and publishers ship soft hyphens regardless, so the fix applies
 * whatever the typography says.
 *
 * The workaround pushes the first letter of every eligible block to
 * text-content offset 2 with generated content that takes no space:
 * `::before { content: "\a0\a0"; font-size: 0 }`. Two characters, because
 * offset 1 triggers the bug as surely as offset 0. Non-breaking spaces
 * rather than zero-width spaces, because Blink's `::first-letter` skips text
 * that is only spaces (U+00A0 included) and moves on to the real first
 * letter, whereas a zero-width space *becomes* the drop cap. `font-size: 0`
 * alone does not make the spaces free: `letter-spacing` and `word-spacing`
 * inherit as computed lengths, so both are zeroed too. Pseudo content is
 * not in the DOM, so [org.readium.r2.shared.publication.Locator]s,
 * highlights and search never see it.
 *
 * The rule is keyed on a per-document token in the attribute name rather
 * than written as `p::before` for two reasons. A `::before` on a block
 * that contains only blocks — a `section`, a `blockquote` holding paragraphs, a `li` with
 * a `p` inside — gets its own anonymous line box and pushes the content
 * down a line, so only blocks whose first in-flow child is inline content
 * are marked. And an author's own `::before` content must never be
 * overridden, so a block that has one is left alone and keeps the bug.
 *
 * Injected at runtime, like [WideContentFit], because Readium decides
 * whether to link its default stylesheet by looking for `<style` in the
 * resource.
 */
internal object SelectionHandleFix {
    internal enum class Result {
        /** The stylesheet was installed on this run: the page may have moved. */
        CHANGED,

        /** Already in place; the layout is untouched. */
        STABLE,

        /** The book's Content-Security-Policy refused the stylesheet. */
        BLOCKED,

        /** No reflowable page to talk to, or the script did not answer. */
        FAILED,
    }

    /**
     * Runs in the book's own document, possibly more than once, and marks
     * the document's blocks on every pass. The first in-flow child is a
     * static property of the markup, but an author's `::before` may be
     * switched on by a media query after a rotation, and the author's
     * content wins.
     *
     * Ownership is a token minted per document and placed in the attribute's
     * name, as in [com.chmouel.liseur.reader.footnotes.FootnoteLayout]:
     * a publisher's `data-liseur-lead` attribute or CSS selector is then
     * never read from, written over or matched by our rule. Nothing of the
     * author's markup is touched beyond setting that token-owned attribute.
     *
     * Every element is a candidate, judged by its computed `display` rather
     * than its tag, so a `figure` holding text, a `body` with no wrapper
     * or a custom element styled as a block are treated like a `p`.
     * Eligibility skips comments, whitespace-only text, empty inlines and
     * elements taken out of flow (floats, absolute and fixed positioning),
     * since none of them starts a line box, and asks the computed style for
     * `::before`, where `none` and `normal` both mean the author generated
     * nothing.
     */
    const val SCRIPT: String = """
        (function () {
          var state = window.__liseurLead || (window.__liseurLead = {});
          var installed = false;
          var tok = state.token ||
                    (state.token = "l" + Math.random().toString(36).slice(2, 10));
          var ATTR = state.attr || (state.attr = "data-liseur-lead-" + tok);
          var SEL = '[' + ATTR + ']';

          if (!state.styleEl || !state.styleEl.isConnected) {
            var css = document.createElement("style");
            var content = 'content:"\u00a0\u00a0"';
            if (window.CSS && CSS.supports &&
                CSS.supports("content", '"x" / ""')) {
              content += ' / ""';
            }
            css.textContent =
              SEL + '::before{' + content + ' !important;' +
              'font-size:0!important;line-height:0!important;' +
              'letter-spacing:0!important;word-spacing:0!important;' +
              // An author's dormant `display: block; margin: 1em` on a
              // contentless ::before has no box until content arrives;
              // ours must not give it one.
              'display:inline!important;position:static!important;' +
              'float:none!important;margin:0!important;padding:0!important;' +
              'border:0!important;background:none!important;' +
              'box-shadow:none!important;outline:none!important;' +
              'width:auto!important;height:auto!important;' +
              'vertical-align:baseline!important;' +
              'counter-increment:none!important;counter-reset:none!important;' +
              'counter-set:none!important}';
            document.head.appendChild(css);
            state.styleEl = css;
            installed = true;
          }
          if (!state.styleEl.sheet) return "blocked";

          var old = document.querySelectorAll(SEL);
          var had = new Set(), i;
          for (i = 0; i < old.length; i++) {
            had.add(old[i]);
            old[i].removeAttribute(ATTR);
          }
          var kept = 0;

          var BLOCK = {
            "block": 1, "list-item": 1, "flow-root": 1, "inline-block": 1,
            "table-cell": 1, "table-caption": 1
          };

          function outOfFlow(s) {
            return s.getPropertyValue("float") !== "none" ||
                   s.position === "absolute" || s.position === "fixed" ||
                   s.display === "none";
          }

          // Inline elements that are a box of their own, and so start a
          // line box even when empty.
          var ATOMIC = {
            "img": 1, "svg": 1, "math": 1, "video": 1, "audio": 1, "canvas": 1,
            "iframe": 1, "object": 1, "embed": 1, "input": 1, "select": 1,
            "textarea": 1, "button": 1, "br": 1
          };

          function inlineStart(node) {
            if (node.nodeType === 3) {
              return /\S/.test(node.data) ? true : null;
            }
            if (node.nodeType !== 1) return null;
            var s = getComputedStyle(node);
            if (outOfFlow(s)) return null;
            var d = s.display;
            if (d === "contents") return startsInline(node);
            if (d.indexOf("inline") !== 0 && d.indexOf("ruby") !== 0) return false;
            // A plain inline holds no line box of its own: the empty
            // `<a id>` a chapter opens with is looked through to what
            // follows it, and a `<span>` counts by what it holds.
            if (d === "inline" && !ATOMIC[node.localName]) return startsInline(node);
            return true;
          }

          function startsInline(el) {
            var nodes = el.childNodes, answer;
            for (var i = 0; i < nodes.length; i++) {
              answer = inlineStart(nodes[i]);
              if (answer !== null) return answer;
            }
            return null;
          }

          function zero(value) {
            return value === "0" || value === "0px";
          }

          function autoOrZero(value) {
            return value === "auto" || zero(value);
          }

          function neutralCounter(style, name) {
            var value = style.getPropertyValue(name);
            return value === "" || value === "none";
          }

          function safePseudo(style) {
            var transparent = style.backgroundColor === "transparent" ||
                             style.backgroundColor === "rgba(0, 0, 0, 0)";
            return style.content !== "none" && style.content !== "normal" &&
                   style.content !== "\"\"" &&
                   style.display === "inline" &&
                   style.position === "static" &&
                   style.getPropertyValue("float") === "none" &&
                   zero(style.fontSize) && zero(style.lineHeight) &&
                   zero(style.letterSpacing) && zero(style.wordSpacing) &&
                   zero(style.marginTop) && zero(style.marginRight) &&
                   zero(style.marginBottom) && zero(style.marginLeft) &&
                   zero(style.paddingTop) && zero(style.paddingRight) &&
                   zero(style.paddingBottom) && zero(style.paddingLeft) &&
                   zero(style.borderTopWidth) && zero(style.borderRightWidth) &&
                   zero(style.borderBottomWidth) && zero(style.borderLeftWidth) &&
                   style.backgroundImage === "none" && transparent &&
                   style.boxShadow === "none" &&
                   (style.outlineStyle === "none" || zero(style.outlineWidth)) &&
                   autoOrZero(style.width) && autoOrZero(style.height) &&
                   style.verticalAlign === "baseline" &&
                   neutralCounter(style, "counter-increment") &&
                   neutralCounter(style, "counter-reset") &&
                   neutralCounter(style, "counter-set");
          }

          var els = document.getElementsByTagName("*");
          var changed = installed;
          for (i = 0; i < els.length; i++) {
            var el = els[i];
            if (!BLOCK[getComputedStyle(el).display]) continue;
            var before = getComputedStyle(el, "::before").content;
            if (before !== "none" && before !== "normal") continue;
            if (startsInline(el) !== true) continue;
            el.setAttribute(ATTR, "");
            if (!safePseudo(getComputedStyle(el, "::before"))) {
              // An important publisher rule with higher specificity can
              // beat our important reset. Do not leave a marker that shifts
              // or paints the book if the cascade did not make it safe.
              el.removeAttribute(ATTR);
              continue;
            }
            if (!had.has(el)) {
              changed = true;
            } else {
              kept++;
            }
          }
          if (kept !== had.size) changed = true;
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

    /**
     * A cancelled caller is let go, as in
     * [com.chmouel.liseur.reader.footnotes.FootnoteLayout]: a navigator
     * being replaced must not read as a page that failed to answer.
     */
    @OptIn(ExperimentalReadiumApi::class)
    internal suspend fun apply(navigator: EpubNavigatorFragment): Result =
        parse(
            try {
                navigator.evaluateJavascript(SCRIPT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (refused: Exception) {
                null
            },
        )
}
