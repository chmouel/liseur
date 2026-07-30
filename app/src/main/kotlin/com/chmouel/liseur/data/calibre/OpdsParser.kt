package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.remote.RemoteBook
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

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

    fun parse(xml: String): OpdsPage {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
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
