package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiContext
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiDom
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiResource
import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import com.chmouel.liseur.data.bookorbit.BookOrbitOpenedEpub
import com.chmouel.liseur.reader.ResourceAddress
import com.chmouel.liseur.reader.chrome.visibleWebView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.w3c.dom.Document
import org.w3c.dom.Node
import kotlin.coroutines.resume

/**
 * A viewport CFI candidate verified against the selected EPUB's original resource. It is not
 * syncable until it is committed with the locator and local revision it describes.
 */
internal object BookOrbitViewportCfi {
    data class Candidate(
        val context: BookOrbitCfiContext,
        val href: String,
        val raw: String,
        val before: String,
        val word: String,
    )

    /**
     * [checkContext] must recheck the captured selected-file binding (for example,
     * BookOrbitCfiRepository::check); a candidate is never stored or restored by this helper.
     */
    suspend fun capture(
        navigator: EpubNavigatorFragment,
        opened: BookOrbitOpenedEpub,
        layoutGeneration: () -> Int,
        isReflowing: () -> Boolean,
        isCurrent: () -> Boolean,
        checkContext: suspend (BookOrbitCfiContext) -> Unit,
        originalDocument: suspend (BookOrbitOpenedEpub, String) -> Document?,
    ): Candidate? {
        val context = opened.context
        checkContext(context)
        val native = navigator.currentLocator.value
        val href = native.href.toString()
        val generation = layoutGeneration()
        val root = navigator.publicationView
        val web = visibleWebView(root) ?: return null
        if (isReflowing() || !isCurrent() || !ResourceAddress.shows(web.url, href)) return null
        val result = suspendCancellableCoroutine<String?> { continuation ->
            web.evaluateJavascript(CAPTURE_SCRIPT) {
                if (continuation.isActive) continuation.resume(it)
            }
        }
        currentCoroutineContext().ensureActive()
        checkContext(context)
        if (isReflowing() || !isCurrent() || generation != layoutGeneration() ||
            navigator.currentLocator.value != native ||
            visibleWebView(root) !== web || !ResourceAddress.shows(web.url, href)
        ) return null
        val captured = parseJavascriptResult(result) ?: return null
        val local = captured.optString("path")
        val text = captured.optString("text")
        val before = captured.optString("before")
        val word = captured.optString("word")
        if (text.isEmpty() || text.length > 4096 || word.isBlank()) return null
        val entry = opened.publication.spine.singleOrNull {
            ResourceAddress.canonicalPath(it.href) == ResourceAddress.canonicalPath(href)
        }?.href ?: return null
        val document = originalDocument(opened, entry) ?: return null
        val raw = verify(opened.publication, href, local, text, word, document) ?: return null
        val repeated = suspendCancellableCoroutine<String?> { continuation ->
            web.evaluateJavascript(CAPTURE_SCRIPT) {
                if (continuation.isActive) continuation.resume(it)
            }
        }
        currentCoroutineContext().ensureActive()
        val final = parseJavascriptResult(repeated) ?: return null
        if (final.optString("path") != local || final.optString("text") != text ||
            final.optString("before") != before || final.optString("word") != word
        ) return null
        checkContext(context)
        if (isReflowing() || !isCurrent() || generation != layoutGeneration() ||
            navigator.currentLocator.value != native ||
            visibleWebView(root) !== web || !ResourceAddress.shows(web.url, href)
        ) return null
        return Candidate(context, href, raw, before, word)
    }

    internal fun parseJavascriptResult(result: String?): JSONObject? {
        if (result.isNullOrBlank() || result == "null") return null
        return when (val decoded = runCatching { JSONTokener(result).nextValue() }.getOrNull()) {
            is JSONObject -> decoded
            is String -> runCatching { JSONObject(decoded) }.getOrNull()
            else -> null
        }
    }

