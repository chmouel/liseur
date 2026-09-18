package com.chmouel.liseur.data.opds

import com.chmouel.liseur.data.remote.RemoteBook
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

/** A book as an OPDS catalog describes it. */
data class OpdsBook(
    /**
     * The entry's `<id>`, with calibre-web's `urn:uuid:` prefix taken
     * off when it is there.
     *
     * Opaque, and only unique within the catalog that issued it: OPDS
     * asks for an IRI and servers oblige with anything from a UUID to
     * `1`. Two unrelated catalogs can hand out the same one, which is
     * why a Custom connection namespaces it before it becomes a book's
     * identity (`OpdsIdentity`).
     *
     * The prefix is stripped rather than kept because calibre-web books
     * are already stored under the bare UUID, and `books.url` is
     * schema: keeping the whole IRI would orphan the reading position
     * of every calibre book on every phone.
     */
    val entryId: String,
    /** calibre's integer id, when the links carry one. */
    val bookId: Int?,
    val title: String,
    val author: String?,
    val coverHref: String?,
    val downloadHref: String?,
    val sizeBytes: Long?,
    val updatedAt: Long?,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    /**
     * The entry's own `xml:base`, if it declared one.
     *
     * Kept unresolved. Which absolute URL these hrefs mean depends on
     * where the document was fetched from, and the parser is given a
     * string with no idea of that.
     */
    val xmlBase: String? = null,
)

/** A link out of a feed: a sub-feed to walk, or the next page. */
data class OpdsLink(val href: String, val title: String? = null)

/**
 * The same book, said in the way the rest of the app speaks.
 *
 * OPDS keeps its own shape here because the parser is tested against
 * real feeds; only this one function knows how the two line up.
 */
