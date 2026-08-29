package com.chmouel.liseur.reader

import com.chmouel.liseur.data.settings.fonts.UserFont
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.resource.Resource

/**
 * Serving an imported font to the reader's web view.
 *
 * Readium can only serve fonts out of the APK's own assets. Its
 * `servedAssets` setting reads like it widens that, but it is only a
 * pattern allowlist: every path it admits is still handed to a
 * `WebViewAssetLoader.AssetsPathHandler`, which knows nothing but
 * `assets/`. A file in `filesDir` is unreachable through it, and no other
 * hook exists — a `data:` URL cannot even be expressed as a Readium
 * [Url] (`AbsoluteUrl` requires a hierarchical URI, a `data:` URI is
 * opaque), and a `file://` subresource of an `https://` page is refused
 * by the web view.
 *
 * What is reachable is the *publication*. Any request the navigator makes
 * that is not aimed at the assets host is looked up in the publication's
 * container, and Liseur builds that container itself. So an imported font
 * is served as though it were part of the book: the container is wrapped
 * at open, and the font is declared with an absolute URL on the package
 * host, which [org.readium.r2.navigator.epub.css.ReadiumCss] passes
 * through untouched because `Url.resolve` returns an absolute URL as it
 * is.
 *
 * It lands same-origin with the page, so unlike the bundled fonts it
 * needs no CORS header.
 */
internal object UserFontResources {

    /**
     * Where imported fonts appear to live inside every book.
     *
     * Underscored and doubled so that it cannot plausibly collide with a
     * real EPUB entry — and if one ever does, [UserFontsContainer] is
     * consulted first and answers only for digests it actually holds, so
     * the book's own resource is still found.
     */
    const val DIR = "__liseur_fonts__"

    /**
     * Readium's package host, spelled out because `WebViewServer.PACKAGE_HOSTNAME`
     * is `internal`.
     *
     * This is the one place a Readium upgrade could break imported fonts
     * quietly: the font would 404 and the page would fall back to a
     * default face with nothing said. If that ever happens, this constant
     * is what to check first. See `docs/adr/0004-user-imported-fonts.md`.
     */
    const val PACKAGE_ORIGIN = "https://readium_package/"

    /** The URL an imported font is declared and served at. */
    fun url(font: UserFont): AbsoluteUrl =
        checkNotNull(AbsoluteUrl(absoluteHref(font.fileName))) {
            "Font file name is not URL-safe: ${font.fileName}"
        }

    private fun absoluteHref(fileName: String): String = "$PACKAGE_ORIGIN$DIR/$fileName"

    private fun relativeHref(fileName: String): String = "$DIR/$fileName"

    /**
     * The font [url] asks for, or null.
     *
     * Exact string equality against an href built from a font Liseur
     * itself stored, rather than any kind of path resolution. Nothing is
     * ever joined onto the font directory, so there is no path for a
     * traversal to traverse: `..`, `%2e%2e`, an encoded separator, a
     * doubled slash or a foreign host all simply fail to equal any
     * candidate. Requiring `https://readium_package/` as a literal prefix
     * settles the scheme, the host, the absence of a port and the absence
     * of user-info in one comparison.
     *
     * The fragment and the query are dropped first rather than refused.
     * `Publication.get` retries a lookup with the query removed, so a
     * rule that turned one away would break Readium's own second attempt
     * at a request that was legitimate to begin with. They are not the
     * boundary; the exact match against a stored font is.
     */
    fun match(url: Url, fonts: List<UserFont>): UserFont? {
        val href = url.removeFragment().removeQuery().toString()
        if (href.length > MAX_HREF) return null
        return fonts.firstOrNull { font ->
            href == absoluteHref(font.fileName) || href == relativeHref(font.fileName)
        }
    }

    private const val MAX_HREF = 256
}

/**
 * The imported fonts, offered to the navigator as part of every book.
 *
 * [fonts] is read on each call rather than captured once, so a font
 * imported while a book is open is servable the moment the navigator asks
 * for it. That is what lets an import reflow the page the reader is on
 * instead of waiting for the next book.
 */
internal class UserFontsContainer(
    private val fonts: () -> List<UserFont>,
) : Container<Resource> {

    override val entries: Set<Url>
        get() = fonts().mapNotNullTo(mutableSetOf()) { Url("${UserFontResources.DIR}/${it.fileName}") }

    override fun get(url: Url): Resource? =
        UserFontResources.match(url, fonts())
            ?.takeIf { it.file.isFile }
            ?.let { FileResource(it.file) }

    /** Nothing to close: every entry is opened per request and owned by the caller. */
    override fun close() {}
}
