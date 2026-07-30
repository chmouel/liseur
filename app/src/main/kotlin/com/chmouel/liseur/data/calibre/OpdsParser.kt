package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteBook
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

/** A book as the calibre-web catalog describes it. */
data class OpdsBook(
    val uuid: String,
    val bookId: Int?,
    val title: String,
    val author: String?,
    val coverHref: String?,
    val downloadHref: String?,
    val sizeBytes: Long?,
    val updatedAt: Long?,
)

/**
 * The same book, said in the way the rest of the app speaks.
 *
 * OPDS keeps its own shape here because the parser is tested against
 * real feeds; only this one function knows how the two line up.
 */
fun OpdsBook.toRemote(): RemoteBook = RemoteBook(
    remoteId = uuid,
    title = title,
    author = author,
    coverHref = coverHref,
    downloadHref = downloadHref,
    sizeBytes = sizeBytes,
    updatedAt = updatedAt,
    calibreBookId = bookId,
)

/** One page of a catalog feed, and where the next one is. */
data class OpdsPage(
    val books: List<OpdsBook>,
    val nextHref: String?,
)

/**
 * Reads calibre-web's OPDS feeds.
 *
 * Written against the DOM rather than Readium's OPDS parser so it can be
 * unit-tested on the JVM, and so the calibre-web specifics — the UUID in
 * the entry id, the integer id in link paths — stay in one place.
 */
object OpdsParser {

    private const val ACQUISITION_REL = "http://opds-spec.org/acquisition"
    private const val IMAGE_REL = "http://opds-spec.org/image"
    private const val THUMBNAIL_REL = "http://opds-spec.org/image/thumbnail"

    /**
     * A parser that will not go and fetch things on the feed's behalf.
     *
     * A stock `DocumentBuilderFactory` honours a DOCTYPE, and an entity
     * declared in one can name a local file or a URL. A feed that
     * declared `file:///data/data/.../shared_prefs/...` would have its
     * contents pulled into the document we then parse, and one naming a
     * host on the same network would have us fetch it -- from inside
     * whatever network the phone is on, which is the whole point of an
     * SSRF. OPDS has no legitimate use for any of it.
     *
     * Each switch is asked for on its own and a refusal is survivable,
     * because which of them an implementation honours varies and
     * Android's parser is not the one these tests run against. What
     * makes that safe is [rejectsDoctype] below: the guarantee does not
     * rest on any of these being granted.
     */
    private fun safeDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            bestEffort("http://apache.org/xml/features/disallow-doctype-decl", true)
            bestEffort("http://xml.org/sax/features/external-general-entities", false)
            bestEffort("http://xml.org/sax/features/external-parameter-entities", false)
            bestEffort("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            bestEffort(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            isNamespaceAware = true
        }

    private fun DocumentBuilderFactory.bestEffort(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
    }

    /**
     * Refuses a feed that declares a document type at all.
     *
     * This is the guarantee, rather than the factory settings above: a
     * parser that does not implement `disallow-doctype-decl` accepts the
     * request to set it by throwing, which leaves nothing enforced. XML
     * permits a DOCTYPE only in the prolog and nowhere else, so looking
     * for one before parsing costs a scan of a few hundred bytes and
     * cannot be talked out of by anything later in the document. A feed
     * that trips it is refused as malformed, which is what it is: OPDS
     * has no document type to declare.
     */
    private fun rejectsDoctype(xml: String) {
        if (prologOf(xml).contains("<!DOCTYPE")) {
            throw SAXException("the feed declares a document type, which OPDS never needs")
        }
    }

    /**
     * Everything before the root element opens.
     *
     * A DOCTYPE is only ever legal here, so this is the only place worth
     * looking -- and looking only here means a document that merely
     * quotes the word, in a title or a CDATA block, is not mistaken for
     * one that declares it.
     */
    private fun prologOf(xml: String): String {
        var i = 0
        while (i < xml.length) {
            val open = xml.indexOf('<', i)
            if (open < 0) return xml
            // Anything that is not <? or <! is the root element.
            val next = xml.getOrNull(open + 1)
            if (next != '?' && next != '!') return xml.take(open)
            val close = xml.indexOf('>', open)
            if (close < 0) return xml
            i = close + 1
        }
        return xml
    }

    fun parse(xml: String): OpdsPage {
        rejectsDoctype(xml)
        val document = safeDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

        val root = document.documentElement
        val books = root.children("entry").mapNotNull(::parseEntry)
        val next = root.children("link")
            .firstOrNull { it.getAttribute("rel") == "next" }
            ?.getAttribute("href")
            ?.takeIf { it.isNotEmpty() }

        return OpdsPage(books, next)
    }

    private fun parseEntry(entry: Element): OpdsBook? {
        val id = entry.childText("id") ?: return null
        val uuid = id.substringAfter("urn:uuid:", "").takeIf { it.isNotEmpty() } ?: return null
        val title = entry.childText("title") ?: return null

        val links = entry.children("link")
        val download = links
            .filter { it.getAttribute("rel") == ACQUISITION_REL }
            // Both an EPUB and a KEPUB are offered once calibre-web has
            // kepubify set up; the plain EPUB is the smaller download.
            .sortedBy { if (it.getAttribute("title").equals("EPUB", true)) 0 else 1 }
            .firstOrNull()
        val cover = links.firstOrNull { it.getAttribute("rel") == IMAGE_REL }
            ?: links.firstOrNull { it.getAttribute("rel") == THUMBNAIL_REL }

        val href = download?.getAttribute("href")?.takeIf { it.isNotEmpty() }

        return OpdsBook(
            uuid = uuid,
            bookId = href?.let(::bookIdFromHref) ?: cover?.getAttribute("href")?.let(::bookIdFromHref),
            title = title.trim(),
            author = entry.children("author")
                .firstNotNullOfOrNull { it.childText("name") }
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            coverHref = cover?.getAttribute("href")?.takeIf { it.isNotEmpty() },
            downloadHref = href,
            sizeBytes = download?.getAttribute("length")?.toLongOrNull(),
            updatedAt = entry.childText("updated")?.let(::parseTimestamp),
        )
    }

    /** calibre's integer id, as it appears in `/opds/download/74/epub/`. */
    private fun bookIdFromHref(href: String): Int? =
        href.trim('/').split('/').firstNotNullOfOrNull { it.toIntOrNull() }

    /** Atom timestamps, e.g. `2026-07-26T10:26:49+00:00`. */
    private fun parseTimestamp(value: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun Element.children(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == name) {
                result += node as Element
            }
        }
        return result
    }

    private fun Element.childText(name: String): String? =
        children(name).firstOrNull()?.textContent?.takeIf { it.isNotBlank() }
}
