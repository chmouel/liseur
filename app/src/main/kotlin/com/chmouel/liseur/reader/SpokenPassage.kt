package com.chmouel.liseur.reader

import com.chmouel.liseur.reader.chrome.visibleWebView
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * Whether the sentence being read aloud is on screen, so the page
 * follows the voice only when it has gone off it. Going to every
 * sentence would scroll a scrolled book's text to the top of the
 * screen each time, and the listener would lose their line.
 */
internal object SpokenPassage {
    /**
     * [selection] marked with the first block on screen, where the voice
     * starts looking for it. Readium's selection names no element, and
     * the book's sentences can only be started from an element, so
     * without one the search would start at the top of the chapter and
     * give up long before a selection pages further in.
     */
    suspend fun startingPoint(navigator: EpubNavigatorFragment, selection: Locator): Locator {
        val shown = try {
            navigator.firstVisibleElementLocator()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return selection
        val selector = shown.locations.otherLocations[CSS_SELECTOR] as? String ?: return selection
        val href = ResourceAddress.canonicalPath(selection.href.toString())
        if (href == null || href != ResourceAddress.canonicalPath(shown.href.toString())) return selection
        return selection.copy(
            locations = selection.locations.copy(
                otherLocations = selection.locations.otherLocations + (CSS_SELECTOR to selector),
            ),
        )
    }

    @OptIn(ExperimentalReadiumApi::class)
    suspend fun isShowing(navigator: EpubNavigatorFragment, locator: Locator): Boolean {
        val here = ResourceAddress.canonicalPath(navigator.currentLocator.value.href.toString())
        if (here == null || here != ResourceAddress.canonicalPath(locator.href.toString())) return false
        // Readium can put the next resource on screen before it says so,
        // so the view itself must show this resource, before the question
        // and after it.
        val web = visibleWebView(navigator.publicationView)
        fun stillShown() = web != null && web === visibleWebView(navigator.publicationView) &&
            ResourceAddress.shows(web.url, locator.href.toString())
        if (!stillShown()) return false
        val highlight = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return false
        val script = script(
            selector = locator.locations.otherLocations[CSS_SELECTOR] as? String,
            before = locator.text.before.orEmpty().takeLast(BEFORE_CHARS),
            highlight = highlight,
        )
        val shown = try {
            navigator.evaluateJavascript(script)?.trim() == "true"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return shown && stillShown()
    }

    // Readium extracts the sentence with its whitespace collapsed, so the
    // page's text is collapsed the same way, keeping where each character
    // came from to build the range back out of it. A sentence that is not
    // found falls back to whether its block is on screen at all.
    private fun script(selector: String?, before: String, highlight: String): String = """
        (() => {
          const selector = ${selector?.let(JSONObject::quote) ?: "null"};
          let block = null;
          try { block = selector ? document.querySelector(selector) : null; } catch (e) {}
          const root = block || document.body;
          if (!root) return false;
          const onScreen = rects => Array.from(rects).some(rect =>
            rect.width > 0 && rect.height > 0 &&
            rect.right > 0 && rect.bottom > 0 &&
            rect.left < window.innerWidth && rect.top < window.innerHeight
          );
          const collapse = s => s.replace(/\s+/g, " ");
          const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
          let flat = "";
          const nodes = [];
          const offsets = [];
          let space = false;
          let node;
          while ((node = walker.nextNode())) {
            const data = node.data;
            for (let i = 0; i < data.length; i++) {
              const blank = /\s/.test(data[i]);
              if (blank && space) continue;
              flat += blank ? " " : data[i];
              nodes.push(node);
              offsets.push(i);
              space = blank;
            }
          }
          const quote = collapse(${JSONObject.quote(highlight)}).trim();
          const before = collapse(${JSONObject.quote(before)});
          let start = -1;
          if (before.length > 0) {
            const found = flat.indexOf(before + quote);
            if (found >= 0) start = found + before.length;
          }
          if (start < 0) start = flat.indexOf(quote);
          if (start < 0 || quote.length === 0) {
            return block ? onScreen(block.getClientRects()) : false;
          }
          const end = start + quote.length - 1;
          const range = document.createRange();
          range.setStart(nodes[start], offsets[start]);
          range.setEnd(nodes[end], offsets[end] + 1);
          return onScreen(range.getClientRects());
        })()
    """.trimIndent()

    private const val CSS_SELECTOR = "cssSelector"
    private const val BEFORE_CHARS = 40
}
