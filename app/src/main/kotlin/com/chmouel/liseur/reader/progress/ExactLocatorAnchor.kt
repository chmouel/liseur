package com.chmouel.liseur.reader.progress

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/** A text quote pinned to the first visible word in a reflowable resource. */
data class ViewportTextAnchor(
    val cssSelector: String,
    val before: String,
    val highlight: String,
    val after: String,
)

/** App-owned exact locators, independent of fonts, columns, and viewport size. */
object ExactLocatorAnchor {
    const val MARKER = "liseurAnchor"
    const val CSS_SELECTOR = "cssSelector"
    const val MAX_BEFORE = 32
    const val MAX_HIGHLIGHT = 64
    const val MAX_AFTER = 32
    private const val MAX_SELECTOR = 2_048
    private const val MAX_LOCATOR_BYTES = 16 * 1024

    fun parseJavascriptResult(result: String?): ViewportTextAnchor? {
        if (result.isNullOrBlank() || result == "null") return null
        val decoded = runCatching { JSONTokener(result).nextValue() }.getOrNull()
        val json = when (decoded) {
            is JSONObject -> decoded
            is String -> runCatching { JSONObject(decoded) }.getOrNull()
            else -> null
        } ?: return null
        val selector = json.optString("cssSelector").takeIf {
            it.isNotBlank() && it.length <= MAX_SELECTOR
        } ?: return null
        val highlight = json.optString("highlight").takeIf { it.isNotBlank() } ?: return null
        return ViewportTextAnchor(
            cssSelector = selector,
            before = json.optString("before").takeLastCodePoints(MAX_BEFORE),
            highlight = highlight.takeCodePoints(MAX_HIGHLIGHT),
            after = json.optString("after").takeCodePoints(MAX_AFTER),
        )
    }

    fun mark(locator: Locator, anchor: ViewportTextAnchor): Locator {
        val other = locator.locations.otherLocations + mapOf(
            MARKER to 1,
            CSS_SELECTOR to anchor.cssSelector,
        )
        val exact = locator.copy(
            locations = locator.locations.copy(otherLocations = other),
            text = Locator.Text(
                before = anchor.before,
                highlight = anchor.highlight,
                after = anchor.after,
            ),
        )
        return exact.takeIf(::fitsSyncLimit) ?: locator
    }

    fun withStableProgression(locator: Locator, progression: Double): Locator =
        locator.copy(
            locations = locator.locations.copy(
                totalProgression = progression.coerceIn(0.0, 1.0),
            ),
        )

    fun isExact(locator: Locator?): Boolean {
        locator ?: return false
        val marker = locator.locations.otherLocations[MARKER]
        val marked = marker is Number && marker.toInt() == 1
        return marked &&
            locator.locations.otherLocations[CSS_SELECTOR] is String &&
            (locator.locations.otherLocations[CSS_SELECTOR] as String).isNotBlank() &&
            !locator.text.highlight.isNullOrBlank() &&
            fitsSyncLimit(locator)
    }

