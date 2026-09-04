package com.chmouel.liseur.reader

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * What image, if any, is under a point on the page.
 *
 * Injected into the book's own document, because Readium 3.3.0 tells
 * nobody about images: its `InputListener` is `onTap`, `onDrag` and
 * `onKey`, and its navigator listener knows about links and jumps. The
 * only thing that can answer "is that a picture" is the document.
 *
 * Asked of the web view directly rather than through
 * `EpubNavigatorFragment.evaluateJavascript`, which answers null on a
 * fixed-layout book — and a fixed-layout book is very often nothing but
 * pictures, so that is exactly where the question matters most.
 *
 * See `docs/adr/0022-pinch-on-the-page.md`.
 */
internal object ImageAtPoint {

    /**
     * An image worth opening full screen.
     *
     * @param src The URL the page actually resolved, absolute.
     * @param alt The book's own caption for it, if it wrote one.
     * @param width Its natural width in pixels, or zero if unknown.
     * @param height Its natural height in pixels, or zero if unknown.
     */
    data class Hit(
        val src: String,
        val alt: String?,
        val width: Int,
        val height: Int,
    )

    /**
     * The smallest an image can be drawn and still be worth enlarging, in
     * CSS pixels.
     *
     * Below this it is punctuation: a drop cap, an inline rule, the
     * dingbat between two scenes. Opening a viewer over one of those is a
     * gesture the reader did not mean.
     */
    const val MIN_RENDERED_PX = 48

    /**
     * The words a book uses for a picture that is furniture rather than
     * something to look at.
     *
     * Size alone is not enough. The imprint page of every Standard Ebook
     * carries a publisher logo drawn around 76 CSS pixels wide — over any
     * threshold meant to exclude an ornament, and still nothing anyone
     * wants full screen. The title page is worse: its lettering is a
     * picture the width of the column. The markup says what both are, so
     * it is read.
     *
     * Read off the image and off the few elements above it, because the
     * word that matters is often on the section rather than on the image:
     * a title page's `<img>` says nothing, its `<section>` says
     * `titlepage`.
     */
    private val DECORATIVE = listOf(
        "publisher-logo",
        "ornament",
        "footnote-separator",
        "titlepage",
        "halftitlepage",
        "colophon",
        "imprint",
        "copyright-page",
    )

    /**
     * Whether this resource has any image at all.
     *
     * Asked once per resource, because the answer is no for most pages of
     * most books, and a no means no script runs when the reader puts two
     * fingers on the page. Only a *yes* is remembered: see the caller.
     */
    const val HAS_IMAGES_SCRIPT: String =
        "(document.images.length > 0 || !!document.querySelector('svg image'))"

    /**
     * The longest an address and a caption are allowed to be.
     *
     * The document is the book's, so everything it says comes back as
     * something a file chose. A book that inlines a plate as a `data:`
     * URL, or writes a novel into an `alt`, would otherwise hand back a
     * string of that size across the bridge on every touch. Both figures
     * are far above anything a real address or a real caption reaches.
     */
    private const val MAX_SRC_CHARS = 4096
    private const val MAX_ALT_CHARS = 512

    /** Room for the JSON around the two strings the script may return. */
    private const val PARSE_SLACK_CHARS = 256

    /**
     * The longest the document is given to answer.
     *
     * A renderer that hangs, or a process that goes away without saying
     * so, never calls the callback at all — and the touch that asked
     * would then wait for ever, with its job still in flight when the
     * next one starts.
     */
    private const val EVAL_TIMEOUT_MS = 1500L