fun OpdsBook.toRemote(): RemoteBook = RemoteBook(
    remoteId = entryId,
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

/** One page of a catalog feed: what is in it, and where to go next. */
data class OpdsPage(
    /** What the feed calls itself, which is what the catalog is called. */
    val title: String? = null,
    val books: List<OpdsBook>,
    /**
     * Entries that are shelves rather than books — "Authors", "By
     * series", a folder — for a walker to descend into.
     *
     * calibre-web never needs these, because it knows the one URL its
     * books are listed at. A catalog nobody has written a client for
     * has to be walked from its root, and this is the road.
     */
    val navigation: List<OpdsLink> = emptyList(),
    val nextHref: String? = null,
    /** The feed element's `xml:base`, unresolved. See [OpdsBook.xmlBase]. */
    val xmlBase: String? = null,
)

/**
 * Reads OPDS feeds: calibre-web's, and any other server's.
 *
 * Written against the DOM rather than Readium's OPDS parser so it can be
 * unit-tested on the JVM, and so the calibre-web specifics — the UUID in
 * the entry id, the integer id in link paths — stay in one place.
 *
 * It reads a feed and nothing else: no fetching, no link resolution, no
 * idea which URL the document came from. That is deliberate. Resolving
 * a link needs the response's own URL and a policy about which origins
 * may be signed, and neither belongs in a function whose only input is
 * a string of XML.
 */
object OpdsParser {

    private const val ACQUISITION_REL = "http://opds-spec.org/acquisition"
    private const val IMAGE_REL = "http://opds-spec.org/image"
    private const val THUMBNAIL_REL = "http://opds-spec.org/image/thumbnail"

    /**
     * The acquisition relations that mean "this file, now".
     *
     * The family has more members than these — `buy`, `borrow`,
     * `subscribe`, `sample` — and every one of them is left out on
     * purpose. Following a `buy` link fetches a payment page, and a
     * `sample` is a few pages of the book dressed as the book. A
     * download offered in the library has to be the whole thing, or the
     * reader finds out only after the download, in the reader, at the
     * end of chapter one.
     */
    private val DOWNLOAD_RELS = setOf(
        ACQUISITION_REL,
        "$ACQUISITION_REL/open-access",
    )

    /**
     * What a link has to say it is before Liseur offers it as a
     * download.
     *
     * Readium opens EPUB, and that is the whole list. A generic OPDS
     * entry cheerfully advertises the same book as EPUB, PDF, CBZ, an
     * audiobook and a Kindle file at once, and taking whichever came
     * first would put a file in the library that cannot be opened —
     * discovered after the download rather than before it.
     */
    private val EPUB_TYPES = setOf(
        "application/epub+zip",
        // Kobo's flavour of EPUB. calibre-web offers it beside the
        // plain one when kepubify is set up; it is still a ZIP of XHTML
        // and Readium reads it.
        "application/x-kobo-epub+zip",
    )

    /**
     * A type parameter that means the file is locked.
     *
     * An LCP-protected EPUB is served as `application/epub+zip` with a
     * marker in the parameters, so type alone would offer it. Liseur
     * cannot open one — `readium-lcp` depends on a proprietary library
     * and will never be a dependency — so it is better not listed than
     * downloaded and refused.
     */
    private val DRM_HINTS = listOf("lcp", "adept", "drm")

    /**
     * Relations that mean an entry is a shelf rather than a book.
     *
     * OPDS has no attribute saying which an entry is: the answer is in
     * what it links to. An entry pointing at another feed is a way
     * further in; anything else is a publication, including one whose
     * only links are its cover, which is a book the server has no file
     * for rather than a shelf.
     */
    private val NAVIGATION_RELS = setOf(
        "subsection",
        "collection",
        "http://opds-spec.org/sort/new",
        "http://opds-spec.org/sort/popular",
        "http://opds-spec.org/featured",
        "http://opds-spec.org/recommended",
        "http://opds-spec.org/crawlable",
        "http://opds-spec.org/facet",
    )

    private const val ATOM_TYPE = "application/atom+xml"

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
        // Well-formed is not the same as a feed. An HTML sign-in page is
        // usually valid XML, so parsing alone would let a Custom
        // connection be made against a login form and then report the
        // library as empty rather than as wrong.
        if (root.localName?.lowercase() != "feed" && root.tagName.lowercase() != "feed") {
            throw SAXException("the document is not an Atom feed but a <${root.tagName}>")
        }
        val rawEntries = root.children("entry")
        val entries = rawEntries.filterNot(::isNoRecordsFound)
        val books = entries.mapNotNull(::parseEntry).oneEntryPerPublication()
        val navigation = entries.filter(::isNavigation).mapNotNull(::navigationLink)
        val next = root.children("link")
            .firstOrNull { it.rels().contains("next") }
            ?.href()

        return OpdsPage(
            title = root.childText("title")?.trim(),
            books = books,
            navigation = navigation,
            nextHref = next,
            xmlBase = root.xmlBase(),
        )
    }

    /**
     * One entry per publication, in the order the feed gave them.
     *
     * A few catalogs list the same book more than once in a single
     * feed, one entry per set of files. Project Gutenberg does it for
     * every book it has: `urn:gutenberg:1342:2` offers the EPUB without
     * illustrations and `urn:gutenberg:1342:3` offers the one with
     * them, same title, same author, same cover. Both carry an EPUB, so
     * both read as books, and the shelf ends up holding every Gutenberg
     * book twice.
     *
     * Same work, same title, same author and the *same cover file* is
     * what counts as the same publication, and every part of that is
     * load-bearing. Two entries name the same work when their ids are a
     * numbered work in a numbered form: `urn:gutenberg:1342:2` and
     * `urn:gutenberg:1342:3` are both 1342. That is the part that makes
     * the test safe, because a catalog that hands all its books one
     * placeholder cover, or no author, or a title as generic as
     * "Untitled", would otherwise collapse a whole shelf into one book.
     * Ids of `book-1` and `book-2`, and identifiers that are complete in
     * themselves like `urn:isbn:0451450523`, name separate works and are
     * left alone however much else they share, and an entry with no
     * cover is never a duplicate of anything.
     *
     * The one with the lowest entry id survives. Which of the two it
     * is matters far less than that the answer never changes:
     * `books.url` is derived from the entry id, so a rule that picked
     * differently on the next refresh would delete the book and add it
     * back under another name, taking its reading position, its
     * highlights and its sessions with it. An id is the entry's own and
     * travels with it; where it sits in the feed is not, because OPDS
     * promises no order and a catalog is free to list the pair the
     * other way round tomorrow. Nothing that varies at all — a clock, a
     * locale, the iteration order of a set — may be consulted here.
     *
     * Lowest rather than highest only because a pair has to be settled
     * somehow. For Gutenberg it keeps `:2` over `:3`, which is the
     * edition without illustrations: the same text in a fiftieth of the
     * bytes.
     *
     * Within one feed and no further. Recognising the two halves of a
     * pair across pages would mean carrying every publication the walk
     * has ever seen, which on a catalog of some tens of thousands of
     * books is unbounded; and no catalog splits a pair that way, since
     * the point of the second entry is to sit beside the first.
     */
    private fun List<OpdsBook>.oneEntryPerPublication(): List<OpdsBook> {
        if (size < 2) return this
        val survivor = mutableMapOf<Publication, String>()
        forEach { book ->
            val key = book.publication() ?: return@forEach
            val standing = survivor[key]
            if (standing == null || book.entryId < standing) survivor[key] = book.entryId
        }
        if (survivor.isEmpty()) return this
        val taken = mutableSetOf<Publication>()
        return filter { book ->
            val key = book.publication() ?: return@filter true
            survivor[key] == book.entryId && taken.add(key)
        }
    }

    /**
     * What makes this entry the same book as another, or nothing.
     *
     * Null for an entry with no cover, which is never a duplicate of
     * anything.
     */
    private fun OpdsBook.publication(): Publication? {
        val cover = coverHref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val work = workId(entryId) ?: return null
        return Publication(
            work = work,
            title = title.trim().lowercase(),
            author = author?.trim()?.lowercase(),
            coverHref = cover,
            // Two entries under different bases mean different files by
            // the same relative href, so they are not the same cover
            // and not the same publication.
            xmlBase = xmlBase,
        )
    }

    /**
     * The work an entry id names, if it names one alongside a variant.
     *
     * The shape is a numbered work in a numbered form:
     * `urn:gutenberg:1342:2` and `urn:gutenberg:1342:3` are both 1342,
     * once in the files without illustrations and once in the files
     * with them. Both halves must be numbers, which is what keeps
     * `urn:isbn:0451450523` out — its number is the whole identifier
     * and `urn:isbn` is not a work, so two ISBNs would otherwise be
     * read as two forms of one book. Null for anything else, including
     * ids of `book-1` and `book-2` and a pair of bare numbers, so those
     * entries are separate books whatever else they share.
     */
    private fun workId(entryId: String): String? {
        val cut = entryId.lastIndexOf(':')
        if (cut <= 0 || cut == entryId.lastIndex) return null
        if (!entryId.substring(cut + 1).all(Char::isDigit)) return null
        val work = entryId.substring(0, cut)
        if (!work.last().isDigit()) return null
        // The work's own number needs a name in front of it, or the id
        // is a bare pair of numbers and neither half means anything.
        if (work.lastIndexOf(':') <= 0) return null
        return work
    }

    /** What makes two entries in one feed the same book. */
    private data class Publication(
        val work: String,
        val title: String,
        val author: String?,
        val coverHref: String,
        val xmlBase: String?,
    )

    /**
     * Whether this entry is a shelf to walk into rather than a book.
     *
     * An entry offering a download is a book whatever else it links to:
     * some servers give a publication a `collection` link to its series
     * as well, and reading that as a shelf would lose the book and send
     * the walk round in a circle.
     */
    private fun isNavigation(entry: Element): Boolean {
        val links = entry.children("link")
        if (links.any { it.rels().any { rel -> rel in DOWNLOAD_RELS } }) return false
        return links.any(::isPointer)
    }

    /**
     * Gutenberg returns an entry with id ending in `/ebooks.opds/` and title
     * "No records found." when an OPDS query yields no matching books.
     * That entry must be ignored so it is neither shelved nor followed as navigation.
     */
    private fun isNoRecordsFound(entry: Element): Boolean {
        val title = entry.childText("title")?.trim() ?: return false
        val id = entry.childText("id")?.trim() ?: ""
        return (title.equals("No records found.", ignoreCase = true) ||
            title.equals("No records found", ignoreCase = true)) &&
            (id.endsWith("/ebooks.opds/") || id.endsWith("/ebooks.opds"))
    }

    /**
     * Whether a link points somewhere else rather than at a file.
     *
     * Loose on purpose, because this is what keeps an entry out of the
     * library. Gutenberg's author feeds end with an entry linking to
     * the author's Wikipedia page — `rel="subsection"`, but
     * `type="text/html"` — and it is no more a book for not being a
     * feed. Shelving it put *See also: en.wikipedia* on the shelf.
     */
    private fun isPointer(link: Element): Boolean =
        link.rels().any { it in NAVIGATION_RELS } ||
            link.getAttribute("type").startsWith(ATOM_TYPE, ignoreCase = true)

    /**
     * Whether a link is one the walk may ask for.
     *
     * Strict, because this one costs a request to somebody's server. A
     * declared type settles it: a link that says `text/html` is a web
     * page, and asking Wikipedia for a catalog feed earned a refusal
     * that ended the whole refresh. A link that declares nothing falls
     * back to its `rel`, which is all there is to go on and is how most
     * catalogs spell navigation (#226).
     */
    private fun pointsAtAFeed(link: Element): Boolean {
        val type = link.getAttribute("type")
        if (type.isNotEmpty()) return type.startsWith(ATOM_TYPE, ignoreCase = true)
        return link.rels().any { it in NAVIGATION_RELS }
    }

    private fun navigationLink(entry: Element): OpdsLink? {
        val href = entry.children("link").firstOrNull(::pointsAtAFeed)?.href() ?: return null
        return OpdsLink(href, entry.childText("title")?.trim())
    }

    private fun parseEntry(entry: Element): OpdsBook? {
        if (isNavigation(entry)) return null
        val id = entry.childText("id") ?: return null
        // calibre-web's books are stored under the bare UUID and
        // `books.url` is schema, so the prefix keeps coming off. An id
        // of any other shape is kept whole: it is opaque, and a server
        // that says `1` means `1`.
        val entryId = id.trim().removePrefix("urn:uuid:").takeIf { it.isNotEmpty() } ?: return null
        val title = entry.childText("title") ?: return null

        val links = entry.children("link")
        val download = links.filter { it.rels().any { rel -> rel in DOWNLOAD_RELS } }
            .let(::pickDownload)
        val cover = links.firstOrNull { it.rels().contains(IMAGE_REL) }
            ?: links.firstOrNull { it.rels().contains(THUMBNAIL_REL) }

        val href = download?.href()
        val series = parseSeries(entry)

        return OpdsBook(
            entryId = entryId,
            bookId = href?.let(::bookIdFromHref) ?: cover?.getAttribute("href")?.let(::bookIdFromHref),
            title = title.trim(),
            author = entry.children("author")
                .firstNotNullOfOrNull { it.childText("name") }
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            coverHref = cover?.href(),
            downloadHref = href,
            sizeBytes = download?.getAttribute("length")?.toLongOrNull(),
            updatedAt = entry.childText("updated")?.let(::parseTimestamp),
            seriesName = series?.name,
            seriesIndex = series?.index,
            xmlBase = entry.xmlBase(),
        )
    }

    /**
     * Which of an entry's acquisition links to offer, if any.
     *
     * A link that says nothing about its type is taken at its word,
     * because plenty of servers say nothing and refusing them all would
     * empty those libraries. A link that says what it is has to say
     * EPUB: one that announces a PDF has been given its chance to be
     * useful and was not.
     */
    private fun pickDownload(acquisitions: List<Element>): Element? {
        val usable = acquisitions.filter { link ->
            val type = link.getAttribute("type").trim()
            type.isEmpty() || isReadableEpub(type)
        }
        return usable
            // Both an EPUB and a KEPUB are offered once calibre-web has
            // kepubify set up; the plain EPUB is the smaller download.
            .sortedBy { if (it.getAttribute("title").equals("EPUB", true)) 0 else 1 }
            .firstOrNull { it.href() != null }
    }

    private fun isReadableEpub(type: String): Boolean {
        val media = type.substringBefore(';').trim().lowercase()
        if (media !in EPUB_TYPES) return false
        val parameters = type.substringAfter(';', "").lowercase()
        return DRM_HINTS.none { it in parameters }
    }

    /** A link's `rel` values: the attribute may hold several. */
    private fun Element.rels(): List<String> =
        getAttribute("rel").split(' ', '\t', '\n').filter { it.isNotEmpty() }

    private fun Element.href(): String? = getAttribute("href").takeIf { it.isNotEmpty() }

    /**
     * The element's own `xml:base`, if it set one.
     *
     * Read by local name as well as by namespace: `xml:` is bound
     * implicitly, and a document builder that was handed a feed without
     * the declaration still reports the attribute under its qualified
     * name.
     */
    private fun Element.xmlBase(): String? =
        (
            getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "base").takeIf { it.isNotEmpty() }
                ?: getAttribute("xml:base").takeIf { it.isNotEmpty() }
            )?.trim()

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
