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
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
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
    seriesName = seriesName,
    seriesIndex = seriesIndex,
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

    private const val COMMENT = "<!--"
    private const val CDATA = "<![CDATA["
    private const val PI = "<?"
    private const val DOCTYPE = "<!DOCTYPE"
    private const val XML_SPACE = " \t\r\n"

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
     *
     * The scan walks the prolog construct by construct rather than
     * cutting it out and searching the text for the word. Stepping over
     * a comment without reading a character of it is what stops a
     * DOCTYPE hiding inside one, and what stops a comment that merely
     * writes the word being taken for a feed that declares one.
     */
    private fun rejectsDoctype(xml: String) {
        var i = 0
        while (i < xml.length) {
            val open = xml.indexOf('<', i)
            if (open < 0) return
            if (xml.startsWithDoctype(open)) {
                throw SAXException("the feed declares a document type, which OPDS never needs")
            }
            i = when {
                xml.startsWith(COMMENT, open) -> xml.endOf(COMMENT, "-->", open)
                // CDATA is not legal in a prolog either. It is skipped
                // rather than refused because its extent is unambiguous,
                // which keeps its body from being read as markup.
                xml.startsWith(CDATA, open) -> xml.endOf(CDATA, "]]>", open)
                xml.startsWith(PI, open) -> xml.endOf(PI, "?>", open)
                // A prolog holds the XML declaration, other processing
                // instructions, comments and one DOCTYPE, and CDATA is
                // already dealt with above. Anything else opening <! is
                // malformed whatever it is, so a stray <!ENTITY out here
                // is a feed to refuse rather than one to step over
                // carefully. Refusing it also saves measuring an
                // internal subset, whose first > can sit inside a quoted
                // entity value.
                xml.startsWith("<!", open) -> malformed()
                // Anything else opens the root element, so the prolog is
                // over and a DOCTYPE can no longer legally appear.
                else -> return
            }
        }
    }

    /**
     * Where the markup opening at [at] ends, one past its terminator.
     *
     * Not the next `>`. A comment ends at `-->`, CDATA at `]]>` and a
     * processing instruction at `?>`, and XML lets all three carry a
     * bare `>` in the body. Stopping at that one leaves the walk
     * standing inside the construct, reading its text as markup: the
     * first `<` it meets there is taken for the root element, the
     * prolog ends early, and every declaration after it falls out of
     * sight.
     *
     * The search starts past [opener] so that an empty construct,
     * `<!---->` or `<?p?>`, cannot terminate on its own opening
     * characters.
     */
    private fun String.endOf(opener: String, terminator: String, at: Int): Int {
        val end = indexOf(terminator, at + opener.length)
        // A construct that never closes is not a prolog whose shape can
        // be trusted, and the document will not parse either way.
        if (end < 0) malformed()
        return end + terminator.length
    }

    /**
     * Whether a document type is declared at [at].
     *
     * XML wants whitespace after the name, so `<!DOCTYPEfoo` declares
     * nothing. The walk still refuses it, as it refuses anything
     * opening `<!` it does not recognise, but as malformed rather than
     * as a document type.
     */
    private fun String.startsWithDoctype(at: Int): Boolean {
        if (!startsWith(DOCTYPE, at)) return false
        val after = getOrNull(at + DOCTYPE.length) ?: return false
        return after in XML_SPACE
    }

    private fun malformed(): Nothing =
        throw SAXException("the feed's prolog is not well-formed XML")

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
        val series = parseSeries(entry)

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
            seriesName = series?.name,
            seriesIndex = series?.index,
        )
    }

    /** A series line as calibre-web writes it, once it has been read. */
    private class OpdsSeries(val name: String, val index: Double?)

    /**
     * `SERIES: The Wheel of Time [1.00]`, out of the entry's summary
     * block.
     *
     * calibre-web has no element for a series: `feed.xml` writes it into
     * the human-readable `<content>` as a line of prose, between the
     * ratings and the tags. Reading it there is what gives calibre-web
     * users series at all, and it costs nothing — the feed is already
     * being walked for everything else.
     *
     * That it is prose is the risk, so the line must be labelled `SERIES`.
     * A custom column of calibre's own series type is written to the same
     * block under its own name, and is not mistaken for the real one. The
     * number is optional: a series without a number is still a series.
     * A feed that does not match is a book without a series, never an
     * error; a downloaded book has its own OPF to ask as well.
     */
    private fun parseSeries(entry: Element): OpdsSeries? {
        val content = entry.children("content").firstOrNull() ?: return null
        return directText(content)
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                SERIES_NUMBERED_LINE.matchEntire(trimmed)
                    ?: SERIES_UNNUMBERED_LINE.matchEntire(trimmed)
            }
            .firstNotNullOfOrNull { match ->
                val name = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    ?: return@firstNotNullOfOrNull null
                OpdsSeries(name, match.groupValues[2].replace(',', '.').toDoubleOrNull())
            }
    }

    /**
     * The text written straight into an element, and into the plain
     * wrappers it uses for layout — not the text of everything under it.
     *
     * calibre-web puts the book's own description in a `<p>` inside the
     * same block. A description quoting a series line would be read as
     * one if the whole subtree were flattened, so the prose lines are
     * taken where they are written and the paragraphs are stepped over.
     */
    private fun directText(element: Element): String {
        val text = StringBuilder()
        val nodes = element.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            when {
                node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE ->
                    text.append(node.textContent)
                // The xhtml wrapper the template always opens with, and
                // the line breaks between the prose lines.
                node.nodeType == Node.ELEMENT_NODE && node.localName == "div" ->
                    text.append(directText(node as Element))
                node.nodeType == Node.ELEMENT_NODE && node.localName == "br" ->
                    text.append('\n')
            }
        }
        return text.toString()
    }

    private val SERIES_NUMBERED_LINE = Regex(
        // Greedy up to the last bracketed number, so a series whose own
        // name carries brackets keeps them.
        """(?i)SERIES\s*:\s*(.+)\[\s*(-?\d+(?:[.,]\d+)?)\s*]""",
    )

    private val SERIES_UNNUMBERED_LINE = Regex(
        // This is tried after the numbered form, which keeps a bracketed
        // number out of the name when there is one.
        """(?i)SERIES\s*:\s*(.+?)(?:\s*\[\s*(-?\d+(?:[.,]\d+)?)\s*])?""",
    )

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