    /**
     * The hit test itself.
     *
     * `currentSrc` before `src`, and this is not a detail. Given
     * `srcset="logo-2x.png 2x, logo.png 1x"` any screen above 1x displays
     * the 2x file, and `src` names the 1x one — so a viewer built from
     * `src` would open a *lower*-resolution copy of the picture the
     * reader is looking at, which is the exact opposite of the point.
     *
     * `elementsFromPoint` gives the whole stack under the point, so a
     * picture beneath a link, a caption's wrapper or a transparent
     * overlay is found without guessing which of them the browser calls
     * topmost. Failing that it walks up a few levels and looks *inside*
     * each ancestor — and anything found that way has to have the point
     * inside its own box, because an ancestor two levels up is usually
     * the `<section>`, and a `<section>` contains the chapter's plate
     * wherever in the chapter that plate happens to be.
     */
    fun script(fx: Float, fy: Float): String = """
        (function () {
          var MIN = $MIN_RENDERED_PX;
          var MAX_SRC = $MAX_SRC_CHARS;
          var MAX_ALT = $MAX_ALT_CHARS;
          // The point arrives as a fraction of the web view rather than
          // in pixels. A fixed-layout page is drawn at whatever scale it
          // takes to fit the screen, so device pixels divided by the
          // display density are not CSS pixels there; a fraction of the
          // viewport is the same fraction in either kind of book.
          var VW = window.innerWidth || document.documentElement.clientWidth;
          var VH = window.innerHeight || document.documentElement.clientHeight;
          var px = $fx * VW;
          var py = $fy * VH;
          var DECOR = ${DECORATIVE.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }};
          var OPS = "http://www.idpf.org/2007/ops";
          // What the book calls this element. The epub:type attribute is
          // rewritten into a class name by the time the document is on
          // screen, so both spellings are read and neither is trusted to
          // be the one that survived.
          function roleOf(e) {
            if (!e || !e.getAttribute) return "";
            var ns = "";
            try { ns = e.getAttributeNS(OPS, "type") || ""; } catch (err) {}
            return ((e.getAttribute("epub:type") || "") + " " + ns + " " +
                    (e.getAttribute("class") || "")).toLowerCase();
          }
          function decorative(e) {
            var role = roleOf(e);
            for (var d = 0; d < DECOR.length; d++) {
              if (role.indexOf(DECOR[d]) !== -1) return true;
            }
            return false;
          }
          function isImage(e) {
            if (!e || !e.tagName) return false;
            var t = e.tagName.toLowerCase();
            return t === "img" || t === "image";
          }
          // Whether the picture is actually under the finger. Without
          // this a pinch on a paragraph, or on a plate's own caption,
          // finds whatever illustration happens to live elsewhere in the
          // same <section> and opens that instead of resizing the text.
          function covers(e, x, y) {
            if (!isImage(e)) return false;
            var r = e.getBoundingClientRect();
            return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
          }
          var img = null;
          var stack = [];
          try {
            if (document.elementsFromPoint) stack = document.elementsFromPoint(px, py) || [];
          } catch (e) {}
          for (var s = 0; s < stack.length && s < 8; s++) {
            if (isImage(stack[s])) { img = stack[s]; break; }
          }
          var el = document.elementFromPoint(px, py);
          for (var i = 0; !img && el && i < 4; i++) {
            if (isImage(el)) { img = el; break; }
            var inner = el.querySelectorAll ? el.querySelectorAll("img, svg image") : [];
            for (var k = 0; k < inner.length && k < 16; k++) {
              if (covers(inner[k], px, py)) { img = inner[k]; break; }
            }
            el = el.parentElement;
          }
          if (!img) return null;

          for (var up = img, j = 0; up && j < 5; up = up.parentElement, j++) {
            if (decorative(up)) return null;
          }

          var box = img.getBoundingClientRect();
          if (box.width < MIN || box.height < MIN) return null;

          var src = img.currentSrc || img.src ||
                    (img.href && img.href.baseVal) ||
                    img.getAttribute("xlink:href") || "";
          if (typeof src !== "string" || !src) return null;
          var abs = src;
          try { abs = new URL(src, document.baseURI).href; } catch (e) {}
          if (abs.length > MAX_SRC) return null;

          var alt = img.getAttribute("alt") || "";
          if (alt.length > MAX_ALT) alt = alt.slice(0, MAX_ALT);

          return {
            src: abs,
            alt: alt,
            width: img.naturalWidth || Math.round(box.width),
            height: img.naturalHeight || Math.round(box.height)
          };
        })();
    """

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so
     * an object arrives as an object and a miss arrives as the four
     * characters `null`.
     *
     * The script caps what it returns, so anything longer than those caps
     * did not come from the script and is refused before it is parsed.
     */
    fun parse(result: String?): Hit? {
        val raw = result?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return null
        if (raw.length > MAX_SRC_CHARS + MAX_ALT_CHARS + PARSE_SLACK_CHARS) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val src = json.optString("src").takeIf { it.isNotBlank() } ?: return null
        if (src.length > MAX_SRC_CHARS) return null
        return Hit(
            src = src,
            alt = json.optString("alt").takeIf { it.isNotBlank() }?.take(MAX_ALT_CHARS),
            width = json.optInt("width"),
            height = json.optInt("height"),
        )
    }

    /** True when the resource on screen has an image anywhere in it. */
    suspend fun hasImages(web: WebView): Boolean =
        eval(web, HAS_IMAGES_SCRIPT)?.trim() == "true"

    /**
     * The image under a point, given as a fraction of [web]'s own width
     * and height rather than in pixels of either kind.
     */
    suspend fun at(web: WebView, fx: Float, fy: Float): Hit? = parse(eval(web, script(fx, fy)))

    private suspend fun eval(web: WebView, js: String): String? =
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    web.evaluateJavascript(js) { if (cont.isActive) cont.resume(it) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
}
