package com.chmouel.liseur.reader

import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * What image, if any, is under a point on the page.
 *
 * Injected into the book's own document, the way [WideContentFit] is,
 * because Readium 3.3.0 tells nobody about images: its `InputListener`
 * is `onTap`, `onDrag` and `onKey`, and its navigator listener knows
 * about links and jumps. The only thing that can answer "is that a
 * picture" is the document.
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
     * `epub:type` roles that name an image as furniture rather than as
     * something to look at.
     *
     * Size alone is not enough. The imprint page of every Standard Ebook
     * carries a publisher logo drawn around 76 CSS pixels wide — over any
     * threshold meant to exclude an ornament, and still nothing anyone
     * wants full screen. The markup says what it is, so it is read.
     */
    private val DECORATIVE = listOf(
        "publisher-logo",
        "ornament",
        "footnote-separator",
    )

    /**
     * Whether this resource has any image at all.
     *
     * Asked once per resource and remembered, because the answer is no
     * for most pages of most books, and a no means no script runs when
     * the reader puts two fingers on the page.
     */
    const val HAS_IMAGES_SCRIPT: String =
        "(document.images.length > 0 || !!document.querySelector('svg image'))"

    /**
     * The hit test itself.
     *
     * `currentSrc` before `src`, and this is not a detail. Given
     * `srcset="logo-2x.png 2x, logo.png 1x"` any screen above 1x displays
     * the 2x file, and `src` names the 1x one — so a viewer built from
     * `src` would open a *lower*-resolution copy of the picture the
     * reader is looking at, which is the exact opposite of the point.
     *
     * `elementFromPoint` answers with the topmost element, which over a
     * picture is often a link or a `<figure>`'s own text node rather than
     * the image, so it walks up a few levels and then, failing that, asks
     * the element what images it contains. Both are bounded: an unbounded
     * walk ends at `<body>`, which contains every image in the chapter.
     */
    fun script(x: Float, y: Float): String = """
        (function () {
          var MIN = $MIN_RENDERED_PX;
          var DECOR = ${DECORATIVE.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }};
          var el = document.elementFromPoint($x, $y);
          var img = null;
          for (var i = 0; el && i < 4; i++) {
            if (el.tagName && (el.tagName.toLowerCase() === "img" ||
                               el.tagName.toLowerCase() === "image")) {
              img = el;
              break;
            }
            var inner = el.querySelector ? el.querySelector("img, svg image") : null;
            if (inner) { img = inner; break; }
            el = el.parentElement;
          }
          if (!img) return null;

          var role = (img.getAttribute("epub:type") ||
                      img.getAttributeNS("http://www.idpf.org/2007/ops", "type") ||
                      "").toLowerCase();
          for (var d = 0; d < DECOR.length; d++) {
            if (role.indexOf(DECOR[d]) !== -1) return null;
          }

          var box = img.getBoundingClientRect();
          if (box.width < MIN || box.height < MIN) return null;

          var src = img.currentSrc || img.src ||
                    (img.href && img.href.baseVal) ||
                    img.getAttribute("xlink:href") || "";
          if (typeof src !== "string" || !src) return null;
          var abs = src;
          try { abs = new URL(src, document.baseURI).href; } catch (e) {}

          return {
            src: abs,
            alt: img.getAttribute("alt") || "",
            width: img.naturalWidth || Math.round(box.width),
            height: img.naturalHeight || Math.round(box.height)
          };
        })();
    """

    /**
     * `evaluateJavascript` hands back the JSON encoding of the value, so
     * an object arrives as an object and a miss arrives as the four
     * characters `null`.
     */
    fun parse(result: String?): Hit? {
        val raw = result?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val src = json.optString("src").takeIf { it.isNotBlank() } ?: return null
        return Hit(
            src = src,
            alt = json.optString("alt").takeIf { it.isNotBlank() },
            width = json.optInt("width"),
            height = json.optInt("height"),
        )
    }

    /** True when the resource on screen has an image anywhere in it. */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun hasImages(navigator: EpubNavigatorFragment): Boolean =
        runCatching { navigator.evaluateJavascript(HAS_IMAGES_SCRIPT) }
            .getOrNull()?.trim() == "true"

    /** The image under ([x], [y]), given in the page's own CSS pixels. */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun at(navigator: EpubNavigatorFragment, x: Float, y: Float): Hit? =
        parse(runCatching { navigator.evaluateJavascript(script(x, y)) }.getOrNull())
}
