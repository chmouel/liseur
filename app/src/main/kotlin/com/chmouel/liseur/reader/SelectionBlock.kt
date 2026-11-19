package com.chmouel.liseur.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * The block holding the start of the reader's selection, as a CSS
 * selector. Readium's selection locator names the file but not the
 * place in it, and the reading companion may read the book only up to
 * the selection.
 */
internal object SelectionBlock {
    private const val CSS_SELECTOR = "cssSelector"
    private const val ANSWER_MS = 500L
    private const val MAX_SELECTOR = 512

    data class Capture(val selector: String, val before: String?, val after: String?)

    suspend fun selector(nav: EpubNavigatorFragment, href: String): String? =
        read(nav, href, text = null, includeContext = false)?.selector

    @OptIn(ExperimentalReadiumApi::class)
    suspend fun read(nav: EpubNavigatorFragment, href: String, text: String?, includeContext: Boolean): Capture? {
        val result = try {
            withTimeoutOrNull(ANSWER_MS) {
                nav.evaluateJavascript(SCRIPT
                    .replace("INCLUDE_CONTEXT", includeContext.toString())
                    .replace("INCLUDE_TEXT", (text != null).toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return parseCapture(result, href, text)
    }

    /** The selector in [result], only when it was read from the file [href] names. */
    internal fun parse(result: String?, href: String): String? = parseCapture(result, href, text = null)?.selector

    internal fun parseCapture(result: String?, href: String, text: String?): Capture? {
        if (result.isNullOrBlank() || result == "null") return null
        val json = when (val decoded = runCatching { JSONTokener(result).nextValue() }.getOrNull()) {
            is JSONObject -> decoded
            is String -> runCatching { JSONObject(decoded) }.getOrNull()
            else -> null
        } ?: return null
        if (!ResourceAddress.shows(json.optString("href"), href)) return null
        if (text != null && json.optString("text").trim() != text.trim()) return null
        val selector = json.optString("selector").takeIf { it.isNotBlank() && it.length <= MAX_SELECTOR } ?: return null
        return Capture(selector, json.optString("before").takeIf { json.has("before") }, json.optString("after").takeIf { json.has("after") })
    }

    /** [locator] with [selector] written in, where the companion reads it. */
    fun place(locator: Locator, selector: String?): Locator {
        selector ?: return locator
        return locator.copy(
            locations = locator.locations.copy(
                otherLocations = locator.locations.otherLocations + (CSS_SELECTOR to selector),
            ),
        )
    }

    // The block and selector rules match ExactLocatorAnchor's capture script.
    private val SCRIPT = """
        (() => {
          const selection = window.getSelection && window.getSelection();
          if (!selection || selection.rangeCount === 0) return null;
          const blockTags = new Set([
            "p", "li", "blockquote", "pre", "td", "th", "figcaption",
            "h1", "h2", "h3", "h4", "h5", "h6", "div"
          ]);
          const tag = element => (element && element.localName || "").toLowerCase();
          const escape = value => window.CSS && CSS.escape
            ? CSS.escape(value)
            : value.replace(/[^a-zA-Z0-9_-]/g, ch => "\\\\" + ch);
          const selector = element => {
            if (element.id) {
              const byId = "#" + escape(element.id);
              if (document.querySelectorAll(byId).length === 1) return byId;
            }
            const parts = [];
            let current = element;
            while (current && current !== document.body) {
              let part = current.localName;
              if (!part) break;
              let index = 1;
              let sibling = current;
              while ((sibling = sibling.previousElementSibling)) {
                if (sibling.localName === current.localName) index++;
              }
              part += ":nth-of-type(" + index + ")";
              parts.unshift(part);
              current = current.parentElement;
            }
            return "body" + (parts.length ? " > " + parts.join(" > ") : "");
          };
          const range = selection.getRangeAt(0);
          const start = range.startContainer;
          let block = start.nodeType === Node.ELEMENT_NODE ? start : start.parentElement;
          while (block && block !== document.body && !blockTags.has(tag(block))) {
            block = block.parentElement;
          }
          if (!block || !document.body.contains(block)) return null;
          const result = {href: location.href, selector: selector(block)};
          if (INCLUDE_TEXT) result.text = range.toString();
          if (INCLUDE_CONTEXT && block !== document.body && block.contains(range.endContainer)) {
            const prefix = document.createRange();
            prefix.selectNodeContents(block);
            prefix.setEnd(start, range.startOffset);
            const suffix = document.createRange();
            suffix.selectNodeContents(block);
            suffix.setStart(range.endContainer, range.endOffset);
            result.before = prefix.toString().slice(-1000);
            result.after = suffix.toString().slice(0, 1000);
          }
          return JSON.stringify(result);
        })()
    """.trimIndent()
}