    internal fun verify(
        publication: BookOrbitEpubPackage,
        href: String,
        local: String,
        text: String,
        word: String,
        document: Document,
    ): String? {
        val raw = assemble(publication, href, local) ?: return null
        val resource = BookOrbitCfiResource.locate(BookOrbitCfi.parse(raw), publication) ?: return null
        val position = BookOrbitCfiDom.resolve(resource, document)?.start ?: return null
        val offset = resource.start.offset?.character ?: return null
        if (position.node.nodeType !in listOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE) ||
            text != textSlot(position.node) || offset !in text.indices ||
            !text.substring(offset).startsWith(word)
        ) return null
        return raw
    }

    private fun textSlot(node: Node): String {
        fun Node.isText() = nodeType == Node.TEXT_NODE || nodeType == Node.CDATA_SECTION_NODE
        var first = node
        while (first.previousSibling?.isText() == true) first = first.previousSibling
        return buildString {
            var current: Node? = first
            while (current?.isText() == true) {
                append(current.nodeValue)
                current = current.nextSibling
            }
        }
    }

    internal fun assemble(publication: BookOrbitEpubPackage, href: String, local: String): String? {
        if (local.length > 4096 || !local.startsWith('/') || '!' in local) return null
        val path = ResourceAddress.canonicalPath(href) ?: return null
        val item = publication.spine.singleOrNull {
            ResourceAddress.canonicalPath(it.href) == path
        } ?: return null
        fun step(step: BookOrbitCfi.Component.Step): String =
            "/${step.index}" + (step.id?.let {
                "[${buildString {
                    for (char in it) {
                        if (char in "^[](),;=") append('^')
                        append(char)
                    }
                }}]"
            } ?: "")
        val raw = "epubcfi(${step(publication.spineStep)}${step(item.step)}!$local)"
        val resource = runCatching {
            val parsed = BookOrbitCfi.parse(raw) as? BookOrbitCfi.Point
                ?: return@runCatching null
            if (parsed.path.offset == null) return@runCatching null
            BookOrbitCfiResource.locate(parsed, publication)
        }.getOrNull()
        return raw.takeIf { resource != null && ResourceAddress.canonicalPath(resource.href) == path }
    }

    // The script only captures a point on visible text. It does not navigate or change the DOM.
    internal val CAPTURE_SCRIPT = """
        JSON.stringify((() => {
          const root = document.documentElement;
          if (!root || !document.body) return null;
          const visible = rect => rect.width > 0 && rect.height > 0 &&
            rect.right > 0 && rect.bottom > 0 &&
            rect.left < innerWidth && rect.top < innerHeight;
          const painted = element => {
            while (element) {
              const style = getComputedStyle(element);
              if (style.display === "none" || style.visibility !== "visible" ||
                Number(style.opacity) === 0) return false;
              element = element.parentElement;
            }
            return true;
          };
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let node;
          while ((node = walker.nextNode())) {
            if (!node.data.trim() || !painted(node.parentElement) ||
              /^(script|style|noscript|template)$/i.test(node.parentElement.localName || "")) continue;
            const words = /\S+/gu;
            let word;
            while ((word = words.exec(node.data))) {
              const offset = word.index;
              const range = document.createRange();
              range.setStart(node, offset);
              range.setEnd(node, offset + word[0].length);
              if (!Array.from(range.getClientRects()).some(visible)) continue;
              const steps = [];
              let current = node;
              while (current !== root) {
                const parent = current.parentNode;
                if (!parent || parent.nodeType !== Node.ELEMENT_NODE) return null;
                let slot = 1;
                let textOffset = offset;
                let previous = current.previousSibling;
                let inTextSlot = true;
                while (previous) {
                  if (previous.nodeType === Node.ELEMENT_NODE) {
                    slot += 2;
                    inTextSlot = false;
                  } else if (inTextSlot && current.nodeType === Node.TEXT_NODE &&
                    (previous.nodeType === Node.TEXT_NODE || previous.nodeType === Node.CDATA_SECTION_NODE)) {
                    textOffset += previous.nodeValue.length;
                  }
                  previous = previous.previousSibling;
                }
                if (current.nodeType === Node.ELEMENT_NODE) {
                  const index = Array.from(parent.children).indexOf(current) + 1;
                  const escaped = value => value.replace(/[\^\[\](),;=]/g, ch => "^" + ch);
                  steps.unshift("/" + (index * 2) + (current.id ? "[" + escaped(current.id) + "]" : ""));
                } else if (current.nodeType === Node.TEXT_NODE) {
                  steps.unshift("/" + slot + ":" + textOffset);
                } else return null;
                current = parent;
              }
              let first = node;
              while (first.previousSibling &&
                (first.previousSibling.nodeType === Node.TEXT_NODE ||
                  first.previousSibling.nodeType === Node.CDATA_SECTION_NODE)) {
                first = first.previousSibling;
              }
              let text = "";
              for (let part = first; part &&
                (part.nodeType === Node.TEXT_NODE || part.nodeType === Node.CDATA_SECTION_NODE);
                part = part.nextSibling) text += part.nodeValue;
              const blocks = new Set([
                "p", "li", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "div"
              ]);
              let block = node.parentElement;
              while (block && block !== document.body &&
                !blocks.has(block.localName.toLowerCase())) block = block.parentElement;
              block = block || document.body;
              const beforeRange = document.createRange();
              beforeRange.selectNodeContents(block);
              beforeRange.setEnd(node, offset);
              const before = Array.from(beforeRange.toString()).slice(-32).join("");
              return {path: steps.join(""), text, before, word: word[0]};
            }
          }
          return null;
        })())
    """.trimIndent()
}