    fun isExactJson(locatorJson: String?): Boolean =
        locatorJson?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        }?.let(::isExact) == true

    fun excerpt(locatorJson: String?): String? {
        val locator = locatorJson?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        } ?: return null
        if (!isExact(locator)) return null
        return listOf(locator.text.before, locator.text.highlight, locator.text.after)
            .filterNotNull()
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeCodePoints(140)
            .takeIf(String::isNotEmpty)
    }

    @OptIn(ExperimentalReadiumApi::class)
    suspend fun capture(navigator: EpubNavigatorFragment, native: Locator): Locator {
        val result = runCatching { navigator.evaluateJavascript(CAPTURE_SCRIPT) }.getOrNull()
        return parseJavascriptResult(result)?.let { mark(native, it) } ?: native
    }

    @OptIn(ExperimentalReadiumApi::class)
    suspend fun verify(navigator: EpubNavigatorFragment, locator: Locator): Boolean {
        if (!isExact(locator)) return false
        val selector = locator.locations.otherLocations[CSS_SELECTOR] as String
        val script = """
            (() => {
              const block = document.querySelector(${JSONObject.quote(selector)});
              if (!block) return false;
              const text = block.textContent || "";
              const quote = ${JSONObject.quote(
                  locator.text.before.orEmpty() +
                      locator.text.highlight.orEmpty() +
                      locator.text.after.orEmpty(),
              )};
              const quoteStart = text.indexOf(quote);
              if (quoteStart < 0) return false;
              const startOffset = quoteStart + ${locator.text.before.orEmpty().length};
              const endOffset = startOffset + ${locator.text.highlight.orEmpty().length};
              const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
              let node;
              let consumed = 0;
              let startNode = null;
              let endNode = null;
              let startInNode = 0;
              let endInNode = 0;
              while ((node = walker.nextNode())) {
                const next = consumed + node.data.length;
                if (!startNode && startOffset >= consumed && startOffset <= next) {
                  startNode = node;
                  startInNode = startOffset - consumed;
                }
                if (endOffset >= consumed && endOffset <= next) {
                  endNode = node;
                  endInNode = endOffset - consumed;
                  break;
                }
                consumed = next;
              }
              if (!startNode || !endNode) return false;
              const range = document.createRange();
              range.setStart(startNode, startInNode);
              range.setEnd(endNode, endInNode);
              return Array.from(range.getClientRects()).some(rect =>
                rect.width > 0 && rect.height > 0 &&
                rect.right > 0 && rect.bottom > 0 &&
                rect.left < window.innerWidth && rect.top < window.innerHeight
              );
            })()
        """.trimIndent()
        return runCatching { navigator.evaluateJavascript(script)?.trim() == "true" }
            .getOrDefault(false)
    }

    private fun fitsSyncLimit(locator: Locator): Boolean =
        locator.toJSON().toString().toByteArray(StandardCharsets.UTF_8).size < MAX_LOCATOR_BYTES

    private fun String.takeCodePoints(count: Int): String {
        if (codePointCount(0, length) <= count) return this
        return substring(0, offsetByCodePoints(0, count))
    }

    private fun String.takeLastCodePoints(count: Int): String {
        val size = codePointCount(0, length)
        if (size <= count) return this
        return substring(offsetByCodePoints(0, size - count))
    }

    private val CAPTURE_SCRIPT = """
        (() => {
          const blocked = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE"]);
          const blockTags = new Set([
            "P", "LI", "BLOCKQUOTE", "PRE", "TD", "TH", "FIGCAPTION",
            "H1", "H2", "H3", "H4", "H5", "H6", "DIV"
          ]);
          const visible = rect => rect.width > 0 && rect.height > 0 &&
            rect.right > 0 && rect.bottom > 0 &&
            rect.left < window.innerWidth && rect.top < window.innerHeight;
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
          const segments = text => {
            if (window.Intl && Intl.Segmenter) {
              return Array.from(new Intl.Segmenter(undefined, {granularity: "word"})
                .segment(text))
                .filter(item => item.isWordLike)
                .map(item => ({index: item.index, text: item.segment}));
            }
            let regex;
            try {
              regex = new RegExp("[\\p{L}\\p{N}\\p{M}]+(?:[’'][\\p{L}\\p{N}\\p{M}]+)*", "gu");
            } catch (_) {
              regex = /\\S+/g;
            }
            const found = [];
            let match;
            while ((match = regex.exec(text)) !== null) {
              found.push({index: match.index, text: match[0]});
            }
            return found;
          };
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: node => {
              const parent = node.parentElement;
              if (!parent || blocked.has(parent.tagName) || !node.data.trim()) {
                return NodeFilter.FILTER_REJECT;
              }
              return NodeFilter.FILTER_ACCEPT;
            }
          });
          let node;
          while ((node = walker.nextNode())) {
            for (const word of segments(node.data)) {
              const range = document.createRange();
              range.setStart(node, word.index);
              range.setEnd(node, word.index + word.text.length);
              if (!Array.from(range.getClientRects()).some(visible)) continue;
              let block = node.parentElement;
              while (block && block !== document.body && !blockTags.has(block.tagName)) {
                block = block.parentElement;
              }
              block = block || document.body;
              const beforeRange = document.createRange();
              beforeRange.selectNodeContents(block);
              beforeRange.setEnd(node, word.index);
              const afterRange = document.createRange();
              afterRange.selectNodeContents(block);
              afterRange.setStart(node, word.index + word.text.length);
              const before = Array.from(beforeRange.toString()).slice(-32).join("");
              const highlight = Array.from(word.text).slice(0, 64).join("");
              const after = Array.from(afterRange.toString()).slice(0, 32).join("");
              return JSON.stringify({
                cssSelector: selector(block), before, highlight, after
              });
            }
          }
          return null;
        })()
    """.trimIndent()
}
