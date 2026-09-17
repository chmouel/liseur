package com.chmouel.liseur.data.opds

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One publication listed twice in one feed lands on the shelf once.
 *
 * Project Gutenberg writes every book as a pair of entries, one for the
 * files with illustrations and one for the files without, identical in
 * every other respect. Both carry an EPUB, so both used to read as
 * books, and a Gutenberg shelf held every title twice.
 *
 * The other half of these tests is the shelf that must *not* be
 * collapsed: two different books whose titles agree, and a catalog that
 * has no covers to tell them apart with.
 */
class OpdsDuplicateEntryTest {

    private fun feed(entries: String) = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>A catalog</title>
          $entries
        </feed>
    """.trimIndent()

    private fun entry(
        id: String,
        title: String = "Pride and Prejudice",
        author: String? = "Jane Austen",
        cover: String? = "/cache/epub/1342/pg1342.cover.medium.jpg",
        download: String = "/ebooks/1342.epub.noimages",
        xmlBase: String? = null,
    ) = """
        <entry${xmlBase?.let { """ xml:base="$it"""" } ?: ""}>
          <id>$id</id>
          <title>$title</title>
          ${author?.let { "<author><name>$it</name></author>" } ?: ""}
          <link rel="http://opds-spec.org/acquisition"
                href="$download" type="application/epub+zip"/>
          ${cover?.let {
        """<link rel="http://opds-spec.org/image" href="$it" type="image/jpeg"/>"""
    } ?: ""}
        </entry>
    """.trimIndent()

    /** The pair exactly as Gutenberg writes it. */
    private val gutenbergPair = feed(
        entry("urn:gutenberg:1342:2", download = "/ebooks/1342.epub.noimages") +
            entry("urn:gutenberg:1342:3", download = "/ebooks/1342.epub3.images"),
    )

    @Test
    fun `the same publication listed twice is one book`() {
        val books = OpdsParser.parse(gutenbergPair).books
        assertEquals(1, books.size)
    }

    @Test
    fun `the lowest entry id is the one that survives`() {
        val books = OpdsParser.parse(gutenbergPair).books
        assertEquals("urn:gutenberg:1342:2", books.single().entryId)
    }

    @Test
    fun `a feed that lists the pair the other way round keeps the same book`() {
        // The rule may not read the order, because OPDS does not
        // promise one. A catalog that reordered its entries between two
        // refreshes would otherwise hand the same book a second
        // identity, and `books.url` is derived from that identity.
        val reversed = feed(
            entry("urn:gutenberg:1342:3", download = "/ebooks/1342.epub3.images") +
                entry("urn:gutenberg:1342:2", download = "/ebooks/1342.epub.noimages"),
        )
        assertEquals("urn:gutenberg:1342:2", OpdsParser.parse(reversed).books.single().entryId)
    }

    @Test
    fun `a second reading of the same feed keeps the same entry id`() {
        // The whole point of the rule: `books.url` is derived from the
        // entry id, so a choice that moved between refreshes would take
        // the reader's place in the book with it.
        val first = OpdsParser.parse(gutenbergPair).books.single().entryId
        val again = OpdsParser.parse(gutenbergPair).books.single().entryId
        assertEquals(first, again)
    }

    @Test
    fun `two books sharing a title but not a cover both stay`() {
        val books = OpdsParser.parse(
            feed(
                entry("urn:x:9:1", cover = "/cover/1.jpg", download = "/get/1.epub") +
                    entry("urn:x:9:2", cover = "/cover/2.jpg", download = "/get/2.epub"),
            ),
        ).books
        assertEquals(listOf("urn:x:9:1", "urn:x:9:2"), books.map { it.entryId })
    }

    @Test
    fun `two books sharing a cover but not an author both stay`() {
        val books = OpdsParser.parse(
            feed(
                entry("urn:x:9:1", author = "Jane Austen", download = "/get/1.epub") +
                    entry("urn:x:9:2", author = "Someone Else", download = "/get/2.epub"),
            ),
        ).books
        assertEquals(listOf("urn:x:9:1", "urn:x:9:2"), books.map { it.entryId })
    }

    @Test
    fun `a catalog with no covers never collapses`() {
        // A whole shelf of untitled-looking books is exactly the case
        // that would be destroyed by a rule that did not insist on a
        // cover to compare.
        val books = OpdsParser.parse(
            feed(
                entry("urn:x:9:1", cover = null, download = "/get/1.epub") +
                    entry("urn:x:9:2", cover = null, download = "/get/2.epub") +
                    entry("urn:x:9:3", cover = null, download = "/get/3.epub"),
            ),
        ).books
        assertEquals(listOf("urn:x:9:1", "urn:x:9:2", "urn:x:9:3"), books.map { it.entryId })
    }

    @Test
    fun `the same relative cover under different bases is two books`() {
        val books = OpdsParser.parse(
            feed(
                entry("urn:x:9:1", cover = "cover.jpg", xmlBase = "https://a.example/1/") +
                    entry("urn:x:9:2", cover = "cover.jpg", xmlBase = "https://a.example/2/"),
            ),
        ).books
        assertEquals(listOf("urn:x:9:1", "urn:x:9:2"), books.map { it.entryId })
    }

    @Test
    fun `a title differing only in case or spacing is still the same book`() {
        val books = OpdsParser.parse(
            feed(
                entry("urn:x:9:1", title = "Pride and Prejudice") +
                    entry("urn:x:9:2", title = "  PRIDE AND PREJUDICE  "),
            ),
        ).books
        assertEquals(listOf("urn:x:9:1"), books.map { it.entryId })
    }

    @Test
    fun `a shared placeholder cover does not merge two works`() {
        // The case that would be destroyed by a rule reading only title,
        // author and cover: a catalog that hands every book the same
        // placeholder, with nothing in the title or the author to tell
        // them apart either.
        val books = OpdsParser.parse(
            feed(
                entry("book-1", title = "Untitled", author = null, cover = "/placeholder.png") +
                    entry("book-2", title = "Untitled", author = null, cover = "/placeholder.png") +
                    entry("book-3", title = "Untitled", author = null, cover = "/placeholder.png"),
            ),
        ).books
        assertEquals(listOf("book-1", "book-2", "book-3"), books.map { it.entryId })
    }

    @Test
    fun `entries naming different works are left alone however alike`() {
        // Same variant number, different work: two editions of one text
        // that a catalog gave matching artwork.
        val books = OpdsParser.parse(
            feed(entry("urn:gutenberg:1342:2") + entry("urn:gutenberg:42671:2")),
        ).books
        assertEquals(listOf("urn:gutenberg:1342:2", "urn:gutenberg:42671:2"), books.map { it.entryId })
    }

    @Test
    fun `two ISBNs are two books, not two forms of one`() {
        // An ISBN URN's number is the whole identifier: `urn:isbn` is
        // not a work, and reading it as one would fold a catalog that
        // reuses cover art across a series into a single entry.
        val books = OpdsParser.parse(
            feed(entry("urn:isbn:0451450523") + entry("urn:isbn:0451526538")),
        ).books
        assertEquals(listOf("urn:isbn:0451450523", "urn:isbn:0451526538"), books.map { it.entryId })
    }

    @Test
    fun `an id that is all of the work is never a variant of another`() {
        val books = OpdsParser.parse(feed(entry("1") + entry("2"))).books
        assertEquals(listOf("1", "2"), books.map { it.entryId })
    }
}
